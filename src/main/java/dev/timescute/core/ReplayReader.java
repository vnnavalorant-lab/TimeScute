package dev.timescute.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Abre um .tsr. Usa o rodapé quando existe; senão (crash) reconstrói o índice varrendo os blocos. */
public final class ReplayReader implements Closeable {

    public interface RecordConsumer { void accept(ReplayRecord r) throws IOException; }

    private record RawChunk(int type, byte[] payload) { }

    private final RandomAccessFile raf;
    private long length;
    private ReplayMeta meta;
    private boolean complete;
    private List<ReplayChunk> chunks = new ArrayList<>();
    private long thumbOffset;
    private long goodEnd;
    private long totalRecords;

    private ReplayReader(RandomAccessFile raf) { this.raf = raf; }

    public static ReplayReader open(Path p) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r");
        try {
            ReplayReader r = new ReplayReader(raf);
            r.load();
            return r;
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    public ReplayMeta meta() { return meta; }
    /** true se o arquivo foi fechado direito (tem rodapé válido). */
    public boolean isComplete() { return complete; }
    public List<ReplayChunk> chunks() { return Collections.unmodifiableList(chunks); }
    public long recordCount() { return totalRecords; }
    /** Fim do último bloco íntegro (usado no reparo). */
    public long goodEnd() { return goodEnd; }
    public long thumbnailOffset() { return thumbOffset; }

    @Override
    public void close() throws IOException { raf.close(); }

    // ---------- carregamento ----------

    private void load() throws IOException {
        length = raf.length();
        if (length < ReplayFormat.HEADER_LEN) throw new IOException("Arquivo pequeno demais para ser um replay.");
        byte[] h = new byte[ReplayFormat.HEADER_LEN];
        raf.seek(0);
        raf.readFully(h);
        if (!Arrays.equals(Arrays.copyOf(h, 8), ReplayFormat.MAGIC)) throw new IOException("Este arquivo não é um replay do TimeScute.");
        int ver = ((h[8] & 255) << 8) | (h[9] & 255);
        if (ver > ReplayFormat.VERSION) throw new IOException("Replay gravado por uma versão mais nova do TimeScute.");
        if (!loadFromFooter()) scan();
    }

    private boolean loadFromFooter() throws IOException {
        if (length < ReplayFormat.HEADER_LEN + ReplayFormat.FOOTER_LEN) return false;
        byte[] f = new byte[ReplayFormat.FOOTER_LEN];
        raf.seek(length - ReplayFormat.FOOTER_LEN);
        raf.readFully(f);
        if (!Arrays.equals(Arrays.copyOf(f, 8), ReplayFormat.FOOTER_MAGIC)) return false;
        ByteBuffer bb = ByteBuffer.wrap(f);
        bb.position(8);
        long metaOff = bb.getLong(), thumbOff = bb.getLong(), idxOff = bb.getLong();
        int crc = bb.getInt();
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(f, 0, ReplayFormat.FOOTER_LEN - 4);
        if ((int) c.getValue() != crc) return false;

        long limit = length - ReplayFormat.FOOTER_LEN;
        if (!inRange(metaOff, limit) || !inRange(idxOff, limit) || (thumbOff != 0 && !inRange(thumbOff, limit))) return false;
        try {
            RawChunk m = readChunk(metaOff, limit), ix = readChunk(idxOff, limit);
            if (m.type != ReplayFormat.CHUNK_META || ix.type != ReplayFormat.CHUNK_INDEX) return false;
            meta = ReplayMeta.fromJson(new String(m.payload, StandardCharsets.UTF_8));
            chunks = ReplayFormat.decodeIndex(ix.payload);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
        thumbOffset = thumbOff;
        complete = true;
        goodEnd = length;
        totalRecords = 0;
        for (ReplayChunk ch : chunks) totalRecords += ch.records();
        return true;
    }

    private static boolean inRange(long off, long limit) { return off >= ReplayFormat.HEADER_LEN && off < limit; }

    private void scan() throws IOException {
        long pos = ReplayFormat.HEADER_LEN;
        goodEnd = pos;
        meta = null;
        chunks = new ArrayList<>();
        thumbOffset = 0;
        long lastStart = -1;
        int lastType = 0;
        while (pos + 9 <= length) {
            raf.seek(pos);
            int type = raf.read();
            long len = raf.readInt() & 0xFFFFFFFFL;
            if (type < 1 || type > 4 || len > ReplayFormat.MAX_CHUNK || pos + 9 + len > length) break;
            if (type == ReplayFormat.CHUNK_DATA) {
                if (len < ReplayFormat.DATA_PREFIX_LEN) break;
                byte[] pre = new byte[12];
                raf.readFully(pre);
                ByteBuffer b = ByteBuffer.wrap(pre);
                chunks.add(new ReplayChunk(pos, b.getInt(4) & 0xFFFFFFFFL, b.getInt(8) & 0xFFFFFFFFL, b.getInt(0)));
            } else {
                RawChunk rc;
                try { rc = readChunk(pos, length); } catch (IOException e) { break; }
                if (type == ReplayFormat.CHUNK_META) {
                    try { meta = ReplayMeta.fromJson(new String(rc.payload, StandardCharsets.UTF_8)); }
                    catch (IllegalArgumentException e) { break; }
                } else if (type == ReplayFormat.CHUNK_THUMB) {
                    thumbOffset = pos;
                }
            }
            lastStart = pos;
            lastType = type;
            pos += ReplayFormat.chunkSize(len);
            goodEnd = pos;
        }
        // Para ser rápido, só os cabeçalhos dos blocos de dados foram lidos. Se o ÚLTIMO bloco aceito é de dados,
        // confere o checksum completo dele: ele pode ter sido escrito pela metade na hora do crash.
        if (lastType == ReplayFormat.CHUNK_DATA && !chunks.isEmpty()) {
            try { readChunk(lastStart, goodEnd); }
            catch (IOException e) { chunks.remove(chunks.size() - 1); goodEnd = lastStart; }
        }
        if (meta == null) throw new IOException("Replay sem cabeçalho (arquivo corrompido).");
        totalRecords = 0;
        long lastMs = 0;
        for (ReplayChunk ch : chunks) { totalRecords += ch.records(); lastMs = Math.max(lastMs, ch.lastMs()); }
        meta.durationMs = Math.max(meta.durationMs, lastMs);
        complete = false;
    }

    private RawChunk readChunk(long offset, long limit) throws IOException {
        raf.seek(offset);
        int type = raf.read();
        long len = raf.readInt() & 0xFFFFFFFFL;
        if (type < 1 || type > 4 || len > ReplayFormat.MAX_CHUNK || offset + ReplayFormat.chunkSize(len) > limit)
            throw new IOException("Trecho do replay inválido na posição " + offset + ".");
        byte[] payload = new byte[(int) len];
        raf.readFully(payload);
        int crc = raf.readInt();
        if (crc != ReplayFormat.crc(type, payload)) throw new IOException("Trecho do replay corrompido (checksum) na posição " + offset + ".");
        return new RawChunk(type, payload);
    }

    private long dataLimit() { return complete ? length - ReplayFormat.FOOTER_LEN : goodEnd; }

    // ---------- leitura ----------

    public Optional<byte[]> thumbnail() throws IOException {
        if (thumbOffset == 0) return Optional.empty();
        RawChunk c = readChunk(thumbOffset, dataLimit());
        return c.type == ReplayFormat.CHUNK_THUMB ? Optional.of(c.payload) : Optional.empty();
    }

    public void forEach(RecordConsumer c) throws IOException { forEachFrom(0, c); }

    /** Entrega os registros com tempo >= fromMs, pulando os blocos anteriores usando o índice. */
    public void forEachFrom(long fromMs, RecordConsumer c) throws IOException {
        for (ReplayChunk ch : chunks) {
            if (ch.lastMs() < fromMs) continue;
            RawChunk rc = readChunk(ch.offset(), dataLimit());
            if (rc.type != ReplayFormat.CHUNK_DATA) throw new IOException("Índice do replay aponta para um trecho que não é de dados.");
            parseData(rc.payload, fromMs, c);
        }
    }

    private static void parseData(byte[] payload, long fromMs, RecordConsumer c) throws IOException {
        ByteBuffer p = ByteBuffer.wrap(payload);
        int count;
        int comp;
        int rawLen;
        try {
            count = p.getInt();
            p.getInt();
            p.getInt();
            comp = p.get();
            rawLen = p.getInt();
        } catch (java.nio.BufferUnderflowException e) {
            throw new IOException("Bloco de dados truncado.");
        }
        if (rawLen < 0 || rawLen > ReplayFormat.MAX_RAW) throw new IOException("Bloco de dados com tamanho inválido.");
        byte[] raw;
        if (comp == 1) {
            raw = new byte[rawLen];
            Inflater inf = new Inflater();
            try {
                inf.setInput(payload, ReplayFormat.DATA_PREFIX_LEN, payload.length - ReplayFormat.DATA_PREFIX_LEN);
                int got = 0;
                while (got < rawLen) {
                    int n = inf.inflate(raw, got, rawLen - got);
                    if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break;
                    got += n;
                }
                if (got != rawLen) throw new IOException("Bloco de dados comprimido está incompleto.");
            } catch (DataFormatException e) {
                throw new IOException("Bloco de dados comprimido está corrompido.");
            } finally {
                inf.end();
            }
        } else {
            raw = Arrays.copyOfRange(payload, ReplayFormat.DATA_PREFIX_LEN, payload.length);
            if (raw.length != rawLen) throw new IOException("Bloco de dados com tamanho inconsistente.");
        }
        ByteBuffer b = ByteBuffer.wrap(raw);
        try {
            for (int i = 0; i < count; i++) {
                long t = b.getInt() & 0xFFFFFFFFL;
                int kind = b.get() & 0xFF;
                int len = b.getInt();
                if (len < 0 || len > b.remaining()) throw new IOException("Registro truncado.");
                byte[] d = new byte[len];
                b.get(d);
                if (t >= fromMs) c.accept(new ReplayRecord(t, kind, d));
            }
        } catch (java.nio.BufferUnderflowException e) {
            throw new IOException("Registro truncado.");
        }
    }
}
