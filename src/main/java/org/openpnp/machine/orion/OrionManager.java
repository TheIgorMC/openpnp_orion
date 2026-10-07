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
import org.openpnp.model.Configuration;
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
    private String lastError = "";

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
            public void machineEnabled(Machine m) {
                if (!getSettings().isConnectOnEnable()) {
                    return;
                }
                org.openpnp.util.UiUtils.submitUiMachineTask(() -> {
                    connectIfNeeded();
                    for (OrionBus b : getBuses()) {
                        b.scan(null);
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
        OrionSettings s = getSettings();
        OrionTransport t;
        switch (s.getMode()) {
            case SIMULATED:
                SimulatedOrionTransport sim = new SimulatedOrionTransport(2);
                for (int i = 0; i < 6; i++) {
                    sim.addFeeder(i % 2, i + 1);
                }
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
            b.addListener(busListener);
            buses.add(b);
        }
        lastError = "";
        addLog(0, OrionBusListener.Direction.INFO, "Connected: " + t.describe());
        fireChanged();
    }

    public synchronized void disconnect() {
        if (transport != null) {
            transport.close();
            addLog(0, OrionBusListener.Direction.INFO, "Disconnected");
        }
        transport = null;
        buses.clear();
        fireChanged();
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
}
