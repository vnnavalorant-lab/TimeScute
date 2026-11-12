package dev.timescute.client;

import dev.timescute.core.TimelineView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
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
        CameraDirector dir = CameraDirector.get();

        dir.loadAutosave();
        PathRenderer.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> dir.autosave());

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
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
        });
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, "category.timescute"));
    }

    private static void say(MinecraftClient mc, String msg) {
        mc.inGameHud.setOverlayMessage(Text.literal("TimeScute: " + msg), false);
    }
}
