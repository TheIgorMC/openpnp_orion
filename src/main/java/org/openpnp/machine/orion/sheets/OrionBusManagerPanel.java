package org.openpnp.machine.orion.sheets;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionFeeder;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.OrionSettings;
import org.openpnp.machine.orion.protocol.JSerialCommChannel;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Feeder;

/**
 * Machine-wide Orion tab: connection settings, then one sub-tab per rail listing every unit in X
 * order with scan / ping / identify / feed / unfeed / peel / unpeel, binding to feeders, and
 * conflict visibility.
 */
public class OrionBusManagerPanel extends JPanel implements OrionManager.Listener {
    private final OrionManager mgr = OrionManager.get();
    private final JLabel statusLabel = new JLabel();
    private final JTabbedPane railTabs = new JTabbedPane();
    private final JProgressBar progress = new JProgressBar();
    private int builtRails = -1;

    public OrionBusManagerPanel() {
        setLayout(new BorderLayout());
        add(buildConnectionPanel(), BorderLayout.NORTH);
        add(railTabs, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.CENTER);
        progress.setStringPainted(true);
        progress.setVisible(false);
        south.add(progress, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
        mgr.addListener(this);
        rebuildRails();
    }

    @Override
    public void removeNotify() {
        mgr.removeListener(this);
        super.removeNotify();
    }

    // ------------------------------------------------------------------ connection

    private JPanel buildConnectionPanel() {
        OrionSettings s = mgr.getSettings();
        JPanel p = OrionUi.grid();
        p.setBorder(BorderFactory.createTitledBorder("Connection"));

        JComboBox<OrionSettings.Mode> mode = new JComboBox<>(OrionSettings.Mode.values());
        mode.setSelectedItem(s.getMode());
        mode.addActionListener(e -> s.setMode((OrionSettings.Mode) mode.getSelectedItem()));
        OrionUi.row(p, 0, "Interface", mode);

        JComboBox<String> adapter = portCombo(s.getAdapterPort(), s::setAdapterPort);
        OrionUi.row(p, 1, "USB-RS485 adapter port (9600 8N1)", adapter);
        JComboBox<String> host = portCombo(s.getHostPort(), s::setHostPort);
        OrionUi.row(p, 2, "Host board port (115200)", host);

        JSpinner maxAddr = new JSpinner(new SpinnerNumberModel(s.getMaxAddress(), 1, 247, 1));
        maxAddr.addChangeListener(e -> {
            s.setMaxAddress((Integer) maxAddr.getValue());
            mgr.applySettingsToBuses();
        });
        OrionUi.row(p, 3, "Highest address to probe", maxAddr);
        JSpinner retries = new JSpinner(new SpinnerNumberModel(s.getRetries(), 0, 10, 1));
        retries.addChangeListener(e -> {
            s.setRetries((Integer) retries.getValue());
            mgr.applySettingsToBuses();
        });
        OrionUi.row(p, 4, "Bus retries per command", retries);
        JCheckBox auto = new JCheckBox("Connect and scan automatically when the machine is enabled",
                s.isConnectOnEnable());
        auto.addActionListener(e -> s.setConnectOnEnable(auto.isSelected()));
        OrionUi.row(p, 5, "", auto);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton connect = new JButton("Connect");
        connect.addActionListener(e -> OrionUi.run("Connect", () -> mgr.connect()));
        JButton disconnect = new JButton("Disconnect");
        disconnect.addActionListener(e -> mgr.disconnect());
        JButton scanAll = new JButton("Scan all rails");
        scanAll.addActionListener(e -> scan(null));
        buttons.add(connect);
        buttons.add(disconnect);
        buttons.add(scanAll);
        OrionUi.row(p, 6, "", buttons);
        return p;
    }

    private JComboBox<String> portCombo(String current, java.util.function.Consumer<String> setter) {
        JComboBox<String> cb = new JComboBox<>(JSerialCommChannel.listPorts());
        cb.setEditable(true);
        cb.setSelectedItem(current);
        cb.addActionListener(e -> {
            Object o = cb.getSelectedItem();
            setter.accept(o == null ? "" : o.toString());
        });
        return cb;
    }

    // ------------------------------------------------------------------ scanning

    /** Scan one rail (or all when rail is null) as a machine task with a progress bar. */
    private void scan(Integer rail) {
        OrionUi.run("Scan", () -> {
            try {
                mgr.connectIfNeeded();
                for (OrionBus b : mgr.getBuses()) {
                    if (rail != null && b.getRail() != rail) {
                        continue;
                    }
                    int added = b.scan((phase, done, total) -> OrionUi.onEdt(() -> {
                        progress.setVisible(true);
                        progress.setMaximum(total);
                        progress.setValue(done);
                        progress.setString("Rail " + (b.getRail() + 1) + ": " + phase);
                    }));
                    mgr.addLog(b.getRail(), org.openpnp.machine.orion.protocol.OrionBusListener.Direction.INFO,
                            "Scan finished, " + added + " new unit(s)");
                }
            } finally {
                OrionUi.onEdt(() -> progress.setVisible(false));
                mgr.refresh();
            }
        });
    }

    // ------------------------------------------------------------------ per-rail tables

    @Override
    public void orionChanged() {
        OrionUi.onEdt(this::rebuildRails);
    }

    private void rebuildRails() {
        statusLabel.setText("  " + mgr.describeConnection()
                + (mgr.getLastError().isEmpty() ? "" : "   Last error: " + mgr.getLastError()));
        int rails = mgr.getRailCount();
        if (rails != builtRails) {
            railTabs.removeAll();
            for (int r = 0; r < rails; r++) {
                railTabs.addTab("Rail " + (r + 1), new RailPanel(r));
            }
            builtRails = rails;
            if (rails == 0) {
                railTabs.addTab("Rails", new JLabel("  Not connected. Choose an interface and press Connect."));
            }
        }
        for (Component c : railTabs.getComponents()) {
            if (c instanceof RailPanel) {
                ((RailPanel) c).refresh();
            }
        }
    }

    /** One row of the table: a live unit and/or a configured feeder. */
    static class Row {
        OrionDeviceInfo info;
        OrionFeeder feeder;

        double x() {
            return feeder == null ? Double.MAX_VALUE : feeder.getLocation().getX();
        }
    }

    private class RailPanel extends JPanel {
        private final int rail;
        private final List<Row> rows = new ArrayList<>();
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"#", "Address", "Serial", "State", "Comp", "Width", "Feeder",
                    "X (mm)", "Note"};

            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int c) {
                return cols[c];
            }

            @Override
            public Object getValueAt(int r, int c) {
                Row row = rows.get(r);
                OrionDeviceInfo d = row.info;
                switch (c) {
                    case 0:
                        return row.feeder == null ? "" : String.valueOf(r + 1);
                    case 1:
                        return d == null ? "-" : d.address;
                    case 2:
                        return d == null ? row.feeder.getSerial() : d.serial;
                    case 3:
                        return d == null ? "MISSING" : d.state.toString();
                    case 4:
                        return d == null ? "" : d.componentId;
                    case 5:
                        return d == null ? "" : (d.tapeWidthMm == 0xFF ? "?" : d.tapeWidthMm + " mm");
                    case 6:
                        return row.feeder == null ? "(unbound)" : row.feeder.getName();
                    case 7:
                        return row.feeder == null ? "" : String.format("%.2f", row.feeder.getLocation().getX());
                    default:
                        return d == null ? "configured feeder not found on this rail" : d.note;
                }
            }
        };
        private final JTable table = new JTable(model);
        private final JLabel summary = new JLabel();

        RailPanel(int rail) {
            this.rail = rail;
            setLayout(new BorderLayout());
            table.setAutoCreateRowSorter(false);
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                        boolean focus, int r, int c) {
                    Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                    Row row = rows.get(r);
                    if (!sel) {
                        Color col = Color.BLACK;
                        if (row.info == null) {
                            col = Color.RED.darker();
                        } else if (row.info.state == OrionDeviceInfo.State.CONFLICT) {
                            col = Color.RED;
                        } else if (row.info.state == OrionDeviceInfo.State.LOST) {
                            col = Color.GRAY;
                        } else if (row.info.state == OrionDeviceInfo.State.NO_SERIAL) {
                            col = new Color(0xB36B00);
                        } else if (row.feeder == null) {
                            col = new Color(0x0050A0);
                        }
                        comp.setForeground(col);
                    }
                    return comp;
                }
            });
            add(summary, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttons(), BorderLayout.SOUTH);
        }

        private JPanel buttons() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
            p.add(btn("Scan rail", "Probe every address, then discover and address new units", () -> scan(rail)));
            p.add(btn("Ping", "Ping the selected unit", () -> {
                Row r = selected();
                if (r != null && r.info != null) {
                    final OrionBus b = mgr.getBus(rail);
                    OrionUi.run("Ping", () -> {
                        long ms = b.ping(r.info.address);
                        mgr.addLog(rail, org.openpnp.machine.orion.protocol.OrionBusListener.Direction.INFO,
                                ms < 0 ? "Ping address " + r.info.address + ": no reply"
                                        : "Ping address " + r.info.address + ": " + ms + " ms");
                    });
                }
            }));
            p.add(feederAction("Identify", f -> f.identify()));
            p.add(feederAction("Feed", f -> f.feedOnce()));
            p.add(feederAction("Unfeed", f -> f.unfeedOnce()));
            p.add(feederAction("Peel", f -> f.peel(false)));
            p.add(feederAction("Unpeel", f -> f.peel(true)));
            p.add(btn("Create feeder", "Add an OrionFeeder for the selected unbound unit", this::createFeeder));
            p.add(btn("Forget", "Drop the selected unit from the table", () -> {
                Row r = selected();
                if (r != null && r.info != null) {
                    mgr.getBus(rail).forgetAddress(r.info.address);
                    mgr.refresh();
                }
            }));
            JButton power = new JButton("Rail power...");
            power.addActionListener(e -> railPower());
            p.add(power);
            return p;
        }

        private JButton btn(String text, String tip, Runnable r) {
            JButton b = new JButton(text);
            b.setToolTipText(tip);
            b.addActionListener(e -> r.run());
            return b;
        }

        private JButton feederAction(String text, FeederThrunnable a) {
            JButton b = new JButton(text);
            b.addActionListener(e -> {
                Row r = selected();
                if (r == null) {
                    return;
                }
                if (r.feeder != null) {
                    OrionUi.run(text, () -> a.run(r.feeder));
                } else if (r.info != null) {
                    // Unbound unit: drive it through a temporary, unregistered feeder object.
                    OrionFeeder tmp = new OrionFeeder();
                    tmp.setName("unit " + r.info.address);
                    tmp.setSerial(r.info.serial);
                    tmp.setRail(rail);
                    tmp.setPitchMm(0);
                    OrionUi.run(text, () -> a.run(tmp));
                }
            });
            return b;
        }

        private Row selected() {
            int i = table.getSelectedRow();
            return i < 0 || i >= rows.size() ? null : rows.get(i);
        }

        private void createFeeder() {
            Row r = selected();
            if (r == null || r.info == null || r.feeder != null) {
                JOptionPane.showMessageDialog(this, "Select a unit that is not bound to a feeder yet.");
                return;
            }
            try {
                OrionFeeder f = new OrionFeeder();
                f.setName("Orion " + r.info.serial.substring(0, 6) + " R" + (rail + 1) + "A" + r.info.address);
                f.setSerial(r.info.serial);
                f.setRail(rail);
                Configuration.get().getMachine().addFeeder(f);
                OrionUi.run("Identify", () -> f.identify());
                mgr.refresh();
            } catch (Exception e) {
                org.openpnp.gui.support.MessageBoxes.errorBox(MainFrame.get(), "Create feeder", e);
            }
        }

        private void railPower() {
            Object[] opts = {"Power ON", "Power OFF", "Cancel"};
            int c = JOptionPane.showOptionDialog(this, "Rail " + (rail + 1)
                    + " supply (host board only; has no effect on a plain USB-RS485 adapter).",
                    "Rail power", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    opts, opts[2]);
            if (c == 0 || c == 1) {
                OrionUi.run("Rail power", () -> {
                    boolean ok = mgr.getTransport().setRailPower(rail, c == 0);
                    if (!ok) {
                        throw new Exception("This interface cannot switch rail power.");
                    }
                });
            }
        }

        void refresh() {
            OrionBus bus = mgr.getBus(rail);
            rows.clear();
            List<OrionDeviceInfo> devs = bus == null ? new ArrayList<>() : bus.getDevices();
            List<OrionFeeder> feeders = new ArrayList<>();
            for (Feeder f : Configuration.get().getMachine().getFeeders()) {
                if (f instanceof OrionFeeder && ((OrionFeeder) f).getSerial() != null) {
                    feeders.add((OrionFeeder) f);
                }
            }
            List<OrionFeeder> placed = new ArrayList<>();
            for (OrionDeviceInfo d : devs) {
                Row row = new Row();
                row.info = d;
                for (OrionFeeder f : feeders) {
                    if (d.serial != null && d.serial.equalsIgnoreCase(f.getSerial())) {
                        row.feeder = f;
                        placed.add(f);
                    }
                }
                rows.add(row);
            }
            // Configured feeders for this rail whose unit is not on it: show as missing.
            for (OrionFeeder f : feeders) {
                if (!placed.contains(f) && f.getRail() == rail && !seenElsewhere(f)) {
                    Row row = new Row();
                    row.feeder = f;
                    rows.add(row);
                }
            }
            rows.sort(Comparator.comparingDouble(Row::x));
            int online = 0;
            int unbound = 0;
            int problems = 0;
            for (Row r : rows) {
                if (r.info != null && r.info.state == OrionDeviceInfo.State.ONLINE) {
                    online++;
                }
                if (r.info != null && r.feeder == null) {
                    unbound++;
                }
                if (r.info == null || r.info.state == OrionDeviceInfo.State.CONFLICT
                        || r.info.state == OrionDeviceInfo.State.LOST) {
                    problems++;
                }
            }
            summary.setText(String.format("  %d unit(s) shown, %d online, %d unbound, %d with problems.  Sorted by feeder X position.",
                    rows.size(), online, unbound, problems));
            model.fireTableDataChanged();
        }

        private boolean seenElsewhere(OrionFeeder f) {
            for (OrionBus b : mgr.getBuses()) {
                if (b.getRail() != rail && b.findBySerial(f.getSerial()) != null) {
                    return true;
                }
            }
            return false;
        }
    }

    interface FeederThrunnable {
        void run(OrionFeeder f) throws Exception;
    }
}
