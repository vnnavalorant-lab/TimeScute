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

    // interface do editor
    public static final int DOCK_MIN = 240, DOCK_MAX = 560, LANES_MIN = 1, LANES_MAX = 8,
            CAM_LANE_MIN = 40, CAM_LANE_MAX = 96, OPACITY_MIN = 30, OPACITY_MAX = 100;
    /** Largura dos painéis laterais (Atores e Reação), em pixels da interface. */
    public int dockWidth = 310;
    /** Altura da faixa da câmera na timeline, em pixels. */
    public int camLaneHeight = 56;
    /** Opacidade dos painéis em %, para enxergar a cena através deles. */
    public int panelOpacity = 93;
    /** Painéis laterais à esquerda (senão, à direita). */
    public boolean dockLeft = false;
    /** Quantas faixas de atores aparecem de uma vez na timeline. */
    public int lanesVisible = 3;
    /** Grade de enquadramento (terços e área segura) sobre a cena. */
    public boolean showGrid = false;
    /** Timeline recolhida numa barra fina, para ver a cena. */
    public boolean collapsed = false;

    public static final int[] FPS_CHOICES = {24, 30, 48, 60};
    public static final int[] HEIGHT_CHOICES = {0, 480, 720, 1080, 1440, 2160};

    public void clamp() {
        renderFps = nearest(FPS_CHOICES, renderFps);
        renderHeight = nearest(HEIGHT_CHOICES, renderHeight);
        dockWidth = Math.max(DOCK_MIN, Math.min(DOCK_MAX, dockWidth));
        lanesVisible = Math.max(LANES_MIN, Math.min(LANES_MAX, lanesVisible));
        camLaneHeight = Math.max(CAM_LANE_MIN, Math.min(CAM_LANE_MAX, camLaneHeight));
        panelOpacity = Math.max(OPACITY_MIN, Math.min(OPACITY_MAX, panelOpacity));
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
                s.dockWidth = m.get("dockWidth") instanceof Double d ? (int) Math.max(0, Math.min(10_000, d)) : s.dockWidth;
                s.lanesVisible = m.get("lanesVisible") instanceof Double d ? (int) Math.max(0, Math.min(100, d)) : s.lanesVisible;
                s.camLaneHeight = m.get("camLaneHeight") instanceof Double d ? (int) Math.max(0, Math.min(10_000, d)) : s.camLaneHeight;
                s.panelOpacity = m.get("panelOpacity") instanceof Double d ? (int) Math.max(0, Math.min(10_000, d)) : s.panelOpacity;
                s.dockLeft = m.get("dockLeft") instanceof Boolean b ? b : s.dockLeft;
                s.showGrid = m.get("showGrid") instanceof Boolean b ? b : s.showGrid;
                s.collapsed = m.get("collapsed") instanceof Boolean b ? b : s.collapsed;
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
                + "  \"freezeWorld\": " + freezeWorld + ",\n"
                + "  \"dockWidth\": " + dockWidth + ",\n"
                + "  \"camLaneHeight\": " + camLaneHeight + ",\n"
                + "  \"panelOpacity\": " + panelOpacity + ",\n"
                + "  \"dockLeft\": " + dockLeft + ",\n"
                + "  \"lanesVisible\": " + lanesVisible + ",\n"
                + "  \"showGrid\": " + showGrid + ",\n"
                + "  \"collapsed\": " + collapsed + "\n"
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
