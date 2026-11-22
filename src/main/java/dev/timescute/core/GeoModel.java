package dev.timescute.core;

import java.util.*;

/**
 * Um modelo de geometria do Blockbench (formato Bedrock): ossos em árvore, cada um com cubos.
 * Os números ficam como estão no arquivo (pixels e graus, eixo Y para cima); a conversão para o Java é feita ao desenhar.
 */
public record GeoModel(String name, int texW, int texH, List<Bone> bones) {
    public static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    /** @param boxUv [u, v] do layout de caixa, ou nulo se o cubo usa UV por face; @param faceUv face -> {u, v, largura, altura} */
    public record Cube(double[] origin, double[] size, double[] pivot, double[] rotation, double inflate,
                       Boolean mirror, double[] boxUv, Map<String, double[]> faceUv) {
        public boolean hasRotation() { return rotation != null && (rotation[0] != 0 || rotation[1] != 0 || rotation[2] != 0); }
    }

    public record Bone(String name, String parent, double[] pivot, double[] rotation, boolean mirror, List<Cube> cubes) { }

    public GeoModel {
        if (name == null || name.isBlank()) name = "modelo";
        texW = Math.max(1, texW);
        texH = Math.max(1, texH);
        bones = List.copyOf(bones);
    }

    public Optional<Bone> bone(String n) {
        for (Bone b : bones) if (b.name().equals(n)) return Optional.of(b);
        return Optional.empty();
    }

    /** Ossos sem pai (ou cujo pai não existe), na ordem do arquivo. */
    public List<Bone> roots() {
        List<Bone> out = new ArrayList<>();
        for (Bone b : bones) if (b.parent() == null || bone(b.parent()).isEmpty()) out.add(b);
        return out;
    }

    public List<Bone> childrenOf(String parent) {
        List<Bone> out = new ArrayList<>();
        for (Bone b : bones) if (parent.equals(b.parent())) out.add(b);
        return out;
    }

    public int cubeCount() {
        int n = 0;
        for (Bone b : bones) n += b.cubes().size();
        return n;
    }

    /** Tem ossos com nome de parte do jogador (cabeça, braços, pernas)? Então dá para andar sem clipe de animação. */
    public boolean looksHumanoid() {
        int n = 0;
        for (Bone b : bones) if (Part.fromBone(b.name()) != null) n++;
        return n >= 3;
    }
}
