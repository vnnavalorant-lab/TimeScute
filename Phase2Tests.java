package dev.timescute.core;

import java.nio.file.*;
import java.util.*;

public final class Phase2Tests {
    static int passed = 0;

    static void check(boolean c, String name) {
        if (!c) throw new AssertionError("FALHOU: " + name);
        passed++;
        System.out.println("  ok  " + name);
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }
    static boolean near(double a, double b, double eps) { return Math.abs(a - b) < eps; }

    static CameraState cs(double x, double y, double z, double yaw, double pitch) {
        return new CameraState(x, y, z, yaw, pitch, 0, 70);
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    static boolean throwsIAE(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    public static void main(String[] args) throws Exception {
        // ---- trilha de velocidade ----
        Timeline t = new Timeline();
        check(near(t.speed().speedAt(500), 1.0) && near(t.speed().replayTimeAt(1000), 1000), "sem pontos: velocidade 1x, tempo idêntico");

        t.addSpeed(0, 0.5);
        check(near(t.speed().replayTimeAt(1000), 500), "0.5x constante: 1000ms de timeline = 500ms de replay");
        check(near(t.speed().speedAt(99999), 0.5), "mantém o último valor depois do fim");

        Timeline r = new Timeline();
        r.addSpeed(0, 1.0);
        r.addSpeed(1000, 3.0);
        check(near(r.speed().speedAt(500), 2.0), "rampa linear: meio = 2x");
        check(near(r.speed().replayTimeAt(1000), 2000), "integral da rampa 1->3 em 1s = 2000");
        check(near(r.speed().replayTimeAt(500), 750), "integral parcial da rampa = 750");
        check(near(r.speed().replayTimeAt(2000), 2000 + 3000), "depois da rampa continua a 3x");

        Timeline lead = new Timeline();
        lead.addSpeed(1000, 2.0);
        check(near(lead.speed().replayTimeAt(500), 1000), "antes do primeiro ponto vale o valor dele");

        boolean inverseOk = true;
        for (long ms : new long[]{0, 1, 250, 999, 1000, 1500, 4000})
            inverseOk &= Math.abs(r.speed().outputTimeFor(r.speed().replayTimeAt(ms)) - ms) <= 1;
        check(inverseOk, "outputTimeFor é o inverso de replayTimeAt");

        check(near(SpeedTrack.clampSpeed(100), 4.0) && near(SpeedTrack.clampSpeed(0), 0.1) && near(SpeedTrack.clampSpeed(Double.NaN), 1.0),
                "valores de velocidade são limitados a 0.1–4x");

        // ---- edição de velocidade com undo ----
        Timeline e = new Timeline();
        SpeedKey a = e.addSpeed(1000, 1.0);
        SpeedKey b = e.addSpeed(3000, 2.0);
        SpeedKey again = e.addSpeed(1020, 0.5);
        check(again.id() == a.id() && e.speed().all().size() == 2, "adicionar colado só altera o valor");
        check(!e.canPlaceSpeed(b.id(), 1010) && e.canPlaceSpeed(b.id(), 4000) && !e.canPlaceSpeed(b.id(), -1), "regras de posição dos pontos");
        check(e.editSpeed(b.id(), 3500, 3.0) && e.speed().byId(b.id()).get().timeMs() == 3500, "editSpeed muda tempo e valor");
        check(!e.editSpeed(b.id(), 3500, 3.0), "editSpeed sem mudança não gera histórico");
        e.undo();
        check(e.speed().byId(b.id()).get().timeMs() == 3000 && near(e.speed().byId(b.id()).get().speed(), 2.0), "undo do editSpeed");
        e.removeSpeed(b.id());
        check(e.speed().byId(b.id()).isEmpty(), "removeSpeed");
        e.undo();
        check(e.speed().byId(b.id()).isPresent(), "undo do removeSpeed");

        // ---- replaceAll / clear desfazíveis ----
        Timeline p = new Timeline();
        p.capture(1000, cs(1, 2, 3, 0, 0));
        p.addSpeed(0, 2.0);
        p.clear();
        check(p.path().isEmpty() && p.speed().isEmpty(), "clear esvazia tudo");
        p.undo();
        check(p.path().size() == 1 && p.speed().all().size() == 1, "undo do clear restaura câmera e velocidade");
        Keyframe fresh = p.capture(5000, cs(0, 0, 0, 0, 0));
        check(fresh.id() > 2, "ids novos não colidem depois de restaurar");

        Timeline from = new Timeline();
        from.capture(0, cs(1, 1, 1, 0, 0));
        from.capture(1000, cs(2, 2, 2, 0, 0));
        from.addSpeed(0, 1.0);
        from.addSpeed(500, 2.0);
        Timeline rs = new Timeline();
        rs.capture(0, cs(0, 0, 0, 0, 0));
        int[] fired = {0};
        rs.onChange(() -> fired[0]++);
        rs.resetTo(from.path().all(), from.speed().all());
        check(rs.path().size() == 2 && rs.speed().all().size() == 2 && !rs.canUndo() && !rs.canRedo() && fired[0] == 1,
                "resetTo carrega sem histórico e avisa os ouvintes");
        check(rs.capture(9000, cs(0, 0, 0, 0, 0)).id() > 4, "resetTo mantém ids novos sem colisão");

        // ---- JSON ----
        Timeline src = new Timeline();
        Keyframe k1 = src.capture(0, new CameraState(1.5, 64.25, -3.125, 350.5, -12.25, 3, 85));
        src.capture(2500, cs(10, 70, 10, 90, 0));
        src.setEasing(Set.of(k1.id()), Easing.HOLD);
        src.addSpeed(500, 0.25);
        src.addSpeed(4000, 2.0);
        PathJson.Data d = PathJson.read(PathJson.write(src));
        check(d.keys().size() == 2 && d.speeds().size() == 2, "round-trip mantém quantidades");
        check(d.keys().equals(src.path().all()), "round-trip dos keyframes é idêntico (inclui double exato)");
        check(d.speeds().equals(src.speed().all()), "round-trip da velocidade é idêntico");
        check(d.keys().get(0).easing() == Easing.HOLD, "round-trip mantém a interpolação");

        PathJson.Data legacy = PathJson.read("{\"format\":\"kinema-path\",\"version\":1,\"keyframes\":[{\"id\":1,\"t\":0,\"x\":0,\"y\":0,\"z\":0,\"yaw\":0,\"pitch\":0}]}");
        check(legacy.keys().size() == 1 && PathJson.write(src).contains("\"timescute-path\""),
                "lê o formato antigo (kinema-path) e grava o novo (timescute-path)");

        Timeline empty = new Timeline();
        PathJson.Data de = PathJson.read(PathJson.write(empty));
        check(de.keys().isEmpty() && de.speeds().isEmpty(), "trajeto vazio salva e abre");

        check(throwsIAE(() -> PathJson.read("lixo")), "rejeita texto que não é JSON");
        check(throwsIAE(() -> PathJson.read("{\"format\":\"outro\"}")), "rejeita formato errado");
        check(throwsIAE(() -> PathJson.read("{\"format\":\"timescute-path\",\"version\":99,\"keyframes\":[]}")), "rejeita versão futura");
        check(throwsIAE(() -> PathJson.read("{\"format\":\"timescute-path\",\"version\":1,\"keyframes\":[{\"id\":1,\"t\":-5,\"x\":0,\"y\":0,\"z\":0,\"yaw\":0,\"pitch\":0}]}")), "rejeita tempo negativo");
        check(throwsIAE(() -> PathJson.read("{\"format\":\"timescute-path\",\"version\":1,\"keyframes\":[{\"id\":1,\"t\":0,\"x\":0,\"y\":0,\"z\":0,\"yaw\":0,\"pitch\":0},{\"id\":1,\"t\":900,\"x\":0,\"y\":0,\"z\":0,\"yaw\":0,\"pitch\":0}]}")), "rejeita ids repetidos");
        check(throwsIAE(() -> PathJson.read("{\"format\":\"timescute-path\",\"version\":1,\"keyframes\":[{\"id\":1,\"t\":0,\"x\":0}]}")), "rejeita campos faltando");
        PathJson.Data tol = PathJson.read("{\"format\":\"timescute-path\",\"version\":1,\"keyframes\":[{\"id\":1,\"t\":0,\"x\":0,\"y\":0,\"z\":0,\"yaw\":0,\"pitch\":0,\"ease\":\"INVENTADO\"}]}");
        check(tol.keys().get(0).easing() == Easing.SMOOTH && near(tol.keys().get(0).state().fov(), 70), "interpolação desconhecida vira SMOOTH; fov padrão 70");
        check(Json.parse("{\"a\":\"x\\u00e9\\n\",\"b\":[1,-2.5e2,true,null]}") instanceof Map<?, ?> m && "xé\n".equals(m.get("a")), "parser entende escapes e números");

        // ---- arquivos ----
        Path dir = Files.createTempDirectory("timescute-test");
        check(PathFiles.list(dir.resolve("naoexiste")).isEmpty(), "listar pasta inexistente dá lista vazia");
        PathFiles.save(dir, "Meu Trajeto", src);
        PathFiles.save(dir, PathFiles.AUTOSAVE, src);
        List<PathFiles.Entry> list = PathFiles.list(dir);
        check(list.size() == 1 && list.get(0).name().equals("Meu Trajeto"), "lista mostra só trajetos do usuário (autosave escondido)");
        check(PathFiles.read(dir, "Meu Trajeto").keys().size() == 2, "lê o arquivo salvo");
        check(PathFiles.exists(dir, PathFiles.AUTOSAVE), "autosave existe");
        PathFiles.save(dir, "Meu Trajeto", empty);
        check(PathFiles.read(dir, "Meu Trajeto").keys().isEmpty(), "salvar de novo sobrescreve");
        check(Files.list(dir).noneMatch(f -> f.toString().endsWith(".tmp")), "não sobra arquivo temporário");
        Files.writeString(dir.resolve("quebrado.json"), "{ nao e json");
        boolean bad = false;
        try { PathFiles.read(dir, "quebrado"); } catch (IllegalArgumentException ex) { bad = true; }
        check(bad, "arquivo corrompido gera erro tratável");
        boolean missing = false;
        try { PathFiles.read(dir, "fantasma"); } catch (java.io.IOException ex) { missing = true; }
        check(missing, "arquivo inexistente gera IOException");
        check(PathFiles.delete(dir, "Meu Trajeto") && !PathFiles.delete(dir, "Meu Trajeto"), "excluir funciona uma vez");

        check(PathFiles.sanitize("../../etc/passwd").equals("etc_passwd") || !PathFiles.sanitize("../../etc/passwd").contains("/"), "sanitize remove barras (sem path traversal)");
        check(PathFiles.sanitize("  ").equals("trajeto") && PathFiles.sanitize(null).equals("trajeto"), "sanitize nunca devolve vazio");
        check(!PathFiles.sanitize("_oculto").startsWith("_") && !PathFiles.sanitize(".x").startsWith("."), "sanitize não cria nomes internos/ocultos");
        check(PathFiles.sanitize("Câmera ação-1").equals("Câmera ação-1"), "sanitize mantém acentos, espaço e hífen");
        check(PathFiles.sanitize("a".repeat(100)).length() == 48, "sanitize limita o tamanho");

        // ---- geometria ----
        for (double[] ang : new double[][]{{0, 0}, {90, 0}, {-135, 40}, {200, -60}}) {
            CameraState s = cs(0, 0, 0, ang[0], ang[1]);
            double[] f = PathGeometry.forward(s), rr = PathGeometry.right(s), u = PathGeometry.up(s);
            check(near(dot(f, rr), 0) && near(dot(f, u), 0) && near(dot(rr, u), 0) && near(dot(u, u), 1),
                    "base ortonormal (yaw " + ang[0] + ", pitch " + ang[1] + ")");
        }
        double[] f0 = PathGeometry.forward(cs(0, 0, 0, 0, 0));
        check(near(f0[2], 1) && near(PathGeometry.up(cs(0, 0, 0, 0, 0))[1], 1), "yaw 0 olha para +Z e 'cima' é +Y");
        check(near(PathGeometry.forward(cs(0, 0, 0, 90, 0))[0], -1), "yaw 90 olha para -X (convenção do Minecraft)");
        check(near(PathGeometry.forward(cs(0, 0, 0, 0, -90))[1], 1), "pitch -90 olha para cima");

        List<PathGeometry.Line> fr = PathGeometry.frustum(cs(5, 6, 7, 0, 0), 1.0, 16.0 / 9.0);
        check(fr.size() == 11, "frustum tem 11 linhas");
        check(fr.stream().allMatch(l -> !Double.isNaN(l.ax() + l.ay() + l.az() + l.bx() + l.by() + l.bz())), "frustum sem NaN");
        check(near(fr.get(0).ax(), 5) && near(fr.get(0).ay(), 6) && near(fr.get(0).az(), 7), "frustum parte da posição da câmera");
        List<PathGeometry.Line> wide = PathGeometry.frustum(new CameraState(0, 0, 0, 0, 0, 0, 120), 1, 1);
        List<PathGeometry.Line> tight = PathGeometry.frustum(new CameraState(0, 0, 0, 0, 0, 0, 30), 1, 1);
        check(Math.abs(wide.get(0).bx()) > Math.abs(tight.get(0).bx()), "FOV maior abre mais a pirâmide");

        Timeline g = new Timeline();
        g.capture(0, cs(0, 0, 0, 0, 0));
        g.capture(1000, cs(10, 0, 0, 0, 0));
        g.capture(2000, cs(10, 0, 10, 0, 0));
        List<PathGeometry.Segment> trail = PathGeometry.trail(g.path(), 8);
        check(trail.size() == 2 && trail.get(0).lines().size() == 8, "trail: 1 trecho por par de keyframes, N linhas cada");
        PathGeometry.Line lastLine = trail.get(1).lines().get(7);
        check(near(lastLine.bx(), 10) && near(lastLine.bz(), 10), "trail termina exatamente no último keyframe");
        check(PathGeometry.trail(new CameraPath(), 8).isEmpty(), "trail de trajeto vazio é vazio");

        System.out.println("\n" + passed + " verificações da fase 2 passaram.");
    }
}
