package dev.timescute.core;

/** Relógio do replay: tempo, velocidade e play/pause. Sem dependência do Minecraft. */
public final class ReplayClock {
    public static final double[] SPEEDS = {0.1, 0.25, 0.5, 1, 2, 4, 8};
    public static final double MIN_SPEED = 0.05, MAX_SPEED = 16;

    private final long durationMs;
    private double timeMs;
    private double speed = 1;
    private boolean playing;

    public ReplayClock(long durationMs) { this.durationMs = Math.max(0, durationMs); }

    public long durationMs() { return durationMs; }
    public long timeMs() { return (long) timeMs; }
    public double exactTimeMs() { return timeMs; }
    public double speed() { return speed; }
    public boolean isPlaying() { return playing; }
    public boolean atEnd() { return timeMs >= durationMs; }

    public void play() {
        if (atEnd()) timeMs = 0; // dar play no fim recomeça
        playing = true;
    }

    public void pause() { playing = false; }
    public void toggle() { if (playing) pause(); else play(); }

    public void setSpeed(double s) {
        if (Double.isNaN(s)) return;
        speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, s));
    }

    /** Vai para a próxima (dir > 0) ou anterior (dir < 0) velocidade predefinida. */
    public void stepSpeed(int dir) {
        if (dir > 0) {
            for (double p : SPEEDS) if (p > speed + 1e-9) { speed = p; return; }
            speed = SPEEDS[SPEEDS.length - 1];
        } else if (dir < 0) {
            for (int i = SPEEDS.length - 1; i >= 0; i--) if (SPEEDS[i] < speed - 1e-9) { speed = SPEEDS[i]; return; }
            speed = SPEEDS[0];
        }
    }

    public void seek(long ms) { timeMs = Math.max(0, Math.min(ms, durationMs)); }
    public void jump(long deltaMs) { seek((long) timeMs + deltaMs); }

    /** Avança com o tempo real; no fim, pausa. */
    public void tick(double realDeltaMs) {
        if (!playing || realDeltaMs <= 0) return;
        timeMs += realDeltaMs * speed;
        if (timeMs >= durationMs) { timeMs = durationMs; playing = false; }
    }

    /** Avança uma quantidade exata de tempo do replay, mesmo pausado (usado pela reprodução do trajeto de câmera). */
    public void advanceExact(double replayDeltaMs) {
        timeMs = Math.max(0, Math.min(timeMs + replayDeltaMs, durationMs));
    }
}
