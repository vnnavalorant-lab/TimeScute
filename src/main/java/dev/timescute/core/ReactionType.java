package dev.timescute.core;

public enum ReactionType {
    ANIMATION("Animação", "Aplica uma animação (do Blockbench ou embutida) às partes escolhidas"),
    SWING("Bater", "Balança o braço (golpe)"),
    USE_ITEM("Usar item", "Segura um item e usa (balança o braço)"),
    SWAP_ITEM("Trocar item", "Muda o item da mão durante o bloco"),
    LOOK_AT("Olhar", "Vira a cabeça para um ponto ou entidade"),
    WALK("Andar até", "Anda em linha reta até um ponto (só atores roteirizados). Rápido demais vira corrida"),
    TURN("Virar para", "Vira o corpo para um ponto (só atores roteirizados)");

    public final String label, description;
    ReactionType(String label, String description) { this.label = label; this.description = description; }
    /** Esse tipo só faz sentido para atores roteirizados (sem gravação)? */
    public boolean scriptedOnly() { return this == WALK || this == TURN; }

    /** Próximo tipo; {@code scripted} = o ator é roteirizado e pode usar Andar/Virar. */
    public ReactionType next(boolean scripted) {
        ReactionType t = this;
        do { t = values()[(t.ordinal() + 1) % values().length]; } while (t.scriptedOnly() && !scripted);
        return t;
    }

    public ReactionType next() { return next(true); }
}
