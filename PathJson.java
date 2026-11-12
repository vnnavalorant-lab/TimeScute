package dev.timescute.core;

import java.util.*;

/** Formato de arquivo dos trajetos (.json). */
public final class PathJson {
    public static final String FORMAT = "timescute-path";
    /** Nome do formato quando o mod ainda se chamava Kinema; ainda é aceito na leitura. */
    private static final String LEGACY_FORMAT = "kinema-path";
    public static final int VERSION = 1;

    public record Data(List<Keyframe> keys, List<SpeedKey> speeds) { }

    private PathJson() { }

    public static String write(Timeline t) {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"format\": ").append(Json.quote(FORMAT)).append(",\n  \"version\": ").append(VERSION);
        b.append(",\n  \"keyframes\": [");
        List<Keyframe> ks = t.path().all();
        for (int i = 0; i < ks.size(); i++) {
            Keyframe k = ks.get(i);
            CameraState s = k.state();
            b.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(k.id())
                    .append(", \"t\": ").append(k.timeMs())
                    .append(", \"x\": ").append(num(s.x())).append(", \"y\": ").append(num(s.y())).append(", \"z\": ").append(num(s.z()))
                    .append(", \"yaw\": ").append(num(s.yaw())).append(", \"pitch\": ").append(num(s.pitch()))
                    .append(", \"roll\": ").append(num(s.roll())).append(", \"fov\": ").append(num(s.fov()))
                    .append(", \"ease\": ").append(Json.quote(k.easing().name())).append("}");
        }
        b.append(ks.isEmpty() ? "]" : "\n  ]");
        b.append(",\n  \"speed\": [");
        List<SpeedKey> ss = t.speed().all();
        for (int i = 0; i < ss.size(); i++) {
            SpeedKey k = ss.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(k.id())
                    .append(", \"t\": ").append(k.timeMs()).append(", \"v\": ").append(num(k.speed())).append("}");
        }
        b.append(ss.isEmpty() ? "]" : "\n  ]").append("\n}\n");
        return b.toString();
    }

    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        return Double.toString(d);
    }

    @SuppressWarnings("unchecked")
    public static Data read(String text) {
        Object root = Json.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("O arquivo não é um trajeto do TimeScute.");
        Map<String, Object> m = (Map<String, Object>) root;
        if (!FORMAT.equals(m.get("format")) && !LEGACY_FORMAT.equals(m.get("format"))) throw new IllegalArgumentException("O arquivo não é um trajeto do TimeScute.");
        double ver = d(m.get("version"), "version");
        if (ver > VERSION) throw new IllegalArgumentException("Trajeto salvo por uma versão mais nova do TimeScute.");

        Set<Long> usedIds = new HashSet<>();
        List<Keyframe> keys = new ArrayList<>();
        for (Object o : list(m.get("keyframes"))) {
            Map<String, Object> k = obj(o);
            long id = (long) d(k.get("id"), "id");
            long t = (long) d(k.get("t"), "t");
            if (t < 0) throw new IllegalArgumentException("Keyframe com tempo negativo.");
            if (!usedIds.add(id)) throw new IllegalArgumentException("Ids repetidos no arquivo.");
            CameraState s = new CameraState(d(k.get("x"), "x"), d(k.get("y"), "y"), d(k.get("z"), "z"),
                    d(k.get("yaw"), "yaw"), d(k.get("pitch"), "pitch"), opt(k.get("roll"), 0), opt(k.get("fov"), 70));
            Easing e = Easing.SMOOTH;
            if (k.get("ease") instanceof String name) {
                try { e = Easing.valueOf(name); } catch (IllegalArgumentException ignored) { /* mantém SMOOTH */ }
            }
            keys.add(new Keyframe(id, t, s, e));
        }
        keys.sort(Comparator.comparingLong(Keyframe::timeMs));
        for (int i = 1; i < keys.size(); i++)
            if (keys.get(i).timeMs() - keys.get(i - 1).timeMs() < Timeline.MIN_GAP_MS)
                throw new IllegalArgumentException("Keyframes grudados demais no arquivo.");

        List<SpeedKey> speeds = new ArrayList<>();
        if (m.containsKey("speed")) {
            for (Object o : list(m.get("speed"))) {
                Map<String, Object> k = obj(o);
                long id = (long) d(k.get("id"), "id");
                long t = (long) d(k.get("t"), "t");
                if (t < 0) throw new IllegalArgumentException("Ponto de velocidade com tempo negativo.");
                if (!usedIds.add(id)) throw new IllegalArgumentException("Ids repetidos no arquivo.");
                speeds.add(new SpeedKey(id, t, SpeedTrack.clampSpeed(d(k.get("v"), "v"))));
            }
            speeds.sort(Comparator.comparingLong(SpeedKey::timeMs));
        }
        return new Data(keys, speeds);
    }

    private static double d(Object o, String name) {
        if (o instanceof Double v && !v.isNaN() && !v.isInfinite()) return v;
        throw new IllegalArgumentException("Campo '" + name + "' ausente ou inválido.");
    }

    private static double opt(Object o, double def) { return o instanceof Double v && !v.isNaN() ? v : def; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalArgumentException("Lista esperada no arquivo.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("Objeto esperado no arquivo.");
    }
}
