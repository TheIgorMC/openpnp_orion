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
    protected int peelTimeMs = -1;

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
        if (peelTimeMs >= 0) {
            bus.setPeelTimeMs(a, peelTimeMs);
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
        setPeelTimeMs(time);
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

    public void peel(boolean reverse) throws Exception {
        Link l = link();
        l.bus.peelCalibrated(l.address(), reverse);
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
        runMove(Move.FEED);
    }

    @Override
    public Location getPickLocation() throws Exception {
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
