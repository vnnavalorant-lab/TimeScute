package dev.timescute.core;

import java.util.*;

/** Lê arquivos {@code .geo.json} do Blockbench (Bedrock 1.12+ e o formato antigo "geometry.nome"). */
public final class BedrockGeometry {
    private BedrockGeometry() { }

    public static final int MAX_BONES = 256, MAX_CUBES = 4096;

    public record Result(List<GeoModel> models, List<String> warnings) { }

    public static Result parse(String text) {
        List<String> warnings = new ArrayList<>();
        List<GeoModel> models = new ArrayList<>();
        Object root;
        try { root = Json.parse(text); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("JSON inválido: " + e.getMessage()); }
        if (!(root instanceof Map<?, ?> m)) throw new IllegalArgumentException("O arquivo não é um modelo do Blockbench.");

        Object geos = m.get("minecraft:geometry");
        if (geos instanceof List<?> list) {
            for (Object g : list) {
                if (!(g instanceof Map<?, ?> gm)) continue;
                Object desc = gm.get("description");
                String id = "modelo";
                int tw = 64, th = 64;
                if (desc instanceof Map<?, ?> dm) {
                    if (dm.get("identifier") instanceof String s) id = s;
                    tw = (int) num(dm.get("texture_width"), 64);
                    th = (int) num(dm.get("texture_height"), 64);
                }
                add(models, warnings, id, tw, th, gm.get("bones"));
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && k.startsWith("geometry.") && e.getValue() instanceof Map<?, ?> gm) {
                    int colon = k.indexOf(':'); // "geometry.a:geometry.b" herda de b: ignoramos a herança
                    add(models, warnings, colon > 0 ? k.substring(0, colon) : k,
                            (int) num(gm.get("texturewidth"), 64), (int) num(gm.get("textureheight"), 64), gm.get("bones"));
                }
            }
        }
        if (models.isEmpty()) throw new IllegalArgumentException("Nenhuma geometria encontrada (esperado um modelo Bedrock exportado do Blockbench).");
        return new Result(models, warnings);
    }

    private static void add(List<GeoModel> out, List<String> warnings, String id, int tw, int th, Object rawBones) {
        if (!(rawBones instanceof List<?> bl)) { warnings.add(id + ": sem ossos."); return; }
        if (bl.size() > MAX_BONES) throw new IllegalArgumentException("Ossos demais no modelo (máximo " + MAX_BONES + ").");
        List<GeoModel.Bone> bones = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int cubes = 0;
        for (Object bo : bl) {
            if (!(bo instanceof Map<?, ?> bm) || !(bm.get("name") instanceof String name) || name.isEmpty()) continue;
            if (!names.add(name)) { warnings.add(id + ": osso repetido \"" + name + "\" ignorado."); continue; }
            List<GeoModel.Cube> cs = new ArrayList<>();
            if (bm.get("cubes") instanceof List<?> cl) {
                for (Object co : cl) {
                    if (!(co instanceof Map<?, ?> cm)) continue;
                    GeoModel.Cube c = cube(cm, warnings, id + "/" + name);
                    if (c != null) cs.add(c);
                }
            }
            cubes += cs.size();
            if (cubes > MAX_CUBES) throw new IllegalArgumentException("Cubos demais no modelo (máximo " + MAX_CUBES + ").");
            bones.add(new GeoModel.Bone(name, bm.get("parent") instanceof String p && !p.isEmpty() ? p : null,
                    vec(bm.get("pivot"), 0), vec(bm.get("rotation"), 0), Boolean.TRUE.equals(bm.get("mirror")), cs));
        }
        // pai inexistente ou ciclo: vira osso raiz (e avisa), para nunca travar o desenho
        List<GeoModel.Bone> fixed = new ArrayList<>();
        Map<String, GeoModel.Bone> byName = new HashMap<>();
        for (GeoModel.Bone b : bones) byName.put(b.name(), b);
        for (GeoModel.Bone b : bones) {
            String parent = b.parent();
            if (parent != null) {
                String problem = null;
                if (!byName.containsKey(parent)) {
                    problem = "o pai \"" + parent + "\" não existe";
                } else {
                    String cur = parent;
                    int steps = 0;
                    while (cur != null) {
                        if (cur.equals(b.name()) || ++steps > bones.size()) { problem = "a hierarquia faz um ciclo"; break; }
                        GeoModel.Bone pb = byName.get(cur);
                        cur = pb == null ? null : pb.parent();
                    }
                }
                if (problem != null) {
                    warnings.add(id + ": no osso \"" + b.name() + "\" " + problem + "; tratado como raiz.");
                    b = new GeoModel.Bone(b.name(), null, b.pivot(), b.rotation(), b.mirror(), b.cubes());
                }
            }
            fixed.add(b);
        }
        if (fixed.isEmpty()) { warnings.add(id + ": sem ossos válidos."); return; }
        out.add(new GeoModel(id, tw, th, fixed));
    }

    private static GeoModel.Cube cube(Map<?, ?> c, List<String> warnings, String where) {
        double[] origin = vec3(c.get("origin")), size = vec3(c.get("size"));
        if (origin == null || size == null) { warnings.add(where + ": cubo sem origin/size ignorado."); return null; }
        double[] pivot = c.get("pivot") != null ? vec(c.get("pivot"), 0) : null;
        double[] rot = c.get("rotation") != null ? vec(c.get("rotation"), 0) : null;
        double inflate = num(c.get("inflate"), 0);
        Boolean mirror = c.get("mirror") instanceof Boolean b ? b : null;
        double[] boxUv = null;
        Map<String, double[]> faces = null;
        Object uv = c.get("uv");
        if (uv instanceof List<?> ul && ul.size() >= 2) {
            boxUv = new double[]{num(ul.get(0), 0), num(ul.get(1), 0)};
        } else if (uv instanceof Map<?, ?> um) {
            faces = new LinkedHashMap<>();
            for (String f : GeoModel.FACES) {
                if (!(um.get(f) instanceof Map<?, ?> fm) || !(fm.get("uv") instanceof List<?> fu) || fu.size() < 2) continue;
                double w = 0, h = 0;
                if (fm.get("uv_size") instanceof List<?> fs && fs.size() >= 2) { w = num(fs.get(0), 0); h = num(fs.get(1), 0); }
                faces.put(f, new double[]{num(fu.get(0), 0), num(fu.get(1), 0), w, h});
            }
        } else {
            boxUv = new double[]{0, 0};
        }
        return new GeoModel.Cube(origin, size, pivot, rot, inflate, mirror, boxUv, faces);
    }

    private static double num(Object o, double def) {
        return o instanceof Double d && !d.isNaN() && !d.isInfinite() ? d : def;
    }

    private static double[] vec3(Object o) {
        if (o instanceof List<?> l && l.size() >= 3 && l.get(0) instanceof Double && l.get(1) instanceof Double && l.get(2) instanceof Double)
            return new double[]{num(l.get(0), 0), num(l.get(1), 0), num(l.get(2), 0)};
        return null;
    }

    private static double[] vec(Object o, double def) {
        double[] v = vec3(o);
        return v != null ? v : new double[]{def, def, def};
    }
}
