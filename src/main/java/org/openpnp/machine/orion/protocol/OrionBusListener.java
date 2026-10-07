package org.openpnp.machine.orion.protocol;

/** Receives every frame that crosses a rail, for the debug tab. */
public interface OrionBusListener {
    enum Direction { TX, RX, INFO, ERROR }

    void onTraffic(int rail, Direction direction, String text);
}
