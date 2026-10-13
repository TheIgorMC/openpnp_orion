package org.openpnp.machine.orion.vision;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;

/**
 * Tape position from the sprocket holes (EIA-481: 1.5 mm holes, 4 mm pitch). The holes repeat every 4 mm along
 * the tape, so the shift of the tape since the pick location was taught is measured modulo the hole pitch:
 * the feeder only ever moves the tape by whole half-pitches, which keeps the hole pattern in phase, and its
 * positioning error is far below half a pitch.
 */
public final class OrionHoleVision {
    private OrionHoleVision() {}

    public enum Axis { X, Y }

    public static final double HOLE_PITCH_MM = 4.0;

    /** Shift along the tape, wrapped into -pitch/2 .. +pitch/2 so any hole of the row can be used. */
    public static double wrap(double measuredMinusNominal, double pitch) {
        return measuredMinusNominal - pitch * Math.rint(measuredMinusNominal / pitch);
    }

    /**
     * Pick the hole that belongs to the row of the taught one (similar position across the tape) and return how
     * far the tape moved along its axis, in mm. Null if no hole lies in the row.
     */
    public static Double residual(List<Location> holes, Location nominal, Axis tapeAxis, double lateralTolMm) {
        Location n = nominal.convertToUnits(LengthUnit.Millimeters);
        double nomAlong = tapeAxis == Axis.Y ? n.getY() : n.getX();
        double nomLateral = tapeAxis == Axis.Y ? n.getX() : n.getY();
        Double best = null;
        for (Location h : holes) {
            Location m = h.convertToUnits(LengthUnit.Millimeters);
            double along = tapeAxis == Axis.Y ? m.getY() : m.getX();
            double lateral = tapeAxis == Axis.Y ? m.getX() : m.getY();
            if (Math.abs(lateral - nomLateral) > lateralTolMm) {
                continue;
            }
            double r = wrap(along - nomAlong, HOLE_PITCH_MM);
            if (best == null || Math.abs(r) < Math.abs(best)) {
                best = r;
            }
        }
        return best;
    }

    /** Run the hole pipeline on the camera and return the hole centres found, in machine coordinates. */
    public static List<Location> findHoles(CvPipeline template, Camera camera, double holeDiameterMm,
            double searchRadiusMm) throws Exception {
        List<Location> holes = new ArrayList<>();
        try (CvPipeline pipeline = template.clone()) {
            pipeline.setProperty("camera", camera);
            pipeline.setProperty("sprocketHole.diameter", new Length(holeDiameterMm, LengthUnit.Millimeters));
            pipeline.setProperty("sprocketHole.maxDistance", new Length(searchRadiusMm, LengthUnit.Millimeters));
            pipeline.process();
            Object model = null;
            CvStage.Result named = pipeline.getResult("results");
            if (named != null) {
                model = named.model;
            }
            if (!(model instanceof List)) {
                model = pipeline.getWorkingModel();
            }
            if (model instanceof List) {
                for (Object o : (List<?>) model) {
                    if (o instanceof CvStage.Result.Circle) {
                        CvStage.Result.Circle c = (CvStage.Result.Circle) o;
                        holes.add(VisionUtils.getPixelLocation(camera, c.x, c.y));
                    }
                }
            }
        }
        return holes;
    }
}
