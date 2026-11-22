package dev.timescute.client;

import com.mojang.authlib.GameProfile;
import dev.timescute.core.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Faz os atores aparecerem no seu mundo ao vivo. Cada ator é uma entidade só do seu cliente (o servidor não sabe
 * dela), movida pela performance gravada e pelas reações da timeline.
 */
public final class ActorManager {
    private static final ActorManager INSTANCE = new ActorManager();
    public static ActorManager get() { return INSTANCE; }

    /** Ids negativos: nunca colidem com os de entidades do servidor. */
    private static final int ID_BASE = -2000;

    private static final class Live {
        OtherClientPlayerEntity entity;
        ClientWorld world;
        int entityId, lastSwings;
        boolean prevSwingFlag;
        String item = "", skin = "";
        PlayerListEntry skinEntry;
        Map<Part, List<PartLayer>> layers = Map.of();
        Map<String, List<PartLayer>> boneLayers = Map.of();
        String model = "";
    }

    /** O que o desenho precisa de um ator com modelo do Blockbench. */
    public record ModelView(GeoModel model, Identifier texture, Map<String, List<PartLayer>> layers, String key) { }

    private final ClipLibrary clips = new ClipLibrary();
    private final Map<Long, Live> live = new HashMap<>();
    private long recordingActorId = -1;
    private long lastStage = Long.MIN_VALUE;
    private int version, lastVersion = -1;

    private ActorManager() { }

    // ================= animações importadas =================

    public ClipLibrary clips() { return clips; }
    public Path animationsDir() { return FabricLoader.getInstance().getConfigDir().resolve("timescute").resolve("animations"); }

    /** Relê a pasta de animações. Devolve os avisos/erros para mostrar ao usuário. */
    public List<String> reloadAnimations() {
        try {
            AnimFiles.Scan sc = AnimFiles.scan(animationsDir());
            ModelManager.get().clearClipCache();
            clips.clearImported();
            clips.addImported(sc.clips());
            AnimFiles.loadConfig(animationsDir(), clips);
            markDirty();
            return sc.messages();
        } catch (IOException e) {
            return List.of("Não foi possível ler a pasta de animações: " + e.getMessage());
        }
    }

    public void saveClipConfig() {
        try { AnimFiles.saveConfig(animationsDir(), clips); }
        catch (IOException e) { System.err.println("[TimeScute] Não foi possível salvar a configuração das animações: " + e); }
        markDirty();
    }

    // ================= estado =================

    /** A cena mudou (edição, desfazer, abrir...): os atores devem ser recalculados. */
    public void markDirty() { version++; }

    public void setRecordingActor(long id) { recordingActorId = id; markDirty(); }

    public boolean isActor(Object entity) { return find(entity) != null; }

    private Live find(Object entity) {
        for (Live l : live.values()) if (l.entity == entity) return l;
        return null;
    }

    /** Camadas de animação do ator (para o modelo desenhar), ou null se não é um ator. */
    public Map<Part, List<PartLayer>> layersFor(Object entity) {
        Live l = find(entity);
        return l == null || l.layers.isEmpty() ? null : l.layers;
    }

    /** Modelo do Blockbench do ator (para o mixin do renderizador), ou null: desenha o jogador normalmente. */
    public ModelView modelView(Object entity) {
        Live l = find(entity);
        if (l == null || l.model.isEmpty()) return null;
        GeoModel gm = ModelManager.get().model(l.model);
        if (gm == null) return null; // modelo apagado: cai para o jogador
        return new ModelView(gm, ModelManager.get().texture(l.model), l.boneLayers, l.model);
    }

    /** Pele do ator (para o mixin da pele), ou null para o comportamento normal do jogo. */
    public PlayerListEntry skinEntry(Object entity) {
        Live l = find(entity);
        return l == null ? null : l.skinEntry;
    }

    // ================= laço =================

    /** A cada tick do cliente: com o tempo correndo, atualiza os atores (movimento suave). */
    public void tick(MinecraftClient mc) {
        CameraDirector dir = CameraDirector.get();
        if (mc.world == null || mc.player == null) { live.clear(); return; }
        if (!dir.isStageActive()) { if (!live.isEmpty()) despawnAll(mc); return; }
        if (dir.isPlaying() || dir.isRendering()) {
            apply(mc, dir.playheadMs(), false);
            lastStage = dir.playheadMs();
            lastVersion = version;
        }
    }

    /** A cada quadro: arrastar a timeline (sem tocar) posiciona os atores na hora. */
    public void frame(MinecraftClient mc) {
        CameraDirector dir = CameraDirector.get();
        if (mc.world == null || mc.player == null || !dir.isStageActive() || dir.isPlaying() || dir.isRendering()) return;
        long stage = dir.playheadMs();
        if (stage == lastStage && version == lastVersion) return;
        apply(mc, stage, true);
        lastStage = stage;
        lastVersion = version;
    }

    /** Põe os atores no instante atual da timeline na hora (usado ao começar a renderizar). */
    public void prime(MinecraftClient mc) {
        CameraDirector dir = CameraDirector.get();
        if (mc.world == null || mc.player == null) return;
        apply(mc, dir.playheadMs(), true);
        lastStage = dir.playheadMs();
        lastVersion = version;
    }

    public void despawnAll(MinecraftClient mc) {
        for (Long id : new ArrayList<>(live.keySet())) despawn(mc, id);
        lastStage = Long.MIN_VALUE;
    }

    private void apply(MinecraftClient mc, long stage, boolean snap) {
        CameraDirector dir = CameraDirector.get();
        Set<Long> seen = new HashSet<>();
        for (Actor a : dir.timeline.actors().all()) {
            if (a.id() == recordingActorId) continue; // o ator que está sendo gravado não se vê
            ActorPose pose = ActorPlayer.evaluate(a, stage, clips);
            Live l = live.get(a.id());
            if (!pose.visible) continue;
            seen.add(a.id());
            if (l == null || l.world != mc.world || l.entity.isRemoved()) l = spawn(mc, a, pose);
            drive(mc, a, l, pose, snap);
        }
        for (Long id : new ArrayList<>(live.keySet())) if (!seen.contains(id)) despawn(mc, id);
    }

    private Live spawn(MinecraftClient mc, Actor a, ActorPose pose) {
        UUID uuid = UUID.nameUUIDFromBytes(("timescute-actor-" + a.id()).getBytes(StandardCharsets.UTF_8));
        OtherClientPlayerEntity e = new OtherClientPlayerEntity(mc.world, new GameProfile(uuid, a.name()));
        Live l = new Live();
        l.entity = e;
        l.world = mc.world;
        l.entityId = ID_BASE - (int) (a.id() % 1_000_000);
        ActorFrame f = pose.frame;
        e.setId(l.entityId);
        e.refreshPositionAndAngles(f.x(), f.y(), f.z(), f.yaw(), f.pitch());
        mc.world.addEntity(l.entityId, e);
        live.put(a.id(), l);
        return l;
    }

    private void despawn(MinecraftClient mc, long actorId) {
        Live l = live.remove(actorId);
        if (l != null && mc.world != null && l.world == mc.world) mc.world.removeEntity(l.entityId, Entity.RemovalReason.DISCARDED);
    }

    private void drive(MinecraftClient mc, Actor a, Live l, ActorPose pose, boolean snap) {
        ActorFrame f = pose.frame;
        OtherClientPlayerEntity e = l.entity;
        float pitch = f.pitch(), head = f.headYaw();

        if (pose.look != null && pose.lookWeight > 0) { // olhar para um ponto ou entidade
            double[] t = lookTarget(mc, pose.look);
            double eye = f.y() + (f.has(ActorFrame.SNEAKING) ? 1.27 : 1.62);
            float[] yp = LookMath.yawPitch(f.x(), eye, f.z(), t[0], t[1], t[2]);
            head = (float) LookMath.blendAngle(head, yp[0], pose.lookWeight);
            pitch = (float) LookMath.blendAngle(pitch, yp[1], pose.lookWeight);
        }

        if (snap) e.refreshPositionAndAngles(f.x(), f.y(), f.z(), f.yaw(), pitch);
        else e.updateTrackedPositionAndAngles(f.x(), f.y(), f.z(), f.yaw(), pitch, 1, false);
        e.setHeadYaw(head);
        e.setBodyYaw(f.bodyYaw());
        e.setOnGround(f.has(ActorFrame.ON_GROUND));
        e.setSneaking(f.has(ActorFrame.SNEAKING));
        e.setSprinting(f.has(ActorFrame.SPRINTING));

        if (!pose.item.equals(l.item)) { e.equipStack(EquipmentSlot.MAINHAND, stackOf(pose.item)); l.item = pose.item; }

        boolean flag = f.has(ActorFrame.SWINGING);
        if (!snap) {
            if (flag && !l.prevSwingFlag) e.swingHand(Hand.MAIN_HAND);                    // golpe gravado na performance
            if (pose.swingCount > l.lastSwings) e.swingHand(pose.swingOffHand ? Hand.OFF_HAND : Hand.MAIN_HAND); // golpe de uma reação
        }
        l.prevSwingFlag = flag;
        l.lastSwings = pose.swingCount;
        l.layers = pose.layers;
        l.boneLayers = pose.boneLayers;
        l.model = a.model();

        if (!a.skin().equals(l.skin) || l.skinEntry == null) { l.skin = a.skin(); l.skinEntry = skinFor(mc, a.skin()); }
    }

    private static double[] lookTarget(MinecraftClient mc, Reaction r) {
        if (!r.targetEntity().isEmpty()) {
            try {
                UUID id = UUID.fromString(r.targetEntity());
                for (Entity en : mc.world.getEntities()) if (id.equals(en.getUuid())) return new double[]{en.getEyePos().x, en.getEyePos().y, en.getEyePos().z};
            } catch (IllegalArgumentException ignored) { /* uuid ruim: usa o ponto guardado */ }
        }
        return new double[]{r.tx(), r.ty(), r.tz()};
    }

    private static PlayerListEntry skinFor(MinecraftClient mc, String skin) {
        ClientPlayNetworkHandler h = mc.getNetworkHandler();
        if (h == null || mc.player == null) return null;
        return skin.isEmpty() ? h.getPlayerListEntry(mc.player.getUuid()) : h.getPlayerListEntry(skin);
    }

    private static ItemStack stackOf(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return ItemStack.EMPTY;
        return Registries.ITEM.getOrEmpty(ident).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }
}
