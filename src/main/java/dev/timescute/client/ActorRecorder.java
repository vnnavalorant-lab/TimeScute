package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Grava a SUA performance (movimento, olhar, golpes, item na mão) para um ator repetir. */
public final class ActorRecorder {
    private static final ActorRecorder INSTANCE = new ActorRecorder();
    public static ActorRecorder get() { return INSTANCE; }

    private enum State { IDLE, COUNTDOWN, RECORDING }
    private static final int COUNTDOWN_TICKS = 60, MAX_FRAMES = 20 * 60 * 20; // até 20 minutos

    private State state = State.IDLE;
    private long actorId = -1, startMs;
    private int countdown;
    private final List<ActorFrame> frames = new ArrayList<>();
    private long lastActorId = -1;

    private ActorRecorder() { }

    public boolean isBusy() { return state != State.IDLE; }
    public void setLastActor(long id) { lastActorId = id; }
    public long lastActor() { return lastActorId; }

    /** Tecla de gravar: começa (com contagem) ou termina e salva. */
    public void toggle(MinecraftClient mc) {
        if (state == State.RECORDING) stop(mc, true);
        else if (state == State.COUNTDOWN) stop(mc, false);
        else {
            CameraDirector dir = CameraDirector.get();
            Actor a = dir.timeline.actors().byId(lastActorId).orElse(null);
            if (a == null) a = dir.timeline.actors().isEmpty() ? dir.timeline.addActor("Ator 1") : dir.timeline.actors().all().get(0);
            start(mc, a.id());
        }
    }

    public void start(MinecraftClient mc, long id) {
        if (isBusy() || mc.world == null || mc.player == null) return;
        CameraDirector dir = CameraDirector.get();
        if (dir.timeline.actors().byId(id).isEmpty()) return;
        actorId = id;
        lastActorId = id;
        countdown = COUNTDOWN_TICKS;
        state = State.COUNTDOWN;
        ActorManager.get().setRecordingActor(id);
        dir.pause();
        mc.setScreen(null);
    }

    public void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) { stop(mc, false); return; }
        CameraDirector dir = CameraDirector.get();

        if (state == State.COUNTDOWN) {
            if (countdown % 20 == 0 && countdown > 0) say(mc, String.valueOf(countdown / 20));
            if (--countdown <= 0) {
                startMs = dir.playheadMs();
                frames.clear();
                dir.startStage(false, true); // os outros atores atuam enquanto você grava; a câmera continua sendo a sua
                state = State.RECORDING;
                say(mc, "Gravando!");
            }
            return;
        }

        long rel = dir.playheadMs() - startMs;
        if (rel < 0) return;
        if (frames.size() >= MAX_FRAMES) { stop(mc, true); return; }
        frames.add(frameOf(p, rel));
    }

    public void stop(MinecraftClient mc, boolean commit) {
        if (state == State.IDLE) return;
        CameraDirector dir = CameraDirector.get();
        boolean wasRecording = state == State.RECORDING;
        state = State.IDLE;
        dir.pause();
        ActorManager.get().setRecordingActor(-1);
        if (commit && wasRecording && frames.size() >= 2) {
            Actor a = dir.timeline.actors().byId(actorId).orElse(null);
            if (a != null) {
                dir.timeline.updateActor(a.withPerformance(Performance.of(frames)).withStart(startMs), "Gravar ator");
                say(mc, a.name() + ": " + Fmt.duration(frames.get(frames.size() - 1).timeMs()) + " gravados");
                dir.setPlayhead(startMs);
                dir.autosave();
            }
        } else if (wasRecording) {
            say(mc, commit ? "gravação descartada (muito curta)" : "gravação cancelada");
        }
        frames.clear();
    }

    private static ActorFrame frameOf(ClientPlayerEntity p, long rel) {
        int f = 0;
        if (p.isSneaking()) f |= ActorFrame.SNEAKING;
        if (p.isSprinting()) f |= ActorFrame.SPRINTING;
        if (p.isOnGround()) f |= ActorFrame.ON_GROUND;
        if (p.handSwinging) f |= ActorFrame.SWINGING;
        ItemStack s = p.getMainHandStack();
        String item = s.isEmpty() ? "" : Registries.ITEM.getId(s.getItem()).toString();
        return new ActorFrame(rel, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), p.getHeadYaw(), p.getBodyYaw(),
                f, p.getInventory().selectedSlot, item);
    }

    private static void say(MinecraftClient mc, String msg) {
        if (mc.inGameHud != null) mc.inGameHud.setOverlayMessage(Text.literal("TimeScute: " + msg), false);
    }

    public void renderHud(DrawContext ctx, MinecraftClient mc) {
        if (state == State.IDLE || mc.options.hudHidden) return;
        int right = mc.getWindow().getScaledWidth() - 6;
        if (state == State.COUNTDOWN) {
            String n = String.valueOf((countdown + 19) / 20);
            ctx.drawCenteredTextWithShadow(mc.textRenderer, "Gravando em " + n + "...", mc.getWindow().getScaledWidth() / 2, 30, 0xFFFFD166);
            return;
        }
        long ms = frames.isEmpty() ? 0 : frames.get(frames.size() - 1).timeMs();
        String txt = "GRAVANDO ATOR  " + Fmt.duration(ms) + "  (F8 para parar)";
        int tw = mc.textRenderer.getWidth(txt);
        ctx.fill(right - tw - 18, 4, right + 3, 17, 0x99000000);
        if ((ms / 600) % 2 == 0) ctx.fill(right - tw - 13, 7, right - tw - 6, 14, 0xFFFF3B3B);
        ctx.drawTextWithShadow(mc.textRenderer, txt, right - tw, 7, 0xFFFFFFFF);
    }
}
