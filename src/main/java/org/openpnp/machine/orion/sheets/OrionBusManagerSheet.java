package org.openpnp.machine.orion.sheets;

import javax.swing.JPanel;

import org.openpnp.spi.PropertySheetHolder;

public class OrionBusManagerSheet implements PropertySheetHolder.PropertySheet {
    @Override
    public String getPropertySheetTitle() {
        return "Orion Bus Manager";
    }

    @Override
    public JPanel getPropertySheetPanel() {
        return new OrionBusManagerPanel();
    }
}
