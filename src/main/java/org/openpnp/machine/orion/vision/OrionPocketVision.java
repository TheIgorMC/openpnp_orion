package org.openpnp.machine.orion.vision;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.RotatedRect;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;

/** Finds the tape pocket (or the part in it) in the camera image and returns its centre in machine coordinates. */
public final class OrionPocketVision {
    private OrionPocketVision() {}

    /** One detected rectangle, size sorted so that width <= length, in mm. */
    public static class Candidate {
        public final Location center;
        public final double widthMm;
        public final double lengthMm;

        public Candidate(Location center, double widthMm, double lengthMm) {
            this.center = center.convertToUnits(LengthUnit.Millimeters);
            this.widthMm = Math.min(widthMm, lengthMm);
            this.lengthMm = Math.max(widthMm, lengthMm);
        }
    }

    /**
     * Keep the rectangles whose sides match the expected pocket within tolFraction, return the one nearest
     * to {@code near}. Sizes of 0 disable the size filter.
     */
    public static Candidate select(List<Candidate> candidates, double widthMm, double lengthMm, double tolFraction,
            Location near) {
        double w = Math.min(widthMm, lengthMm);
        double l = Math.max(widthMm, lengthMm);
        Location n = near.convertToUnits(LengthUnit.Millimeters);
        Candidate best = null;
        double bestDist = Double.MAX_VALUE;
        for (Candidate c : candidates) {
            if (w > 0 && l > 0) {
                if (Math.abs(c.widthMm - w) > tolFraction * w || Math.abs(c.lengthMm - l) > tolFraction * l) {
                    continue;
                }
            }
            double d = Math.hypot(c.center.getX() - n.getX(), c.center.getY() - n.getY());
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    /** "0.52x1.01, 0.30x0.30" for messages: what the pipeline actually saw (at most six). */
    public static String describe(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size() && i < 6; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%.2fx%.2f", candidates.get(i).widthMm, candidates.get(i).lengthMm));
        }
        return sb.length() == 0 ? "none" : sb.toString() + " mm";
    }

    /** Run the pocket pipeline on the camera; its "results" stage (or last stage) must give rotated rectangles. */
    public static List<Candidate> findPockets(CvPipeline template, Camera camera) throws Exception {
        List<Candidate> out = new ArrayList<>();
        try (CvPipeline pipeline = template.clone()) {
            pipeline.setProperty("camera", camera);
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
                Location o = VisionUtils.getPixelCenterOffsets(camera, 0, 0);
                for (Object r : (List<?>) model) {
                    if (r instanceof RotatedRect) {
                        RotatedRect rr = (RotatedRect) r;
                        Location c = VisionUtils.getPixelLocation(camera, rr.center.x, rr.center.y);
                        double w = VisionUtils.getPixelCenterOffsets(camera, rr.size.width, 0)
                                .getLinearLengthTo(o).convertToUnits(LengthUnit.Millimeters).getValue();
                        double h = VisionUtils.getPixelCenterOffsets(camera, 0, rr.size.height)
                                .getLinearLengthTo(o).convertToUnits(LengthUnit.Millimeters).getValue();
                        out.add(new Candidate(c, w, h));
                    }
                }
            }
        }
        return out;
    }
}
