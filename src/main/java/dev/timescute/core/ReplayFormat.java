package dev.timescute.core;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Formato .tsr (TimeScute Replay), versão 1.
 *
 * <pre>
 * Arquivo  := Cabeçalho Bloco* [Rodapé]
 * Cabeçalho:= "TSREPLAY" u16(versão) u16(0)                         (12 bytes)
 * Bloco    := u8(tipo) u32(tamanho) payload u32(crc32 de tipo+payload)
 * Rodapé   := "TSRPEND\0" u64(metaOff) u64(thumbOff) u64(indexOff) u32(crc)   (36 bytes)
 *
 * Tipos: 1 META (json) | 2 DATA | 3 INDEX | 4 THUMB (png)
 * DATA   := u32(n) u32(primeiroMs) u32(últimoMs) u8(comprimido) u32(tamanhoBruto) corpo
 *           corpo (descomprimido) = n × [ u32(tempoMs) u8(tipo) u32(tamanho) bytes ]
 * </pre>
 * Sem o rodapé (crash), o leitor reconstrói o índice varrendo os blocos.
 */
public final class ReplayFormat {
    public static final byte[] MAGIC = "TSREPLAY".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] FOOTER_MAGIC = {'T', 'S', 'R', 'P', 'E', 'N', 'D', 0};
    public static final int VERSION = 1;
    public static final int HEADER_LEN = 12;
    public static final int FOOTER_LEN = 36;
    public static final int DATA_PREFIX_LEN = 17;

    public static final int CHUNK_META = 1, CHUNK_DATA = 2, CHUNK_INDEX = 3, CHUNK_THUMB = 4;
    public static final int KIND_PACKET = 1, KIND_SELF = 2, KIND_MARKER = 3;

    /** Limites defensivos contra arquivos corrompidos (evitam OutOfMemory). */
    public static final long MAX_CHUNK = 512L << 20;
    public static final long MAX_RAW = 256L << 20;

    private ReplayFormat() { }

    public static long chunkSize(long payloadLen) { return 1 + 4 + payloadLen + 4; }

    public static int crc(int type, byte[] payload) {
        CRC32 c = new CRC32();
        c.update(type);
        c.update(payload, 0, payload.length);
        return (int) c.getValue();
    }

    public static void writeHeader(DataOutput o) throws IOException {
        o.write(MAGIC);
        o.writeShort(VERSION);
        o.writeShort(0);
    }

    public static void writeChunk(DataOutput o, int type, byte[] payload) throws IOException {
        o.writeByte(type);
        o.writeInt(payload.length);
        o.write(payload);
        o.writeInt(crc(type, payload));
    }

    public static byte[] encodeIndex(List<ReplayChunk> chunks) {
        ByteBuffer b = ByteBuffer.allocate(4 + chunks.size() * 20);
        b.putInt(chunks.size());
        for (ReplayChunk c : chunks) {
            b.putLong(c.offset());
            b.putInt((int) c.firstMs());
            b.putInt((int) c.lastMs());
            b.putInt(c.records());
        }
        return b.array();
    }

    public static List<ReplayChunk> decodeIndex(byte[] p) throws IOException {
        try {
            ByteBuffer b = ByteBuffer.wrap(p);
            int n = b.getInt();
            if (n < 0 || n > (p.length - 4) / 20) throw new IOException("Índice do replay corrompido.");
            List<ReplayChunk> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                out.add(new ReplayChunk(b.getLong(), b.getInt() & 0xFFFFFFFFL, b.getInt() & 0xFFFFFFFFL, b.getInt()));
            return out;
        } catch (java.nio.BufferUnderflowException e) {
            throw new IOException("Índice do replay corrompido.");
        }
    }

    public static void writeFooter(DataOutput o, long metaOff, long thumbOff, long indexOff) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(FOOTER_LEN - 4);
        b.put(FOOTER_MAGIC).putLong(metaOff).putLong(thumbOff).putLong(indexOff);
        byte[] body = b.array();
        CRC32 c = new CRC32();
        c.update(body, 0, body.length);
        o.write(body);
        o.writeInt((int) c.getValue());
    }
}
