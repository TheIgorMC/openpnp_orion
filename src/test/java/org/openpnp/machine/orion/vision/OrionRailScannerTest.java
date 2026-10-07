package org.openpnp.machine.orion.vision;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.orion.vision.OrionRailScanner.Params;
import org.openpnp.machine.orion.vision.OrionRailScanner.Result;
import org.openpnp.machine.orion.vision.OrionRailScanner.State;
import org.openpnp.machine.orion.vision.OrionRailScanner.Target;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class OrionRailScannerTest {
    /** Physical fiducials on the rail, plus a camera view of +-4 mm. */
    List<Double> physical = new ArrayList<>();
    int finds;
    Params p;
    OrionRailScanner scanner;
    List<String> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        p = new Params();
        p.xMin = 0;
        p.xMax = 300;
        p.fiducialY = 20;
        p.fiducialZ = -5;
        scanner = new OrionRailScanner(p, nominal -> {
            finds++;
            for (double x : physical) {
                if (Math.abs(x - nominal.getX()) <= 4.0) {
                    return new Location(LengthUnit.Millimeters, x, 20, -5, 0);
                }
            }
            return null;
        }, null);
    }

    Target target(String name, double x, boolean online, double slotShift) {
        return new Target(name, new Location(LengthUnit.Millimeters, x, 20, -5, 0), online, slotShift,
                f -> saved.add(name + "=" + String.format("%.2f", f.getX())));
    }

    @Test
    void smallShiftIsSavedAndNoRescanHappens() throws Exception {
        physical.addAll(Arrays.asList(50.0, 100.4, 150.0));
        Result r = scanner.run(Arrays.asList(target("a", 50, true, Double.NaN),
                target("b", 100, true, Double.NaN), target("c", 150, true, Double.NaN)));
        assertEquals(State.OK, r.targets.get(0).state);
        assertEquals(State.SHIFTED, r.targets.get(1).state);
        assertEquals(Arrays.asList("b=100.40"), saved);
        assertFalse(r.rescanNeeded);
        assertEquals(3, finds, "no sweep when everything is where it should be");
    }

    @Test
    void missingFeederTriggersExactlyOneSweepAfterAllAreChecked() throws Exception {
        physical.addAll(Arrays.asList(50.0, 150.0, 200.0)); // b moved from 100 to 200
        Result r = scanner.run(Arrays.asList(target("a", 50, true, Double.NaN),
                target("b", 100, true, Double.NaN), target("c", 150, true, Double.NaN)));
        assertTrue(r.rescanRan);
        assertEquals(State.RELOCATED, r.targets.get(1).state);
        assertEquals(Arrays.asList("b=200.00"), saved);
        assertTrue(r.unclaimed.isEmpty());
        // Phase 1 is 3 finds; the sweep skips known feeders' neighbourhoods, so it stays small.
        assertTrue(finds < 3 + 52, "sweep finds " + finds);
    }

    @Test
    void slotShiftPredictionPicksTheRightFiducialAmongSeveral() throws Exception {
        physical.addAll(Arrays.asList(50.0, 120.0, 200.0, 260.0)); // two feeders moved/new
        Result r = scanner.run(Arrays.asList(target("a", 50, true, Double.NaN),
                target("b", 100, true, 100.0), // unit slot moved +100 -> expected 200
                target("c", 130, true, -10.0))); // -> expected 120
        assertEquals(State.RELOCATED, r.targets.get(1).state);
        assertEquals(State.RELOCATED, r.targets.get(2).state);
        assertTrue(saved.contains("b=200.00"));
        assertTrue(saved.contains("c=120.00"));
        assertEquals(1, r.unclaimed.size());
        assertEquals(260.0, r.unclaimed.get(0).getX(), 1e-9);
    }

    @Test
    void ambiguousCaseIsLeftUnresolvedNotGuessed() throws Exception {
        physical.addAll(Arrays.asList(50.0, 200.0, 250.0));
        Result r = scanner.run(Arrays.asList(target("a", 50, true, Double.NaN),
                target("b", 100, true, Double.NaN), target("c", 150, true, Double.NaN)));
        assertEquals(State.UNRESOLVED, r.targets.get(1).state);
        assertEquals(State.UNRESOLVED, r.targets.get(2).state);
        assertEquals(2, r.unclaimed.size());
        assertTrue(saved.isEmpty(), "nothing saved for unresolved feeders");
    }

    @Test
    void feederWhoseUnitIsGoneStaysMissing() throws Exception {
        physical.addAll(Arrays.asList(50.0));
        Result r = scanner.run(Arrays.asList(target("a", 50, true, Double.NaN),
                target("gone", 100, false, Double.NaN)));
        assertEquals(State.MISSING, r.targets.get(1).state);
        assertTrue(r.rescanRan);
    }

    @Test
    void autoRescanOffOnlyReportsNeed() throws Exception {
        p.autoRescan = false;
        Result r = scanner.run(Arrays.asList(target("a", 50, true, Double.NaN)));
        assertTrue(r.rescanNeeded);
        assertFalse(r.rescanRan);
        assertEquals(1, finds);
    }

    @Test
    void largeShiftIsNotSavedAsASmallShift() throws Exception {
        physical.addAll(Arrays.asList(53.5)); // within camera view but beyond maxShift
        Result r = scanner.run(Arrays.asList(target("a", 50, true, Double.NaN)));
        assertNotEquals(State.SHIFTED, r.targets.get(0).state);
    }
}
