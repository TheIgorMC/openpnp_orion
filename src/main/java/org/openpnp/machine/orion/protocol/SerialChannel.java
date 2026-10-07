package org.openpnp.machine.orion.protocol;

/** Minimal byte pipe so transports can be tested without a real serial port. */
public interface SerialChannel {
    void open() throws OrionException;

    void close();

    boolean isOpen();

    void write(byte[] data) throws OrionException;

    /** Read whatever is available, waiting up to timeoutMs for at least one byte. Returns 0 on timeout. */
    int read(byte[] buffer, int timeoutMs) throws OrionException;

    /** Drop anything waiting in the input buffer. */
    void flushInput();

    String describe();
}
