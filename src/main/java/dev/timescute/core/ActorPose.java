package dev.timescute.core;

import java.util.List;
import java.util.Map;

/** Tudo que o jogo precisa para desenhar o ator num instante. */
public final class ActorPose {
    public static final ActorPose HIDDEN = new ActorPose(false, null, "", Map.of(), 0, false, null, 0);

    public final boolean visible;
    public final ActorFrame frame;
    /** Item efetivamente na mão (id do registro; vazio = mão livre). */
    public final String item;
    /** Camadas de animação por parte do corpo, na ordem em que devem ser aplicadas sobre a pose do jogo. */
    public final Map<Part, List<PartLayer>> layers;
    /** Quantas batidas já começaram até agora (só cresce com o tempo): permite detectar "começou uma nova". */
    public final int swingCount;
    public final boolean swingOffHand;
    /** Reação de olhar ativa, ou null; {@code lookWeight} 0..1. */
    public final Reaction look;
    public final double lookWeight;

    /** Camadas por NOME DE OSSO do arquivo (para modelos do Blockbench, cujos ossos não são os do jogador). */
    public final Map<String, List<PartLayer>> boneLayers;

    public ActorPose(boolean visible, ActorFrame frame, String item, Map<Part, List<PartLayer>> layers,
                     int swingCount, boolean swingOffHand, Reaction look, double lookWeight) {
        this(visible, frame, item, layers, Map.of(), swingCount, swingOffHand, look, lookWeight);
    }

    public ActorPose(boolean visible, ActorFrame frame, String item, Map<Part, List<PartLayer>> layers,
                     Map<String, List<PartLayer>> boneLayers,
                     int swingCount, boolean swingOffHand, Reaction look, double lookWeight) {
        this.boneLayers = boneLayers;
        this.visible = visible;
        this.frame = frame;
        this.item = item;
        this.layers = layers;
        this.swingCount = swingCount;
        this.swingOffHand = swingOffHand;
        this.look = look;
        this.lookWeight = lookWeight;
    }
}
