package dev.timescute.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Phase3Tests {
    static int passed = 0;

    static void check(boolean c, String name) {
        if (!c) throw new AssertionError("FALHOU: " + name);
        passed++;
        System.out.println("  ok  " + name);
    }

    static boolean throwsIO(IORunnable r, String contains) {
        try { r.run(); return false; }
        catch (IOException e) { return contains == null || (e.getMessage() != null && e.getMessage().contains(contains)); }
    }

    interface IORunnable { void run() throws IOException; }

    static ReplayMeta meta(long start) {
        ReplayMeta m = new ReplayMeta();
        m.mcVersion = "1.20.1"; m.protocol = 763; m.modVersion = "0.3.0";
        m.startedAtMs = start; m.server = "play.exemplo.com"; m.serverName = "Servidor Ação";
        m.playerName = "Fulano"; m.playerUuid = "00000000-0000-0000-0000-000000000001";
        return m;
    }

    static List<ReplayRecord> readAll(Path p) throws IOException {
        List<ReplayRecord> out = new ArrayList<>();
        try (ReplayReader r = ReplayReader.open(p)) { r.forEach(out::add); }
        return out;
    }

    static boolean same(ReplayRecord a, ReplayRecord b) {
        return a.timeMs() == b.timeMs() && a.kind() == b.kind() && Arrays.equals(a.data(), b.data());
    }

    static boolean samePrefix(List<ReplayRecord> got, List<ReplayRecord> expected) {
        if (got.size() > expected.size()) return false;
        for (int i = 0; i < got.size(); i++) if (!same(got.get(i), expected.get(i))) return false;
        return true;
    }

    static byte[] bytes(Random r, int n) { byte[] b = new byte[n]; r.nextBytes(b); return b; }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tsr-test");

        // ---- amostra do jogador ----
        SelfSample s = new SelfSample(42, 1.5, -64.25, 1e6 + 0.125, 90f, -12.5f, 91f,
                SelfSample.SNEAKING | SelfSample.ON_GROUND, 3, 17.5f);
        SelfSample s2 = SelfSample.decode(s.encode());
        check(s.equals(s2) && s.encode().length == SelfSample.ENCODED_LEN, "SelfSample: ida e volta exata");
        check(s2.has(SelfSample.SNEAKING) && s2.has(SelfSample.ON_GROUND) && !s2.has(SelfSample.SPRINTING), "SelfSample: flags");
        boolean badLen = false;
        try { SelfSample.decode(new byte[3]); } catch (IllegalArgumentException e) { badLen = true; }
        check(badLen, "SelfSample: rejeita tamanho inválido");

        // ---- metadados ----
        ReplayMeta m = meta(1234567890123L);
        m.durationMs = 98765; m.packets = 5; m.markers = 2; m.singleplayer = false; m.stopReason = "teste \"aspas\"\n";
        m.markerList.add(new ReplayMeta.Marker(1000, "Início ✓"));
        m.markerList.add(new ReplayMeta.Marker(5000, "Linha\ndupla"));
        ReplayMeta back = ReplayMeta.fromJson(m.toJson());
        check(back.startedAtMs == m.startedAtMs && back.durationMs == 98765 && back.protocol == 763, "Meta: números");
        check(back.serverName.equals("Servidor Ação") && back.stopReason.equals(m.stopReason), "Meta: textos com acento/aspas/quebra");
        check(back.markerList.equals(m.markerList), "Meta: marcadores");
        check(back.where().equals("Servidor Ação"), "Meta.where usa o nome do servidor");
        ReplayMeta solo = new ReplayMeta(); solo.singleplayer = true; solo.world = "Meu Mundo";
        check(solo.where().equals("Meu Mundo"), "Meta.where em um jogador usa o mundo");
        boolean badMeta = false;
        try { ReplayMeta.fromJson("{\"format\":\"outro\"}"); } catch (IllegalArgumentException e) { badMeta = true; }
        check(badMeta, "Meta: rejeita formato errado");

        // ---- ida e volta (vários blocos) ----
        AtomicLong clk = new AtomicLong(1_000_000);
        ReplayWriter.Options o = new ReplayWriter.Options();
        o.clock = clk::get;
        o.maxBatchBytes = 4096;
        Path f1 = tmp.resolve("a.tsr");
        ReplayWriter w = ReplayWriter.create(f1, meta(1000), o);
        Random rnd = new Random(7);
        List<ReplayRecord> expected = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            clk.set(1_000_000 + i * 10L);
            int kind = i % 3 == 0 ? ReplayFormat.KIND_SELF : ReplayFormat.KIND_PACKET;
            byte[] d = bytes(rnd, 1 + rnd.nextInt(120));
            w.append(kind, d);
            expected.add(new ReplayRecord(i * 10L, kind, d));
            if (i % 1000 == 500) {
                w.addMarker("Marcador " + i);
                expected.add(new ReplayRecord(i * 10L, ReplayFormat.KIND_MARKER, ("Marcador " + i).getBytes(StandardCharsets.UTF_8)));
            }
        }
        w.close();
        check(w.error().isEmpty() && w.isClosed(), "gravador fecha sem erro");
        try (ReplayReader r = ReplayReader.open(f1)) {
            check(r.isComplete(), "arquivo fechado tem rodapé (completo)");
            check(r.chunks().size() > 5, "dados divididos em vários blocos (" + r.chunks().size() + ")");
            check(r.recordCount() == expected.size(), "contagem de registros no índice");
            check(r.meta().packets + r.meta().selfSamples + r.meta().markers == expected.size(), "contagens por tipo somam o total");
            check(r.meta().markers == 5 && r.meta().markerList.size() == 5, "5 marcadores nos metadados");
            check(r.meta().durationMs == 4999 * 10L, "duração = instante do último registro");
            check(r.meta().playerName.equals("Fulano"), "metadados iniciais preservados");
        }
        List<ReplayRecord> got = readAll(f1);
        boolean allSame = got.size() == expected.size();
        for (int i = 0; allSame && i < got.size(); i++) allSame = same(got.get(i), expected.get(i));
        check(allSame, "todos os " + expected.size() + " registros voltam idênticos e na ordem");

        // ---- busca por tempo ----
        List<ReplayRecord> from = new ArrayList<>();
        try (ReplayReader r = ReplayReader.open(f1)) { r.forEachFrom(30_000, from::add); }
        long wantCount = expected.stream().filter(x -> x.timeMs() >= 30_000).count();
        check(from.size() == wantCount && from.get(0).timeMs() >= 30_000, "forEachFrom devolve só registros a partir do instante");
        List<ReplayRecord> none = new ArrayList<>();
        try (ReplayReader r = ReplayReader.open(f1)) { r.forEachFrom(10_000_000, none::add); }
        check(none.isEmpty(), "forEachFrom depois do fim não devolve nada");

        // ---- compressão ----
        ReplayWriter.Options oc = new ReplayWriter.Options();
        Path fz = tmp.resolve("zeros.tsr");
        ReplayWriter wz = ReplayWriter.create(fz, meta(1), oc);
        for (int i = 0; i < 2000; i++) wz.append(ReplayFormat.KIND_PACKET, new byte[1000]);
        wz.close();
        check(Files.size(fz) < 2000 * 1000 / 20, "dados repetitivos comprimem bem (" + Files.size(fz) + " bytes)");
        Path fr = tmp.resolve("random.tsr");
        ReplayWriter wr = ReplayWriter.create(fr, meta(1), oc);
        Random rr = new Random(1);
        for (int i = 0; i < 2000; i++) wr.append(ReplayFormat.KIND_PACKET, bytes(rr, 500));
        wr.close();
        check(Files.size(fr) < 2000L * 509 * 1.05 + 2000, "dados aleatórios não incham (guardados sem compressão)");
        check(readAll(fr).size() == 2000 && readAll(fz).size() == 2000, "ambos continuam legíveis");

        // ---- miniatura ----
        Path ft = tmp.resolve("thumb.tsr");
        ReplayWriter wt = ReplayWriter.create(ft, meta(1), new ReplayWriter.Options());
        wt.append(ReplayFormat.KIND_PACKET, new byte[]{1, 2, 3});
        byte[] png = bytes(new Random(3), 3000);
        wt.setThumbnail(png);
        wt.flushNow();
        wt.close();
        try (ReplayReader r = ReplayReader.open(ft)) { check(r.thumbnail().isPresent() && Arrays.equals(r.thumbnail().get(), png), "miniatura volta idêntica"); }
        try (ReplayReader r = ReplayReader.open(fz)) { check(r.thumbnail().isEmpty(), "sem miniatura devolve vazio"); }
        check(ReplayLibrary.thumbnail(ft).isPresent() && ReplayLibrary.thumbnail(tmp.resolve("nao-existe.tsr")).isEmpty(), "Library.thumbnail");

        // ---- crash: arquivo sem rodapé ----
        AtomicLong clk2 = new AtomicLong(0);
        ReplayWriter.Options oq = new ReplayWriter.Options();
        oq.clock = clk2::get;
        oq.maxBatchBytes = 2048;
        Path live = tmp.resolve("live.tsr");
        ReplayWriter wl = ReplayWriter.create(live, meta(1500), oq);
        List<ReplayRecord> exp2 = new ArrayList<>();
        Random r2 = new Random(11);
        for (int i = 0; i < 3000; i++) {
            clk2.set(i * 5L);
            byte[] d = bytes(r2, 20 + r2.nextInt(80));
            wl.append(ReplayFormat.KIND_PACKET, d);
            exp2.add(new ReplayRecord(i * 5L, ReplayFormat.KIND_PACKET, d));
        }
        wl.flushNow();
        Path crashed = tmp.resolve("crashed.tsr");
        Files.copy(live, crashed);
        wl.close();
        try (ReplayReader r = ReplayReader.open(crashed)) {
            check(!r.isComplete(), "cópia sem rodapé é detectada como incompleta");
            check(r.recordCount() == 3000 && r.meta().durationMs == 2999 * 5L, "reconstrói índice e duração varrendo os blocos");
        }
        check(samePrefix(readAll(crashed), exp2) && readAll(crashed).size() == 3000, "todos os registros recuperados do arquivo sem rodapé");

        byte[] all = Files.readAllBytes(crashed);
        Path cut = tmp.resolve("cut.tsr");
        Files.write(cut, Arrays.copyOf(all, all.length - 7));
        List<ReplayRecord> cutRecs = readAll(cut);
        check(cutRecs.size() < 3000 && cutRecs.size() > 2000 && samePrefix(cutRecs, exp2), "bloco cortado ao meio é descartado; o resto é um prefixo exato");
        try (ReplayReader r = ReplayReader.open(cut)) { check(!r.isComplete(), "arquivo cortado continua incompleto"); }
        check(ReplayRepair.repair(cut), "reparo age em arquivo incompleto");
        try (ReplayReader r = ReplayReader.open(cut)) {
            check(r.isComplete() && r.meta().stopReason.contains("Recuperado"), "depois do reparo: completo e com aviso");
        }
        check(readAll(cut).size() == cutRecs.size() && samePrefix(readAll(cut), exp2), "reparo não perde nem altera registros");
        check(!ReplayRepair.repair(cut), "reparar de novo não faz nada");
        check(!ReplayRepair.repair(f1), "reparar arquivo íntegro não faz nada");

        Path garbageTail = tmp.resolve("tail.tsr");
        byte[] withJunk = Arrays.copyOf(all, all.length + 50);
        for (int i = all.length; i < withJunk.length; i++) withJunk[i] = (byte) 0xEE;
        Files.write(garbageTail, withJunk);
        check(readAll(garbageTail).size() == 3000, "lixo no fim do arquivo é ignorado");

        // ---- arquivos ruins ----
        Path junk = tmp.resolve("junk.tsr");
        Files.writeString(junk, "isto nao e um replay de verdade, so texto");
        check(throwsIO(() -> ReplayReader.open(junk).close(), "não é um replay"), "rejeita arquivo qualquer");
        Path tiny = tmp.resolve("tiny.tsr");
        Files.write(tiny, new byte[5]);
        check(throwsIO(() -> ReplayReader.open(tiny).close(), "pequeno"), "rejeita arquivo minúsculo");
        byte[] future = Files.readAllBytes(f1);
        future[9] = 99;
        Path fut = tmp.resolve("future.tsr");
        Files.write(fut, future);
        check(throwsIO(() -> ReplayReader.open(fut).close(), "mais nova"), "rejeita versão futura");

        try (ReplayReader r = ReplayReader.open(f1)) {
            ReplayChunk mid = r.chunks().get(r.chunks().size() / 2);
            byte[] flip2 = Files.readAllBytes(f1);
            flip2[(int) mid.offset() + 40] ^= 0x5A;
            Path bad2 = tmp.resolve("flip2.tsr");
            Files.write(bad2, flip2);
            check(throwsIO(() -> readAll(bad2), "corrompido"), "byte alterado no meio de um bloco é detectado (checksum)");
        }

        byte[] noFooter = Files.readAllBytes(f1);
        noFooter[noFooter.length - 10] ^= 0x77; // estraga o rodapé
        Path nf = tmp.resolve("nofooter.tsr");
        Files.write(nf, noFooter);
        try (ReplayReader r = ReplayReader.open(nf)) {
            check(!r.isComplete(), "rodapé estragado: cai para a varredura");
        }
        check(readAll(nf).size() == expected.size(), "rodapé estragado: TODOS os dados continuam legíveis");

        // ---- proteções ----
        ReplayWriter.Options ob = new ReplayWriter.Options();
        ob.maxBacklogBytes = 1000;
        Path fb = tmp.resolve("backlog.tsr");
        ReplayWriter wb = ReplayWriter.create(fb, meta(1), ob);
        check(wb.append(ReplayFormat.KIND_PACKET, new byte[10]), "append pequeno é aceito");
        check(!wb.append(ReplayFormat.KIND_PACKET, new byte[5000]), "fila estourada recusa o item");
        check(wb.error().isPresent() && !wb.append(ReplayFormat.KIND_PACKET, new byte[1]), "depois do erro, nada mais é aceito");
        wb.close();
        try (ReplayReader r = ReplayReader.open(fb)) {
            check(r.isComplete() && r.meta().stopReason.contains("disco"), "arquivo de gravação interrompida continua válido, com o motivo");
        }

        ReplayWriter.Options od = new ReplayWriter.Options();
        od.minFreeBytes = Long.MAX_VALUE;
        Path fd = tmp.resolve("disk.tsr");
        ReplayWriter wd = ReplayWriter.create(fd, meta(1), od);
        wd.append(ReplayFormat.KIND_PACKET, new byte[100]);
        wd.flushNow();
        check(wd.error().isPresent() && wd.error().get().contains("espaço"), "pouco espaço em disco para a gravação");
        wd.close();
        try (ReplayReader r = ReplayReader.open(fd)) {
            check(r.isComplete() && r.meta().stopReason.contains("espaço") && r.recordCount() == 0, "arquivo continua válido e explica o motivo");
        }

        // ---- gravação concorrente ----
        ReplayWriter.Options oc2 = new ReplayWriter.Options();
        oc2.clock = () -> System.nanoTime() / 1_000_000L;
        oc2.maxBatchBytes = 8192;
        Path fc = tmp.resolve("conc.tsr");
        ReplayWriter wc = ReplayWriter.create(fc, meta(1), oc2);
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++) {
            final int id = t;
            ts[t] = new Thread(() -> { for (int i = 0; i < 2000; i++) wc.append(ReplayFormat.KIND_PACKET, new byte[]{(byte) id, (byte) i}); });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        wc.close();
        List<ReplayRecord> conc = readAll(fc);
        boolean mono = true;
        for (int i = 1; i < conc.size(); i++) mono &= conc.get(i).timeMs() >= conc.get(i - 1).timeMs();
        check(conc.size() == 8000 && mono, "4 threads gravando juntas: 8000 registros, tempos nunca voltam");

        // ---- biblioteca ----
        Path lib = Files.createDirectory(tmp.resolve("lib"));
        Files.copy(f1, lib.resolve("antigo.tsr"));          // início = 1000
        Files.copy(ft, lib.resolve("novo.tsr"));            // início = 1 (miniatura)
        Files.copy(crashed, lib.resolve("crash.tsr"));      // início = 1500, sem rodapé
        Files.copy(junk, lib.resolve("lixo.tsr"));
        Files.writeString(lib.resolve("notas.txt"), "ignorar");
        List<ReplayLibrary.Entry> es = ReplayLibrary.scan(lib);
        Map<String, ReplayLibrary.Entry> byName = new HashMap<>();
        for (ReplayLibrary.Entry e : es) byName.put(e.name(), e);
        check(es.size() == 4 && !byName.containsKey("notas"), "scan lista só .tsr");
        check(byName.get("antigo").complete() && !byName.get("crash").complete() && byName.get("crash").readable(), "scan: completo x incompleto");
        check(!byName.get("lixo").readable() && byName.get("lixo").error() != null, "scan: arquivo ruim vira entrada com erro");
        check(byName.get("antigo").durationMs() == 49_990 && byName.get("antigo").meta().where().equals("Servidor Ação"), "scan: duração e origem");
        int iAntigo = es.indexOf(byName.get("antigo")), iCrash = es.indexOf(byName.get("crash")), iNovo = es.indexOf(byName.get("novo"));
        check(iCrash < iAntigo && iAntigo < iNovo, "ordenado do mais recente para o mais antigo");
        check(ReplayLibrary.scan(tmp.resolve("sem-pasta")).isEmpty(), "pasta inexistente = lista vazia");

        Path renamed = ReplayLibrary.rename(lib.resolve("antigo.tsr"), "Meu Replay ação");
        check(renamed.getFileName().toString().equals("Meu Replay ação.tsr") && Files.exists(renamed) && !Files.exists(lib.resolve("antigo.tsr")), "renomear");
        boolean clash = false;
        try { ReplayLibrary.rename(renamed, "novo"); } catch (FileAlreadyExistsException e) { clash = true; }
        check(clash, "renomear para nome existente é recusado");
        check(ReplayLibrary.rename(renamed, "Meu Replay ação").equals(renamed), "renomear para o mesmo nome não faz nada");
        check(ReplayLibrary.rename(renamed, "../../fuga").getParent().equals(lib), "renomear não escapa da pasta");
        check(ReplayLibrary.delete(lib.resolve("lixo.tsr")) && !ReplayLibrary.delete(lib.resolve("lixo.tsr")), "excluir");

        Path u1 = ReplayLibrary.uniqueFile(lib, "novo");
        check(u1.getFileName().toString().equals("novo-2.tsr"), "uniqueFile evita sobrescrever");
        check(ReplayLibrary.uniqueFile(lib, "livre").getFileName().toString().equals("livre.tsr"), "uniqueFile devolve o nome puro quando livre");
        check(ReplayLibrary.sanitizeName("a/b\\c:d*e").indexOf('/') < 0 && !ReplayLibrary.sanitizeName("..oculto").startsWith("."), "sanitizeName");
        check(ReplayLibrary.sanitizeName("   ").equals("replay") && ReplayLibrary.sanitizeName("x".repeat(200)).length() == 64, "sanitizeName: vazio e limite");
        check(ReplayLibrary.defaultName(1_700_000_000_000L, "Servidor X").matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_Servidor X"), "defaultName: data + origem");
        check(ReplayLibrary.defaultName(1_700_000_000_000L, "").matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}"), "defaultName sem origem");
        check(ReplayLibrary.formatDuration(3_725_000).equals("1:02:05") && ReplayLibrary.formatDuration(65_000).equals("01:05"), "formatDuration");
        check(ReplayLibrary.formatSize(1536).equals("1.5 KB") && ReplayLibrary.formatSize(5L * 1024 * 1024).equals("5.0 MB") && ReplayLibrary.formatSize(12).equals("12 B"), "formatSize");

        // ---- configurações ----
        Path cfg = tmp.resolve("cfg").resolve("settings.json");
        Settings def = Settings.load(cfg);
        check(def.autoRecord && def.recordSingleplayer && def.compressionLevel == 3, "padrões quando não há arquivo");
        def.autoRecord = false; def.compressionLevel = 9; def.minFreeMb = 2048;
        def.save(cfg);
        Settings rd = Settings.load(cfg);
        check(!rd.autoRecord && rd.compressionLevel == 9 && rd.minFreeMb == 2048, "configurações salvam e carregam");
        Files.writeString(cfg, "{\"compressionLevel\": 99, \"maxBacklogMb\": 1, \"minFreeMb\": -5}");
        Settings cl = Settings.load(cfg);
        check(cl.compressionLevel == 9 && cl.maxBacklogMb == 16 && cl.minFreeMb == 0, "valores absurdos são limitados");
        Files.writeString(cfg, "{ isso nao e json");
        check(Settings.load(cfg).autoRecord, "arquivo corrompido volta aos padrões sem erro");
        ReplayWriter.Options wo = def.writerOptions();
        check(wo.level == 9 && wo.minFreeBytes == 2048L * 1024 * 1024, "Settings -> opções do gravador");

        System.out.println("\n" + passed + " verificações da fase 3 passaram.");
    }
}
