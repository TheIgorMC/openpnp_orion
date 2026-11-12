package org.openpnp.machine.orion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openpnp.machine.orion.OrionManager.Notice;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;
import org.openpnp.machine.orion.protocol.OrionException;
import org.openpnp.spi.Machine;
import org.pmw.tinylog.Logger;

/**
 * Keeps an eye on the feeder bus while nothing else is using it:
 * <ul>
 * <li>pings every known feeder once per sweep, so a feeder that is unplugged turns red within a
 * few seconds (and one that is plugged back in turns green);</li>
 * <li>broadcasts a DISCOVER (without assigning anything) to notice a feeder that was just plugged
 * in, tells the user, and scans the rail by itself when no further feeder shows up for the
 * configured delay (30 s by default);</li>
 * <li>notices a hung bus (nobody answers any more, or the adapter vanished) and resets it, soft
 * first, then harder, and reconnects when the adapter is plugged in again.</li>
 * </ul>
 * It stays completely silent while a job runs or the machine is doing a task, since the feeders
 * are needed then and the user must not be disturbed.
 */
public class OrionBusMonitor {
    /** A unit is declared lost after this many missed pings in a row. */
    static final int LOSS_THRESHOLD = 2;
    /** Sweeps in which nobody answered before the port is reset. */
    static final int SWEEPS_BEFORE_SOFT_RESET = 3;
    /** Soft resets that did not help before the rails are power cycled (where the hardware allows). */
    static final int SOFT_RESETS_BEFORE_HARD = 2;
    /** Quickest time between two automatic resets. */
    static final long MIN_RESET_GAP_MS = 20000;

    /** Unassigned units seen on a rail and when they will be scanned. */
    public static class Pending {
        public int count;
        public long scanAtMs;
        /** True when the unplugged-and-back units explain all of them (a restart, not a new feeder). */
        public boolean returning;
    }

    private final OrionManager mgr;
    private Thread thread;
    private volatile boolean stop;
    private volatile String status = "Monitor not started";
    private volatile boolean paused;
    private volatile long lastSweepMs;
    private volatile long sweepCount;

    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Map<Integer, Integer> badSweeps = new HashMap<>();
    private int resetsSinceHealthy;
    private long lastResetMs;
    private int transportFailures;
    private long nextReconnectMs;
    private int reconnectAttempts;
    private volatile boolean scanInFlight;
    private boolean wasConnected;

    OrionBusMonitor(OrionManager mgr) {
        this.mgr = mgr;
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        stop = false;
        thread = new Thread(this::loop, "Orion-Monitor");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        stop = true;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** One line for the UI. */
    public String getStatus() {
        return status;
    }

    public boolean isPaused() {
        return paused;
    }

    public long getSweepCount() {
        return sweepCount;
    }

    public long getLastSweepMs() {
        return lastSweepMs;
    }

    /** Units waiting to be scanned on this rail, or null. A copy. */
    public synchronized Pending getPending(int rail) {
        Pending p = pending.get(rail);
        if (p == null) {
            return null;
        }
        Pending c = new Pending();
        c.count = p.count;
        c.scanAtMs = p.scanAtMs;
        c.returning = p.returning;
        return c;
    }

    /** "Scan now" on the new-feeder message: do not wait for the delay. */
    public synchronized void scanNow(int rail) {
        Pending p = pending.get(rail);
        if (p == null) {
            p = new Pending();
            p.count = 1;
            pending.put(rail, p);
        }
        p.scanAtMs = System.currentTimeMillis();
        if (thread != null) {
            thread.interrupt(); // wake the sleep
        }
    }

    /** The user dismissed the message: keep looking but do not scan automatically for these units. */
    public synchronized void cancelAutoScan(int rail) {
        Pending p = pending.get(rail);
        if (p != null) {
            p.scanAtMs = Long.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------ the loop

    private void loop() {
        while (!stop) {
            long intervalMs = 3000;
            try {
                OrionSettings s = mgr.getSettings();
                intervalMs = s.getMonitorIntervalS() * 1000L;
                if (!s.isMonitorEnabled()) {
                    status = "Monitoring is switched off";
                } else if (jobOrTaskActive()) {
                    paused = true;
                    status = "Paused while a job or machine task is running";
                } else {
                    paused = false;
                    sweep(s);
                }
            } catch (InterruptedException e) {
                if (stop) {
                    return;
                }
                // woken by scanNow: go round immediately
                continue;
            } catch (Throwable t) {
                Logger.warn("Orion monitor: " + t);
                status = "Monitor error: " + t.getMessage();
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                if (stop) {
                    return;
                }
            }
        }
    }

    /** A job in any state but stopped, or any machine task (jog, home, a feed started from the UI). */
    private boolean jobOrTaskActive() {
        try {
            org.openpnp.gui.MainFrame mf = org.openpnp.gui.MainFrame.get();
            if (mf != null && mf.getJobTab() != null && mf.getJobTab().isJobActive()) {
                return true;
            }
            Machine m = org.openpnp.model.Configuration.get().getMachine();
            return m != null && m.isBusy();
        } catch (Throwable t) {
            return false;
        }
    }

    private void sweep(OrionSettings s) throws InterruptedException {
        if (!mgr.isConnected()) {
            tryReconnect();
            return;
        }
        wasConnected = true;
        reconnectAttempts = 0;
        boolean anyChange = false;
        boolean anyOnline = false;
        boolean anyKnown = false;
        boolean transportProblem = false;
        List<OrionBus> buses = mgr.getBuses();
        for (OrionBus bus : buses) {
            int rail = bus.getRail();
            List<OrionDeviceInfo> known = bus.getDevices();
            int answered = 0;
            int missed = 0;
            try {
                for (OrionDeviceInfo d : known) {
                    if (stop || jobOrTaskActive()) {
                        return;
                    }
                    OrionDeviceInfo.State before = d.state;
                    boolean changed = bus.monitorPing(d.address, LOSS_THRESHOLD);
                    OrionDeviceInfo now = bus.getDevice(d.address);
                    if (now == null) {
                        continue;
                    }
                    if (now.missedPings == 0) {
                        answered++;
                    } else {
                        missed++;
                    }
                    if (changed) {
                        anyChange = true;
                        if (now.state == OrionDeviceInfo.State.LOST) {
                            mgr.notice(new Notice(Notice.Kind.LOST, rail, "Feeder " + describe(now)
                                    + " stopped answering (unplugged?)"));
                        } else if (before == OrionDeviceInfo.State.LOST) {
                            mgr.notice(new Notice(Notice.Kind.BACK, rail, "Feeder " + describe(now)
                                    + " is back"));
                        }
                    }
                }
                anyKnown |= !known.isEmpty();
                anyOnline |= answered > 0;
                // Nobody on this rail answers although feeders were known: a hung bus, not a missing feeder.
                if (!known.isEmpty() && answered == 0 && missed > 0) {
                    badSweeps.merge(rail, 1, Integer::sum);
                } else {
                    badSweeps.put(rail, 0);
                }
                transportFailures = 0;
                if (stop || jobOrTaskActive()) {
                    return;
                }
                anyChange |= watchForNewFeeders(bus, s);
            } catch (OrionException e) {
                if (e.kind == OrionException.Kind.TRANSPORT) {
                    transportProblem = true;
                    bus.markAllLost("adapter not responding");
                    anyChange = true;
                } else {
                    Logger.trace("Orion monitor: " + e.getMessage());
                }
            }
        }
        sweepCount++;
        lastSweepMs = System.currentTimeMillis();
        if (transportProblem) {
            transportFailures++;
            status = "Adapter not responding (" + transportFailures + ")";
            if (transportFailures >= 2) {
                recover(false, "the adapter returned errors");
            }
        } else if (anyKnown && !anyOnline && maxBad() >= SWEEPS_BEFORE_SOFT_RESET) {
            recover(resetsSinceHealthy >= SOFT_RESETS_BEFORE_HARD,
                    "no feeder has answered for " + maxBad() + " sweeps");
        } else {
            if (anyOnline) {
                resetsSinceHealthy = 0;
            }
            status = anyKnown ? "Watching " + countOnline(buses) + " feeder(s)" : "Watching (no feeders yet)";
        }
        if (anyChange) {
            mgr.refresh();
        }
    }

    private int maxBad() {
        int m = 0;
        for (int v : badSweeps.values()) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static int countOnline(List<OrionBus> buses) {
        int n = 0;
        for (OrionBus b : buses) {
            for (OrionDeviceInfo d : b.getDevices()) {
                if (d.state != OrionDeviceInfo.State.LOST) {
                    n++;
                }
            }
        }
        return n;
    }

    static String describe(OrionDeviceInfo d) {
        String comp = d.componentId == 0xFFFF ? "no part" : "part " + d.componentId;
        return "#" + d.address + " (" + comp + ", rail " + (d.rail + 1) + ")";
    }

    // ------------------------------------------------------------------ new feeders

    /** @return true if the device table changed */
    private boolean watchForNewFeeders(OrionBus bus, OrionSettings s) throws OrionException {
        int rail = bus.getRail();
        int unassigned = bus.peekUnassigned();
        long now = System.currentTimeMillis();
        int lost = 0;
        for (OrionDeviceInfo d : bus.getDevices()) {
            if (d.state == OrionDeviceInfo.State.LOST) {
                lost++;
            }
        }
        boolean doScan = false;
        synchronized (this) {
            Pending p = pending.get(rail);
            if (unassigned == 0) {
                pending.remove(rail);
                return false;
            }
            long delay = s.getAutoScanDelayS() * 1000L;
            boolean returning = unassigned <= lost;
            if (p == null || p.count != unassigned) {
                boolean first = p == null;
                boolean dismissed = p != null && p.scanAtMs == Long.MAX_VALUE;
                if (p == null) {
                    p = new Pending();
                    pending.put(rail, p);
                }
                p.count = unassigned;
                p.returning = returning;
                // Units that were here before are restored quickly: nothing new to wait for.
                p.scanAtMs = dismissed ? Long.MAX_VALUE : now + (returning ? Math.min(delay, 5000) : delay);
                mgr.notice(new Notice(Notice.Kind.FOUND, rail, returning
                        ? unassigned + " feeder(s) restarted on rail " + (rail + 1) + ", restoring them"
                        : (first ? "Found " : "Found another: ") + unassigned + " new feeder(s) on rail "
                                + (rail + 1) + ". Scanning the rail in " + (delay / 1000) + " s unless more are added"));
                mgr.refresh();
            }
            doScan = now >= p.scanAtMs && !scanInFlight;
        }
        if (doScan) {
            autoScan(bus, s);
            return true;
        }
        return false;
    }

    private void autoScan(OrionBus bus, OrionSettings s) {
        scanInFlight = true;
        int rail = bus.getRail();
        status = "Scanning rail " + (rail + 1) + "...";
        org.openpnp.util.UiUtils.submitUiMachineTask(() -> {
            int added = bus.scan(null);
            int created = 0;
            if (s.isAutoCreateFeeders()) {
                created = mgr.createFeedersForUnbound(rail).size();
            }
            synchronized (OrionBusMonitor.this) {
                pending.remove(rail);
            }
            mgr.notice(new Notice(Notice.Kind.ADDED, rail, "Rail " + (rail + 1) + " scanned: " + added
                    + " feeder(s) added" + (created > 0 ? ", " + created + " OpenPnP feeder(s) created" : "")));
            mgr.refresh();
            if (added > 0 && org.openpnp.model.Configuration.get().getMachine().isHomed()) {
                mgr.offerSetupCycle("New feeder(s) were added to rail " + (rail + 1)
                        + ". Verify the rail by vision and set up their pick positions now?");
            }
            return null;
        }, r -> scanInFlight = false, t -> {
            scanInFlight = false;
            mgr.notice(new Notice(Notice.Kind.ERROR, rail, "Automatic scan of rail " + (rail + 1) + " failed: "
                    + t.getMessage()));
        }, true);
    }

    // ------------------------------------------------------------------ recovery

    private void recover(boolean hard, String why) {
        long now = System.currentTimeMillis();
        if (now - lastResetMs < MIN_RESET_GAP_MS) {
            return;
        }
        lastResetMs = now;
        resetsSinceHealthy++;
        status = hard ? "Resetting the bus (power cycle)..." : "Resetting the bus...";
        mgr.notice(new Notice(Notice.Kind.RESET, 0, (hard ? "Hard reset of the bus: " : "Resetting the bus: ") + why));
        boolean ok = mgr.resetBus(hard, why);
        badSweeps.clear();
        transportFailures = 0;
        if (!ok) {
            mgr.notice(new Notice(Notice.Kind.ERROR, 0, "The bus could not be reopened. Check the USB adapter; "
                    + "it will be retried automatically."));
        }
    }

    /** The port is closed although the user did not close it (adapter unplugged): keep trying, with backoff. */
    private void tryReconnect() {
        if (!wasConnected || mgr.isUserDisconnected()) {
            status = "Not connected";
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextReconnectMs) {
            status = "Adapter lost, retrying in " + Math.max(1, (nextReconnectMs - now) / 1000) + " s";
            return;
        }
        reconnectAttempts++;
        nextReconnectMs = now + Math.min(30000, 2000L << Math.min(4, reconnectAttempts));
        status = "Reconnecting (try " + reconnectAttempts + ")...";
        try {
            mgr.reconnectKeepingTable();
            reconnectAttempts = 0;
            mgr.notice(new Notice(Notice.Kind.RESET, 0, "Adapter is back, bus reopened"));
        } catch (OrionException e) {
            if (reconnectAttempts == 1 || reconnectAttempts % 5 == 0) {
                mgr.notice(new Notice(Notice.Kind.ERROR, 0, "Cannot reopen the adapter: " + e.getMessage()));
            }
        }
    }
}
