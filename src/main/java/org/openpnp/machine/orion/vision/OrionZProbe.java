package org.openpnp.machine.orion.vision;

/**
 * Finds the height at which the nozzle seals on a part by lowering it in small steps with the vacuum on and reading the
 * vacuum sensor. Z decreases going down (OpenPnP's usual axis). Pure logic over two interfaces.
 */
public final class OrionZProbe {
    private OrionZProbe() {}

    public interface Mover {
        void moveZ(double z) throws Exception;
    }

    public interface Sensor {
        double read() throws Exception;
    }

    public static class Params {
        /** Z the cycle expects the pick height to be at (taught or the rail default). */
        public double expectedZ;
        public double searchAboveMm = 1.0;
        public double searchBelowMm = 1.5;
        public double stepMm = 0.05;
        /** Deeper than the first sealed reading, mm. */
        public double extraMm = 0.10;
        /** Sealed when reading <= threshold (true) or >= threshold (false). */
        public boolean sealedBelow = true;
        public double threshold;
        public int settleMs = 60;
    }

    public static class Result {
        public boolean found;
        /** Height to pick at: the first sealed Z minus the extra. */
        public double pickZ = Double.NaN;
        public double sealZ = Double.NaN;
        public double reading = Double.NaN;
        public int steps;
        public String problem = "";
    }

    static boolean sealed(Params p, double v) {
        return p.sealedBelow ? v <= p.threshold : v >= p.threshold;
    }

    public static Result probe(Params p, Mover mover, Sensor sensor, java.util.function.IntConsumer pause)
            throws Exception {
        Result r = new Result();
        double start = p.expectedZ + p.searchAboveMm;
        double end = p.expectedZ - p.searchBelowMm;
        double step = Math.max(0.01, p.stepMm);
        mover.moveZ(start);
        pause.accept(p.settleMs);
        if (sealed(p, sensor.read())) {
            r.problem = "the vacuum already reads as sealed at the start height (" + start
                    + " mm): a part is on the nozzle, or the threshold is wrong";
            return r;
        }
        for (double z = start - step; z >= end - 1e-9; z -= step) {
            mover.moveZ(z);
            r.steps++;
            pause.accept(p.settleMs);
            double v = sensor.read();
            if (sealed(p, v)) {
                r.found = true;
                r.sealZ = z;
                r.reading = v;
                r.pickZ = z - p.extraMm;
                return r;
            }
        }
        r.problem = String.format("no vacuum seal between Z %.2f and %.2f (no part in the pocket, or the sensor / "
                + "threshold is not right)", start, end);
        return r;
    }
}
