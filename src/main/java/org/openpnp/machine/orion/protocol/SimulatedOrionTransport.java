package org.openpnp.machine.orion.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * In-memory multi-rail bus with virtual feeders. Used by the unit tests and as an offline mode so
 * the GUI can be tried without hardware. Behaviour mirrors the v0.02b firmware at protocol level.
 */
public class SimulatedOrionTransport implements OrionTransport {
    public static class SimFeeder {
        public final int rail;
        public final byte[] serial;
        public int address = 0;
        public int nonce;
        public boolean present = true;
        public int componentId = 0xFFFF; // unset, like the firmware
        public int zero = 0;
        public int halfTeeth = 0;
        public int tapeWidth = 8;
        public int peelTimeMs = 0xFFFF;
        public int peelRate = 0xFFFF;
        public int angle = 0;
        public int posRaw = 0xFFFF;
        public int lastAddr = 0;
        public int feeds = 0;
        public int peels = 0;
        public OrionError failNextMove = OrionError.NONE;
        public boolean hasSerial = true;

        SimFeeder(int rail, byte[] serial, int nonce) {
            this.rail = rail;
            this.serial = serial;
            this.nonce = nonce;
        }
    }

    private final int rails;
    private final List<SimFeeder> feeders = new ArrayList<>();
    private final boolean[] power;
    private final Random rnd = new Random(1);
    private boolean open;
    public final List<String> log = new ArrayList<>();

    public SimulatedOrionTransport(int rails) {
        this.rails = rails;
        this.power = new boolean[rails];
        java.util.Arrays.fill(power, true);
    }

    public SimFeeder addFeeder(int rail, int serialSeed) {
        byte[] s = new byte[16];
        for (int i = 0; i < 16; i++) {
            s[i] = (byte) (serialSeed * 31 + i * 7);
        }
        SimFeeder f = new SimFeeder(rail, s, 0x1000 + feeders.size() * 17);
        feeders.add(f);
        return f;
    }

    public List<SimFeeder> getFeeders() {
        return feeders;
    }

    /** A power cycle: the feeder forgets its bus address and picks a new nonce. */
    public void reboot(SimFeeder f) {
        f.address = 0;
        f.nonce = (f.nonce + 0x111) & 0xFFFF;
    }

    @Override
    public String describe() {
        return "Simulated bus (" + rails + " rails)";
    }

    @Override
    public int getRailCount() {
        return rails;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean setRailPower(int rail, boolean on) {
        power[rail] = on;
        return true;
    }

    @Override
    public synchronized List<OrionFrame> exchange(int rail, OrionFrame req, int timeoutMs,
            int collectMs) throws OrionException {
        if (!open) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "simulated bus not open");
        }
        List<OrionFrame> out = new ArrayList<>();
        if (!power[rail]) {
            return out;
        }
        List<SimFeeder> targets = new ArrayList<>();
        for (SimFeeder f : feeders) {
            if (f.rail == rail && f.present
                    && (req.address == 0 || f.address == req.address)) {
                targets.add(f);
            }
        }
        // Two feeders on one address both answer: garbled on a real bus, so nothing valid arrives.
        if (req.address != 0 && targets.size() > 1) {
            return out;
        }
        for (SimFeeder f : targets) {
            OrionFrame r = handle(f, req);
            if (r != null) {
                out.add(r);
            }
        }
        // Real discover replies are jittered; shuffle to mimic arrival order.
        if (req.command == OrionCommand.DISCOVER) {
            java.util.Collections.shuffle(out, rnd);
        }
        return out;
    }

    private static byte[] be16(int v) {
        return new byte[] {(byte) (v >> 8), (byte) v};
    }

    private OrionFrame ackFrame(SimFeeder f, byte... p) {
        return new OrionFrame(f.address, OrionCommand.ACK, p);
    }

    private OrionFrame nack(SimFeeder f, OrionError e) {
        return new OrionFrame(f.address, OrionCommand.NACK, (byte) e.code);
    }

    private OrionFrame handle(SimFeeder f, OrionFrame req) {
        boolean unassigned = f.address == 0;
        switch (req.command) {
            case OrionCommand.DISCOVER:
                if (!unassigned) {
                    return null;
                }
                return new OrionFrame(0, OrionCommand.DISCOVER_HERE, (byte) (f.nonce >> 8),
                        (byte) f.nonce, (byte) (f.componentId >> 8), (byte) f.componentId,
                        (byte) f.tapeWidth, (byte) f.lastAddr, (byte) (f.posRaw >> 8),
                        (byte) f.posRaw);
            case OrionCommand.ASSIGN_ADDR:
                if (!unassigned || req.u16(0) != f.nonce) {
                    return null;
                }
                f.address = req.u8(2);
                f.lastAddr = f.address;
                return ackFrame(f);
            default:
                break;
        }
        if (unassigned) {
            return null;
        }
        switch (req.command) {
            case OrionCommand.PING:
                return new OrionFrame(f.address, OrionCommand.PONG);
            case OrionCommand.GET_SERIAL:
                return f.hasSerial ? new OrionFrame(f.address, OrionCommand.SERIAL_INFO, f.serial)
                        : nack(f, OrionError.I2C);
            case OrionCommand.GET_COMPONENT:
                return new OrionFrame(f.address, OrionCommand.COMPONENT_INFO,
                        concat(be16(f.componentId), be16(f.zero), new byte[] {(byte) f.halfTeeth}));
            case OrionCommand.GET_HW_INFO:
                return new OrionFrame(f.address, OrionCommand.HW_INFO, (byte) f.tapeWidth);
            case OrionCommand.SET_HW_INFO:
                f.tapeWidth = req.u8(0);
                return ackFrame(f);
            case OrionCommand.SET_COMPONENT:
                if (req.u16(0) != f.componentId) {
                    f.zero = 0;
                    f.halfTeeth = 0;
                }
                f.componentId = req.u16(0);
                return ackFrame(f);
            case OrionCommand.SET_PITCH_MM:
                if (req.u8(0) < 2 || req.u8(0) > 24 || req.u8(0) % 2 != 0) {
                    return nack(f, OrionError.BAD_PARAM);
                }
                f.halfTeeth = req.u8(0) / 2;
                return ackFrame(f);
            case OrionCommand.ZERO_HERE:
                f.zero = f.angle;
                return ackFrame(f);
            case OrionCommand.RESET_CONFIG:
                f.zero = 0;
                f.halfTeeth = 0;
                return ackFrame(f);
            case OrionCommand.FEED_NEXT:
            case OrionCommand.FEED_BACK:
                if (f.halfTeeth == 0) {
                    return nack(f, OrionError.NOT_READY);
                }
                if (f.failNextMove != OrionError.NONE) {
                    OrionError e = f.failNextMove;
                    f.failNextMove = OrionError.NONE;
                    return nack(f, e);
                }
                f.feeds += req.command == OrionCommand.FEED_NEXT ? 1 : -1;
                f.angle = (f.angle + (req.command == OrionCommand.FEED_NEXT ? 1 : -1) * f.halfTeeth * 100) & 0xFFF;
                return ackFrame(f);
            case OrionCommand.JOG:
                f.angle = (f.angle + (short) req.u16(0)) & 0xFFF;
                return ackFrame(f, be16(f.angle));
            case OrionCommand.PEEL:
                if (req.payload.length == 1 && f.peelTimeMs == 0xFFFF) {
                    return nack(f, OrionError.NOT_READY);
                }
                f.peels++;
                return ackFrame(f);
            case OrionCommand.SET_POSITION:
                f.posRaw = req.u16(0);
                return ackFrame(f, req.payload);
            case OrionCommand.GET_POSITION:
                return new OrionFrame(f.address, OrionCommand.POSITION_INFO,
                        concat(be16(f.posRaw), new byte[] {(byte) f.lastAddr}));
            case OrionCommand.SET_PEEL_TIME:
                if (req.u16(0) < 10 || req.u16(0) > 5000) {
                    return nack(f, OrionError.BAD_PARAM);
                }
                f.peelTimeMs = req.u16(0);
                return ackFrame(f, req.payload);
            case OrionCommand.GET_PEEL_TIME:
                return new OrionFrame(f.address, OrionCommand.PEEL_TIME_INFO, be16(f.peelTimeMs));
            case OrionCommand.SET_PEEL_RATE:
                if (req.u16(0) != 0 && (req.u16(0) < 5 || req.u16(0) > 5000)) {
                    return nack(f, OrionError.BAD_PARAM);
                }
                f.peelRate = req.u16(0);
                return ackFrame(f, req.payload);
            case OrionCommand.GET_PEEL_RATE:
                return new OrionFrame(f.address, OrionCommand.PEEL_RATE_INFO, be16(f.peelRate));
            case OrionCommand.GET_STATUS:
                return new OrionFrame(f.address, OrionCommand.STATUS_INFO, concat(be16(f.angle),
                        new byte[] {0x20, 0, 0}, be16(100), new byte[] {1}));
            case OrionCommand.STOP:
            case OrionCommand.IDENTIFY:
            case OrionCommand.SET_LED_BRIGHTNESS:
                return ackFrame(f);
            case OrionCommand.I2C_SCAN:
                return new OrionFrame(f.address, OrionCommand.I2C_SCAN_INFO, (byte) 0x36,
                        (byte) 0x50, (byte) 0x58);
            default:
                return null;
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}
