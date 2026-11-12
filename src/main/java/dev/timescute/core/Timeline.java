package dev.timescute.core;

import java.util.*;

/** Documento de edição: projeto (câmera + velocidade) e histórico de desfazer/refazer. */
public final class Timeline {
    /** Distância mínima entre dois pontos da mesma trilha. */
    public static final long MIN_GAP_MS = 50;
    public static final long MIN_DURATION_MS = 10_000;

    private final Project project = new Project();
    private final Deque<Command> undo = new ArrayDeque<>();
    private final Deque<Command> redo = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private long nextId = 1;

    public CameraPath path() { return project.path; }
    public SpeedTrack speed() { return project.speed; }

    public void onChange(Runnable r) { listeners.add(r); }
    private void fire() { listeners.forEach(Runnable::run); }

    /** Duração mostrada na timeline: sempre sobra espaço depois do último ponto. */
    public long durationMs() {
        return Math.max(MIN_DURATION_MS, Math.max(path().lastTimeMs(), speed().lastTimeMs()) + 5_000);
    }

    // ---------- câmera ----------

    /** Captura a câmera em {@code timeMs}. Se já há keyframe colado, ele é recapturado. */
    public Keyframe capture(long timeMs, CameraState state) {
        timeMs = Math.max(0, timeMs);
        Keyframe before = path().nearTime(timeMs, MIN_GAP_MS, Set.of()).orElse(null);
        Keyframe after = before != null
                ? before.withState(state)
                : new Keyframe(nextId++, timeMs, state, Easing.SMOOTH);
        run(new Put(before, after));
        return after;
    }

    public boolean canMove(Set<Long> ids, long deltaMs) {
        if (ids.isEmpty()) return false;
        for (long id : ids) {
            Optional<Keyframe> k = path().byId(id);
            if (k.isEmpty()) return false;
            long t = k.get().timeMs() + deltaMs;
            if (t < 0) return false;
            if (path().nearTime(t, MIN_GAP_MS, ids).isPresent()) return false;
        }
        return true;
    }

    /** Menor deslocamento válido (sem passar de 0 ms) para arrastar. */
    public long clampDelta(Set<Long> ids, long deltaMs) {
        long min = Long.MAX_VALUE;
        for (long id : ids) min = Math.min(min, path().byId(id).map(Keyframe::timeMs).orElse(Long.MAX_VALUE));
        return min == Long.MAX_VALUE ? 0 : Math.max(deltaMs, -min);
    }

    public boolean move(Set<Long> ids, long deltaMs) {
        if (deltaMs == 0 || !canMove(ids, deltaMs)) return false;
        run(new Move(new HashSet<>(ids), deltaMs));
        return true;
    }

    public void remove(Set<Long> ids) {
        List<Keyframe> gone = new ArrayList<>();
        for (long id : ids) path().byId(id).ifPresent(gone::add);
        if (!gone.isEmpty()) run(new Remove(gone));
    }

    public void setEasing(Set<Long> ids, Easing e) {
        Map<Long, Easing> before = new HashMap<>();
        for (long id : ids) path().byId(id).ifPresent(k -> before.put(id, k.easing()));
        if (!before.isEmpty()) run(new SetEasing(before, e));
    }

    // ---------- velocidade ----------

    /** Adiciona um ponto de velocidade; se há um colado, só muda o valor dele. */
    public SpeedKey addSpeed(long timeMs, double speed) {
        timeMs = Math.max(0, timeMs);
        speed = SpeedTrack.clampSpeed(speed);
        SpeedKey before = speed().nearTime(timeMs, MIN_GAP_MS, Set.of()).orElse(null);
        SpeedKey after = before != null ? before.withSpeed(speed) : new SpeedKey(nextId++, timeMs, speed);
        run(new SpeedPut(before, after));
        return after;
    }

    public boolean canPlaceSpeed(long id, long timeMs) {
        return timeMs >= 0 && speed().nearTime(timeMs, MIN_GAP_MS, Set.of(id)).isEmpty();
    }

    /** Altera tempo/valor de um ponto existente. Retorna false se inválido ou sem mudança. */
    public boolean editSpeed(long id, long timeMs, double speed) {
        Optional<SpeedKey> cur = speed().byId(id);
        if (cur.isEmpty() || !canPlaceSpeed(id, timeMs)) return false;
        SpeedKey after = new SpeedKey(id, timeMs, SpeedTrack.clampSpeed(speed));
        if (after.equals(cur.get())) return false;
        run(new SpeedPut(cur.get(), after));
        return true;
    }

    public void removeSpeed(long id) {
        speed().byId(id).ifPresent(k -> run(new SpeedRemove(k)));
    }

    // ---------- projeto inteiro ----------

    /** Troca todo o conteúdo (abrir arquivo / novo). É desfazível. */
    public void replaceAll(List<Keyframe> keys, List<SpeedKey> speeds, String label) {
        for (Keyframe k : keys) nextId = Math.max(nextId, k.id() + 1);
        for (SpeedKey k : speeds) nextId = Math.max(nextId, k.id() + 1);
        run(new Replace(List.copyOf(path().all()), List.copyOf(speed().all()),
                List.copyOf(keys), List.copyOf(speeds), label));
    }

    public void clear() { replaceAll(List.of(), List.of(), "Novo trajeto"); }

    /** Carrega conteúdo sem criar passo de desfazer e zera o histórico (ex.: restaurar a sessão anterior). */
    public void resetTo(List<Keyframe> keys, List<SpeedKey> speeds) {
        for (Keyframe k : keys) nextId = Math.max(nextId, k.id() + 1);
        for (SpeedKey k : speeds) nextId = Math.max(nextId, k.id() + 1);
        project.path.clear(); project.path.putAll(keys);
        project.speed.clear(); project.speed.putAll(speeds);
        undo.clear();
        redo.clear();
        fire();
    }

    // ---------- histórico ----------

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public Optional<String> undoLabel() { return Optional.ofNullable(undo.peek()).map(Command::label); }
    public Optional<String> redoLabel() { return Optional.ofNullable(redo.peek()).map(Command::label); }

    public void undo() {
        Command c = undo.poll();
        if (c == null) return;
        c.revert(project);
        redo.push(c);
        fire();
    }

    public void redo() {
        Command c = redo.poll();
        if (c == null) return;
        c.apply(project);
        undo.push(c);
        fire();
    }

    private void run(Command c) {
        c.apply(project);
        undo.push(c);
        redo.clear();
        fire();
    }

    // ---------- comandos ----------

    private record Put(Keyframe before, Keyframe after) implements Command {
        public void apply(Project p) { p.path.put(after); }
        public void revert(Project p) { if (before == null) p.path.removeAll(List.of(after.id())); else p.path.put(before); }
        public String label() { return before == null ? "Adicionar keyframe" : "Recapturar keyframe"; }
    }

    private record Remove(List<Keyframe> gone) implements Command {
        public void apply(Project p) { p.path.removeAll(gone.stream().map(Keyframe::id).toList()); }
        public void revert(Project p) { p.path.putAll(gone); }
        public String label() { return gone.size() == 1 ? "Remover keyframe" : "Remover " + gone.size() + " keyframes"; }
    }

    private record Move(Set<Long> ids, long delta) implements Command {
        private void shift(CameraPath p, long d) {
            List<Keyframe> moved = new ArrayList<>();
            for (long id : ids) p.byId(id).ifPresent(k -> moved.add(k.withTime(k.timeMs() + d)));
            p.putAll(moved);
        }
        public void apply(Project p) { shift(p.path, delta); }
        public void revert(Project p) { shift(p.path, -delta); }
        public String label() { return "Mover " + ids.size() + (ids.size() == 1 ? " keyframe" : " keyframes"); }
    }

    private record SetEasing(Map<Long, Easing> before, Easing after) implements Command {
        public void apply(Project p) {
            List<Keyframe> ks = new ArrayList<>();
            for (long id : before.keySet()) p.path.byId(id).ifPresent(k -> ks.add(k.withEasing(after)));
            p.path.putAll(ks);
        }
        public void revert(Project p) {
            List<Keyframe> ks = new ArrayList<>();
            before.forEach((id, e) -> p.path.byId(id).ifPresent(k -> ks.add(k.withEasing(e))));
            p.path.putAll(ks);
        }
        public String label() { return "Mudar interpolação para " + after.label; }
    }

    private record SpeedPut(SpeedKey before, SpeedKey after) implements Command {
        public void apply(Project p) { p.speed.put(after); }
        public void revert(Project p) { if (before == null) p.speed.remove(after.id()); else p.speed.put(before); }
        public String label() { return before == null ? "Adicionar ponto de velocidade" : "Editar ponto de velocidade"; }
    }

    private record SpeedRemove(SpeedKey gone) implements Command {
        public void apply(Project p) { p.speed.remove(gone.id()); }
        public void revert(Project p) { p.speed.put(gone); }
        public String label() { return "Remover ponto de velocidade"; }
    }

    private record Replace(List<Keyframe> oldKeys, List<SpeedKey> oldSpeed,
                           List<Keyframe> newKeys, List<SpeedKey> newSpeed, String label) implements Command {
        private static void set(Project p, List<Keyframe> ks, List<SpeedKey> ss) {
            p.path.clear(); p.path.putAll(ks);
            p.speed.clear(); p.speed.putAll(ss);
        }
        public void apply(Project p) { set(p, newKeys, newSpeed); }
        public void revert(Project p) { set(p, oldKeys, oldSpeed); }
    }
}
