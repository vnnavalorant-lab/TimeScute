package dev.timescute.core;

import java.util.*;

/** Lê arquivos .animation.json (formato Bedrock, o que o Blockbench exporta e o que os add-ons usam). */
public final class BedrockAnimations {
    private BedrockAnimations() { }

    public record Result(List<AnimClip> clips, List<String> warnings) { }

    /** @throws IllegalArgumentException se o arquivo não for uma animação válida */
    @SuppressWarnings("unchecked")
    public static Result parse(String text) {
        Object root = Json.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("O arquivo não é uma animação do Blockbench.");
        Object anims = ((Map<String, Object>) root).get("animations");
        if (!(anims instanceof Map<?, ?> am) || am.isEmpty()) throw new IllegalArgumentException("O arquivo não tem nenhuma animação (campo \"animations\").");

        List<AnimClip> clips = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<?, ?> e : am.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map)) { warnings.add("\"" + name + "\" ignorada (formato inesperado)."); continue; }
            try {
                clips.add(parseClip(name, (Map<String, Object>) e.getValue(), warnings));
            } catch (IllegalArgumentException ex) {
                warnings.add("\"" + name + "\" ignorada: " + ex.getMessage());
            }
        }
        if (clips.isEmpty()) throw new IllegalArgumentException("Nenhuma animação do arquivo pôde ser lida. " + String.join(" ", warnings));
        return new Result(clips, warnings);
    }

    @SuppressWarnings("unchecked")
    private static AnimClip parseClip(String name, Map<String, Object> m, List<String> warnings) {
        AnimClip.Loop loop = AnimClip.Loop.ONCE;
        Object lp = m.get("loop");
        if (Boolean.TRUE.equals(lp)) loop = AnimClip.Loop.LOOP;
        else if ("hold_on_last_frame".equals(lp)) loop = AnimClip.Loop.HOLD;
        double length = m.get("animation_length") instanceof Double d ? d : 0;

        Map<String, AnimClip.BoneTrack> bones = new LinkedHashMap<>();
        if (m.get("bones") instanceof Map<?, ?> bm) {
            for (Map.Entry<?, ?> be : bm.entrySet()) {
                String bone = String.valueOf(be.getKey());
                if (!(be.getValue() instanceof Map<?, ?> chans)) continue;
                bones.put(bone, new AnimClip.BoneTrack(bone,
                        channel(chans.get("rotation"), new double[]{0, 0, 0}, name + "/" + bone + "/rotation"),
                        channel(chans.get("position"), new double[]{0, 0, 0}, name + "/" + bone + "/position"),
                        channel(chans.get("scale"), new double[]{1, 1, 1}, name + "/" + bone + "/scale")));
            }
        }
        AnimClip clip = new AnimClip(name, length, loop, bones, false);
        List<String> unmapped = clip.unmappedBones();
        if (!unmapped.isEmpty()) warnings.add("\"" + shortName(name) + "\" usa ossos que o jogador não tem e que serão ignorados: " + String.join(", ", unmapped) + ".");
        return clip;
    }

    public static String shortName(String name) { return name.startsWith("animation.") ? name.substring(10) : name; }

    // ---- canais ----

    private static AnimClip.Channel channel(Object v, double[] fallback, String where) {
        if (v == null) return new AnimClip.Channel(List.of(), fallback);
        if (!(v instanceof Map<?, ?> km)) {
            Molang.Expr[] e = vec(v, where);
            return new AnimClip.Channel(List.of(new AnimClip.Key(0, e, e, false, false)), fallback); // valor constante
        }
        List<AnimClip.Key> keys = new ArrayList<>();
        for (Map.Entry<?, ?> ke : km.entrySet()) {
            double time;
            try { time = Double.parseDouble(String.valueOf(ke.getKey())); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("tempo de keyframe inválido \"" + ke.getKey() + "\" em " + where); }
            Object kv = ke.getValue();
            Molang.Expr[] pre, post;
            boolean smooth = false, step = false;
            if (kv instanceof Map<?, ?> o && (o.containsKey("post") || o.containsKey("pre"))) {
                post = o.containsKey("post") ? vec(o.get("post"), where) : vec(o.get("pre"), where);
                pre = o.containsKey("pre") ? vec(o.get("pre"), where) : post;
                String lm = String.valueOf(o.get("lerp_mode"));
                smooth = lm.equals("catmullrom");
                step = lm.equals("step");
            } else {
                post = vec(kv, where);
                pre = post;
            }
            keys.add(new AnimClip.Key(time, pre, post, smooth, step));
        }
        return new AnimClip.Channel(keys, fallback);
    }

    /** [x,y,z], um número (vale para os três) ou uma expressão. */
    private static Molang.Expr[] vec(Object v, String where) {
        if (v instanceof List<?> l) {
            if (l.size() != 3) throw new IllegalArgumentException("vetor com " + l.size() + " valores em " + where + " (esperado 3)");
            return new Molang.Expr[]{expr(l.get(0), where), expr(l.get(1), where), expr(l.get(2), where)};
        }
        Molang.Expr e = expr(v, where);
        return new Molang.Expr[]{e, e, e};
    }

    private static Molang.Expr expr(Object v, String where) {
        if (v instanceof Double d) return Molang.constant(Double.isNaN(d) ? 0 : d);
        if (v instanceof String s) {
            try { return Molang.parse(s); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(e.getMessage() + " em " + where); }
        }
        throw new IllegalArgumentException("valor inesperado em " + where);
    }
}
