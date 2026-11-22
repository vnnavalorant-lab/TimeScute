package dev.timescute.client;

import dev.timescute.core.*;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Desenha um ator com modelo do Blockbench no lugar do jogador (chamado pelo mixin do PlayerEntityRenderer). */
public final class ActorModelRenderer {
    private ActorModelRenderer() { }

    public static void render(AbstractClientPlayerEntity e, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light,
                              ActorManager.ModelView mv) {
        GeoModel gm = mv.model();
        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw);
        float headYaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevHeadYaw, e.headYaw);
        float pitch = MathHelper.lerp(tickDelta, e.prevPitch, e.getPitch());
        float netHeadYaw = MathHelper.wrapDegrees(headYaw - bodyYaw);
        float limbSpeed = Math.min(1.0f, e.limbAnimator.getSpeed(tickDelta));
        float limbPos = e.limbAnimator.getPos(tickDelta);
        double seconds = (e.age + tickDelta) / 20.0;

        // camadas por osso: andar/parado automáticos (modelos sem ossos de jogador) e depois as reações do ator
        Map<String, List<PartLayer>> layers = new HashMap<>();
        boolean humanoid = gm.looksHumanoid();
        if (!humanoid) addLocomotion(mv.key(), seconds, limbSpeed, layers);
        for (Map.Entry<String, List<PartLayer>> en : mv.layers().entrySet()) layers.computeIfAbsent(en.getKey(), k -> new java.util.ArrayList<>()).addAll(en.getValue());

        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - bodyYaw));
        matrices.scale(-1.0f, -1.0f, 1.0f);
        matrices.translate(0.0, -1.501, 0.0);

        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(mv.texture()));
        GeoRenderer.render(matrices, vc, gm, bone -> {
            PartPose pose = humanoid ? humanoidPose(Part.fromBone(bone), limbPos, limbSpeed, netHeadYaw, pitch) : PartPose.REST;
            List<PartLayer> ls = layers.get(bone);
            if (ls != null) for (PartLayer l : ls) pose = l.applyOver(pose);
            return pose;
        }, light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }

    /** O mesmo balanço de braços e pernas e olhar do jogador, para modelos com ossos de jogador. */
    private static PartPose humanoidPose(Part part, float limbPos, float limbSpeed, float netHeadYaw, float headPitch) {
        if (part == null) return PartPose.REST;
        double a = limbPos * 0.6662;
        return switch (part) {
            case HEAD -> new PartPose(Math.toRadians(headPitch), Math.toRadians(netHeadYaw), 0, 0, 0, 0, 1, 1, 1);
            case RIGHT_ARM -> new PartPose(Math.cos(a + Math.PI) * limbSpeed, 0, 0, 0, 0, 0, 1, 1, 1);
            case LEFT_ARM -> new PartPose(Math.cos(a) * limbSpeed, 0, 0, 0, 0, 0, 1, 1, 1);
            case RIGHT_LEG -> new PartPose(Math.cos(a) * 1.4 * limbSpeed, 0, 0, 0, 0, 0, 1, 1, 1);
            case LEFT_LEG -> new PartPose(Math.cos(a + Math.PI) * 1.4 * limbSpeed, 0, 0, 0, 0, 0, 1, 1, 1);
            default -> PartPose.REST;
        };
    }

    /** Para modelos de outro bicho: toca a animação importada de "andar" quando se move e a de "parado" quando para. */
    private static void addLocomotion(String modelKey, double seconds, float limbSpeed, Map<String, List<PartLayer>> layers) {
        ClipLibrary lib = ActorManager.get().clips();
        double walkW = Math.max(0, Math.min(1, limbSpeed * 3.0));
        add(modelKey, "walk", seconds, walkW, lib, layers);
        add(modelKey, "idle", seconds, 1.0 - walkW, lib, layers);
    }

    private static void add(String modelKey, String kind, double seconds, double weight, ClipLibrary lib, Map<String, List<PartLayer>> layers) {
        if (weight <= 0.001) return;
        String name = ModelManager.get().clipFor(modelKey, kind);
        if (name == null) return;
        AnimClip clip = lib.clip(name).orElse(null);
        if (clip == null) return;
        Map<String, AnimClip.BoneValue> sample = clip.sample(seconds);
        AnimLibrary.Config cfg = lib.config(name);
        for (Map.Entry<String, AnimClip.BoneValue> en : sample.entrySet())
            layers.computeIfAbsent(en.getKey(), k -> new java.util.ArrayList<>()).add(new PartLayer(cfg.toPose(en.getValue()), weight, false));
    }
}
