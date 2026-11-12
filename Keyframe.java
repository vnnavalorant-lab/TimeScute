package dev.timescute.core;

/** Uma "tomada" de câmera num ponto da linha do tempo. Imutável. */
public record Keyframe(long id, long timeMs, CameraState state, Easing easing) {
    public Keyframe withTime(long t) { return new Keyframe(id, t, state, easing); }
    public Keyframe withState(CameraState s) { return new Keyframe(id, timeMs, s, easing); }
    public Keyframe withEasing(Easing e) { return new Keyframe(id, timeMs, state, e); }
}
