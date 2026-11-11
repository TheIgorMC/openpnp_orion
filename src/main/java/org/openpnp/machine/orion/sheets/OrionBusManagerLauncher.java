package org.openpnp.machine.orion.sheets;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;

/**
 * Compact version of the Bus Manager for the small area under the feeder list: connection light, a row of
 * status lights per rail (one per feeder) and the two buttons that matter. The full manager lives in its own
 * resizable window.
 */
public class OrionBusManagerLauncher extends JPanel implements OrionManager.Listener {
    private final OrionManager mgr = OrionManager.get();
    private final JLabel connection = new JLabel();
    private final JLabel summary = new JLabel();
    private final JPanel railBox = new JPanel();

    public OrionBusManagerLauncher() {
        setLayout(new BorderLayout());
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Orion feeder bus"),
                BorderFactory.createEmptyBorder(2, 6, 6, 6)));

        connection.setFont(connection.getFont().deriveFont(java.awt.Font.BOLD));
        connection.setIconTextGap(8);
        connection.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(connection);
        box.add(summary);
        box.add(javax.swing.Box.createVerticalStrut(6));
        railBox.setLayout(new BoxLayout(railBox, BoxLayout.Y_AXIS));
        railBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(railBox);
        box.add(javax.swing.Box.createVerticalStrut(6));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton open = new JButton("Open feeder bus manager...");
        open.setToolTipText("All feeders at a glance, details, scan, settings, bus log");
        open.addActionListener(e -> OrionBusManagerDialog.open());
        buttons.add(open);
        JButton setup = new JButton("Set up all feeders");
        setup.setToolTipText("Connect, scan every rail and create an OpenPnP feeder for each unit that has none");
        setup.addActionListener(e -> OrionUi.run("Connect, scan and create feeders",
                () -> mgr.bootstrap(),
                msg -> JOptionPane.showMessageDialog(MainFrame.get(), msg
                        + "\n\nAssign a part to each new feeder.", "Orion", JOptionPane.INFORMATION_MESSAGE)));
        buttons.add(setup);
        box.add(buttons);
        add(box, BorderLayout.NORTH);

        mgr.addListener(this);
        refresh();
    }

    private void refresh() {
        boolean connected = mgr.isConnected();
        int total = 0;
        int online = 0;
        railBox.removeAll();
        for (OrionBus b : mgr.getBuses()) {
            List<OrionDeviceInfo> devs = b.getDevices();
            JPanel line = new JPanel(new BorderLayout(8, 0));
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel name = new JLabel("Rail " + (b.getRail() + 1));
            name.setPreferredSize(new java.awt.Dimension(48, 16));
            line.add(name, BorderLayout.WEST);
            JPanel dots = new JPanel(new WrapLayout(FlowLayout.LEFT, 1, 1));
            for (OrionDeviceInfo d : devs) {
                total++;
                Color c = d.state == OrionDeviceInfo.State.ONLINE ? OrionLed.GREEN
                        : d.state == OrionDeviceInfo.State.LOST ? OrionLed.RED : OrionLed.AMBER;
                if (d.state == OrionDeviceInfo.State.ONLINE) {
                    online++;
                }
                JLabel dot = new JLabel(new OrionLed(c, 9));
                dot.setToolTipText("#" + d.address + "  " + FeederTile.componentText(d));
                dots.add(dot);
            }
            if (devs.isEmpty()) {
                JLabel none = new JLabel("no feeders");
                none.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
                dots.add(none);
            }
            line.add(dots, BorderLayout.CENTER);
            boolean ok = mgr.railCheckProblem(b.getRail()) == null;
            JLabel v = new JLabel(ok ? "verified" : "not verified");
            v.setIcon(new OrionLed(ok ? OrionLed.GREEN : OrionLed.AMBER, 7));
            v.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
            line.add(v, BorderLayout.EAST);
            railBox.add(line);
        }
        connection.setIcon(new OrionLed(!connected ? OrionLed.RED : (online < total ? OrionLed.AMBER : OrionLed.GREEN), 11));
        connection.setText(connected ? mgr.describeConnection() : "Not connected"
                + (mgr.getLastError().isEmpty() ? "" : " (" + mgr.getLastError() + ")"));
        summary.setText(connected ? online + " of " + total + " feeder(s) answering   ·   "
                + mgr.getMonitor().getStatus() : "Press \"Set up all feeders\" or use the Orion menu");
        railBox.revalidate();
        revalidate();
        repaint();
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

    @Override
    public void addNotify() {
        super.addNotify();
        mgr.addListener(this);
        refresh();
    }
}
