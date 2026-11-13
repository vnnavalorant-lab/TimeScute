package dev.timescute.client;

import dev.timescute.core.*;
import io.netty.buffer.Unpooled;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.Session;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Grava a sessão atual em um .tsr. Os pacotes chegam na thread de rede; as amostras do jogador,
 * a miniatura e o HUD vêm da thread principal. Nada aqui bloqueia no disco: quem escreve é o ReplayWriter.
 */
public final class RecordingManager {
    private static final RecordingManager INSTANCE = new RecordingManager();
    public static RecordingManager get() { return INSTANCE; }

    private static final String MOD_VERSION = "0.4.0";
    private static final int RED = 0xFFFF3B3B;

    private final ExecutorService closer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timescute-replay-closer");
        t.setDaemon(true);
        return t;
    });

    private Settings settings = new Settings();
    private volatile ReplayWriter writer;
    private volatile Object connection;
    private volatile Object lastAttempt;
    private volatile String lastError = "";
    private volatile long errorUntil;
    private boolean thumbDone;
    private int thumbTries;
    private int markerCount;

    private RecordingManager() { }

    // ================= configuração e pastas =================

    public void init() { settings = Settings.load(settingsFile()); }
    public Settings settings() { return settings; }

    public void saveSettings() {
        try { settings.save(settingsFile()); }
        catch (IOException e) { System.err.println("[TimeScute] Não foi possível salvar as configurações: " + e); }
    }

    public Path settingsFile() { return FabricLoader.getInstance().getConfigDir().resolve("timescute").resolve("settings.json"); }
    public Path replaysDir() { return FabricLoader.getInstance().getGameDir().resolve("timescute").resolve("replays"); }

    public boolean isRecording() {
        ReplayWriter w = writer;
        return w != null && !w.isClosed();
    }

    // ================= thread de rede =================

    /** Chamado para todo pacote recebido, na thread de rede. Nunca lança exceção. */
    public void onClientPacket(Object conn, Packet<?> packet, PacketListener listener) {
        try {
            if (!(listener instanceof ClientPlayNetworkHandler handler)) return; // só a fase de jogo
            ReplayWriter w = writer;
            if (w == null) {
                if (conn == lastAttempt) return; // já decidimos para esta conexão (parada, desligada ou com erro)
                lastAttempt = conn;
                w = tryStart(conn, handler);
                if (w == null) return;
            }
            if (conn != connection) return;
            record(w, packet);
            if (w.error().isPresent()) fail(w);
        } catch (Throwable t) {
            System.err.println("[TimeScute] Erro ao gravar pacote: " + t);
        }
    }

    private ReplayWriter tryStart(Object conn, ClientPlayNetworkHandler handler) {
        if (!settings.autoRecord) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean solo = mc.getServer() != null;
        if (solo && !settings.recordSingleplayer) return null;

        ReplayMeta m = new ReplayMeta();
        m.mcVersion = SharedConstants.getGameVersion().getName();
        m.protocol = SharedConstants.getGameVersion().getProtocolVersion();
        m.modVersion = MOD_VERSION;
        m.startedAtMs = System.currentTimeMillis();
        m.singleplayer = solo;
        ServerInfo si = handler.getServerInfo();
        if (si != null) { m.server = si.address; m.serverName = si.name; }
        if (solo) {
            try { m.world = mc.getServer().getSaveProperties().getLevelName(); } catch (Throwable ignored) { /* fica sem nome */ }
        }
        Session ses = mc.getSession();
        m.playerName = ses.getUsername();
        UUID id = ses.getUuidOrNull();
        m.playerUuid = id == null ? "" : id.toString();

        try {
            Path file = ReplayLibrary.uniqueFile(replaysDir(), ReplayLibrary.defaultName(m.startedAtMs, m.where()));
            ReplayWriter w = ReplayWriter.create(file, m, settings.writerOptions());
            thumbDone = false;
            thumbTries = 0;
            markerCount = 0;
            connection = conn;
            writer = w;
            return w;
        } catch (IOException e) {
            notifyError("Não foi possível iniciar a gravação: " + e.getMessage());
            return null;
        }
    }

    private void record(ReplayWriter w, Packet<?> p) {
        if (p instanceof BundleS2CPacket bundle) {
            for (Packet<?> sub : bundle.getPackets()) one(w, sub);
        } else {
            one(w, p);
        }
    }

    /** Reconstrói o formato do fio: varint(id do pacote) + corpo. */
    private void one(ReplayWriter w, Packet<?> p) {
        Integer id = NetworkState.PLAY.getPacketId(NetworkSide.CLIENTBOUND, p);
        if (id == null) return;
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(id);
            p.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            w.append(ReplayFormat.KIND_PACKET, data);
        } finally {
            buf.release();
        }
    }

    // ================= thread principal =================

    public void tick(MinecraftClient mc) {
        try {
            ReplayWriter w = writer;
            if (w == null) return;
            ClientPlayerEntity p = mc.player;
            if (p == null || mc.world == null || mc.isPaused()) return;

            w.append(ReplayFormat.KIND_SELF, sample(p).encode());

            if (settings.thumbnails && !thumbDone && thumbTries < 20 && w.elapsedMs() > 5000 && mc.currentScreen == null) {
                thumbTries++;
                byte[] png = captureThumbnail(mc);
                if (png != null) { w.setThumbnail(png); thumbDone = true; }
            }
            if (w.error().isPresent()) fail(w);
        } catch (Throwable t) {
            System.err.println("[TimeScute] Erro no tick da gravação: " + t);
        }
    }

    private static SelfSample sample(ClientPlayerEntity p) {
        int f = 0;
        if (p.isSneaking()) f |= SelfSample.SNEAKING;
        if (p.isSprinting()) f |= SelfSample.SPRINTING;
        if (p.isOnGround()) f |= SelfSample.ON_GROUND;
        if (p.handSwinging) f |= SelfSample.SWINGING;
        if (p.isFallFlying()) f |= SelfSample.FALL_FLYING;
        if (p.isSwimming()) f |= SelfSample.SWIMMING;
        return new SelfSample(p.getId(), p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), p.getHeadYaw(),
                f, p.getInventory().selectedSlot, p.getHealth());
    }

    private static byte[] captureThumbnail(MinecraftClient mc) {
        try (NativeImage full = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) {
            int w = 320, h = Math.max(1, Math.round(320f * full.getHeight() / full.getWidth()));
            try (NativeImage small = new NativeImage(w, h, false)) {
                full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
                return small.getBytes();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ================= controle =================

    public void onToggleKey(MinecraftClient mc) {
        if (isRecording()) {
            stop(null);
            say(mc, "gravação finalizada e salva");
        } else if (!settings.autoRecord) {
            say(mc, "a gravação automática está desligada (Replays > Gravação automática)");
        } else {
            say(mc, "a gravação começa sozinha ao entrar em um mundo ou servidor");
        }
    }

    public void marker(MinecraftClient mc) {
        ReplayWriter w = writer;
        if (w == null) { say(mc, "não há gravação ativa para marcar"); return; }
        markerCount++;
        w.addMarker("Marcador " + markerCount);
        say(mc, "marcador " + markerCount + " em " + ReplayLibrary.formatDuration(w.elapsedMs()));
    }

    /** Para e fecha o arquivo em segundo plano. {@code reason} aparece nos metadados (pode ser null). */
    public void stop(String reason) {
        ReplayWriter w;
        synchronized (this) {
            w = writer;
            writer = null;
            connection = null;
        }
        if (w == null) return;
        w.setStopReason(reason);
        closer.submit(w::close);
    }

    /** Ao fechar o jogo: fecha tudo e espera terminar (para o arquivo não ficar sem rodapé). */
    public void shutdown() {
        stop(null);
        closer.shutdown();
        try { closer.awaitTermination(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void fail(ReplayWriter w) {
        synchronized (this) {
            if (writer != w) return;
            writer = null;
            connection = null;
        }
        notifyError("Gravação interrompida: " + w.error().orElse("erro desconhecido"));
        closer.submit(w::close);
    }

    private void notifyError(String msg) {
        lastError = msg;
        errorUntil = System.currentTimeMillis() + 15_000;
        System.err.println("[TimeScute] " + msg);
    }

    private static void say(MinecraftClient mc, String msg) {
        if (mc.inGameHud != null) mc.inGameHud.setOverlayMessage(Text.literal("TimeScute: " + msg), false);
    }

    // ================= HUD =================

    public void renderHud(DrawContext ctx, MinecraftClient mc) {
        if (mc.options.hudHidden) return;
        int right = mc.getWindow().getScaledWidth() - 6, y = 6;
        ReplayWriter w = writer;
        if (w != null && !w.isClosed()) {
            long ms = w.elapsedMs();
            String txt = "REC " + ReplayLibrary.formatDuration(ms) + "  " + ReplayLibrary.formatSize(w.bytesWritten());
            int tw = mc.textRenderer.getWidth(txt);
            ctx.fill(right - tw - 18, y - 2, right + 3, y + 11, 0x99000000);
            if ((ms / 600) % 2 == 0) ctx.fill(right - tw - 13, y + 1, right - tw - 6, y + 8, RED);
            ctx.drawTextWithShadow(mc.textRenderer, txt, right - tw, y + 1, 0xFFFFFFFF);
            y += 16;
        }
        if (System.currentTimeMillis() < errorUntil) {
            int tw = mc.textRenderer.getWidth(lastError);
            ctx.fill(right - tw - 6, y - 2, right + 3, y + 11, 0xCC000000);
            ctx.drawTextWithShadow(mc.textRenderer, lastError, right - tw, y + 1, 0xFFFF6B6B);
        }
    }
}
