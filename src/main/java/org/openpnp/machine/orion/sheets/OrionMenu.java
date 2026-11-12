package org.openpnp.machine.orion.sheets;

import java.awt.event.KeyEvent;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;

/**
 * The "Orion" menu of the main window. The top line is a live status read-out (connection light and how
 * many feeders answer); below it everything is grouped by what you want to do: look, set up, maintain.
 */
public final class OrionMenu {
    private OrionMenu() {}

    public static JMenu create() {
        // Before anything touches jSerialComm: point it at a ready native library (see the method).
        org.openpnp.machine.orion.protocol.JSerialCommChannel.prepareNativeLibrary();
        OrionToast.install();
        final OrionManager mgr = OrionManager.get();
        JMenu menu = new JMenu("Orion");
        menu.setMnemonic(KeyEvent.VK_O);

        JMenuItem status = new JMenuItem();
        status.setEnabled(false);
        menu.add(status);
        JMenuItem monitorLine = new JMenuItem();
        monitorLine.setEnabled(false);
        menu.add(monitorLine);
        menu.addSeparator();

        JMenuItem manager = new JMenuItem("Feeder bus manager...");
        manager.setMnemonic(KeyEvent.VK_M);
        manager.setToolTipText("All feeders at a glance, details, scan, settings");
        manager.addActionListener(e -> OrionBusManagerDialog.open());
        menu.add(manager);
        JMenuItem debug = new JMenuItem("Bus log and raw commands...");
        debug.setToolTipText("Traffic log, probes, send a raw command");
        debug.addActionListener(e -> OrionBusManagerDialog.openDebug());
        menu.add(debug);
        menu.addSeparator();

        JMenuItem setup = new JMenuItem("Connect and set up all feeders");
        setup.setMnemonic(KeyEvent.VK_C);
        setup.setToolTipText("One step: connects, scans every rail and creates one OpenPnP feeder per unit "
                + "that has none (assign parts afterwards)");
        setup.addActionListener(e -> OrionUi.run("Connect, scan and create feeders",
                () -> mgr.bootstrap(),
                msg -> JOptionPane.showMessageDialog(MainFrame.get(), msg
                        + "\n\nAssign a part to each new feeder in the Feeders tab.", "Orion",
                        JOptionPane.INFORMATION_MESSAGE)));
        menu.add(setup);
        JMenuItem scan = new JMenuItem("Scan all rails now");
        scan.setMnemonic(KeyEvent.VK_S);
        scan.setToolTipText("Probe every address and address new feeders");
        scan.addActionListener(e -> OrionUi.run("Scan all rails", () -> {
            mgr.connectIfNeeded();
            for (OrionBus b : mgr.getBuses()) {
                b.scan(null);
            }
            mgr.refresh();
        }));
        menu.add(scan);
        JMenuItem cycle = new JMenuItem("Setup cycle: verify rails and set pick positions...");
        cycle.setMnemonic(KeyEvent.VK_U);
        cycle.setToolTipText("Moves the camera and the tapes. Per feeder: fiducial, fiber, pocket to the pick window. "
                + "Machine must be homed.");
        cycle.addActionListener(e -> OrionSetupUi.start(null, null));
        menu.add(cycle);
        JMenuItem verify = new JMenuItem("Verify all rails by vision (positions only)");
        verify.setMnemonic(KeyEvent.VK_V);
        verify.setToolTipText("Moves the camera. Machine must be homed.");
        verify.addActionListener(e -> OrionUi.run("Verify all rails",
                () -> OrionManager.get().verifyAllRails(null),
                lines -> JOptionPane.showMessageDialog(MainFrame.get(), String.join("\n", lines),
                        "Orion: vision verify", JOptionPane.INFORMATION_MESSAGE)));
        menu.add(verify);
        menu.addSeparator();

        JCheckBoxMenuItem monitor = new JCheckBoxMenuItem("Watch the bus (ping feeders, find new ones)");
        monitor.setToolTipText("Pings every feeder every few seconds while no job runs; announces new feeders, "
                + "marks removed ones and recovers a hung bus");
        monitor.addActionListener(e -> mgr.getSettings().setMonitorEnabled(monitor.isSelected()));
        menu.add(monitor);
        JMenu recover = new JMenu("Recover the bus");
        JMenuItem soft = new JMenuItem("Reset bus (reopen the port)");
        soft.addActionListener(e -> OrionUi.run("Reset bus", () -> {
            if (!mgr.resetBus(false, "requested from the menu")) {
                throw new Exception("The port could not be reopened: " + mgr.getLastError());
            }
            for (OrionBus b : mgr.getBuses()) {
                b.scan(null);
            }
            mgr.refresh();
        }));
        JMenuItem hard = new JMenuItem("Hard reset (power-cycle the rails)...");
        hard.setToolTipText("Host board only. Feeders restart and get their old addresses back.");
        hard.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(MainFrame.get(), "Power-cycle the feeder rails? Feeders restart "
                    + "(about 3 s).", "Hard reset", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                OrionUi.run("Hard reset", () -> {
                    if (!mgr.resetBus(true, "requested from the menu")) {
                        throw new Exception("The port could not be reopened: " + mgr.getLastError());
                    }
                    for (OrionBus b : mgr.getBuses()) {
                        b.scan(null);
                    }
                    mgr.refresh();
                });
            }
        });
        recover.add(soft);
        recover.add(hard);
        menu.add(recover);
        JMenuItem disconnect = new JMenuItem("Disconnect");
        disconnect.addActionListener(e -> mgr.disconnect());
        menu.add(disconnect);

        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                boolean connected = mgr.isConnected();
                int total = 0;
                int online = 0;
                for (OrionBus b : mgr.getBuses()) {
                    for (OrionDeviceInfo d : b.getDevices()) {
                        total++;
                        if (d.state == OrionDeviceInfo.State.ONLINE) {
                            online++;
                        }
                    }
                }
                status.setIcon(new OrionLed(!connected ? OrionLed.RED
                        : (online < total ? OrionLed.AMBER : OrionLed.GREEN), 10));
                status.setText(connected ? online + " of " + total + " feeder(s) answering, " + mgr.getRailCount()
                        + " rail(s)" : "Not connected");
                monitorLine.setText(connected ? "Monitor: " + mgr.getMonitor().getStatus() : "Monitor: idle");
                monitor.setSelected(mgr.getSettings().isMonitorEnabled());
                recover.setEnabled(connected);
                disconnect.setEnabled(connected);
                scan.setEnabled(true);
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        return menu;
    }
}
