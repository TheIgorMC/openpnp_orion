package org.openpnp.machine.orion;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.machine.orion.vision.OrionPipelines;
import org.openpnp.vision.pipeline.CvPipeline;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
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

    /** The large fiducial (middle one of each feeder's set) used to find feeders on the rail. */
    @Attribute(required = false)
    private String largeFiducialPartId = "";

    @ElementList(required = false)
    private List<OrionRailSettings> railSettings = new ArrayList<>();

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

    @Element(required = false)
    private CvPipeline fiberPipeline;

    @Element(required = false)
    private CvPipeline movementPipeline;

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

    public boolean isConnectOnEnable() {
        return connectOnEnable;
    }

    public void setConnectOnEnable(boolean connectOnEnable) {
        this.connectOnEnable = connectOnEnable;
    }
}
