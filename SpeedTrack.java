package dev.timescute.core;

import java.util.*;

/**
 * Velocidade do replay ao longo da timeline (1.0 = normal, 0.5 = câmera lenta).
 * Interpolação linear entre pontos; constante antes do primeiro e depois do último.
 */
public final class SpeedTrack {
    public static final double MIN = 0.1, MAX = 4.0;
    private final List<SpeedKey> keys = new ArrayList<>();

    public List<SpeedKey> all() { return Collections.unmodifiableList(keys); }
    public boolean isEmpty() { return keys.isEmpty(); }
    public long lastTimeMs() { return keys.isEmpty() ? 0 : keys.get(keys.size() - 1).timeMs(); }
    public void clear() { keys.clear(); }

    public static double clampSpeed(double v) {
        if (Double.isNaN(v)) return 1.0;
        return Math.max(MIN, Math.min(MAX, v));
    }

    public Optional<SpeedKey> byId(long id) {
        for (SpeedKey k : keys) if (k.id() == id) return Optional.of(k);
        return Optional.empty();
    }

    public void put(SpeedKey k) {
        keys.removeIf(o -> o.id() == k.id());
        keys.add(k);
        keys.sort(Comparator.comparingLong(SpeedKey::timeMs));
    }

    public void putAll(Collection<SpeedKey> ks) { for (SpeedKey k : ks) put(k); }
    public void remove(long id) { keys.removeIf(k -> k.id() == id); }

    public Optional<SpeedKey> nearTime(long timeMs, long gapMs, Set<Long> ignore) {
        for (SpeedKey k : keys)
            if (!ignore.contains(k.id()) && Math.abs(k.timeMs() - timeMs) < gapMs) return Optional.of(k);
        return Optional.empty();
    }

    public double speedAt(long ms) {
        if (keys.isEmpty()) return 1.0;
        SpeedKey first = keys.get(0), last = keys.get(keys.size() - 1);
        if (ms <= first.timeMs()) return first.speed();
        if (ms >= last.timeMs()) return last.speed();
        int i = 0;
        while (keys.get(i + 1).timeMs() <= ms) i++;
        SpeedKey a = keys.get(i), b = keys.get(i + 1);
        double u = (double) (ms - a.timeMs()) / (b.timeMs() - a.timeMs());
        return a.speed() + (b.speed() - a.speed()) * u;
    }

    /** Tempo do replay (ms) correspondente ao instante {@code outputMs} da timeline: integral exata da velocidade. */
    public double replayTimeAt(long outputMs) {
        if (outputMs <= 0) return 0;
        if (keys.isEmpty()) return outputMs;
        double total = 0;
        long prevT = 0;
        double prevV = speedAt(0);
        for (SpeedKey k : keys) {
            if (k.timeMs() <= 0) continue;
            if (k.timeMs() >= outputMs) break;
            total += (prevV + k.speed()) / 2.0 * (k.timeMs() - prevT);
            prevT = k.timeMs();
            prevV = k.speed();
        }
        total += (prevV + speedAt(outputMs)) / 2.0 * (outputMs - prevT);
        return total;
    }

    /** Inverso de {@link #replayTimeAt}: em que instante da timeline o replay chega a {@code replayMs}. */
    public long outputTimeFor(double replayMs) {
        if (replayMs <= 0) return 0;
        long lo = 0, hi = (long) (replayMs / MIN) + 1;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (replayTimeAt(mid) < replayMs) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}
