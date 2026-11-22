package dev.timescute.core;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Um ator: a performance gravada, onde ela começa na timeline e as reações editadas por cima. Imutável. */
public record Actor(long id, String name, String skin, long startMs, Performance performance,
                    List<Reaction> reactions, boolean muted) {
    public Actor {
        if (name == null || name.isBlank()) name = "Ator";
        if (skin == null) skin = "";
        startMs = Math.max(0, startMs);
        if (performance == null) performance = Performance.EMPTY;
        reactions = reactions == null ? List.of()
                : reactions.stream().sorted(Comparator.comparingLong(Reaction::startMs).thenComparingLong(Reaction::id)).toList();
    }

    public static Actor create(long id, String name) {
        return new Actor(id, name, "", 0, Performance.EMPTY, List.of(), false);
    }

    public boolean hasPerformance() { return !performance.isEmpty(); }
    public long performanceEndMs() { return startMs + performance.durationMs(); }

    /** Último instante em que este ator faz algo (fim da performance ou de alguma reação). */
    public long endMs() {
        long e = hasPerformance() ? performanceEndMs() : 0;
        for (Reaction r : reactions) e = Math.max(e, r.endMs());
        return e;
    }

    public Optional<Reaction> reaction(long rid) {
        for (Reaction r : reactions) if (r.id() == rid) return Optional.of(r);
        return Optional.empty();
    }

    public Actor withName(String n) { return new Actor(id, n, skin, startMs, performance, reactions, muted); }
    public Actor withSkin(String s) { return new Actor(id, name, s, startMs, performance, reactions, muted); }
    public Actor withStart(long s) { return new Actor(id, name, skin, s, performance, reactions, muted); }
    public Actor withPerformance(Performance p) { return new Actor(id, name, skin, startMs, p, reactions, muted); }
    public Actor withMuted(boolean m) { return new Actor(id, name, skin, startMs, performance, reactions, m); }
    public Actor withReactions(List<Reaction> rs) { return new Actor(id, name, skin, startMs, performance, rs, muted); }

    public Actor withReaction(Reaction r) {
        java.util.ArrayList<Reaction> l = new java.util.ArrayList<>(reactions);
        l.removeIf(x -> x.id() == r.id());
        l.add(r);
        return withReactions(l);
    }

    public Actor withoutReaction(long rid) {
        java.util.ArrayList<Reaction> l = new java.util.ArrayList<>(reactions);
        l.removeIf(x -> x.id() == rid);
        return withReactions(l);
    }
}
