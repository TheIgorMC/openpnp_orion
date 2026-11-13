package org.openpnp.machine.orion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public class OrionBoardCheckTest {
    @Test
    void matchingBoardPassesInEitherOrientation() {
        // job says 100 x 80; belt length 100, width 80.5 (+0.5 clearance)
        assertTrue(OrionBoardCheck.checkSize(100.3, 80.5, 100, 80, 2, 0.5).ok);
        // inserted the other way round: length 80, width 100.5
        assertTrue(OrionBoardCheck.checkSize(80.2, 100.5, 100, 80, 2, 0.5).ok);
    }

    @Test
    void wrongLengthIsReported() {
        OrionBoardCheck.Result r = OrionBoardCheck.checkSize(118.0, 80.5, 100, 80, 2, 0.5);
        assertFalse(r.ok);
        assertTrue(r.message.contains("118.0") && r.message.contains("long"), r.message);
    }

    @Test
    void wrongWidthIsReportedEvenBeforeAnyBoardIsLoaded() {
        OrionBoardCheck.Result r = OrionBoardCheck.checkSize(Double.NaN, 120.0, 100, 80, 2, 0.5);
        assertFalse(r.ok);
        assertTrue(r.message.contains("width"), r.message);
    }

    @Test
    void nothingToCompareIsNotAnError() {
        assertTrue(OrionBoardCheck.checkSize(100, 80, 0, 0, 2, 0.5).ok);
        assertTrue(OrionBoardCheck.checkSize(Double.NaN, Double.NaN, 100, 80, 2, 0.5).ok);
    }

    @Test
    void placementsInTheEdgeStripAreCounted() {
        List<double[]> p = Arrays.asList(new double[] {10, 10}, new double[] {1.5, 40}, new double[] {50, 79},
                new double[] {99, 20});
        assertEquals(3, OrionBoardCheck.placementsInEdge(p, 100, 80, 3));
        assertEquals(0, OrionBoardCheck.placementsInEdge(p, 100, 80, 0));
    }

    @Test
    void anOriginOutsideTheBoardMakesTheEdgeCheckUnavailable() {
        List<double[]> p = Arrays.asList(new double[] {-50, 10}, new double[] {40, 20});
        assertEquals(-1, OrionBoardCheck.placementsInEdge(p, 100, 80, 3));
    }
}
