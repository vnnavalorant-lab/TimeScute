package dev.timescute.client;

import dev.timescute.core.TimelineView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class TimeScuteClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBinding open = key("key.timescute.open", GLFW.GLFW_KEY_K);
        KeyBinding capture = key("key.timescute.capture", GLFW.GLFW_KEY_J);
        KeyBinding play = key("key.timescute.play", GLFW.GLFW_KEY_H);
        KeyBinding record = key("key.timescute.record", GLFW.GLFW_KEY_F8);
        KeyBinding marker = key("key.timescute.marker", GLFW.GLFW_KEY_M);
        KeyBinding replays = key("key.timescute.replays", GLFW.GLFW_KEY_R);

        CameraDirector dir = CameraDirector.get();
        RecordingManager rec = RecordingManager.get();

        rec.init();
        dir.loadAutosave();
        PathRenderer.register();

        HudRenderCallback.EVENT.register((ctx, tickDelta) -> rec.renderHud(ctx, MinecraftClient.getInstance()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> rec.stop(null));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { dir.autosave(); rec.shutdown(); });

        // atalho para a lista de replays no menu principal e no menu de pausa
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof TitleScreen || screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Replays"),
                        b -> mc.setScreen(new ReplayListScreen(screen))).dimensions(6, 6, 70, 20).build());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            rec.tick(mc);
            if (mc.world == null || mc.player == null) return;

            while (open.wasPressed())
                if (mc.currentScreen == null) mc.setScreen(new TimelineScreen());

            while (capture.wasPressed()) {
                if (mc.currentScreen != null) continue;
                var kf = dir.timeline.capture(dir.playheadMs(), dir.captureCurrent(mc));
                dir.selection.set(kf.id());
                say(mc, "Keyframe em " + TimelineView.format(kf.timeMs()) + " (" + dir.timeline.path().size() + " no total)");
                dir.setPlayhead(kf.timeMs() + CameraDirector.CAPTURE_STEP_MS);
                dir.autosave();
            }

            while (play.wasPressed()) {
                if (mc.currentScreen != null) continue;
                dir.togglePlay();
                say(mc, dir.isPlaying() ? "Reproduzindo trajeto" : "Trajeto pausado");
            }

            while (record.wasPressed()) if (mc.currentScreen == null) rec.onToggleKey(mc);
            while (marker.wasPressed()) if (mc.currentScreen == null) rec.marker(mc);
            while (replays.wasPressed()) if (mc.currentScreen == null) mc.setScreen(new ReplayListScreen(null));
        });
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, "category.timescute"));
    }

    private static void say(MinecraftClient mc, String msg) {
        mc.inGameHud.setOverlayMessage(Text.literal("TimeScute: " + msg), false);
    }
}
