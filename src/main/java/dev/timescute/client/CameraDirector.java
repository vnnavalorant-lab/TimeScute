package dev.timescute.client;

import dev.timescute.core.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.Optional;

/** Estado global do editor: timeline, seleção, playhead, reprodução, arquivos e avisos. */
public final class CameraDirector {
    private static final CameraDirector INSTANCE = new CameraDirector();
    public static CameraDirector get() { return INSTANCE; }

    /** Quanto o playhead avança após cada captura feita fora do editor. */
    public static final long CAPTURE_STEP_MS = 2000;
    private static final long TOAST_MS = 4000;

    public final Timeline timeline = new Timeline();
    public final Selection selection = new Selection();

    private long playheadMs;
    private boolean playing;
    private boolean editorOpen;
    private boolean rendering;
    private boolean cameraOverride = true;
    private boolean freeRun;
    private long lastNanos;

    private boolean showGuides = true;
    private String pathName; // nome do trajeto aberto/salvo (null = ainda sem nome)

    private String toast = "";
    private boolean toastError;
    private long toastUntil;

    private CameraDirector() {
        timeline.onChange(() -> selection.retainExisting(timeline.path()));
    }

    // ---------- reprodução ----------

    public long playheadMs() { return playheadMs; }
    public boolean isPlaying() { return playing; }
    public void setEditorOpen(boolean open) { editorOpen = open; }
    /** Durante a renderização o tempo não anda sozinho: quem conduz é a RenderSession, quadro a quadro. */
    public void setRendering(boolean r) { rendering = r; if (r) playing = false; }
    public boolean isRendering() { return rendering; }

    /** Se false, o tempo corre (os atores atuam) mas a câmera continua sendo a do jogador (usado ao gravar um ator). */
    public void setCameraOverride(boolean on) { cameraOverride = on; }

    /** Há algo acontecendo no palco (atores devem aparecer)? */
    public boolean isStageActive() { return editorOpen || playing || rendering; }

    /** Começa a rodar a timeline do instante atual. */
    public void startStage(boolean withCamera) { startStage(withCamera, false); }

    /** @param freeRun não para no fim do conteúdo (gravando, o conteúdo ainda está sendo criado) */
    public void startStage(boolean withCamera, boolean freeRun) {
        cameraOverride = withCamera;
        this.freeRun = freeRun;
        playing = true;
        lastNanos = System.nanoTime();
    }

    public void setPlayhead(long ms) { playheadMs = Math.max(0, ms); }
    public void pause() { playing = false; freeRun = false; cameraOverride = true; }

    public void togglePlay() {
        if (timeline.contentEndMs() <= 0) return;
        if (playing) { playing = false; return; }
        if (playheadMs >= timeline.contentEndMs()) playheadMs = 0;
        startStage(true);
    }

    /** Chamado uma vez por frame (pelo mixin da câmera). */
    public void advance() {
        if (rendering || !playing) return;
        long now = System.nanoTime();
        playheadMs += (now - lastNanos) / 1_000_000L;
        lastNanos = now;
        long end = timeline.contentEndMs();
        if (!freeRun && playheadMs >= end) { playheadMs = end; playing = false; cameraOverride = true; }
    }

    /** Câmera forçada neste frame, se houver preview ou reprodução. */
    public Optional<CameraState> current() {
        if (!cameraOverride || !(editorOpen || playing || rendering)) return Optional.empty();
        return timeline.path().sample(playheadMs);
    }

    /** Lê a câmera REAL do jogador (não a de preview). */
    public CameraState captureCurrent(MinecraftClient mc) {
        PlayerEntity p = mc.player;
        Vec3d eye = p.getCameraPosVec(1f);
        return new CameraState(eye.x, eye.y, eye.z, p.getYaw(), p.getPitch(), 0, mc.options.getFov().getValue());
    }

    // ---------- guias 3D ----------

    // ---------- modo corte ----------

    private boolean cutMode;
    public boolean cutMode() { return cutMode; }
    public void toggleCutMode() { cutMode = !cutMode; }

    /** Captura a câmera real no playhead: como corte (câmera parada que salta) ou keyframe normal (que viaja), conforme o modo. */
    public Keyframe captureKeyframe(MinecraftClient mc) {
        CameraState s = captureCurrent(mc);
        return cutMode ? timeline.captureCut(playheadMs(), s) : timeline.capture(playheadMs(), s);
    }

    public boolean showGuides() { return showGuides; }
    public void toggleGuides() { showGuides = !showGuides; }

    // ---------- avisos (aparecem no rodapé do editor) ----------

    public void toast(String msg, boolean error) {
        toast = msg;
        toastError = error;
        toastUntil = System.currentTimeMillis() + TOAST_MS;
    }

    public boolean hasToast() { return System.currentTimeMillis() < toastUntil; }
    public String toastText() { return toast; }
    public boolean toastIsError() { return toastError; }

    // ---------- arquivos ----------

    public Path pathsDir() { return FabricLoader.getInstance().getConfigDir().resolve("timescute").resolve("paths"); }

    public String pathName() { return pathName; }
    public void setPathName(String n) { pathName = n; }

    public void autosave() {
        try {
            PathFiles.save(pathsDir(), PathFiles.AUTOSAVE, timeline);
        } catch (Exception e) {
            System.err.println("[TimeScute] Falha no autosave: " + e);
        }
    }

    /** Restaura a sessão anterior (silencioso; ignora arquivo ausente ou inválido). */
    public void loadAutosave() {
        try {
            if (!PathFiles.exists(pathsDir(), PathFiles.AUTOSAVE)) return;
            PathJson.Data d = PathFiles.read(pathsDir(), PathFiles.AUTOSAVE);
            timeline.resetTo(d.keys(), d.speeds(), d.actors());
        } catch (Exception e) {
            System.err.println("[TimeScute] Autosave ignorado: " + e);
        }
    }
}
