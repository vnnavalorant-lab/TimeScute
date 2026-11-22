package dev.timescute.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Phase5Tests {
    static int passed = 0;

    static void check(boolean c, String name) {
        if (!c) throw new AssertionError("FALHOU: " + name);
        passed++;
        System.out.println("  ok  " + name);
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    static boolean throwsIAE(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tsr5");

        // ---- plano ----
        SpeedTrack none = new SpeedTrack();
        RenderPlan p30 = new RenderPlan(0, 10_000, 30, none);
        check(p30.totalFrames() == 301 && new RenderPlan(0, 10_000, 60, none).totalFrames() == 601, "10 s a 30/60 fps = 301/601 quadros");
        check(new RenderPlan(0, 1001, 30, none).totalFrames() == 32, "duração quebrada arredonda para cima (32 quadros)");
        check(p30.outputTimeMs(0) == 0 && p30.outputTimeMs(p30.totalFrames() - 1) == 10_000, "primeiro quadro no início, último exatamente no fim");
        RenderPlan p60 = new RenderPlan(5_000, 65_000, 60, none);
        check(p60.outputTimeMs(0) == 5_000 && p60.outputTimeMs(3600) == 65_000, "sem deriva: quadro 3600 a 60 fps cai exatamente 60 s depois");
        boolean inc = true, within = true;
        for (long i = 1; i < p60.totalFrames(); i++) {
            inc &= p60.outputTimeMs(i) >= p60.outputTimeMs(i - 1);
            within &= p60.outputTimeMs(i) <= 65_000;
        }
        check(inc && within, "tempos dos quadros nunca voltam nem passam do fim");
        check(p60.durationSeconds() == 60.0 && p60.startMs() == 5_000 && p60.endMs() == 65_000 && p60.fps() == 60, "getters do plano");
        check(throwsIAE(() -> new RenderPlan(0, 1000, 0, none)) && throwsIAE(() -> new RenderPlan(0, 1000, 300, none)), "fps inválido é recusado");
        check(throwsIAE(() -> new RenderPlan(1000, 1000, 30, none)) && throwsIAE(() -> new RenderPlan(2000, 1000, 30, none)), "trajeto sem duração é recusado");
        check(near(p30.progress(0), 0) && near(p30.progress(301), 1) && near(p30.progress(9999), 1) && near(p30.progress(-5), 0), "progresso limitado entre 0 e 1");

        check(near(p30.speedFactor(0), 1) && near(p30.speedFactor(10), 1) && near(p30.tickAdvance(0), 0), "sem trilha de velocidade: fator 1 e o 1º quadro não avança");
        check(near(p30.tickAdvance(5), (1000.0 / 30) / 50.0), "1x: um quadro a 30 fps avança 2/3 de tick");

        boolean uniform = true;
        for (long i = 1; i < p30.totalFrames() - 1; i++) uniform &= near(p30.replayAdvanceMs(i), 1000.0 / 30);
        check(uniform, "1x a 30 fps: todo quadro avança o MESMO tempo de mundo (33,33 ms, sem alternar 33/34)");
        check(Math.abs(p30.replayAdvanceMs(p30.totalFrames() - 1) - (10_000 - 300 * 1000.0 / 30)) < 1e-6 || p30.replayAdvanceMs(p30.totalFrames() - 1) >= 0, "último quadro nunca anda para trás");

        Timeline half = new Timeline();
        half.addSpeed(0, 0.5);
        RenderPlan ph = new RenderPlan(0, 10_000, 30, half.speed());
        boolean allHalf = true;
        for (long i = 0; i < ph.totalFrames(); i++) allHalf &= near(ph.speedFactor(i), 0.5);
        check(allHalf, "0.5x constante: fator 0.5 em todos os quadros");
        double sum = 0;
        for (long i = 0; i < ph.totalFrames(); i++) sum += ph.tickAdvance(i);
        check(Math.abs(sum - 100) < 0.01, "0.5x por 10 s = 100 ticks no total (soma " + sum + ")");

        Timeline ramp = new Timeline();
        ramp.addSpeed(0, 1.0);
        ramp.addSpeed(10_000, 3.0);
        RenderPlan pr = new RenderPlan(0, 10_000, 30, ramp.speed());
        check(pr.speedFactor(5) < pr.speedFactor(150) && pr.speedFactor(150) < pr.speedFactor(295), "rampa 1x -> 3x: o fator cresce ao longo do vídeo");
        double sumR = 0;
        for (long i = 0; i < pr.totalFrames(); i++) sumR += pr.tickAdvance(i);
        double expectTicks = (ramp.speed().replayTimeAt(10_000) - ramp.speed().replayTimeAt(0)) / 50.0;
        check(Math.abs(sumR - expectTicks) < 1e-6, "soma dos ticks = tempo de replay percorrido / 50 ms (telescópica, sem erro)");
        double sumMs = 0;
        for (long i = 0; i < pr.totalFrames(); i++) sumMs += pr.replayAdvanceMs(i);
        check(Math.abs(sumMs - 20_000) < 1e-6, "tempo de replay total pela rampa = 20 s");

        // ---- presets ----
        Set<String> ids = new HashSet<>();
        for (RenderPreset p : RenderPreset.values()) ids.add(p.id);
        check(ids.size() == RenderPreset.values().length, "ids de preset são únicos");
        check(RenderPreset.byId("alta") == RenderPreset.ALTA && RenderPreset.byId("???") == RenderPreset.PADRAO && RenderPreset.byId(null) == RenderPreset.PADRAO, "byId com fallback para Padrão");
        check(RenderPreset.RASCUNHO.crf > RenderPreset.PADRAO.crf && RenderPreset.PADRAO.crf > RenderPreset.ALTA.crf, "CRF: rascunho > padrão > alta (menor = melhor)");
        RenderPreset cur = RenderPreset.RASCUNHO;
        for (int i = 0; i < RenderPreset.values().length; i++) cur = cur.next();
        check(cur == RenderPreset.RASCUNHO, "next() dá a volta completa");
        check(RenderPreset.SEM_PERDAS.extension.equals("mkv") && RenderPreset.PADRAO.extension.equals("mp4"), "extensões por preset");

        // ---- comando ----
        check(Arrays.equals(FfmpegCommand.outputSize(1920, 1080, 0), new int[]{1920, 1080}), "tamanho: sem redimensionar");
        check(Arrays.equals(FfmpegCommand.outputSize(1920, 1080, 720), new int[]{1280, 720}), "tamanho: 1080 -> 720");
        check(Arrays.equals(FfmpegCommand.outputSize(1366, 768, 720), new int[]{1280, 720}), "tamanho: largura sempre par (1366x768 -> 1280x720)");
        check(Arrays.equals(FfmpegCommand.outputSize(1281, 721, 0), new int[]{1280, 720}), "tamanho: origem ímpar vira par");
        check(Arrays.equals(FfmpegCommand.outputSize(640, 360, 1080), new int[]{1920, 1080}), "tamanho: ampliar também funciona (640x360 -> 1920x1080)");
        check(Arrays.equals(FfmpegCommand.outputSize(854, 480, 1080), new int[]{1922, 1080}), "tamanho: 854x480 -> 1922x1080 (1921,5 vai para o par mais próximo, como o FFmpeg faz)");

        List<String> cmd = FfmpegCommand.build("ffmpeg", 1920, 1080, 30, 0, RenderPreset.PADRAO, "out.mp4");
        String joined = String.join(" ", cmd);
        check(cmd.get(0).equals("ffmpeg") && cmd.get(cmd.size() - 1).equals("out.mp4"), "executável primeiro, saída por último");
        check(joined.contains("-f rawvideo") && joined.contains("-pix_fmt rgb24") && joined.contains("-s 1920x1080") && joined.contains("-r 30") && joined.contains("-i -"), "entrada: vídeo cru RGB24 pelo stdin, tamanho e fps");
        check(joined.contains("-vf vflip") && !joined.contains("scale"), "só inverte verticalmente quando não redimensiona");
        check(joined.contains("-c:v libx264") && joined.contains("-crf 20") && joined.contains("-preset medium") && joined.contains("-pix_fmt yuv420p") && joined.contains("+faststart"), "H.264 padrão");
        check(cmd.indexOf("-y") > 0 && joined.contains("-loglevel error"), "sobrescreve e fica silencioso");

        String tricky = "C:\\Meus Vídeos\\o \"melhor\" vídeo.mp4";
        List<String> c2 = FfmpegCommand.build("C:\\Program Files\\ffmpeg\\ffmpeg.exe", 1280, 720, 60, 0, RenderPreset.ALTA, tricky);
        check(c2.get(c2.size() - 1).equals(tricky) && c2.get(0).equals("C:\\Program Files\\ffmpeg\\ffmpeg.exe"), "caminhos com espaço, acento e aspas continuam sendo UM argumento");
        check(String.join(" ", FfmpegCommand.build("f", 1920, 1080, 30, 720, RenderPreset.PADRAO, "o.mp4")).contains("scale=-2:720:flags=lanczos"), "redimensiona com lanczos");
        check(String.join(" ", FfmpegCommand.build("f", 1281, 721, 30, 0, RenderPreset.PADRAO, "o.mp4")).contains("scale=1280:720"), "origem ímpar ganha scale para tamanho par");
        String lossless = String.join(" ", FfmpegCommand.build("f", 1920, 1080, 30, 0, RenderPreset.SEM_PERDAS, "o.mkv"));
        check(lossless.contains("-c:v ffv1") && lossless.contains("bgr0") && !lossless.contains("-crf"), "sem perdas: FFV1, sem CRF");

        // ---- configurações de render ----
        Path cfg = tmp.resolve("settings.json");
        Settings s = new Settings();
        s.ffmpegPath = "C:\\Tools\\ff mpeg\\bin\\ffmpeg.exe";
        s.renderPreset = "alta"; s.renderFps = 60; s.renderHeight = 1080;
        s.save(cfg);
        Settings back = Settings.load(cfg);
        check(back.ffmpegPath.equals(s.ffmpegPath) && back.renderPreset.equals("alta") && back.renderFps == 60 && back.renderHeight == 1080, "configurações de render salvam e carregam");
        Files.writeString(cfg, "{\"renderFps\": 50, \"renderHeight\": 800, \"renderPreset\": \"inventado\", \"ffmpegPath\": \"  x  \"}");
        Settings cl = Settings.load(cfg);
        check(cl.renderFps == 48 && cl.renderHeight == 720 && cl.renderPreset.equals("padrao") && cl.ffmpegPath.equals("x"), "valores fora da lista vão para o mais próximo; preset desconhecido vira padrão");
        check(Settings.load(tmp.resolve("nao-existe.json")).renderFps == 30, "padrões de render sem arquivo");

        // ---- localizar o FFmpeg ----
        check(FfmpegTool.probe("/caminho/que/nao/existe/ffmpeg").isEmpty() && FfmpegTool.probe("").isEmpty() && FfmpegTool.probe(null).isEmpty(), "probe: inexistente/vazio/nulo");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Optional<String> v = FfmpegTool.probe(java);
        check(v.isPresent() && !v.get().isBlank(), "probe: executável real que responde a -version (" + v.orElse("") + ")");
        Optional<FfmpegTool.Found> f = FfmpegTool.locate(java);
        check(f.isPresent() && f.get().path().equals(java), "locate: usa primeiro o caminho configurado");
        if (Files.exists(Path.of("/bin/false"))) check(FfmpegTool.probe("/bin/false").isEmpty(), "probe: programa que sai com erro não conta");

        // ---- escritor de quadros (processos reais) ----
        if (Files.exists(Path.of("/bin/sh"))) {
            Path out = tmp.resolve("frames.bin");
            FrameWriter w = FrameWriter.start(List.of("/bin/sh", "-c", "cat > '" + out + "'"));
            ByteArrayOutputStream expect = new ByteArrayOutputStream();
            for (int i = 0; i < 20; i++) {
                byte[] fr = new byte[1000];
                Arrays.fill(fr, (byte) (i + 1));
                w.submit(fr);
                expect.write(fr);
            }
            check(w.finish() == 0 && w.framesWritten() == 20 && w.error() == null, "escritor: 20 quadros, saída 0, sem erro");
            check(Arrays.equals(Files.readAllBytes(out), expect.toByteArray()), "escritor: os bytes chegam inteiros e na ordem");

            Path big = tmp.resolve("big.bin");
            FrameWriter wb = FrameWriter.start(List.of("/bin/sh", "-c", "cat > '" + big + "'"));
            for (int i = 0; i < 50; i++) wb.submit(new byte[200_000]);
            check(wb.finish() == 0 && Files.size(big) == 50L * 200_000, "escritor: 50 quadros de 200 KB passam pelo cano (contrapressão)");

            FrameWriter bad = FrameWriter.start(List.of("/bin/sh", "-c", "echo falhou feio >&2; exit 3"));
            IOException caught = null;
            try { for (int i = 0; i < 2000; i++) bad.submit(new byte[100_000]); } catch (IOException e) { caught = e; }
            check(caught != null, "escritor: detecta que o FFmpeg morreu");
            check(bad.error() != null && bad.stderrTail().contains("falhou feio") && caught.getMessage().contains("falhou feio"), "escritor: a mensagem de erro do FFmpeg chega ao usuário");
            check(bad.finish() == 3, "escritor: devolve o código de saída real (3)");

            FrameWriter err = FrameWriter.start(List.of("/bin/sh", "-c", "echo aviso >&2; cat > /dev/null"));
            err.submit(new byte[10]);
            err.finish();
            check(err.stderrTail().contains("aviso"), "escritor: guarda o que o FFmpeg escreveu no stderr");

            FrameWriter slow = FrameWriter.start(List.of("/bin/sh", "-c", "sleep 30"));
            long t0 = System.nanoTime();
            slow.abort();
            slow.finish();
            check((System.nanoTime() - t0) / 1_000_000 < 5000, "cancelar mata o processo na hora (sem esperar os 30 s)");
        }
        // ---- simulação do laço de renderização completo ----
        if (Files.exists(Path.of("/bin/sh"))) {
            Timeline t = new Timeline();
            t.addSpeed(0, 1.0);
            t.addSpeed(4000, 2.0);
            long a = 1000, b = 4000;
            RenderPlan plan = new RenderPlan(a, b, 24, t.speed());
            // Relógio de reprodução simples (o mod não grava mais replays): começa em 5000 ms.
            double[] clock = { 5000.0 };
            Path out = tmp.resolve("sim.bin");
            FrameWriter fw = FrameWriter.start(List.of("/bin/sh", "-c", "cat > '" + out + "'"));
            double ticks = 0;
            long lastTime = -1;
            boolean monotonic = true;
            for (long i = 0; i < plan.totalFrames(); i++) {
                if (i > 0) { clock[0] += plan.replayAdvanceMs(i); ticks += plan.tickAdvance(i); }
                long ot = plan.outputTimeMs(i);
                monotonic &= ot >= lastTime;
                lastTime = ot;
                byte[] frame = new byte[12];
                Arrays.fill(frame, (byte) i);
                fw.submit(frame);
            }
            check(fw.finish() == 0, "simulação: o 'FFmpeg' terminou bem");
            check(Files.size(out) == plan.totalFrames() * 12 && fw.framesWritten() == plan.totalFrames(), "simulação: chegaram exatamente " + plan.totalFrames() + " quadros (nem a mais, nem a menos)");
            double replayPassed = t.speed().replayTimeAt(b) - t.speed().replayTimeAt(a);
            check(Math.abs((clock[0] - 5000) - replayPassed) < 1e-6, "simulação: o replay percorreu exatamente " + (long) replayPassed + " ms (integral da trilha de velocidade)");
            check(Math.abs(ticks * 50.0 - replayPassed) < 1e-6, "simulação: ticks do jogo x 50 ms = tempo de replay percorrido (mundo e câmera em sincronia)");
            check(monotonic && plan.outputTimeMs(0) == a && plan.outputTimeMs(plan.totalFrames() - 1) == b, "simulação: o trajeto vai de ponta a ponta sem voltar");
            byte[] written = Files.readAllBytes(out);
            boolean ordered = true;
            for (int i = 0; i < plan.totalFrames(); i++) ordered &= written[i * 12] == (byte) i;
            check(ordered, "simulação: os quadros chegam na ordem certa");
        }

        boolean noExe = false;
        try { FrameWriter.start(List.of("/nao/existe/ffmpeg")); } catch (IOException e) { noExe = true; }
        check(noExe, "iniciar um executável inexistente dá IOException");

        System.out.println("\n" + passed + " verificações da fase 5 passaram.");
    }
}
