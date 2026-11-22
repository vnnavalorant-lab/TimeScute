package dev.timescute.client;

import dev.timescute.core.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Renderiza o trajeto de câmera para vídeo, um quadro por vez.
 *
 * <p>Como funciona: a cada quadro de tela o jogo avança uma fração FIXA de tick ({@link RenderTickCounterMixin}),
 * a câmera é posta no instante exato do trajeto, o replay avança o tempo correspondente (seguindo a trilha de
 * velocidade) e, no fim do quadro, a imagem do framebuffer é lida e enviada ao FFmpeg. Se o mundo ainda não
 * estiver pronto (chunks sendo montados, pacotes atrasados), o quadro é segurado: o tempo não anda e nada é capturado.
 */
public final class RenderSession {
    private static final RenderSession INSTANCE = new RenderSession();
    public static RenderSession get() { return INSTANCE; }

    private static final int MAX_HOLD_FRAMES = 300; // ~5 s esperando o terreno antes de capturar assim mesmo

    public enum State { IDLE, RENDERING, FINISHING }

    private volatile State state = State.IDLE;
    private RenderPlan plan;
    private FrameWriter writer;
    private Path outFile;
    private ByteBuffer pixelBuf;
    private int w, h;
    private long frame, lastTitleFrame;
    private int hold;
    private float pendingTicks;
    private boolean prevHudHidden, freezeServer;
    private long startedNanos;

    private volatile String lastResult = "";
    private volatile boolean lastOk;

    private RenderSession() { }

    public boolean isActive() { return state != State.IDLE; }
    public boolean isRendering() { return state == State.RENDERING; }

    /** O servidor integrado deve parar de rodar neste instante? (um jogador, durante a renderização) */
    public boolean shouldFreezeServer() { return state == State.RENDERING && freezeServer; }
    public String lastResult() { return lastResult; }
    public boolean lastWasOk() { return lastOk; }

    public static Path rendersDir() {
        return FabricLoader.getInstance().getGameDir().resolve("timescute").resolve("renders");
    }

    /** Usado pelo mixin do contador de ticks: NaN = não estamos renderizando. */
    public float tickAdvance() { return state == State.RENDERING ? pendingTicks : Float.NaN; }

    // ================= início =================

    /** @return mensagem de erro, ou {@code null} se a renderização começou. */
    public String start(MinecraftClient mc, Settings st, String name, FfmpegTool.Found found) {
        if (state != State.IDLE) return "Já existe uma renderização em andamento.";
        if (mc.world == null || mc.player == null) return "Entre em um mundo para renderizar.";

        CameraDirector dir = CameraDirector.get();
        List<Keyframe> keys = dir.timeline.path().all();
        if (keys.size() < 2) return "O trajeto precisa de pelo menos 2 keyframes.";
        RenderPlan p;
        try {
            p = new RenderPlan(keys.get(0).timeMs(), keys.get(keys.size() - 1).timeMs(), st.renderFps, new SpeedTrack());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        if (found == null) found = FfmpegTool.locate(st.ffmpegPath).orElse(null);
        if (found == null) return "FFmpeg não encontrado. Instale-o ou informe o caminho na tela de renderização.";

        Framebuffer fb = mc.getFramebuffer();
        w = fb.viewportWidth;
        h = fb.viewportHeight;
        if (w < 2 || h < 2) return "A janela do jogo está pequena demais.";

        RenderPreset preset = RenderPreset.byId(st.renderPreset);
        try {
            Files.createDirectories(rendersDir());
            outFile = uniqueOutput(Fmt.fileName(name), preset.extension);
            writer = FrameWriter.start(FfmpegCommand.build(found.path(), w, h, st.renderFps, st.renderHeight, preset, outFile.toString()));
        } catch (IOException e) {
            return "Não foi possível iniciar o FFmpeg: " + e.getMessage();
        }

        plan = p;
        pixelBuf = MemoryUtil.memAlloc(w * h * 3);
        prevHudHidden = mc.options.hudHidden;
        mc.options.hudHidden = true; // quadros limpos: sem interface
        freezeServer = st.freezeWorld && mc.getServer() != null; // um jogador: o servidor para, e o vídeo sai exato
        dir.pause();
        dir.setRendering(true);
        dir.setPlayhead(plan.outputTimeMs(0));
        ActorManager.get().prime(mc); // atores já no lugar no quadro 0 (o primeiro tick do jogo só vem alguns quadros depois)
        frame = 0;
        lastTitleFrame = 0;
        hold = 0;
        pendingTicks = 0f;
        startedNanos = System.nanoTime();
        state = State.RENDERING;
        mc.setScreen(null);
        return null;
    }

    private static Path uniqueOutput(String base, String ext) {
        Path p = rendersDir().resolve(base + "." + ext);
        for (int i = 2; Files.exists(p); i++) p = rendersDir().resolve(base + "-" + i + "." + ext);
        return p;
    }

    // ================= por quadro =================

    /** Depois do quadro pronto: captura e prepara o próximo. */
    public void afterFrame(MinecraftClient mc) {
        if (state != State.RENDERING) return;
        try {
            if (mc.world == null || mc.player == null) { cancel("Você saiu do mundo: renderização cancelada."); return; }

            if (mc.currentScreen != null) { // menu aberto (ou janela sem foco): segura sem perder nada
                pendingTicks = 0f;
                setTitle(mc, "TimeScute: renderização PAUSADA (feche o menu para continuar)");
                return;
            }
            Framebuffer fb = mc.getFramebuffer();
            if (fb.viewportWidth != w || fb.viewportHeight != h) {
                cancel("A janela foi redimensionada durante a renderização.");
                return;
            }

            boolean terrainReady = mc.worldRenderer == null || mc.worldRenderer.isTerrainRenderComplete();
            if (!terrainReady && hold < MAX_HOLD_FRAMES) { // o terreno ainda está sendo montado
                hold++;
                pendingTicks = 0f;
                return;
            }
            hold = 0;

            writer.submit(readPixels(fb)); // pode esperar o FFmpeg (contrapressão) ou lançar se ele morreu
            frame++;
            if (frame >= plan.totalFrames()) { finish(mc); return; }

            CameraDirector.get().setPlayhead(plan.outputTimeMs(frame));
            pendingTicks = (float) plan.tickAdvance(frame);

            if (frame - lastTitleFrame >= 10) { lastTitleFrame = frame; setTitle(mc, progressTitle()); }
        } catch (IOException e) {
            cancel("Erro no FFmpeg: " + e.getMessage());
        } catch (Throwable t) {
            cancel("Erro inesperado na renderização: " + t);
        }
    }

    private byte[] readPixels(Framebuffer fb) {
        int prev = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        pixelBuf.clear();
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixelBuf);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev);
        byte[] out = new byte[w * h * 3];
        pixelBuf.clear();
        pixelBuf.get(out);
        return out;
    }

    private String progressTitle() {
        long total = plan.totalFrames();
        double elapsed = (System.nanoTime() - startedNanos) / 1e9;
        double perFrame = elapsed / Math.max(1, frame);
        long eta = (long) (perFrame * (total - frame));
        return String.format(Locale.ROOT, "TimeScute: renderizando %d%%  (%d/%d)  faltam ~%s  |  menu abre = pausa",
                (int) (plan.progress(frame) * 100), frame, total, Fmt.duration(eta * 1000));
    }

    private static void setTitle(MinecraftClient mc, String title) {
        GLFW.glfwSetWindowTitle(mc.getWindow().getHandle(), title);
    }

    // ================= fim =================

    private void restore(MinecraftClient mc) {
        CameraDirector.get().setRendering(false);
        mc.options.hudHidden = prevHudHidden;
        if (pixelBuf != null) { MemoryUtil.memFree(pixelBuf); pixelBuf = null; }
        mc.updateWindowTitle();
    }

    private void finish(MinecraftClient mc) {
        state = State.FINISHING;
        restore(mc);
        FrameWriter fw = writer;
        Path out = outFile;
        long frames = frame;
        double seconds = (System.nanoTime() - startedNanos) / 1e9;
        Thread t = new Thread(() -> {
            String msg;
            boolean ok;
            try {
                int code = fw.finish();
                if (code == 0 && fw.error() == null && Files.exists(out)) {
                    ok = true;
                    msg = "Vídeo salvo: " + out + " (" + Fmt.size(Files.size(out)) + ", " + frames
                            + " quadros em " + Fmt.duration((long) (seconds * 1000)) + ")";
                } else {
                    ok = false;
                    msg = "O FFmpeg terminou com erro (código " + code + "). " + fw.stderrTail();
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                ok = false;
                msg = "Falha ao finalizar o vídeo: " + e;
            }
            try { report(mc, ok, msg); } finally { state = State.IDLE; }
        }, "timescute-render-finish");
        t.setDaemon(true);
        t.start();
    }

    /** Cancela e apaga o arquivo parcial. */
    public void cancel(String reason) {
        if (state != State.RENDERING) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        state = State.FINISHING;
        restore(mc);
        FrameWriter fw = writer;
        Path out = outFile;
        fw.abort();
        Thread t = new Thread(() -> {
            try { fw.finish(); } catch (IOException | InterruptedException | RuntimeException ignored) { /* já cancelado */ }
            try { Files.deleteIfExists(out); } catch (IOException ignored) { /* arquivo em uso */ }
            try { report(mc, false, reason); } finally { state = State.IDLE; }
        }, "timescute-render-cancel");
        t.setDaemon(true);
        t.start();
    }

    private void report(MinecraftClient mc, boolean ok, String msg) {
        lastResult = msg;
        lastOk = ok;
        System.out.println("[TimeScute] " + msg);
        mc.execute(() -> {
            if (mc.inGameHud != null) {
                mc.inGameHud.getChatHud().addMessage(Text.literal("[TimeScute] " + msg));
                mc.inGameHud.setOverlayMessage(Text.literal(ok ? "TimeScute: vídeo pronto!" : "TimeScute: renderização não concluída"), false);
            }
        });
    }

    public Optional<RenderPlan> plan() { return Optional.ofNullable(plan); }
}
