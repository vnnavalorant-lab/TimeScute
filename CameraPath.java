package dev.timescute.core;

import java.util.*;

/** Conjunto ordenado de keyframes + amostragem da câmera em qualquer instante. */
public final class CameraPath {
    private final List<Keyframe> keys = new ArrayList<>();

    public List<Keyframe> all() { return Collections.unmodifiableList(keys); }
    public boolean isEmpty() { return keys.isEmpty(); }
    public void clear() { keys.clear(); }
    public int size() { return keys.size(); }
    public long lastTimeMs() { return keys.isEmpty() ? 0 : keys.get(keys.size() - 1).timeMs(); }

    public Optional<Keyframe> byId(long id) {
        for (Keyframe k : keys) if (k.id() == id) return Optional.of(k);
        return Optional.empty();
    }

    /** Insere ou substitui (por id), mantendo a ordem por tempo. */
    public void put(Keyframe k) { putAll(List.of(k)); }

    public void putAll(Collection<Keyframe> ks) {
        Set<Long> ids = new HashSet<>();
        for (Keyframe k : ks) ids.add(k.id());
        keys.removeIf(k -> ids.contains(k.id()));
        keys.addAll(ks);
        keys.sort(Comparator.comparingLong(Keyframe::timeMs));
    }

    public void removeAll(Collection<Long> ids) {
        Set<Long> s = new HashSet<>(ids);
        keys.removeIf(k -> s.contains(k.id()));
    }

    /** Keyframe a menos de {@code gapMs} de {@code timeMs}, ignorando os ids dados. */
    public Optional<Keyframe> nearTime(long timeMs, long gapMs, Set<Long> ignore) {
        for (Keyframe k : keys)
            if (!ignore.contains(k.id()) && Math.abs(k.timeMs() - timeMs) < gapMs) return Optional.of(k);
        return Optional.empty();
    }

    /** Câmera no instante {@code ms}; vazio se não há keyframes. */
    public Optional<CameraState> sample(long ms) {
        int n = keys.size();
        if (n == 0) return Optional.empty();
        if (ms <= keys.get(0).timeMs()) return Optional.of(keys.get(0).state());
        if (ms >= keys.get(n - 1).timeMs()) return Optional.of(keys.get(n - 1).state());

        int i = 0;
        while (keys.get(i + 1).timeMs() <= ms) i++;
        Keyframe a = keys.get(i), b = keys.get(i + 1);
        double u = (double) (ms - a.timeMs()) / (b.timeMs() - a.timeMs());
        double e = a.easing().remap(u);

        CameraState lin = CameraState.lerp(a.state(), b.state(), e);
        if (a.easing() != Easing.SMOOTH) return Optional.of(lin);

        CameraState p0 = keys.get(Math.max(i - 1, 0)).state();
        CameraState p3 = keys.get(Math.min(i + 2, n - 1)).state();
        CameraState s1 = a.state(), s2 = b.state();
        return Optional.of(lin.withPosition(
                catmull(p0.x(), s1.x(), s2.x(), p3.x(), u),
                catmull(p0.y(), s1.y(), s2.y(), p3.y(), u),
                catmull(p0.z(), s1.z(), s2.z(), p3.z(), u)));
    }

    /** Amostra o trajeto inteiro (para desenhar a linha no mundo). */
    public List<CameraState> sampleRange(long fromMs, long toMs, long stepMs) {
        List<CameraState> out = new ArrayList<>();
        for (long t = fromMs; t <= toMs; t += stepMs) sample(t).ifPresent(out::add);
        return out;
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
