package dev.timescute.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Acha o executável do FFmpeg. O TimeScute não embute o FFmpeg: usa o que estiver instalado. */
public final class FfmpegTool {
    public record Found(String path, String version) { }

    private FfmpegTool() { }

    /** Roda "{exe} -version". Devolve a primeira linha se funcionou. */
    public static Optional<String> probe(String exe) {
        if (exe == null || exe.isBlank()) return Optional.empty();
        Process p = null;
        try {
            p = new ProcessBuilder(exe, "-version").redirectErrorStream(true).start();
            String first;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                first = r.readLine();
                while (r.readLine() != null) { /* esvazia a saída */ }
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return Optional.empty(); }
            return p.exitValue() == 0 && first != null && !first.isBlank() ? Optional.of(first.trim()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    /** Tenta o caminho configurado, depois o PATH e alguns lugares comuns. */
    public static Optional<Found> locate(String configured) {
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(configured.trim());
        candidates.add("ffmpeg");
        candidates.add("/usr/bin/ffmpeg");
        candidates.add("/usr/local/bin/ffmpeg");
        candidates.add("/opt/homebrew/bin/ffmpeg");
        candidates.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
        for (String c : candidates) {
            Optional<String> v = probe(c);
            if (v.isPresent()) return Optional.of(new Found(c, v.get()));
        }
        return Optional.empty();
    }
}
