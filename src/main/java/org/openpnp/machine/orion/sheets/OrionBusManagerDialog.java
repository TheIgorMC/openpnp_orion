package org.openpnp.machine.orion.sheets;

import javax.swing.JDialog;

import org.openpnp.gui.MainFrame;

/** The Bus Manager and Debug tabs in a window of their own, reachable without any Orion feeder. */
public class OrionBusManagerDialog extends JDialog {
    private static OrionBusManagerDialog instance;
    private final OrionBusManagerPanel panel;

    private OrionBusManagerDialog() {
        super(MainFrame.get(), "Orion Feeder Bus", false);
        panel = new OrionBusManagerPanel();
        setContentPane(panel);
        setSize(1280, 800);
        setMinimumSize(new java.awt.Dimension(900, 560));
        setLocationRelativeTo(MainFrame.get());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                instance = null;
            }
        });
    }

    /** Open the window on the log / raw command tab. */
    public static void openDebug() {
        open();
        OrionUi.onEdt(() -> {
            synchronized (OrionBusManagerDialog.class) {
                if (instance != null) {
                    instance.panel.showLog();
                }
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
