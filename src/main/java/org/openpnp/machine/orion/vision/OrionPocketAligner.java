package org.openpnp.machine.orion.vision;

/**
 * Jogs a tape until a pocket sits on the target. The hardware is behind two small interfaces so the loop can be
 * tested with a simulated tape.
 */
public final class OrionPocketAligner {
    private OrionPocketAligner() {}

    public interface Sensor {
        /** Pocket centre minus target along the tape, mm; null when no pocket is visible. */
        Double measure() throws Exception;
    }

    public interface Jogger {
        /** Move the tape by this many 0.1 mm units (the sign convention is the caller's, see jogSign). */
        void jog(int tenths) throws Exception;
    }

    public static class Result {
        public boolean converged;
        public boolean alreadyInPlace;
        public int jogs;
        public double errorMm = Double.NaN;
        /** +1 or -1: the sign that moved the pocket the right way (flipped when the first jog went wrong). */
        public int jogSign;
        public String problem = "";
    }

    /**
     * @param tolMm       final error limit
     * @param maxJogs     measurement / jog rounds at most
     * @param searchMm    when no pocket is visible: jog this far per try
     * @param maxSearches search jogs at most
     * @param jogSign     which way a positive jog moves the pocket along the axis (+1 / -1)
     */
    public static Result align(Sensor sensor, Jogger jogger, double tolMm, int maxJogs, double searchMm,
            int maxSearches, int jogSign) throws Exception {
        Result r = new Result();
        r.jogSign = jogSign >= 0 ? 1 : -1;
        Double e = sensor.measure();
        int searches = 0;
        while (e == null) {
            if (searches++ >= maxSearches) {
                r.problem = "no pocket visible, even after searching along the tape";
                return r;
            }
            jogger.jog((int) Math.round(searchMm * 10.0 * r.jogSign));
            r.jogs++;
            e = sensor.measure();
        }
        if (Math.abs(e) <= tolMm) {
            r.converged = true;
            r.alreadyInPlace = r.jogs == 0;
            r.errorMm = e;
            return r;
        }
        boolean flipped = false;
        double previous = Math.abs(e);
        for (int round = 0; round < maxJogs; round++) {
            int tenths = (int) Math.round(-e * 10.0 * r.jogSign);
            if (tenths == 0) {
                tenths = e > 0 ? -r.jogSign : r.jogSign; // below the unit's resolution but still outside the limit
            }
            jogger.jog(tenths);
            r.jogs++;
            Double now = sensor.measure();
            if (now == null) {
                r.problem = "pocket lost after a jog of " + tenths / 10.0 + " mm";
                return r;
            }
            if (Math.abs(now) <= tolMm) {
                r.converged = true;
                r.errorMm = now;
                return r;
            }
            if (!flipped && Math.abs(now) > previous + 0.05) {
                // Worse: the jog direction is the other way round on this machine.
                r.jogSign = -r.jogSign;
                flipped = true;
            }
            previous = Math.abs(now);
            e = now;
        }
        r.errorMm = e;
        r.problem = String.format("still %.2f mm off after %d jogs", e, r.jogs);
        return r;
    }
}
