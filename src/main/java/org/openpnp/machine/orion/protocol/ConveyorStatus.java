package org.openpnp.machine.orion.protocol;

/** The conveyor's C_STATUS reply (14 bytes), see Conveyor/PROTOCOL.md section 5.3. */
public class ConveyorStatus {
    public static final int IDLE = 0;
    public static final int BUSY = 1;
    public static final int ERROR = 2;
    public static final int BOARD_AT_ENTRY = 3;

    public static final int UNKNOWN_LENGTH = -1;
    public static final int UNKNOWN_WIDTH = 0xFFFF;

    public int state;
    public int flags;
    public int lastErr;
    /** Leading edge of the loaded board relative to the entry sensor, 0.01 mm. */
    public int pos;
    /** Board length, 0.01 mm, or {@link #UNKNOWN_LENGTH}. */
    public long length;
    /** Width 0.1 mm or {@link #UNKNOWN_WIDTH}. */
    public int width;
    public long time = System.currentTimeMillis();

    public static ConveyorStatus parse(OrionFrame f) {
        ConveyorStatus s = new ConveyorStatus();
        s.state = f.u8(0);
        s.flags = f.u16(1);
        s.lastErr = f.u8(3);
        s.pos = f.s32(4);
        long len = f.u32(8);
        s.length = len == 0xFFFFFFFFL ? UNKNOWN_LENGTH : len;
        s.width = f.u16(12);
        return s;
    }

    public OrionFrame toFrame(int address) {
        byte[] p = new byte[14];
        p[0] = (byte) state;
        p[1] = (byte) (flags >> 8);
        p[2] = (byte) flags;
        p[3] = (byte) lastErr;
        for (int i = 0; i < 4; i++) {
            p[4 + i] = (byte) (pos >> (24 - 8 * i));
        }
        long l = length < 0 ? 0xFFFFFFFFL : length;
        for (int i = 0; i < 4; i++) {
            p[8 + i] = (byte) (l >> (24 - 8 * i));
        }
        p[12] = (byte) (width >> 8);
        p[13] = (byte) width;
        return new OrionFrame(address, OrionCommand.C_STATUS, p);
    }

    private boolean flag(int bit) {
        return (flags & (1 << bit)) != 0;
    }

    public boolean busy() {
        return state == BUSY;
    }

    public boolean entryBlocked() {
        return flag(0);
    }

    public boolean stopSensor() {
        return flag(1);
    }

    public boolean clamped() {
        return flag(2);
    }

    public boolean stopperOut() {
        return flag(3);
    }

    public boolean posValid() {
        return flag(4);
    }

    public boolean lengthValid() {
        return flag(5);
    }

    public boolean widthHomed() {
        return flag(6);
    }

    public boolean driverFault() {
        return flag(7);
    }

    public boolean boardLoaded() {
        return flag(8);
    }

    /** A second board is tracked behind the loaded one (or, with nothing loaded, the next board is ready). */
    public boolean boardWaiting() {
        return flag(9);
    }

    public boolean widthConfirmed() {
        return flag(10);
    }

    public boolean waitingLengthValid() {
        return flag(11);
    }

    /** The unloaded board has not left the sensor yet (being taken out). */
    public boolean boardLeaving() {
        return flag(12);
    }

    /** Nothing loaded and a board just inserted: the host should offer to load it. */
    public boolean insertedBoard() {
        return state == BOARD_AT_ENTRY && !boardLoaded() && !boardLeaving();
    }

    public double lengthMm() {
        return length < 0 ? Double.NaN : length / 100.0;
    }

    public double widthMm() {
        return width == UNKNOWN_WIDTH ? Double.NaN : width / 10.0;
    }

    public double posMm() {
        return pos / 100.0;
    }
}
