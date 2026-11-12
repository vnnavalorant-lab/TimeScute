package dev.timescute.core;

/** Ponto da trilha de velocidade: quão rápido o tempo do replay corre neste instante da timeline. */
public record SpeedKey(long id, long timeMs, double speed) {
    public SpeedKey withTime(long t) { return new SpeedKey(id, t, speed); }
    public SpeedKey withSpeed(double v) { return new SpeedKey(id, timeMs, v); }
}
