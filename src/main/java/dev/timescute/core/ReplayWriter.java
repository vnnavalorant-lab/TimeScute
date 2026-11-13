package dev.timescute.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.zip.Deflater;

/**
 * Grava um replay em segundo plano. {@link #append} nunca bloqueia nem toca o disco: só enfileira.
 * Uma thread própria agrupa, comprime e escreve blocos independentes (seguros contra crash).
 */
public final class ReplayWriter implements Closeable {

    public static final class Options {
        public int level = 3;                         // 1 (rápido) .. 9 (menor)
        public long minFreeBytes = 0;                 // 0 = não verifica
        public long maxBacklogBytes = 256L << 20;     // fila máxima na memória
        public long maxBatchBytes = 512 << 10;
        public long maxBatchMs = 2000;
        public LongSupplier clock = System::currentTimeMillis;
    }

    private static final class Item {
        final long timeMs; final int kind; final byte[] data; final CountDownLatch flush;
        Item(long t, int k, byte[] d, CountDownLatch f) { timeMs = t; kind = k; data = d; flush = f; }
    }

    private static final Item POISON = new Item(-1, 0, null, null);
    private static final int ITEM_OVERHEAD = 16;

    private final Path file;
    private final Options opt;
    private final ReplayMeta meta;
    private final long startClock;
    private final Object lock = new Object();
    private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final Thread thread;
    private FileStore store;

    private volatile String error;
    private volatile boolean closed;
    private volatile byte[] pendingThumb;
    private volatile long lastTimeMs;

    // estado exclusivo da thread de escrita
    private DataOutputStream out;
    private long pos;
    private long thumbOffset;
    private final List<ReplayChunk> index = new ArrayList<>();
    private long nPackets, nSelf, nMarkers;

    private ReplayWriter(Path file, ReplayMeta meta, Options opt) {
        this.file = file;
        this.meta = meta;
        this.opt = opt;
        this.startClock = opt.clock.getAsLong();
        this.thread = new Thread(this::run, "timescute-replay-writer");
        this.thread.setDaemon(true);
    }

    public static ReplayWriter create(Path file, ReplayMeta meta, Options opt) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        ReplayWriter w = new ReplayWriter(file, meta, opt);
        try { w.store = Files.getFileStore(file.toAbsolutePath().getParent()); } catch (IOException | RuntimeException ignored) { /* sem verificação de espaço */ }
        w.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 1 << 16));
        ReplayFormat.writeHeader(w.out);
        w.pos = ReplayFormat.HEADER_LEN;
        w.writeChunk(ReplayFormat.CHUNK_META, meta.toJson().getBytes(StandardCharsets.UTF_8));
        w.out.flush();
        w.thread.start();
        return w;
    }

    // ================= API (qualquer thread) =================

    /** Enfileira um item no instante atual. Retorna false se a gravação já parou. */
    public boolean append(int kind, byte[] data) {
        synchronized (lock) {
            if (closed || error != null) return false;
            return enqueueLocked(elapsedLocked(), kind, data);
        }
    }

    public void addMarker(String text) {
        synchronized (lock) {
            if (closed || error != null) return;
            long t = elapsedLocked();
            if (meta.markerList.size() < ReplayMeta.MAX_MARKERS) meta.markerList.add(new ReplayMeta.Marker(t, text));
            enqueueLocked(t, ReplayFormat.KIND_MARKER, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** PNG da miniatura. Gravado no arquivo assim que possível (sobrevive a crash). */
    public void setThumbnail(byte[] png) { pendingThumb = png; }

    public void setStopReason(String reason) { if (reason != null) meta.stopReason = reason; }

    public Optional<String> error() { return Optional.ofNullable(error); }
    public boolean isClosed() { return closed; }
    public long elapsedMs() { synchronized (lock) { return elapsedLocked(); } }
    public long records() { return enqueued.get(); }
    public long bytesWritten() { return bytesWritten.get(); }
    public long backlogBytes() { return backlog.get(); }
    public Path file() { return file; }

    /** Espera a fila esvaziar e força a escrita do bloco atual (útil em testes e antes de copiar o arquivo). */
    public void flushNow() throws InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        synchronized (lock) { if (closed) return; queue.add(new Item(0, 0, null, l)); }
        l.await(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            queue.add(POISON);
        }
        try { thread.join(120_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ================= internos =================

    private long elapsedLocked() {
        return Math.max(0, Math.min(opt.clock.getAsLong() - startClock, 0xFFFFFFFFL));
    }

    private boolean enqueueLocked(long t, int kind, byte[] data) {
        long size = data.length + ITEM_OVERHEAD;
        if (backlog.addAndGet(size) > opt.maxBacklogBytes) {
            failLocked("O disco não está acompanhando a gravação.");
            return false;
        }
        lastTimeMs = t;
        enqueued.incrementAndGet();
        queue.add(new Item(t, kind, data, null));
        return true;
    }

    private void failLocked(String msg) {
        if (error == null) {
            error = msg;
            queue.add(POISON);
        }
    }

    private void fail(String msg) { synchronized (lock) { failLocked(msg); } }

    private void run() {
        ByteArrayOutputStream batch = new ByteArrayOutputStream(1 << 16);
        DataOutputStream bd = new DataOutputStream(batch);
        int count = 0;
        long first = 0, last = 0, batchStartNanos = 0;
        try {
            while (true) {
                Item it = queue.poll(500, TimeUnit.MILLISECONDS);
                boolean idle = it == null;
                if (it == POISON) break;
                if (!idle && it.data != null) {
                    backlog.addAndGet(-(it.data.length + ITEM_OVERHEAD));
                    if (error != null) break;
                    if (count == 0) { first = it.timeMs; batchStartNanos = System.nanoTime(); }
                    bd.writeInt((int) it.timeMs);
                    bd.writeByte(it.kind);
                    bd.writeInt(it.data.length);
                    bd.write(it.data);
                    count++;
                    last = it.timeMs;
                    switch (it.kind) {
                        case ReplayFormat.KIND_PACKET -> nPackets++;
                        case ReplayFormat.KIND_SELF -> nSelf++;
                        case ReplayFormat.KIND_MARKER -> nMarkers++;
                        default -> { }
                    }
                }
                boolean due = count > 0 && (batch.size() >= opt.maxBatchBytes
                        || (System.nanoTime() - batchStartNanos) / 1_000_000L >= opt.maxBatchMs
                        || (it != null && it.flush != null));
                if (due) {
                    if (!flushBatch(batch, count, first, last)) break;
                    batch.reset();
                    count = 0;
                }
                byte[] th = pendingThumb;
                if (th != null) { pendingThumb = null; thumbOffset = pos; writeChunk(ReplayFormat.CHUNK_THUMB, th); out.flush(); }
                if (it != null && it.flush != null) it.flush.countDown();
            }
            if (count > 0 && error == null) flushBatch(batch, count, first, last);
            writeFinal();
        } catch (IOException e) {
            fail("Erro ao gravar o replay: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            fail("Erro inesperado na gravação: " + e);
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignored) { /* nada a fazer */ }
            // libera quem ainda espera um flush
            Item rest;
            while ((rest = queue.poll()) != null) if (rest.flush != null) rest.flush.countDown();
        }
    }

    /** @return false se a gravação de dados deve parar (erro). */
    private boolean flushBatch(ByteArrayOutputStream batch, int count, long first, long last) throws IOException {
        if (opt.minFreeBytes > 0 && store != null && store.getUsableSpace() < opt.minFreeBytes) {
            fail("Pouco espaço em disco: gravação interrompida.");
            return false;
        }
        byte[] raw = batch.toByteArray();
        byte[] comp = deflate(raw, opt.level);
        boolean useComp = comp.length < raw.length;
        byte[] body = useComp ? comp : raw;

        java.nio.ByteBuffer p = java.nio.ByteBuffer.allocate(ReplayFormat.DATA_PREFIX_LEN + body.length);
        p.putInt(count).putInt((int) first).putInt((int) last).put((byte) (useComp ? 1 : 0)).putInt(raw.length).put(body);
        long off = pos;
        writeChunk(ReplayFormat.CHUNK_DATA, p.array());
        out.flush();
        index.add(new ReplayChunk(off, first, last, count));
        return true;
    }

    private void writeChunk(int type, byte[] payload) throws IOException {
        ReplayFormat.writeChunk(out, type, payload);
        long size = ReplayFormat.chunkSize(payload.length);
        pos += size;
        bytesWritten.addAndGet(size);
    }

    private void writeFinal() throws IOException {
        List<ReplayMeta.Marker> markers;
        synchronized (lock) { markers = new ArrayList<>(meta.markerList); }
        meta.markerList.clear();
        meta.markerList.addAll(markers);
        meta.durationMs = Math.max(lastTimeMs, index.isEmpty() ? 0 : index.get(index.size() - 1).lastMs());
        meta.packets = nPackets;
        meta.selfSamples = nSelf;
        meta.markers = nMarkers;
        if (error != null) meta.stopReason = error;

        long metaOff = pos;
        writeChunk(ReplayFormat.CHUNK_META, meta.toJson().getBytes(StandardCharsets.UTF_8));
        long idxOff = pos;
        writeChunk(ReplayFormat.CHUNK_INDEX, ReplayFormat.encodeIndex(index));
        ReplayFormat.writeFooter(out, metaOff, thumbOffset, idxOff);
        out.flush();
    }

    static byte[] deflate(byte[] raw, int level) {
        Deflater d = new Deflater(Math.max(1, Math.min(9, level)));
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length / 2 + 64);
            byte[] buf = new byte[1 << 16];
            while (!d.finished()) o.write(buf, 0, d.deflate(buf));
            return o.toByteArray();
        } finally {
            d.end();
        }
    }
}
