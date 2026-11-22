package dev.timescute.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Configurações do TimeScute (settings.json). Nunca falha ao carregar: usa os padrões. */
public final class Settings {
    // renderização de vídeo
    public String ffmpegPath = "";     // vazio = procura no PATH
    public String renderPreset = RenderPreset.PADRAO.id;
    public int renderFps = 30;
    public int renderHeight = 0;       // 0 = tamanho da janela
    /** Em um jogador, congela o servidor durante a renderização para o vídeo sair exato. */
    public boolean freezeWorld = true;

    public static final int[] FPS_CHOICES = {24, 30, 48, 60};
    public static final int[] HEIGHT_CHOICES = {0, 480, 720, 1080, 1440, 2160};

    public void clamp() {
        renderFps = nearest(FPS_CHOICES, renderFps);
        renderHeight = nearest(HEIGHT_CHOICES, renderHeight);
        renderPreset = RenderPreset.byId(renderPreset).id;
        if (ffmpegPath == null) ffmpegPath = "";
        ffmpegPath = ffmpegPath.trim();
    }

    private static int nearest(int[] choices, int v) {
        int best = choices[0];
        for (int c : choices) if (Math.abs(c - v) < Math.abs(best - v)) best = c;
        return best;
    }

    @SuppressWarnings("unchecked")
    public static Settings load(Path file) {
        Settings s = new Settings();
        try {
            if (!Files.exists(file)) return s;
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> m0) {
                Map<String, Object> m = (Map<String, Object>) m0;
                s.ffmpegPath = m.get("ffmpegPath") instanceof String f ? f : s.ffmpegPath;
                s.renderPreset = m.get("renderPreset") instanceof String r ? r : s.renderPreset;
                s.renderFps = m.get("renderFps") instanceof Double d ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d)) : s.renderFps;
                s.renderHeight = m.get("renderHeight") instanceof Double d ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d)) : s.renderHeight;
                s.freezeWorld = m.get("freezeWorld") instanceof Boolean b ? b : s.freezeWorld;
            }
        } catch (IOException | IllegalArgumentException e) {
            return new Settings();
        }
        s.clamp();
        return s;
    }

    public void save(Path file) throws IOException {
        clamp();
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        String json = "{\n"
                + "  \"ffmpegPath\": " + Json.quote(ffmpegPath) + ",\n"
                + "  \"renderPreset\": " + Json.quote(renderPreset) + ",\n"
                + "  \"renderFps\": " + renderFps + ",\n"
                + "  \"renderHeight\": " + renderHeight + ",\n"
                + "  \"freezeWorld\": " + freezeWorld + "\n"
                + "}\n";
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
