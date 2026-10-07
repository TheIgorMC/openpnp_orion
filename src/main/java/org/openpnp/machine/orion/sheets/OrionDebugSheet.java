package org.openpnp.machine.orion.sheets;

import javax.swing.JPanel;

import org.openpnp.spi.PropertySheetHolder;

public class OrionDebugSheet implements PropertySheetHolder.PropertySheet {
    @Override
    public String getPropertySheetTitle() {
        return "Orion Debug";
    }

    @Override
    public JPanel getPropertySheetPanel() {
        return new OrionDebugPanel();
    }
}
