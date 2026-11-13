package dev.timescute.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Configurações do TimeScute (arquivo settings.json). Nunca falha ao carregar: usa os padrões. */
public final class Settings {
    public boolean autoRecord = true;
    public boolean recordSingleplayer = true;
    public boolean thumbnails = true;
    public int compressionLevel = 3;   // 1..9
    public int minFreeMb = 512;        // para a gravação se sobrar menos que isso
    public int maxBacklogMb = 256;     // fila máxima na memória

    public void clamp() {
        compressionLevel = Math.max(1, Math.min(9, compressionLevel));
        minFreeMb = Math.max(0, Math.min(1_000_000, minFreeMb));
        maxBacklogMb = Math.max(16, Math.min(4096, maxBacklogMb));
    }

    @SuppressWarnings("unchecked")
    public static Settings load(Path file) {
        Settings s = new Settings();
        try {
            if (!Files.exists(file)) return s;
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> m0) {
                Map<String, Object> m = (Map<String, Object>) m0;
                s.autoRecord = bool(m, "autoRecord", s.autoRecord);
                s.recordSingleplayer = bool(m, "recordSingleplayer", s.recordSingleplayer);
                s.thumbnails = bool(m, "thumbnails", s.thumbnails);
                s.compressionLevel = num(m, "compressionLevel", s.compressionLevel);
                s.minFreeMb = num(m, "minFreeMb", s.minFreeMb);
                s.maxBacklogMb = num(m, "maxBacklogMb", s.maxBacklogMb);
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
                + "  \"autoRecord\": " + autoRecord + ",\n"
                + "  \"recordSingleplayer\": " + recordSingleplayer + ",\n"
                + "  \"thumbnails\": " + thumbnails + ",\n"
                + "  \"compressionLevel\": " + compressionLevel + ",\n"
                + "  \"minFreeMb\": " + minFreeMb + ",\n"
                + "  \"maxBacklogMb\": " + maxBacklogMb + "\n"
                + "}\n";
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public ReplayWriter.Options writerOptions() {
        clamp();
        ReplayWriter.Options o = new ReplayWriter.Options();
        o.level = compressionLevel;
        o.minFreeBytes = minFreeMb * 1024L * 1024L;
        o.maxBacklogBytes = maxBacklogMb * 1024L * 1024L;
        return o;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) { return m.get(k) instanceof Boolean b ? b : def; }
    private static int num(Map<String, Object> m, String k, int def) { return m.get(k) instanceof Double d ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d)) : def; }
}
