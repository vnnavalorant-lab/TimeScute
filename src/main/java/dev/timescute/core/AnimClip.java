package dev.timescute.core;

import java.util.*;

/** Uma animação (como as do Blockbench): ossos com canais de rotação, posição e escala ao longo do tempo. */
public final class AnimClip {
    public enum Loop { ONCE, LOOP, HOLD }

    /** Um keyframe: valor antes ({@code pre}) e depois ({@code post}) do instante (iguais, salvo descontinuidade). */
    public record Key(double time, Molang.Expr[] pre, Molang.Expr[] post, boolean smooth, boolean step) { }

    /** Valor de um osso num instante, no espaço do ARQUIVO (graus e pixels), antes de converter para o Java. */
    public record BoneValue(double[] rot, double[] pos, double[] scale) { }

    /** Os três valores de um canal ao longo do tempo. */
    public static final class Channel {
        private final Key[] keys;
        private final double[] fallback;

        public Channel(List<Key> keys, double[] fallback) {
            this.keys = keys.stream().sorted(Comparator.comparingDouble(Key::time)).toArray(Key[]::new);
            this.fallback = fallback;
        }

        public boolean isEmpty() { return keys.length == 0; }
        public int size() { return keys.length; }
        public double lastTime() { return keys.length == 0 ? 0 : keys[keys.length - 1].time(); }

        public double[] sample(double t) {
            if (keys.length == 0) return fallback.clone();
            if (t <= keys[0].time()) return eval(keys[0].post(), t);
            Key last = keys[keys.length - 1];
            if (t >= last.time()) return eval(last.post(), t);
            int i = 0;
            while (keys[i + 1].time() <= t) i++;
            Key a = keys[i], b = keys[i + 1];
            double[] va = eval(a.post(), t), vb = eval(b.pre(), t);
            if (a.step()) return va;
            double u = (t - a.time()) / (b.time() - a.time());
            if (a.smooth() || b.smooth()) {
                double[] p0 = i > 0 ? eval(keys[i - 1].post(), t) : va;
                double[] p3 = i + 2 < keys.length ? eval(keys[i + 2].pre(), t) : vb;
                double[] out = new double[3];
                for (int k = 0; k < 3; k++) out[k] = catmull(p0[k], va[k], vb[k], p3[k], u);
                return out;
            }
            return new double[]{va[0] + (vb[0] - va[0]) * u, va[1] + (vb[1] - va[1]) * u, va[2] + (vb[2] - va[2]) * u};
        }

        private static double[] eval(Molang.Expr[] e, double t) { return new double[]{e[0].eval(t), e[1].eval(t), e[2].eval(t)}; }

        private static double catmull(double p0, double p1, double p2, double p3, double t) {
            double t2 = t * t, t3 = t2 * t;
            return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
        }
    }

    public record BoneTrack(String bone, Channel rotation, Channel position, Channel scale) { }

    private final String name;
    private final double length;
    private final Loop loop;
    private final Map<String, BoneTrack> bones;
    private final boolean builtin;

    public AnimClip(String name, double length, Loop loop, Map<String, BoneTrack> bones, boolean builtin) {
        this.name = name;
        this.loop = loop;
        this.bones = Collections.unmodifiableMap(new LinkedHashMap<>(bones));
        double len = length;
        if (len <= 0) for (BoneTrack b : bones.values()) len = Math.max(len, Math.max(b.rotation().lastTime(), Math.max(b.position().lastTime(), b.scale().lastTime())));
        this.length = len;
        this.builtin = builtin;
    }

    public String name() { return name; }
    public double length() { return length; }
    public Loop loop() { return loop; }
    public boolean builtin() { return builtin; }
    public Map<String, BoneTrack> bones() { return bones; }

    /** Ossos do arquivo que o modelo do jogador não tem (não serão aplicados). */
    public List<String> unmappedBones() {
        List<String> out = new ArrayList<>();
        for (String b : bones.keySet()) if (Part.fromBone(b) == null) out.add(b);
        return out;
    }

    /** Há algo de movimento neste clipe para o jogador? */
    public boolean affectsPlayer() { return bones.keySet().stream().anyMatch(b -> Part.fromBone(b) != null); }

    /**
     * Tempo efetivo dentro do clipe, ou -1 se o clipe já terminou (ONCE): aí as partes voltam ao normal.
     */
    public double effectiveTime(double t) {
        if (t < 0) return 0;
        if (length <= 0) return 0;
        return switch (loop) {
            case LOOP -> t % length;
            case HOLD -> Math.min(t, length);
            case ONCE -> t > length ? -1 : t;
        };
    }

    /** Valores dos ossos no instante {@code t} (segundos desde o começo do clipe). */
    public Map<String, BoneValue> sample(double t) {
        double et = effectiveTime(t);
        if (et < 0) return Map.of();
        Map<String, BoneValue> out = new LinkedHashMap<>();
        for (BoneTrack b : bones.values()) out.put(b.bone(), new BoneValue(b.rotation().sample(et), b.position().sample(et), b.scale().sample(et)));
        return out;
    }
}
