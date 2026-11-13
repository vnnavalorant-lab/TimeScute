package dev.timescute.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Fecha direito um replay que ficou sem rodapé (jogo fechou/crashou durante a gravação). */
public final class ReplayRepair {
    private ReplayRepair() { }

    /** @return false se o arquivo já estava íntegro (nada feito). */
    public static boolean repair(Path file) throws IOException {
        ReplayMeta meta;
        java.util.List<ReplayChunk> chunks;
        long good, thumb;
        long records;
        try (ReplayReader r = ReplayReader.open(file)) {
            if (r.isComplete()) return false;
            meta = r.meta();
            chunks = r.chunks();
            good = r.goodEnd();
            thumb = r.thumbnailOffset();
            records = r.recordCount();
        }
        long lastMs = 0;
        for (ReplayChunk c : chunks) lastMs = Math.max(lastMs, c.lastMs());
        meta.durationMs = Math.max(meta.durationMs, lastMs);
        if (meta.stopReason.isEmpty()) meta.stopReason = "Recuperado após uma falha (" + records + " registros).";

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(good);
            raf.seek(good);
            byte[] metaBytes = meta.toJson().getBytes(StandardCharsets.UTF_8);
            long metaOff = good;
            ReplayFormat.writeChunk(raf, ReplayFormat.CHUNK_META, metaBytes);
            long idxOff = metaOff + ReplayFormat.chunkSize(metaBytes.length);
            ReplayFormat.writeChunk(raf, ReplayFormat.CHUNK_INDEX, ReplayFormat.encodeIndex(chunks));
            ReplayFormat.writeFooter(raf, metaOff, thumb, idxOff);
        }
        return true;
    }
}
