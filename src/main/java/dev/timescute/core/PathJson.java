package dev.timescute.core;

import java.util.*;

/** Formato de arquivo dos trajetos (.json). */
public final class PathJson {
    public static final String FORMAT = "timescute-path";
    /** Nome do formato quando o mod ainda se chamava Kinema; ainda é aceito na leitura. */
    private static final String LEGACY_FORMAT = "kinema-path";
    public static final int VERSION = 2;

    public record Data(List<Keyframe> keys, List<SpeedKey> speeds, List<Actor> actors) { }

    private static final int MAX_ACTORS = 200, MAX_REACTIONS = 5000, MAX_FRAMES = 2_000_000;

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
        b.append(ss.isEmpty() ? "]" : "\n  ]");
        writeActors(b, t.actors().all());
        b.append("\n}\n");
        return b.toString();
    }

    private static String r(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        double f = Math.pow(10, decimals);
        return Double.toString(Math.round(v * f) / f);
    }

    private static void writeActors(StringBuilder b, List<Actor> actors) {
        b.append(",\n  \"actors\": [");
        for (int ai = 0; ai < actors.size(); ai++) {
            Actor a = actors.get(ai);
            Map<String, Integer> items = new LinkedHashMap<>();
            items.put("", 0);
            for (ActorFrame f : a.performance().frames()) items.putIfAbsent(f.item(), items.size());

            b.append(ai == 0 ? "\n" : ",\n").append("    {\"id\": ").append(a.id())
                    .append(", \"name\": ").append(Json.quote(a.name()))
                    .append(", \"skin\": ").append(Json.quote(a.skin()))
                    .append(", \"startMs\": ").append(a.startMs())
                    .append(", \"model\": ").append(Json.quote(a.model()))
                    .append(a.origin() == null ? "" : ", \"origin\": [" + r(a.origin().x(), 3) + "," + r(a.origin().y(), 3) + "," + r(a.origin().z(), 3) + "," + r(a.origin().yaw(), 2) + "]")
                    .append(", \"muted\": ").append(a.muted()).append(",\n      \"items\": [");
            int n = 0;
            for (String it : items.keySet()) b.append(n++ == 0 ? "" : ", ").append(Json.quote(it));
            b.append("],\n      \"frames\": [");
            List<ActorFrame> fr = a.performance().frames();
            for (int i = 0; i < fr.size(); i++) {
                ActorFrame f = fr.get(i);
                b.append(i == 0 ? "\n        " : ",\n        ").append('[').append(f.timeMs())
                        .append(',').append(r(f.x(), 3)).append(',').append(r(f.y(), 3)).append(',').append(r(f.z(), 3))
                        .append(',').append(r(f.yaw(), 2)).append(',').append(r(f.pitch(), 2))
                        .append(',').append(r(f.headYaw(), 2)).append(',').append(r(f.bodyYaw(), 2))
                        .append(',').append(f.flags()).append(',').append(f.slot()).append(',').append(items.get(f.item())).append(']');
            }
            b.append(fr.isEmpty() ? "]" : "\n      ]").append(",\n      \"reactions\": [");
            List<Reaction> rs = a.reactions();
            for (int i = 0; i < rs.size(); i++) {
                Reaction x = rs.get(i);
                b.append(i == 0 ? "\n        " : ",\n        ").append("{\"id\": ").append(x.id())
                        .append(", \"t\": ").append(x.startMs()).append(", \"d\": ").append(x.durationMs())
                        .append(", \"type\": ").append(Json.quote(x.type().name()))
                        .append(", \"clip\": ").append(Json.quote(x.anim().clip()))
                        .append(", \"parts\": ").append(x.anim().partMask())
                        .append(", \"speed\": ").append(num(x.anim().speed())).append(", \"weight\": ").append(num(x.anim().weight()))
                        .append(", \"additive\": ").append(x.anim().additive()).append(", \"mirror\": ").append(x.anim().mirror())
                        .append(", \"fit\": ").append(x.anim().fit())
                        .append(", \"item\": ").append(Json.quote(x.itemId()))
                        .append(", \"off\": ").append(x.offHand()).append(", \"count\": ").append(x.count())
                        .append(", \"x\": ").append(num(x.tx())).append(", \"y\": ").append(num(x.ty())).append(", \"z\": ").append(num(x.tz()))
                        .append(", \"entity\": ").append(Json.quote(x.targetEntity()))
                        .append(", \"label\": ").append(Json.quote(x.label())).append("}");
            }
            b.append(rs.isEmpty() ? "]" : "\n      ]").append("}");
        }
        b.append(actors.isEmpty() ? "]" : "\n  ]");
    }

    @SuppressWarnings("unchecked")
    private static List<Actor> readActors(Map<String, Object> m, Set<Long> usedIds) {
        List<Actor> out = new ArrayList<>();
        if (!m.containsKey("actors")) return out;
        List<Object> list = list(m.get("actors"));
        if (list.size() > MAX_ACTORS) throw new IllegalArgumentException("Atores demais no arquivo.");
        for (Object ao : list) {
            Map<String, Object> a = obj(ao);
            long id = (long) d(a.get("id"), "id");
            if (!usedIds.add(id)) throw new IllegalArgumentException("Ids repetidos no arquivo.");

            List<String> items = new ArrayList<>();
            for (Object o : list(a.get("items"))) items.add(o instanceof String str ? str : "");
            if (items.isEmpty()) items.add("");

            List<Object> rawFrames = list(a.get("frames"));
            if (rawFrames.size() > MAX_FRAMES) throw new IllegalArgumentException("Quadros demais na performance.");
            List<ActorFrame> frames = new ArrayList<>(rawFrames.size());
            for (Object fo : rawFrames) {
                List<Object> f = list(fo);
                if (f.size() != 11) throw new IllegalArgumentException("Quadro da performance inválido.");
                long t = (long) d(f.get(0), "t");
                if (t < 0) throw new IllegalArgumentException("Quadro com tempo negativo.");
                int idx = (int) d(f.get(10), "item");
                if (idx < 0 || idx >= items.size()) throw new IllegalArgumentException("Quadro aponta para um item inexistente.");
                frames.add(new ActorFrame(t, d(f.get(1), "x"), d(f.get(2), "y"), d(f.get(3), "z"),
                        (float) d(f.get(4), "yaw"), (float) d(f.get(5), "pitch"), (float) d(f.get(6), "head"), (float) d(f.get(7), "body"),
                        (int) d(f.get(8), "flags"), (int) d(f.get(9), "slot"), items.get(idx)));
            }

            List<Object> rawReactions = a.containsKey("reactions") ? list(a.get("reactions")) : List.of();
            if (rawReactions.size() > MAX_REACTIONS) throw new IllegalArgumentException("Reações demais para um ator.");
            List<Reaction> reactions = new ArrayList<>();
            for (Object ro : rawReactions) {
                Map<String, Object> r = obj(ro);
                long rid = (long) d(r.get("id"), "id");
                if (!usedIds.add(rid)) throw new IllegalArgumentException("Ids repetidos no arquivo.");
                long t = (long) d(r.get("t"), "t");
                if (t < 0) throw new IllegalArgumentException("Reação com tempo negativo.");
                ReactionType type = ReactionType.ANIMATION;
                if (r.get("type") instanceof String ts) { try { type = ReactionType.valueOf(ts); } catch (IllegalArgumentException ignored) { /* padrão */ } }
                AnimParams anim = new AnimParams(r.get("clip") instanceof String cs ? cs : "", (int) opt(r.get("parts"), Part.ALL),
                        opt(r.get("speed"), 1), opt(r.get("weight"), 1), Boolean.TRUE.equals(r.get("additive")),
                        Boolean.TRUE.equals(r.get("mirror")), Boolean.TRUE.equals(r.get("fit")));
                reactions.add(new Reaction(rid, t, (long) d(r.get("d"), "d"), type, anim,
                        r.get("item") instanceof String is ? is : "", Boolean.TRUE.equals(r.get("off")),
                        (int) opt(r.get("count"), 1), opt(r.get("x"), 0), opt(r.get("y"), 0), opt(r.get("z"), 0),
                        r.get("entity") instanceof String es ? es : "", r.get("label") instanceof String ls ? ls : ""));
            }
            Actor.Origin origin = null;
            if (a.get("origin") instanceof List<?> ol && ol.size() == 4 && ol.get(0) instanceof Double ox && ol.get(1) instanceof Double oy
                    && ol.get(2) instanceof Double oz && ol.get(3) instanceof Double oyaw && !ox.isNaN() && !oy.isNaN() && !oz.isNaN() && !oyaw.isNaN())
                origin = new Actor.Origin(ox, oy, oz, oyaw.floatValue());
            out.add(new Actor(id, a.get("name") instanceof String n ? n : "Ator", a.get("skin") instanceof String sk ? sk : "",
                    (long) opt(a.get("startMs"), 0), Performance.of(frames), reactions, Boolean.TRUE.equals(a.get("muted")),
                    a.get("model") instanceof String ms ? ms : "", origin));
        }
        return out;
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
        return new Data(keys, speeds, readActors(m, usedIds));
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
