package dev.timescute.core;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Entrega ao reprodutor os registros cujo tempo já chegou, respeitando um orçamento de tempo por quadro. */
public final class ReplayFeeder {
    public interface Handler { void handle(ReplayRecord r) throws IOException; }

    /** @param reachedEnd acabaram os registros; @param outOfBudget parou porque o orçamento do quadro acabou. */
    public record Result(int delivered, boolean reachedEnd, boolean outOfBudget) { }

    private ReplayFeeder() { }

    /**
     * Entrega, em ordem, todos os registros com tempo <= {@code targetMs}. O orçamento é consultado a cada 32
     * registros; se estourar, para e na próxima chamada continua exatamente de onde parou.
     */
    public static Result feed(ReplayCursor cursor, long targetMs, Handler handler, BooleanSupplier outOfBudget) throws IOException {
        int n = 0;
        while (true) {
            ReplayRecord r = cursor.peek();
            if (r == null) return new Result(n, true, false);
            if (r.timeMs() > targetMs) return new Result(n, false, false);
            cursor.next();
            handler.handle(r);
            n++;
            if ((n & 31) == 0 && outOfBudget.getAsBoolean()) return new Result(n, false, true);
        }
    }
}
