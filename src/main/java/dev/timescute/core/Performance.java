package dev.timescute.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** A gravação do movimento do ator: quadros ordenados no tempo, com acesso aleatório e interpolação. */
public final class Performance {
    /** Intervalo maior que isto entre dois quadros (jogo pausado, lag): não interpola. */
    public static final long GAP_MS = 1500;
    /** Salto maior que isto (blocos) entre quadros vizinhos é teletransporte: não interpola. */
    public static final double TELEPORT_BLOCKS = 16.0;
    public static final Performance EMPTY = new Performance(new ActorFrame[0]);

    private final ActorFrame[] frames;

    private Performance(ActorFrame[] frames) { this.frames = frames; }

    /** Ordena por tempo (estável) e copia. */
    public static Performance of(List<ActorFrame> list) {
        if (list.isEmpty()) return EMPTY;
        List<ActorFrame> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingLong(ActorFrame::timeMs));
        return new Performance(sorted.toArray(new ActorFrame[0]));
    }

    public int size() { return frames.length; }
    public boolean isEmpty() { return frames.length == 0; }
    public long durationMs() { return frames.length == 0 ? 0 : frames[frames.length - 1].timeMs(); }
    public List<ActorFrame> frames() { return List.of(frames); }

    /** Índice do último quadro com tempo <= rel, ou -1. */
    int indexAt(long rel) {
        int lo = 0, hi = frames.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (frames[mid].timeMs() <= rel) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    /** O ator no instante {@code rel} (ms desde o início da gravação). Fora do intervalo, devolve a ponta. */
    public ActorFrame sampleAt(long rel) {
        if (frames.length == 0) throw new IllegalStateException("Performance vazia.");
        int i = indexAt(rel);
        if (i < 0) return frames[0];
        if (i >= frames.length - 1) return frames[frames.length - 1];
        ActorFrame a = frames[i], b = frames[i + 1];
        long dt = b.timeMs() - a.timeMs();
        if (dt <= 0 || dt > GAP_MS) return a;
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > TELEPORT_BLOCKS) return a;
        return ActorFrame.lerp(a, b, (double) (rel - a.timeMs()) / dt, rel);
    }

    @Override
    public boolean equals(Object o) { return o instanceof Performance p && Arrays.equals(frames, p.frames); }

    @Override
    public int hashCode() { return Arrays.hashCode(frames); }
}
