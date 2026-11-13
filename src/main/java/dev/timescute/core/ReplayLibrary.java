package dev.timescute.core;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/** Lista, renomeia, apaga e repara os replays de uma pasta. */
public final class ReplayLibrary {
    public static final String EXT = ".tsr";

    /** @param meta nulo quando o arquivo não pôde ser lido (então {@code error} explica). */
    public record Entry(Path file, String name, long sizeBytes, long modifiedMs,
                        ReplayMeta meta, boolean complete, long durationMs, String error) {
        public boolean readable() { return meta != null; }
        public long sortTimeMs() { return meta != null && meta.startedAtMs > 0 ? meta.startedAtMs : modifiedMs; }
    }

    private ReplayLibrary() { }

    public static List<Entry> scan(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(EXT) || !Files.isRegularFile(p)) continue;
                String name = fn.substring(0, fn.length() - EXT.length());
                long size = Files.size(p), mod = Files.getLastModifiedTime(p).toMillis();
                try (ReplayReader r = ReplayReader.open(p)) {
                    out.add(new Entry(p, name, size, mod, r.meta(), r.isComplete(), r.meta().durationMs, null));
                } catch (IOException | RuntimeException e) {
                    out.add(new Entry(p, name, size, mod, null, false, 0, e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
        }
        out.sort(Comparator.comparingLong(Entry::sortTimeMs).reversed());
        return out;
    }

    public static Optional<byte[]> thumbnail(Path file) {
        try (ReplayReader r = ReplayReader.open(file)) {
            return r.thumbnail();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Nome seguro para arquivo: sem barras, sem nomes ocultos, no máximo 64 caracteres. */
    public static String sanitizeName(String raw) {
        String n = raw == null ? "" : raw.trim().replaceAll("[^\\p{L}\\p{N} _.\\-]", "_");
        n = n.replaceAll("^[.]+", "").trim();
        if (n.length() > 64) n = n.substring(0, 64).trim();
        return n.isEmpty() ? "replay" : n;
    }

    /** Nome padrão: data/hora + onde foi gravado. */
    public static String defaultName(long startedAtMs, String where) {
        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(startedAtMs));
        String w = sanitizeName(where == null || where.isBlank() ? "" : where);
        return where == null || where.isBlank() ? stamp : sanitizeName(stamp + "_" + w);
    }

    /** Caminho que ainda não existe: nome.tsr, nome-2.tsr, nome-3.tsr... */
    public static Path uniqueFile(Path dir, String base) {
        String b = sanitizeName(base);
        Path p = dir.resolve(b + EXT);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(b + "-" + i + EXT);
        return p;
    }

    public static Path rename(Path file, String newName) throws IOException {
        Path target = file.resolveSibling(sanitizeName(newName) + EXT);
        if (target.equals(file)) return file;
        if (Files.exists(target)) throw new FileAlreadyExistsException("Já existe um replay com esse nome.");
        return Files.move(file, target);
    }

    public static boolean delete(Path file) throws IOException { return Files.deleteIfExists(file); }

    public static String formatDuration(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
    }
}
