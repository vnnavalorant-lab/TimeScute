package dev.timescute.core;

import java.util.Locale;

/** As partes do corpo do ator que uma animação pode controlar. */
public enum Part {
    HEAD("Cabeça"), BODY("Corpo"), RIGHT_ARM("Braço direito"), LEFT_ARM("Braço esquerdo"),
    RIGHT_LEG("Perna direita"), LEFT_LEG("Perna esquerda");

    public final String label;
    Part(String label) { this.label = label; }

    public int bit() { return 1 << ordinal(); }
    public static final int ALL = (1 << 6) - 1;
    public static final int ARMS = RIGHT_ARM.bit() | LEFT_ARM.bit();
    public static final int LEGS = RIGHT_LEG.bit() | LEFT_LEG.bit();

    public Part mirror() {
        return switch (this) {
            case RIGHT_ARM -> LEFT_ARM; case LEFT_ARM -> RIGHT_ARM;
            case RIGHT_LEG -> LEFT_LEG; case LEFT_LEG -> RIGHT_LEG;
            default -> this;
        };
    }

    /** Descobre a parte pelo nome do osso (Blockbench/Bedrock/Java). Nulo se o osso não existe no jogador. */
    public static Part fromBone(String bone) {
        String n = bone.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        return switch (n) {
            case "head", "hat" -> HEAD;
            case "body", "torso", "chest", "waist", "jacket" -> BODY;
            case "rightarm", "rarm", "armright", "rightsleeve" -> RIGHT_ARM;
            case "leftarm", "larm", "armleft", "leftsleeve" -> LEFT_ARM;
            case "rightleg", "rleg", "legright", "rightpants" -> RIGHT_LEG;
            case "leftleg", "lleg", "legleft", "leftpants" -> LEFT_LEG;
            default -> null;
        };
    }

    public static boolean has(int mask, Part p) { return (mask & p.bit()) != 0; }
}
