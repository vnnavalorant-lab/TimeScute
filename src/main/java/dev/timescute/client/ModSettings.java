package dev.timescute.client;

import dev.timescute.core.Settings;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;

/** As configurações do mod (config/timescute/settings.json), carregadas uma vez. */
public final class ModSettings {
    private static Settings settings = new Settings();

    private ModSettings() { }

    public static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("timescute").resolve("settings.json"); }
    public static void init() { settings = Settings.load(file()); }
    public static Settings get() { return settings; }

    public static void save() {
        try { settings.save(file()); }
        catch (IOException e) { System.err.println("[TimeScute] Não foi possível salvar as configurações: " + e); }
    }
}
