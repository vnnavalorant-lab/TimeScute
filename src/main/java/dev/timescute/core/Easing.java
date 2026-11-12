package dev.timescute.core;

/** Como a câmera viaja do keyframe atual até o PRÓXIMO. */
public enum Easing {
    SMOOTH("Suave", "Curva suave passando por todos os pontos"),
    LINEAR("Linear", "Velocidade constante em linha reta"),
    EASE("Acelera/Freia", "Começa e termina devagar"),
    HOLD("Parado", "Fica parado e salta no próximo keyframe");

    public final String label;
    public final String description;

    Easing(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Remapeia o progresso t (0..1) do segmento. */
    public double remap(double t) {
        return switch (this) {
            case SMOOTH, LINEAR -> t;
            case EASE -> t * t * (3 - 2 * t);
            case HOLD -> 0;
        };
    }

    public Easing next() {
        Easing[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
