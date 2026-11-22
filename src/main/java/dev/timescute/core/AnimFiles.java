package dev.timescute.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Lê a pasta de animações (arquivos do Blockbench) e guarda a configuração de eixos de cada clipe. */
public final class AnimFiles {
    public static final String CONFIG_FILE = "clips.json";

    /** @param messages avisos e erros por arquivo, prontos para mostrar ao usuário */
    public record Scan(List<AnimClip> clips, List<String> messages, int files) { }

    private AnimFiles() { }

    public static Scan scan(Path dir) throws IOException {
        List<AnimClip> clips = new ArrayList<>();
        List<String> msgs = new ArrayList<>();
        int files = 0;
        if (!Files.isDirectory(dir)) return new Scan(clips, msgs, 0);
        List<Path> list;
        try (Stream<Path> s = Files.list(dir)) {
            list = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                            && !p.getFileName().toString().equals(CONFIG_FILE) && Files.isRegularFile(p))
                    .sorted().toList();
        }
        for (Path p : list) {
            files++;
            String fn = p.getFileName().toString();
            try {
                BedrockAnimations.Result r = BedrockAnimations.parse(Files.readString(p, StandardCharsets.UTF_8));
                clips.addAll(r.clips());
                for (String w : r.warnings()) msgs.add(fn + ": " + w);
            } catch (IllegalArgumentException | IOException e) {
                msgs.add(fn + ": " + (e.getMessage() == null ? e : e.getMessage()));
            }
        }
        return new Scan(clips, msgs, files);
    }

    public static void saveConfig(Path dir, ClipLibrary lib) throws IOException {
        Files.createDirectories(dir);
        StringBuilder b = new StringBuilder("{\n  \"clips\": {");
        int n = 0;
        for (Map.Entry<String, AnimLibrary.Config> e : new TreeMap<>(lib.configs()).entrySet()) {
            AnimLibrary.Config c = e.getValue();
            b.append(n++ == 0 ? "\n" : ",\n").append("    ").append(Json.quote(e.getKey()))
                    .append(": {\"convention\": ").append(Json.quote(c.convention().name()))
                    .append(", \"flipPitch\": ").append(c.flipPitch()).append(", \"flipYaw\": ").append(c.flipYaw())
                    .append(", \"flipRoll\": ").append(c.flipRoll()).append("}");
        }
        b.append(n == 0 ? "}" : "\n  }").append("\n}\n");
        Path target = dir.resolve(CONFIG_FILE), tmp = dir.resolve(CONFIG_FILE + ".tmp");
        Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Aplica a configuração salva aos clipes que existem; ignora o que não existe mais e arquivo ruim. */
    @SuppressWarnings("unchecked")
    public static void loadConfig(Path dir, ClipLibrary lib) {
        Path f = dir.resolve(CONFIG_FILE);
        try {
            if (!Files.exists(f)) return;
            Object root = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m) || !(m.get("clips") instanceof Map<?, ?> cm)) return;
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> c)) continue;
                Convention conv = Convention.BEDROCK;
                if (c.get("convention") instanceof String cs) { try { conv = Convention.valueOf(cs); } catch (IllegalArgumentException ignored) { /* padrão */ } }
                lib.setConfig(String.valueOf(e.getKey()), new AnimLibrary.Config(conv,
                        Boolean.TRUE.equals(c.get("flipPitch")), Boolean.TRUE.equals(c.get("flipYaw")), Boolean.TRUE.equals(c.get("flipRoll"))));
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // configuração ruim: usa os padrões
        }
    }
}
