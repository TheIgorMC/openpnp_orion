package org.openpnp.machine.orion.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OrionConveyorTest {
    SimulatedOrionTransport sim;
    SimulatedConveyor belt;
    OrionBus bus;
    OrionConveyor conv;

    @BeforeEach
    void setUp() throws Exception {
        sim = new SimulatedOrionTransport(2);
        sim.open();
        belt = sim.addConveyor(1);
        bus = new OrionBus(sim, 1);
        bus.setRetries(0);
        bus.setConveyorAddress(OrionCommand.ADDR_CONVEYOR);
        sim.addFeeder(1, 1);
        sim.addFeeder(1, 2);
        bus.scan(null);
        conv = bus.getConveyor();
    }

    @Test
    public void scanFindsTheConveyorAtItsFixedAddressAndNotAsAFeeder() {
        assertNotNull(conv);
        assertTrue(conv.hasClamp() && conv.hasStopper() && conv.hasWidthAxis());
        assertEquals(2, bus.getFeeders().size());
        assertEquals(3, bus.getDevices().size());
        assertEquals(OrionDeviceInfo.Kind.CONVEYOR, bus.getDevice(240).kind);
        for (OrionDeviceInfo d : bus.getFeeders()) {
            assertTrue(d.address < 240, "feeders never get a reserved address");
        }
    }

    @Test
    public void feedersCannotBeGivenReservedAddresses() {
        bus.setMaxAddress(247);
        assertEquals(239, bus.getMaxAddress());
    }

    @Test
    public void conveyorIsNotInvolvedInAddressAssignment() throws Exception {
        sim.addFeeder(1, 3);
        assertEquals(1, bus.scan(null));
        assertEquals(OrionDeviceInfo.State.ONLINE, bus.getDevice(240).state);
    }

    @Test
    public void monitorPingKeepsItGreenAndNoticesWhenItIsUnplugged() throws Exception {
        assertFalse(bus.monitorPing(240, 2));
        belt.present = false;
        bus.monitorPing(240, 2);
        assertTrue(bus.monitorPing(240, 2));
        assertEquals(OrionDeviceInfo.State.LOST, bus.getDevice(240).state);
        belt.present = true;
        assertTrue(bus.monitorPing(240, 2));
    }

    @Test
    public void insertedBoardIsReportedThenLoadedMeasuredAndClamped() throws Exception {
        assertEquals(ConveyorStatus.IDLE, conv.getStatus().state);
        belt.insertBoard(120.0);
        ConveyorStatus s = conv.getStatus();
        assertTrue(s.insertedBoard());
        assertTrue(s.entryBlocked());
        s = conv.loadBoard(true, null);
        assertTrue(s.boardLoaded());
        assertTrue(s.clamped());
        assertTrue(s.stopperOut());
        assertEquals(120.0, s.lengthMm(), 0.001);
        assertEquals(250.0, s.posMm(), 0.001, "leading edge at the pin");
        assertFalse(s.entryBlocked());
    }

    @Test
    public void tooLongBoardIsRefusedByTheMeasuringPass() throws Exception {
        belt.insertBoard(300.0);
        conv.load(false);
        OrionException e = assertThrows(OrionException.class, () -> conv.awaitIdle(1000, null));
        assertEquals(OrionError.TOO_LONG, e.error);
    }

    @Test
    public void beltMovesAreRefusedWhileClamped() throws Exception {
        belt.insertBoard(100.0);
        conv.loadBoard(true, null);
        OrionException e = assertThrows(OrionException.class, () -> conv.move(500));
        assertEquals(OrionError.CLAMPED, e.error);
    }

    @Test
    public void unloadBackOutLeavesTheBoardInTheSensorAndIsNotOfferedAsNew() throws Exception {
        belt.insertBoard(100.0);
        conv.loadBoard(true, null);
        ConveyorStatus s = conv.unloadBoard(null);
        assertFalse(s.boardLoaded());
        assertTrue(s.boardLeaving());
        assertFalse(s.insertedBoard(), "the board being taken out must not be offered for loading again");
        belt.removeBoardAtEntry();
        s = conv.getStatus();
        assertFalse(s.entryBlocked());
        assertEquals(ConveyorStatus.IDLE, s.state);
    }

    @Test
    public void backwardsUnloadIsRefusedWhileANextBoardWaitsAtTheEntry() throws Exception {
        belt.insertBoard(100.0);
        conv.loadBoard(true, null);
        belt.insertBoard(80.0); // the next one
        ConveyorStatus s = conv.getStatus();
        assertTrue(s.boardLoaded() && s.boardWaiting() && s.entryBlocked());
        assertFalse(s.insertedBoard(), "a loaded board means this is a waiting board, not a new one");
        conv.clamp(false);
        conv.awaitIdle(1000, null);
        OrionException e = assertThrows(OrionException.class, () -> conv.unload());
        assertEquals(OrionError.ENTRY_BLOCKED, e.error);
        belt.removeBoardAtEntry();
        conv.unload();
        conv.awaitIdle(1000, null);
    }

    @Test
    public void forwardUnloadMakesTheWaitingBoardTheNextOneReady() throws Exception {
        conv.setConfig(OrionConveyor.CFG_UNLOAD_DIR, 1);
        conv.setConfig(OrionConveyor.CFG_EXIT_DISTANCE, 20000);
        belt.insertBoard(100.0);
        conv.loadBoard(true, null);
        belt.insertBoard(80.0);
        ConveyorStatus s = conv.unloadBoard(null);
        assertFalse(s.boardLoaded());
        assertTrue(s.boardWaiting(), "next board ready");
        assertTrue(s.waitingLengthValid(), "its trailing edge passed the sensor");
        s = conv.loadBoard(true, null);
        assertTrue(s.boardLoaded());
        assertEquals(80.0, s.lengthMm(), 0.001);
    }

    @Test
    public void forwardUnloadIsRefusedWhenItWouldPushTheWaitingBoardPastTheStop() throws Exception {
        conv.setConfig(OrionConveyor.CFG_UNLOAD_DIR, 1);
        conv.setConfig(OrionConveyor.CFG_EXIT_DISTANCE, 30000); // > STOP_DISTANCE 250 mm
        belt.insertBoard(100.0);
        conv.loadBoard(true, null);
        belt.insertBoard(80.0);
        conv.clamp(false);
        conv.awaitIdle(1000, null);
        OrionException e = assertThrows(OrionException.class, () -> conv.unload());
        assertEquals(OrionError.NEXT_OVERRUN, e.error);
    }

    @Test
    public void widthNeedsAClearBeltAndCanBeHomedOrDefinedByHand() throws Exception {
        ConveyorStatus s = conv.getStatus();
        assertFalse(s.widthConfirmed(), "the saved width is not proof");
        belt.insertBoard(100.0);
        conv.loadBoard(true, null);
        OrionException e = assertThrows(OrionException.class, () -> conv.setWidth(1000, false));
        assertEquals(OrionError.BOARD_PRESENT, e.error);
        // clamped: even "clear" is refused, the user must release it first
        e = assertThrows(OrionException.class, () -> conv.setWidth(1000, true));
        assertEquals(OrionError.BOARD_PRESENT, e.error);
        conv.clamp(false);
        conv.awaitIdle(1000, null);
        conv.setWidth(1000, true); // the user says the belt is empty
        conv.awaitIdle(2000, null);
        s = conv.getStatus();
        assertEquals(100.0, s.widthMm(), 0.001);
        assertFalse(s.boardLoaded(), "the user's statement clears the board memory");
        conv.homeWidth(true);
        s = conv.awaitIdle(2000, null);
        assertTrue(s.widthConfirmed() && s.widthHomed());
        conv.defineWidth(1234);
        assertEquals(123.4, conv.getStatus().widthMm(), 0.001);
        e = assertThrows(OrionException.class, () -> conv.setWidth(5000, true));
        assertEquals(OrionError.WIDTH_LIMIT, e.error);
    }

    @Test
    public void clampStaysEngagedAcrossAPowerCycleSoAJobCanResume() throws Exception {
        belt.insertBoard(100.0);
        conv.loadBoard(true, null);
        belt.powerCycle();
        ConveyorStatus s = conv.getStatus();
        assertTrue(s.clamped() && s.boardLoaded() && s.posValid());
        assertEquals(250.0, s.posMm(), 0.001);
        assertFalse(s.widthConfirmed());
        OrionException e = assertThrows(OrionException.class, () -> conv.move(100));
        assertEquals(OrionError.CLAMPED, e.error);
    }

    @Test
    public void asynchronousMovesReportBusyUntilDone() throws Exception {
        belt.instant = false;
        belt.insertBoard(100.0);
        conv.load(false);
        assertTrue(conv.getStatus().busy());
        OrionException e = assertThrows(OrionException.class, () -> conv.move(100));
        assertEquals(OrionError.BUSY, e.error);
        conv.stop();
        assertFalse(conv.getStatus().busy());
    }
}
