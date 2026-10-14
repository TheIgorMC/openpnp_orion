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
        assertEquals(7, p.getStages().size());
        CvPipeline back = new CvPipeline(p.toXmlString());
        assertEquals(7, back.getStages().size());
    }
}
