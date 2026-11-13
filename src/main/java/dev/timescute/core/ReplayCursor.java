package dev.timescute.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Leitura em fluxo de um replay: carrega um bloco por vez, entrega os registros em ordem
 * e permite voltar ao começo. Pensado para a reprodução (que só avança e, para voltar, recomeça).
 */
public final class ReplayCursor implements Closeable {
    private final ReplayReader reader;
    private int chunkIdx = -1;
    private List<ReplayRecord> buf = List.of();
    private int pos;
    private long lastTimeMs;

    private ReplayCursor(ReplayReader reader) { this.reader = reader; }

    public static ReplayCursor open(Path file) throws IOException { return new ReplayCursor(ReplayReader.open(file)); }

    public ReplayMeta meta() { return reader.meta(); }
    public boolean isComplete() { return reader.isComplete(); }
    public long durationMs() { return reader.meta().durationMs; }
    /** Tempo do último registro entregue por {@link #next()}. */
    public long lastTimeMs() { return lastTimeMs; }

    /** Primeira amostra do jogador (posição inicial da câmera). */
    public Optional<SelfSample> firstSelf() throws IOException {
        Optional<ReplayRecord> r = reader.findFirst(ReplayFormat.KIND_SELF);
        return r.isEmpty() ? Optional.empty() : Optional.of(SelfSample.decode(r.get().data()));
    }

    /** Próximo registro sem consumir; {@code null} no fim. */
    public ReplayRecord peek() throws IOException {
        while (pos >= buf.size()) {
            chunkIdx++;
            if (chunkIdx >= reader.chunks().size()) { chunkIdx = reader.chunks().size(); buf = List.of(); pos = 0; return null; }
            buf = reader.readChunkRecords(chunkIdx);
            pos = 0;
        }
        return buf.get(pos);
    }

    public ReplayRecord next() throws IOException {
        ReplayRecord r = peek();
        if (r != null) { pos++; lastTimeMs = r.timeMs(); }
        return r;
    }

    public boolean hasNext() throws IOException { return peek() != null; }

    /** Volta ao primeiro registro. */
    public void reset() {
        chunkIdx = -1;
        buf = List.of();
        pos = 0;
        lastTimeMs = 0;
    }

    @Override
    public void close() throws IOException { reader.close(); }
}
