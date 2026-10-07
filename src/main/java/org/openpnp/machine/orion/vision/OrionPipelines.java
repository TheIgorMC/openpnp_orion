package org.openpnp.machine.orion.vision;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.spi.Camera;

/**
 * Default pipelines and measurements for the fiber-light and tape-movement detection. Both
 * pipelines end in a gray image of the masked spot; tune them in the pipeline editor (mask
 * diameter, blur, threshold stages, ...). The detector reads the pipeline's last stage output.
 */
public final class OrionPipelines {
    private OrionPipelines() {}

    /** Gray level difference counted as "this pixel changed" for the movement check. */
    public static final int PIXEL_CHANGE_LEVELS = 25;

    public static CvPipeline defaultFiberPipeline() {
        try {
            return new CvPipeline(
                    "<cv-pipeline><stages>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ImageCapture\" name=\"capture\" enabled=\"true\" default-light=\"false\" settle-first=\"true\" count=\"1\"/>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.MaskCircle\" name=\"spot\" enabled=\"true\" diameter=\"80\" property-name=\"FiberSpot\"/>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.BlurGaussian\" name=\"blur\" enabled=\"true\" kernel-size=\"5\"/>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ConvertColor\" name=\"gray\" enabled=\"true\" conversion=\"Bgr2Gray\"/>"
                    + "</stages></cv-pipeline>");
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public static CvPipeline defaultMovementPipeline() {
        try {
            return new CvPipeline(
                    "<cv-pipeline><stages>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ImageCapture\" name=\"capture\" enabled=\"true\" default-light=\"true\" settle-first=\"true\" count=\"1\"/>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.MaskCircle\" name=\"hole\" enabled=\"true\" diameter=\"120\" property-name=\"TapeHole\"/>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.BlurGaussian\" name=\"blur\" enabled=\"true\" kernel-size=\"3\"/>"
                    + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ConvertColor\" name=\"gray\" enabled=\"true\" conversion=\"Bgr2Gray\"/>"
                    + "</stages></cv-pipeline>");
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    /** Run a pipeline on the camera and return a gray copy of its final image. */
    public static Mat grab(CvPipeline template, Camera camera) throws Exception {
        try (CvPipeline pipeline = template.clone()) {
            pipeline.setProperty("camera", camera);
            pipeline.process();
            Mat m = pipeline.getWorkingImage();
            if (m == null) {
                throw new Exception("The pipeline produced no image.");
            }
            Mat gray = new Mat();
            if (m.channels() > 1) {
                Imgproc.cvtColor(m, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                m.copyTo(gray);
            }
            return gray;
        }
    }

    /** Brightness of the fiber spot: peak of the masked, blurred gray image. */
    public static double peak(Mat gray) {
        return Core.minMaxLoc(gray).maxVal;
    }

    /** Number of pixels that changed by more than {@link #PIXEL_CHANGE_LEVELS} between two frames. */
    public static double changedPixels(Mat a, Mat b) {
        Mat diff = new Mat();
        Core.absdiff(a, b, diff);
        Mat bin = new Mat();
        Imgproc.threshold(diff, bin, PIXEL_CHANGE_LEVELS, 255, Imgproc.THRESH_BINARY);
        double n = Core.countNonZero(bin);
        diff.release();
        bin.release();
        return n;
    }
}
