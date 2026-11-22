package dev.timescute.client;

import dev.timescute.core.Convention;
import dev.timescute.core.GeoModel;
import dev.timescute.core.PartPose;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;

import java.util.function.Function;

/**
 * Desenha um modelo do Blockbench (geometria Bedrock). Reproduz o que o jogo faz com um ModelPart: os ossos viram "partes"
 * no espaço do modelo do Java (Y para baixo, raiz a 24 pixels do chão) e os cubos seguem o mesmo mapa de UV do jogo.
 * A matriz que chega já deve ter o giro do corpo, {@code scale(-1,-1,1)} e {@code translate(0,-1.501,0)} (como no jogador).
 */
public final class GeoRenderer {
    private GeoRenderer() { }

    private static final int MAX_DEPTH = 64;

    public static void render(MatrixStack m, VertexConsumer vc, GeoModel gm, Function<String, PartPose> poseOf, int light, int overlay) {
        for (GeoModel.Bone root : gm.roots()) bone(m, vc, gm, root, null, poseOf, light, overlay, 0);
    }

    private static void bone(MatrixStack m, VertexConsumer vc, GeoModel gm, GeoModel.Bone b, GeoModel.Bone parent,
                             Function<String, PartPose> poseOf, int light, int overlay, int depth) {
        if (depth > MAX_DEPTH) return;
        PartPose ps = poseOf.apply(b.name());
        if (ps == null) ps = PartPose.REST;

        // pivô relativo ao pai, no espaço do Java (Y invertido); a raiz é medida a partir de y = 24
        double rx, ry, rz;
        if (parent == null) { rx = b.pivot()[0]; ry = 24 - b.pivot()[1]; rz = b.pivot()[2]; }
        else { rx = b.pivot()[0] - parent.pivot()[0]; ry = -(b.pivot()[1] - parent.pivot()[1]); rz = b.pivot()[2] - parent.pivot()[2]; }

        m.push();
        m.translate((rx + ps.x()) / 16.0, (ry + ps.y()) / 16.0, (rz + ps.z()) / 16.0);
        double pitch = ps.pitch() + baseAngle(b.rotation(), 0), yaw = ps.yaw() + baseAngle(b.rotation(), 1), roll = ps.roll() + baseAngle(b.rotation(), 2);
        if (pitch != 0 || yaw != 0 || roll != 0) m.multiply(new Quaternionf().rotationZYX((float) roll, (float) yaw, (float) pitch));
        if (ps.sx() != 1 || ps.sy() != 1 || ps.sz() != 1) m.scale((float) ps.sx(), (float) ps.sy(), (float) ps.sz());

        for (GeoModel.Cube c : b.cubes()) cube(m, vc, gm, b, c, light, overlay);
        for (GeoModel.Bone child : gm.childrenOf(b.name())) bone(m, vc, gm, child, b, poseOf, light, overlay, depth + 1);
        m.pop();
    }

    /** Giro do arquivo (graus) para o do Java (radianos), com os mesmos sinais usados nas animações Bedrock. */
    private static double baseAngle(double[] rot, int axis) {
        return rot == null ? 0 : Math.toRadians(rot[axis]) * Convention.BEDROCK.rotSign(axis) + 0.0;
    }

    private static void cube(MatrixStack m, VertexConsumer vc, GeoModel gm, GeoModel.Bone b, GeoModel.Cube c, int light, int overlay) {
        boolean rotated = c.hasRotation();
        if (rotated) {
            double[] cp = c.pivot() != null ? c.pivot() : b.pivot();
            float px = (float) ((cp[0] - b.pivot()[0]) / 16.0), py = (float) (-(cp[1] - b.pivot()[1]) / 16.0), pz = (float) ((cp[2] - b.pivot()[2]) / 16.0);
            m.push();
            m.translate(px, py, pz);
            m.multiply(new Quaternionf().rotationZYX((float) baseAngle(c.rotation(), 2), (float) baseAngle(c.rotation(), 1), (float) baseAngle(c.rotation(), 0)));
            m.translate(-px, -py, -pz);
        }

        // canto mínimo do cubo, relativo ao pivô do osso, no espaço do Java (Y para baixo)
        float sx = (float) c.size()[0], sy = (float) c.size()[1], sz = (float) c.size()[2];
        float x = (float) (c.origin()[0] - b.pivot()[0]);
        float y = (float) (-((c.origin()[1] - b.pivot()[1]) + c.size()[1]));
        float z = (float) (c.origin()[2] - b.pivot()[2]);
        float e = (float) c.inflate();
        boolean mirror = c.mirror() != null ? c.mirror() : b.mirror();

        float f = x + sx + e, g = y + sy + e, h = z + sz + e;
        x -= e; y -= e; z -= e;
        if (mirror) { float t = f; f = x; x = t; }

        float[] v1 = {x, y, z}, v2 = {f, y, z}, v3 = {f, g, z}, v4 = {x, g, z};
        float[] v5 = {x, y, h}, v6 = {f, y, h}, v7 = {f, g, h}, v8 = {x, g, h};

        float tw = gm.texW(), th = gm.texH();
        MatrixStack.Entry en = m.peek();

        if (c.boxUv() != null) {
            float u = (float) c.boxUv()[0], v = (float) c.boxUv()[1];
            float j = u, k = u + sz, l = u + sz + sx, mm = u + sz + sx + sx, n = u + sz + sx + sz, o = u + sz + sx + sz + sx;
            float p = v, q = v + sz, r = v + sz + sy;
            quad(en, vc, new float[][]{v6, v5, v1, v2}, k, p, l, q, tw, th, mirror, 0, -1, 0, light, overlay);   // topo
            quad(en, vc, new float[][]{v3, v4, v8, v7}, l, q, mm, p, tw, th, mirror, 0, 1, 0, light, overlay);   // base
            quad(en, vc, new float[][]{v1, v5, v8, v4}, j, q, k, r, tw, th, mirror, -1, 0, 0, light, overlay);   // oeste
            quad(en, vc, new float[][]{v2, v1, v4, v3}, k, q, l, r, tw, th, mirror, 0, 0, -1, light, overlay);   // norte
            quad(en, vc, new float[][]{v6, v2, v3, v7}, l, q, n, r, tw, th, mirror, 1, 0, 0, light, overlay);    // leste
            quad(en, vc, new float[][]{v5, v6, v7, v8}, n, q, o, r, tw, th, mirror, 0, 0, 1, light, overlay);    // sul
        } else if (c.faceUv() != null) {
            face(c, "up", en, vc, new float[][]{v6, v5, v1, v2}, tw, th, mirror, 0, -1, 0, light, overlay);
            face(c, "down", en, vc, new float[][]{v3, v4, v8, v7}, tw, th, mirror, 0, 1, 0, light, overlay);
            face(c, "west", en, vc, new float[][]{v1, v5, v8, v4}, tw, th, mirror, -1, 0, 0, light, overlay);
            face(c, "north", en, vc, new float[][]{v2, v1, v4, v3}, tw, th, mirror, 0, 0, -1, light, overlay);
            face(c, "east", en, vc, new float[][]{v6, v2, v3, v7}, tw, th, mirror, 1, 0, 0, light, overlay);
            face(c, "south", en, vc, new float[][]{v5, v6, v7, v8}, tw, th, mirror, 0, 0, 1, light, overlay);
        }
        if (rotated) m.pop();
    }

    private static void face(GeoModel.Cube c, String name, MatrixStack.Entry en, VertexConsumer vc, float[][] verts, float tw, float th,
                             boolean mirror, float nx, float ny, float nz, int light, int overlay) {
        double[] uv = c.faceUv().get(name);
        if (uv == null) return;
        quad(en, vc, verts, (float) uv[0], (float) uv[1], (float) (uv[0] + uv[2]), (float) (uv[1] + uv[3]), tw, th, mirror, nx, ny, nz, light, overlay);
    }

    /** Mesmo mapeamento de vértices e UV do ModelPart.Quad do jogo (inclusive o espelhamento). */
    private static void quad(MatrixStack.Entry en, VertexConsumer vc, float[][] vs, float u1, float v1, float u2, float v2, float tw, float th,
                             boolean flip, float nx, float ny, float nz, int light, int overlay) {
        float[][] uv = {{u2 / tw, v1 / th}, {u1 / tw, v1 / th}, {u1 / tw, v2 / th}, {u2 / tw, v2 / th}};
        int[] order = flip ? new int[]{3, 2, 1, 0} : new int[]{0, 1, 2, 3};
        if (flip) nx = -nx;
        for (int i : order) {
            vc.vertex(en.getPositionMatrix(), vs[i][0] / 16f, vs[i][1] / 16f, vs[i][2] / 16f)
                    .color(255, 255, 255, 255)
                    .texture(uv[i][0], uv[i][1])
                    .overlay(overlay)
                    .light(light)
                    .normal(en.getNormalMatrix(), nx, ny, nz)
                    .next();
        }
    }
}
