package dev.timescute.core;

/**
 * Uma camada de animação sobre uma parte. Aplicadas em ordem sobre a pose do jogo:
 * substituir = mistura até a pose (peso 1 = a animação manda); somar = acrescenta a pose por cima.
 */
public record PartLayer(PartPose pose, double weight, boolean additive) {

    /** Aplica esta camada sobre uma pose já calculada (o jogo + camadas anteriores). */
    public PartPose applyOver(PartPose base) {
        double w = Math.max(0, Math.min(1, weight));
        if (additive) {
            return new PartPose(base.pitch() + pose.pitch() * w, base.yaw() + pose.yaw() * w, base.roll() + pose.roll() * w,
                    base.x() + pose.x() * w, base.y() + pose.y() * w, base.z() + pose.z() * w,
                    base.sx() * (1 + (pose.sx() - 1) * w), base.sy() * (1 + (pose.sy() - 1) * w), base.sz() * (1 + (pose.sz() - 1) * w));
        }
        return new PartPose(lerp(base.pitch(), pose.pitch(), w), lerp(base.yaw(), pose.yaw(), w), lerp(base.roll(), pose.roll(), w),
                lerp(base.x(), pose.x(), w), lerp(base.y(), pose.y(), w), lerp(base.z(), pose.z(), w),
                lerp(base.sx(), pose.sx(), w), lerp(base.sy(), pose.sy(), w), lerp(base.sz(), pose.sz(), w));
    }

    private static double lerp(double a, double b, double w) { return a + (b - a) * w; }
}
