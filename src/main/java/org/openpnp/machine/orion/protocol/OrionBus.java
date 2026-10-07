package org.openpnp.machine.orion.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One RS485 rail: request/response with retries, addressing (discover/assign), scanning and
 * conflict tracking, plus typed wrappers for every feeder command. All access to a rail is
 * serialised through this object since the bus is half duplex.
 */
public class OrionBus {
    public static final int DEFAULT_TIMEOUT_MS = 100;
    /** Feed/peel/jog block on the feeder until the move (and coupled peel) is finished. */
    public static final int MOVE_TIMEOUT_MS = 12000;
    public static final int DISCOVER_WINDOW_MS = 350;

    private final OrionTransport transport;
    private final int rail;
    private final List<OrionBusListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, OrionDeviceInfo> devices = new TreeMap<>();

    private boolean quiet;
    private int retries = 2;
    private int maxAddress = 40;

    public OrionBus(OrionTransport transport, int rail) {
        this.transport = transport;
        this.rail = rail;
    }

    public int getRail() {
        return rail;
    }

    public OrionTransport getTransport() {
        return transport;
    }

    public void setRetries(int retries) {
        this.retries = Math.max(0, retries);
    }

    public void setMaxAddress(int maxAddress) {
        this.maxAddress = Math.max(1, Math.min(OrionCommand.ADDR_MAX, maxAddress));
    }

    public int getMaxAddress() {
        return maxAddress;
    }

    public void addListener(OrionBusListener l) {
        listeners.add(l);
    }

    public void removeListener(OrionBusListener l) {
        listeners.remove(l);
    }

    private void log(OrionBusListener.Direction d, String text) {
        for (OrionBusListener l : listeners) {
            l.onTraffic(rail, d, text);
        }
    }

    // ------------------------------------------------------------------ raw access

    /** Send a frame and return every valid reply. Single attempt, no retry. */
    public synchronized List<OrionFrame> exchange(OrionFrame request, int timeoutMs, int collectMs)
            throws OrionException {
        if (!quiet) {
            log(OrionBusListener.Direction.TX,
                    OrionCommand.name(request.command) + " " + request);
        }
        List<OrionFrame> replies;
        try {
            replies = transport.exchange(rail, request, timeoutMs, collectMs);
        } catch (OrionException e) {
            log(OrionBusListener.Direction.ERROR, e.getMessage());
            throw e;
        }
        for (OrionFrame r : replies) {
            log(OrionBusListener.Direction.RX, OrionCommand.name(r.command) + " " + r);
        }
        return replies;
    }

    /**
     * Unicast request with retries. Replies from the wrong address are ignored (and logged); a
     * NACK is converted into an exception carrying the error code.
     */
    public synchronized OrionFrame request(int address, int cmd, byte[] payload, int timeoutMs)
            throws OrionException {
        OrionFrame req = new OrionFrame(address, cmd, payload);
        OrionException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            List<OrionFrame> replies = exchange(req, timeoutMs, 0);
            OrionFrame mine = null;
            for (OrionFrame r : replies) {
                if (r.address == address) {
                    mine = r;
                    break;
                }
                log(OrionBusListener.Direction.ERROR,
                        "Reply from unexpected address " + r.address + " while talking to " + address);
                markConflict(address, "Reply from address " + r.address + " while addressing " + address);
            }
            if (mine == null) {
                last = new OrionException(OrionException.Kind.TIMEOUT,
                        String.format("No reply from rail %d address %d to %s", rail, address,
                                OrionCommand.name(cmd)));
                continue;
            }
            if (mine.isNack()) {
                OrionError err = mine.payload.length > 0 ? OrionError.fromCode(mine.u8(0))
                        : OrionError.UNKNOWN;
                throw new OrionException(OrionException.Kind.NACK, err,
                        String.format("%s refused by address %d: %s", OrionCommand.name(cmd),
                                address, err.description));
            }
            touch(address);
            return mine;
        }
        markLost(address);
        throw last;
    }

    private OrionFrame expect(int address, int cmd, byte[] payload, int timeoutMs, int replyCmd)
            throws OrionException {
        OrionFrame f = request(address, cmd, payload, timeoutMs);
        if (f.command != replyCmd) {
            throw new OrionException(OrionException.Kind.UNEXPECTED_REPLY, String.format(
                    "Expected %s but got %s", OrionCommand.name(replyCmd), OrionCommand.name(f.command)));
        }
        return f;
    }

    // ------------------------------------------------------------------ device table

    public synchronized List<OrionDeviceInfo> getDevices() {
        List<OrionDeviceInfo> out = new ArrayList<>();
        for (OrionDeviceInfo d : devices.values()) {
            out.add(d.copy());
        }
        return out;
    }

    public synchronized OrionDeviceInfo getDevice(int address) {
        OrionDeviceInfo d = devices.get(address);
        return d == null ? null : d.copy();
    }

    public synchronized OrionDeviceInfo findBySerial(String serial) {
        for (OrionDeviceInfo d : devices.values()) {
            if (serial.equalsIgnoreCase(d.serial) && d.state != OrionDeviceInfo.State.LOST) {
                return d.copy();
            }
        }
        return null;
    }

    public synchronized void forgetAddress(int address) {
        devices.remove(address);
    }

    public synchronized void forgetAll() {
        devices.clear();
    }

    private void touch(int address) {
        OrionDeviceInfo d = devices.get(address);
        if (d != null) {
            d.lastSeenMs = System.currentTimeMillis();
            if (d.state == OrionDeviceInfo.State.LOST) {
                d.state = d.serial != null && d.serial.startsWith("NONCE-")
                        ? OrionDeviceInfo.State.NO_SERIAL : OrionDeviceInfo.State.ONLINE;
            }
        }
    }

    private void markLost(int address) {
        OrionDeviceInfo d = devices.get(address);
        if (d != null && d.state != OrionDeviceInfo.State.LOST) {
            d.state = OrionDeviceInfo.State.LOST;
            d.note = "no reply";
            log(OrionBusListener.Direction.INFO, "Address " + address + " lost");
        }
    }

    private void markConflict(int address, String why) {
        OrionDeviceInfo d = devices.get(address);
        if (d != null) {
            d.state = OrionDeviceInfo.State.CONFLICT;
            d.note = why;
        }
    }

    // ------------------------------------------------------------------ scan / addressing

    public interface ScanProgress {
        void update(String phase, int done, int total);
    }

    /**
     * Full scan. Phase 1 pings every address up to maxAddress so feeders that kept an address from
     * an earlier session (host restarted, feeders did not) are picked up. Phase 2 broadcasts
     * DISCOVER and assigns addresses to every unassigned feeder that answers. Returns the number of
     * newly assigned feeders.
     */
    public synchronized int scan(ScanProgress progress) throws OrionException {
        Set<Integer> seen = new HashSet<>();
        log(OrionBusListener.Direction.INFO, "Probing addresses 1.." + maxAddress);
        quiet = true; // 40 silent PINGs would drown the log; replies are still logged
        try {
            for (int a = 1; a <= maxAddress; a++) {
                if (progress != null) {
                    progress.update("Probing addresses", a, maxAddress);
                }
                try {
                    OrionFrame req = new OrionFrame(a, OrionCommand.PING);
                    List<OrionFrame> replies = exchange(req, 40, 0);
                    for (OrionFrame r : replies) {
                        if (r.command == OrionCommand.PONG && r.address == a) {
                            seen.add(a);
                        }
                    }
                } catch (OrionException e) {
                    if (e.kind == OrionException.Kind.TRANSPORT) {
                        throw e;
                    }
                }
            }
        } finally {
            quiet = false;
        }
        for (int a : seen) {
            try {
                identify(a);
            } catch (OrionException e) {
                if (e.kind == OrionException.Kind.TRANSPORT) {
                    throw e;
                }
                log(OrionBusListener.Direction.ERROR,
                        "Address " + a + " answered a ping but could not be identified: " + e.getMessage());
            }
        }
        // Anything we knew about that did not answer is now lost.
        for (OrionDeviceInfo d : new ArrayList<>(devices.values())) {
            if (!seen.contains(d.address)) {
                markLost(d.address);
            }
        }
        if (progress != null) {
            progress.update("Looking for new feeders", 0, 1);
        }
        int added = discoverAndAssign();
        if (progress != null) {
            progress.update("Done", 1, 1);
        }
        return added;
    }

    /** Learn serial / component / width of the unit at an address and record it. */
    private OrionDeviceInfo identify(int address) throws OrionException {
        OrionDeviceInfo d = devices.get(address);
        String serial = null;
        try {
            OrionFrame s = expect(address, OrionCommand.GET_SERIAL, new byte[0], DEFAULT_TIMEOUT_MS,
                    OrionCommand.SERIAL_INFO);
            serial = OrionFrame.toHex(s.payload);
        } catch (OrionException e) {
            if (e.kind == OrionException.Kind.TIMEOUT || e.kind == OrionException.Kind.TRANSPORT) {
                throw e;
            }
        }
        OrionFrame comp = expect(address, OrionCommand.GET_COMPONENT, new byte[0],
                DEFAULT_TIMEOUT_MS, OrionCommand.COMPONENT_INFO);
        int width = 0xFF;
        try {
            width = expect(address, OrionCommand.GET_HW_INFO, new byte[0], DEFAULT_TIMEOUT_MS,
                    OrionCommand.HW_INFO).u8(0);
        } catch (OrionException ignored) {
        }

        OrionDeviceInfo fresh = new OrionDeviceInfo();
        fresh.rail = rail;
        fresh.address = address;
        fresh.componentId = comp.u16(0);
        fresh.tapeWidthMm = width;
        fresh.lastSeenMs = System.currentTimeMillis();
        if (serial == null) {
            fresh.serial = d != null && d.serial != null ? d.serial : "NONCE-" + String.format("%04X", address);
            fresh.state = OrionDeviceInfo.State.NO_SERIAL;
            fresh.note = "no readable serial";
        } else {
            fresh.serial = serial;
            fresh.state = OrionDeviceInfo.State.ONLINE;
        }
        if (d != null && d.serial != null && !d.serial.equals(fresh.serial)
                && !d.serial.startsWith("NONCE-") && d.state != OrionDeviceInfo.State.LOST) {
            // The address now answers with a different serial than we recorded: swapped unit.
            log(OrionBusListener.Direction.ERROR, "Address " + address + " changed owner: "
                    + d.serial + " -> " + fresh.serial);
            fresh.note = "address previously held " + d.serial;
        }
        // A serial that already sits at another address means a stale entry, drop the old one.
        for (OrionDeviceInfo other : new ArrayList<>(devices.values())) {
            if (other.address != address && fresh.serial.equals(other.serial)
                    && !fresh.serial.startsWith("NONCE-")) {
                devices.remove(other.address);
            }
        }
        devices.put(address, fresh);
        return fresh;
    }

    /** Broadcast DISCOVER, give every unassigned feeder an address. */
    public synchronized int discoverAndAssign() throws OrionException {
        int added = 0;
        for (int round = 0; round < 8; round++) {
            List<OrionFrame> replies = exchange(new OrionFrame(OrionCommand.ADDR_BROADCAST,
                    OrionCommand.DISCOVER), DISCOVER_WINDOW_MS, DISCOVER_WINDOW_MS);
            Map<Integer, OrionFrame> byNonce = new TreeMap<>();
            for (OrionFrame r : replies) {
                if (r.command == OrionCommand.DISCOVER_HERE && r.payload.length >= 5) {
                    byNonce.putIfAbsent(r.u16(0), r);
                }
            }
            if (byNonce.isEmpty()) {
                break;
            }
            for (OrionFrame r : byNonce.values()) {
                int nonce = r.u16(0);
                int addr = allocateAddress();
                if (addr < 0) {
                    throw new OrionException(OrionException.Kind.CONFLICT,
                            "No free address left on rail " + rail + " (max " + maxAddress + ")");
                }
                byte[] payload = {(byte) (nonce >> 8), (byte) nonce, (byte) addr};
                List<OrionFrame> acks = exchange(new OrionFrame(OrionCommand.ADDR_BROADCAST,
                        OrionCommand.ASSIGN_ADDR, payload), 150, 0);
                boolean ok = false;
                for (OrionFrame a : acks) {
                    ok |= a.isAck() && a.address == addr;
                }
                if (!ok) {
                    log(OrionBusListener.Direction.ERROR,
                            String.format("Assign %d to nonce %04X not acknowledged", addr, nonce));
                    continue;
                }
                OrionDeviceInfo d = identify(addr);
                if (d.state == OrionDeviceInfo.State.NO_SERIAL) {
                    d.serial = String.format("NONCE-%04X", nonce);
                }
                added++;
                log(OrionBusListener.Direction.INFO,
                        String.format("Assigned address %d to %s", addr, d.serial));
            }
        }
        return added;
    }

    private int allocateAddress() {
        for (int a = 1; a <= maxAddress; a++) {
            if (!devices.containsKey(a)
                    || devices.get(a).state == OrionDeviceInfo.State.LOST) {
                return a;
            }
        }
        return -1;
    }

    /** Ping one address, updating the table. Returns round trip in ms or -1. */
    public synchronized long ping(int address) {
        long t0 = System.nanoTime();
        try {
            expect(address, OrionCommand.PING, new byte[0], DEFAULT_TIMEOUT_MS, OrionCommand.PONG);
            return Math.max(0, (System.nanoTime() - t0) / 1_000_000);
        } catch (OrionException e) {
            return -1;
        }
    }

    /** Re-check the serial at an address; used before a feed to catch swapped/rebooted units. */
    public synchronized boolean verifySerial(int address, String expectedSerial) throws OrionException {
        OrionFrame s = expect(address, OrionCommand.GET_SERIAL, new byte[0], DEFAULT_TIMEOUT_MS,
                OrionCommand.SERIAL_INFO);
        boolean same = OrionFrame.toHex(s.payload).equalsIgnoreCase(expectedSerial);
        if (!same) {
            markConflict(address, "serial mismatch");
        }
        return same;
    }

    // ------------------------------------------------------------------ typed commands

    private OrionFrame ack(int address, int cmd, byte... payload) throws OrionException {
        return expect(address, cmd, payload, DEFAULT_TIMEOUT_MS, OrionCommand.ACK);
    }

    private OrionFrame ackSlow(int address, int cmd, byte... payload) throws OrionException {
        return expect(address, cmd, payload, MOVE_TIMEOUT_MS, OrionCommand.ACK);
    }

    private static byte[] be16(int v) {
        return new byte[] {(byte) (v >> 8), (byte) v};
    }

    public void identifyBlink(int address, int blinks) throws OrionException {
        expect(address, OrionCommand.IDENTIFY, new byte[] {(byte) blinks}, 6000, OrionCommand.ACK);
    }

    public void feedNext(int address) throws OrionException {
        ackSlow(address, OrionCommand.FEED_NEXT);
    }

    public void feedBack(int address) throws OrionException {
        ackSlow(address, OrionCommand.FEED_BACK);
    }

    /** Jog in 0.1 mm units, negative = backwards. Returns the new raw angle. */
    public int jog(int address, int tenthsMm) throws OrionException {
        OrionFrame f = ackSlow(address, OrionCommand.JOG, be16(tenthsMm));
        return f.payload.length >= 2 ? f.u16(0) : -1;
    }

    /** Peel for the calibrated time. */
    public void peelCalibrated(int address, boolean reverse) throws OrionException {
        ackSlow(address, OrionCommand.PEEL, (byte) (reverse ? 1 : 0));
    }

    /** Peel for an explicit duration in 10 ms steps (1..255). */
    public void peel(int address, boolean reverse, int tensOfMs) throws OrionException {
        ackSlow(address, OrionCommand.PEEL, (byte) (reverse ? 1 : 0), (byte) tensOfMs);
    }

    public void stop(int address) throws OrionException {
        ack(address, OrionCommand.STOP);
    }

    public OrionStatus getStatus(int address) throws OrionException {
        return OrionStatus.parse(expect(address, OrionCommand.GET_STATUS, new byte[0],
                DEFAULT_TIMEOUT_MS, OrionCommand.STATUS_INFO));
    }

    public void setPitchMm(int address, int mm) throws OrionException {
        ack(address, OrionCommand.SET_PITCH_MM, (byte) mm);
    }

    public void zeroHere(int address) throws OrionException {
        ack(address, OrionCommand.ZERO_HERE);
    }

    public void resetConfig(int address) throws OrionException {
        ack(address, OrionCommand.RESET_CONFIG);
    }

    public void setComponent(int address, int id) throws OrionException {
        ack(address, OrionCommand.SET_COMPONENT, be16(id));
    }

    /** @return {componentId, tapeZero, feedHalfTeeth}. */
    public int[] getComponent(int address) throws OrionException {
        OrionFrame f = expect(address, OrionCommand.GET_COMPONENT, new byte[0], DEFAULT_TIMEOUT_MS,
                OrionCommand.COMPONENT_INFO);
        return new int[] {f.u16(0), f.u16(2), f.u8(4)};
    }

    public void setPeelTimeMs(int address, int ms) throws OrionException {
        ack(address, OrionCommand.SET_PEEL_TIME, be16(ms));
    }

    /** @return peel time in ms, or -1 if not calibrated (0xFFFF). */
    public int getPeelTimeMs(int address) throws OrionException {
        int v = expect(address, OrionCommand.GET_PEEL_TIME, new byte[0], DEFAULT_TIMEOUT_MS,
                OrionCommand.PEEL_TIME_INFO).u16(0);
        return v == 0xFFFF ? -1 : v;
    }

    /** Peel rate in 0.1 ms of peel per mm of sprocket travel; 0 = off. */
    public void setPeelRate(int address, int tenthsMsPerMm) throws OrionException {
        ack(address, OrionCommand.SET_PEEL_RATE, be16(tenthsMsPerMm));
    }

    /** @return peel rate, or -1 if unset (0xFFFF). */
    public int getPeelRate(int address) throws OrionException {
        int v = expect(address, OrionCommand.GET_PEEL_RATE, new byte[0], DEFAULT_TIMEOUT_MS,
                OrionCommand.PEEL_RATE_INFO).u16(0);
        return v == 0xFFFF ? -1 : v;
    }

    public void setLedBrightness(int address, int level) throws OrionException {
        ack(address, OrionCommand.SET_LED_BRIGHTNESS, (byte) level);
    }

    public void setTapeWidth(int address, int mm) throws OrionException {
        ack(address, OrionCommand.SET_HW_INFO, (byte) mm);
    }

    public byte[] i2cScan(int address) throws OrionException {
        return expect(address, OrionCommand.I2C_SCAN, new byte[0], DEFAULT_TIMEOUT_MS,
                OrionCommand.I2C_SCAN_INFO).payload;
    }

    public List<Integer> knownAddresses() {
        return Collections.unmodifiableList(Arrays.asList(devices.keySet().toArray(new Integer[0])));
    }

    public Optional<OrionDeviceInfo> firstOnline() {
        for (OrionDeviceInfo d : devices.values()) {
            if (d.state == OrionDeviceInfo.State.ONLINE) {
                return Optional.of(d.copy());
            }
        }
        return Optional.empty();
    }
}
