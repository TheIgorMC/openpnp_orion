package org.openpnp.machine.orion.vision;

import org.openpnp.machine.reference.vision.ReferenceFiducialLocator;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.FiducialLocator;
import org.pmw.tinylog.Logger;

/** {@link OrionRailScanner.FiducialFinder} on the machine's fiducial locator and head camera. */
public class OrionVisionFinder implements OrionRailScanner.FiducialFinder {
    private final Part part;

    public OrionVisionFinder(Part part) {
        this.part = part;
    }

    /** Extra tries when the locator sees nothing: the first picture after a move can be unsettled or unlit. */
    public static volatile int EXTRA_TRIES = 2;
    private int extraTries = 0;

    private volatile String lastProblem = "";

    @Override
    public String lastProblem() {
        return lastProblem;
    }

    @Override
    public Location findExpected(Location nominal) throws Exception {
        extraTries = EXTRA_TRIES;
        try {
            return find(nominal);
        } finally {
            extraTries = 0;
        }
    }

    @Override
    public Location find(Location nominal) throws Exception {
        FiducialLocator locator = Configuration.get().getMachine().getFiducialLocator();
        lastProblem = "";
        for (int attempt = 0; attempt <= extraTries; attempt++) {
            try {
                if (locator instanceof ReferenceFiducialLocator) {
                    return ((ReferenceFiducialLocator) locator).getFiducialLocation(nominal, part);
                }
                return locator.getHomeFiducialLocation(nominal, part);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // The locator throws when it cannot see the fiducial. That is the normal "nothing here".
                lastProblem = String.valueOf(e.getMessage());
                Logger.debug("Orion: no fiducial near {} (try {}): {}", nominal, attempt + 1, lastProblem);
                if (attempt < extraTries) {
                    Thread.sleep(300);
                }
            }
        }
        return null;
    }
}
