package dev.timescute.core;

import java.nio.ByteBuffer;

/**
 * O servidor nunca envia o jogador local para si mesmo, então o próprio jogador é gravado à parte,
 * uma amostra por tick, para poder aparecer no replay.
 */
public record SelfSample(int entityId, double x, double y, double z,
                         float yaw, float pitch, float headYaw,
                         int flags, int hotbarSlot, float health) {
    public static final int SNEAKING = 1, SPRINTING = 2, ON_GROUND = 4, SWINGING = 8, FALL_FLYING = 16, SWIMMING = 32;
    public static final int ENCODED_LEN = 4 + 24 + 12 + 1 + 1 + 4;

    public boolean has(int flag) { return (flags & flag) != 0; }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(ENCODED_LEN);
        b.putInt(entityId).putDouble(x).putDouble(y).putDouble(z)
                .putFloat(yaw).putFloat(pitch).putFloat(headYaw)
                .put((byte) flags).put((byte) hotbarSlot).putFloat(health);
        return b.array();
    }

    public static SelfSample decode(byte[] p) {
        if (p.length != ENCODED_LEN) throw new IllegalArgumentException("Amostra do jogador com tamanho inválido.");
        ByteBuffer b = ByteBuffer.wrap(p);
        return new SelfSample(b.getInt(), b.getDouble(), b.getDouble(), b.getDouble(),
                b.getFloat(), b.getFloat(), b.getFloat(), b.get() & 0xFF, b.get() & 0xFF, b.getFloat());
    }
}
