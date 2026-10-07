package org.openpnp.machine.orion;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;
import org.openpnp.machine.orion.protocol.OrionException;
import org.openpnp.machine.orion.protocol.SimulatedOrionTransport;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.common.io.Files;

public class OrionFeederTest {
    File dir;
    OrionManager mgr;
    Machine machine;

    @BeforeEach
    void setUp() throws Exception {
        dir = new File(Files.createTempDir(), ".openpnp");
        Configuration.initialize(dir);
        Configuration.get().load();
        machine = Configuration.get().getMachine();
        mgr = OrionManager.get();
        mgr.disconnect();
        mgr.getSettings().setMode(OrionSettings.Mode.SIMULATED);
    }

    private OrionFeeder bound(int rail) throws Exception {
        mgr.connect();
        mgr.getBus(rail).scan(null);
        OrionDeviceInfo d = mgr.getBus(rail).getDevices().get(0);
        OrionFeeder f = new OrionFeeder();
        f.setName("t" + rail);
        f.setSerial(d.serial);
        f.setRail(rail);
        machine.addFeeder(f);
        return f;
    }

    private SimulatedOrionTransport sim() {
        return (SimulatedOrionTransport) mgr.getTransport();
    }

    @Test
    void feedPushesPitchAndAdvances() throws Exception {
        OrionFeeder f = bound(0);
        f.setPitchMm(4);
        f.setPeelMsPerMm(12.5);
        f.setPeelTimeMs(300);
        f.feed(null);
        SimulatedOrionTransport.SimFeeder s = sim().getFeeders().get(0);
        assertEquals(1, s.feeds);
        assertEquals(2, s.halfTeeth);
        assertEquals(125, s.peelRate);
        assertEquals(300, s.peelTimeMs);
    }

    @Test
    void feederFollowsUnitAcrossRebootAndRailLoss() throws Exception {
        OrionFeeder f = bound(0);
        f.feedOnce();
        SimulatedOrionTransport.SimFeeder s = sim().getFeeders().get(0);
        sim().reboot(s); // loses its address, pitch config is re-pushed after rediscovery
        f.feedOnce();
        assertEquals(2, s.feeds);
        s.present = false;
        OrionException e = assertThrows(OrionException.class, f::feedOnce);
        assertTrue(e.getMessage().contains("not found") || e.getMessage().contains("did not respond"));
    }

    @Test
    void skipNextAndDisableDoNotMove() throws Exception {
        OrionFeeder f = bound(0);
        f.setFeedOptions(OrionFeeder.FeedOptions.SkipNext);
        f.feed(null);
        assertEquals(0, sim().getFeeders().get(0).feeds);
        f.feed(null);
        assertEquals(1, sim().getFeeders().get(0).feeds);
    }

    @Test
    void unboundFeederGivesHelpfulError() throws Exception {
        mgr.connect();
        OrionFeeder f = new OrionFeeder();
        f.setName("x");
        OrionException e = assertThrows(OrionException.class, f::feedOnce);
        assertEquals(OrionException.Kind.NOT_FOUND, e.kind);
    }

    @Test
    void duplicateSerialOnTwoRailsIsReportedAsConflict() throws Exception {
        mgr.connect();
        SimulatedOrionTransport.SimFeeder a = sim().getFeeders().get(0);
        SimulatedOrionTransport.SimFeeder b = sim().getFeeders().get(1);
        System.arraycopy(a.serial, 0, b.serial, 0, 16);
        for (OrionBus bus : mgr.getBuses()) {
            bus.scan(null);
        }
        OrionFeeder f = new OrionFeeder();
        f.setName("dup");
        f.setSerial(serialHex(a.serial));
        OrionException e = assertThrows(OrionException.class, f::feedOnce);
        assertEquals(OrionException.Kind.CONFLICT, e.kind);
    }

    private static String serialHex(byte[] s) {
        return org.openpnp.machine.orion.protocol.OrionFrame.toHex(s);
    }

    @Test
    void feederAndSettingsSurviveSaveAndReload() throws Exception {
        OrionFeeder f = bound(1);
        org.openpnp.model.Package pkg = new org.openpnp.model.Package("test-pkg");
        Configuration.get().addPackage(pkg);
        org.openpnp.model.Part part = new org.openpnp.model.Part("test-part");
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        f.setPart(part);
        f.setPitchMm(8);
        f.setPeelMsPerMm(7.5);
        mgr.getSettings().setAdapterPort("COM9");
        String serial = f.getSerial();
        Configuration.get().save();

        Configuration.initialize(dir);
        Configuration.get().load();
        Machine m = Configuration.get().getMachine();
        OrionFeeder loaded = null;
        for (Feeder x : m.getFeeders()) {
            if (x instanceof OrionFeeder) {
                loaded = (OrionFeeder) x;
            }
        }
        assertNotNull(loaded);
        assertEquals(serial, loaded.getSerial());
        assertEquals(8, loaded.getPitchMm());
        assertEquals(7.5, loaded.getPeelMsPerMm());
        assertEquals(1, loaded.getRail());
        assertEquals("COM9", mgr.getSettings().getAdapterPort());
    }

    @Test
    void followingSlotPositionShiftsPickX() throws Exception {
        OrionFeeder f = bound(0);
        SimulatedOrionTransport.SimFeeder s = sim().getFeeders().get(0);
        f.setLocation(new org.openpnp.model.Location(org.openpnp.model.LengthUnit.Millimeters, 100, 50, 0, 0));
        f.writeSlotPosition(20.0);
        f.teachSlotPosition();
        f.setFollowSlotPosition(true);
        assertEquals(100.0, f.getPickLocation().getX(), 1e-9);
        f.writeSlotPosition(26.5); // unit now sits in another slot
        assertEquals(106.5, f.getPickLocation().getX(), 1e-9);
        assertEquals(265, s.posRaw);
    }

    @Test
    void foundFiducialMovesPickXAndNominalByTheSameAmount() throws Exception {
        OrionFeeder f = bound(0);
        OrionRailSettings rs = mgr.getSettings().getRailSettings(0);
        rs.setFiducialY(20);
        rs.setFiducialZ(-5);
        org.openpnp.model.LengthUnit mm = org.openpnp.model.LengthUnit.Millimeters;
        f.setLocation(new org.openpnp.model.Location(mm, 100, 50, 0, 0));
        // first sighting: no nominal yet, only the nominal is recorded
        f.applyFoundFiducial(new org.openpnp.model.Location(mm, 95, 20, -5, 0), rs);
        assertEquals(100.0, f.getLocation().getX(), 1e-9);
        assertEquals(95.0, f.getFiducialNominal().getX(), 1e-9);
        // later it is seen 0.3 mm further along
        f.applyFoundFiducial(new org.openpnp.model.Location(mm, 95.3, 20, -5, 0), rs);
        assertEquals(100.3, f.getLocation().getX(), 1e-9);
        assertEquals(95.3, f.getFiducialNominal().getX(), 1e-9);
        assertEquals(50.0, f.getLocation().getY(), 1e-9);
    }

    @Test
    void railSettingsSurviveSaveAndReload() throws Exception {
        OrionRailSettings rs = mgr.getSettings().getRailSettings(1);
        rs.setXMin(10);
        rs.setXMax(400);
        rs.setScanStepMm(5);
        mgr.getSettings().setLargeFiducialPartId("FID-L");
        Configuration.get().save();
        Configuration.initialize(dir);
        Configuration.get().load();
        OrionRailSettings back = mgr.getSettings().getRailSettings(1);
        assertEquals(400.0, back.getXMax());
        assertEquals(5.0, back.getScanStepMm());
        assertEquals("FID-L", mgr.getSettings().getLargeFiducialPartId());
    }

    @Test
    void unverifiedRailWarnsBlocksOrIsIgnoredAccordingToPolicy() throws Exception {
        OrionFeeder f = bound(0);
        mgr.clearRailChecks();
        assertNotNull(mgr.railCheckProblem(0), "never verified");

        mgr.getSettings().setUnverifiedPolicy(OrionSettings.UnverifiedPolicy.Off);
        f.prepareForJob(false); // silently fine

        mgr.getSettings().setUnverifiedPolicy(OrionSettings.UnverifiedPolicy.Warn);
        f.prepareForJob(false); // warns, does not throw
        assertTrue(mgr.getLog().stream().anyMatch(e -> e.text.contains("not been checked")));

        mgr.getSettings().setUnverifiedPolicy(OrionSettings.UnverifiedPolicy.Block);
        assertThrows(Exception.class, () -> f.prepareForJob(false));

        // a clean verify clears it
        mgr.recordRailCheck(0, true, "ok");
        assertNull(mgr.railCheckProblem(0));
        f.prepareForJob(false);

        // ... until the feeders on the rail change
        sim().addFeeder(0, 77);
        mgr.getBus(0).scan(null);
        assertNotNull(mgr.railCheckProblem(0));

        // and a verify that found problems is not trusted either
        mgr.recordRailCheck(0, false, "1 missing");
        assertTrue(mgr.railCheckProblem(0).contains("1 missing"));
    }

    @Test
    void identificationSettingsAndPipelinesSurviveSaveAndReload() throws Exception {
        OrionSettings st = mgr.getSettings();
        st.setIdentifyMethod(OrionSettings.IdentifyMethod.TapeMovement);
        st.setFiberThreshold(77);
        st.getFiberPipeline(); // creates the default
        mgr.getSettings().getRailSettings(0).setFiberOffsetX(12.5);
        Configuration.get().save();
        Configuration.initialize(dir);
        Configuration.get().load();
        OrionSettings back = mgr.getSettings();
        assertEquals(OrionSettings.IdentifyMethod.TapeMovement, back.getIdentifyMethod());
        assertEquals(77.0, back.getFiberThreshold());
        assertEquals(12.5, back.getRailSettings(0).getFiberOffsetX());
        assertEquals(4, back.getFiberPipeline().getStages().size());
    }
}
