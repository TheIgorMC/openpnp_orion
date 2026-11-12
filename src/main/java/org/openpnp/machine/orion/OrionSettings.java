package org.openpnp.machine.orion;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.machine.orion.vision.OrionPipelines;
import org.openpnp.vision.pipeline.CvPipeline;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.ElementMap;
import org.simpleframework.xml.Root;

/** Machine-wide Orion bus settings, stored as a machine property (machine.xml). */
@Root
public class OrionSettings {
    public enum Mode {
        SIMULATED("Simulated (no hardware)"),
        USB_RS485("USB-RS485 adapter (1 rail)"),
        HOST_BOARD("Orion host board (2 rails)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Attribute(required = false)
    private Mode mode = Mode.USB_RS485;

    @Attribute(required = false)
    private String adapterPort = "";

    @Attribute(required = false)
    private int adapterBaud = 9600;

    @Attribute(required = false)
    private String hostPort = "";

    @Attribute(required = false)
    private int hostBaud = 115200;

    @Attribute(required = false)
    private int maxAddress = 40;

    @Attribute(required = false)
    private int retries = 2;

    @Attribute(required = false)
    private boolean connectOnEnable = true;

    /** After each scan on machine enable, create an OpenPnP feeder for every unit that has none. */
    @Attribute(required = false)
    private boolean autoCreateFeeders = true;

    /** Background pinging of all known feeders and watching for new ones. */
    @Attribute(required = false)
    private boolean monitorEnabled = true;

    /** Seconds between two ping sweeps of the whole rail. */
    @Attribute(required = false)
    private int monitorIntervalS = 3;

    /** Seconds to wait after the last new feeder showed up before the rail is scanned automatically. */
    @Attribute(required = false)
    private int autoScanDelayS = 30;

    public boolean isMonitorEnabled() {
        return monitorEnabled;
    }

    public void setMonitorEnabled(boolean v) {
        monitorEnabled = v;
    }

    public int getMonitorIntervalS() {
        return Math.max(1, monitorIntervalS);
    }

    public void setMonitorIntervalS(int v) {
        monitorIntervalS = Math.max(1, v);
    }

    public int getAutoScanDelayS() {
        return Math.max(5, autoScanDelayS);
    }

    public void setAutoScanDelayS(int v) {
        autoScanDelayS = Math.max(5, v);
    }

    // ---- setup cycle (verify rails, bring every pocket to the pick window) and pick height probe

    /** Parts up to this size (the larger of the two sides against the larger limit) use camera + jog; bigger go semi blind. */
    @Attribute(required = false)
    private double maxVisionWidthMm = 3.0;

    @Attribute(required = false)
    private double maxVisionLengthMm = 4.0;

    /** A pocket this close to the target (along the tape) counts as already in place. */
    @Attribute(required = false)
    private double pocketTolMm = 0.10;

    /** Semi blind: a pocket seen further than this from where it should be is reported. */
    @Attribute(required = false)
    private double blindCheckTolMm = 1.5;

    /** Offer the setup cycle when the machine has been homed. */
    @Attribute(required = false)
    private boolean promptSetupOnHome = true;

    /** Lower each feeder's pick position onto the part with the vacuum sensor at its first feed of a session. */
    @Attribute(required = false)
    private boolean zProbeEnabled = true;

    @Attribute(required = false)
    private String vacuumSenseActuator = "";

    /** True: the reading goes down when the nozzle seals (a pressure sensor), false: it goes up (a vacuum level). */
    @Attribute(required = false)
    private boolean vacuumSealedBelow = true;

    @Attribute(required = false)
    private double vacuumThreshold = 0;

    @Attribute(required = false)
    private double zStepMm = 0.05;

    @Attribute(required = false)
    private double zSearchAboveMm = 1.0;

    @Attribute(required = false)
    private double zSearchBelowMm = 1.5;

    /** Deeper than the first sealed reading. */
    @Attribute(required = false)
    private double zExtraMm = 0.10;

    @Attribute(required = false)
    private int zSettleMs = 60;

    /** Fraction of the nozzle's speed used while probing. */
    @Attribute(required = false)
    private double zSpeed = 0.25;

    public double getMaxVisionWidthMm() {
        return maxVisionWidthMm;
    }

    public void setMaxVisionWidthMm(double v) {
        maxVisionWidthMm = v;
    }

    public double getMaxVisionLengthMm() {
        return maxVisionLengthMm;
    }

    public void setMaxVisionLengthMm(double v) {
        maxVisionLengthMm = v;
    }

    public double getPocketTolMm() {
        return Math.max(0.01, pocketTolMm);
    }

    public void setPocketTolMm(double v) {
        pocketTolMm = v;
    }

    public double getBlindCheckTolMm() {
        return blindCheckTolMm;
    }

    public void setBlindCheckTolMm(double v) {
        blindCheckTolMm = v;
    }

    public boolean isPromptSetupOnHome() {
        return promptSetupOnHome;
    }

    public void setPromptSetupOnHome(boolean v) {
        promptSetupOnHome = v;
    }

    public boolean isZProbeEnabled() {
        return zProbeEnabled;
    }

    public void setZProbeEnabled(boolean v) {
        zProbeEnabled = v;
    }

    public String getVacuumSenseActuator() {
        return vacuumSenseActuator == null ? "" : vacuumSenseActuator;
    }

    public void setVacuumSenseActuator(String v) {
        vacuumSenseActuator = v == null ? "" : v;
    }

    public boolean isVacuumSealedBelow() {
        return vacuumSealedBelow;
    }

    public void setVacuumSealedBelow(boolean v) {
        vacuumSealedBelow = v;
    }

    public double getVacuumThreshold() {
        return vacuumThreshold;
    }

    public void setVacuumThreshold(double v) {
        vacuumThreshold = v;
    }

    public double getZStepMm() {
        return Math.max(0.01, zStepMm);
    }

    public void setZStepMm(double v) {
        zStepMm = v;
    }

    public double getZSearchAboveMm() {
        return zSearchAboveMm;
    }

    public void setZSearchAboveMm(double v) {
        zSearchAboveMm = v;
    }

    public double getZSearchBelowMm() {
        return zSearchBelowMm;
    }

    public void setZSearchBelowMm(double v) {
        zSearchBelowMm = v;
    }

    public double getZExtraMm() {
        return zExtraMm;
    }

    public void setZExtraMm(double v) {
        zExtraMm = v;
    }

    public int getZSettleMs() {
        return zSettleMs;
    }

    public void setZSettleMs(int v) {
        zSettleMs = Math.max(0, v);
    }

    public double getZSpeed() {
        return Math.min(1.0, Math.max(0.02, zSpeed));
    }

    public void setZSpeed(double v) {
        zSpeed = v;
    }

    /** The large fiducial (middle one of each feeder's set) used to find feeders on the rail. */
    @Attribute(required = false)
    private String largeFiducialPartId = "";

    @ElementList(required = false)
    private List<OrionRailSettings> railSettings = new ArrayList<>();

    /**
     * OpenPnP part id -> component id stored on the units (a number, 0..65534). Learned from units the
     * first time a part is assigned, or allocated for new parts, so the part follows the unit.
     */
    @ElementMap(required = false, entry = "component", key = "part", attribute = true, value = "id")
    private java.util.Map<String, Integer> componentMap = new java.util.HashMap<>();

    /** Pick position trim per nozzle name (mm), added to every pick location of an Orion feeder. */
    @ElementMap(required = false, entry = "trim-x", key = "nozzle", attribute = true, value = "mm")
    private java.util.Map<String, Double> nozzleTrimX = new java.util.HashMap<>();

    @ElementMap(required = false, entry = "trim-y", key = "nozzle", attribute = true, value = "mm")
    private java.util.Map<String, Double> nozzleTrimY = new java.util.HashMap<>();

    /** Pick Z trim per nozzle tip name (mm), from the Z touch check: how much higher it touches than the reference. */
    @ElementMap(required = false, entry = "tip-trim-z", key = "tip", attribute = true, value = "mm")
    private java.util.Map<String, Double> tipTrimZ = new java.util.HashMap<>();

    public double getTipTrimZ(String tip) {
        Double v = tip == null ? null : tipTrimZ.get(tip);
        return v == null ? 0 : v;
    }

    public void setTipTrimZ(String tip, double mm) {
        tipTrimZ.put(tip, mm);
    }

    public double getNozzleTrimX(String nozzle) {
        Double v = nozzle == null ? null : nozzleTrimX.get(nozzle);
        return v == null ? 0 : v;
    }

    public double getNozzleTrimY(String nozzle) {
        Double v = nozzle == null ? null : nozzleTrimY.get(nozzle);
        return v == null ? 0 : v;
    }

    public void setNozzleTrim(String nozzle, double x, double y) {
        nozzleTrimX.put(nozzle, x);
        nozzleTrimY.put(nozzle, y);
    }

    /**
     * Names of nozzles and nozzle tips that always get the camera check of the pocket before a pick (fine tips
     * that need constant alignment), comma separated.
     */
    @Attribute(required = false)
    private String alwaysCheckNames = "";

    public boolean isAlwaysCheck(String name) {
        if (name == null || alwaysCheckNames == null) {
            return false;
        }
        for (String n : alwaysCheckNames.split(",")) {
            if (n.trim().equals(name.trim()) && !n.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void setAlwaysCheck(String name, boolean on) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String n : (alwaysCheckNames == null ? "" : alwaysCheckNames).split(",")) {
            if (!n.trim().isEmpty()) {
                set.add(n.trim());
            }
        }
        if (on) {
            set.add(name.trim());
        } else {
            set.remove(name.trim());
        }
        alwaysCheckNames = String.join(",", set);
    }

    /** A part id that is a plain number (0..65534) is its own component id; no table needed. */
    public static Integer numericId(String partId) {
        if (partId == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(partId.trim());
            return n >= 0 && n <= 65534 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public synchronized Integer componentFor(String partId) {
        Integer direct = numericId(partId);
        return direct != null ? direct : (partId == null ? null : componentMap.get(partId));
    }

    /**
     * Part id that goes with a component id: the part whose id IS that number (also "0042" for 42), else
     * a learned pairing. Null if none.
     */
    public synchronized String partFor(int componentId) {
        String exact = String.valueOf(componentId);
        String loose = null;
        for (org.openpnp.model.Part p : org.openpnp.model.Configuration.get().getParts()) {
            Integer n = numericId(p.getId());
            if (n != null && n == componentId) {
                if (p.getId().equals(exact)) {
                    return exact;
                }
                if (loose == null) {
                    loose = p.getId();
                }
            }
        }
        if (loose != null) {
            return loose;
        }
        for (java.util.Map.Entry<String, Integer> e : componentMap.entrySet()) {
            if (e.getValue() == componentId) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Remember that this part is what component id N means. */
    public synchronized void learn(String partId, int componentId) {
        componentMap.put(partId, componentId);
    }

    /** Next free component id (from 1) for a part that has none yet; numeric part ids keep their number. */
    public synchronized int allocateComponent(String partId) {
        Integer have = componentFor(partId);
        if (have != null) {
            return have;
        }
        int id = -1;
        for (int c = 1; id < 0 && c <= 65534; c++) {
            if (partFor(c) == null) {
                id = c;
            }
        }
        componentMap.put(partId, id);
        return id;
    }

    public enum IdentifyMethod {
        Off("Off (leave ambiguous feeders unresolved)"),
        Fiber("Fiber light"),
        TapeMovement("Tape movement"),
        FiberThenTape("Fiber light, tape movement as fallback");

        private final String label;

        IdentifyMethod(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum UnverifiedPolicy {
        Off("Don't check"),
        Warn("Warn (job still runs)"),
        Block("Block the job");

        private final String label;

        UnverifiedPolicy(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Attribute(required = false)
    private IdentifyMethod identifyMethod = IdentifyMethod.FiberThenTape;

    @Attribute(required = false)
    private UnverifiedPolicy unverifiedPolicy = UnverifiedPolicy.Warn;

    /** Peak brightness increase (gray levels, 0..255) that counts as "fiber is on". */
    @Attribute(required = false)
    private double fiberThreshold = 40;

    /** Changed pixel count that counts as "the tape moved". */
    @Attribute(required = false)
    private double movementThreshold = 150;

    /** How far the tape is moved back for the movement check, mm. */
    @Attribute(required = false)
    private double movementMm = 0.5;

    @Attribute(required = false)
    private int settleMs = 150;

    /** Pause between moving one feeder and the next, so motors never start together (supply load). */
    @Attribute(required = false)
    private int staggerMs = 150;

    @Element(required = false)
    private CvPipeline fiberPipeline;

    @Element(required = false)
    private CvPipeline movementPipeline;

    /** Sprocket hole detection for the per-pick tape position check. */
    @Element(required = false)
    private CvPipeline holePipeline;

    @Attribute(required = false)
    private double holeDiameterMm = 1.5;

    /** Only holes within this distance of the camera centre are considered. */
    @Attribute(required = false)
    private double holeSearchMm = 6.0;

    /** A hole counts as part of the taught row if it is this close (mm) across the tape to the taught one. */
    @Attribute(required = false)
    private double holeLateralTolMm = 1.0;

    /** Pocket / part detection for centring the pick on the pocket each time. */
    @Element(required = false)
    private CvPipeline pocketPipeline;

    public synchronized CvPipeline getPocketPipeline() {
        if (pocketPipeline == null) {
            pocketPipeline = OrionPipelines.defaultPocketPipeline();
        }
        return pocketPipeline;
    }

    public synchronized void resetPocketPipeline() {
        pocketPipeline = OrionPipelines.defaultPocketPipeline();
    }

    public synchronized CvPipeline getHolePipeline() {
        if (holePipeline == null) {
            holePipeline = OrionPipelines.defaultHolePipeline();
        }
        return holePipeline;
    }

    public synchronized void resetHolePipeline() {
        holePipeline = OrionPipelines.defaultHolePipeline();
    }

    public double getHoleDiameterMm() {
        return holeDiameterMm;
    }

    public void setHoleDiameterMm(double v) {
        holeDiameterMm = v;
    }

    public double getHoleSearchMm() {
        return holeSearchMm;
    }

    public void setHoleSearchMm(double v) {
        holeSearchMm = v;
    }

    public double getHoleLateralTolMm() {
        return holeLateralTolMm;
    }

    public void setHoleLateralTolMm(double v) {
        holeLateralTolMm = v;
    }

    public IdentifyMethod getIdentifyMethod() {
        return identifyMethod;
    }

    public void setIdentifyMethod(IdentifyMethod m) {
        identifyMethod = m;
    }

    public UnverifiedPolicy getUnverifiedPolicy() {
        return unverifiedPolicy;
    }

    public void setUnverifiedPolicy(UnverifiedPolicy p) {
        unverifiedPolicy = p;
    }

    public double getFiberThreshold() {
        return fiberThreshold;
    }

    public void setFiberThreshold(double v) {
        fiberThreshold = v;
    }

    public double getMovementThreshold() {
        return movementThreshold;
    }

    public void setMovementThreshold(double v) {
        movementThreshold = v;
    }

    public double getMovementMm() {
        return movementMm;
    }

    public void setMovementMm(double v) {
        movementMm = v;
    }

    public int getStaggerMs() {
        return staggerMs;
    }

    public void setStaggerMs(int v) {
        staggerMs = v;
    }

    public int getSettleMs() {
        return settleMs;
    }

    public void setSettleMs(int v) {
        settleMs = v;
    }

    public synchronized CvPipeline getFiberPipeline() {
        if (fiberPipeline == null) {
            fiberPipeline = OrionPipelines.defaultFiberPipeline();
        }
        return fiberPipeline;
    }

    public synchronized CvPipeline getMovementPipeline() {
        if (movementPipeline == null) {
            movementPipeline = OrionPipelines.defaultMovementPipeline();
        }
        return movementPipeline;
    }

    public synchronized void resetFiberPipeline() {
        fiberPipeline = OrionPipelines.defaultFiberPipeline();
    }

    public synchronized void resetMovementPipeline() {
        movementPipeline = OrionPipelines.defaultMovementPipeline();
    }

    public String getLargeFiducialPartId() {
        return largeFiducialPartId;
    }

    public void setLargeFiducialPartId(String id) {
        this.largeFiducialPartId = id == null ? "" : id;
    }

    public synchronized OrionRailSettings getRailSettings(int rail) {
        for (OrionRailSettings r : railSettings) {
            if (r.getIndex() == rail) {
                return r;
            }
        }
        OrionRailSettings r = new OrionRailSettings(rail);
        railSettings.add(r);
        return r;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getAdapterPort() {
        return adapterPort;
    }

    public void setAdapterPort(String adapterPort) {
        this.adapterPort = adapterPort;
    }

    public int getAdapterBaud() {
        return adapterBaud;
    }

    public void setAdapterBaud(int adapterBaud) {
        this.adapterBaud = adapterBaud;
    }

    public String getHostPort() {
        return hostPort;
    }

    public void setHostPort(String hostPort) {
        this.hostPort = hostPort;
    }

    public int getHostBaud() {
        return hostBaud;
    }

    public void setHostBaud(int hostBaud) {
        this.hostBaud = hostBaud;
    }

    public int getMaxAddress() {
        return maxAddress;
    }

    public void setMaxAddress(int maxAddress) {
        this.maxAddress = maxAddress;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public boolean isAutoCreateFeeders() {
        return autoCreateFeeders;
    }

    public void setAutoCreateFeeders(boolean v) {
        autoCreateFeeders = v;
    }

    public boolean isConnectOnEnable() {
        return connectOnEnable;
    }

    public void setConnectOnEnable(boolean connectOnEnable) {
        this.connectOnEnable = connectOnEnable;
    }
}
