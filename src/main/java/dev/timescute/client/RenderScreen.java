package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Configuração simples da renderização: qualidade, fps, resolução, nome e FFmpeg. */
public final class RenderScreen extends Screen {
    private static final int W = 340, H = 284;
    private static final int TEXT = 0xFFE6EAF2, MUTED = 0xFF8A93A6, ERROR = 0xFFFF6B6B, OK = 0xFF6BD68B, WARN = 0xFFE8A33D;

    private final Screen parent;
    private final Settings st = ModSettings.get();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timescute-ffmpeg-check");
        t.setDaemon(true);
        return t;
    });

    private ButtonWidget bPreset, bFps, bRes, bFreeze, bStart;
    private TextFieldWidget name, ffmpeg;
    private volatile FfmpegTool.Found found;
    private volatile boolean checking = true;
    private String message = "";
    private RenderPreset tipPreset;

    public RenderScreen(Screen parent) {
        super(Text.literal("Renderizar vídeo"));
        this.parent = parent;
    }

    private int x0() { return (width - W) / 2; }
    private int y0() { return (height - H) / 2; }

    @Override
    protected void init() {
        int x = x0(), y = y0();
        bPreset = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> { st.renderPreset = RenderPreset.byId(st.renderPreset).next().id; save(); })
                .dimensions(x + 10, y + 24, W - 20, 20).build());
        bFps = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> { st.renderFps = nextOf(Settings.FPS_CHOICES, st.renderFps); save(); })
                .dimensions(x + 10, y + 48, (W - 24) / 2, 20).tooltip(Tooltip.of(Text.literal("Quadros por segundo do vídeo"))).build());
        bRes = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> { st.renderHeight = nextOf(Settings.HEIGHT_CHOICES, st.renderHeight); save(); })
                .dimensions(x + 14 + (W - 24) / 2, y + 48, (W - 24) / 2, 20)
                .tooltip(Tooltip.of(Text.literal("A imagem é capturada no tamanho da janela do jogo. Para mais qualidade, aumente a janela."))).build());

        bFreeze = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> { st.freezeWorld = !st.freezeWorld; save(); })
                .dimensions(x + 10, y + 72, W - 20, 20)
                .tooltip(Tooltip.of(Text.literal("Em um jogador, para o servidor durante a renderização: o vídeo sai exato, com o mundo parado. Desligado, o mundo segue ao vivo (a velocidade dos mobs varia)."))).build());

        name = new TextFieldWidget(textRenderer, x + 10, y + 114, W - 20, 18, Text.literal("Nome"));
        name.setMaxLength(48);
        name.setText(defaultName());
        addDrawableChild(name);

        ffmpeg = new TextFieldWidget(textRenderer, x + 10, y + 150, W - 92, 18, Text.literal("FFmpeg"));
        ffmpeg.setMaxLength(260);
        ffmpeg.setText(st.ffmpegPath);
        ffmpeg.setSuggestion(st.ffmpegPath.isEmpty() ? "automático (procura no PATH)" : "");
        ffmpeg.setChangedListener(s -> ffmpeg.setSuggestion(s.isEmpty() ? "automático (procura no PATH)" : ""));
        addDrawableChild(ffmpeg);
        addDrawableChild(ButtonWidget.builder(Text.literal("Testar"), b -> checkFfmpeg()).dimensions(x + W - 76, y + 149, 66, 20).build());

        bStart = addDrawableChild(ButtonWidget.builder(Text.literal("Renderizar"), b -> start()).dimensions(x + 10, y + H - 30, 160, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Fechar"), b -> close()).dimensions(x + W - 170, y + H - 30, 160, 20).build());

        checkFfmpeg();
    }

    private static String defaultName() {
        return "video";
    }

    private static int nextOf(int[] choices, int cur) {
        for (int i = 0; i < choices.length; i++) if (choices[i] == cur) return choices[(i + 1) % choices.length];
        return choices[0];
    }

    private void save() { ModSettings.save(); }

    private void checkFfmpeg() {
        st.ffmpegPath = ffmpeg == null ? st.ffmpegPath : ffmpeg.getText().trim();
        save();
        checking = true;
        found = null;
        String path = st.ffmpegPath;
        io.submit(() -> {
            Optional<FfmpegTool.Found> f = FfmpegTool.locate(path);
            found = f.orElse(null);
            checking = false;
        });
    }

    private String problem() {
        if (RenderSession.get().isActive()) return "Já existe uma renderização em andamento.";
        if (client.world == null) return "Entre em um mundo para renderizar.";
        if (CameraDirector.get().timeline.path().size() < 2) return "Crie pelo menos 2 keyframes no editor (K).";
        if (checking) return "Procurando o FFmpeg...";
        if (found == null) return "FFmpeg não encontrado.";
        return null;
    }

    private void start() {
        st.ffmpegPath = ffmpeg.getText().trim();
        save();
        String err = RenderSession.get().start(client, st, name.getText(), found);
        if (err != null) message = err;
        // sucesso: a RenderSession fecha esta tela (setScreen(null))
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx);
        int x = x0(), y = y0();
        ctx.fill(x, y, x + W, y + H, 0xEE12151C);
        ctx.drawBorder(x, y, W, H, 0xFF2A3142);
        ctx.drawCenteredTextWithShadow(textRenderer, "Renderizar trajeto para vídeo", width / 2, y + 8, TEXT);

        RenderPreset preset = RenderPreset.byId(st.renderPreset);
        bPreset.setMessage(Text.literal("Qualidade: " + preset.label));
        if (preset != tipPreset) { // só recria o tooltip quando muda (recriar todo quadro reiniciaria a espera do mouse)
            bPreset.setTooltip(Tooltip.of(Text.literal(preset.description)));
            tipPreset = preset;
        }
        bFps.setMessage(Text.literal(st.renderFps + " fps"));
        var fb = client.getFramebuffer();
        int[] out = FfmpegCommand.outputSize(fb.viewportWidth, fb.viewportHeight, st.renderHeight);
        bRes.setMessage(Text.literal(st.renderHeight == 0 ? "Tamanho da janela" : st.renderHeight + "p"));

        bFreeze.setMessage(Text.literal("Congelar o mundo (um jogador): " + (st.freezeWorld ? "sim" : "não")));
        ctx.drawTextWithShadow(textRenderer, "Nome do arquivo", x + 10, y + 102, MUTED);
        ctx.drawTextWithShadow(textRenderer, "Caminho do FFmpeg (opcional)", x + 10, y + 138, MUTED);

        // resumo
        int ly = y + 176;
        var keys = CameraDirector.get().timeline.path().all();
        if (keys.size() >= 2) {
            long a = keys.get(0).timeMs(), b = keys.get(keys.size() - 1).timeMs();
            try {
                RenderPlan p = new RenderPlan(a, b, st.renderFps, CameraDirector.get().timeline.speed());
                ctx.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "Trajeto: %s  |  %d quadros  |  saída %dx%d",
                        Fmt.duration(b - a), p.totalFrames(), out[0], out[1]), x + 10, ly, TEXT);
            } catch (IllegalArgumentException e) {
                ctx.drawTextWithShadow(textRenderer, "Trajeto muito curto.", x + 10, ly, ERROR);
            }
        } else {
            ctx.drawTextWithShadow(textRenderer, "Trajeto: crie keyframes no editor (K)", x + 10, ly, WARN);
        }
        ctx.drawTextWithShadow(textRenderer, "Arquivo: " + preset.extension.toUpperCase(Locale.ROOT) + ", sem áudio  |  Pasta: timescute/renders", x + 10, ly + 12, MUTED);

        FfmpegTool.Found f = found;
        if (checking) ctx.drawTextWithShadow(textRenderer, "FFmpeg: procurando...", x + 10, ly + 26, MUTED);
        else if (f != null) ctx.drawTextWithShadow(textRenderer, "FFmpeg: " + ellipsize(f.version(), W - 20), x + 10, ly + 26, OK);
        else {
            ctx.drawTextWithShadow(textRenderer, "FFmpeg não encontrado.", x + 10, ly + 26, ERROR);
            ctx.drawTextWithShadow(textRenderer, "Instale (ex.: winget install Gyan.FFmpeg) ou informe o caminho acima.", x + 10, ly + 38, MUTED);
        }

        String problem = problem();
        bStart.active = problem == null;
        String line = !message.isEmpty() ? message : problem != null ? problem : "Tudo pronto. Durante a renderização, abrir o menu pausa; no menu há o botão Cancelar.";
        int color = !message.isEmpty() || (problem != null && !checking) ? ERROR : MUTED;
        ctx.drawTextWithShadow(textRenderer, ellipsize(line, W - 20), x + 10, y + H - 46, color);

        String last = RenderSession.get().lastResult();
        if (!last.isEmpty()) ctx.drawTextWithShadow(textRenderer, ellipsize("Último: " + last, W - 20), x + 10, y + H - 58, RenderSession.get().lastWasOk() ? OK : WARN);

        super.render(ctx, mx, my, delta);
    }

    private String ellipsize(String s, int maxPx) {
        if (textRenderer.getWidth(s) <= maxPx) return s;
        while (s.length() > 4 && textRenderer.getWidth(s + "...") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && bStart.active && !ffmpeg.isFocused()) { start(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void removed() { io.shutdownNow(); }

    @Override
    public void close() { client.setScreen(parent); }
}
