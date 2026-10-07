package org.openpnp.machine.orion.sheets;

import javax.swing.JDialog;
import javax.swing.JTabbedPane;

import org.openpnp.gui.MainFrame;

/** The Bus Manager and Debug tabs in a window of their own, reachable without any Orion feeder. */
public class OrionBusManagerDialog extends JDialog {
    private static OrionBusManagerDialog instance;

    private OrionBusManagerDialog() {
        super(MainFrame.get(), "Orion Bus Manager", false);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Bus Manager", new OrionBusManagerPanel());
        tabs.addTab("Debug", new OrionDebugPanel());
        setContentPane(tabs);
        setSize(1150, 760);
        setLocationRelativeTo(MainFrame.get());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                instance = null;
            }
        });
    }

    public static synchronized void open() {
        OrionUi.onEdt(() -> {
            synchronized (OrionBusManagerDialog.class) {
                if (instance == null) {
                    instance = new OrionBusManagerDialog();
                }
                instance.setVisible(true);
                instance.toFront();
            }
        });
    }
}
