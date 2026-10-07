package org.openpnp.machine.orion.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OrionBusTest {
    SimulatedOrionTransport sim;
    OrionBus bus;

    @BeforeEach
    void setUp() throws Exception {
        sim = new SimulatedOrionTransport(2);
        sim.open();
        bus = new OrionBus(sim, 0);
        bus.setRetries(0);
    }

    @Test
    public void crcMatchesFirmwareVector() {
        // Known: CRC8 poly 0x07 of "123456789" is 0xF4
        assertEquals(0xF4, OrionFrame.crc8("123456789".getBytes(), 0, 9));
    }

    @Test
    public void frameRoundTripAndGarbageSkipping() {
        OrionFrame f = new OrionFrame(5, OrionCommand.JOG, (byte) 0x00, (byte) 0x28);
        byte[] enc = f.encode();
        byte[] noisy = new byte[enc.length + 3];
        System.arraycopy(enc, 0, noisy, 3, enc.length);
        noisy[0] = 0x55;
        noisy[1] = (byte) 0xAA;
        OrionFrame back = OrionFrame.findFirst(noisy).get();
        assertEquals(5, back.address);
        assertEquals(0x0028, back.u16(0));
        enc[enc.length - 1] ^= 1;
        assertFalse(OrionFrame.decode(enc).isPresent());
    }

    @Test
    public void scanAssignsAddressesToAllNewFeeders() throws Exception {
        for (int i = 0; i < 5; i++) {
            sim.addFeeder(0, i + 1);
        }
        sim.addFeeder(1, 99); // other rail must not appear
        int added = bus.scan(null);
        assertEquals(5, added);
        assertEquals(5, bus.getDevices().size());
        for (OrionDeviceInfo d : bus.getDevices()) {
            assertEquals(OrionDeviceInfo.State.ONLINE, d.state);
            assertEquals(32, d.serial.length());
        }
    }

    @Test
    public void rescanKeepsAddressesAndFindsOnlyNewUnit() throws Exception {
        sim.addFeeder(0, 1);
        bus.scan(null);
        int before = bus.getDevices().get(0).address;
        sim.addFeeder(0, 2);
        assertEquals(1, bus.scan(null));
        assertEquals(2, bus.getDevices().size());
        assertEquals(before, bus.getDevices().get(0).address);
    }

    @Test
    public void rebootedFeederIsLostThenRecoveredByDiscover() throws Exception {
        SimulatedOrionTransport.SimFeeder f = sim.addFeeder(0, 1);
        bus.scan(null);
        String serial = bus.getDevices().get(0).serial;
        sim.reboot(f);
        bus.scan(null); // probing marks lost, discover reassigns
        List<OrionDeviceInfo> devs = bus.getDevices();
        OrionDeviceInfo online = bus.findBySerial(serial);
        assertNotNull(online);
        assertEquals(OrionDeviceInfo.State.ONLINE, online.state);
        assertEquals(1, devs.stream().filter(d -> serial.equals(d.serial)).count());
    }

    @Test
    public void unpluggedFeederBecomesLost() throws Exception {
        SimulatedOrionTransport.SimFeeder f = sim.addFeeder(0, 1);
        bus.scan(null);
        f.present = false;
        bus.scan(null);
        assertEquals(OrionDeviceInfo.State.LOST, bus.getDevices().get(0).state);
    }

    @Test
    public void duplicateAddressIsNotValidReplyAndTimesOut() throws Exception {
        SimulatedOrionTransport.SimFeeder a = sim.addFeeder(0, 1);
        SimulatedOrionTransport.SimFeeder b = sim.addFeeder(0, 2);
        a.address = 7;
        b.address = 7;
        try {
            bus.getStatus(7);
            fail("expected timeout");
        } catch (OrionException e) {
            assertEquals(OrionException.Kind.TIMEOUT, e.kind);
        }
    }

    @Test
    public void feedNackSurfacesErrorCode() throws Exception {
        SimulatedOrionTransport.SimFeeder f = sim.addFeeder(0, 1);
        bus.scan(null);
        int addr = bus.getDevices().get(0).address;
        try {
            bus.feedNext(addr);
            fail();
        } catch (OrionException e) {
            assertEquals(OrionError.NOT_READY, e.error);
        }
        bus.setPitchMm(addr, 4);
        bus.feedNext(addr);
        assertEquals(1, f.feeds);
        f.failNextMove = OrionError.STALL;
        try {
            bus.feedNext(addr);
            fail();
        } catch (OrionException e) {
            assertEquals(OrionError.STALL, e.error);
        }
    }

    @Test
    public void parameterRoundTrips() throws Exception {
        sim.addFeeder(0, 1);
        bus.scan(null);
        int a = bus.getDevices().get(0).address;
        assertEquals(-1, bus.getPeelTimeMs(a));
        bus.setPeelTimeMs(a, 450);
        assertEquals(450, bus.getPeelTimeMs(a));
        bus.setPeelRate(a, 120);
        assertEquals(120, bus.getPeelRate(a));
        bus.setComponent(a, 77);
        assertEquals(77, bus.getComponent(a)[0]);
        OrionStatus st = bus.getStatus(a);
        assertTrue(st.magnetDetected());
    }

    @Test
    public void twoRailsAreIndependent() throws Exception {
        sim.addFeeder(0, 1);
        sim.addFeeder(1, 2);
        OrionBus bus1 = new OrionBus(sim, 1);
        bus.scan(null);
        bus1.scan(null);
        assertEquals(1, bus.getDevices().size());
        assertEquals(1, bus1.getDevices().size());
        assertNotEquals(bus.getDevices().get(0).serial, bus1.getDevices().get(0).serial);
    }

    @Test
    void slotPositionIsReadOnScanAndWritable() throws Exception {
        SimulatedOrionTransport.SimFeeder f = sim.addFeeder(0, 1);
        f.posRaw = 1234;
        bus.scan(null);
        OrionDeviceInfo d = bus.getDevices().get(0);
        assertEquals(123.4, d.slotXMm, 1e-9);
        bus.setSlotPosition(d.address, 14.3);
        assertEquals(143, f.posRaw);
        assertEquals(14.3, bus.getSlotPosition(d.address), 1e-9);
        bus.setSlotPosition(d.address, Double.NaN);
        assertTrue(Double.isNaN(bus.getSlotPosition(d.address)));
    }

    @Test
    void rebootedUnitsGetTheirOldAddressesBack() throws Exception {
        SimulatedOrionTransport.SimFeeder a = sim.addFeeder(0, 1);
        SimulatedOrionTransport.SimFeeder b = sim.addFeeder(0, 2);
        SimulatedOrionTransport.SimFeeder c = sim.addFeeder(0, 3);
        bus.scan(null);
        int ia = a.address;
        int ib = b.address;
        int ic = c.address;
        sim.reboot(a);
        sim.reboot(b);
        sim.reboot(c);
        OrionBus fresh = new OrionBus(sim, 0); // host restarted, empty table
        fresh.setRetries(0);
        fresh.discoverAndAssign();
        assertEquals(ia, a.address);
        assertEquals(ib, b.address);
        assertEquals(ic, c.address);
    }

    @Test
    void ambiguousLastAddressIsNotRestored() throws Exception {
        SimulatedOrionTransport.SimFeeder a = sim.addFeeder(0, 1);
        SimulatedOrionTransport.SimFeeder b = sim.addFeeder(0, 2);
        a.lastAddr = 5;
        b.lastAddr = 5;
        bus.discoverAndAssign();
        assertNotEquals(a.address, b.address);
        assertTrue(a.address != 0 && b.address != 0);
    }
}
