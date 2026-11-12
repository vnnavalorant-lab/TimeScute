package dev.timescute.core;

import java.util.*;

/** Matemática de zoom/pan/grade/hit-test da timeline, sem depender do Minecraft. */
public final class TimelineView {
    private static final double MIN_MS_PER_PX = 0.5, MAX_MS_PER_PX = 200;
    private static final long[] NICE_STEPS = {10, 20, 50, 100, 250, 500, 1000, 2000, 5000,
            10_000, 30_000, 60_000, 120_000, 300_000};

    private double msPerPx = 10;
    private double originMs = 0; // tempo na borda esquerda
    private int widthPx = 800;

    public void setWidth(int w) { widthPx = Math.max(1, w); }
    public int width() { return widthPx; }
    public double msPerPx() { return msPerPx; }
    public double originMs() { return originMs; }

    public double xOf(long ms) { return (ms - originMs) / msPerPx; }
    public long msAt(double x) { return Math.round(originMs + x * msPerPx); }

    /** factor > 1 aproxima. O ponto sob o cursor fica parado. */
    public void zoomAt(double factor, double anchorX) {
        double anchorMs = originMs + anchorX * msPerPx;
        msPerPx = Math.max(MIN_MS_PER_PX, Math.min(MAX_MS_PER_PX, msPerPx / factor));
        originMs = anchorMs - anchorX * msPerPx;
        clampOrigin();
    }

    public void panPx(double dx) { originMs -= dx * msPerPx; clampOrigin(); }

    public void fit(long durationMs) {
        msPerPx = Math.max(MIN_MS_PER_PX, Math.min(MAX_MS_PER_PX, durationMs * 1.05 / widthPx));
        originMs = -durationMs * 0.025;
    }

    /** Mantém o playhead visível, rolando se sair da tela. */
    public void follow(long ms) {
        double x = xOf(ms);
        if (x < 20) originMs = ms - 20 * msPerPx;
        else if (x > widthPx - 20) originMs = ms - (widthPx - 20) * msPerPx;
        clampOrigin();
    }

    private void clampOrigin() { originMs = Math.max(-1000, originMs); }

    /** Passo da grade: o menor "redondo" que deixa as linhas ≥ 60 px afastadas. */
    public long gridStepMs() {
        for (long s : NICE_STEPS) if (s / msPerPx >= 60) return s;
        return NICE_STEPS[NICE_STEPS.length - 1];
    }

    public long snap(long ms, boolean enabled) {
        if (!enabled) return Math.max(0, ms);
        long step = Math.max(1, gridStepMs() / 2);
        return Math.max(0, Math.round((double) ms / step) * step);
    }

    public Optional<Keyframe> hit(CameraPath p, double x, double radiusPx) {
        Keyframe best = null;
        double bestD = radiusPx;
        for (Keyframe k : p.all()) {
            double d = Math.abs(xOf(k.timeMs()) - x);
            if (d <= bestD) { best = k; bestD = d; }
        }
        return Optional.ofNullable(best);
    }

    public List<Keyframe> inRange(CameraPath p, double x0, double x1) {
        double lo = Math.min(x0, x1), hi = Math.max(x0, x1);
        List<Keyframe> out = new ArrayList<>();
        for (Keyframe k : p.all()) {
            double x = xOf(k.timeMs());
            if (x >= lo && x <= hi) out.add(k);
        }
        return out;
    }

    public static String format(long ms) {
        long m = ms / 60_000, s = (ms / 1000) % 60, r = ms % 1000;
        return String.format("%02d:%02d.%03d", m, s, r);
    }
}
