package org.openpnp.machine.orion.protocol;

import java.util.List;

/**
 * Carries raw Orion frames to one RS485 rail and brings the replies back. Implementations:
 * a USB-RS485 adapter on a serial port, or the multi-rail host board (line protocol over USB CDC).
 */
public interface OrionTransport {
    /** Human readable description, e.g. "COM5 @ 9600" or "host board COM7". */
    String describe();

    /** Number of rails behind this transport (adapter: 1, host board: 2). */
    int getRailCount();

    boolean isOpen();

    void open() throws OrionException;

    void close();

    /**
     * Send a frame on a rail and collect every valid reply frame that arrives.
     *
     * @param rail      0-based rail index
     * @param request   frame to send
     * @param timeoutMs time to wait for the first reply
     * @param collectMs extra time to keep listening after the first reply (0 = return on first reply).
     *                  Used for DISCOVER where several feeders answer with random jitter.
     */
    List<OrionFrame> exchange(int rail, OrionFrame request, int timeoutMs, int collectMs)
            throws OrionException;

    /** Host-board only: switch a rail's power on/off. Adapter transports return false. */
    default boolean setRailPower(int rail, boolean on) throws OrionException {
        return false;
    }
}
