package dev.timescute.core;

/**
 * Como os eixos do arquivo de animação viram os eixos do modelo do jogador no Minecraft Java.
 * BEDROCK (padrão do Blockbench para add-ons): quadro girado 180° em Z em relação ao do Java, então X e Y mudam de sinal.
 * JAVA: o arquivo já foi feito para o modelo do Java (posição Y invertida como no jogo).
 * O que não puder ser garantido de cabeça é conferido com o clipe de calibração.
 */
public enum Convention {
    BEDROCK("Bedrock (Blockbench)", new int[]{-1, -1, 1}, new int[]{-1, -1, 1}),
    JAVA("Java (Modded Entity)", new int[]{1, 1, 1}, new int[]{1, -1, 1});

    public final String label;
    private final int[] rot, pos;

    Convention(String label, int[] rot, int[] pos) { this.label = label; this.rot = rot; this.pos = pos; }

    public int rotSign(int axis) { return rot[axis]; }
    public int posSign(int axis) { return pos[axis]; }
    public Convention next() { return values()[(ordinal() + 1) % values().length]; }
}
