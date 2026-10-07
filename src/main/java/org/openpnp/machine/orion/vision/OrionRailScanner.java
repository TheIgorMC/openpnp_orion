package org.openpnp.machine.orion.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * Vision procedure for one rail, pure logic (the camera is behind {@link FiducialFinder}).
 *
 * <ol>
 * <li><b>Verify</b>: look for the large fiducial of every known feeder on the rail, one after the
 * other. A small shift is saved (new X). A fiducial that is not where it should be marks the feeder
 * as missing.</li>
 * <li><b>Rescan</b>, only once and only after ALL feeders of the rail have been checked, and only if
 * something is missing: sweep the unoccupied stretches of the rail in steps (6 mm) for large
 * fiducials, then associate what was found with the missing feeders.</li>
 * </ol>
 * Association is automatic when it is unambiguous: a feeder whose slot position on the unit moved is
 * expected at the matching shifted X, or exactly one missing feeder and one unclaimed fiducial are
 * left. Anything else is returned as unresolved for a manual or light-assisted decision.
 */
public class OrionRailScanner {
    public interface FiducialFinder {
        /** Look for a large fiducial near the nominal location. Return null when none is found. */
        Location find(Location nominal) throws Exception;
    }

    public interface Progress {
        void update(String text, int done, int total);
    }

    public static class Params {
        /** X range of the rail, mm. */
        public double xMin;
        public double xMax;
        /** Where the large fiducials sit across the rail (machine Y/Z, mm). */
        public double fiducialY;
        public double fiducialZ;
        public double scanStepMm = 6.0;
        /** Free-area margin around known feeders; fiducials closer than this belong to a known one. */
        public double exclusionMm = 5.0;
        /** Shifts below this are noise and not saved. */
        public double minSaveMm = 0.02;
        /** Shifts above this are not "the same feeder, slightly moved". */
        public double maxShiftMm = 2.0;
        /** Predicted vs found distance still counted as the same feeder in association. */
        public double associateTolMm = 2.0;
        /** Run the rescan when something is missing. If false the result only says it is needed. */
        public boolean autoRescan = true;
    }

    public enum State { OK, SHIFTED, MISSING, RELOCATED, UNRESOLVED }

    /** One feeder to verify. */
    public static class Target {
        public final String name;
        /** Where its large fiducial was last (mm; only X is used for matching). */
        public final Location nominal;
        /** The unit answers on the bus (so it exists, it is just not where it was). */
        public final boolean unitOnline;
        /** How far the unit's slot position moved since teaching, mm; NaN = unknown. */
        public final double slotShiftMm;
        /** Called with the measured fiducial location when the feeder was found / relocated. */
        public final Consumer<Location> onFound;

        public State state = State.MISSING;
        public Location found;

        public Target(String name, Location nominal, boolean unitOnline, double slotShiftMm,
                Consumer<Location> onFound) {
            this.name = name;
            this.nominal = nominal.convertToUnits(LengthUnit.Millimeters);
            this.unitOnline = unitOnline;
            this.slotShiftMm = slotShiftMm;
            this.onFound = onFound;
        }
    }

    public static class Result {
        public final List<Target> targets = new ArrayList<>();
        public boolean rescanNeeded;
        public boolean rescanRan;
        /** Fiducials found by the sweep that no feeder claimed (new, unbound feeders?). */
        public final List<Location> unclaimed = new ArrayList<>();
        public final List<String> log = new ArrayList<>();

        public List<Target> in(State s) {
            List<Target> out = new ArrayList<>();
            for (Target t : targets) {
                if (t.state == s) {
                    out.add(t);
                }
            }
            return out;
        }

        public String summary() {
            return String.format("%d OK, %d shifted (saved), %d relocated, %d unresolved, %d missing; "
                    + "%d unclaimed fiducial(s)%s", in(State.OK).size(), in(State.SHIFTED).size(),
                    in(State.RELOCATED).size(), in(State.UNRESOLVED).size(), in(State.MISSING).size(),
                    unclaimed.size(), rescanNeeded && !rescanRan ? " (rescan needed)" : "");
        }
    }

    private final Params p;
    private final FiducialFinder finder;
    private final Progress progress;

    public OrionRailScanner(Params params, FiducialFinder finder, Progress progress) {
        this.p = params;
        this.finder = finder;
        this.progress = progress;
    }

    private void report(String text, int done, int total) {
        if (progress != null) {
            progress.update(text, done, total);
        }
    }

    private Location at(double x) {
        return new Location(LengthUnit.Millimeters, x, p.fiducialY, p.fiducialZ, 0);
    }

    private static double x(Location l) {
        return l.convertToUnits(LengthUnit.Millimeters).getX();
    }

    public Result run(List<Target> targets) throws Exception {
        Result r = new Result();
        r.targets.addAll(targets);
        r.targets.sort((a, b) -> Double.compare(a.nominal.getX(), b.nominal.getX()));

        // ---- phase 1: verify every feeder first
        List<Double> known = new ArrayList<>();
        int i = 0;
        for (Target t : r.targets) {
            report("Checking " + t.name, i++, r.targets.size());
            Location f = finder.find(t.nominal);
            double dx = f == null ? Double.NaN : x(f) - t.nominal.getX();
            if (f == null || Math.abs(dx) > p.maxShiftMm) {
                t.state = State.MISSING;
                r.log.add(f == null ? t.name + ": no fiducial at X=" + fmt(t.nominal.getX())
                        : String.format("%s: fiducial %.2f mm away (limit %.2f), treated as missing",
                                t.name, dx, p.maxShiftMm));
                continue;
            }
            known.add(x(f));
            t.found = f;
            if (Math.abs(dx) >= p.minSaveMm) {
                t.state = State.SHIFTED;
                t.onFound.accept(f);
                r.log.add(String.format("%s: shifted %+.3f mm, new X saved", t.name, dx));
            } else {
                t.state = State.OK;
            }
        }

        List<Target> missing = r.in(State.MISSING);
        r.rescanNeeded = !missing.isEmpty();
        if (!r.rescanNeeded) {
            report("All feeders in place", 1, 1);
            return r;
        }
        if (!p.autoRescan) {
            r.log.add(missing.size() + " feeder(s) missing, rescan needed (auto rescan is off)");
            return r;
        }

        // ---- phase 2: one sweep of the free stretches of the rail
        r.rescanRan = true;
        List<Location> candidates = sweep(known, r);

        // ---- phase 3: associate
        List<Target> pending = new ArrayList<>();
        for (Target t : missing) {
            if (t.unitOnline) {
                pending.add(t);
            } else {
                r.log.add(t.name + ": its unit does not answer on the bus (removed or unpowered)");
            }
        }
        // 3a. slot shift prediction
        for (Target t : new ArrayList<>(pending)) {
            if (Double.isNaN(t.slotShiftMm)) {
                continue;
            }
            double predicted = t.nominal.getX() + t.slotShiftMm;
            Location best = null;
            for (Location c : candidates) {
                if (Math.abs(x(c) - predicted) <= p.associateTolMm
                        && (best == null || Math.abs(x(c) - predicted) < Math.abs(x(best) - predicted))) {
                    best = c;
                }
            }
            if (best != null) {
                claim(t, best, "matches its moved slot position", r);
                candidates.remove(best);
                pending.remove(t);
            }
        }
        // 3b. one and one
        if (pending.size() == 1 && candidates.size() == 1) {
            claim(pending.get(0), candidates.get(0), "only unmatched feeder and fiducial", r);
            candidates.clear();
            pending.clear();
        }
        for (Target t : pending) {
            t.state = State.UNRESOLVED;
            r.log.add(t.name + ": " + candidates.size() + " unclaimed fiducial(s), cannot decide "
                    + "automatically (use manual locate or the fiber light search)");
        }
        r.unclaimed.addAll(candidates);
        report("Done", 1, 1);
        return r;
    }

    private void claim(Target t, Location c, String why, Result r) {
        t.state = State.RELOCATED;
        t.found = c;
        t.onFound.accept(c);
        r.log.add(String.format("%s: found at X=%.2f (%s), new X saved", t.name, x(c), why));
    }

    /** Sweep [xMin,xMax] outside ±exclusion of the known fiducials. Returns new fiducial locations. */
    List<Location> sweep(List<Double> known, Result r) throws Exception {
        List<double[]> free = new ArrayList<>();
        known.sort(Double::compare);
        double cursor = p.xMin;
        for (double k : known) {
            if (k - p.exclusionMm > cursor) {
                free.add(new double[] {cursor, k - p.exclusionMm});
            }
            cursor = Math.max(cursor, k + p.exclusionMm);
        }
        if (cursor < p.xMax) {
            free.add(new double[] {cursor, p.xMax});
        }
        int total = 0;
        for (double[] seg : free) {
            total += (int) Math.floor((seg[1] - seg[0]) / p.scanStepMm) + 1;
        }
        List<Location> found = new ArrayList<>();
        int done = 0;
        for (double[] seg : free) {
            double xs = seg[0];
            while (xs <= seg[1] + 1e-9) {
                report("Scanning free area X=" + fmt(xs), done++, total);
                Location f = finder.find(at(xs));
                if (f != null && !nearAny(x(f), known) && !nearAny(x(f), xs(found), 2.0)) {
                    found.add(f);
                    r.log.add(String.format("fiducial found at X=%.2f", x(f)));
                    // Skip the rest of this feeder's footprint.
                    xs = Math.max(xs + p.scanStepMm, x(f) + p.exclusionMm);
                } else {
                    xs += p.scanStepMm;
                }
            }
        }
        return found;
    }

    private static List<Double> xs(List<Location> l) {
        List<Double> out = new ArrayList<>();
        for (Location c : l) {
            out.add(x(c));
        }
        return out;
    }

    private boolean nearAny(double v, List<Double> list) {
        return nearAny(v, list, p.exclusionMm);
    }

    private static boolean nearAny(double v, List<Double> list, double tol) {
        for (double k : list) {
            if (Math.abs(k - v) < tol) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /** Convenience for UI code that works with lengths. */
    static Length mm(double v) {
        return new Length(v, LengthUnit.Millimeters);
    }
}
