package dev.timescute.client;

import dev.timescute.core.Keyframe;
import dev.timescute.core.PathGeometry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/** Desenha no mundo a linha do trajeto e uma "pirâmide de câmera" em cada keyframe. */
public final class PathRenderer {
    private PathRenderer() { }

    public static void register() {
        WorldRenderEvents.LAST.register(PathRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        CameraDirector d = CameraDirector.get();
        var path = d.timeline.path();
        if (!d.showGuides() || path.isEmpty() || d.isPlaying()) return;
        if (!(ctx.consumers() instanceof VertexConsumerProvider.Immediate imm)) return;

        MatrixStack.Entry entry = ctx.matrixStack().peek();
        Vec3d cam = ctx.camera().getPos();
        VertexConsumer vc = imm.getBuffer(RenderLayer.getLines());

        for (PathGeometry.Segment seg : PathGeometry.trail(path, 24)) {
            int color = TimelineScreen.colorOf(seg.easing());
            for (PathGeometry.Line l : seg.lines()) put(vc, entry, cam, l, color);
        }

        for (Keyframe k : path.all()) {
            double dx = k.state().x() - cam.x, dy = k.state().y() - cam.y, dz = k.state().z() - cam.z;
            if (dx * dx + dy * dy + dz * dz < 0.25) continue; // estamos dentro dela (preview): não desenha
            int color = d.selection.contains(k.id()) ? 0xFFFFFFFF : TimelineScreen.colorOf(k.easing());
            for (PathGeometry.Line l : PathGeometry.frustum(k.state(), 0.8, 16.0 / 9.0)) put(vc, entry, cam, l, color);
        }

        imm.draw(RenderLayer.getLines());
    }

    /** Subtrai a câmera em double (evita tremor em coordenadas grandes) e só então converte para float. */
    private static void put(VertexConsumer vc, MatrixStack.Entry e, Vec3d cam, PathGeometry.Line l, int argb) {
        float ax = (float) (l.ax() - cam.x), ay = (float) (l.ay() - cam.y), az = (float) (l.az() - cam.z);
        float bx = (float) (l.bx() - cam.x), by = (float) (l.by() - cam.y), bz = (float) (l.bz() - cam.z);
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) return;
        dx /= len; dy /= len; dz /= len;
        int a = (argb >>> 24) & 255, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        vc.vertex(e.getPositionMatrix(), ax, ay, az).color(r, g, b, a).normal(e.getNormalMatrix(), dx, dy, dz).next();
        vc.vertex(e.getPositionMatrix(), bx, by, bz).color(r, g, b, a).normal(e.getNormalMatrix(), dx, dy, dz).next();
    }
}
