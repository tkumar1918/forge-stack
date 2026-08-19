package dev.tushar.forgestack.sandbox;

/**
 * A piece of a running command's output, as it happens.
 *
 * <p>Streamed rather than returned whole because §11 calls unbounded tool output the context-window
 * killer, and it is the heap killer too: a test suite that prints a hundred megabytes must reach blob
 * storage without ever being a single string in this process. The consumer decides what to persist
 * and what to summarise; nothing here accumulates.
 */
public record OutputChunk(Stream stream, byte[] bytes) {

    public enum Stream {
        STDOUT,
        STDERR
    }
}
