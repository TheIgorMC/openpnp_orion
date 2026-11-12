package org.openpnp.machine.orion.vision;

/**
 * The pick window of a rail: the stretch of tape just behind the point where the cover tape is peeled back.
 * It is fixed in the machine. A pocket sits correctly when its centre lies half a pocket length plus a margin
 * behind the peel edge, so the opening is fully exposed whatever the part size. Pure arithmetic, no hardware.
 */
public final class OrionPickWindow {
    private OrionPickWindow() {}

    /** How a feeder's pick position is found. */
    public enum Mode {
        /** Offset only, the camera just checks that a pocket is roughly there (large parts). */
        SEMI_BLIND,
        /** The tape is jogged until the camera sees the pocket centred on the pick point (small parts). */
        VISION
    }

    /**
     * Where the pocket centre has to be, along the tape axis (machine coordinate, mm).
     *
     * @param peelEdge  machine coordinate of the edge where the cover tape is peeled
     * @param direction +1 or -1: on which side of the edge the exposed pockets are
     * @param marginMm  distance from the edge to the nearest side of the part's pocket
     * @param lengthMm  pocket length along the tape
     */
    public static double targetAlong(double peelEdge, int direction, double marginMm, double lengthMm) {
        return peelEdge + Math.signum(direction == 0 ? 1 : direction) * (marginMm + lengthMm / 2.0);
    }

    /**
     * Vision for parts that fit inside the limits, semi blind above them. Unknown dimensions (zero) cannot be
     * judged and fall back to semi blind, since a jog loop on an unknown pocket would be a guess.
     */
    public static Mode chooseMode(double widthMm, double lengthMm, double maxWidthMm, double maxLengthMm) {
        if (widthMm <= 0 || lengthMm <= 0) {
            return Mode.SEMI_BLIND;
        }
        double w = Math.min(widthMm, lengthMm);
        double l = Math.max(widthMm, lengthMm);
        double mw = Math.min(maxWidthMm, maxLengthMm);
        double ml = Math.max(maxWidthMm, maxLengthMm);
        return w <= mw && l <= ml ? Mode.VISION : Mode.SEMI_BLIND;
    }
}
