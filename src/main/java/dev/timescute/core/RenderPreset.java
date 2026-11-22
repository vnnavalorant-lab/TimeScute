package dev.timescute.core;

import java.util.ArrayList;
import java.util.List;

/** Qualidades de vídeo prontas. Cada uma sabe seus argumentos de codificação do FFmpeg. */
public enum RenderPreset {
    RASCUNHO("rascunho", "Rascunho", "Rápido e leve, para testar o enquadramento", "mp4", "libx264", "veryfast", 28, "yuv420p"),
    PADRAO("padrao", "Padrão", "Boa qualidade para compartilhar (H.264)", "mp4", "libx264", "medium", 20, "yuv420p"),
    ALTA("alta", "Alta qualidade", "Mais lento e maior; ideal para o vídeo final", "mp4", "libx264", "slow", 16, "yuv420p"),
    SEM_PERDAS("sem-perdas", "Sem perdas (edição)", "Arquivo enorme, sem perda nenhuma, para editar depois (FFV1, .mkv)", "mkv", "ffv1", "", 0, "bgr0");

    public final String id, label, description, extension, codec, speed, pixelFormat;
    public final int crf;

    RenderPreset(String id, String label, String description, String ext, String codec, String speed, int crf, String pix) {
        this.id = id; this.label = label; this.description = description;
        this.extension = ext; this.codec = codec; this.speed = speed; this.crf = crf; this.pixelFormat = pix;
    }

    public static RenderPreset byId(String id) {
        for (RenderPreset p : values()) if (p.id.equals(id)) return p;
        return PADRAO;
    }

    public RenderPreset next() { return values()[(ordinal() + 1) % values().length]; }

    /** Argumentos do codificador (sem entrada/saída). */
    List<String> encoderArgs() {
        List<String> a = new ArrayList<>();
        a.add("-c:v"); a.add(codec);
        if (codec.equals("libx264")) {
            a.add("-preset"); a.add(speed);
            a.add("-crf"); a.add(Integer.toString(crf));
            a.add("-pix_fmt"); a.add(pixelFormat);
            a.add("-movflags"); a.add("+faststart");
        } else { // ffv1: sem perdas, cada quadro independente
            a.add("-level"); a.add("3");
            a.add("-g"); a.add("1");
            a.add("-pix_fmt"); a.add(pixelFormat);
        }
        return a;
    }
}
