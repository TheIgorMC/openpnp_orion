package org.openpnp.machine.orion.protocol;

/** What the host knows about one feeder on a rail. Mutable, updated by scans and polls. */
public class OrionDeviceInfo {
    public enum State {
        /** Answered the last ping. */
        ONLINE,
        /** Known from earlier but no longer answering (unplugged, rebooted, or rail off). */
        LOST,
        /** Two different serials claimed the same address, or a reply came from the wrong unit. */
        CONFLICT,
        /** Answers, but has no readable serial so it can't be tied to a configured feeder. */
        NO_SERIAL
    }

    public int rail;
    public int address;
    /** 32 hex chars, or "NONCE-xxxx" if the unit has no serial. */
    public String serial;
    public int componentId;
    public int tapeWidthMm;
    /** Slot position X stored on the unit, mm along the rail. NaN when unset. */
    public double slotXMm = Double.NaN;
    /** Address this unit held before the last power cycle, 0 = never assigned. */
    public int lastAddress;
    public State state = State.ONLINE;
    public long lastSeenMs;
    public String note = "";

    public OrionDeviceInfo copy() {
        OrionDeviceInfo c = new OrionDeviceInfo();
        c.rail = rail;
        c.address = address;
        c.serial = serial;
        c.componentId = componentId;
        c.tapeWidthMm = tapeWidthMm;
        c.slotXMm = slotXMm;
        c.lastAddress = lastAddress;
        c.state = state;
        c.lastSeenMs = lastSeenMs;
        c.note = note;
        return c;
    }

    @Override
    public String toString() {
        return String.format("rail %d addr %d %s %s", rail, address, serial, state);
    }
}
