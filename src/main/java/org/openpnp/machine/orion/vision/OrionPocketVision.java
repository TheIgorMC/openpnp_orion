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

    /** One line per rectangle: centre in machine coordinates and size, for the "what did it see" report. */
    public static String describeWithCentres(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size() && i < 6; i++) {
            Candidate c = candidates.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(String.format("centre X=%.3f Y=%.3f size %.2fx%.2f", c.center.getX(), c.center.getY(),
                    c.widthMm, c.lengthMm));
        }
        return sb.length() == 0 ? "none" : sb.toString();
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

    private static volatile long lightOnAt = 0;

    /**
     * Switch the camera light on now (call it before the camera starts moving, so the light has settled when
     * it arrives). {@link #find} then only waits for what is left of {@link #LIGHT_DELAY_MS}.
     */
    public static void lightOn(Camera camera) throws Exception {
        if (camera.getLightActuator() != null) {
            camera.actuateLightBeforeCapture(null);
            lightOnAt = System.nanoTime();
        }
    }

    /** Pause between switching the camera light on and the first capture. */
    public static volatile int LIGHT_DELAY_MS = 200;

    /** What a pipeline run produced: the candidates, and a note of what each stage returned (for diagnosis). */
    public static class Found {
        public final List<Candidate> candidates = new ArrayList<>();
        public String diagnostics = "";
    }

    /**
     * Collect rectangle-like things from a stage result, as {centreX, centreY, width, height} in pixels. Accepts
     * rotated rectangles, plain rectangles, contours (their minimum-area rectangle), circles, and lists or
     * single objects of those.
     */
    public static void extract(Object model, List<double[]> out) {
        if (model == null) {
            return;
        }
        if (model instanceof List) {
            for (Object o : (List<?>) model) {
                extract(o, out);
            }
        } else if (model instanceof RotatedRect) {
            RotatedRect r = (RotatedRect) model;
            out.add(new double[] {r.center.x, r.center.y, r.size.width, r.size.height});
        } else if (model instanceof org.opencv.core.Rect) {
            org.opencv.core.Rect r = (org.opencv.core.Rect) model;
            out.add(new double[] {r.x + r.width / 2.0, r.y + r.height / 2.0, r.width, r.height});
        } else if (model instanceof org.opencv.core.MatOfPoint) {
            org.opencv.core.Point[] pts = ((org.opencv.core.MatOfPoint) model).toArray();
            if (pts.length >= 3) {
                RotatedRect r = org.opencv.imgproc.Imgproc.minAreaRect(new org.opencv.core.MatOfPoint2f(pts));
                out.add(new double[] {r.center.x, r.center.y, r.size.width, r.size.height});
            }
        } else if (model instanceof CvStage.Result.Circle) {
            CvStage.Result.Circle c = (CvStage.Result.Circle) model;
            out.add(new double[] {c.x, c.y, c.diameter, c.diameter});
        }
    }

    /**
     * Run the pocket pipeline on the camera. Rectangles are taken from the stage named "results" if it gave any,
     * otherwise from the last stage that did, so a pipeline that was edited still works.
     */
    public static Found find(CvPipeline template, Camera camera) throws Exception {
        return find(template, camera, 0, 0, 0);
    }

    /** The feeder's pocket geometry, handed to the pocket pipeline. Sizes in mm, 0 = unknown. */
    public static class Params {
        public double widthMm, lengthMm;
        /** Size tolerance as a fraction (0.35 = 35 %). */
        public double tolerance;
        /** Crop window: across the tape and along it (so that it holds this pocket but not the neighbours). 0 = none. */
        public double cropAcrossMm, cropAlongMm;
        public boolean tapeAlongY = true;

        public Params() {}

        public Params(double widthMm, double lengthMm, double tolerance) {
            this.widthMm = widthMm;
            this.lengthMm = lengthMm;
            this.tolerance = tolerance;
        }

        /**
         * Crop window for a pocket: the pocket plus what it may be off by on each side, but along the tape never
         * reaching the neighbouring pockets (pitch away).
         */
        public static Params forFeeder(double widthMm, double lengthMm, double tolerance, int pitchMm,
                double maxShiftMm, boolean tapeAlongY) {
            Params p = new Params(widthMm, lengthMm, tolerance);
            p.tapeAlongY = tapeAlongY;
            if (widthMm > 0 && lengthMm > 0) {
                double allow = pitchMm > 0 ? Math.min(maxShiftMm, 0.45 * pitchMm) : maxShiftMm;
                p.cropAcrossMm = widthMm + 2 * allow;
                double half = lengthMm / 2 + allow;
                if (pitchMm > 0) {
                    half = Math.max(lengthMm / 2 + 0.1, Math.min(half, pitchMm - lengthMm / 2 - 0.05));
                }
                p.cropAlongMm = 2 * half;
            }
            return p;
        }
    }

    /**
     * Pipeline parameters from the feeder's pocket geometry, given to the pipeline as properties:
     * <pre>
     * pocket.widthMm, pocket.lengthMm, pocket.tolerance       as set on the feeder
     * pocket.widthPx, pocket.lengthPx                          the same at the camera's scale
     * pocket.minAreaPx, pocket.maxAreaPx                       area range of a pocket (with tolerance), px^2
     * pocket.cropAcrossMm / cropAlongMm (and ...Px)            crop window around this pocket
     * </pre>
     * and applied: every FilterContours stage gets its min / max area overwritten with that range, and every
     * MaskRectangle stage (the crop) gets its width / height from the crop window, so the pipeline only has to be
     * tuned for the picture, not for the size. Returns {minAreaPx, maxAreaPx}, or null if no size is set.
     */
    public static double[] applySizeParameters(CvPipeline pipeline, Params g, double uppX, double uppY) {
        if (g.widthMm <= 0 || g.lengthMm <= 0 || uppX <= 0 || uppY <= 0) {
            return null;
        }
        double tol = g.tolerance;
        double wPx = g.widthMm / uppX, lPx = g.lengthMm / uppY;
        double minA = g.widthMm * (1 - tol) * g.lengthMm * (1 - tol) / (uppX * uppY);
        double maxA = g.widthMm * (1 + tol) * g.lengthMm * (1 + tol) / (uppX * uppY);
        pipeline.setProperty("pocket.widthMm", g.widthMm);
        pipeline.setProperty("pocket.lengthMm", g.lengthMm);
        pipeline.setProperty("pocket.tolerance", tol);
        pipeline.setProperty("pocket.widthPx", wPx);
        pipeline.setProperty("pocket.lengthPx", lPx);
        pipeline.setProperty("pocket.minAreaPx", minA);
        pipeline.setProperty("pocket.maxAreaPx", maxA);
        // Image X is machine X: across the tape when the tape runs along Y.
        double cropXmm = g.tapeAlongY ? g.cropAcrossMm : g.cropAlongMm;
        double cropYmm = g.tapeAlongY ? g.cropAlongMm : g.cropAcrossMm;
        int cropWPx = (int) Math.round(cropXmm / uppX), cropHPx = (int) Math.round(cropYmm / uppY);
        pipeline.setProperty("pocket.cropAcrossMm", g.cropAcrossMm);
        pipeline.setProperty("pocket.cropAlongMm", g.cropAlongMm);
        pipeline.setProperty("pocket.cropWidthPx", cropWPx);
        pipeline.setProperty("pocket.cropHeightPx", cropHPx);
        for (CvStage st : pipeline.getStages()) {
            if (st instanceof org.openpnp.vision.pipeline.stages.FilterContours) {
                ((org.openpnp.vision.pipeline.stages.FilterContours) st).setMinArea(minA);
                ((org.openpnp.vision.pipeline.stages.FilterContours) st).setMaxArea(maxA);
            } else if (st instanceof org.openpnp.vision.pipeline.stages.MaskRectangle && cropWPx > 0 && cropHPx > 0) {
                ((org.openpnp.vision.pipeline.stages.MaskRectangle) st).setWidth(cropWPx);
                ((org.openpnp.vision.pipeline.stages.MaskRectangle) st).setHeight(cropHPx);
            }
        }
        return new double[] {minA, maxA};
    }

    public static double[] applySizeParameters(CvPipeline pipeline, double widthMm, double lengthMm, double tol,
            double uppX, double uppY) {
        return applySizeParameters(pipeline, new Params(widthMm, lengthMm, tol), uppX, uppY);
    }

    public static Found find(CvPipeline template, Camera camera, double widthMm, double lengthMm, double tol)
            throws Exception {
        return find(template, camera, new Params(widthMm, lengthMm, tol));
    }

    public static Found find(CvPipeline template, Camera camera, Params geometry) throws Exception {
        Found found = new Found();
        // Light on (usually already switched on before the camera moved) and given time to settle, so the
        // first frame is not overexposed.
        if (camera.getLightActuator() != null && LIGHT_DELAY_MS > 0) {
            if (System.nanoTime() - lightOnAt > 10_000_000_000L) {
                lightOn(camera);
            }
            long wait = LIGHT_DELAY_MS - (System.nanoTime() - lightOnAt) / 1_000_000L;
            if (wait > 0) {
                Thread.sleep(wait);
            }
        }
        try (CvPipeline pipeline = template.clone()) {
            pipeline.setProperty("camera", camera);
            Location upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
            double[] area = applySizeParameters(pipeline, geometry, upp.getX(), upp.getY());
            pipeline.process();
            List<double[]> px = new ArrayList<>();
            StringBuilder diag = new StringBuilder();
            CvStage named = pipeline.getStage("results");
            List<CvStage> order = new ArrayList<>();
            if (named != null) {
                order.add(named);
            }
            List<CvStage> stages = pipeline.getStages();
            for (int i = stages.size() - 1; i >= 0; i--) {
                if (stages.get(i) != named) {
                    order.add(stages.get(i));
                }
            }
            String used = null;
            for (CvStage st : order) {
                CvStage.Result r = pipeline.getResult(st);
                Object m = r == null ? null : r.model;
                List<double[]> here = new ArrayList<>();
                extract(m, here);
                if (m instanceof Exception) {
                    diag.append(String.format("%s: ERROR %s  ", st.getName(), ((Exception) m).getMessage()));
                } else if (m != null) {
                    diag.append(String.format("%s: %s (%d rect)  ", st.getName(), m.getClass().getSimpleName(),
                            here.size()));
                } else if (!st.isEnabled()) {
                    diag.append(String.format("%s: disabled  ", st.getName()));
                }
                if (px.isEmpty() && !here.isEmpty()) {
                    px = here;
                    used = st.getName();
                }
            }
            found.diagnostics = (used == null ? "no stage returned a rectangle. Stages: " + diag : "from stage " + used)
                    + (area == null ? "" : String.format(" (area filter %.0f..%.0f px)", area[0], area[1]));
            Location o = VisionUtils.getPixelCenterOffsets(camera, 0, 0);
            for (double[] r : px) {
                Location c = VisionUtils.getPixelLocation(camera, r[0], r[1]);
                double w = VisionUtils.getPixelCenterOffsets(camera, r[2], 0).getLinearLengthTo(o)
                        .convertToUnits(LengthUnit.Millimeters).getValue();
                double h = VisionUtils.getPixelCenterOffsets(camera, 0, r[3]).getLinearLengthTo(o)
                        .convertToUnits(LengthUnit.Millimeters).getValue();
                found.candidates.add(new Candidate(c, w, h));
            }
        }
        return found;
    }

    /** Candidates only. */
    public static List<Candidate> findPockets(CvPipeline template, Camera camera) throws Exception {
        return find(template, camera).candidates;
    }
}
