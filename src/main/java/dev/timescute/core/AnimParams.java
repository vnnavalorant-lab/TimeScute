package dev.timescute.core;

/** Como uma reação de animação usa o clipe: quais partes, quão rápido, quão forte, espelhada etc. */
public record AnimParams(String clip, int partMask, double speed, double weight,
                         boolean additive, boolean mirror, boolean fit) {
    public static final AnimParams DEFAULT = new AnimParams("", Part.ALL, 1.0, 1.0, false, false, false);

    public AnimParams {
        if (clip == null) clip = "";
        partMask &= Part.ALL;
        speed = Double.isNaN(speed) ? 1.0 : Math.max(0.05, Math.min(8.0, speed));
        weight = Double.isNaN(weight) ? 1.0 : Math.max(0, Math.min(1, weight));
    }

    public AnimParams withClip(String c) { return new AnimParams(c, partMask, speed, weight, additive, mirror, fit); }
    public AnimParams withMask(int m) { return new AnimParams(clip, m, speed, weight, additive, mirror, fit); }
    public AnimParams withSpeed(double s) { return new AnimParams(clip, partMask, s, weight, additive, mirror, fit); }
    public AnimParams withWeight(double w) { return new AnimParams(clip, partMask, speed, w, additive, mirror, fit); }
    public AnimParams withAdditive(boolean a) { return new AnimParams(clip, partMask, speed, weight, a, mirror, fit); }
    public AnimParams withMirror(boolean m) { return new AnimParams(clip, partMask, speed, weight, additive, m, fit); }
    public AnimParams withFit(boolean f) { return new AnimParams(clip, partMask, speed, weight, additive, mirror, f); }
    public AnimParams toggled(Part p) { return withMask(partMask ^ p.bit()); }
}
