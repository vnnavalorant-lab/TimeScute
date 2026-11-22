package dev.timescute.core;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Um ator: de onde vem o movimento (uma performance gravada OU um roteiro feito na timeline a partir de uma posição inicial),
 * onde isso começa na timeline e as reações editadas por cima. Imutável.
 *
 * @param model nome de um modelo do Blockbench para desenhar no lugar do jogador; vazio = o modelo do jogador
 * @param origin onde o ator roteirizado começa (nulo = ator que só existe se tiver gravação)
 */
public record Actor(long id, String name, String skin, long startMs, Performance performance,
                    List<Reaction> reactions, boolean muted, String model, Origin origin) {

    /** Posição inicial de um ator roteirizado: pés em (x, y, z), virado para {@code yaw} (graus, convenção do Minecraft). */
    public record Origin(double x, double y, double z, float yaw) { }

    public Actor {
        if (name == null || name.isBlank()) name = "Ator";
        if (skin == null) skin = "";
        if (model == null) model = "";
        startMs = Math.max(0, startMs);
        if (performance == null) performance = Performance.EMPTY;
        reactions = reactions == null ? List.of()
                : reactions.stream().sorted(Comparator.comparingLong(Reaction::startMs).thenComparingLong(Reaction::id)).toList();
    }

    /** Ator só com gravação (modelo do jogador, sem roteiro). */
    public Actor(long id, String name, String skin, long startMs, Performance performance, List<Reaction> reactions, boolean muted) {
        this(id, name, skin, startMs, performance, reactions, muted, "", null);
    }

    public static Actor create(long id, String name) {
        return new Actor(id, name, "", 0, Performance.EMPTY, List.of(), false);
    }

    /** Ator que você dirige pela timeline, sem gravar nada: começa em {@code origin}. */
    public static Actor createScripted(long id, String name, Origin origin) {
        return new Actor(id, name, "", 0, Performance.EMPTY, List.of(), false, "", origin);
    }

    public boolean hasPerformance() { return !performance.isEmpty(); }
    public boolean isScripted() { return origin != null; }
    public boolean hasModel() { return !model.isEmpty(); }
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

    public Actor withName(String n) { return new Actor(id, n, skin, startMs, performance, reactions, muted, model, origin); }
    public Actor withSkin(String s) { return new Actor(id, name, s, startMs, performance, reactions, muted, model, origin); }
    public Actor withStart(long s) { return new Actor(id, name, skin, s, performance, reactions, muted, model, origin); }
    public Actor withPerformance(Performance p) { return new Actor(id, name, skin, startMs, p, reactions, muted, model, origin); }
    public Actor withMuted(boolean m) { return new Actor(id, name, skin, startMs, performance, reactions, m, model, origin); }
    public Actor withReactions(List<Reaction> rs) { return new Actor(id, name, skin, startMs, performance, rs, muted, model, origin); }
    public Actor withModel(String m) { return new Actor(id, name, skin, startMs, performance, reactions, muted, m, origin); }
    public Actor withOrigin(Origin o) { return new Actor(id, name, skin, startMs, performance, reactions, muted, model, o); }

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
