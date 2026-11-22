package dev.timescute.core;

import java.util.*;

/** Os atores da cena, em ordem (a ordem define as faixas na timeline). */
public final class ActorSet {
    private final List<Actor> list = new ArrayList<>();

    public List<Actor> all() { return Collections.unmodifiableList(list); }
    public boolean isEmpty() { return list.isEmpty(); }
    public int size() { return list.size(); }
    public void clear() { list.clear(); }

    public int indexOf(long id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id() == id) return i;
        return -1;
    }

    public Optional<Actor> byId(long id) {
        int i = indexOf(id);
        return i < 0 ? Optional.empty() : Optional.of(list.get(i));
    }

    /** Substitui (mantendo a posição) ou acrescenta no fim. */
    public void put(Actor a) {
        int i = indexOf(a.id());
        if (i >= 0) list.set(i, a); else list.add(a);
    }

    public void insert(int index, Actor a) {
        list.removeIf(x -> x.id() == a.id());
        list.add(Math.max(0, Math.min(index, list.size())), a);
    }

    public void remove(long id) { list.removeIf(a -> a.id() == id); }
    public void putAll(Collection<Actor> as) { for (Actor a : as) put(a); }

    /** Fim de tudo que os atores fazem. */
    public long endMs() {
        long e = 0;
        for (Actor a : list) e = Math.max(e, a.endMs());
        return e;
    }
}
