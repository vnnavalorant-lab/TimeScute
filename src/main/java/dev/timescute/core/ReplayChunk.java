package dev.timescute.core;

/** Referência a um bloco de dados dentro do arquivo (para pular direto a um instante). */
public record ReplayChunk(long offset, long firstMs, long lastMs, int records) { }
