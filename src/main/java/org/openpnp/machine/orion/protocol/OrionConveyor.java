package org.openpnp.machine.orion.protocol;

import java.util.function.Consumer;

/**
 * The board conveyor on its fixed address (see Conveyor/PROTOCOL.md). Motion commands are accepted at once and run
 * in the controller; {@link #awaitIdle} polls the status until they are done.
 */
public class OrionConveyor {
    public static final int CFG_STOP_DISTANCE = 0x01;
    public static final int CFG_PUSH = 0x02;
    public static final int CFG_EXPOSE = 0x03;
    public static final int CFG_SPEED_FEED = 0x04;
    public static final int CFG_SPEED_SLOW = 0x05;
    public static final int CFG_STOPPER_TYPE = 0x0E;
    public static final int CFG_CLAMP_MS = 0x13;
    public static final int CFG_WIDTH_MIN = 0x15;
    public static final int CFG_WIDTH_MAX = 0x16;
    public static final int CFG_UNLOAD_DIR = 0x1B;
    public static final int CFG_EXIT_DISTANCE = 0x1C;

    private final OrionBus bus;
    private final int address;
    private int capabilities;

    public OrionConveyor(OrionBus bus, int address) {
        this.bus = bus;
        this.address = address;
    }

    public OrionBus getBus() {
        return bus;
    }

    public int getAddress() {
        return address;
    }

    public boolean hasWidthAxis() {
        return (capabilities & 1) != 0;
    }

    public boolean hasStopper() {
        return (capabilities & 2) != 0;
    }

    public boolean hasStopSensor() {
        return (capabilities & 4) != 0;
    }

    public boolean hasClamp() {
        return (capabilities & 8) != 0;
    }

    private OrionFrame ask(int cmd, int timeoutMs, byte... payload) throws OrionException {
        return bus.request(address, cmd, payload, timeoutMs);
    }

    private void ack(int cmd, byte... payload) throws OrionException {
        OrionFrame f = ask(cmd, OrionBus.DEFAULT_TIMEOUT_MS, payload);
        if (f.command != OrionCommand.ACK) {
            throw new OrionException(OrionException.Kind.UNEXPECTED_REPLY, "Expected ACK for "
                    + OrionCommand.name(cmd) + " but got " + OrionCommand.name(f.command));
        }
    }

    /** @return {devType, fwMajor, fwMinor, caps} */
    public int[] getInfo() throws OrionException {
        OrionFrame f = ask(OrionCommand.C_GET_INFO, OrionBus.DEFAULT_TIMEOUT_MS);
        if (f.command != OrionCommand.C_INFO || f.u8(0) != 0x02) {
            throw new OrionException(OrionException.Kind.UNEXPECTED_REPLY, "Address " + address
                    + " is not a conveyor");
        }
        capabilities = f.u16(3);
        return new int[] {f.u8(0), f.u8(1), f.u8(2), capabilities};
    }

    public ConveyorStatus getStatus() throws OrionException {
        OrionFrame f = ask(OrionCommand.C_GET_STATUS, OrionBus.DEFAULT_TIMEOUT_MS);
        if (f.command != OrionCommand.C_STATUS || f.payload.length < 14) {
            throw new OrionException(OrionException.Kind.UNEXPECTED_REPLY, "Bad conveyor status reply");
        }
        return ConveyorStatus.parse(f);
    }

    public void stop() throws OrionException {
        ack(OrionCommand.C_STOP);
    }

    public void identify(int blinks) throws OrionException {
        ack(OrionCommand.C_IDENTIFY, (byte) blinks);
    }

    public void setConfig(int id, int value) throws OrionException {
        ack(OrionCommand.C_SET_CFG, (byte) id, (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
                (byte) value);
    }

    public int getConfig(int id) throws OrionException {
        OrionFrame f = ask(OrionCommand.C_GET_CFG, OrionBus.DEFAULT_TIMEOUT_MS, (byte) id);
        return f.s32(1);
    }

    private static byte[] be32(int v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    /** Relative belt move in 0.01 mm. */
    public void move(int delta) throws OrionException {
        ack(OrionCommand.C_MOVE, be32(delta));
    }

    public void load(boolean skipPositioning) throws OrionException {
        ack(OrionCommand.C_LOAD, (byte) (skipPositioning ? 1 : 0));
    }

    public void measure() throws OrionException {
        ack(OrionCommand.C_MEASURE);
    }

    public void gotoStop() throws OrionException {
        ack(OrionCommand.C_GOTO_STOP);
    }

    public void unload() throws OrionException {
        ack(OrionCommand.C_UNLOAD);
    }

    /** The user states that nothing is on the conveyor. */
    public void clear() throws OrionException {
        ack(OrionCommand.C_CLEAR);
    }

    /** @param widthTenthsMm 0.1 mm; clear = the user confirmed the belt is empty */
    public void setWidth(int widthTenthsMm, boolean clear) throws OrionException {
        ack(OrionCommand.C_SET_WIDTH, (byte) (widthTenthsMm >> 8), (byte) widthTenthsMm, (byte) (clear ? 1 : 0));
    }

    public void homeWidth(boolean clear) throws OrionException {
        ack(OrionCommand.C_HOME_WIDTH, (byte) (clear ? 1 : 0));
    }

    /** No motion: the width was adjusted by hand to this value. */
    public void defineWidth(int widthTenthsMm) throws OrionException {
        ack(OrionCommand.C_DEFINE_WIDTH, (byte) (widthTenthsMm >> 8), (byte) widthTenthsMm);
    }

    public void clamp(boolean on) throws OrionException {
        ack(OrionCommand.C_CLAMP, (byte) (on ? 1 : 0));
    }

    public void stopper(boolean extend) throws OrionException {
        ack(OrionCommand.C_STOPPER, (byte) (extend ? 1 : 0));
    }

    /**
     * Poll the status until the controller is no longer busy. A move that ended with an error throws it.
     *
     * @param onStatus called with every status polled (may be null)
     */
    public ConveyorStatus awaitIdle(long timeoutMs, Consumer<ConveyorStatus> onStatus) throws OrionException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            ConveyorStatus s = getStatus();
            if (onStatus != null) {
                onStatus.accept(s);
            }
            if (!s.busy()) {
                if (s.state == ConveyorStatus.ERROR) {
                    OrionError e = OrionError.fromCode(s.lastErr);
                    throw new OrionException(OrionException.Kind.NACK, e, "Conveyor: " + e.description);
                }
                return s;
            }
            if (System.currentTimeMillis() > end) {
                throw new OrionException(OrionException.Kind.TIMEOUT, "The conveyor is still busy after "
                        + timeoutMs / 1000 + " s");
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new OrionException(OrionException.Kind.TIMEOUT, "interrupted");
            }
        }
    }

    /** Stopper out (if fitted), load, then optionally clamp. Blocks until done; throws what went wrong. */
    public ConveyorStatus loadBoard(boolean clampAfter, Consumer<ConveyorStatus> onStatus) throws OrionException {
        if (hasStopper()) {
            stopper(true);
            awaitIdle(10000, onStatus);
        }
        load(false);
        awaitIdle(60000, onStatus);
        if (clampAfter && hasClamp()) {
            clamp(true);
            return awaitIdle(10000, onStatus);
        }
        return getStatus();
    }

    /** Release the clamp, then unload in the configured direction. Blocks until done. */
    public ConveyorStatus unloadBoard(Consumer<ConveyorStatus> onStatus) throws OrionException {
        ConveyorStatus s = getStatus();
        if (s.clamped()) {
            clamp(false);
            awaitIdle(10000, onStatus);
        }
        unload();
        return awaitIdle(60000, onStatus);
    }
}
