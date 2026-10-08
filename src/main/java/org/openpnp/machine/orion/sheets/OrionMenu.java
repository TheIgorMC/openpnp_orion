package org.openpnp.machine.orion.sheets;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;

/** The "Orion" menu of the main window: the orchestrator's entry points, no feeder needed. */
public final class OrionMenu {
    private OrionMenu() {}

    public static JMenu create() {
        // Before anything touches jSerialComm: point it at a ready native library (see the method).
        org.openpnp.machine.orion.protocol.JSerialCommChannel.prepareNativeLibrary();
        JMenu menu = new JMenu("Orion");

        JMenuItem manager = new JMenuItem("Bus manager and debug...");
        manager.addActionListener(e -> OrionBusManagerDialog.open());
        menu.add(manager);

        JMenuItem setup = new JMenuItem("Connect, scan and create all feeders");
        setup.setToolTipText("One step: connects, scans every rail and creates one OpenPnP feeder per unit "
                + "that has none (assign parts afterwards)");
        setup.addActionListener(e -> OrionUi.run("Connect, scan and create feeders",
                () -> OrionManager.get().bootstrap(),
                msg -> JOptionPane.showMessageDialog(MainFrame.get(), msg
                        + "\n\nAssign a part to each new feeder in the Feeders tab.", "Orion",
                        JOptionPane.INFORMATION_MESSAGE)));
        menu.add(setup);

        JMenuItem verify = new JMenuItem("Verify all rails by vision");
        verify.setToolTipText("Moves the camera. Machine must be homed.");
        verify.addActionListener(e -> OrionUi.run("Verify all rails",
                () -> OrionManager.get().verifyAllRails(null),
                lines -> JOptionPane.showMessageDialog(MainFrame.get(), String.join("\n", lines),
                        "Orion: vision verify", JOptionPane.INFORMATION_MESSAGE)));
        menu.add(verify);
        return menu;
    }
}
