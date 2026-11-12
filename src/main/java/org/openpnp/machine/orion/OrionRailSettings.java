package org.openpnp.machine.orion;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Root;

/** Geometry and tolerances for the vision verify / rescan of one rail. */
@Root
public class OrionRailSettings {
    @Attribute
    private int index;

    /** Rail X range (machine coordinates, mm) that may hold feeders. xMax <= xMin means not set up. */
    @Attribute(required = false)
    private double xMin = 0;

    @Attribute(required = false)
    private double xMax = 0;

    /** Machine Y / Z where the large fiducials of this rail sit. */
    @Attribute(required = false)
    private double fiducialY = 0;

    @Attribute(required = false)
    private double fiducialZ = 0;

    @Attribute(required = false)
    private double scanStepMm = 6.0;

    @Attribute(required = false)
    private double exclusionMm = 5.0;

    /** Distance between the three fiducials of a feeder (0 = unknown); only the middle one is wanted. */
    @Attribute(required = false)
    private double fiducialSpacingMm = 0;

    @Attribute(required = false)
    private double minSaveMm = 0.02;

    @Attribute(required = false)
    private double maxShiftMm = 2.0;

    @Attribute(required = false)
    private boolean autoRescan = true;

    /**
     * The pick window: where the cover tape is peeled back, as a machine coordinate along the tape axis. The pocket of
     * every feeder of the rail is brought to peelEdge + direction * (margin + half its length).
     */
    @Attribute(required = false)
    private boolean pickWindowSet = false;

    @Attribute(required = false)
    private double peelEdge = 0;

    /** Distance from the peel edge to the near side of the exposed pocket, mm. */
    @Attribute(required = false)
    private double windowMarginMm = 0.5;

    /** +1 or -1: the exposed pockets lie on the positive / negative side of the peel edge. */
    @Attribute(required = false)
    private int windowDirection = 1;

    /** Pick Z every feeder of the rail starts with, until its own is found by the vacuum probe. */
    @Attribute(required = false)
    private boolean pickZSet = false;

    @Attribute(required = false)
    private double defaultPickZ = 0;

    public boolean isPickWindowSet() {
        return pickWindowSet;
    }

    public void setPickWindowSet(boolean v) {
        pickWindowSet = v;
    }

    public double getPeelEdge() {
        return peelEdge;
    }

    public void setPeelEdge(double v) {
        peelEdge = v;
    }

    public double getWindowMarginMm() {
        return windowMarginMm;
    }

    public void setWindowMarginMm(double v) {
        windowMarginMm = v;
    }

    public int getWindowDirection() {
        return windowDirection >= 0 ? 1 : -1;
    }

    public void setWindowDirection(int v) {
        windowDirection = v >= 0 ? 1 : -1;
    }

    public boolean isPickZSet() {
        return pickZSet;
    }

    public void setPickZSet(boolean v) {
        pickZSet = v;
    }

    public double getDefaultPickZ() {
        return defaultPickZ;
    }

    public void setDefaultPickZ(double v) {
        defaultPickZ = v;
    }

    /** Where the fiber spot is seen, relative to the large fiducial (mm, machine axes). */
    @Attribute(required = false)
    private double fiberOffsetX = 0;

    @Attribute(required = false)
    private double fiberOffsetY = 0;

    public double getFiberOffsetX() {
        return fiberOffsetX;
    }

    public void setFiberOffsetX(double v) {
        fiberOffsetX = v;
    }

    public double getFiberOffsetY() {
        return fiberOffsetY;
    }

    public void setFiberOffsetY(double v) {
        fiberOffsetY = v;
    }

    /** Where a sprocket hole is seen for the tape-movement check, relative to the large fiducial. */
    @Attribute(required = false)
    private double holeOffsetX = 0;

    @Attribute(required = false)
    private double holeOffsetY = 0;

    public double getHoleOffsetX() {
        return holeOffsetX;
    }

    public void setHoleOffsetX(double v) {
        holeOffsetX = v;
    }

    public double getHoleOffsetY() {
        return holeOffsetY;
    }

    public void setHoleOffsetY(double v) {
        holeOffsetY = v;
    }

    OrionRailSettings() {
    }

    public OrionRailSettings(int index) {
        this.index = index;
    }

    public boolean isConfigured() {
        return xMax > xMin;
    }

    public int getIndex() {
        return index;
    }

    public double getXMin() {
        return xMin;
    }

    public void setXMin(double v) {
        xMin = v;
    }

    public double getXMax() {
        return xMax;
    }

    public void setXMax(double v) {
        xMax = v;
    }

    public double getFiducialY() {
        return fiducialY;
    }

    public void setFiducialY(double v) {
        fiducialY = v;
    }

    public double getFiducialZ() {
        return fiducialZ;
    }

    public void setFiducialZ(double v) {
        fiducialZ = v;
    }

    public double getScanStepMm() {
        return scanStepMm;
    }

    public void setScanStepMm(double v) {
        scanStepMm = v;
    }

    public double getFiducialSpacingMm() {
        return fiducialSpacingMm;
    }

    public void setFiducialSpacingMm(double v) {
        fiducialSpacingMm = v;
    }

    public double getExclusionMm() {
        return exclusionMm;
    }

    public void setExclusionMm(double v) {
        exclusionMm = v;
    }

    public double getMinSaveMm() {
        return minSaveMm;
    }

    public void setMinSaveMm(double v) {
        minSaveMm = v;
    }

    public double getMaxShiftMm() {
        return maxShiftMm;
    }

    public void setMaxShiftMm(double v) {
        maxShiftMm = v;
    }

    public boolean isAutoRescan() {
        return autoRescan;
    }

    public void setAutoRescan(boolean v) {
        autoRescan = v;
    }
}
