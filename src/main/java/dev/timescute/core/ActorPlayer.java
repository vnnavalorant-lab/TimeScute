package dev.timescute.core;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Calcula, para qualquer instante da timeline, o que o ator está fazendo. Função pura: arrastar a timeline é grátis. */
public final class ActorPlayer {
    private ActorPlayer() { }

    public static ActorPose evaluate(Actor a, long stageMs, AnimLibrary lib) {
        if (a.muted()) return ActorPose.HIDDEN;
        long rel = stageMs - a.startMs();
        ActorFrame f;
        if (a.hasPerformance()) {
            if (rel < 0 || rel > a.performance().durationMs()) return ActorPose.HIDDEN;
            f = a.performance().sampleAt(rel);
        } else if (a.isScripted()) {
            if (rel < 0) return ActorPose.HIDDEN; // roteirizado: fica na cena do começo até o fim
            f = ScriptedMotion.frameAt(a, stageMs);
        } else {
            return ActorPose.HIDDEN;
        }
        String item = f.item();
        Map<Part, List<PartLayer>> layers = new EnumMap<>(Part.class);
        Map<String, List<PartLayer>> boneLayers = new LinkedHashMap<>();
        Reaction look = null;
        double lookW = 0;
        int swings = 0;
        boolean off = false;
        long lastSwingStart = -1;

        for (Reaction r : a.reactions()) {
            if (r.startMs() > stageMs) continue; // ainda não começou
            boolean active = stageMs < r.endMs();
            switch (r.type()) {
                case SWAP_ITEM -> { if (active) item = r.itemId(); }
                case USE_ITEM -> {
                    if (active && !r.itemId().isEmpty()) item = r.itemId();
                    int n = swingsStarted(r, stageMs);
                    swings += n;
                    if (n > 0 && r.startMs() >= lastSwingStart) { lastSwingStart = r.startMs(); off = r.offHand(); }
                }
                case SWING -> {
                    int n = swingsStarted(r, stageMs);
                    swings += n;
                    if (n > 0 && r.startMs() >= lastSwingStart) { lastSwingStart = r.startMs(); off = r.offHand(); }
                }
                case ANIMATION -> {
                    if (active) addAnimation(r, stageMs, lib, layers, boneLayers);
                }
                case LOOK_AT -> {
                    if (active) { look = r; lookW = LookMath.smoothWeight(progress(r, stageMs), 0.15); }
                }
                case WALK, TURN -> { } // já aplicados em ScriptedMotion (movem o ator)
            }
        }
        return new ActorPose(true, f, item, layers, boneLayers, swings, off, look, lookW);
    }

    /** Intensidade sobe e desce suave nas pontas do bloco (fração de cada ponta). */
    static final double FADE = 0.12;

    /** Amostra o clipe no instante e põe uma camada em cada parte afetada. */
    private static void addAnimation(Reaction r, long stageMs, AnimLibrary lib, Map<Part, List<PartLayer>> layers,
                                     Map<String, List<PartLayer>> boneLayers) {
        AnimParams p = r.anim();
        Optional<AnimClip> oc = lib.clip(p.clip());
        if (oc.isEmpty()) return; // clipe ausente: não faz nada (a interface avisa)
        AnimClip clip = oc.get();
        double seconds = (stageMs - r.startMs()) / 1000.0;
        double speed = p.fit() && clip.length() > 0 ? clip.length() / (r.durationMs() / 1000.0) : p.speed();

        Map<String, AnimClip.BoneValue> sample = clip.sample(seconds * speed);
        if (sample.isEmpty()) return; // clipe sem repetição que já acabou: as partes voltam ao normal
        AnimLibrary.Config cfg = lib.config(p.clip());
        double w = p.weight() * LookMath.smoothWeight(progress(r, stageMs), FADE);

        for (Map.Entry<String, AnimClip.BoneValue> e : sample.entrySet()) {
            // modelos do Blockbench usam o osso pelo nome, sem partes nem espelho
            boneLayers.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).add(new PartLayer(cfg.toPose(e.getValue()), w, p.additive()));
            Part part = Part.fromBone(e.getKey());
            if (part == null) continue;
            PartPose pose = cfg.toPose(e.getValue());
            if (p.mirror()) { part = part.mirror(); pose = pose.mirrored(); }
            if (!Part.has(p.partMask(), part)) continue; // só as partes que o usuário escolheu
            layers.computeIfAbsent(part, k -> new java.util.ArrayList<>()).add(new PartLayer(pose, w, p.additive()));
        }
    }

    static double progress(Reaction r, long t) {
        return (double) (t - r.startMs()) / r.durationMs();
    }

    /** Quantas batidas do bloco já começaram em {@code t}: distribuídas igualmente ao longo da duração. */
    static int swingsStarted(Reaction r, long t) {
        if (t < r.startMs()) return 0;
        int c = r.count();
        double interval = (double) r.durationMs() / c;
        return Math.min(c, (int) ((t - r.startMs()) / interval) + 1);
    }
}
