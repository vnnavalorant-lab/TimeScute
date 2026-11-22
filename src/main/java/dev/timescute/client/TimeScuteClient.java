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
        KeyBinding render = key("key.timescute.render", GLFW.GLFW_KEY_F9);

        CameraDirector dir = CameraDirector.get();
        ActorManager actors = ActorManager.get();
        ActorRecorder recorder = ActorRecorder.get();

        ModSettings.init();
        actors.reloadAnimations();
        dir.loadAutosave();
        dir.timeline.onChange(actors::markDirty);
        PathRenderer.register();

        HudRenderCallback.EVENT.register((ctx, tickDelta) -> recorder.renderHud(ctx, MinecraftClient.getInstance()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { recorder.stop(mc, false); actors.despawnAll(mc); dir.pause(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> dir.autosave());

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof GameMenuScreen && RenderSession.get().isRendering()) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Cancelar renderização"), b -> {
                    RenderSession.get().cancel("Renderização cancelada.");
                    mc.setScreen(null);
                }).dimensions(6, 6, 150, 20).build());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.world == null || mc.player == null) { actors.tick(mc); return; }
            recorder.tick(mc);
            actors.tick(mc);

            while (open.wasPressed())
                if (mc.currentScreen == null && !recorder.isBusy()) mc.setScreen(new TimelineScreen());

            while (capture.wasPressed()) {
                if (mc.currentScreen != null) continue;
                var kf = dir.timeline.capture(dir.playheadMs(), dir.captureCurrent(mc));
                dir.selection.set(kf.id());
                say(mc, "Keyframe em " + TimelineView.format(kf.timeMs()) + " (" + dir.timeline.path().size() + " no total)");
                dir.setPlayhead(kf.timeMs() + CameraDirector.CAPTURE_STEP_MS);
                dir.autosave();
            }

            while (play.wasPressed()) {
                if (mc.currentScreen != null || recorder.isBusy()) continue;
                dir.togglePlay();
                say(mc, dir.isPlaying() ? "Reproduzindo a cena" : "Cena pausada");
            }

            while (record.wasPressed()) if (mc.currentScreen == null) recorder.toggle(mc);
            while (render.wasPressed()) if (mc.currentScreen == null && !RenderSession.get().isRendering() && !recorder.isBusy()) mc.setScreen(new RenderScreen(null));
        });
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, "category.timescute"));
    }

    private static void say(MinecraftClient mc, String msg) {
        mc.inGameHud.setOverlayMessage(Text.literal("TimeScute: " + msg), false);
    }
}
