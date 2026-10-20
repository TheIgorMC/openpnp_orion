package org.openpnp.machine.orion.vision;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.openpnp.machine.orion.vision.OrionPocketVision.Candidate;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.vision.pipeline.CvPipeline;

public class OrionPocketVisionTest {
    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Location loc(double x, double y) {
        return new Location(LengthUnit.Millimeters, x, y, 0, 0);
    }

    @Test
    void selectKeepsThePocketSizedRectangleNearestToThePickSpot() {
        List<Candidate> c = Arrays.asList(
                new Candidate(loc(10.0, 20.0), 0.68, 1.30),   // the pocket
                new Candidate(loc(10.5, 20.1), 1.31, 0.72),   // sides swapped still matches
                new Candidate(loc(10.0, 22.0), 0.2, 0.2),     // dust
                new Candidate(loc(14.0, 20.0), 3.5, 5.0));    // a big feature
        Candidate best = OrionPocketVision.select(c, 0.7, 1.3, 0.35, loc(10.4, 20.0));
        assertNotNull(best);
        assertEquals(10.5, best.center.getX(), 1e-9, "nearest of the two pocket-sized ones");
        assertNull(OrionPocketVision.select(c, 2.0, 3.0, 0.1, loc(10, 20)), "nothing of that size");
        assertNotNull(OrionPocketVision.select(c, 0, 0, 0.1, loc(10, 20)), "size filter off");
    }

    @Test
    void defaultPocketPipelineFindsADarkRectangleOnALightTape() throws Exception {
        // synthetic image: light tape, dark 36 x 20 px pocket centred at (300, 200), a speck, and noise-free
        Mat img = new Mat(480, 640, CvType.CV_8UC3, new Scalar(200, 200, 200));
        Imgproc.rectangle(img, new Point(282, 190), new Point(318, 210), new Scalar(40, 40, 40), -1);
        Imgproc.circle(img, new Point(100, 100), 3, new Scalar(30, 30, 30), -1);
        File f = File.createTempFile("pocket", ".png");
        f.deleteOnExit();
        assertTrue(Imgcodecs.imwrite(f.getAbsolutePath(), img));

        CvPipeline p = OrionPipelines.pocketPipeline("<cv-stage class=\"org.openpnp.vision.pipeline.stages.ImageRead\" "
                + "name=\"capture\" enabled=\"true\" file=\"" + f.getAbsolutePath().replace("\\", "/") + "\"/>");
        p.process();
        Object model = p.getResult("results").model;
        assertTrue(model instanceof List);
        List<?> rects = (List<?>) model;
        assertFalse(rects.isEmpty());
        RotatedRect big = null;
        for (Object o : rects) {
            RotatedRect r = (RotatedRect) o;
            if (big == null || r.size.area() > big.size.area()) {
                big = r;
            }
        }
        assertEquals(300, big.center.x, 2.0);
        assertEquals(200, big.center.y, 2.0);
        assertEquals(36 * 20, big.size.area(), 150);
        p.close();
    }

    @Test
    void pocketPipelineSurvivesAnXmlRoundTrip() throws Exception {
        CvPipeline p = OrionPipelines.defaultPocketPipeline();
        assertEquals(8, p.getStages().size());
        CvPipeline back = new CvPipeline(p.toXmlString());
        assertEquals(8, back.getStages().size());
    }

    @Test
    void extractAcceptsAnyCommonRectangleFormAndUsesTheCentre() {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        // a single rotated rectangle (not in a list)
        OrionPocketVision.extract(new RotatedRect(new Point(100, 50), new org.opencv.core.Size(30, 10), 15), out);
        assertEquals(1, out.size());
        assertArrayEquals(new double[] {100, 50, 30, 10}, out.get(0), 1e-9);

        // a plain Rect: the centre, not the top-left corner
        out.clear();
        OrionPocketVision.extract(new org.opencv.core.Rect(10, 20, 30, 40), out);
        assertArrayEquals(new double[] {25, 40, 30, 40}, out.get(0), 1e-9);

        // contours: their minimum-area rectangle
        out.clear();
        org.opencv.core.MatOfPoint square = new org.opencv.core.MatOfPoint(new Point(0, 0), new Point(40, 0),
                new Point(40, 20), new Point(0, 20));
        OrionPocketVision.extract(Arrays.asList(square, square), out);
        assertEquals(2, out.size());
        assertEquals(20, out.get(0)[0], 0.6);
        assertEquals(10, out.get(0)[1], 0.6);

        // circles, nested lists, and things that are not rectangles
        out.clear();
        OrionPocketVision.extract(Arrays.asList(Arrays.asList(
                new org.openpnp.vision.pipeline.CvStage.Result.Circle(5, 6, 8)), "text", 42), out);
        assertEquals(1, out.size());
        assertArrayEquals(new double[] {5, 6, 8, 8}, out.get(0), 1e-9);
        out.clear();
        OrionPocketVision.extract(null, out);
        assertTrue(out.isEmpty());
    }

    @Test
    void anySizeIsAcceptedWhenSizeIsZeroAndTheNearestWins() {
        List<Candidate> c = Arrays.asList(new Candidate(loc(5, 5), 3, 4), new Candidate(loc(1, 1), 0.2, 0.9));
        assertEquals(1.0, OrionPocketVision.select(c, 0, 0, 0.35, loc(0.8, 1.2)).center.getX(), 1e-9);
    }

    @Test
    void feederPocketSizeBecomesPipelineParameters() throws Exception {
        org.openpnp.vision.pipeline.CvPipeline p = OrionPipelines.defaultPocketPipeline();
        double[] area = OrionPocketVision.applySizeParameters(p, 1.0, 2.0, 0.25, 0.02, 0.02);
        // 1.0 x 2.0 mm at 0.02 mm/px = 50 x 100 px = 5000 px^2, +-25 % on each side
        assertEquals(5000 * 0.75 * 0.75, area[0], 1e-6);
        assertEquals(5000 * 1.25 * 1.25, area[1], 1e-6);
        org.openpnp.vision.pipeline.stages.FilterContours f = null;
        for (org.openpnp.vision.pipeline.CvStage st : p.getStages()) {
            if (st instanceof org.openpnp.vision.pipeline.stages.FilterContours) {
                f = (org.openpnp.vision.pipeline.stages.FilterContours) st;
            }
        }
        assertEquals(area[0], f.getMinArea(), 1e-6);
        assertEquals(area[1], f.getMaxArea(), 1e-6);
        assertEquals(100.0, (Double) p.getProperty("pocket.lengthPx"), 1e-6);
        assertEquals(null, OrionPocketVision.applySizeParameters(p, 0, 0, 0.25, 0.02, 0.02), "size 0 = any: untouched");
    }
}
