package org.openpnp.machine.orion.sheets;

import java.awt.FlowLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;

/**
 * Compact version of the Bus Manager for the small area under the feeder list: connection status,
 * units per rail, and the buttons that matter. The full manager lives in its own resizable window.
 */
public class OrionBusManagerLauncher extends JPanel implements OrionManager.Listener {
    private final OrionManager mgr = OrionManager.get();
    private final JLabel connection = new JLabel();
    private final JLabel rails = new JLabel();

    public OrionBusManagerLauncher() {
        setLayout(new java.awt.BorderLayout());
        JPanel box = OrionUi.grid();
        box.setBorder(BorderFactory.createTitledBorder("Orion bus"));
        OrionUi.row(box, 0, "Connection", connection);
        OrionUi.row(box, 1, "Rails", rails);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton open = new JButton("Open Bus Manager window...");
        open.setToolTipText("Interface settings, per-rail unit tables, vision scan, fiber / tape identification");
        open.addActionListener(e -> OrionBusManagerDialog.open());
        buttons.add(open);
        JButton setup = new JButton("Connect, scan, create all feeders");
        setup.addActionListener(e -> OrionUi.run("Connect, scan and create feeders",
                () -> mgr.bootstrap(),
                msg -> JOptionPane.showMessageDialog(MainFrame.get(), msg
                        + "\n\nAssign a part to each new feeder.", "Orion", JOptionPane.INFORMATION_MESSAGE)));
        buttons.add(setup);
        JButton disconnect = new JButton("Disconnect");
        disconnect.addActionListener(e -> mgr.disconnect());
        buttons.add(disconnect);
        OrionUi.row(box, 2, "", buttons);
        add(box, java.awt.BorderLayout.NORTH);

        mgr.addListener(this);
        refresh();
    }

    private void refresh() {
        connection.setText(mgr.isConnected() ? mgr.describeConnection() : "not connected"
                + (mgr.getLastError().isEmpty() ? "" : "  (" + mgr.getLastError() + ")"));
        StringBuilder sb = new StringBuilder();
        for (OrionBus b : mgr.getBuses()) {
            List<OrionDeviceInfo> devs = b.getDevices();
            int online = 0;
            for (OrionDeviceInfo d : devs) {
                if (d.state == OrionDeviceInfo.State.ONLINE) {
                    online++;
                }
            }
            String problem = mgr.railCheckProblem(b.getRail());
            sb.append(String.format("Rail %d: %d unit(s), %d online, %s   ", b.getRail() + 1, devs.size(),
                    online, problem == null ? "verified by vision" : "NOT verified by vision"));
        }
        rails.setText(sb.length() == 0 ? "-" : sb.toString());
    }

    @Override
    public void orionChanged() {
        OrionUi.onEdt(this::refresh);
    }

    @Override
    public void removeNotify() {
        mgr.removeListener(this);
        super.removeNotify();
    }
}
