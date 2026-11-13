package dev.timescute.client;

import com.mojang.authlib.GameProfile;
import dev.timescute.core.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Reproduz um replay dentro do jogo.
 *
 * <p>Como funciona: criamos uma conexão "de mentira" (um canal Netty em memória) com um {@link ClientPlayNetworkHandler}
 * normal e entregamos a ele, no ritmo certo, os pacotes gravados. O jogo acha que está num servidor.
 * O jogador local vira uma câmera livre (modo espectador); o jogador que foi gravado volta como uma entidade
 * à parte, movida pelas amostras gravadas.
 *
 * <p>Tudo que depende de detalhes do Minecraft fica nesta classe de propósito.
 */
public final class ReplaySession {
    private static final ReplaySession INSTANCE = new ReplaySession();
    public static ReplaySession get() { return INSTANCE; }

    /** Id da câmera. O id original do jogador gravado fica livre para a entidade que o representa. */
    private static final int CAMERA_ID = -1000;
    private static final long TICK_BUDGET_MS = 30, FF_BUDGET_MS = 45, CLOSE_SCREEN_AFTER_MS = 1500;
    private static final int MAX_ERRORS = 300;

    public enum State { IDLE, LOADING, PLAYING }

    private State state = State.IDLE;
    private ReplayCursor cursor;
    private ReplayMeta meta;
    private ReplayClock clock;
    private ClientConnection conn;
    private EmbeddedChannel channel;
    private ClientPlayNetworkHandler handler;

    private ClientWorld lastWorld, selfWorld;
    private OtherClientPlayerEntity self;
    private SelfSample lastSelf, firstSelf;
    private boolean prevSwing, fastForward, restarting, joined;
    private long joinedAtMs, lastNanos;
    private int errors;
    private double[] savedCam; // x, y, z, yaw, pitch

    private volatile String lastError = "";
    private volatile long errorUntil;

    private ReplaySession() { }

    // ================= consulta =================

    public boolean isActive() { return state != State.IDLE; }
    public boolean isLoading() { return state == State.LOADING || fastForward; }
    public ReplayClock clock() { return clock; }
    public ReplayMeta meta() { return meta; }
    public boolean isCamera(Object entity) {
        return state != State.IDLE && entity != null && MinecraftClient.getInstance().player == entity;
    }

    /** Mensagem de erro se este replay não pode ser aberto nesta versão do jogo; senão {@code null}. */
    public static String check(ReplayMeta m) {
        int cur = SharedConstants.getGameVersion().getProtocolVersion();
        if (m.protocol != 0 && m.protocol != cur)
            return "Este replay foi gravado na versão " + (m.mcVersion.isEmpty() ? "desconhecida" : m.mcVersion)
                    + " e o jogo está na " + SharedConstants.getGameVersion().getName() + ".";
        return null;
    }

    // ================= abrir e fechar =================

    /** Abre o replay. Precisa estar fora de qualquer mundo. */
    public void start(Path file) throws IOException {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) throw new IOException("Saia do mundo atual antes de abrir um replay.");
        if (isActive()) stop();

        ReplayCursor c = ReplayCursor.open(file);
        String problem = check(c.meta());
        if (problem != null) { c.close(); throw new IOException(problem); }

        cursor = c;
        meta = c.meta();
        firstSelf = c.firstSelf().orElse(null);
        lastSelf = firstSelf;
        clock = new ReplayClock(meta.durationMs);
        clock.setSpeed(1);
        clock.play();
        savedCam = null;
        errors = 0;
        fastForward = false;
        restarting = false;
        try {
            connect(mc);
        } catch (RuntimeException e) {
            stop();
            throw new IOException("Não foi possível iniciar a reprodução: " + e, e);
        }
        state = State.LOADING;
        lastNanos = System.nanoTime();
        mc.setScreen(new MessageScreen(Text.literal("Carregando replay...")));
    }

    private void connect(MinecraftClient mc) {
        conn = new ClientConnection(NetworkSide.CLIENTBOUND);
        channel = new EmbeddedChannel(new Sink(), conn);
        conn.setState(NetworkState.PLAY);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TimeScute");
        handler = new ClientPlayNetworkHandler(mc, null, conn, null, profile,
                mc.getTelemetryManager().createWorldSession(false, null, null));
        conn.setPacketListener(handler);
        cursor.reset();
        lastWorld = null;
        selfWorld = null;
        self = null;
        joined = false;
        joinedAtMs = 0;
        prevSwing = false;
    }

    /** Tudo que o servidor "responderia" ao jogo é descartado aqui (não há para quem enviar). */
    private static final class Sink extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }
    }

    /** Encerra a sessão (sem mexer no mundo do jogo). */
    public void stop() {
        state = State.IDLE;
        fastForward = false;
        self = null;
        try { if (cursor != null) cursor.close(); } catch (IOException ignored) { /* nada a fazer */ }
        cursor = null;
        try { if (channel != null) channel.close(); } catch (RuntimeException ignored) { /* já fechado */ }
        channel = null;
        conn = null;
        handler = null;
    }

    /** Chamado quando o jogo se desconecta (usuário saiu pelo menu). */
    public void onDisconnect() {
        if (restarting || !isActive()) return;
        stop();
    }

    /** Sai do replay e volta ao menu principal, como o botão "Desconectar". */
    public void exitReplay() {
        MinecraftClient mc = MinecraftClient.getInstance();
        stop();
        if (mc.world != null) mc.world.disconnect();
        mc.disconnect();
        mc.setScreen(new TitleScreen());
    }

    private void fail(String msg) {
        lastError = msg;
        errorUntil = System.currentTimeMillis() + 15_000;
        System.err.println("[TimeScute] " + msg);
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(this::exitReplay);
    }

    // ================= controles =================

    public void togglePause() { if (isActive()) clock.toggle(); }
    public void stepSpeed(int dir) { if (isActive()) clock.stepSpeed(dir); }
    public void setSpeed(double s) { if (isActive()) clock.setSpeed(s); }
    public void jump(long deltaMs) { if (isActive()) seek(clock.timeMs() + deltaMs); }

    public void seekToNextMarker() {
        if (isActive()) meta.nextMarkerAfter(clock.timeMs()).ifPresent(this::seek);
    }

    public void seekToPreviousMarker() {
        if (isActive()) meta.previousMarkerBefore(clock.timeMs()).ifPresent(this::seek);
    }

    /** Vai para um instante. Para a frente é rápido; para trás o replay recomeça do início e avança até lá. */
    public void seek(long ms) {
        if (!isActive() || state != State.PLAYING) return;
        ms = Math.max(0, Math.min(ms, clock.durationMs()));
        if (ms >= cursor.lastTimeMs()) {
            clock.seek(ms);
            fastForward = true;
        } else {
            restartTo(ms);
        }
    }

    private void restartTo(long ms) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity pl = mc.player;
        savedCam = pl == null ? null : camera(pl);
        restarting = true;
        try {
            channel.close();
            mc.disconnect();
            connect(mc);
            clock.seek(ms);
            fastForward = true;
            state = State.LOADING;
            lastNanos = System.nanoTime();
            mc.setScreen(new MessageScreen(Text.literal("Buscando no replay...")));
        } catch (RuntimeException e) {
            restarting = false;
            fail("Não foi possível buscar no replay: " + e);
            return;
        }
        restarting = false;
    }

    /** A reprodução do trajeto de câmera conduz o tempo do replay pela trilha de velocidade da timeline. */
    public void onPathAdvance(long oldOutputMs, long newOutputMs, SpeedTrack speed) {
        if (state != State.PLAYING || fastForward) return;
        clock.advanceExact(speed.replayTimeAt(newOutputMs) - speed.replayTimeAt(oldOutputMs));
    }

    private static double[] camera(ClientPlayerEntity p) {
        return new double[]{p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch()};
    }

    // ================= laço principal (início de cada tick) =================

    public void tick(MinecraftClient mc) {
        if (!isActive()) return;
        try {
            long now = System.nanoTime();
            double dt = Math.min(250, (now - lastNanos) / 1e6);
            lastNanos = now;

            if (joined && mc.world == null && !restarting) { stop(); return; } // o usuário saiu do mundo

            if (!fastForward && !CameraDirector.get().isPlaying()) clock.tick(dt);

            long deadline = now + (fastForward ? FF_BUDGET_MS : TICK_BUDGET_MS) * 1_000_000L;
            ReplayFeeder.Result r = ReplayFeeder.feed(cursor, clock.timeMs(), this::handle, () -> System.nanoTime() > deadline);
            if (fastForward && !r.outOfBudget()) fastForward = false;

            housekeeping(mc);
        } catch (Throwable t) {
            fail("Erro na reprodução: " + t);
        }
    }

    private void handle(ReplayRecord rec) {
        try {
            switch (rec.kind()) {
                case ReplayFormat.KIND_PACKET -> applyPacket(rec.data());
                case ReplayFormat.KIND_SELF -> applySelf(rec.data());
                default -> { /* marcadores não afetam o mundo */ }
            }
        } catch (RuntimeException e) {
            errors++;
            if (errors <= 5) System.err.println("[TimeScute] Pacote do replay ignorado: " + e);
            if (errors > MAX_ERRORS) throw new IllegalStateException("Erros demais ao reproduzir (" + errors + "). O replay pode estar corrompido.");
        }
    }

    // ================= pacotes =================

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyPacket(byte[] data) {
        int off = PacketBytes.varIntLength(data);
        if (off < 0) return;
        int id = PacketBytes.readVarInt(data);
        Packet<?> p = decode(data, off, id);
        if (p == null) return;

        if (p instanceof GameJoinS2CPacket) { // troca o id do jogador por o da câmera, liberando o id original
            p = decode(PacketBytes.patchInt(data, off, CAMERA_ID), off, id);
            if (p == null) return;
        }
        if (shouldDrop(p)) return;
        if (fastForward && isTransient(p)) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (p instanceof PlayerRespawnS2CPacket && mc.player != null) savedCam = camera(mc.player);

        Packet raw = p;
        raw.apply(handler);

        if (p instanceof GameJoinS2CPacket || p instanceof PlayerRespawnS2CPacket) afterJoin(mc);
    }

    private static Packet<?> decode(byte[] data, int off, int id) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(data, off, data.length - off));
        try {
            return NetworkState.PLAY.getPacketHandler(NetworkSide.CLIENTBOUND, id, buf);
        } finally {
            buf.release();
        }
    }

    /** Pacotes que só fazem sentido para quem estava jogando: mexeriam na câmera ou abririam telas. */
    private static boolean shouldDrop(Packet<?> p) {
        if (p instanceof GameStateChangeS2CPacket g) return g.getReason() == GameStateChangeS2CPacket.GAME_MODE_CHANGED;
        return p instanceof PlayerPositionLookS2CPacket
                || p instanceof PlayerAbilitiesS2CPacket
                || p instanceof HealthUpdateS2CPacket
                || p instanceof ExperienceBarUpdateS2CPacket
                || p instanceof UpdateSelectedSlotS2CPacket
                || p instanceof InventoryS2CPacket
                || p instanceof ScreenHandlerSlotUpdateS2CPacket
                || p instanceof ScreenHandlerPropertyUpdateS2CPacket
                || p instanceof OpenScreenS2CPacket
                || p instanceof OpenHorseScreenS2CPacket
                || p instanceof SignEditorOpenS2CPacket
                || p instanceof OpenWrittenBookS2CPacket
                || p instanceof CloseScreenS2CPacket
                || p instanceof KeepAliveS2CPacket
                || p instanceof ResourcePackSendS2CPacket
                || p instanceof AdvancementUpdateS2CPacket
                || p instanceof DisconnectS2CPacket;
    }

    /** Efeitos passageiros (som, partículas, chat): ao avançar rápido, tocar todos seria só barulho. */
    private static boolean isTransient(Packet<?> p) {
        return p instanceof PlaySoundS2CPacket
                || p instanceof PlaySoundFromEntityS2CPacket
                || p instanceof ParticleS2CPacket
                || p instanceof WorldEventS2CPacket
                || p instanceof EntityAnimationS2CPacket
                || p instanceof GameMessageS2CPacket
                || p instanceof ChatMessageS2CPacket
                || p instanceof OverlayMessageS2CPacket;
    }

    // ================= câmera e jogador gravado =================

    private void afterJoin(MinecraftClient mc) {
        ClientPlayerEntity pl = mc.player;
        if (pl == null || mc.interactionManager == null) return;
        mc.interactionManager.setGameMode(GameMode.SPECTATOR);
        pl.getAbilities().flying = true;
        pl.getAbilities().setFlySpeed(0.08f);

        double[] cam = savedCam != null ? savedCam
                : firstSelf == null ? null
                : new double[]{firstSelf.x(), firstSelf.y(), firstSelf.z(), firstSelf.yaw(), firstSelf.pitch()};
        if (cam != null) pl.refreshPositionAndAngles(cam[0], cam[1], cam[2], (float) cam[3], (float) cam[4]);
        savedCam = null;

        if (!joined) { joined = true; joinedAtMs = System.currentTimeMillis(); }
        state = State.PLAYING;
    }

    private void applySelf(byte[] data) {
        SelfSample s = SelfSample.decode(data);
        lastSelf = s;
        OtherClientPlayerEntity e = self;
        if (e == null) return;
        e.updateTrackedPositionAndAngles(s.x(), s.y(), s.z(), s.yaw(), s.pitch(), 3, false);
        e.setHeadYaw(s.headYaw());
        e.setOnGround(s.has(SelfSample.ON_GROUND));
        boolean swinging = s.has(SelfSample.SWINGING);
        if (swinging && !prevSwing && !fastForward) e.swingHand(Hand.MAIN_HAND);
        prevSwing = swinging;
    }

    private void spawnSelf(MinecraftClient mc) {
        if (lastSelf == null || mc.world == null) return;
        UUID uuid;
        try { uuid = UUID.fromString(meta.playerUuid); } catch (IllegalArgumentException e) { uuid = UUID.randomUUID(); }
        GameProfile gp = new GameProfile(uuid, meta.playerName.isEmpty() ? "Jogador" : meta.playerName);
        OtherClientPlayerEntity e = new OtherClientPlayerEntity(mc.world, gp);
        e.setId(lastSelf.entityId());
        e.refreshPositionAndAngles(lastSelf.x(), lastSelf.y(), lastSelf.z(), lastSelf.yaw(), lastSelf.pitch());
        e.setHeadYaw(lastSelf.headYaw());
        mc.world.addEntity(lastSelf.entityId(), e);
        self = e;
        selfWorld = mc.world;
    }

    private void housekeeping(MinecraftClient mc) {
        if (mc.world != lastWorld) { lastWorld = mc.world; self = null; }
        if (mc.world != null && (self == null || self.isRemoved() || selfWorld != mc.world)) spawnSelf(mc);
        if (joined && System.currentTimeMillis() - joinedAtMs > CLOSE_SCREEN_AFTER_MS
                && (mc.currentScreen instanceof DownloadingTerrainScreen || mc.currentScreen instanceof MessageScreen)) {
            mc.setScreen(null);
        }
    }

    // ================= HUD =================

    public static String speedLabel(double s) {
        if (Math.abs(s - Math.rint(s)) < 1e-9) return (int) Math.rint(s) + "x";
        String t = String.format(Locale.ROOT, "%.2f", s).replaceAll("0+$", "").replaceAll("\\.$", "");
        return t + "x";
    }

    public void renderHud(DrawContext ctx, MinecraftClient mc) {
        if (mc.options.hudHidden) return;
        int w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight(), right = w - 6;

        if (isActive() && clock != null) {
            String status = isLoading() ? "buscando..." : clock.isPlaying() ? ">" : "||";
            String txt = "REPLAY  " + status + "  " + ReplayLibrary.formatDuration(clock.timeMs()) + " / "
                    + ReplayLibrary.formatDuration(clock.durationMs()) + "  " + speedLabel(clock.speed());
            int tw = mc.textRenderer.getWidth(txt);
            ctx.fill(right - tw - 6, 4, right + 3, 17, 0x99000000);
            ctx.drawTextWithShadow(mc.textRenderer, txt, right - tw, 7, 0xFFFFFFFF);

            // barra de progresso fina embaixo, com os marcadores
            long dur = Math.max(1, clock.durationMs());
            ctx.fill(0, h - 3, w, h, 0x88000000);
            ctx.fill(0, h - 3, (int) ((long) w * clock.timeMs() / dur), h, 0xFF4DA3FF);
            for (ReplayMeta.Marker m : meta.markerList)
                ctx.fill((int) ((long) w * m.timeMs() / dur), h - 6, (int) ((long) w * m.timeMs() / dur) + 1, h - 3, 0xFFE8A33D);
        }

        if (System.currentTimeMillis() < errorUntil) {
            int tw = mc.textRenderer.getWidth(lastError);
            ctx.fill(right - tw - 6, 22, right + 3, 35, 0xCC000000);
            ctx.drawTextWithShadow(mc.textRenderer, lastError, right - tw, 25, 0xFFFF6B6B);
        }
    }

    public String lastError() { return System.currentTimeMillis() < errorUntil ? lastError : ""; }
}
