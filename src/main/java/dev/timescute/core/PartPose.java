package dev.timescute.core;

/** Pose de uma parte em unidades do modelo do Java: rotação em radianos (pitch, yaw, roll), posição em pixels, escala. */
public record PartPose(double pitch, double yaw, double roll, double x, double y, double z,
                       double sx, double sy, double sz) {
    public static final PartPose REST = new PartPose(0, 0, 0, 0, 0, 0, 1, 1, 1);

    /** Espelha esquerda/direita: gira em Y e Z ao contrário e inverte o deslocamento lateral. */
    public PartPose mirrored() { return new PartPose(pitch, neg(yaw), neg(roll), neg(x), y, z, sx, sy, sz); }

    /** Negativo sem gerar -0.0 (que seria diferente de 0.0 numa comparação de poses). */
    private static double neg(double v) { return 0.0 - v; }

    public PartPose scaledRotation(double k) { return new PartPose(pitch * k, yaw * k, roll * k, x * k, y * k, z * k, sx, sy, sz); }
}
