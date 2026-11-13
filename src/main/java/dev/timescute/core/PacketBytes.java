package dev.timescute.core;

import java.util.Arrays;

/** Ferramentas para os bytes de um pacote gravado: varint(id) + corpo. */
public final class PacketBytes {
    private PacketBytes() { }

    /** Tamanho em bytes do varint no começo, ou -1 se inválido/truncado. */
    public static int varIntLength(byte[] b) {
        for (int i = 0; i < Math.min(5, b.length); i++) if ((b[i] & 0x80) == 0) return i + 1;
        return -1;
    }

    public static int readVarInt(byte[] b) {
        int v = 0;
        for (int i = 0; i < 5; i++) {
            if (i >= b.length) return -1;
            v |= (b[i] & 0x7F) << (7 * i);
            if ((b[i] & 0x80) == 0) return v;
        }
        return -1;
    }

    /** Cópia com um int de 4 bytes (big-endian) escrito em {@code offset}. */
    public static byte[] patchInt(byte[] data, int offset, int value) {
        if (offset < 0 || offset + 4 > data.length) throw new IllegalArgumentException("Pacote curto demais para alterar.");
        byte[] out = Arrays.copyOf(data, data.length);
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
        return out;
    }

    public static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}
