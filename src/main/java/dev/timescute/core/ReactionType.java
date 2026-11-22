package dev.timescute.core;

public enum ReactionType {
    ANIMATION("Animação", "Aplica uma animação (do Blockbench ou embutida) às partes escolhidas"),
    SWING("Bater", "Balança o braço (golpe)"),
    USE_ITEM("Usar item", "Segura um item e usa (balança o braço)"),
    SWAP_ITEM("Trocar item", "Muda o item da mão durante o bloco"),
    LOOK_AT("Olhar", "Vira a cabeça para um ponto ou entidade");

    public final String label, description;
    ReactionType(String label, String description) { this.label = label; this.description = description; }
    public ReactionType next() { return values()[(ordinal() + 1) % values().length]; }
}
