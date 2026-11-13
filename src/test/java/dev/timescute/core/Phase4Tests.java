package dev.timescute.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Phase4Tests {
    static int passed = 0;

    static void check(boolean c, String name) {
        if (!c) throw new AssertionError("FALHOU: " + name);
        passed++;
        System.out.println("  ok  " + name);
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    static byte[] varInt(int v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
        return o.toByteArray();
    }

    /** Cria um replay sintético: pacotes a cada 50ms, amostra do jogador a cada 50ms, marcadores em 2s e 6s. */
    static Path makeReplay(Path dir, String name, int seconds, boolean close) throws Exception {
        AtomicLong clk = new AtomicLong(0);
        ReplayWriter.Options o = new ReplayWriter.Options();
        o.clock = clk::get;
        o.maxBatchBytes = 2048;
        ReplayMeta m = new ReplayMeta();
        m.protocol = 763; m.playerName = "Fulano"; m.startedAtMs = 5;
        Path f = dir.resolve(name + ".tsr");
        ReplayWriter w = ReplayWriter.create(f, m, o);
        for (int t = 0; t <= seconds * 1000; t += 50) {
            clk.set(t);
            w.append(ReplayFormat.KIND_PACKET, new byte[]{1, (byte) (t / 50), (byte) (t >> 8)});
            w.append(ReplayFormat.KIND_SELF, new SelfSample(7, t / 100.0, 64, 0, 0, 0, 0, SelfSample.ON_GROUND, 0, 20f).encode());
            if (t == 2000 || t == 6000) w.addMarker("M" + t);
        }
        if (close) w.close(); else w.flushNow();
        return f;
    }

    static List<ReplayRecord> drain(ReplayCursor c) throws IOException {
        List<ReplayRecord> out = new ArrayList<>();
        ReplayRecord r;
        while ((r = c.next()) != null) out.add(r);
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tsr4");

        // ---- bytes de pacote ----
        for (int v : new int[]{0, 1, 127, 128, 300, 16383, 16384, 2097151, 2097152, Integer.MAX_VALUE}) {
            byte[] enc = varInt(v);
            check(PacketBytes.readVarInt(enc) == v && PacketBytes.varIntLength(enc) == enc.length, "varint " + v);
        }
        check(PacketBytes.varIntLength(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1}) == -1, "varint longo demais é inválido");
        check(PacketBytes.varIntLength(new byte[]{(byte) 0x80}) == -1 && PacketBytes.varIntLength(new byte[0]) == -1, "varint truncado/vazio é inválido");
        check(PacketBytes.readVarInt(new byte[]{(byte) 0x80}) == -1, "readVarInt truncado devolve -1");

        byte[] pkt = new byte[]{0x26, 0, 0, 0, 5, 9, 9};
        byte[] patched = PacketBytes.patchInt(pkt, 1, -1000);
        check(PacketBytes.readInt(patched, 1) == -1000 && PacketBytes.readInt(pkt, 1) == 5, "patchInt troca o int e não altera o original");
        check(patched[0] == 0x26 && patched[5] == 9 && patched.length == pkt.length, "patchInt preserva o resto");
        check(PacketBytes.readInt(PacketBytes.patchInt(pkt, 1, Integer.MIN_VALUE), 1) == Integer.MIN_VALUE, "patchInt com valores extremos");
        boolean thrown = false;
        try { PacketBytes.patchInt(pkt, 5, 1); } catch (IllegalArgumentException e) { thrown = true; }
        check(thrown, "patchInt recusa pacote curto");

        // ---- relógio ----
        ReplayClock c = new ReplayClock(10_000);
        c.tick(1000);
        check(c.timeMs() == 0, "pausado não avança");
        c.play();
        c.tick(1000);
        check(c.timeMs() == 1000, "1x: 1s real = 1s de replay");
        c.setSpeed(2);
        c.tick(500);
        check(c.timeMs() == 2000, "2x: 0.5s real = 1s de replay");
        c.setSpeed(0.5);
        c.tick(1000);
        check(c.timeMs() == 2500, "0.5x: 1s real = 0.5s de replay");
        c.pause();
        c.advanceExact(1000);
        check(c.timeMs() == 3500 && !c.isPlaying(), "advanceExact funciona mesmo pausado");
        c.advanceExact(-99999);
        check(c.timeMs() == 0, "advanceExact nunca fica negativo");
        c.seek(99_999);
        check(c.timeMs() == 10_000 && c.atEnd(), "seek limita ao fim");
        c.seek(-5);
        check(c.timeMs() == 0, "seek limita ao começo");
        c.seek(9_900);
        c.setSpeed(1);
        c.play();
        c.tick(500);
        check(c.atEnd() && !c.isPlaying() && c.timeMs() == 10_000, "chegar ao fim pausa");
        c.play();
        check(c.timeMs() == 0 && c.isPlaying(), "play no fim recomeça do zero");
        c.jump(3000);
        c.jump(-1000);
        check(c.timeMs() == 2000, "jump relativo");
        c.setSpeed(Double.NaN);
        check(near(c.speed(), 1), "NaN é ignorado");
        c.setSpeed(1000);
        check(near(c.speed(), ReplayClock.MAX_SPEED), "velocidade máxima limitada");
        c.setSpeed(0);
        check(near(c.speed(), ReplayClock.MIN_SPEED), "velocidade mínima limitada (nunca zero)");

        ReplayClock s = new ReplayClock(1000);
        s.setSpeed(1);
        s.stepSpeed(1); check(near(s.speed(), 2), "stepSpeed sobe 1 -> 2");
        s.stepSpeed(1); s.stepSpeed(1); s.stepSpeed(1); check(near(s.speed(), 8), "sobe até 8");
        s.stepSpeed(1); check(near(s.speed(), 8), "não passa do máximo da lista");
        s.stepSpeed(-1); check(near(s.speed(), 4), "desce 8 -> 4");
        s.setSpeed(0.7); s.stepSpeed(1); check(near(s.speed(), 1), "de velocidade fora da lista sobe para a próxima");
        s.setSpeed(0.7); s.stepSpeed(-1); check(near(s.speed(), 0.5), "de velocidade fora da lista desce para a anterior");
        s.setSpeed(0.1); s.stepSpeed(-1); check(near(s.speed(), 0.1), "não passa do mínimo da lista");

        // relógio guiado pela trilha de velocidade da timeline
        Timeline tl = new Timeline();
        tl.addSpeed(0, 1.0);
        tl.addSpeed(1000, 3.0);
        ReplayClock pc = new ReplayClock(100_000);
        long prev = 0;
        for (long out = 100; out <= 1000; out += 100) { pc.advanceExact(tl.speed().replayTimeAt(out) - tl.speed().replayTimeAt(prev)); prev = out; }
        check(Math.abs(pc.exactTimeMs() - 2000) < 1e-6, "avançar por fatias na trilha de velocidade soma a integral exata (2000ms)");

        // ---- marcadores ----
        ReplayMeta mm = new ReplayMeta();
        mm.markerList.add(new ReplayMeta.Marker(2000, "a"));
        mm.markerList.add(new ReplayMeta.Marker(6000, "b"));
        mm.markerList.add(new ReplayMeta.Marker(9000, "c"));
        check(mm.nextMarkerAfter(0).getAsLong() == 2000 && mm.nextMarkerAfter(2000).getAsLong() == 6000, "próximo marcador");
        check(mm.nextMarkerAfter(9000).isEmpty(), "sem próximo marcador depois do último");
        check(mm.previousMarkerBefore(6500).getAsLong() == 2000, "anterior pula o marcador onde acabamos de chegar (folga de 1s)");
        check(mm.previousMarkerBefore(7500).getAsLong() == 6000 && mm.previousMarkerBefore(1000).isEmpty(), "anterior e sem anterior");
        check(new ReplayMeta().nextMarkerAfter(0).isEmpty() && new ReplayMeta().previousMarkerBefore(99999).isEmpty(), "sem marcadores");

        // ---- cursor ----
        Path rp = makeReplay(tmp, "a", 20, true);
        List<ReplayRecord> all = new ArrayList<>();
        try (ReplayReader r = ReplayReader.open(rp)) { r.forEach(all::add); }
        try (ReplayCursor cur = ReplayCursor.open(rp)) {
            check(cur.durationMs() == 20_000 && cur.isComplete(), "cursor: duração e estado");
            ReplayRecord p1 = cur.peek(), p2 = cur.peek();
            check(p1 == p2 && cur.hasNext(), "peek não consome");
            List<ReplayRecord> got = drain(cur);
            boolean same = got.size() == all.size();
            for (int i = 0; same && i < got.size(); i++) same = got.get(i).timeMs() == all.get(i).timeMs() && got.get(i).kind() == all.get(i).kind() && Arrays.equals(got.get(i).data(), all.get(i).data());
            check(same && got.size() > 800, "cursor entrega exatamente o mesmo que o leitor (" + got.size() + " registros)");
            check(!cur.hasNext() && cur.next() == null && cur.peek() == null, "no fim: nada mais");
            check(cur.lastTimeMs() == 20_000, "lastTimeMs acompanha o último entregue");
            cur.reset();
            check(cur.lastTimeMs() == 0 && cur.next().timeMs() == all.get(0).timeMs(), "reset volta ao primeiro registro");
            check(drain(cur).size() == all.size() - 1, "depois do reset lê tudo de novo");
            check(cur.firstSelf().isPresent() && cur.firstSelf().get().entityId() == 7, "firstSelf acha a primeira amostra do jogador");
        }

        Path live = makeReplay(tmp, "live", 10, false);
        Path crashed = tmp.resolve("crashed.tsr");
        Files.copy(live, crashed);
        try (ReplayCursor cur = ReplayCursor.open(crashed)) {
            check(!cur.isComplete() && cur.durationMs() == 10_000 && drain(cur).size() > 400, "cursor lê arquivo incompleto (sem rodapé)");
        }

        ReplayMeta em = new ReplayMeta();
        Path empty = tmp.resolve("empty.tsr");
        ReplayWriter ew = ReplayWriter.create(empty, em, new ReplayWriter.Options());
        ew.append(ReplayFormat.KIND_PACKET, new byte[]{1});
        ew.close();
        try (ReplayCursor cur = ReplayCursor.open(empty)) {
            check(cur.firstSelf().isEmpty(), "firstSelf vazio quando não há amostras");
        }
        Path none = tmp.resolve("none.tsr");
        ReplayWriter nw = ReplayWriter.create(none, new ReplayMeta(), new ReplayWriter.Options());
        nw.close();
        try (ReplayCursor cur = ReplayCursor.open(none)) {
            check(cur.peek() == null && !cur.hasNext() && cur.durationMs() == 0, "replay sem registros: cursor vazio");
        }
        try (ReplayReader r = ReplayReader.open(rp)) {
            boolean oob = false;
            try { r.readChunkRecords(9999); } catch (IndexOutOfBoundsException e) { oob = true; }
            check(oob, "readChunkRecords recusa índice inválido");
            check(r.readChunkRecords(0).size() == r.chunks().get(0).records(), "readChunkRecords traz a quantidade do índice");
        }

        // ---- alimentador: reprodução simulada ----
        List<ReplayRecord> played = new ArrayList<>();
        long[] lastTarget = {0};
        boolean[] early = {false};
        try (ReplayCursor cur = ReplayCursor.open(rp)) {
            ReplayClock clk = new ReplayClock(cur.durationMs());
            clk.play();
            clk.setSpeed(1);
            int frames = 0;
            while (!clk.atEnd() && frames < 100_000) {
                clk.tick(16.7);
                lastTarget[0] = clk.timeMs();
                ReplayFeeder.feed(cur, clk.timeMs(), r -> { if (r.timeMs() > lastTarget[0]) early[0] = true; played.add(r); }, () -> false);
                frames++;
            }
            ReplayFeeder.Result end = ReplayFeeder.feed(cur, clk.timeMs(), played::add, () -> false);
            check(!early[0], "nenhum registro é entregue antes da hora");
            check(played.size() == all.size() && end.reachedEnd(), "reprodução em tempo real entrega todos os registros, uma vez cada");
            boolean ordered = true;
            for (int i = 1; i < played.size(); i++) ordered &= played.get(i).timeMs() >= played.get(i - 1).timeMs();
            check(ordered, "entregues em ordem");
        }

        try (ReplayCursor cur = ReplayCursor.open(rp)) { // orçamento estourado não perde nem repete nada
            List<ReplayRecord> got = new ArrayList<>();
            int calls = 0;
            ReplayFeeder.Result r;
            do {
                final int[] budget = {0};
                r = ReplayFeeder.feed(cur, 20_000, got::add, () -> ++budget[0] >= 1); // estoura a cada 32 registros
                calls++;
            } while (!r.reachedEnd() && calls < 10_000);
            check(calls > 20 && got.size() == all.size(), "orçamento estourado: retoma de onde parou, sem perder (" + calls + " passadas)");
            Set<String> uniq = new HashSet<>();
            for (ReplayRecord x : got) uniq.add(x.timeMs() + ":" + x.kind() + ":" + Arrays.toString(x.data()));
            check(uniq.size() == got.size(), "sem repetição");
        }

        try (ReplayCursor cur = ReplayCursor.open(rp)) { // avançar (seek para frente) e recomeçar (seek para trás)
            ReplayFeeder.feed(cur, 8_000, r -> { }, () -> false);
            long at8 = cur.lastTimeMs();
            check(at8 <= 8_000 && at8 > 7_900, "avanço rápido até 8s");
            cur.reset();
            int[] packets = {0};
            long[] lastSelfX = {-1};
            ReplayFeeder.feed(cur, 3_000, r -> {
                if (r.kind() == ReplayFormat.KIND_PACKET) packets[0]++;
                if (r.kind() == ReplayFormat.KIND_SELF) lastSelfX[0] = (long) SelfSample.decode(r.data()).x();
            }, () -> false);
            check(packets[0] == 61 && lastSelfX[0] == 30, "recomeçar e avançar até 3s reconstrói o estado (61 pacotes; jogador em x=30)");
        }

        System.out.println("\n" + passed + " verificações da fase 4 passaram.");
    }
}
