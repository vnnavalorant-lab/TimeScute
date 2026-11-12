package dev.timescute.core;

import java.util.*;

/** Keyframes selecionados (estado de UI, fora do histórico). */
public final class Selection {
    private final Set<Long> ids = new LinkedHashSet<>();

    public Set<Long> ids() { return Collections.unmodifiableSet(ids); }
    public boolean isEmpty() { return ids.isEmpty(); }
    public boolean contains(long id) { return ids.contains(id); }
    public void clear() { ids.clear(); }
    public void set(long id) { ids.clear(); ids.add(id); }
    public void setAll(Collection<Long> c) { ids.clear(); ids.addAll(c); }
    public void toggle(long id) { if (!ids.remove(id)) ids.add(id); }
    public void retainExisting(CameraPath p) { ids.removeIf(id -> p.byId(id).isEmpty()); }
}
