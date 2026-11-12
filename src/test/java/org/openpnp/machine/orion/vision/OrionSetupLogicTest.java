package org.openpnp.machine.orion.vision;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class OrionSetupLogicTest {
    @Test
    void targetIsEdgePlusMarginPlusHalfLength() {
        assertEquals(100 + 0.5 + 0.6, OrionPickWindow.targetAlong(100, 1, 0.5, 1.2), 1e-9);
        assertEquals(100 - (0.5 + 1.75), OrionPickWindow.targetAlong(100, -1, 0.5, 3.5), 1e-9);
    }

    @Test
    void smallPartsUseVisionLargeOnesGoSemiBlind() {
        assertEquals(OrionPickWindow.Mode.VISION, OrionPickWindow.chooseMode(0.5, 1.0, 3, 4));
        assertEquals(OrionPickWindow.Mode.VISION, OrionPickWindow.chooseMode(4, 3, 3, 4), "orientation does not matter");
        assertEquals(OrionPickWindow.Mode.SEMI_BLIND, OrionPickWindow.chooseMode(5, 5, 3, 4));
        assertEquals(OrionPickWindow.Mode.SEMI_BLIND, OrionPickWindow.chooseMode(1, 6, 3, 4));
        assertEquals(OrionPickWindow.Mode.SEMI_BLIND, OrionPickWindow.chooseMode(0, 0, 3, 4), "unknown size");
    }

    /** A tape with pockets every `pitch` mm; the pocket nearest the target is the one seen. */
    static class Tape {
        double offset; // pocket centre position relative to the target, mm
        final double pitch;
        final int sign; // which way +jog moves the pocket
        boolean blind;
        int jogs;

        Tape(double offset, double pitch, int sign) {
            this.offset = offset;
            this.pitch = pitch;
            this.sign = sign;
        }

        Double measure() {
            if (blind) {
                return null;
            }
            double e = offset % pitch;
            if (e > pitch / 2) {
                e -= pitch;
            }
            if (e < -pitch / 2) {
                e += pitch;
            }
            return e;
        }

        void jog(int tenths) {
            offset += sign * tenths / 10.0;
            jogs++;
        }
    }

    @Test
    void alignsEvenWhenTheFirstJogGoesTheWrongWay() throws Exception {
        Tape t = new Tape(1.3, 4, -1);
        OrionPocketAligner.Result r = OrionPocketAligner.align(t::measure, t::jog, 0.1, 6, 1.0, 4, 1);
        assertTrue(r.converged, r.problem);
        assertEquals(-1, r.jogSign, "the direction is learned");
        assertTrue(Math.abs(t.measure()) <= 0.1);
    }

    @Test
    void doesNothingWhenTheSavedZeroIsAlreadyRight() throws Exception {
        Tape t = new Tape(0.04, 4, 1);
        OrionPocketAligner.Result r = OrionPocketAligner.align(t::measure, t::jog, 0.1, 6, 1.0, 4, 1);
        assertTrue(r.converged);
        assertTrue(r.alreadyInPlace);
        assertEquals(0, t.jogs);
    }

    @Test
    void searchesAlongTheTapeWhenNoPocketIsVisible() throws Exception {
        Tape t = new Tape(0.0, 4, 1);
        t.blind = true;
        int[] n = {0};
        OrionPocketAligner.Result r = OrionPocketAligner.align(
                () -> n[0] >= 2 ? t.measure() : (t.blind ? null : t.measure()), x -> {
                    n[0]++;
                    t.blind = n[0] < 2;
                    t.jog(x);
                }, 0.1, 6, 1.0, 4, 1);
        assertTrue(r.converged, r.problem);
        assertTrue(r.jogs >= 2);
    }

    @Test
    void givesUpWhenThereIsNoPocketAtAll() throws Exception {
        OrionPocketAligner.Result r = OrionPocketAligner.align(() -> null, x -> { }, 0.1, 6, 1.0, 3, 1);
        assertFalse(r.converged);
        assertTrue(r.problem.contains("no pocket"));
        assertEquals(3, r.jogs);
    }

    /** Z axis going down; the part top (seal) at z = -21.37. */
    @Test
    void zProbeStopsAtTheSealAndGoesAFewTenthsDeeper() throws Exception {
        double[] z = {0};
        OrionZProbe.Params p = new OrionZProbe.Params();
        p.expectedZ = -21.0;
        p.threshold = 100;
        p.sealedBelow = true;
        p.stepMm = 0.05;
        p.extraMm = 0.1;
        OrionZProbe.Result r = OrionZProbe.probe(p, v -> z[0] = v, () -> z[0] <= -21.37 ? 60 : 400, ms -> { });
        assertTrue(r.found, r.problem);
        assertEquals(-21.40, r.sealZ, 0.026);
        assertEquals(r.sealZ - 0.1, r.pickZ, 1e-9);
        assertTrue(r.steps > 10 && r.steps < 80);
    }

    @Test
    void zProbeReportsNoSealAndAnAlreadySealedStart() throws Exception {
        OrionZProbe.Params p = new OrionZProbe.Params();
        p.expectedZ = -5;
        p.threshold = 100;
        assertFalse(OrionZProbe.probe(p, v -> { }, () -> 400, ms -> { }).found);
        OrionZProbe.Result sealed = OrionZProbe.probe(p, v -> { }, () -> 10, ms -> { });
        assertFalse(sealed.found);
        assertTrue(sealed.problem.contains("already"));
    }

    @Test
    void zProbeCanReadTheOtherWayRound() throws Exception {
        double[] z = {0};
        OrionZProbe.Params p = new OrionZProbe.Params();
        p.expectedZ = -10;
        p.threshold = 50;
        p.sealedBelow = false;
        OrionZProbe.Result r = OrionZProbe.probe(p, v -> z[0] = v, () -> z[0] <= -10.2 ? 80 : 5, ms -> { });
        assertTrue(r.found);
    }
}
