package dev.timescute.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Salvar/abrir trajetos numa pasta. Nomes que começam com "_" são internos (ex.: _autosave). */
public final class PathFiles {
    public static final String EXT = ".json";
    public static final String AUTOSAVE = "_autosave";

    public record Entry(String name, long modifiedMs) { }

    private PathFiles() { }

    /** Deixa só letras, números, espaço, '-', '_' e '.'; nunca vazio, nunca começa com '_' ou '.'. */
    public static String sanitize(String raw) {
        String n = raw == null ? "" : raw.trim().replaceAll("[^\\p{L}\\p{N} _.\\-]", "_");
        n = n.replaceAll("^[_.]+", "").trim();
        if (n.length() > 48) n = n.substring(0, 48).trim();
        return n.isEmpty() ? "trajeto" : n;
    }

    private static Path file(Path dir, String name) { return dir.resolve(name + EXT); }

    public static void save(Path dir, String name, Timeline t) throws IOException {
        Files.createDirectories(dir);
        Path target = file(dir, name);
        Path tmp = dir.resolve(name + EXT + ".tmp");
        Files.writeString(tmp, PathJson.write(t), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Lê e valida o arquivo. Lança IOException/IllegalArgumentException com mensagem amigável. */
    public static PathJson.Data read(Path dir, String name) throws IOException {
        Path f = file(dir, name);
        if (!Files.exists(f)) throw new IOException("Arquivo não encontrado: " + name);
        return PathJson.read(Files.readString(f, StandardCharsets.UTF_8));
    }

    public static boolean exists(Path dir, String name) { return Files.exists(file(dir, name)); }

    public static boolean delete(Path dir, String name) throws IOException {
        return Files.deleteIfExists(file(dir, name));
    }

    /** Trajetos do usuário, do mais recente para o mais antigo. */
    public static List<Entry> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(EXT) || fn.startsWith("_")) continue;
                out.add(new Entry(fn.substring(0, fn.length() - EXT.length()), Files.getLastModifiedTime(p).toMillis()));
            }
        }
        out.sort(Comparator.comparingLong(Entry::modifiedMs).reversed());
        return out;
    }
}
