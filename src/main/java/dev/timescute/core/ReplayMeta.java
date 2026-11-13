package dev.timescute.core;

import java.util.*;

/** Informações gerais do replay (guardadas como JSON dentro do arquivo). */
public final class ReplayMeta {
    public static final String FORMAT = "timescute-replay";
    public static final int MAX_MARKERS = 2000;

    public record Marker(long timeMs, String text) { }

    public String mcVersion = "", modVersion = "", server = "", serverName = "", world = "";
    public String playerName = "", playerUuid = "", stopReason = "";
    public int protocol;
    public boolean singleplayer;
    public long startedAtMs, durationMs, packets, selfSamples, markers;
    public final List<Marker> markerList = new ArrayList<>();

    /** Nome para mostrar: servidor ou mundo. */
    public String where() {
        if (singleplayer) return world.isEmpty() ? "Um jogador" : world;
        if (!serverName.isEmpty()) return serverName;
        return server.isEmpty() ? "?" : server;
    }

    /** Primeiro marcador depois de {@code timeMs}. */
    public OptionalLong nextMarkerAfter(long timeMs) {
        long best = Long.MAX_VALUE;
        for (Marker m : markerList) if (m.timeMs() > timeMs + 1 && m.timeMs() < best) best = m.timeMs();
        return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
    }

    /** Último marcador antes de {@code timeMs - 1s} (a folga evita "voltar" para o marcador em que acabamos de chegar). */
    public OptionalLong previousMarkerBefore(long timeMs) {
        long best = -1;
        for (Marker m : markerList) if (m.timeMs() < timeMs - 1000 && m.timeMs() > best) best = m.timeMs();
        return best < 0 ? OptionalLong.empty() : OptionalLong.of(best);
    }

    public String toJson() {
        StringBuilder b = new StringBuilder("{\n");
        str(b, "format", FORMAT); b.append(",\n");
        num(b, "version", ReplayFormat.VERSION); b.append(",\n");
        str(b, "mcVersion", mcVersion); b.append(",\n");
        num(b, "protocol", protocol); b.append(",\n");
        str(b, "modVersion", modVersion); b.append(",\n");
        num(b, "startedAtMs", startedAtMs); b.append(",\n");
        num(b, "durationMs", durationMs); b.append(",\n");
        str(b, "server", server); b.append(",\n");
        str(b, "serverName", serverName); b.append(",\n");
        str(b, "world", world); b.append(",\n");
        b.append("  \"singleplayer\": ").append(singleplayer).append(",\n");
        str(b, "playerName", playerName); b.append(",\n");
        str(b, "playerUuid", playerUuid); b.append(",\n");
        num(b, "packets", packets); b.append(",\n");
        num(b, "selfSamples", selfSamples); b.append(",\n");
        num(b, "markers", markers); b.append(",\n");
        str(b, "stopReason", stopReason); b.append(",\n");
        b.append("  \"markerList\": [");
        int n = Math.min(markerList.size(), MAX_MARKERS);
        for (int i = 0; i < n; i++) {
            Marker m = markerList.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"t\": ").append(m.timeMs()).append(", \"text\": ")
                    .append(Json.quote(m.text())).append("}");
        }
        b.append(n == 0 ? "]" : "\n  ]").append("\n}");
        return b.toString();
    }

    private static void str(StringBuilder b, String k, String v) {
        b.append("  ").append(Json.quote(k)).append(": ").append(Json.quote(v == null ? "" : v));
    }

    private static void num(StringBuilder b, String k, long v) {
        b.append("  ").append(Json.quote(k)).append(": ").append(v);
    }

    @SuppressWarnings("unchecked")
    public static ReplayMeta fromJson(String text) {
        Object root = Json.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Metadados do replay inválidos.");
        Map<String, Object> m = (Map<String, Object>) root;
        if (!FORMAT.equals(m.get("format"))) throw new IllegalArgumentException("Metadados do replay inválidos.");
        ReplayMeta r = new ReplayMeta();
        r.mcVersion = s(m, "mcVersion");
        r.modVersion = s(m, "modVersion");
        r.server = s(m, "server");
        r.serverName = s(m, "serverName");
        r.world = s(m, "world");
        r.playerName = s(m, "playerName");
        r.playerUuid = s(m, "playerUuid");
        r.stopReason = s(m, "stopReason");
        r.protocol = (int) n(m, "protocol");
        r.singleplayer = Boolean.TRUE.equals(m.get("singleplayer"));
        r.startedAtMs = n(m, "startedAtMs");
        r.durationMs = n(m, "durationMs");
        r.packets = n(m, "packets");
        r.selfSamples = n(m, "selfSamples");
        r.markers = n(m, "markers");
        if (m.get("markerList") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> mm && mm.get("t") instanceof Double t && mm.get("text") instanceof String x
                        && r.markerList.size() < MAX_MARKERS)
                    r.markerList.add(new Marker(t.longValue(), x));
            }
        }
        return r;
    }

    private static String s(Map<String, Object> m, String k) { return m.get(k) instanceof String v ? v : ""; }
    private static long n(Map<String, Object> m, String k) { return m.get(k) instanceof Double v ? v.longValue() : 0; }
}
