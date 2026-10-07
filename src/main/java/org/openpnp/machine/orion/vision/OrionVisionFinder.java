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

    @Override
    public Location find(Location nominal) throws Exception {
        FiducialLocator locator = Configuration.get().getMachine().getFiducialLocator();
        try {
            if (locator instanceof ReferenceFiducialLocator) {
                return ((ReferenceFiducialLocator) locator).getFiducialLocation(nominal, part);
            }
            return locator.getHomeFiducialLocation(nominal, part);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            // The locator throws when it cannot see the fiducial. That is the normal "nothing here".
            Logger.debug("Orion: no fiducial near {}: {}", nominal, e.getMessage());
            return null;
        }
    }
}
