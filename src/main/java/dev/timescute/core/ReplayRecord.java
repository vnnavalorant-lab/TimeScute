package dev.timescute.core;

/** Um item da gravação: pacote do servidor, amostra do jogador ou marcador. */
public record ReplayRecord(long timeMs, int kind, byte[] data) { }
