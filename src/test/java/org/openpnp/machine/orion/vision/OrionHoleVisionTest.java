package org.openpnp.machine.orion.vision;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.orion.vision.OrionHoleVision.Axis;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class OrionHoleVisionTest {
    private static Location loc(double x, double y) {
        return new Location(LengthUnit.Millimeters, x, y, 5, 0);
    }

    @Test
    void shiftIsWrappedIntoHalfAPitch() {
        assertEquals(0.1, OrionHoleVision.wrap(0.1, 4), 1e-9);
        assertEquals(-0.1, OrionHoleVision.wrap(3.9, 4), 1e-9, "a hole one pitch away is the same hole");
        assertEquals(0.2, OrionHoleVision.wrap(-3.8, 4), 1e-9);
        assertEquals(0.0, OrionHoleVision.wrap(8.0, 4), 1e-9);
    }

    @Test
    void usesTheHoleOfTheTaughtRowAndReportsTapeShiftAlongY() {
        Location nominal = loc(103.5, 50.0); // taught hole, row at X=103.5
        // visible holes: one in the taught row 0.18 mm further along, one a pitch ahead, one from the other edge
        java.util.List<Location> holes = Arrays.asList(loc(103.5, 54.18), loc(103.45, 50.18), loc(96.0, 50.0));
        Double r = OrionHoleVision.residual(holes, nominal, Axis.Y, 1.0);
        assertNotNull(r);
        assertEquals(0.18, r, 1e-9);
    }

    @Test
    void worksAlongXToo() {
        Double r = OrionHoleVision.residual(Arrays.asList(loc(20.1, 7.0)), loc(16.0, 7.0), Axis.X, 1.0);
        assertEquals(0.1, r, 1e-9);
    }

    @Test
    void noHoleInTheRowGivesNull() {
        assertNull(OrionHoleVision.residual(Arrays.asList(loc(96.0, 50.0)), loc(103.5, 50.0), Axis.Y, 1.0));
        assertNull(OrionHoleVision.residual(Arrays.asList(), loc(103.5, 50.0), Axis.Y, 1.0));
    }
}
