package dev.timescute.core;

/** Estado completo da câmera num instante. Ângulos em graus. */
public record CameraState(double x, double y, double z,
                          double yaw, double pitch, double roll, double fov) {

    /** Menor diferença angular de a para b, em (-180, 180]. */
    public static double angleDelta(double a, double b) {
        double d = (b - a) % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d <= -180.0) d += 360.0;
        return d;
    }

    /** Interpola posição linearmente; ângulos pelo caminho mais curto. */
    public static CameraState lerp(CameraState a, CameraState b, double t) {
        return new CameraState(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t,
                a.yaw + angleDelta(a.yaw, b.yaw) * t,
                a.pitch + (b.pitch - a.pitch) * t,
                a.roll + angleDelta(a.roll, b.roll) * t,
                a.fov + (b.fov - a.fov) * t);
    }

    public CameraState withPosition(double nx, double ny, double nz) {
        return new CameraState(nx, ny, nz, yaw, pitch, roll, fov);
    }
}
