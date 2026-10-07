package org.openpnp.machine.orion.sheets;

import javax.swing.JPanel;

import org.openpnp.machine.orion.OrionFeeder;
import org.openpnp.spi.PropertySheetHolder;

public class OrionFeederSheet implements PropertySheetHolder.PropertySheet {
    private final OrionFeeder feeder;

    public OrionFeederSheet(OrionFeeder feeder) {
        this.feeder = feeder;
    }

    @Override
    public String getPropertySheetTitle() {
        return "Orion Feeder";
    }

    @Override
    public JPanel getPropertySheetPanel() {
        return new OrionFeederWizard(feeder);
    }
}
