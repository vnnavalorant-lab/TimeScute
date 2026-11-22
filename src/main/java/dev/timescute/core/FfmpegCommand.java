package dev.timescute.core;

import java.util.ArrayList;
import java.util.List;

/** Monta a linha de comando do FFmpeg (lista de argumentos, sem shell: nomes com espaço ou aspas são seguros). */
public final class FfmpegCommand {
    private FfmpegCommand() { }

    /** Tamanho final do vídeo. targetHeight 0 = mantém; sempre par (exigência do H.264). */
    public static int[] outputSize(int srcW, int srcH, int targetHeight) {
        if (targetHeight <= 0 || targetHeight == srcH) return new int[]{srcW & ~1, srcH & ~1};
        int w = (int) Math.round(srcW * (double) targetHeight / srcH);
        if (w % 2 != 0) w = (int) Math.round(srcW * (double) targetHeight / srcH / 2.0) * 2;
        return new int[]{Math.max(2, w), targetHeight & ~1};
    }

    /**
     * @param exe        caminho do ffmpeg
     * @param srcW,srcH  tamanho dos quadros que serão enviados (RGB24, de baixo para cima, como o OpenGL entrega)
     * @param targetHeight altura final (0 = igual à origem)
     */
    public static List<String> build(String exe, int srcW, int srcH, int fps, int targetHeight, RenderPreset preset, String outputFile) {
        List<String> c = new ArrayList<>();
        c.add(exe);
        c.add("-y");
        c.add("-hide_banner");
        c.add("-loglevel"); c.add("error");
        c.add("-f"); c.add("rawvideo");
        c.add("-pix_fmt"); c.add("rgb24");
        c.add("-s"); c.add(srcW + "x" + srcH);
        c.add("-r"); c.add(Integer.toString(fps));
        c.add("-i"); c.add("-");

        StringBuilder vf = new StringBuilder("vflip"); // o OpenGL entrega a imagem de cabeça para baixo
        int[] out = outputSize(srcW, srcH, targetHeight);
        if (targetHeight > 0 && targetHeight != srcH) vf.append(",scale=-2:").append(out[1]).append(":flags=lanczos");
        else if (out[0] != srcW || out[1] != srcH) vf.append(",scale=").append(out[0]).append(':').append(out[1]);
        c.add("-vf"); c.add(vf.toString());

        c.addAll(preset.encoderArgs());
        c.add(outputFile);
        return c;
    }
}
