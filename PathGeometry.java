package dev.timescute.core;

import java.util.*;

/** Linhas 3D das guias desenhadas no mundo (trajeto e câmeras). Só matemática. */
public final class PathGeometry {
    public record Line(double ax, double ay, double az, double bx, double by, double bz) { }

    /** Trecho do trajeto entre dois keyframes, com a interpolação que o define. */
    public record Segment(Easing easing, List<Line> lines) { }

    private PathGeometry() { }

    /** Vetores da câmera (convenção do Minecraft: yaw 0 olha para +Z; yaw 90 para -X). */
    public static double[] forward(CameraState s) {
        double y = Math.toRadians(s.yaw()), p = Math.toRadians(s.pitch());
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    public static double[] right(CameraState s) {
        double y = Math.toRadians(s.yaw());
        return new double[]{-Math.cos(y), 0, -Math.sin(y)};
    }

    public static double[] up(CameraState s) {
        double[] r = right(s), f = forward(s);
        return new double[]{r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
    }

    /** Pirâmide do campo de visão + marcador de "cima": 8 linhas da pirâmide + 3 do marcador. */
    public static List<Line> frustum(CameraState s, double depth, double aspect) {
        double[] f = forward(s), r = right(s), u = up(s);
        double h = depth * Math.tan(Math.toRadians(Math.max(1, Math.min(170, s.fov())) / 2));
        double w = h * aspect;
        double[][] c = new double[4][];
        int[][] sg = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
        for (int i = 0; i < 4; i++)
            c[i] = new double[]{
                    s.x() + f[0] * depth + r[0] * w * sg[i][0] + u[0] * h * sg[i][1],
                    s.y() + f[1] * depth + r[1] * w * sg[i][0] + u[1] * h * sg[i][1],
                    s.z() + f[2] * depth + r[2] * w * sg[i][0] + u[2] * h * sg[i][1]};
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(new Line(s.x(), s.y(), s.z(), c[i][0], c[i][1], c[i][2]));
            double[] n = c[(i + 1) % 4];
            out.add(new Line(c[i][0], c[i][1], c[i][2], n[0], n[1], n[2]));
        }
        // triângulo acima da moldura: mostra qual lado é "cima"
        double tx = (c[0][0] + c[1][0]) / 2 + u[0] * h * 0.35, ty = (c[0][1] + c[1][1]) / 2 + u[1] * h * 0.35, tz = (c[0][2] + c[1][2]) / 2 + u[2] * h * 0.35;
        out.add(new Line(c[0][0], c[0][1], c[0][2], tx, ty, tz));
        out.add(new Line(tx, ty, tz, c[1][0], c[1][1], c[1][2]));
        out.add(new Line(c[0][0], c[0][1], c[0][2], c[1][0], c[1][1], c[1][2]));
        return out;
    }

    /** Trajeto dividido por segmento, amostrado {@code perSegment} vezes entre cada par de keyframes. */
    public static List<Segment> trail(CameraPath path, int perSegment) {
        List<Segment> out = new ArrayList<>();
        List<Keyframe> ks = path.all();
        int n = Math.max(1, perSegment);
        for (int i = 0; i + 1 < ks.size(); i++) {
            Keyframe a = ks.get(i), b = ks.get(i + 1);
            List<Line> lines = new ArrayList<>();
            CameraState prev = a.state();
            for (int j = 1; j <= n; j++) {
                long t = a.timeMs() + (b.timeMs() - a.timeMs()) * j / n;
                CameraState cur = path.sample(t).orElse(prev);
                lines.add(new Line(prev.x(), prev.y(), prev.z(), cur.x(), cur.y(), cur.z()));
                prev = cur;
            }
            out.add(new Segment(a.easing(), lines));
        }
        return out;
    }
}
