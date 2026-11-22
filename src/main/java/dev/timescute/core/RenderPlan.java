package dev.timescute.core;

/**
 * Plano de uma renderização: quantos quadros, em que instante da timeline cada um cai e quanto "tempo de jogo"
 * (ticks) passa entre dois quadros. Tudo calculado a partir do número do quadro, então não acumula erro.
 */
public final class RenderPlan {
    public static final double TICK_MS = 50.0;

    private final long startMs, endMs;
    private final int fps;
    private final long totalFrames;
    private final SpeedTrack speed;

    public RenderPlan(long startMs, long endMs, int fps, SpeedTrack speed) {
        if (fps < 1 || fps > 240) throw new IllegalArgumentException("FPS inválido: " + fps);
        if (endMs <= startMs) throw new IllegalArgumentException("O trajeto precisa ter duração.");
        this.startMs = startMs;
        this.endMs = endMs;
        this.fps = fps;
        this.speed = speed;
        this.totalFrames = (long) Math.ceil((endMs - startMs) * fps / 1000.0) + 1;
    }

    public long totalFrames() { return totalFrames; }
    public int fps() { return fps; }
    public long startMs() { return startMs; }
    public long endMs() { return endMs; }
    public double durationSeconds() { return (endMs - startMs) / 1000.0; }

    /** Instante exato (fracionário) do quadro {@code i}; o último nunca passa do fim. */
    public double exactTimeMs(long i) {
        return Math.min((double) endMs, startMs + i * 1000.0 / fps);
    }

    /** Instante da timeline do quadro {@code i}, em ms inteiros (para amostrar a câmera). */
    public long outputTimeMs(long i) {
        return Math.min(endMs, Math.round(exactTimeMs(i)));
    }

    /** Fator de velocidade do replay entre o quadro anterior e este (1.0 = tempo normal). */
    public double speedFactor(long i) {
        if (i <= 0) return speed.speedAt((double) startMs);
        double a = exactTimeMs(i - 1), b = exactTimeMs(i);
        if (b <= a) return speed.speedAt(b);
        return (speed.replayTimeAt(b) - speed.replayTimeAt(a)) / (b - a);
    }

    /** Quanto tempo de replay passa entre o quadro anterior e este (ms). */
    public double replayAdvanceMs(long i) {
        if (i <= 0) return 0;
        return speed.replayTimeAt(exactTimeMs(i)) - speed.replayTimeAt(exactTimeMs(i - 1));
    }

    /**
     * Ticks do jogo que passam neste quadro. Acompanha a velocidade do replay, então câmera lenta também
     * deixa partículas e animações lentas. O primeiro quadro não avança nada.
     */
    public double tickAdvance(long i) {
        return i <= 0 ? 0 : replayAdvanceMs(i) / TICK_MS;
    }

    public double progress(long framesDone) { return Math.max(0, Math.min(1, framesDone / (double) totalFrames)); }
}
