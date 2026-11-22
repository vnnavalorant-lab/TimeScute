package dev.timescute.core;

/** Matemática de olhar (convenção do Minecraft: yaw 0 olha para +Z; yaw -90 para +X; pitch negativo olha para cima). */
public final class LookMath {
    private LookMath() { }

    /** @return {yaw, pitch} em graus para olhar de (ex,ey,ez) até (tx,ty,tz). */
    public static float[] yawPitch(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1e-9 && Math.abs(dy) < 1e-9) return new float[]{0f, 0f};
        float yaw = horizontal < 1e-9 ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    /** Mistura dois ângulos pelo caminho mais curto. w = 0 devolve {@code from}; w = 1 devolve {@code to}. */
    public static double blendAngle(double from, double to, double w) {
        return from + CameraState.angleDelta(from, to) * Math.max(0, Math.min(1, w));
    }

    /** Peso 0..1 ao longo de um bloco: sobe suave no começo, fica em 1, desce suave no fim (edge = fração em cada ponta). */
    public static double smoothWeight(double progress, double edge) {
        double p = Math.max(0, Math.min(1, progress));
        double e = Math.max(1e-6, Math.min(0.5, edge));
        double w = p < e ? p / e : p > 1 - e ? (1 - p) / e : 1;
        return w * w * (3 - 2 * w);
    }
}
