package org.openpnp.machine.orion.protocol;

/** Decoded CMD_GET_STATUS reply. */
public class OrionStatus {
    public int angleRaw;
    public int as5600Status;
    public boolean faultActive;
    public OrionError lastMoveError = OrionError.NONE;
    /** -1 when the firmware doesn't report it (alpha02/03). */
    public int iMonRaw = -1;
    public boolean relayEngaged;

    public boolean magnetDetected() {
        return (as5600Status & 0x20) != 0;
    }

    static OrionStatus parse(OrionFrame f) {
        OrionStatus s = new OrionStatus();
        s.angleRaw = f.u16(0);
        s.as5600Status = f.u8(2);
        s.faultActive = f.u8(3) != 0;
        s.lastMoveError = OrionError.fromCode(f.u8(4));
        if (f.payload.length >= 8) {
            s.iMonRaw = f.u16(5);
            s.relayEngaged = f.u8(7) != 0;
        }
        return s;
    }

    @Override
    public String toString() {
        return String.format("angle=%d magnet=%b fault=%b lastErr=%s iMonRaw=%d relay=%b", angleRaw,
                magnetDetected(), faultActive, lastMoveError, iMonRaw, relayEngaged);
    }
}
