package dev.timescute.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Envia quadros para o stdin de um processo (o FFmpeg) em thread própria.
 * A fila é pequena de propósito: se o codificador for mais lento, o jogo espera, em vez de encher a memória.
 */
public final class FrameWriter {
    private static final byte[] POISON = new byte[0];
    private static final int STDERR_KEEP = 4000;

    private final Process proc;
    private final OutputStream stdin;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(3);
    private final Thread writerThread, errThread;
    private final StringBuilder stderr = new StringBuilder();
    private final AtomicLong written = new AtomicLong();
    private volatile IOException error;
    private volatile boolean finished;

    private FrameWriter(Process proc) {
        this.proc = proc;
        this.stdin = proc.getOutputStream();
        this.writerThread = new Thread(this::writeLoop, "timescute-frame-writer");
        this.errThread = new Thread(() -> readErr(proc.getErrorStream()), "timescute-frame-stderr");
        writerThread.setDaemon(true);
        errThread.setDaemon(true);
    }

    public static FrameWriter start(List<String> command) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
        FrameWriter w = new FrameWriter(p);
        w.writerThread.start();
        w.errThread.start();
        return w;
    }

    public long framesWritten() { return written.get(); }
    public IOException error() { return error; }

    public synchronized String stderrTail() {
        String s = stderr.toString().trim();
        return s.length() > STDERR_KEEP ? s.substring(s.length() - STDERR_KEEP) : s;
    }

    /** Enfileira um quadro (espera se a fila estiver cheia). Lança se o processo morreu. */
    public void submit(byte[] frame) throws IOException {
        while (true) {
            checkError();
            try {
                if (queue.offer(frame, 100, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrompido.");
            }
            if (!proc.isAlive()) {
                waitErr();
                fail(new IOException("O FFmpeg parou inesperadamente. " + stderrTail()));
            }
        }
    }

    /**
     * Termina a entrada, espera o FFmpeg acabar de codificar e devolve o código de saída.
     * A thread de escrita é SEMPRE encerrada, mesmo se o FFmpeg já tiver morrido.
     */
    public int finish() throws IOException, InterruptedException {
        finished = true;
        while (writerThread.isAlive()) {
            if (error != null) queue.clear(); // processo morto: descarta o que sobrou para abrir espaço
            if (queue.offer(POISON, 100, TimeUnit.MILLISECONDS)) break;
        }
        writerThread.join(120_000);
        if (writerThread.isAlive()) { // não deveria acontecer; não deixa o jogo preso
            proc.destroyForcibly();
            writerThread.join(5_000);
        }
        try { stdin.close(); } catch (IOException ignored) { /* o processo pode já ter fechado */ }
        int code = proc.waitFor();
        errThread.join(2000);
        return code;
    }

    /** Cancela: mata o processo na hora. */
    public void abort() {
        finished = true;
        proc.destroyForcibly();
        queue.clear();
        queue.offer(POISON);
    }

    private void checkError() throws IOException {
        IOException e = error;
        if (e != null) throw e;
    }

    private synchronized void fail(IOException e) throws IOException {
        if (error == null) error = e;
        throw error;
    }

    private void waitErr() {
        try { errThread.join(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] f = queue.take();
                if (f == POISON) return;
                if (error != null) continue; // descarta o resto
                try {
                    stdin.write(f);
                    written.incrementAndGet();
                } catch (IOException e) {
                    waitErr();
                    synchronized (this) {
                        if (error == null) error = new IOException("Não foi possível enviar o quadro ao FFmpeg. " + stderrTail(), e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void readErr(InputStream in) {
        byte[] buf = new byte[1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (this) {
                    stderr.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (stderr.length() > STDERR_KEEP * 2) stderr.delete(0, stderr.length() - STDERR_KEEP);
                }
            }
        } catch (IOException ignored) { /* processo encerrado */ }
    }

    public boolean isFinished() { return finished; }
}
