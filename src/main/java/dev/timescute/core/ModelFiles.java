package dev.timescute.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Pasta de modelos do Blockbench: {@code nome.geo.json} (ou {@code nome.json}) + {@code nome.png} ao lado. */
public final class ModelFiles {
    private ModelFiles() { }

    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    public record Scan(Map<String, GeoModel> models, List<String> messages) { }

    /** Nome do modelo (a chave que o ator guarda): o nome do arquivo sem {@code .geo.json}/{@code .json}. */
    public static String keyOf(String fileName) {
        String n = fileName;
        String low = n.toLowerCase(Locale.ROOT);
        if (low.endsWith(".geo.json")) n = n.substring(0, n.length() - 9);
        else if (low.endsWith(".json")) n = n.substring(0, n.length() - 5);
        return n;
    }

    public static Path texturePath(Path dir, String key) { return dir.resolve(key + ".png"); }

    public static Scan scan(Path dir) throws IOException {
        Map<String, GeoModel> models = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> messages = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new Scan(models, messages);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fn.endsWith(".json") && Files.isRegularFile(p)) files.add(p);
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (Path p : files) {
            String fn = p.getFileName().toString();
            try {
                if (Files.size(p) > MAX_FILE_BYTES) { messages.add(fn + ": arquivo grande demais."); continue; }
                BedrockGeometry.Result r = BedrockGeometry.parse(Files.readString(p, StandardCharsets.UTF_8));
                String key = keyOf(fn);
                models.put(key, r.models().get(0));
                if (r.models().size() > 1) messages.add(fn + ": tem " + r.models().size() + " geometrias; só a primeira é usada.");
                for (String w : r.warnings()) messages.add(fn + ": " + w);
                if (!Files.isRegularFile(texturePath(dir, key))) messages.add(fn + ": falta a textura \"" + key + ".png\" na mesma pasta (o modelo ficará cinza).");
            } catch (IllegalArgumentException e) {
                // pode ser um arquivo .json de animação na mesma pasta: não é erro, só não é um modelo
                if (!e.getMessage().startsWith("Nenhuma geometria") && !e.getMessage().startsWith("O arquivo não é")) messages.add(fn + ": " + e.getMessage());
            } catch (IOException e) {
                messages.add(fn + ": não foi possível ler (" + e.getMessage() + ").");
            }
        }
        return new Scan(models, messages);
    }
}
