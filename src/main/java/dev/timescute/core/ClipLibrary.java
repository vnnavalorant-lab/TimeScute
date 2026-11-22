package dev.timescute.core;

import java.util.*;

/** Todos os clipes disponíveis: embutidos primeiro, depois os importados, cada um com sua configuração de eixos. */
public final class ClipLibrary implements AnimLibrary {
    private final Map<String, AnimClip> clips = new LinkedHashMap<>();
    private final Map<String, Config> configs = new HashMap<>();

    public ClipLibrary() {
        for (AnimClip c : BuiltinClips.all()) {
            clips.put(c.name(), c);
            configs.put(c.name(), BuiltinClips.defaultConfig(c.name()));
        }
    }

    /** Acrescenta clipes importados; nomes repetidos ganham " (2)", " (3)"... Devolve os nomes finais. */
    public List<String> addImported(List<AnimClip> imported) {
        List<String> names = new ArrayList<>();
        for (AnimClip c : imported) {
            String n = c.name();
            for (int i = 2; clips.containsKey(n); i++) n = c.name() + " (" + i + ")";
            clips.put(n, n.equals(c.name()) ? c : new AnimClip(n, c.length(), c.loop(), c.bones(), false));
            configs.putIfAbsent(n, Config.BEDROCK);
            names.add(n);
        }
        return names;
    }

    /** Remove os importados (mantém os embutidos): usado ao reler a pasta. */
    public void clearImported() {
        clips.entrySet().removeIf(e -> !e.getValue().builtin());
        configs.keySet().removeIf(k -> !clips.containsKey(k));
    }

    @Override public Optional<AnimClip> clip(String name) { return Optional.ofNullable(clips.get(name)); }
    @Override public Config config(String name) { return configs.getOrDefault(name, Config.BEDROCK); }
    public void setConfig(String name, Config c) { if (clips.containsKey(name)) configs.put(name, c); }
    public Map<String, Config> configs() { return Collections.unmodifiableMap(configs); }
    public List<String> names() { return new ArrayList<>(clips.keySet()); }
    public int importedCount() { return (int) clips.values().stream().filter(c -> !c.builtin()).count(); }

    /** Nome curto para mostrar na interface. */
    public static String label(String name) {
        if (name == null || name.isEmpty()) return "(nenhuma)";
        if (name.startsWith("builtin.")) return "★ " + name.substring(8);
        return BedrockAnimations.shortName(name);
    }
}
