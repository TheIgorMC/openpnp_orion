package org.openpnp.machine.orion.vision;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.orion.vision.OrionIdentifier.FiberProbe;
import org.openpnp.machine.orion.vision.OrionIdentifier.MovementProbe;
import org.openpnp.machine.orion.vision.OrionRailScanner.Params;
import org.openpnp.machine.orion.vision.OrionRailScanner.Result;
import org.openpnp.machine.orion.vision.OrionRailScanner.State;
import org.openpnp.machine.orion.vision.OrionRailScanner.Target;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class OrionIdentifierTest {
    /** The unit the camera is looking at. */
    String subject = "u5";
    final Set<String> fibersOn = new TreeSet<>();
    final Set<String> movedBack = new TreeSet<>();
    int tests;
    List<String> log = new ArrayList<>();
    List<String> units = Arrays.asList("u1", "u2", "u3", "u4", "u5", "u6", "u7", "u8");

    OrionIdentifier.Fibers<String> fibers = (on, all) -> {
        fibersOn.clear();
        fibersOn.addAll(on);
    };

    @Test
    void binarySearchFindsTheSubjectInLogSteps() throws Exception {
        FiberProbe<String> probe = new FiberProbe<>(units, fibers,
                () -> fibersOn.contains(subject) ? 200 : 20, 50, 0) {
            @Override
            public boolean test(List<String> active) throws Exception {
                tests++;
                return super.test(active);
            }
        };
        assertEquals("u5", OrionIdentifier.search(units, probe, log::add));
        // 1 (all) + 3 halvings (8 -> 1) + 1 confirm
        assertEquals(5, tests);
        assertTrue(fibersOn.isEmpty(), "all fibers off afterwards");
    }

    @Test
    void everyPositionIsFound() throws Exception {
        for (String s : units) {
            subject = s;
            FiberProbe<String> probe = new FiberProbe<>(units, fibers,
                    () -> fibersOn.contains(subject) ? 200 : 20, 50, 0);
            assertEquals(s, OrionIdentifier.search(units, probe, log::add));
        }
    }

    @Test
    void nothingSeenReturnsNull() throws Exception {
        subject = "someone-else";
        FiberProbe<String> probe = new FiberProbe<>(units, fibers,
                () -> fibersOn.contains(subject) ? 200 : 20, 50, 0);
        assertNull(OrionIdentifier.search(units, probe, log::add));
    }

    @Test
    void tooDimLedIsNotGuessed() throws Exception {
        FiberProbe<String> probe = new FiberProbe<>(units, fibers,
                () -> fibersOn.contains(subject) ? 30 : 20, 50, 0);
        assertNull(OrionIdentifier.search(units, probe, log::add));
    }

    @Test
    void tapeMovementFallbackFindsSubjectAndRestoresTapes() throws Exception {
        OrionIdentifier.Mover<String> mover = (us, back) -> {
            for (String u : us) {
                if (back) {
                    movedBack.add(u);
                } else {
                    movedBack.remove(u);
                }
            }
        };
        OrionIdentifier.FrameSource frames = new OrionIdentifier.FrameSource() {
            public Object snap() {
                return movedBack.contains(subject) ? 1 : 0;
            }

            public double difference(Object a, Object b) {
                return Math.abs((Integer) a - (Integer) b) * 100;
            }
        };
        MovementProbe<String> probe = new MovementProbe<>(mover, frames, 50, 0);
        assertEquals("u5", OrionIdentifier.search(units, probe, log::add));
        assertTrue(movedBack.isEmpty(), "tapes moved back forward again");
    }

    @Test
    void scannerUsesIdentifierForAmbiguousLeftovers() throws Exception {
        List<Double> physical = Arrays.asList(50.0, 200.0, 250.0);
        Params p = new Params();
        p.xMin = 0;
        p.xMax = 300;
        p.fiducialY = 20;
        p.fiducialZ = -5;
        List<String> saved = new ArrayList<>();
        OrionRailScanner sc = new OrionRailScanner(p, nominal -> {
            for (double x : physical) {
                if (Math.abs(x - nominal.getX()) <= 4.0) {
                    return new Location(LengthUnit.Millimeters, x, 20, -5, 0);
                }
            }
            return null;
        }, null).withIdentifier(new OrionRailScanner.Identifier() {
            public String method() {
                return "test";
            }

            public Target identify(Location f, List<Target> pending) {
                // the unit "b" belongs to 250, "c" to 200
                for (Target t : pending) {
                    if (t.name.equals(f.getX() > 225 ? "b" : "c")) {
                        return t;
                    }
                }
                return null;
            }
        });
        List<Target> ts = Arrays.asList(t("a", 50, saved), t("b", 100, saved), t("c", 150, saved));
        Result r = sc.run(ts);
        assertEquals(State.RELOCATED, r.targets.get(1).state);
        assertEquals(State.RELOCATED, r.targets.get(2).state);
        assertTrue(saved.contains("b=250.00") && saved.contains("c=200.00"), saved.toString());
        assertTrue(r.unclaimed.isEmpty());
    }

    private Target t(String name, double x, List<String> saved) {
        return new Target(name, new Location(LengthUnit.Millimeters, x, 20, -5, 0), true, Double.NaN,
                f -> saved.add(name + "=" + String.format("%.2f", f.getX())));
    }
}
