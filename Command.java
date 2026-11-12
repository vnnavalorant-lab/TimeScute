package dev.timescute.core;

/** Operação reversível sobre o projeto. */
public interface Command {
    void apply(Project p);
    void revert(Project p);
    String label();
}
