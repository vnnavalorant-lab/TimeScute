package dev.timescute.core;

/** Um instante da performance do ator: onde ele está, para onde olha e o que faz. {@code timeMs} é relativo ao início da gravação. */
public record ActorFrame(long timeMs, double x, double y, double z,
                         float yaw, float pitch, float headYaw, float bodyYaw,
                         int flags, int slot, String item) {
    public static final int SNEAKING = 1, SPRINTING = 2, ON_GROUND = 4, SWINGING = 8;

    public ActorFrame {
        if (item == null) item = "";
    }

    public boolean has(int flag) { return (flags & flag) != 0; }

    /** Interpola posição e ângulos; flags, slot e item vêm do quadro anterior (não se interpolam). */
    public static ActorFrame lerp(ActorFrame a, ActorFrame b, double u, long timeMs) {
        return new ActorFrame(timeMs,
                a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u,
                (float) (a.yaw + CameraState.angleDelta(a.yaw, b.yaw) * u),
                (float) (a.pitch + (b.pitch - a.pitch) * u),
                (float) (a.headYaw + CameraState.angleDelta(a.headYaw, b.headYaw) * u),
                (float) (a.bodyYaw + CameraState.angleDelta(a.bodyYaw, b.bodyYaw) * u),
                a.flags, a.slot, a.item);
    }
}
