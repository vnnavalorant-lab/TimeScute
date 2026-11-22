package dev.timescute.core;

/**
 * Movimento de um ator roteirizado: parte da posição inicial e segue, na ordem do tempo, os blocos
 * "Andar até" e "Virar para". Função pura do instante: dá para arrastar a timeline para qualquer ponto.
 */
public final class ScriptedMotion {
    private ScriptedMotion() { }

    /** Mais rápido que isto (blocos/s) vira corrida; o andar normal do jogo é ~4,3 e a corrida ~5,6. */
    public static final double SPRINT_BLOCKS_PER_S = 5.0;
    /** Tempo para o corpo virar para a direção do passo no começo de cada caminhada. */
    public static final long TURN_IN_MS = 250;

    public static ActorFrame frameAt(Actor a, long stageMs) {
        Actor.Origin o = a.origin();
        if (o == null) throw new IllegalArgumentException("Ator sem posição inicial.");
        double x = o.x(), y = o.y(), z = o.z();
        float yaw = o.yaw();
        int flags = ActorFrame.ON_GROUND;

        for (Reaction r : a.reactions()) {
            if (!r.type().scriptedOnly()) continue;
            if (stageMs < r.startMs()) break;
            boolean done = stageMs >= r.endMs();
            double p = done ? 1.0 : (double) (stageMs - r.startMs()) / r.durationMs();

            if (r.type() == ReactionType.WALK) {
                double dx = r.tx() - x, dz = r.tz() - z;
                double dist = Math.hypot(dx, dz);
                float walkYaw = dist > 0.01 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : yaw;
                if (done) { x = r.tx(); y = r.ty(); z = r.tz(); yaw = walkYaw; continue; }
                long turnMs = Math.min(TURN_IN_MS, r.durationMs() / 3);
                double tp = turnMs <= 0 ? 1 : Math.min(1.0, (double) (stageMs - r.startMs()) / turnMs);
                yaw = (float) LookMath.blendAngle(yaw, walkYaw, smooth(tp));
                x += dx * p; y += (r.ty() - y) * p; z += dz * p;
                double speed = dist / (r.durationMs() / 1000.0);
                if (r.offHand()) flags |= ActorFrame.SNEAKING;
                else if (speed > SPRINT_BLOCKS_PER_S) flags |= ActorFrame.SPRINTING;
                break;
            } else { // TURN
                float target = LookMath.yawPitch(x, y, z, r.tx(), r.ty(), r.tz())[0];
                if (done) { yaw = target; continue; }
                yaw = (float) LookMath.blendAngle(yaw, target, smooth(p));
                break;
            }
        }
        return new ActorFrame(Math.max(0, stageMs - a.startMs()), x, y, z, yaw, 0f, yaw, yaw, flags, 0, "");
    }

    /** Onde o ator termina o último Andar (ou a posição inicial): ponto de partida sugerido para o próximo bloco. */
    public static double[] endPosition(Actor a) {
        Actor.Origin o = a.origin();
        if (o == null) return null;
        double[] p = {o.x(), o.y(), o.z()};
        for (Reaction r : a.reactions()) if (r.type() == ReactionType.WALK) { p[0] = r.tx(); p[1] = r.ty(); p[2] = r.tz(); }
        return p;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }
}
