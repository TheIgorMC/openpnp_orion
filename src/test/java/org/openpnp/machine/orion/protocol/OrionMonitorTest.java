package org.openpnp.machine.orion.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The bus-level pieces the background monitor is built from. */
public class OrionMonitorTest {
    SimulatedOrionTransport sim;
    OrionBus bus;
    SimulatedOrionTransport.SimFeeder a, b;

    @BeforeEach
    void setUp() throws Exception {
        sim = new SimulatedOrionTransport(1);
        sim.open();
        bus = new OrionBus(sim, 0);
        bus.setRetries(0);
        a = sim.addFeeder(0, 1);
        b = sim.addFeeder(0, 2);
        bus.scan(null);
    }

    @Test
    public void pingKeepsFeedersOnlineAndRecordsRoundTrip() throws Exception {
        assertFalse(bus.monitorPing(1, 2));
        OrionDeviceInfo d = bus.getDevice(1);
        assertEquals(OrionDeviceInfo.State.ONLINE, d.state);
        assertEquals(0, d.missedPings);
        assertTrue(d.lastPingMs >= 0);
    }

    @Test
    public void unpluggedFeederIsLostAfterTwoMissesNotOne() throws Exception {
        b.present = false;
        assertFalse(bus.monitorPing(b.address, 2), "one miss must not flip the state");
        assertEquals(1, bus.getDevice(b.address).missedPings);
        assertTrue(bus.monitorPing(b.address, 2));
        assertEquals(OrionDeviceInfo.State.LOST, bus.getDevice(b.address).state);
        assertEquals(OrionDeviceInfo.State.ONLINE, bus.getDevice(a.address).state);
    }

    @Test
    public void pluggedBackFeederTurnsGreenAgain() throws Exception {
        b.present = false;
        bus.monitorPing(b.address, 1);
        assertEquals(OrionDeviceInfo.State.LOST, bus.getDevice(b.address).state);
        b.present = true;
        assertTrue(bus.monitorPing(b.address, 1));
        OrionDeviceInfo d = bus.getDevice(b.address);
        assertEquals(OrionDeviceInfo.State.ONLINE, d.state);
        assertEquals(0, d.missedPings);
    }

    @Test
    public void peekSeesNewFeederWithoutAssigningIt() throws Exception {
        assertEquals(0, bus.peekUnassigned());
        sim.addFeeder(0, 3);
        assertEquals(1, bus.peekUnassigned());
        assertEquals(1, bus.peekUnassigned(), "peeking must not hand out an address");
        assertEquals(2, bus.getDevices().size());
        assertEquals(1, bus.scan(null));
        assertEquals(0, bus.peekUnassigned());
        assertEquals(3, bus.getDevices().size());
    }

    @Test
    public void restartedFeederShowsUpAsUnassignedAndGetsItsOldAddressBack() throws Exception {
        int addr = a.address;
        sim.reboot(a);
        bus.monitorPing(addr, 1);
        assertEquals(OrionDeviceInfo.State.LOST, bus.getDevice(addr).state);
        assertEquals(1, bus.peekUnassigned());
        bus.discoverAndAssign();
        assertEquals(addr, a.address);
        assertEquals(OrionDeviceInfo.State.ONLINE, bus.getDevice(addr).state);
    }

    @Test
    public void monitorTrafficIsNotLogged() throws Exception {
        java.util.List<String> lines = new java.util.ArrayList<>();
        bus.addListener((rail, dir, text) -> lines.add(text));
        bus.monitorPing(1, 2);
        bus.peekUnassigned();
        assertTrue(lines.isEmpty(), "monitor pings would drown the log: " + lines);
    }

    @Test
    public void transportFailureIsReportedAndAllFeedersShowLost() throws Exception {
        sim.close();
        OrionException e = assertThrows(OrionException.class, () -> bus.monitorPing(1, 2));
        assertEquals(OrionException.Kind.TRANSPORT, e.kind);
        bus.markAllLost("adapter not responding");
        for (OrionDeviceInfo d : bus.getDevices()) {
            assertEquals(OrionDeviceInfo.State.LOST, d.state);
        }
        sim.open();
        bus.noteBusReset();
        assertTrue(bus.monitorPing(1, 2));
        assertEquals(OrionDeviceInfo.State.ONLINE, bus.getDevice(1).state);
    }
}
