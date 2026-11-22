package dev.timescute.client;

import dev.timescute.core.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Modelos do Blockbench que podem ser usados no lugar do jogador: {@code config/timescute/models/nome.geo.json} + {@code nome.png}.
 * Também escolhe, para modelos que não têm ossos de jogador, as animações importadas de "andar" e "parado".
 */
public final class ModelManager {
    private static final ModelManager INSTANCE = new ModelManager();
    public static ModelManager get() { return INSTANCE; }

    private Map<String, GeoModel> models = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<String> messages = List.of();
    private boolean loaded;
    private final Map<String, Identifier> textures = new HashMap<>();
    private final Map<String, String> clipCache = new HashMap<>();

    private ModelManager() { }

    public Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("timescute").resolve("models"); }

    /** Relê a pasta e descarta as texturas antigas. */
    public synchronized List<String> reload() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Identifier id : textures.values()) mc.getTextureManager().destroyTexture(id);
        textures.clear();
        clipCache.clear();
        try {
            ModelFiles.Scan sc = ModelFiles.scan(dir());
            models = sc.models();
            messages = sc.messages();
        } catch (IOException e) {
            models = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            messages = List.of("Não foi possível ler a pasta de modelos: " + e.getMessage());
        }
        loaded = true;
        ActorManager.get().markDirty();
        return messages;
    }

    private synchronized void ensureLoaded() { if (!loaded) reload(); }

    public List<String> messages() { ensureLoaded(); return messages; }
    public List<String> keys() { ensureLoaded(); return new ArrayList<>(models.keySet()); }
    public GeoModel model(String key) { ensureLoaded(); return key == null || key.isEmpty() ? null : models.get(key); }
    public void clearClipCache() { clipCache.clear(); }

    /** Textura do modelo (cria na primeira vez). Cinza se o .png não existir ou não abrir. */
    public synchronized Identifier texture(String key) {
        Identifier id = textures.get(key);
        if (id != null) return id;
        MinecraftClient mc = MinecraftClient.getInstance();
        id = new Identifier("timescute", "models/" + safe(key));
        NativeImage img = null;
        Path png = ModelFiles.texturePath(dir(), key);
        if (Files.isRegularFile(png)) {
            try (InputStream in = Files.newInputStream(png)) { img = NativeImage.read(in); }
            catch (IOException | RuntimeException e) { System.err.println("[TimeScute] Textura do modelo \"" + key + "\" inválida: " + e); }
        }
        if (img == null) {
            img = new NativeImage(16, 16, false);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setColor(x, y, 0xFFB4B4B4);
        }
        mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(img));
        textures.put(key, id);
        return id;
    }

    private static String safe(String key) {
        StringBuilder b = new StringBuilder();
        for (char c : key.toLowerCase(Locale.ROOT).toCharArray()) b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '_');
        return b + "_" + Integer.toHexString(key.hashCode());
    }

    /**
     * Animação importada que serve de "andar" ({@code kind} = walk) ou "parado" (idle) para este modelo: um clipe cujo nome
     * contém a palavra e que usa ossos do modelo. Prefere o que tem o nome do modelo. Nulo se não houver.
     */
    public String clipFor(String modelKey, String kind) {
        GeoModel gm = model(modelKey);
        if (gm == null) return null;
        String ck = modelKey + "|" + kind;
        if (clipCache.containsKey(ck)) return clipCache.get(ck).isEmpty() ? null : clipCache.get(ck);
        String[] words = kind.equals("walk") ? new String[]{"walk", "andar", "run"} : new String[]{"idle", "parado", "stand"};
        String best = null;
        int bestScore = 0;
        ClipLibrary lib = ActorManager.get().clips();
        for (String n : lib.names()) {
            AnimClip c = lib.clip(n).orElse(null);
            if (c == null || c.builtin()) continue;
            String low = n.toLowerCase(Locale.ROOT);
            boolean wordHit = false;
            for (String w : words) if (low.contains(w)) { wordHit = true; break; }
            if (!wordHit) continue;
            int shared = 0;
            for (String bone : c.bones().keySet()) if (gm.bone(bone).isPresent()) shared++;
            if (shared == 0) continue;
            int score = 1 + shared + (low.contains(modelKey.toLowerCase(Locale.ROOT)) ? 100 : 0);
            if (score > bestScore) { bestScore = score; best = n; }
        }
        clipCache.put(ck, best == null ? "" : best);
        return best;
    }
}
