package org.openpnp.machine.orion;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openpnp.machine.orion.protocol.HostBoardTransport;
import org.openpnp.machine.orion.protocol.JSerialCommChannel;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionBusListener;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;
import org.openpnp.machine.orion.protocol.OrionException;
import org.openpnp.machine.orion.protocol.OrionTransport;
import org.openpnp.machine.orion.protocol.SerialRs485Transport;
import org.openpnp.machine.orion.protocol.SimulatedOrionTransport;
import org.opencv.core.Mat;
import org.openpnp.machine.orion.vision.OrionIdentifier;
import org.openpnp.machine.orion.vision.OrionPipelines;
import org.openpnp.machine.orion.vision.OrionRailScanner;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Camera;
import org.openpnp.machine.orion.vision.OrionVisionFinder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.pmw.tinylog.Logger;

/**
 * Owns the transport and one {@link OrionBus} per rail. Shared by all OrionFeeders and by the
 * Bus Manager / Debug tabs. Connects lazily, so a machine file with Orion feeders loads fine
 * without any hardware attached.
 */
public class OrionManager {
    static final String SETTINGS_PROPERTY = "OrionFeeder.Settings";
    private static final int LOG_LIMIT = 3000;

    public interface Listener {
        /** Connection state, device table or log changed. Called on the Swing thread, coalesced. */
        void orionChanged();
    }

    public static class LogEntry {
        public final long time = System.currentTimeMillis();
        public final int rail;
        public final OrionBusListener.Direction direction;
        public final String text;

        LogEntry(int rail, OrionBusListener.Direction direction, String text) {
            this.rail = rail;
            this.direction = direction;
            this.text = text;
        }

        @Override
        public String toString() {
            return String.format("%s  R%d %-5s %s", new SimpleDateFormat("HH:mm:ss.SSS").format(
                    new Date(time)), rail + 1, direction, text);
        }
    }

    /** A short message for the user ("found a new feeder", "feeder lost", "bus was reset"). */
    public static class Notice {
        public enum Kind { FOUND, ADDED, LOST, BACK, RESET, ERROR, SETUP_DUE, BOARD_INSERTED, BOARD_WAITING }

        public final long time = System.currentTimeMillis();
        public final Kind kind;
        public final int rail;
        public final String text;

        public Notice(Kind kind, int rail, String text) {
            this.kind = kind;
            this.rail = rail;
            this.text = text;
        }
    }

    private static OrionManager instance;

    public static synchronized OrionManager get() {
        if (instance == null) {
            instance = new OrionManager();
        }
        return instance;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<LogEntry> log = new ArrayList<>();
    private OrionTransport transport;
    private final List<OrionBus> buses = new ArrayList<>();
    private volatile String lastError = "";
    /** True after the user pressed Disconnect: no automatic reconnect then. */
    private boolean userDisconnected;
    private final boolean[] userPowerOff = new boolean[8];
    private final List<java.util.function.Consumer<Notice>> noticeListeners = new CopyOnWriteArrayList<>();
    private final OrionBusMonitor monitor = new OrionBusMonitor(this);

    public OrionBusMonitor getMonitor() {
        return monitor;
    }

    public void addNoticeListener(java.util.function.Consumer<Notice> l) {
        noticeListeners.add(l);
    }

    public void removeNoticeListener(java.util.function.Consumer<Notice> l) {
        noticeListeners.remove(l);
    }

    public void notice(Notice n) {
        addLog(Math.max(0, n.rail), n.kind == Notice.Kind.ERROR ? OrionBusListener.Direction.ERROR
                : OrionBusListener.Direction.INFO, n.text);
        javax.swing.SwingUtilities.invokeLater(() -> {
            for (java.util.function.Consumer<Notice> l : noticeListeners) {
                l.accept(n);
            }
        });
    }

    public OrionSettings getSettings() {
        Machine machine = Configuration.get().getMachine();
        Object o = machine.getProperty(SETTINGS_PROPERTY);
        if (!(o instanceof OrionSettings)) {
            o = new OrionSettings();
            machine.setProperty(SETTINGS_PROPERTY, o);
        }
        return (OrionSettings) o;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Tell the UI the device table changed. */
    public void refresh() {
        fireChanged();
    }

    /** Coalesces bursts (a scan logs hundreds of frames) into at most ~5 UI updates per second. */
    private final javax.swing.Timer notifyTimer = new javax.swing.Timer(200, e -> {
        for (Listener l : listeners) {
            l.orionChanged();
        }
    });

    {
        notifyTimer.setRepeats(false);
    }

    void fireChanged() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (!notifyTimer.isRunning()) {
                notifyTimer.start();
            }
        });
    }

    private Machine hookedMachine;

    /**
     * Called by OrionFeeder instances once the configuration is loaded: auto connect and scan when
     * the machine is enabled (if the user left that option on).
     */
    public synchronized void hookMachine(Machine machine) {
        if (hookedMachine == machine) {
            return;
        }
        hookedMachine = machine;
        machine.addListener(new org.openpnp.spi.MachineListener.Adapter() {
            @Override
            public void machineHomed(Machine m, boolean isHomed) {
                if (isHomed && getSettings().isPromptSetupOnHome()) {
                    offerSetupCycle("The machine is homed. Verify the feeder rails by vision and set up the pick "
                            + "positions now?");
                }
            }

            @Override
            public void machineEnabled(Machine m) {
                if (!getSettings().isConnectOnEnable()) {
                    return;
                }
                org.openpnp.util.UiUtils.submitUiMachineTask(() -> {
                    connectIfNeeded();
                    for (OrionBus b : getBuses()) {
                        b.scan(null);
                        if (getSettings().isAutoCreateFeeders()) {
                            createFeedersForUnbound(b.getRail());
                        }
                    }
                    refresh();
                    return null;
                }, r -> {
                }, t -> addLog(0, OrionBusListener.Direction.ERROR,
                        "Auto connect/scan failed: " + t.getMessage()), true);
            }
        });
    }

    // ------------------------------------------------------------------ log

    private final OrionBusListener busListener = (rail, dir, text) -> addLog(rail, dir, text);

    public void addLog(int rail, OrionBusListener.Direction dir, String text) {
        synchronized (log) {
            log.add(new LogEntry(rail, dir, text));
            while (log.size() > LOG_LIMIT) {
                log.remove(0);
            }
        }
        if (dir == OrionBusListener.Direction.ERROR) {
            Logger.warn("Orion rail " + (rail + 1) + ": " + text);
        } else {
            Logger.trace("Orion rail " + (rail + 1) + " " + dir + " " + text);
        }
        fireChanged();
    }

    public List<LogEntry> getLog() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    public void clearLog() {
        synchronized (log) {
            log.clear();
        }
        fireChanged();
    }

    // ------------------------------------------------------------------ connection

    public synchronized boolean isConnected() {
        return transport != null && transport.isOpen();
    }

    public synchronized String describeConnection() {
        return isConnected() ? transport.describe() : "not connected";
    }

    public synchronized String getLastError() {
        return lastError;
    }

    public synchronized int getRailCount() {
        return isConnected() ? buses.size() : 0;
    }

    /** The board conveyor if one answered on its rail, else null. */
    public org.openpnp.machine.orion.protocol.OrionConveyor getConveyor() {
        for (OrionBus b : getBuses()) {
            org.openpnp.machine.orion.protocol.OrionConveyor c = b.getConveyor();
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private volatile org.openpnp.machine.orion.protocol.ConveyorStatus conveyorStatus;

    /** Last status the monitor or the Conveyor tab read, or null. */
    public org.openpnp.machine.orion.protocol.ConveyorStatus getConveyorStatus() {
        return conveyorStatus;
    }

    public void setConveyorStatus(org.openpnp.machine.orion.protocol.ConveyorStatus s) {
        conveyorStatus = s;
    }

    public synchronized OrionBus getBus(int rail) {
        return rail >= 0 && rail < buses.size() ? buses.get(rail) : null;
    }

    public synchronized List<OrionBus> getBuses() {
        return new ArrayList<>(buses);
    }

    public synchronized OrionTransport getTransport() {
        return transport;
    }

    public synchronized void connect() throws OrionException {
        hookMachine(Configuration.get().getMachine());
        disconnect();
        userDisconnected = false;
        OrionSettings s = getSettings();
        OrionTransport t;
        switch (s.getMode()) {
            case SIMULATED:
                SimulatedOrionTransport sim = new SimulatedOrionTransport(2);
                for (int i = 0; i < 6; i++) {
                    sim.addFeeder(i % 2, i + 1);
                }
                org.openpnp.machine.orion.protocol.SimulatedConveyor belt = sim.addConveyor(
                        Math.min(1, sim.getRailCount() - 1));
                belt.instant = false;
                t = sim;
                break;
            case HOST_BOARD:
                t = new HostBoardTransport(new JSerialCommChannel(s.getHostPort(), s.getHostBaud()));
                break;
            case USB_RS485:
            default:
                t = new SerialRs485Transport(new JSerialCommChannel(s.getAdapterPort(),
                        s.getAdapterBaud()));
                break;
        }
        try {
            t.open();
        } catch (OrionException e) {
            lastError = e.getMessage();
            addLog(0, OrionBusListener.Direction.ERROR, "Connect failed: " + e.getMessage());
            fireChanged();
            throw e;
        }
        transport = t;
        buses.clear();
        for (int r = 0; r < t.getRailCount(); r++) {
            OrionBus b = new OrionBus(t, r);
            b.setMaxAddress(s.getMaxAddress());
            b.setRetries(s.getRetries());
            b.setConveyorAddress(s.isConveyorEnabled() && r == s.getConveyorRail()
                    ? org.openpnp.machine.orion.protocol.OrionCommand.ADDR_CONVEYOR : 0);
            b.addListener(busListener);
            buses.add(b);
        }
        lastError = "";
        java.util.Arrays.fill(userPowerOff, false);
        addLog(0, OrionBusListener.Direction.INFO, "Connected: " + t.describe());
        monitor.start();
        fireChanged();
    }

    /** Close the connection for good (until the user connects again): the monitor will not reconnect. */
    public synchronized void disconnect() {
        userDisconnected = true;
        if (transport != null) {
            transport.close();
            addLog(0, OrionBusListener.Direction.INFO, "Disconnected");
        }
        transport = null;
        buses.clear();
        fireChanged();
    }

    /** Reopen the port of the existing transport and keep the device table (the adapter was replugged). */
    synchronized void reconnectKeepingTable() throws OrionException {
        if (transport == null) {
            connect();
            return;
        }
        transport.close();
        transport.open();
        for (OrionBus b : buses) {
            b.noteBusReset();
        }
        lastError = "";
        addLog(0, OrionBusListener.Direction.INFO, "Reconnected: " + transport.describe());
        fireChanged();
    }

    synchronized boolean isUserDisconnected() {
        return userDisconnected;
    }

    /** Switch a rail's supply (host board) and remember that the user did it, so the monitor leaves it alone. */
    public synchronized boolean setRailPower(int rail, boolean on) throws OrionException {
        if (transport == null) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "Not connected");
        }
        boolean ok = transport.setRailPower(rail, on);
        if (ok && rail >= 0 && rail < userPowerOff.length) {
            userPowerOff[rail] = !on;
        }
        return ok;
    }

    synchronized boolean isRailPoweredOffByUser(int rail) {
        return rail >= 0 && rail < userPowerOff.length && userPowerOff[rail];
    }

    /**
     * Bring a hung bus back without losing the device table. Soft: flush and reopen the port (cures a
     * stalled USB driver, a half-unplugged adapter, garbage in the buffers). Hard: also switch every
     * rail supply off and on where the interface can (a feeder stuck transmitting, which blocks every
     * other one, only lets go of the line then). Feeders restart with no address and get the one they
     * had before; the monitor picks them up again by itself.
     *
     * @return true when the port is open again
     */
    public boolean resetBus(boolean hard, String why) {
        OrionTransport t;
        List<OrionBus> bs;
        final boolean[] off;
        synchronized (this) {
            t = transport;
            bs = new ArrayList<>(buses);
            off = userPowerOff.clone(); // read now: taking the manager lock while holding a bus lock could deadlock
        }
        if (t == null) {
            return false;
        }
        addLog(0, OrionBusListener.Direction.INFO,
                (hard ? "HARD bus reset" : "Bus reset") + (why == null ? "" : ": " + why));
        // Wait for every exchange in flight, then keep the rails still while the port is cycled.
        return lockAll(bs, 0, () -> {
            try {
                if (hard) {
                    for (OrionBus b : bs) {
                        if (!off[b.getRail()]) {
                            try {
                                t.setRailPower(b.getRail(), false);
                            } catch (OrionException ignore) {
                                // not every interface can
                            }
                        }
                    }
                }
                t.close();
                Thread.sleep(hard ? 1200 : 250);
                if (hard) {
                    for (OrionBus b : bs) {
                        if (!off[b.getRail()]) {
                            try {
                                t.setRailPower(b.getRail(), true);
                            } catch (OrionException ignore) {
                            }
                        }
                    }
                    Thread.sleep(800);
                }
                t.open();
                for (OrionBus b : bs) {
                    b.noteBusReset();
                }
                lastError = "";
                addLog(0, OrionBusListener.Direction.INFO, "Bus reopened: " + t.describe());
                fireChanged();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (OrionException e) {
                lastError = e.getMessage();
                addLog(0, OrionBusListener.Direction.ERROR, "Bus reset failed: " + e.getMessage());
                fireChanged();
                return false;
            }
        });
    }

    private static boolean lockAll(List<OrionBus> bs, int i, java.util.function.Supplier<Boolean> action) {
        if (i >= bs.size()) {
            return action.get();
        }
        synchronized (bs.get(i)) {
            return lockAll(bs, i + 1, action);
        }
    }

    public synchronized void connectIfNeeded() throws OrionException {
        if (!isConnected()) {
            connect();
        }
    }

    /** Push changed settings (retries, max address) into live buses. */
    public synchronized void applySettingsToBuses() {
        OrionSettings s = getSettings();
        for (OrionBus b : buses) {
            b.setMaxAddress(s.getMaxAddress());
            b.setRetries(s.getRetries());
        }
    }

    // ------------------------------------------------------------------ lookup

    /** Result of locating a feeder by serial. */
    public static class Located {
        public final OrionBus bus;
        public final OrionDeviceInfo info;

        Located(OrionBus bus, OrionDeviceInfo info) {
            this.bus = bus;
            this.info = info;
        }
    }

    /**
     * Find the live unit with this serial. Tries the hinted rail first. Throws CONFLICT if the
     * serial shows up on two rails (duplicated serial programmed into two feeders).
     */
    public synchronized Located locate(String serial, int railHint) throws OrionException {
        connectIfNeeded();
        Located found = null;
        List<OrionBus> order = new ArrayList<>(buses);
        if (railHint >= 0 && railHint < order.size()) {
            OrionBus h = order.remove(railHint);
            order.add(0, h);
        }
        for (OrionBus b : order) {
            OrionDeviceInfo d = b.findBySerial(serial);
            if (d != null) {
                if (found != null) {
                    throw new OrionException(OrionException.Kind.CONFLICT, "Serial " + serial
                            + " answers on rail " + (found.bus.getRail() + 1) + " and rail "
                            + (b.getRail() + 1) + ". Two feeders share a serial number.");
                }
                found = new Located(b, d);
            }
        }
        return found;
    }

    /**
     * Like {@link #locate} but scans the rails if the feeder isn't known yet, once.
     */
    public synchronized Located locateOrScan(String serial, int railHint) throws OrionException {
        Located l = locate(serial, railHint);
        if (l != null) {
            return l;
        }
        for (OrionBus b : buses) {
            b.scan(null);
        }
        fireChanged();
        return locate(serial, railHint);
    }


    // ------------------------------------------------------------------ "was the rail verified?"

    /** Outcome of the last vision verify of a rail. */
    public static class RailCheck {
        public final long time = System.currentTimeMillis();
        public final boolean clean;
        public final String summary;
        final String signature;

        RailCheck(boolean clean, String summary, String signature) {
            this.clean = clean;
            this.summary = summary;
            this.signature = signature;
        }
    }

    private final java.util.Map<Integer, RailCheck> checks = new java.util.HashMap<>();
    private final java.util.Map<Integer, Long> lastWarned = new java.util.HashMap<>();

    private static String signature(OrionBus bus) {
        List<String> l = new ArrayList<>();
        for (OrionDeviceInfo d : bus.getFeeders()) {
            if (d.state != OrionDeviceInfo.State.LOST) {
                l.add(d.serial + "|" + d.slotXMm);
            }
        }
        java.util.Collections.sort(l);
        return String.join(",", l);
    }

    /** Remember the outcome of a vision verify of the rail (also the bus state it was done against). */
    public synchronized void recordRailCheck(int rail, boolean clean, String summary) {
        OrionBus bus = getBus(rail);
        checks.put(rail, new RailCheck(clean, summary, bus == null ? "" : signature(bus)));
    }

    public synchronized void clearRailChecks() {
        checks.clear();
        lastWarned.clear();
    }

    public synchronized RailCheck getRailCheck(int rail) {
        return checks.get(rail);
    }

    /**
     * Why the rail cannot be trusted for a job right now, or null if its last vision verify was clean
     * and nothing on the bus changed since.
     */
    public synchronized String railCheckProblem(int rail) {
        RailCheck c = checks.get(rail);
        if (c == null) {
            return "Rail " + (rail + 1) + " has not been checked by vision since the program started "
                    + "(Feeder bus manager > Verify rail by vision). Feeder locations may be off.";
        }
        if (!c.clean) {
            return "The last vision check of rail " + (rail + 1) + " found problems: " + c.summary;
        }
        OrionBus bus = getBus(rail);
        if (bus != null && !signature(bus).equals(c.signature)) {
            return "The feeders on rail " + (rail + 1) + " changed since it was last checked by vision.";
        }
        return null;
    }

    /** Once per minute per rail, so a job preparing 30 feeders shows a single dialog. */
    synchronized boolean shouldShowWarning(int rail) {
        long now = System.currentTimeMillis();
        Long last = lastWarned.get(rail);
        if (last != null && now - last < 60000) {
            return false;
        }
        lastWarned.put(rail, now);
        return true;
    }

    // ------------------------------------------------------------------ fiber / tape identification

    private static Camera headCamera() throws Exception {
        return Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
    }

    private static OrionFeeder feederOf(OrionRailScanner.Target t) {
        return (OrionFeeder) t.tag;
    }

    /** Identifier used by the rail scan for fiducials that cannot be matched to a unit directly. */
    private OrionRailScanner.Identifier buildIdentifier(int rail, OrionRailSettings rs) {
        final OrionSettings st = getSettings();
        final OrionSettings.IdentifyMethod method = st.getIdentifyMethod();
        if (method == OrionSettings.IdentifyMethod.Off) {
            return null;
        }
        return new OrionRailScanner.Identifier() {
            public String method() {
                return method.toString();
            }

            public OrionRailScanner.Target identify(Location fiducial, List<OrionRailScanner.Target> pending)
                    throws Exception {
                Camera cam = headCamera();
                if (method != OrionSettings.IdentifyMethod.TapeMovement) {
                    Location spot = fiducial.add(new Location(LengthUnit.Millimeters,
                            rs.getFiberOffsetX(), rs.getFiberOffsetY(), 0, 0));
                    org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(cam, spot);
                    OrionRailScanner.Target hit = OrionIdentifier.search(pending,
                            fiberProbe(pending, cam), line -> addLog(rail, OrionBusListener.Direction.INFO,
                                    "identify: " + line));
                    if (hit != null || method == OrionSettings.IdentifyMethod.Fiber) {
                        return hit;
                    }
                    addLog(rail, OrionBusListener.Direction.INFO,
                            "identify: fiber light inconclusive, trying tape movement");
                }
                Location hole = fiducial.add(new Location(LengthUnit.Millimeters,
                        rs.getHoleOffsetX(), rs.getHoleOffsetY(), 0, 0));
                org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(cam, hole);
                return OrionIdentifier.search(pending, movementProbe(cam),
                        line -> addLog(rail, OrionBusListener.Direction.INFO, "identify: " + line));
            }
        };
    }

    private OrionIdentifier.Probe<OrionRailScanner.Target> fiberProbe(
            List<OrionRailScanner.Target> all, Camera cam) {
        final OrionSettings st = getSettings();
        final java.util.Set<OrionRailScanner.Target> lit = new java.util.HashSet<>();
        OrionIdentifier.Fibers<OrionRailScanner.Target> fibers = (on, every) -> {
            for (OrionRailScanner.Target t : every) {
                boolean want = on.contains(t);
                if (want != lit.contains(t)) {
                    feederOf(t).setFiber(want);
                    if (want) {
                        lit.add(t);
                    } else {
                        lit.remove(t);
                    }
                }
            }
        };
        OrionIdentifier.Metric metric = () -> OrionPipelines.fiberReading(st.getFiberPipeline(), cam).score;
        return new OrionIdentifier.FiberProbe<>(all, fibers, metric, st.getFiberThreshold(),
                st.getSettleMs());
    }

    private OrionIdentifier.Probe<OrionRailScanner.Target> movementProbe(Camera cam) {
        final OrionSettings st = getSettings();
        final int tenths = Math.max(1, (int) Math.round(st.getMovementMm() * 10));
        OrionIdentifier.Mover<OrionRailScanner.Target> mover = (t, back) ->
                feederOf(t).jogTenthsMm(back ? -tenths : tenths);
        OrionIdentifier.FrameSource frames = new OrionIdentifier.FrameSource() {
            public Object snap() throws Exception {
                return OrionPipelines.grab(st.getMovementPipeline(), cam);
            }

            public double difference(Object a, Object b) {
                double d = OrionPipelines.changedPixels((Mat) a, (Mat) b);
                ((Mat) a).release();
                ((Mat) b).release();
                return d;
            }
        };
        return new OrionIdentifier.MovementProbe<>(mover, frames, st.getMovementThreshold(),
                st.getSettleMs(), st.getStaggerMs());
    }

    /**
     * Tuning helper: measure the fiber spot with the unit's fiber off and on. Camera must already be over
     * the spot. Returns {off, on, rise, threshold, circleMode (1/0), circlesOff, circlesOn}.
     */
    public double[] testFiber(OrionFeeder feeder) throws Exception {
        OrionSettings st = getSettings();
        Camera cam = headCamera();
        try {
            feeder.setFiber(false);
            Thread.sleep(st.getSettleMs());
            OrionPipelines.Reading off = OrionPipelines.fiberReading(st.getFiberPipeline(), cam);
            feeder.setFiber(true);
            Thread.sleep(st.getSettleMs());
            OrionPipelines.Reading on = OrionPipelines.fiberReading(st.getFiberPipeline(), cam);
            return new double[] {off.score, on.score, on.score - off.score, st.getFiberThreshold(),
                    on.circleMode ? 1 : 0, off.circles, on.circles};
        } finally {
            feeder.setFiber(false);
        }
    }

    /** Tuning helper: move the unit's tape back a bit, count changed pixels in the hole mask, move it forward again. */
    public double[] testMovement(OrionFeeder feeder) throws Exception {
        OrionSettings st = getSettings();
        Camera cam = headCamera();
        int tenths = Math.max(1, (int) Math.round(st.getMovementMm() * 10));
        Mat a = OrionPipelines.grab(st.getMovementPipeline(), cam);
        feeder.jogTenthsMm(-tenths);
        try {
            Thread.sleep(st.getSettleMs());
            Mat b = OrionPipelines.grab(st.getMovementPipeline(), cam);
            double changed = OrionPipelines.changedPixels(a, b);
            b.release();
            return new double[] {changed, st.getMovementThreshold()};
        } finally {
            a.release();
            feeder.jogTenthsMm(tenths);
        }
    }

    /**
     * Create one OrionFeeder per live unit of the rail that has no feeder yet, in slot-X order, named
     * "Orion R1-01" ... The user then assigns a part to each. Returns the new feeders.
     */
    public List<OrionFeeder> createFeedersForUnbound(int rail) throws Exception {
        OrionBus bus = getBus(rail);
        if (bus == null) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "Rail " + (rail + 1) + " is not connected");
        }
        java.util.Set<String> bound = new java.util.HashSet<>();
        for (Feeder f : Configuration.get().getMachine().getFeeders()) {
            if (f instanceof OrionFeeder && ((OrionFeeder) f).getSerial() != null) {
                bound.add(((OrionFeeder) f).getSerial().toUpperCase());
            }
        }
        List<OrionDeviceInfo> units = new ArrayList<>();
        for (OrionDeviceInfo d : bus.getFeeders()) {
            if (d.state != OrionDeviceInfo.State.LOST && d.serial != null
                    && !d.serial.startsWith("NONCE-") && !bound.contains(d.serial.toUpperCase())) {
                units.add(d);
            }
        }
        units.sort((a, b) -> Double.compare(Double.isNaN(a.slotXMm) ? Double.MAX_VALUE : a.slotXMm,
                Double.isNaN(b.slotXMm) ? Double.MAX_VALUE : b.slotXMm));
        List<OrionFeeder> created = new ArrayList<>();
        for (OrionDeviceInfo d : units) {
            OrionFeeder f = new OrionFeeder();
            f.setName(uniqueName(String.format("Orion R%d-%s", rail + 1, d.serial.substring(0, 4))));
            f.setSerial(d.serial);
            f.setRail(rail);
            if (!Double.isNaN(d.slotXMm)) {
                f.setTaughtSlotXMm(d.slotXMm);
            }
            Configuration.get().getMachine().addFeeder(f);
            try {
                f.readSettingsFromUnit(); // mirror what is stored on the unit, push nothing
            } catch (Exception e) {
                addLog(rail, OrionBusListener.Direction.ERROR, "Could not read the settings of "
                        + f.getName() + ": " + e.getMessage());
            }
            created.add(f);
        }
        addLog(rail, OrionBusListener.Direction.INFO, "Created " + created.size() + " feeder(s)");
        refresh();
        return created;
    }

    /**
     * The one-step setup: connect, scan every rail, create a feeder for each unit that has none.
     * Needs no existing OrionFeeder. Returns a one-line summary.
     */
    public String bootstrap() throws Exception {
        connectIfNeeded();
        int units = 0;
        int created = 0;
        for (OrionBus b : getBuses()) {
            b.scan(null);
            for (OrionDeviceInfo d : b.getFeeders()) {
                if (d.state != OrionDeviceInfo.State.LOST) {
                    units++;
                }
            }
            created += createFeedersForUnbound(b.getRail()).size();
        }
        String msg = String.format("%d unit(s) on %d rail(s), %d new feeder(s) created", units,
                getRailCount(), created);
        addLog(0, OrionBusListener.Direction.INFO, "bootstrap: " + msg);
        return msg;
    }

    /** Verify every rail that has its vision scan set up. Returns one summary line per rail. */
    public List<String> verifyAllRails(OrionRailScanner.Progress progress) throws Exception {
        List<String> out = new ArrayList<>();
        for (OrionBus b : getBuses()) {
            if (!getSettings().getRailSettings(b.getRail()).isConfigured()) {
                out.add("Rail " + (b.getRail() + 1) + ": vision scan not set up (X range empty), skipped");
                continue;
            }
            out.add("Rail " + (b.getRail() + 1) + ": " + verifyRail(b.getRail(), progress).summary());
        }
        return out;
    }

    private static String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (true) {
            boolean taken = false;
            for (Feeder f : Configuration.get().getMachine().getFeeders()) {
                taken |= name.equals(f.getName());
            }
            if (!taken) {
                return name;
            }
            name = base + "-" + n++;
        }
    }

    // ------------------------------------------------------------------ vision verify / rescan

    /**
     * Check every feeder of a rail by its large fiducial (small shifts are saved as the new X); if
     * any is missing, do ONE sweep of the free parts of the rail after all feeders were checked.
     * Must run as a machine task (moves the camera).
     */
    public OrionRailScanner.Result verifyRail(int rail, OrionRailScanner.Progress progress)
            throws Exception {
        return verifyRail(rail, progress, false);
    }

    /**
     * @param confirmByFiber also prove with the fiber that the unit at a feeder's saved X is that feeder (catches a
     *                       replaced feeder in a tightly packed bank)
     */
    public OrionRailScanner.Result verifyRail(int rail, OrionRailScanner.Progress progress, boolean confirmByFiber)
            throws Exception {
        OrionSettings settings = getSettings();
        OrionRailSettings rs = settings.getRailSettings(rail);
        if (!rs.isConfigured()) {
            throw new Exception("Rail " + (rail + 1) + " has no X range set. Fill in the vision scan "
                    + "settings of the rail first.");
        }
        Part part = Configuration.get().getPart(settings.getLargeFiducialPartId());
        if (part == null) {
            throw new Exception("Choose the large fiducial part in the Bus Manager vision settings first.");
        }
        OrionBus bus = getBus(rail);
        if (bus == null) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "Rail " + (rail + 1) + " is not connected");
        }
        // Refresh who is on the bus, so unit presence and slot positions are current.
        bus.scan(null);

        List<OrionRailScanner.Target> targets = new ArrayList<>();
        for (Feeder f : Configuration.get().getMachine().getFeeders()) {
            if (!(f instanceof OrionFeeder)) {
                continue;
            }
            final OrionFeeder of = (OrionFeeder) f;
            if (of.getSerial() == null || of.getRail() != rail) {
                continue;
            }
            OrionDeviceInfo d = bus.findBySerial(of.getSerial());
            double slotShift = Double.NaN;
            if (d != null && !Double.isNaN(d.slotXMm) && !Double.isNaN(of.getTaughtSlotXMm())) {
                slotShift = d.slotXMm - of.getTaughtSlotXMm();
            }
            final OrionDeviceInfo dd = d;
            OrionRailScanner.Target tgt = new OrionRailScanner.Target(of.getName(), of.getFiducialNominalOrDefault(rs),
                    d != null && d.state != OrionDeviceInfo.State.LOST, slotShift, found -> {
                        if (dd != null) {
                            of.setCurrentSlotXMm(dd.slotXMm);
                        }
                        of.applyFoundFiducial(found, rs);
                    });
            tgt.tag = of;
            targets.add(tgt);
        }
        OrionRailScanner.Params p = new OrionRailScanner.Params();
        p.xMin = rs.getXMin();
        p.xMax = rs.getXMax();
        p.fiducialY = rs.getFiducialY();
        p.fiducialZ = rs.getFiducialZ();
        p.scanStepMm = rs.getScanStepMm();
        p.exclusionMm = rs.getExclusionMm();
        p.fiducialSpacingMm = rs.getFiducialSpacingMm();
        p.minSaveMm = rs.getMinSaveMm();
        p.maxShiftMm = rs.getMaxShiftMm();
        p.autoRescan = rs.isAutoRescan();
        OrionRailScanner scanner = new OrionRailScanner(p, new OrionVisionFinder(part), progress)
                .withIdentifier(buildIdentifier(rail, rs));
        if (confirmByFiber) {
            scanner.withConfirmer((t, fiducial) -> confirmByFiber(rail, rs, t, fiducial));
        }
        OrionRailScanner.Result r = scanner.run(targets);
        boolean clean = true;
        for (OrionRailScanner.Target t : r.targets) {
            clean &= t.state == OrionRailScanner.State.OK || t.state == OrionRailScanner.State.SHIFTED
                    || t.state == OrionRailScanner.State.RELOCATED;
        }
        recordRailCheck(rail, clean, r.summary());
        for (String line : r.log) {
            addLog(rail, OrionBusListener.Direction.INFO, "vision: " + line);
        }
        addLog(rail, OrionBusListener.Direction.INFO, "vision verify: " + r.summary());
        refresh();
        return r;
    }

    private final java.util.Set<Integer> fiberCheckWarned = new java.util.HashSet<>();

    /**
     * The feeder's fiducial is where it should be: light its fiber and look. If the spot does not light up, the unit
     * standing there is another one (the feeder was replaced) or the unit is gone.
     */
    private boolean confirmByFiber(int rail, OrionRailSettings rs, OrionRailScanner.Target t, Location fiducial)
            throws Exception {
        OrionFeeder f = feederOf(t);
        if (!t.unitOnline) {
            return false;
        }
        if (rs.getFiberOffsetX() == 0 && rs.getFiberOffsetY() == 0) {
            if (fiberCheckWarned.add(rail)) {
                addLog(rail, OrionBusListener.Direction.INFO, "setup: no fiber spot offset set for rail " + (rail + 1)
                        + ", the fiber confirmation is skipped");
            }
            return true;
        }
        Camera cam = headCamera();
        Location spot = fiducial.add(new Location(LengthUnit.Millimeters, rs.getFiberOffsetX(),
                rs.getFiberOffsetY(), 0, 0));
        org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(cam, spot);
        try {
            double[] r = testFiber(f);
            boolean lit = r[2] >= r[3];
            addLog(rail, OrionBusListener.Direction.INFO, "setup: " + f.getName() + " fiber "
                    + (lit ? "confirmed" : "NOT seen") + String.format(" (rise %.0f, threshold %.0f)", r[2], r[3]));
            return lit;
        } catch (Exception e) {
            addLog(rail, OrionBusListener.Direction.ERROR, "setup: fiber check of " + f.getName()
                    + " not possible, taken as confirmed: " + e.getMessage());
            return true;
        }
    }

    /** What a setup cycle did on one rail. */
    public static class SetupReport {
        public final int rail;
        public final List<String> lines = new ArrayList<>();
        public int fine;
        public int problems;

        SetupReport(int rail) {
            this.rail = rail;
        }
    }

    /**
     * The setup cycle of one rail, feeder after feeder in X order: find and confirm each feeder (fiducial, then
     * fiber), then bring its pocket to the rail's pick window (vision or semi blind by part size). New units get a
     * feeder first. Every feeder re-probes its pick height at its first feed. Machine task; the machine must be homed.
     */
    public SetupReport runSetupCycle(int rail, OrionRailScanner.Progress progress) throws Exception {
        if (!Configuration.get().getMachine().isHomed()) {
            throw new Exception("Home the machine first.");
        }
        connectIfNeeded();
        OrionSettings st = getSettings();
        OrionRailSettings rs = st.getRailSettings(rail);
        SetupReport rep = new SetupReport(rail);
        OrionBus bus = getBus(rail);
        if (bus == null) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "Rail " + (rail + 1) + " is not connected");
        }
        bus.scan(null);
        int made = createFeedersForUnbound(rail).size();
        if (made > 0) {
            rep.lines.add(made + " new feeder(s) created for units that had none");
        }
        OrionRailScanner.Result r = verifyRail(rail, progress, true);
        rep.lines.add("Vision: " + r.summary());
        List<OrionRailScanner.Target> ordered = new ArrayList<>(r.targets);
        ordered.sort((a, b) -> Double.compare(a.found == null ? Double.MAX_VALUE : a.found.getX(),
                b.found == null ? Double.MAX_VALUE : b.found.getX()));
        int i = 0;
        for (OrionRailScanner.Target t : ordered) {
            OrionFeeder f = feederOf(t);
            f.forgetZCheck();
            boolean there = t.state == OrionRailScanner.State.OK || t.state == OrionRailScanner.State.SHIFTED
                    || t.state == OrionRailScanner.State.RELOCATED;
            if (!there) {
                rep.problems++;
                rep.lines.add(f.getName() + ": " + t.state + ", pick position not set up");
                continue;
            }
            if (progress != null) {
                progress.update("Pick position of " + f.getName(), i++, ordered.size());
            }
            try {
                OrionFeeder.PickSetupResult pr = f.setupPick(rs, st);
                rep.lines.add(f.getName() + ": " + pr.message);
                if (pr.ok) {
                    rep.fine++;
                } else {
                    rep.problems++;
                }
            } catch (Exception e) {
                rep.problems++;
                rep.lines.add(f.getName() + ": pick position FAILED: " + e.getMessage());
            }
        }
        for (String l : rep.lines) {
            addLog(rail, OrionBusListener.Direction.INFO, "setup: " + l);
        }
        refresh();
        return rep;
    }

    /** Ask the user to run the setup cycle (a message with a Start button), if a rail is configured for it. */
    public void offerSetupCycle(String why) {
        OrionSettings st = getSettings();
        boolean any = false;
        for (int r = 0; r < Math.max(2, getRailCount()); r++) {
            any |= st.getRailSettings(r).isConfigured();
        }
        if (any) {
            notice(new Notice(Notice.Kind.SETUP_DUE, 0, why));
        }
    }

    /** Manual association: the camera is over the feeder's large fiducial; measure and save it. */
    public Location locateHere(OrionFeeder feeder) throws Exception {
        OrionSettings settings = getSettings();
        Part part = Configuration.get().getPart(settings.getLargeFiducialPartId());
        if (part == null) {
            throw new Exception("Choose the large fiducial part in the Bus Manager vision settings first.");
        }
        OrionRailSettings rs = settings.getRailSettings(feeder.getRail());
        Location cam = Configuration.get().getMachine().getDefaultHead().getDefaultCamera().getLocation();
        Location found = new OrionVisionFinder(part).find(cam);
        if (found == null) {
            throw new Exception("No large fiducial found under the camera.");
        }
        feeder.applyFoundFiducial(found, rs);
        addLog(feeder.getRail(), OrionBusListener.Direction.INFO, String.format(
                "vision: %s located manually at X=%.2f", feeder.getName(), found.getX()));
        refresh();
        return found;
    }
}
