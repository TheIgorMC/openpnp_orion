package org.openpnp.machine.orion.vision;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Point;

public class OrionPipelinesTest {
    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    void defaultPipelinesParse() {
        assertEquals(4, OrionPipelines.defaultFiberPipeline().getStages().size());
        assertEquals(4, OrionPipelines.defaultMovementPipeline().getStages().size());
    }

    @Test
    void peakSeesAFiberSpotAndChangedPixelsSeesMovement() {
        Mat dark = Mat.zeros(100, 100, CvType.CV_8UC1);
        Mat lit = Mat.zeros(100, 100, CvType.CV_8UC1);
        Imgproc.circle(lit, new Point(50, 50), 6, new Scalar(220), -1);
        assertTrue(OrionPipelines.peak(lit) - OrionPipelines.peak(dark) > 200);
        assertTrue(OrionPipelines.changedPixels(dark, lit) > 100);
        assertEquals(0, OrionPipelines.changedPixels(dark, dark.clone()), 0);
    }

    @Test
    void circleDetectionResultCountsAsLitWhenACircleIsFound() {
        java.util.List<org.openpnp.vision.pipeline.CvStage.Result.Circle> none = new java.util.ArrayList<>();
        java.util.List<org.openpnp.vision.pipeline.CvStage.Result.Circle> one = new java.util.ArrayList<>();
        one.add(new org.openpnp.vision.pipeline.CvStage.Result.Circle(10, 12, 40));
        assertEquals(0, OrionPipelines.circleCount(none));
        assertEquals(1, OrionPipelines.circleCount(one));
        assertEquals(-1, OrionPipelines.circleCount("not a circle list"));
        assertEquals(-1, OrionPipelines.circleCount(java.util.Arrays.asList("x", "y")));
    }
}
