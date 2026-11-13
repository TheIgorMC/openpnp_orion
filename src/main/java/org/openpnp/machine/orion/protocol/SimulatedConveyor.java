package org.openpnp.machine.orion.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Behaviour model of the conveyor controller (Conveyor/PROTOCOL.md) for the unit tests and the offline demo.
 * Motion is accepted at once and finishes after a delay ({@link #instant} = at the next frame handled).
 */
public class SimulatedConveyor {
    public final int rail;
    public final int address = OrionCommand.ADDR_CONVEYOR;
    /** True: a started move is finished before the next command is handled (tests). False: it takes real time. */
    public boolean instant = true;

    // ---- the physical world
    /** True length (0.01 mm) of the board sitting at the entry sensor, or 0 = none. */
    private long entryBoardLen;
    private final Map<Integer, Integer> cfg = new HashMap<>();

    // ---- controller state
    private int width = 0xFFFF;
    private boolean widthConfirmed;
    private boolean widthHomed;
    private boolean clamp;
    private boolean stopper;
    private boolean loaded;
    private boolean lengthKnown;
    private long length;
    private boolean posValid;
    private int pos;
    private boolean waiting;
    private int posNext;
    private boolean nextLenKnown;
    private boolean leaving;
    private int lastErr;
    private boolean errState;
    private long busyUntil;
    private Runnable pending;
    public boolean present = true;
    public int[] caps = {0, 0x0F};

    public SimulatedConveyor(int rail) {
        this.rail = rail;
        cfg.put(OrionConveyor.CFG_STOP_DISTANCE, 25000);
        cfg.put(OrionConveyor.CFG_PUSH, 200);
        cfg.put(OrionConveyor.CFG_EXPOSE, 3000);
        cfg.put(OrionConveyor.CFG_SPEED_FEED, 800);
        cfg.put(OrionConveyor.CFG_SPEED_SLOW, 150);
        cfg.put(OrionConveyor.CFG_STOPPER_TYPE, 1);
        cfg.put(OrionConveyor.CFG_CLAMP_MS, 600);
        cfg.put(OrionConveyor.CFG_WIDTH_MIN, 500);
        cfg.put(OrionConveyor.CFG_WIDTH_MAX, 2500);
        cfg.put(OrionConveyor.CFG_UNLOAD_DIR, 0);
        cfg.put(OrionConveyor.CFG_EXIT_DISTANCE, 35000);
        width = 500;
    }

    private int stopDistance() {
        return cfg.get(OrionConveyor.CFG_STOP_DISTANCE);
    }

    // ------------------------------------------------------------------ the user's hands

    /** A board (length in mm) is pushed into the entry sensor by hand. */
    public synchronized void insertBoard(double lengthMm) {
        entryBoardLen = Math.round(lengthMm * 100);
        if (loaded || waiting) {
            waiting = true;
            posNext = 0;
            nextLenKnown = false;
        } else {
            posValid = true;
            pos = 0;
        }
    }

    /** The board at the entry is taken out by hand. */
    public synchronized void removeBoardAtEntry() {
        entryBoardLen = 0;
        leaving = false;
        waiting = false;
        if (!loaded) {
            posValid = false;
        }
    }

    /** Power off and on: the width, clamp, board and stopper are remembered, the rest is not. */
    public synchronized void powerCycle() {
        pending = null;
        busyUntil = 0;
        errState = false;
        lastErr = 0;
        widthConfirmed = false;
        widthHomed = false;
        waiting = false;
        leaving = false;
        if (clamp && loaded) {
            posValid = true;
            pos = stopDistance();
        } else if (!loaded) {
            posValid = false;
        }
    }

    // ------------------------------------------------------------------ state read-out

    /** The beam is interrupted: a board lies over the sensor. */
    private boolean entryBlocked() {
        return entryBoardLen > 0 && (waiting ? posNext < entryBoardLen : (!loaded || leaving));
    }

    private boolean busy() {
        return pending != null || System.currentTimeMillis() < busyUntil;
    }

    private void tick() {
        if (pending != null && (instant || System.currentTimeMillis() >= busyUntil)) {
            Runnable r = pending;
            pending = null;
            busyUntil = 0;
            r.run();
        }
    }

    public synchronized ConveyorStatus status() {
        tick();
        ConveyorStatus s = new ConveyorStatus();
        boolean blocked = entryBlocked();
        s.state = busy() ? ConveyorStatus.BUSY : errState ? ConveyorStatus.ERROR
                : blocked && !leaving ? ConveyorStatus.BOARD_AT_ENTRY : ConveyorStatus.IDLE;
        int f = 0;
        f |= blocked ? 1 : 0;
        f |= clamp ? 1 << 2 : 0;
        f |= stopper ? 1 << 3 : 0;
        f |= posValid ? 1 << 4 : 0;
        f |= lengthKnown ? 1 << 5 : 0;
        f |= widthHomed ? 1 << 6 : 0;
        f |= loaded ? 1 << 8 : 0;
        f |= waiting ? 1 << 9 : 0;
        f |= widthConfirmed ? 1 << 10 : 0;
        f |= nextLenKnown ? 1 << 11 : 0;
        f |= leaving ? 1 << 12 : 0;
        s.flags = f;
        s.lastErr = lastErr;
        s.pos = posValid ? pos : 0;
        s.length = lengthKnown ? length : ConveyorStatus.UNKNOWN_LENGTH;
        s.width = width;
        return s;
    }

    /** What the controller reports, for tests and the demo. */
    public synchronized ConveyorStatus peek() {
        return status();
    }

    // ------------------------------------------------------------------ commands

    private OrionFrame ack(byte... p) {
        return new OrionFrame(address, OrionCommand.ACK, p);
    }

    private OrionFrame nack(OrionError e) {
        return new OrionFrame(address, OrionCommand.NACK, (byte) e.code);
    }

    private OrionFrame start(long ms, Runnable done) {
        errState = false;
        lastErr = 0;
        pending = done;
        busyUntil = System.currentTimeMillis() + Math.max(1, ms);
        return ack();
    }

    private long travelMs(int units) {
        // 0.01 mm at SPEED_FEED (0.1 mm/s): scaled so a demo move takes a second or two
        return Math.min(2500, 300 + Math.abs(units) / 20);
    }

    private void fail(OrionError e) {
        lastErr = e.code;
        errState = true;
    }

    public synchronized OrionFrame handle(OrionFrame req) {
        if (!present) {
            return null;
        }
        tick();
        switch (req.command) {
            case OrionCommand.PING:
                return new OrionFrame(address, OrionCommand.PONG);
            case OrionCommand.C_GET_INFO:
                return new OrionFrame(address, OrionCommand.C_INFO, (byte) 0x02, (byte) 0, (byte) 1,
                        (byte) (caps[1] >> 8), (byte) caps[1]);
            case OrionCommand.C_GET_STATUS:
                return status().toFrame(address);
            case OrionCommand.C_STOP:
                pending = null;
                busyUntil = 0;
                return ack();
            case OrionCommand.C_IDENTIFY:
                return ack();
            case OrionCommand.C_SET_CFG:
                cfg.put(req.u8(0), req.s32(1));
                return ack();
            case OrionCommand.C_GET_CFG: {
                int v = cfg.getOrDefault(req.u8(0), 0);
                return new OrionFrame(address, OrionCommand.C_CFG, (byte) req.u8(0), (byte) (v >> 24), (byte) (v >> 16),
                        (byte) (v >> 8), (byte) v);
            }
            default:
                break;
        }
        if (busy() && req.command != OrionCommand.C_GET_STATUS) {
            return nack(OrionError.BUSY);
        }
        switch (req.command) {
            case OrionCommand.C_MOVE:
                return move(req.s32(0));
            case OrionCommand.C_LOAD:
                return loadOrMeasure((req.u8(0) & 1) == 0);
            case OrionCommand.C_MEASURE:
                return loadOrMeasure(false);
            case OrionCommand.C_GOTO_STOP:
                if (!loaded || !posValid) {
                    return nack(OrionError.NO_BOARD);
                }
                return start(travelMs(stopDistance() - pos), () -> pos = stopDistance());
            case OrionCommand.C_UNLOAD:
                return unload();
            case OrionCommand.C_CLEAR:
                if (entryBlocked() || clamp) {
                    return nack(OrionError.BOARD_PRESENT);
                }
                loaded = false;
                waiting = false;
                posValid = false;
                lengthKnown = false;
                return ack();
            case OrionCommand.C_SET_WIDTH:
                return setWidth(req.u16(0), req.u8(2) != 0);
            case OrionCommand.C_HOME_WIDTH: {
                OrionFrame r = widthGuard(req.u8(0) != 0);
                if (r != null) {
                    return r;
                }
                return start(900, () -> {
                    width = 500;
                    widthConfirmed = true;
                    widthHomed = true;
                });
            }
            case OrionCommand.C_DEFINE_WIDTH:
                width = req.u16(0);
                widthConfirmed = true;
                return ack();
            case OrionCommand.C_CLAMP:
                if (req.u8(0) != 0 && !(loaded && posValid && Math.abs(pos - stopDistance()) <= 100)) {
                    return nack(OrionError.NO_BOARD);
                }
                final boolean on = req.u8(0) != 0;
                return start(cfg.get(OrionConveyor.CFG_CLAMP_MS), () -> clamp = on);
            case OrionCommand.C_STOPPER:
                if (cfg.get(OrionConveyor.CFG_STOPPER_TYPE) == 0) {
                    return nack(OrionError.BAD_PARAM);
                }
                final boolean out = req.u8(0) != 0;
                return start(250, () -> stopper = out);
            default:
                return null;
        }
    }

    private OrionFrame move(int delta) {
        if (clamp) {
            return nack(OrionError.CLAMPED);
        }
        if (delta < 0 && waiting && entryBlocked()) {
            return nack(OrionError.ENTRY_BLOCKED);
        }
        return start(travelMs(delta), () -> {
            if (loaded || entryBoardLen > 0) {
                pos += delta;
            }
            if (waiting) {
                posNext += delta;
            }
        });
    }

    private OrionFrame loadOrMeasure(boolean position) {
        if (loaded) {
            return nack(OrionError.BOARD_PRESENT);
        }
        long len = entryBoardLen;
        if (len <= 0 && !waiting) {
            return nack(OrionError.NO_BOARD);
        }
        if (len > stopDistance()) {
            return start(1200, () -> fail(OrionError.TOO_LONG));
        }
        return start(travelMs(stopDistance()), () -> {
            loaded = true;
            waiting = false;
            lengthKnown = true;
            length = len;
            posValid = true;
            pos = position ? stopDistance() : (int) len;
            entryBoardLen = 0;
        });
    }

    private OrionFrame unload() {
        if (!loaded) {
            return nack(OrionError.NO_BOARD);
        }
        if (clamp) {
            return nack(OrionError.CLAMPED);
        }
        if (cfg.get(OrionConveyor.CFG_UNLOAD_DIR) == 0) {
            if (waiting && entryBlocked()) {
                return nack(OrionError.ENTRY_BLOCKED);
            }
            final long len = length;
            return start(travelMs(stopDistance()), () -> {
                loaded = false;
                lengthKnown = false;
                posValid = false;
                stopper = false;
                entryBoardLen = len; // the board's rear sticks out through the sensor
                leaving = true;
            });
        }
        int exit = cfg.get(OrionConveyor.CFG_EXIT_DISTANCE);
        if (waiting && posNext + exit > stopDistance()) {
            return nack(OrionError.NEXT_OVERRUN);
        }
        return start(travelMs(exit), () -> {
            loaded = false;
            lengthKnown = false;
            posValid = false;
            stopper = false;
            if (waiting) {
                posNext += exit;
                if (exit >= entryBoardLen) {
                    nextLenKnown = true;
                }
            }
        });
    }

    private OrionFrame widthGuard(boolean clear) {
        if (entryBlocked() || clamp) {
            return nack(OrionError.BOARD_PRESENT);
        }
        if ((loaded || waiting) && !clear) {
            return nack(OrionError.BOARD_PRESENT);
        }
        if (clear) {
            loaded = false;
            waiting = false;
            posValid = false;
            lengthKnown = false;
        }
        return null;
    }

    private OrionFrame setWidth(int w, boolean clear) {
        if (w < cfg.get(OrionConveyor.CFG_WIDTH_MIN) || w > cfg.get(OrionConveyor.CFG_WIDTH_MAX)) {
            return nack(OrionError.WIDTH_LIMIT);
        }
        if (width == 0xFFFF) {
            return nack(OrionError.WIDTH_NOT_HOMED);
        }
        OrionFrame r = widthGuard(clear);
        if (r != null) {
            return r;
        }
        return start(600 + Math.abs(w - width), () -> width = w);
    }
}
