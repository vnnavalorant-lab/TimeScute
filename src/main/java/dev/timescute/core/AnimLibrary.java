package dev.timescute.core;

import java.util.Optional;

/** De onde vêm os clipes de animação (embutidos + importados). */
public interface AnimLibrary {
    /** Como os eixos de um clipe viram os do jogo; ajustável por clipe (calibração). */
    record Config(Convention convention, boolean flipPitch, boolean flipYaw, boolean flipRoll) {
        public static final Config BEDROCK = new Config(Convention.BEDROCK, false, false, false);
        public static final Config JAVA = new Config(Convention.JAVA, false, false, false);

        /** Valor do osso (graus/pixels do arquivo) -> pose no modelo do Java (radianos/pixels). */
        public PartPose toPose(AnimClip.BoneValue v) {
            double[] r = v.rot(), p = v.pos(), s = v.scale();
            // "+ 0.0" troca -0.0 por 0.0 (0 x -1 daria zero negativo)
            return new PartPose(
                    Math.toRadians(r[0]) * convention.rotSign(0) * (flipPitch ? -1 : 1) + 0.0,
                    Math.toRadians(r[1]) * convention.rotSign(1) * (flipYaw ? -1 : 1) + 0.0,
                    Math.toRadians(r[2]) * convention.rotSign(2) * (flipRoll ? -1 : 1) + 0.0,
                    p[0] * convention.posSign(0) + 0.0, p[1] * convention.posSign(1) + 0.0, p[2] * convention.posSign(2) + 0.0,
                    s[0], s[1], s[2]);
        }
    }

    Optional<AnimClip> clip(String name);
    Config config(String name);

    AnimLibrary EMPTY = new AnimLibrary() {
        public Optional<AnimClip> clip(String name) { return Optional.empty(); }
        public Config config(String name) { return Config.BEDROCK; }
    };
}
