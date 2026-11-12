package dev.timescute.core;

import java.util.*;

public final class CoreTests {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FALHOU: " + name);
        passed++;
        System.out.println("  ok  " + name);
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    static CameraState at(double x, double y, double z, double yaw) {
        return new CameraState(x, y, z, yaw, 0, 0, 70);
    }

    public static void main(String[] args) {
        // ---- amostragem ----
        Timeline t = new Timeline();
        t.capture(0, at(0, 0, 0, 0));
        t.capture(1000, at(10, 0, 0, 90));
        t.capture(2000, at(10, 0, 10, 180));
        CameraPath p = t.path();

        check(near(p.sample(0).get().x(), 0), "passa pelo keyframe inicial");
        check(near(p.sample(1000).get().x(), 10) && near(p.sample(1000).get().z(), 0), "passa exatamente pelo keyframe do meio");
        check(near(p.sample(2000).get().z(), 10), "passa pelo keyframe final");
        check(near(p.sample(-500).get().x(), 0) && near(p.sample(9000).get().z(), 10), "fora do intervalo prende nas pontas");

        Timeline w = new Timeline();
        w.capture(0, at(0, 0, 0, 350));
        w.capture(1000, at(0, 0, 0, 10));
        double yaw = w.path().sample(500).get().yaw();
        check(near(((yaw % 360) + 360) % 360, 0), "yaw 350->10 gira pelo caminho curto (meio = 0/360)");

        Timeline h = new Timeline();
        Keyframe k0 = h.capture(0, at(0, 0, 0, 0));
        h.capture(1000, at(100, 0, 0, 0));
        h.setEasing(Set.of(k0.id()), Easing.HOLD);
        check(near(h.path().sample(999).get().x(), 0) && near(h.path().sample(1000).get().x(), 100), "HOLD fica parado e salta no próximo");

        Timeline l = new Timeline();
        Keyframe a = l.capture(0, at(0, 0, 0, 0));
        l.capture(1000, at(100, 0, 0, 0));
        l.setEasing(Set.of(a.id()), Easing.LINEAR);
        check(near(l.path().sample(250).get().x(), 25), "LINEAR é proporcional ao tempo");
        l.setEasing(Set.of(a.id()), Easing.EASE);
        check(l.path().sample(250).get().x() < 25, "EASE começa mais devagar que linear");

        // ---- edição ----
        Timeline e = new Timeline();
        Keyframe x = e.capture(1000, at(1, 0, 0, 0));
        Keyframe y = e.capture(3000, at(2, 0, 0, 0));
        Keyframe re = e.capture(1010, at(9, 0, 0, 0));
        check(re.id() == x.id() && e.path().size() == 2 && near(e.path().byId(x.id()).get().state().x(), 9),
                "capturar colado recaptura em vez de duplicar");

        check(!e.canMove(Set.of(x.id()), -2000), "não deixa mover para tempo negativo");
        check(!e.canMove(Set.of(x.id()), 2000), "não deixa colidir com outro keyframe");
        check(e.canMove(Set.of(x.id(), y.id()), 500), "grupo pode mover junto");
        check(e.clampDelta(Set.of(x.id(), y.id()), -5000) == -1000, "clampDelta para no zero");

        check(e.move(Set.of(x.id(), y.id()), 500), "move grupo");
        check(e.path().byId(x.id()).get().timeMs() == 1500 && e.path().byId(y.id()).get().timeMs() == 3500, "tempos após mover");
        e.undo();
        check(e.path().byId(x.id()).get().timeMs() == 1000, "undo do move");
        e.redo();
        check(e.path().byId(x.id()).get().timeMs() == 1500, "redo do move");

        e.remove(Set.of(x.id()));
        check(e.path().size() == 1, "remove");
        e.undo();
        check(e.path().size() == 2, "undo do remove restaura");

        int before = e.path().size();
        e.capture(9000, at(0, 0, 0, 0));
        e.undo();
        check(e.path().size() == before, "undo do add");
        check(e.undoLabel().isPresent() && e.redoLabel().isPresent(), "rótulos de undo/redo existem");
        e.capture(9500, at(0, 0, 0, 0));
        check(!e.canRedo(), "nova ação limpa o redo");

        Timeline d = new Timeline();
        check(d.durationMs() == Timeline.MIN_DURATION_MS, "duração mínima");
        d.capture(40_000, at(0, 0, 0, 0));
        check(d.durationMs() == 45_000, "duração cresce com o último keyframe");

        int[] fired = {0};
        Timeline n = new Timeline();
        n.onChange(() -> fired[0]++);
        n.capture(0, at(0, 0, 0, 0));
        n.undo();
        check(fired[0] == 2, "listeners disparam em ação e undo");

        // ---- view ----
        TimelineView v = new TimelineView();
        v.setWidth(1000);
        long msUnder = v.msAt(400);
        v.zoomAt(2.0, 400);
        check(Math.abs(v.msAt(400) - msUnder) <= 1, "zoom mantém o ponto sob o cursor parado");
        double before2 = v.msPerPx();
        v.zoomAt(1000, 100);
        check(v.msPerPx() >= 0.5 && v.msPerPx() < before2, "zoom respeita o limite mínimo");

        TimelineView g = new TimelineView();
        g.setWidth(1000);
        g.fit(10_000);
        check(g.gridStepMs() / g.msPerPx() >= 60, "grade nunca fica mais densa que 60px");
        check(g.snap(1234, true) % Math.max(1, g.gridStepMs() / 2) == 0, "snap alinha na grade");
        check(g.snap(1234, false) == 1234, "sem snap mantém o valor");
        check(g.snap(-50, true) == 0, "snap nunca fica negativo");

        TimelineView hv = new TimelineView();
        hv.setWidth(1000);
        hv.fit(10_000);
        Timeline ht = new Timeline();
        Keyframe q1 = ht.capture(2000, at(0, 0, 0, 0));
        ht.capture(6000, at(0, 0, 0, 0));
        check(hv.hit(ht.path(), hv.xOf(2000) + 3, 8).get().id() == q1.id(), "hit-test acha keyframe perto do clique");
        check(hv.hit(ht.path(), hv.xOf(4000), 8).isEmpty(), "hit-test ignora área vazia");
        check(hv.inRange(ht.path(), hv.xOf(1000), hv.xOf(7000)).size() == 2, "seleção por caixa pega os dois");
        check(hv.inRange(ht.path(), hv.xOf(1000), hv.xOf(3000)).size() == 1, "seleção por caixa parcial");

        check(TimelineView.format(83_456).equals("01:23.456"), "formatação de tempo");

        System.out.println("\n" + passed + " verificações passaram.");
    }
}
