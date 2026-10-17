package org.openpnp.machine.orion;

import java.util.ArrayList;
import java.util.List;

import javax.swing.Action;

import org.openpnp.machine.reference.vision.ReferenceFiducialLocator;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Part;
import org.openpnp.spi.FiducialLocator;
import org.simpleframework.xml.Element;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;
import org.openpnp.machine.orion.protocol.OrionError;
import org.openpnp.machine.orion.protocol.OrionException;
import org.openpnp.machine.orion.sheets.OrionBusManagerSheet;
import org.openpnp.machine.orion.sheets.OrionDebugSheet;
import org.openpnp.machine.orion.sheets.OrionFeederSheet;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

/**
 * A feeder on the OrionPnP RS485 bus. The feeder is bound to physical hardware by its factory
 * serial number, not by its (disposable) bus address, so it follows the unit across reboots,
 * rails and slots. Pitch / peel settings live here as well as on the unit, and are pushed to the
 * unit whenever it shows up with different values.
 */
public class OrionFeeder extends ReferenceFeeder {
    /** 32 hex chars of the factory serial, or null when not bound to hardware yet. */
    @Attribute(required = false)
    protected String serial;

    /** Last rail this feeder was seen on (hint only; the serial is the identity). */
    @Attribute(required = false)
    protected int rail = 0;

    @Attribute(required = false)
    /** Part pitch in mm pushed to the unit. 0 = leave whatever is stored on the unit. */
    protected int pitchMm = 0;

    /** Peel coupling in ms of peel per mm of sprocket travel. 0 = off, negative = leave as is. */
    @Attribute(required = false)
    protected double peelMsPerMm = -1;

    /** Standalone peel duration used by the Peel / Unpeel buttons, ms. Negative = leave as is. */
    @Attribute(required = false)
    protected int peelTimeMs = -1; // unused: the unit's own peel time is internal, kept only so old machine.xml files load

    @Attribute(required = false)
    protected int ledBrightness = 0;

    @Attribute(required = false)
    protected int feedRetries = 2;

    // ---- slot position (stored on the unit, 0.1 mm along the rail X)

    /** Slot X the unit reported when the pick location was taught, NaN = never taught. */
    @Attribute(required = false)
    protected double taughtSlotXMm = Double.NaN;

    /** Shift the pick X by how far the unit's slot X moved since teaching (feeder swapped slots). */
    @Attribute(required = false)
    protected boolean followSlotPosition = false;

    /** Latest slot X seen from the unit, NaN unknown. Not persisted. */
    private transient double currentSlotXMm = Double.NaN;

    // ---- vision (fiducial based fine X position)

    public enum VisionMode {
        Off("Off"),
        FirstFeedOfJob("On the first feed of each job"),
        EveryFeed("Before every feed");

        private final String label;

        VisionMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Attribute(required = false)
    protected VisionMode visionMode = VisionMode.Off;

    /** A fiducial part (with its own vision settings / pipeline) marking this feeder's true position. */
    @Attribute(required = false)
    protected String fiducialPartId;

    /** Where the fiducial was when the pick location was taught. */
    @Element(required = false)
    protected Location fiducialNominal;

    /** Only correct X (the feeder can only shift along the rail). */
    @Attribute(required = false)
    protected boolean visionXOnly = true;

    /** Corrections bigger than this are rejected as a bad detection. */
    @Attribute(required = false)
    protected double maxCorrectionMm = 2.0;

    // ---- vision: tape position from the sprocket holes (every pick)

    public enum HoleMode {
        Off("Off"),
        EveryFeed("After every feed"),
        FirstFeedOfJob("After the first feed of each job");

        private final String label;

        HoleMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Attribute(required = false)
    protected HoleMode holeMode = HoleMode.Off;

    /** Direction the tape runs in machine axes (rail along X: tape along Y). */
    @Attribute(required = false)
    protected org.openpnp.machine.orion.vision.OrionHoleVision.Axis tapeAxis =
            org.openpnp.machine.orion.vision.OrionHoleVision.Axis.Y;

    /** A sprocket hole of the taught row, seen while the taught pocket sat at the pick location. */
    @Element(required = false)
    protected Location holeNominal;

    /** Corrections bigger than this are rejected as a bad detection. */
    @Attribute(required = false)
    protected double maxHoleCorrectionMm = 0.8;

    public enum TapeTarget {
        SprocketHole("Sprocket hole (tape position only)"),
        Pocket("Pocket / part (centres the pick)");

        private final String label;

        TapeTarget(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What the per-pick check looks at. */
    @Attribute(required = false)
    protected TapeTarget tapeTarget = TapeTarget.SprocketHole;

    /** Expected pocket outline (mm) used to pick the right rectangle in the image. */
    @Attribute(required = false)
    protected double pocketWidthMm = 0; // 0 = any size

    @Attribute(required = false)
    protected double pocketLengthMm = 0;

    @Attribute(required = false)
    protected double pocketSizeTolPct = 35;

    /** Detected pocket centre minus the taught pick location, when taught: the pick spot inside the pocket. */
    @Element(required = false)
    protected Location pocketBias;

    /**
     * Where the pocket centre was when taught (machine coordinates), and the slot-follow / fiducial correction that
     * was in effect then. The shift is measured against this, not against the pick location, so the pick location
     * can be edited later without the correction undoing it.
     */
    @Element(required = false)
    protected Location pocketTaught;

    @Element(required = false)
    protected Location pocketExtra;

    /** Also correct across the tape (X when the tape runs along Y), not only along it. */
    @Attribute(required = false)
    protected boolean pocketCorrectAcross = true;

    /** Travel to just above the pick location while the feeder is still feeding. */
    @Attribute(required = false)
    protected boolean moveWhileFeeding = false;

    /** Obsolete (the nozzle no longer changes Z while feeding); kept only so older machine.xml files still load. */
    @Attribute(required = false)
    protected Double approachHeightMm;

    /** Move the tape with the unit's jog when the pocket is off along the tape by more than {@link #jogMinMm}. */
    @Attribute(required = false)
    protected boolean jogCorrectPocket = false;

    @Attribute(required = false)
    protected double jogMinMm = 0.15;

    /** +1 or -1: which way a positive jog moves the pocket along the machine axis (learned). */
    @Attribute(required = false)
    protected int jogSign = 1;

    /** Start the next feeders while the nozzle picks (job look-ahead). */
    @Attribute(required = false)
    protected boolean prefeedNext = true;

    /** Bottom vision lost the part: discard, re-check the pocket, feed and pick again (twice at most). */
    @Attribute(required = false)
    protected boolean repickOnVisionFail = true;

    /** Camera height the pocket was taught at; NaN = the camera's default Z. */
    @Attribute(required = false)
    protected double pocketCameraZ = Double.NaN;

    private transient double pocketShiftX;
    private transient double pocketShiftY;

    /** Tape shift measured at the last pick, along the tape axis, mm. Not persisted. */
    private transient double holeShiftMm;
    private transient boolean holeDoneThisJob;

    /** Correction found by the last detection, added to the taught pick location. Not persisted. */
    private transient Location visionDelta;
    private transient boolean visionDoneThisJob;

    /** Set once this session's settings have been pushed to the unit. */
    private transient String configuredForSerialAddress;

    public OrionFeeder() {
        Configuration.get().addListener(new org.openpnp.ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration configuration) {
                OrionManager.get().hookMachine(configuration.getMachine());
            }
        });
    }

    // ------------------------------------------------------------------ properties

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        Object old = this.serial;
        this.serial = serial == null || serial.isEmpty() ? null : serial.toUpperCase();
        configuredForSerialAddress = null;
        firePropertyChange("serial", old, this.serial);
    }

    public int getRail() {
        return rail;
    }

    public void setRail(int rail) {
        this.rail = rail;
    }

    public int getPitchMm() {
        return pitchMm;
    }

    public void setPitchMm(int pitchMm) {
        this.pitchMm = pitchMm;
        configuredForSerialAddress = null;
    }

    public double getPeelMsPerMm() {
        return peelMsPerMm;
    }

    public void setPeelMsPerMm(double v) {
        this.peelMsPerMm = v;
        configuredForSerialAddress = null;
    }

    public int getPeelTimeMs() {
        return peelTimeMs;
    }

    public void setPeelTimeMs(int v) {
        this.peelTimeMs = v;
        configuredForSerialAddress = null;
    }

    public int getLedBrightness() {
        return ledBrightness;
    }

    public void setLedBrightness(int v) {
        this.ledBrightness = v;
        configuredForSerialAddress = null;
    }

    public int getFeedRetries() {
        return feedRetries;
    }

    public void setFeedRetries(int feedRetries) {
        this.feedRetries = feedRetries;
    }

    /** X position of this feeder along the rail, mm (machine coordinates). */
    public double getSlotX() {
        return location.getX();
    }

    // ------------------------------------------------------------------ hardware access

    /** A live handle: bus + current address. */
    public static class Link {
        public final OrionBus bus;
        public final OrionDeviceInfo info;

        Link(OrionBus bus, OrionDeviceInfo info) {
            this.bus = bus;
            this.info = info;
        }

        public int address() {
            return info.address;
        }
    }

    /** Find the unit (scanning once if it is not known), verify it, and push settings if needed. */
    public synchronized Link link() throws Exception {
        return link(true);
    }

    /**
     * @param applySettings push this feeder's explicitly set values to the unit the first time it is
     *        seen (false when the caller is about to read the unit's own values instead)
     */
    public synchronized Link link(boolean applySettings) throws Exception {
        if (serial == null) {
            throw new OrionException(OrionException.Kind.NOT_FOUND, "Feeder '" + getName()
                    + "' is not bound to hardware. Open the Orion Bus Manager tab, scan, and bind a unit.");
        }
        OrionManager mgr = OrionManager.get();
        OrionManager.Located l = mgr.locateOrScan(serial, rail);
        if (l == null) {
            throw new OrionException(OrionException.Kind.NOT_FOUND, "Feeder '" + getName()
                    + "' (serial " + serial + ") was not found on any rail. Is it plugged in and the rail powered?");
        }
        if (l.info.state == OrionDeviceInfo.State.CONFLICT) {
            throw new OrionException(OrionException.Kind.CONFLICT, "Feeder '" + getName()
                    + "' is in conflict: " + l.info.note);
        }
        if (rail != l.bus.getRail()) {
            rail = l.bus.getRail();
        }
        currentSlotXMm = l.info.slotXMm;
        Link link = new Link(l.bus, l.info);
        String key = serial + "@" + l.info.address;
        if (applySettings && !key.equals(configuredForSerialAddress)) {
            applySettings(link);
            configuredForSerialAddress = key;
            if (getPart() == null) {
                try {
                    assignPartFromComponent(l.bus.getComponent(l.info.address)[0]);
                } catch (OrionException ignored) {
                    // not essential
                }
            }
        }
        return link;
    }

    /** Push pitch / peel / LED settings that are set (non-negative) to the unit. */
    public void applySettings(Link link) throws OrionException {
        OrionBus bus = link.bus;
        int a = link.address();
        if (pitchMm > 0) {
            bus.setPitchMm(a, pitchMm);
        }
        if (peelMsPerMm >= 0) {
            bus.setPeelRate(a, (int) Math.round(peelMsPerMm * 10.0));
        }
        if (ledBrightness > 0) {
            bus.setLedBrightness(a, ledBrightness);
        }
    }

    /** What the unit itself reports (after {@link #readSettingsFromUnit()}), for display. */
    private transient String unitSummary = "";

    /**
     * The unit remembers which component is loaded; make that the feeder's part (and enable the feeder)
     * when the id is known. Unknown ids are left for the user: assigning a part later learns the pairing.
     */
    private void assignPartFromComponent(int componentId) {
        if (componentId == 0xFFFF) {
            return;
        }
        String partId = OrionManager.get().getSettings().partFor(componentId);
        if (partId == null) {
            Logger.info("Orion feeder {}: unit holds component id {} which no part is paired with yet",
                    getName(), componentId);
            return;
        }
        Part part = Configuration.get().getPart(partId);
        if (part != null && (getPart() == null || !partId.equals(getPart().getId()))) {
            setPart(part);
            setEnabled(true);
        }
    }

    /**
     * After the user assigned a part: make the unit and OpenPnP agree on which component is loaded.
     * A unit that already holds an id nobody is paired with yet teaches us the pairing (nothing is
     * written, calibration stays). Otherwise the part's id is written to the unit; changing the id makes
     * the unit clear its tape zero and pitch, so the pitch / peel settings are pushed again afterwards.
     */
    public synchronized void syncComponentToUnit() throws Exception {
        Part part = getPart();
        if (part == null || serial == null) {
            return;
        }
        OrionSettings st = OrionManager.get().getSettings();
        Link l = link(false);
        int unitId = l.bus.getComponent(l.address())[0];
        Integer mapped = st.componentFor(part.getId()); // a numeric part id is its own component id
        if (mapped == null) {
            if (unitId != 0xFFFF && st.partFor(unitId) == null) {
                st.learn(part.getId(), unitId); // the unit already says what it holds: take it over
                return;
            }
            mapped = st.allocateComponent(part.getId());
        }
        if (unitId != mapped) {
            Logger.info("Orion feeder {}: writing component id {} for part {}", getName(), mapped, part.getId());
            l.bus.setComponent(l.address(), mapped);
            configuredForSerialAddress = null; // pitch and tape zero were cleared by the unit
        }
        link(true);
    }

    public String getUnitSummary() {
        return unitSummary;
    }

    /**
     * Take over what is stored on the unit (pitch, peel time / rate, component, slot X), e.g. values set
     * earlier with the Python GUI. Does NOT push anything to the unit and does not change the location.
     */
    public synchronized String readSettingsFromUnit() throws Exception {
        Link link = link(false);
        int a = link.address();
        int rate = link.bus.getPeelRate(a);
        int time = link.bus.getPeelTimeMs(a);
        int[] comp = link.bus.getComponent(a);
        setPeelMsPerMm(rate < 0 ? -1 : rate / 10.0);
        setPitchMm(comp[2] > 0 ? comp[2] * 2 : 0);
        currentSlotXMm = link.bus.getSlotPosition(a);
        configuredForSerialAddress = serial + "@" + a;
        assignPartFromComponent(comp[0]);
        unitSummary = String.format("pitch %s, peel time %s, peel rate %s, component %d, slot X %s",
                comp[2] > 0 ? comp[2] * 2 + " mm" : "not set", time < 0 ? "not set" : time + " ms",
                rate < 0 ? "not set" : String.format("%.1f ms/mm", rate / 10.0), comp[0],
                Double.isNaN(currentSlotXMm) ? "not set" : String.format("%.1f mm", currentSlotXMm));
        return unitSummary;
    }

    public void identify() throws Exception {
        Link l = link();
        l.bus.identifyBlink(l.address(), 5);
    }

    public void feedOnce() throws Exception {
        runMove(Move.FEED);
    }

    public void unfeedOnce() throws Exception {
        runMove(Move.UNFEED);
    }

    /**
     * Manual peel for loading tape. Uses the unit's own peel time when it has one, else a fixed 0.5 s.
     * (Normal feeding peels by itself through the peel coupling rate.)
     */
    public void peel(boolean reverse) throws Exception {
        Link l = link();
        try {
            l.bus.peelCalibrated(l.address(), reverse);
        } catch (OrionException e) {
            if (e.kind == OrionException.Kind.NACK && e.error == OrionError.NOT_READY) {
                l.bus.peel(l.address(), reverse, 50);
            } else {
                throw e;
            }
        }
    }

    /** Fiber (second) LED on/off, used for identification and for tuning the light detection. */
    public void setFiber(boolean on) throws Exception {
        Link l = link();
        l.bus.setFiberLed(l.address(), on);
    }

    public void stop() throws Exception {
        Link l = link();
        l.bus.stop(l.address());
    }

    public int jogTenthsMm(int tenths) throws Exception {
        Link l = link();
        return l.bus.jog(l.address(), tenths);
    }

    public void zeroHere() throws Exception {
        Link l = link();
        l.bus.zeroHere(l.address());
    }

    private enum Move { FEED, UNFEED }

    private void runMove(Move move) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= feedRetries; attempt++) {
            Link l;
            try {
                l = link();
            } catch (OrionException e) {
                throw e;
            }
            try {
                if (move == Move.FEED) {
                    l.bus.feedNext(l.address());
                } else {
                    l.bus.feedBack(l.address());
                }
                return;
            } catch (OrionException e) {
                last = e;
                if (e.kind == OrionException.Kind.TIMEOUT) {
                    // Unit may have rebooted and lost its address: forget and re-find it.
                    Logger.warn("Orion feeder " + getName() + ": no reply, rescanning");
                    configuredForSerialAddress = null;
                    l.bus.scan(null);
                    continue;
                }
                if (e.kind == OrionException.Kind.NACK && e.error == OrionError.NOT_READY) {
                    if (pitchMm <= 0) {
                        throw new OrionException(e.kind, e.error, "Feeder '" + getName() + "': the unit has "
                                + "no pitch set. Set the pitch (mm) in the Orion Feeder tab and press Apply, "
                                + "or set it on the unit first.");
                    }
                    configuredForSerialAddress = null;
                    continue; // link() pushes the pitch and we retry
                }
                throw new OrionException(e.kind, e.error, "Feeder '" + getName() + "' failed to "
                        + (move == Move.FEED ? "feed" : "unfeed") + ": " + e.getMessage());
            }
        }
        if (last instanceof OrionException && ((OrionException) last).kind == OrionException.Kind.NACK) {
            // The unit answered every time but refused: say so, it is not a communication problem.
            throw new OrionException(OrionException.Kind.NACK, ((OrionException) last).error,
                    "Feeder '" + getName() + "': " + last.getMessage());
        }
        throw new OrionException(OrionException.Kind.TIMEOUT, OrionError.NONE,
                "Feeder '" + getName() + "' did not respond to " + move + " after "
                        + (feedRetries + 1) + " attempts: " + (last == null ? "" : last.getMessage()));
    }

    // ------------------------------------------------------------------ Feeder interface

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        switch (getFeedOptions()) {
            case Normal:
                break;
            case SkipNext:
                setFeedOptions(FeedOptions.Normal);
                return;
            case Disable:
                return;
        }
        refineByVisionIfNeeded();
        FeedRun pre = takePrefed();
        if (moveWhileFeeding && nozzle != null) {
            feedWhileMoving(nozzle, pre);
        } else if (pre != null) {
            pre.finish();
        } else {
            runMove(Move.FEED);
        }
        checkTapeByHolesIfNeeded();
    }

    /**
     * Feed on a helper thread (the feeder is on its own bus) while the nozzle already travels in XY over the
     * pick location, Z unchanged. A tape check afterwards, or the job's own pick move, continues from there.
     * Returns when both are done.
     */
    /** A feed running on a helper thread. */
    private static class FeedRun {
        Thread thread;
        volatile Throwable error;

        void finish() throws Exception {
            thread.join();
            if (error instanceof Exception) {
                throw (Exception) error;
            } else if (error != null) {
                throw new Exception(error);
            }
        }
    }

    private transient FeedRun prefed;

    private FeedRun startFeedRun() {
        FeedRun run = new FeedRun();
        run.thread = new Thread(() -> {
            try {
                runMove(Move.FEED);
            } catch (Throwable t) {
                run.error = t;
            }
        }, "orion-feed-" + getName());
        run.thread.start();
        return run;
    }

    /**
     * Start the next feed now, before the job asks for it (the job calls {@link #feed(Nozzle)} later and just
     * waits for it to finish). Does nothing when one is already running or feeding is switched off.
     */
    public synchronized void prefeed() {
        if (!prefeedNext || prefed != null || getFeedOptions() != FeedOptions.Normal) {
            return;
        }
        Logger.debug("Orion feeder {}: feeding ahead of the pick", getName());
        prefed = startFeedRun();
    }

    private synchronized FeedRun takePrefed() {
        FeedRun r = prefed;
        prefed = null;
        return r;
    }

    private void feedWhileMoving(Nozzle nozzle, FeedRun pre) throws Exception {
        if (tapeCheckWillRun()) {
            // The camera has to look at the pick spot after the feed anyway: go there instead of the nozzle.
            feedWhileMovingCamera(nozzle, pre);
            return;
        }
        Location base = basePickLocation();
        // XY and the pick rotation together, so the nozzle is already turned when it arrives.
        Location overXY = new Location(base.getUnits(), base.getX(), base.getY(), Double.NaN, base.getRotation());
        FeedRun run = pre != null ? pre : startFeedRun();
        try {
            nozzle.moveTo(overXY); // XY and rotation, Z stays where it is
        } finally {
            run.thread.join();
        }
        run.finish();
    }

    private void feedWhileMovingCamera(Nozzle nozzle, FeedRun pre) throws Exception {
        org.openpnp.spi.Camera cam = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
        Location look;
        if (tapeTarget == TapeTarget.Pocket) {
            Location base = basePickLocation().convertToUnits(LengthUnit.Millimeters);
            look = new Location(LengthUnit.Millimeters, base.getX(), base.getY(), pocketCamZ(cam),
                    base.getRotation());
        } else {
            look = holeCameraLocation();
        }
        org.openpnp.machine.orion.vision.OrionPocketVision.lightOn(cam); // lit and settled when it arrives
        FeedRun run = pre != null ? pre : startFeedRun();
        try {
            // Turn the nozzle to the pick rotation while the feed runs, so the pick move needs no rotation.
            double rot = basePickLocation().getRotation();
            if (!Double.isNaN(rot)) {
                nozzle.moveTo(new Location(LengthUnit.Millimeters, Double.NaN, Double.NaN, Double.NaN, rot));
            }
            org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(cam, look);
        } finally {
            run.thread.join();
        }
        run.finish();
    }

    public boolean isPrefeedNext() {
        return prefeedNext;
    }

    public void setPrefeedNext(boolean v) {
        prefeedNext = v;
    }

    public boolean isRepickOnVisionFail() {
        return repickOnVisionFail;
    }

    public void setRepickOnVisionFail(boolean v) {
        repickOnVisionFail = v;
    }

    /** Make the next feed run the tape / pocket check even in "first feed of job" mode. */
    public void forceTapeCheckNextFeed() {
        holeDoneThisJob = false;
    }

    public boolean isMoveWhileFeeding() {
        return moveWhileFeeding;
    }

    public void setMoveWhileFeeding(boolean v) {
        moveWhileFeeding = v;
    }

    @Override
    public Location getPickLocation() throws Exception {
        Location l = basePickLocation();
        if (holeShiftMm != 0) {
            l = l.add(tapeAxis == org.openpnp.machine.orion.vision.OrionHoleVision.Axis.Y
                    ? new Location(LengthUnit.Millimeters, 0, holeShiftMm, 0, 0)
                    : new Location(LengthUnit.Millimeters, holeShiftMm, 0, 0, 0));
        }
        if (pocketShiftX != 0 || pocketShiftY != 0) {
            l = l.add(new Location(LengthUnit.Millimeters, pocketShiftX, pocketShiftY, 0, 0));
        }
        return l;
    }

    /** Taught pick location plus slot follow and fiducial correction, before any per-pick tape check. */
    private Location basePickLocation() throws Exception {
        Location l = location;
        if (followSlotPosition && !Double.isNaN(taughtSlotXMm)) {
            if (Double.isNaN(currentSlotXMm)) {
                link(); // reads the unit's slot position
            }
            if (!Double.isNaN(currentSlotXMm)) {
                l = l.add(new Location(LengthUnit.Millimeters, currentSlotXMm - taughtSlotXMm, 0, 0, 0));
            }
        }
        if (visionDelta != null) {
            l = l.add(visionDelta);
        }
        return l;
    }

    // ---- sprocket hole vision

    public HoleMode getHoleMode() {
        return holeMode;
    }

    public void setHoleMode(HoleMode m) {
        holeMode = m;
    }

    public org.openpnp.machine.orion.vision.OrionHoleVision.Axis getTapeAxis() {
        return tapeAxis;
    }

    public void setTapeAxis(org.openpnp.machine.orion.vision.OrionHoleVision.Axis a) {
        tapeAxis = a;
    }

    public Location getHoleNominal() {
        return holeNominal;
    }

    public void setHoleNominal(Location l) {
        holeNominal = l;
    }

    public double getMaxHoleCorrectionMm() {
        return maxHoleCorrectionMm;
    }

    public void setMaxHoleCorrectionMm(double v) {
        maxHoleCorrectionMm = v;
    }

    public double getHoleShiftMm() {
        return holeShiftMm;
    }

    /** Apply a measured shift (also used by tests). */
    public void applyHoleShift(double mm) {
        holeShiftMm = mm;
    }

    /** Where the camera looks: the taught pick location, at the camera height the hole was taught at. */
    private Location holeCameraLocation() {
        Location l = location.convertToUnits(LengthUnit.Millimeters);
        Location h = holeNominal.convertToUnits(LengthUnit.Millimeters);
        return new Location(LengthUnit.Millimeters, l.getX(), l.getY(), h.getZ(), l.getRotation());
    }

    /**
     * Look at the holes next to the pick location and return how far the tape sits from where it was when the
     * pick location was taught (mm along the tape, hole-pitch wrapped). Throws if no hole is found.
     */
    public synchronized double measureHoleShift() throws Exception {
        if (holeNominal == null) {
            throw new Exception("Feeder '" + getName() + "': teach a sprocket hole first (Teach hole here).");
        }
        OrionSettings st = OrionManager.get().getSettings();
        org.openpnp.spi.Camera cam = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
        org.openpnp.machine.orion.vision.OrionPocketVision.lightOn(cam);
        org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(cam, holeCameraLocation());
        java.util.List<Location> holes = org.openpnp.machine.orion.vision.OrionHoleVision.findHoles(
                st.getHolePipeline(), cam, st.getHoleDiameterMm(), st.getHoleSearchMm());
        Double r = org.openpnp.machine.orion.vision.OrionHoleVision.residual(holes, holeNominal, tapeAxis,
                st.getHoleLateralTolMm());
        if (r == null) {
            throw new Exception(String.format("Feeder '%s': no sprocket hole found next to the pick location "
                    + "(%d circle(s) seen, none in the taught row).", getName(), holes.size()));
        }
        if (Math.abs(r) > maxHoleCorrectionMm) {
            throw new Exception(String.format("Feeder '%s': tape is %.2f mm off, more than the %.2f mm limit.",
                    getName(), r, maxHoleCorrectionMm));
        }
        return r;
    }

    /** Remember the hole nearest to the camera centre as the reference (pocket is at the pick location). */
    public synchronized Location teachHoleHere() throws Exception {
        OrionSettings st = OrionManager.get().getSettings();
        org.openpnp.spi.Camera cam = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
        java.util.List<Location> holes = org.openpnp.machine.orion.vision.OrionHoleVision.findHoles(
                st.getHolePipeline(), cam, st.getHoleDiameterMm(), st.getHoleSearchMm());
        if (holes.isEmpty()) {
            throw new Exception("No sprocket hole found under the camera. Tune the hole pipeline first.");
        }
        Location centre = cam.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location best = null;
        for (Location h : holes) {
            if (best == null || h.getLinearDistanceTo(centre) < best.getLinearDistanceTo(centre)) {
                best = h;
            }
        }
        holeNominal = best.convertToUnits(LengthUnit.Millimeters);
        holeShiftMm = 0;
        return holeNominal;
    }

    public TapeTarget getTapeTarget() {
        return tapeTarget;
    }

    public void setTapeTarget(TapeTarget t) {
        tapeTarget = t;
    }

    public double getPocketWidthMm() {
        return pocketWidthMm;
    }

    public void setPocketWidthMm(double v) {
        pocketWidthMm = v;
    }

    public double getPocketLengthMm() {
        return pocketLengthMm;
    }

    public void setPocketLengthMm(double v) {
        pocketLengthMm = v;
    }

    public double getPocketSizeTolPct() {
        return pocketSizeTolPct;
    }

    public void setPocketSizeTolPct(double v) {
        pocketSizeTolPct = v;
    }

    public boolean isPocketCorrectAcross() {
        return pocketCorrectAcross;
    }

    public void setPocketCorrectAcross(boolean v) {
        pocketCorrectAcross = v;
    }

    public Location getPocketBias() {
        return pocketBias;
    }

    public void setPocketBias(Location l) {
        pocketBias = l;
        pocketTaught = null; // set directly: use the offset to the pick location
        pocketExtra = null;
    }

    public double getPocketShiftX() {
        return pocketShiftX;
    }

    public double getPocketShiftY() {
        return pocketShiftY;
    }

    public void applyPocketShift(double dx, double dy) {
        pocketShiftX = dx;
        pocketShiftY = dy;
    }

    private double pocketCamZ(org.openpnp.spi.Camera cam) throws Exception {
        return Double.isNaN(pocketCameraZ) ? cam.getDefaultZ().convertToUnits(LengthUnit.Millimeters).getValue()
                : pocketCameraZ;
    }

    /**
     * Look at the pocket (or the part in it) and return how far its centre is from where the pick spot is
     * supposed to be, {dx, dy} in mm. Throws if no pocket of the expected size is found.
     */
    public synchronized double[] measurePocketShift() throws Exception {
        return measurePocketShift(true);
    }

    private synchronized double[] measurePocketShift(boolean moveCamera) throws Exception {
        OrionSettings st = OrionManager.get().getSettings();
        org.openpnp.spi.Camera cam = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
        Location base = basePickLocation().convertToUnits(LengthUnit.Millimeters);
        if (moveCamera) {
            org.openpnp.machine.orion.vision.OrionPocketVision.lightOn(cam);
            org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(cam, new Location(LengthUnit.Millimeters,
                    base.getX(), base.getY(), pocketCamZ(cam), base.getRotation()));
        }
        org.openpnp.machine.orion.vision.OrionPocketVision.Found res =
                org.openpnp.machine.orion.vision.OrionPocketVision.find(st.getPocketPipeline(), cam);
        java.util.List<org.openpnp.machine.orion.vision.OrionPocketVision.Candidate> found = res.candidates;
        org.openpnp.machine.orion.vision.OrionPocketVision.Candidate c =
                org.openpnp.machine.orion.vision.OrionPocketVision.select(found, pocketWidthMm, pocketLengthMm,
                        pocketSizeTolPct / 100.0, base);
        if (c == null) {
            throw new Exception(String.format("Feeder '%s': no pocket found%s. Rectangles seen: %s (%s). "
                    + "Set the size to 0 to accept any, or to what the pipeline sees.", getName(),
                    pocketWidthMm > 0 ? String.format(" of about %.2f x %.2f mm", pocketWidthMm, pocketLengthMm) : "",
                    org.openpnp.machine.orion.vision.OrionPocketVision.describe(found), res.diagnostics));
        }
        lastPocketReport = String.format("pick location X=%.3f Y=%.3f; camera at X=%.3f Y=%.3f; saw %s",
                base.getX(), base.getY(), cam.getLocation().convertToUnits(LengthUnit.Millimeters).getX(),
                cam.getLocation().convertToUnits(LengthUnit.Millimeters).getY(),
                org.openpnp.machine.orion.vision.OrionPocketVision.describeWithCentres(found))
                + String.format(" -> using centre X=%.3f Y=%.3f", c.center.getX(), c.center.getY());
        if (pocketBias == null && pocketTaught == null) {
            // Without the taught offset the camera/nozzle offset error would be applied to every pick.
            throw new Exception(String.format("Feeder '%s': teach the pocket first (Teach here), the offset between "
                    + "the camera's view and the nozzle is not known yet. %s", getName(), lastPocketReport));
        }
        double dx;
        double dy;
        if (pocketTaught != null && pocketExtra != null) {
            // Compare with where the pocket was taught, corrected for slot follow / fiducial change since then.
            Location loc = location.convertToUnits(LengthUnit.Millimeters);
            Location t = pocketTaught.convertToUnits(LengthUnit.Millimeters);
            Location x0 = pocketExtra.convertToUnits(LengthUnit.Millimeters);
            double expX = t.getX() + (base.getX() - loc.getX()) - x0.getX();
            double expY = t.getY() + (base.getY() - loc.getY()) - x0.getY();
            dx = c.center.getX() - expX;
            dy = c.center.getY() - expY;
        } else {
            // Taught with an older version: offset to the pick location at that time.
            double bx = pocketBias.convertToUnits(LengthUnit.Millimeters).getX();
            double by = pocketBias.convertToUnits(LengthUnit.Millimeters).getY();
            dx = c.center.getX() - (base.getX() + bx);
            dy = c.center.getY() - (base.getY() + by);
        }
        if (!pocketCorrectAcross) {
            if (tapeAxis == org.openpnp.machine.orion.vision.OrionHoleVision.Axis.Y) {
                dx = 0;
            } else {
                dy = 0;
            }
        }
        if (Math.hypot(dx, dy) > maxHoleCorrectionMm) {
            throw new Exception(String.format("Feeder '%s': pocket is %.2f mm off, more than the %.2f mm limit.",
                    getName(), Math.hypot(dx, dy), maxHoleCorrectionMm));
        }
        Logger.debug("Orion feeder {}: pocket {}; shift {} / {}", getName(), lastPocketReport, dx, dy);
        return new double[] {dx, dy};
    }

    private transient String lastPocketReport = "";

    /** What the last pocket measurement saw: centre in machine coordinates and size. */
    public String getLastPocketReport() {
        return lastPocketReport;
    }

    /**
     * The camera is over the pocket and the pick location is where the nozzle should pick: remember where
     * the detected pocket centre lies relative to the pick location, so later checks keep that spot.
     */
    public synchronized Location teachPocketHere() throws Exception {
        OrionSettings st = OrionManager.get().getSettings();
        org.openpnp.spi.Camera cam = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
        org.openpnp.machine.orion.vision.OrionPocketVision.Found res =
                org.openpnp.machine.orion.vision.OrionPocketVision.find(st.getPocketPipeline(), cam);
        java.util.List<org.openpnp.machine.orion.vision.OrionPocketVision.Candidate> found = res.candidates;
        org.openpnp.machine.orion.vision.OrionPocketVision.Candidate c =
                org.openpnp.machine.orion.vision.OrionPocketVision.select(found, pocketWidthMm, pocketLengthMm,
                        pocketSizeTolPct / 100.0, cam.getLocation());
        if (c == null) {
            throw new Exception(String.format("No pocket found under the camera. Rectangles seen: %s (%s). "
                    + "Set the size to 0 to accept any, or to what the pipeline sees.",
                    org.openpnp.machine.orion.vision.OrionPocketVision.describe(found), res.diagnostics));
        }
        Location base = basePickLocation().convertToUnits(LengthUnit.Millimeters);
        pocketBias = new Location(LengthUnit.Millimeters, c.center.getX() - base.getX(),
                c.center.getY() - base.getY(), 0, 0);
        Location loc = location.convertToUnits(LengthUnit.Millimeters);
        pocketTaught = new Location(LengthUnit.Millimeters, c.center.getX(), c.center.getY(), 0, 0);
        pocketExtra = new Location(LengthUnit.Millimeters, base.getX() - loc.getX(), base.getY() - loc.getY(), 0, 0);
        pocketCameraZ = cam.getLocation().convertToUnits(LengthUnit.Millimeters).getZ();
        pocketShiftX = 0;
        pocketShiftY = 0;
        return pocketBias;
    }

    /** True when the tape / pocket camera check is due after this feed. */
    /**
     * The pocket is further from the pick spot along the tape than {@link #jogMinMm}: move the tape with the
     * unit's jog command (0.1 mm steps) and measure again, instead of moving the nozzle. Which way a positive
     * jog moves the tape is learned: if the first jog makes it worse the sign is flipped and stored.
     * Returns the remaining shift (applied to the pick location as usual).
     */
    private double[] jogTapeToPocket(double[] d) throws Exception {
        int ai = tapeAxis == org.openpnp.machine.orion.vision.OrionHoleVision.Axis.Y ? 1 : 0;
        double along = d[ai];
        if (Math.abs(along) < jogMinMm || Math.abs(along) > 2.0) {
            return d;
        }
        for (int pass = 0; pass < 2; pass++) {
            int tenths = (int) Math.round(-along * 10.0 * jogSign);
            if (tenths == 0) {
                return d;
            }
            jogTenthsMm(tenths);
            OrionManager.get().addLog(rail, org.openpnp.machine.orion.protocol.OrionBusListener.Direction.TX,
                    String.format("%s: tape jogged %+d x 0.1 mm for a pocket shift of %+.2f mm", getName(),
                            tenths, along));
            double[] again = measurePocketShift(false);
            double now = again[ai];
            if (Math.abs(now) <= Math.abs(along) + 0.05) {
                return again;
            }
            // Worse: the jog direction is the other way round on this tape. Flip, and try once more.
            jogSign = -jogSign;
            Logger.info("Orion feeder {}: jog direction flipped to {}", getName(), jogSign);
            d = again;
            along = now;
        }
        return d;
    }

    public boolean isJogCorrectPocket() {
        return jogCorrectPocket;
    }

    public void setJogCorrectPocket(boolean v) {
        jogCorrectPocket = v;
    }

    public double getJogMinMm() {
        return jogMinMm;
    }

    public void setJogMinMm(double v) {
        jogMinMm = v;
    }

    private boolean tapeCheckWillRun() {
        boolean pocket = tapeTarget == TapeTarget.Pocket;
        if (holeMode == HoleMode.Off || (!pocket && holeNominal == null)) {
            return false;
        }
        if (holeMode == HoleMode.FirstFeedOfJob && holeDoneThisJob) {
            return false;
        }
        return Configuration.get().getMachine().isHomed();
    }

    private void checkTapeByHolesIfNeeded() throws Exception {
        holeShiftMm = 0; // the tape just moved, an older measurement does not apply
        pocketShiftX = 0;
        pocketShiftY = 0;
        boolean pocket = tapeTarget == TapeTarget.Pocket;
        if (!tapeCheckWillRun()) {
            return;
        }
        try {
            if (pocket) {
                double[] d = measurePocketShift();
                if (jogCorrectPocket) {
                    d = jogTapeToPocket(d);
                }
                pocketShiftX = d[0];
                pocketShiftY = d[1];
                Logger.debug("Orion feeder {}: pocket shift {} / {} mm", getName(), d[0], d[1]);
            } else {
                holeShiftMm = measureHoleShift();
                Logger.debug("Orion feeder {}: tape shift {} mm", getName(), holeShiftMm);
            }
            holeDoneThisJob = true;
        } catch (Exception e) {
            // Never stop a job for this: pick at the taught position and say so.
            Logger.warn("Orion feeder {}: tape check failed: {}", getName(), e.getMessage());
            OrionManager.get().addLog(rail,
                    org.openpnp.machine.orion.protocol.OrionBusListener.Direction.ERROR,
                    "tape check failed: " + e.getMessage());
        }
    }

    public double getTaughtSlotXMm() {
        return taughtSlotXMm;
    }

    public void setTaughtSlotXMm(double v) {
        this.taughtSlotXMm = v;
    }

    public boolean isFollowSlotPosition() {
        return followSlotPosition;
    }

    public void setFollowSlotPosition(boolean v) {
        this.followSlotPosition = v;
    }

    public void setCurrentSlotXMm(double v) {
        currentSlotXMm = v;
    }

    public double getCurrentSlotXMm() {
        return currentSlotXMm;
    }

    /** Read the slot X stored on the unit. */
    public double readSlotPosition() throws Exception {
        Link l = link();
        currentSlotXMm = l.bus.getSlotPosition(l.address());
        return currentSlotXMm;
    }

    /** Write the slot X to the unit (NaN clears it). */
    public void writeSlotPosition(double mm) throws Exception {
        Link l = link();
        l.bus.setSlotPosition(l.address(), mm);
        currentSlotXMm = mm;
    }

    /** Remember the unit's current slot X as the position the pick location was taught at. */
    public void teachSlotPosition() throws Exception {
        double x = readSlotPosition();
        if (Double.isNaN(x)) {
            throw new Exception("The unit has no slot position stored. Write one first.");
        }
        taughtSlotXMm = x;
    }

    @Override
    public void prepareForJob(boolean visit) throws Exception {
        visionDelta = null;
        visionDoneThisJob = false;
        holeShiftMm = 0;
        pocketShiftX = 0;
        pocketShiftY = 0;
        holeDoneThisJob = false;
        checkRailVerified();
        super.prepareForJob(visit);
    }

    /** Warn (or block) when the rail was not checked by vision, so a bad location does not fail the job later. */
    private void checkRailVerified() throws Exception {
        OrionManager mgr = OrionManager.get();
        OrionSettings.UnverifiedPolicy policy = mgr.getSettings().getUnverifiedPolicy();
        if (policy == OrionSettings.UnverifiedPolicy.Off || serial == null) {
            return;
        }
        String problem = mgr.railCheckProblem(rail);
        if (problem == null) {
            return;
        }
        String msg = "Orion feeder '" + getName() + "': " + problem;
        Logger.warn(msg);
        mgr.addLog(rail, org.openpnp.machine.orion.protocol.OrionBusListener.Direction.ERROR, msg);
        if (policy == OrionSettings.UnverifiedPolicy.Block) {
            throw new Exception(msg);
        }
        if (!java.awt.GraphicsEnvironment.isHeadless() && mgr.shouldShowWarning(rail)) {
            javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
                    org.openpnp.gui.MainFrame.get(), problem + "\n\nThe job continues, but picks from "
                            + "feeders on this rail may miss.", "Orion: rail not verified",
                    javax.swing.JOptionPane.WARNING_MESSAGE));
        }
    }

    @Override
    public void findIssues(org.openpnp.model.Solutions solutions) {
        super.findIssues(solutions);
        OrionSettings.UnverifiedPolicy policy = OrionManager.get().getSettings().getUnverifiedPolicy();
        if (serial == null || policy == OrionSettings.UnverifiedPolicy.Off) {
            return;
        }
        String problem = OrionManager.get().railCheckProblem(rail);
        if (problem != null) {
            solutions.add(new org.openpnp.model.Solutions.PlainIssue(this,
                    "Orion rail " + (rail + 1) + " not verified: " + problem,
                    "Open the Orion Bus Manager tab of any Orion feeder and press \"Verify rail (vision)\".",
                    policy == OrionSettings.UnverifiedPolicy.Block ? org.openpnp.model.Solutions.Severity.Error
                            : org.openpnp.model.Solutions.Severity.Warning,
                    "https://github.com/TheIgorMC/orionPnP"));
        }
    }

    // ------------------------------------------------------------------ vision

    public VisionMode getVisionMode() {
        return visionMode;
    }

    public void setVisionMode(VisionMode visionMode) {
        this.visionMode = visionMode;
    }

    public Part getFiducialPart() {
        return fiducialPartId == null ? null : Configuration.get().getPart(fiducialPartId);
    }

    public void setFiducialPart(Part part) {
        this.fiducialPartId = part == null ? null : part.getId();
    }

    public Location getFiducialNominal() {
        return fiducialNominal;
    }

    public void setFiducialNominal(Location fiducialNominal) {
        this.fiducialNominal = fiducialNominal;
    }

    public boolean isVisionXOnly() {
        return visionXOnly;
    }

    public void setVisionXOnly(boolean visionXOnly) {
        this.visionXOnly = visionXOnly;
    }

    public double getMaxCorrectionMm() {
        return maxCorrectionMm;
    }

    public void setMaxCorrectionMm(double maxCorrectionMm) {
        this.maxCorrectionMm = maxCorrectionMm;
    }

    public Location getVisionDelta() {
        return visionDelta;
    }

    /**
     * Look for the fiducial at its nominal position and compute how far it moved. The correction is
     * stored in {@link #visionDelta} and applied by {@link #getPickLocation()}.
     *
     * @return the correction (mm) or throws if the detection failed or is implausible.
     */
    public Location locateByFiducial() throws Exception {
        Part part = getFiducialPart();
        if (part == null || fiducialNominal == null) {
            throw new Exception("Feeder '" + getName() + "': set a fiducial part and its nominal location "
                    + "before using vision.");
        }
        FiducialLocator locator = Configuration.get().getMachine().getFiducialLocator();
        Location found;
        if (locator instanceof ReferenceFiducialLocator) {
            found = ((ReferenceFiducialLocator) locator).getFiducialLocation(fiducialNominal, part);
        } else {
            found = locator.getHomeFiducialLocation(fiducialNominal, part);
        }
        if (found == null) {
            throw new Exception("Feeder '" + getName() + "': fiducial '" + part.getId() + "' not found.");
        }
        Location nominalMm = fiducialNominal.convertToUnits(LengthUnit.Millimeters);
        Location foundMm = found.convertToUnits(LengthUnit.Millimeters);
        double dx = foundMm.getX() - nominalMm.getX();
        double dy = visionXOnly ? 0 : foundMm.getY() - nominalMm.getY();
        if (Math.hypot(dx, dy) > maxCorrectionMm) {
            throw new Exception(String.format("Feeder '%s': fiducial found %.2f mm away from where it "
                    + "should be (limit %.2f mm). Check the feeder is seated, or raise the limit.",
                    getName(), Math.hypot(dx, dy), maxCorrectionMm));
        }
        visionDelta = new Location(LengthUnit.Millimeters, dx, dy, 0, 0);
        Logger.info("Orion feeder {}: fiducial correction dx={} dy={}", getName(), dx, dy);
        return visionDelta;
    }

    /** Rail-scan nominal for this feeder's large fiducial: taught position, else the pick X on the rail line. */
    public Location getFiducialNominalOrDefault(OrionRailSettings rail) {
        if (fiducialNominal != null) {
            return fiducialNominal;
        }
        return new Location(LengthUnit.Millimeters, location.convertToUnits(LengthUnit.Millimeters).getX(),
                rail.getFiducialY(), rail.getFiducialZ(), 0);
    }

    /**
     * Save a measured large-fiducial position: the pick X moves by the same amount the fiducial moved,
     * and the fiducial becomes the new nominal. First time (no nominal yet) only the nominal is set.
     */
    public synchronized void applyFoundFiducial(Location found, OrionRailSettings rail) {
        Location f = found.convertToUnits(LengthUnit.Millimeters);
        Location old = getFiducialNominalOrDefault(rail);
        if (fiducialNominal != null) {
            double dx = f.getX() - old.getX();
            setLocation(location.add(new Location(LengthUnit.Millimeters, dx, 0, 0, 0)));
        }
        fiducialNominal = new Location(LengthUnit.Millimeters, f.getX(), old.getY(), old.getZ(), 0);
        visionDelta = null;
        // Remember where the unit reports it sits now, so the next move can be predicted.
        if (!Double.isNaN(currentSlotXMm)) {
            taughtSlotXMm = currentSlotXMm;
        }
        firePropertyChange("fiducialNominal", old, fiducialNominal);
    }

    /** Make the current vision correction permanent: move the taught pick X and fiducial position. */
    public void commitVisionCorrection() {
        if (visionDelta == null) {
            return;
        }
        setLocation(location.add(visionDelta));
        fiducialNominal = fiducialNominal.add(visionDelta);
        visionDelta = null;
    }

    private void refineByVisionIfNeeded() throws Exception {
        if (visionMode == VisionMode.Off || fiducialNominal == null || getFiducialPart() == null) {
            return;
        }
        if (visionMode == VisionMode.FirstFeedOfJob && visionDoneThisJob) {
            return;
        }
        if (!Configuration.get().getMachine().isHomed()) {
            return; // not running a job; camera moves are not possible
        }
        locateByFiducial();
        visionDoneThisJob = true;
    }

    @Override
    public boolean supportsFeedOptions() {
        return true;
    }

    @Override
    public boolean canTakeBackPart() {
        return getFeedOptions() == FeedOptions.Normal;
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        super.takeBackPart(nozzle);
        putPartBack(nozzle);
        setFeedOptions(FeedOptions.SkipNext);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && serial != null;
    }

    // ------------------------------------------------------------------ UI

    @Override
    public String getPropertySheetHolderTitle() {
        return String.format("%s %s", getClass().getSimpleName(), getName());
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        List<PropertySheet> sheets = new ArrayList<>();
        sheets.add(new OrionFeederSheet(this));
        sheets.add(new OrionBusManagerSheet());
        sheets.add(new OrionDebugSheet());
        return sheets.toArray(new PropertySheet[0]);
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return new PropertySheetHolder[0];
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return new Action[0];
    }

    @Override
    public Wizard getConfigurationWizard() {
        return null;
    }

    @Override
    public String toString() {
        return getName();
    }
}
