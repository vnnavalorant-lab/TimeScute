package dev.timescute.core;

/**
 * Uma reação do ator: um bloco na timeline, em tempo ABSOLUTO da timeline (não da gravação).
 * Cada tipo usa só os campos que lhe interessam.
 */
public record Reaction(long id, long startMs, long durationMs, ReactionType type,
                       AnimParams anim, String itemId, boolean offHand, int count,
                       double tx, double ty, double tz, String targetEntity, String label) {
    public static final long MIN_DURATION_MS = 100;

    public Reaction {
        if (type == null) type = ReactionType.ANIMATION;
        if (anim == null) anim = AnimParams.DEFAULT;
        if (itemId == null) itemId = "";
        if (targetEntity == null) targetEntity = "";
        if (label == null) label = "";
        startMs = Math.max(0, startMs);
        durationMs = Math.max(MIN_DURATION_MS, durationMs);
        count = Math.max(1, Math.min(50, count));
    }

    public static Reaction of(long id, long startMs, long durationMs, ReactionType type) {
        return new Reaction(id, startMs, durationMs, type, AnimParams.DEFAULT, "", false, 1, 0, 0, 0, "", "");
    }

    public long endMs() { return startMs + durationMs; }
    public boolean activeAt(long t) { return t >= startMs && t < endMs(); }

    public Reaction withStart(long s) { return new Reaction(id, s, durationMs, type, anim, itemId, offHand, count, tx, ty, tz, targetEntity, label); }
    public Reaction withDuration(long d) { return new Reaction(id, startMs, d, type, anim, itemId, offHand, count, tx, ty, tz, targetEntity, label); }
    public Reaction withType(ReactionType t) { return new Reaction(id, startMs, durationMs, t, anim, itemId, offHand, count, tx, ty, tz, targetEntity, label); }
    public Reaction withAnim(AnimParams a) { return new Reaction(id, startMs, durationMs, type, a, itemId, offHand, count, tx, ty, tz, targetEntity, label); }
    public Reaction withItem(String item) { return new Reaction(id, startMs, durationMs, type, anim, item, offHand, count, tx, ty, tz, targetEntity, label); }
    public Reaction withOffHand(boolean o) { return new Reaction(id, startMs, durationMs, type, anim, itemId, o, count, tx, ty, tz, targetEntity, label); }
    public Reaction withCount(int c) { return new Reaction(id, startMs, durationMs, type, anim, itemId, offHand, c, tx, ty, tz, targetEntity, label); }
    public Reaction withTarget(double x, double y, double z, String entityUuid, String label) {
        return new Reaction(id, startMs, durationMs, type, anim, itemId, offHand, count, x, y, z, entityUuid, label);
    }

    /** Texto curto para o bloco na timeline. */
    public String shortLabel() {
        return switch (type) {
            case ANIMATION -> anim.clip().isEmpty() ? "Animação (escolha)" : ClipLibrary.label(anim.clip());
            case SWING -> count > 1 ? "Bater x" + count : "Bater";
            case USE_ITEM -> "Usar" + (itemId.isEmpty() ? "" : " " + simpleItem(itemId));
            case SWAP_ITEM -> "Item " + (itemId.isEmpty() ? "(vazio)" : simpleItem(itemId));
            case LOOK_AT -> "Olhar" + (label.isEmpty() ? "" : " " + label);
            case WALK -> (offHand ? "Esgueirar" : "Andar") + (label.isEmpty() ? "" : " " + label);
            case TURN -> "Virar" + (label.isEmpty() ? "" : " " + label);
        };
    }

    private static String simpleItem(String id) {
        int i = id.indexOf(':');
        return (i >= 0 ? id.substring(i + 1) : id).replace('_', ' ');
    }
}
