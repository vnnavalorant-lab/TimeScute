package dev.timescute.core;

import java.util.Locale;

/** Formatação de tempo, tamanho e nomes de arquivo. */
public final class Fmt {
    private Fmt() { }

    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
    }

    /** Nome seguro para arquivo: sem barras, sem nomes ocultos, no máximo 64 caracteres, nunca vazio. */
    public static String fileName(String raw) {
        String n = raw == null ? "" : raw.trim().replaceAll("[^\\p{L}\\p{N} _.\\-]", "_");
        n = n.replaceAll("^[.]+", "").trim();
        if (n.length() > 64) n = n.substring(0, 64).trim();
        return n.isEmpty() ? "video" : n;
    }
}
