package org.openpnp.machine.orion;

import java.util.List;

/**
 * Plausibility checks for the board on the conveyor: does the measured length and the set width fit the board the job
 * expects, and do placements lie in the edge strip the guides and clamp cover. Pure arithmetic.
 */
public final class OrionBoardCheck {
    private OrionBoardCheck() {}

    public static class Result {
        public boolean ok = true;
        public String message = "";
    }

    /**
     * The expected board has two sides a and b (in either order: it can be inserted either way round). The belt length
     * must match one of them, the set width must match the other plus the clearance of the guides. A value of 0 or less
     * is not checked.
     *
     * @param lengthMm measured board length (NaN = not measured)
     * @param widthMm  the conveyor's width setting (NaN = unknown)
     */
    public static Result checkSize(double lengthMm, double widthMm, double a, double b, double tolMm, double clearanceMm) {
        Result r = new Result();
        if (a <= 0 || b <= 0) {
            r.message = "no board size to compare with";
            return r;
        }
        boolean haveLen = !Double.isNaN(lengthMm);
        boolean haveWid = !Double.isNaN(widthMm);
        // the two ways round
        double e1 = (haveLen ? Math.abs(lengthMm - a) : 0) + (haveWid ? Math.abs(widthMm - (b + clearanceMm)) : 0);
        double e2 = (haveLen ? Math.abs(lengthMm - b) : 0) + (haveWid ? Math.abs(widthMm - (a + clearanceMm)) : 0);
        boolean lenAlongA = e1 <= e2;
        double expLen = lenAlongA ? a : b;
        double expWid = (lenAlongA ? b : a) + clearanceMm;
        StringBuilder sb = new StringBuilder();
        if (haveLen && Math.abs(lengthMm - expLen) > tolMm) {
            r.ok = false;
            sb.append(String.format("The board is %.1f mm long but the job expects about %.1f mm. ", lengthMm, expLen));
        }
        if (haveWid && Math.abs(widthMm - expWid) > tolMm) {
            r.ok = false;
            sb.append(String.format("The width is set to %.1f mm but a %.1f mm board needs about %.1f mm. ", widthMm,
                    expWid - clearanceMm, expWid));
        }
        r.message = r.ok ? String.format("Board %.1f x %.1f mm matches the job", expLen, expWid - clearanceMm)
                : sb.toString().trim();
        return r;
    }

    /**
     * How many placements lie inside the edge strip (closer than edgeMm to a side of the board). Only meaningful when the
     * board origin is at a corner: if any placement is further than a tolerance outside 0..dim the origin is somewhere
     * else and -1 is returned (cannot tell).
     *
     * @param xy placements relative to the board origin, mm
     */
    public static int placementsInEdge(List<double[]> xy, double dimX, double dimY, double edgeMm) {
        if (edgeMm <= 0 || dimX <= 0 || dimY <= 0) {
            return 0;
        }
        double slack = 1.0;
        for (double[] p : xy) {
            if (p[0] < -slack || p[0] > dimX + slack || p[1] < -slack || p[1] > dimY + slack) {
                return -1;
            }
        }
        int n = 0;
        for (double[] p : xy) {
            if (p[0] < edgeMm || p[0] > dimX - edgeMm || p[1] < edgeMm || p[1] > dimY - edgeMm) {
                n++;
            }
        }
        return n;
    }
}
