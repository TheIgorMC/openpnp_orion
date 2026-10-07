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
        JTabbedPane top = new JTabbedPane();
        top.addTab("Connection", buildConnectionPanel());
        top.addTab("Feeder identification (fiber / tape)", buildIdentifyPanel());
        add(top, BorderLayout.NORTH);
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

        JComboBox<org.openpnp.model.Part> fid = new JComboBox<>(new org.openpnp.gui.support.PartsComboBoxModel());
        fid.setRenderer(new org.openpnp.gui.support.IdentifiableListCellRenderer<org.openpnp.model.Part>());
        fid.setSelectedItem(Configuration.get().getPart(s.getLargeFiducialPartId()));
        fid.addActionListener(e -> {
            org.openpnp.model.Part part = (org.openpnp.model.Part) fid.getSelectedItem();
            s.setLargeFiducialPartId(part == null ? "" : part.getId());
        });
        OrionUi.row(p, 6, "Large fiducial part (feeder finder)", fid);
        JComboBox<OrionSettings.UnverifiedPolicy> policy = new JComboBox<>(OrionSettings.UnverifiedPolicy.values());
        policy.setSelectedItem(s.getUnverifiedPolicy());
        policy.addActionListener(e -> s.setUnverifiedPolicy((OrionSettings.UnverifiedPolicy) policy.getSelectedItem()));
        OrionUi.row(p, 8, "If a rail was not verified by vision when a job starts", policy);

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
        OrionUi.row(p, 7, "", buttons);
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


    private JPanel buildIdentifyPanel() {
        OrionSettings s = mgr.getSettings();
        JPanel p = OrionUi.grid();
        JComboBox<OrionSettings.IdentifyMethod> method = new JComboBox<>(OrionSettings.IdentifyMethod.values());
        method.setSelectedItem(s.getIdentifyMethod());
        method.addActionListener(e -> s.setIdentifyMethod((OrionSettings.IdentifyMethod) method.getSelectedItem()));
        OrionUi.row(p, 0, "Used when a fiducial cannot be matched to a unit directly", method);

        OrionUi.row(p, 1, "Fiber light", OrionUi.line(new JLabel("brightness rise >"),
                spinner(s.getFiberThreshold(), 1, 255, 1, s::setFiberThreshold)));
        OrionUi.row(p, 2, "Tape movement (fallback)", OrionUi.line(new JLabel("changed pixels >"),
                spinner(s.getMovementThreshold(), 1, 100000, 10, s::setMovementThreshold),
                new JLabel("moved back (mm)"), spinner(s.getMovementMm(), 0.1, 5, 0.1, s::setMovementMm),
                new JLabel("stagger (ms between feeders)"),
                spinner(s.getStaggerMs(), 0, 5000, 50, v -> s.setStaggerMs((int) v))));
        OrionUi.row(p, 3, "Camera settle (both methods)", OrionUi.line(
                spinner(s.getSettleMs(), 0, 5000, 50, v -> s.setSettleMs((int) v)),
                new JLabel("ms to wait after switching a fiber or moving a tape, before taking the picture")));
        OrionUi.row(p, 4, "Pipelines", OrionUi.line(
                button("Edit fiber pipeline...", () -> editPipeline("Orion fiber detection",
                        s.getFiberPipeline())),
                button("Reset", () -> s.resetFiberPipeline()),
                button("Edit tape pipeline...", () -> editPipeline("Orion tape movement",
                        s.getMovementPipeline())),
                button("Reset", () -> s.resetMovementPipeline())));
        OrionUi.row(p, 5, "Tuning (unit selected below)", OrionUi.line(
                button("Fiber ON", () -> tuneAction("Fiber on", f -> f.setFiber(true))),
                button("Fiber OFF", () -> tuneAction("Fiber off", f -> f.setFiber(false))),
                button("Test fiber", () -> testFiber()),
                button("Test tape movement", () -> testMovement())));
        JLabel hint = new JLabel("<html><body style='width:560px'>Jog the camera over the fiber spot (or a "
                + "sprocket hole), press <b>Fiber ON</b>, open the pipeline and tune the mask / blur until "
                + "the spot stands out. <b>Test</b> reports the measured values and the verdict; set the "
                + "threshold between 'off' and 'on'. The spot / hole offsets from the large fiducial are set "
                + "per rail below.</body></html>");
        OrionUi.row(p, 6, "", hint);
        return p;
    }

    private JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private javax.swing.JSpinner spinner(double v, double min, double max, double step,
            java.util.function.DoubleConsumer set) {
        javax.swing.JSpinner sp = new javax.swing.JSpinner(new SpinnerNumberModel(v, min, max, step));
        sp.setPreferredSize(new java.awt.Dimension(80, sp.getPreferredSize().height));
        sp.addChangeListener(e -> set.accept(((Number) sp.getValue()).doubleValue()));
        return sp;
    }

    private void editPipeline(String title, org.openpnp.vision.pipeline.CvPipeline pipeline) {
        try {
            pipeline.setProperty("camera", Configuration.get().getMachine().getDefaultHead().getDefaultCamera());
            org.openpnp.vision.pipeline.ui.CvPipelineEditor editor =
                    new org.openpnp.vision.pipeline.ui.CvPipelineEditor(pipeline);
            new org.openpnp.vision.pipeline.ui.CvPipelineEditorDialog(MainFrame.get(), title, editor)
                    .setVisible(true);
        } catch (Exception e) {
            org.openpnp.gui.support.MessageBoxes.errorBox(MainFrame.get(), "Pipeline editor", e);
        }
    }

    private RailPanel currentRail() {
        Component c = railTabs.getSelectedComponent();
        return c instanceof RailPanel ? (RailPanel) c : null;
    }

    private OrionFeeder selectedOrTemp() {
        RailPanel rp = currentRail();
        OrionFeeder f = rp == null ? null : rp.feederForSelected();
        if (f == null) {
            JOptionPane.showMessageDialog(this, "Select a unit in the rail table first.");
        }
        return f;
    }

    private void tuneAction(String what, FeederThrunnable a) {
        OrionFeeder f = selectedOrTemp();
        if (f != null) {
            OrionUi.run(what, () -> a.run(f));
        }
    }

    private void testFiber() {
        OrionFeeder f = selectedOrTemp();
        if (f != null) {
            OrionUi.run("Test fiber", () -> mgr.testFiber(f), r -> JOptionPane.showMessageDialog(this,
                    String.format("Fiber off: %.0f\nFiber on:  %.0f\nRise:      %.0f   (threshold %.0f)\n\n"
                            + "Verdict: %s", r[0], r[1], r[2], r[3],
                            r[2] >= r[3] ? "DETECTED" : "NOT detected: brighter LED, bigger mask, or lower threshold"),
                    "Fiber detection test", JOptionPane.INFORMATION_MESSAGE));
        }
    }

    private void testMovement() {
        OrionFeeder f = selectedOrTemp();
        if (f != null) {
            OrionUi.run("Test movement", () -> mgr.testMovement(f), r -> JOptionPane.showMessageDialog(this,
                    String.format("Changed pixels: %.0f   (threshold %.0f)\n\nVerdict: %s", r[0], r[1],
                            r[0] >= r[1] ? "MOVEMENT DETECTED" : "NOT detected: check mask / hole position / "
                                    + "distance moved"), "Tape movement test", JOptionPane.INFORMATION_MESSAGE));
        }
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

        /** Sort key: the unit's own slot X, else its feeder's pick X, else last. */
        double x() {
            if (info != null && !Double.isNaN(info.slotXMm)) {
                return info.slotXMm;
            }
            return feeder == null ? Double.MAX_VALUE : feeder.getLocation().getX();
        }
    }

    private class RailPanel extends JPanel {
        private final int rail;
        private final List<Row> rows = new ArrayList<>();
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"#", "Address", "Serial", "State", "Comp", "Width", "Feeder",
                    "Slot X (mm)", "Pick X (mm)", "Note"};

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
                        return d == null || Double.isNaN(d.slotXMm) ? "" : String.format("%.1f", d.slotXMm);
                    case 8:
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
            JPanel north = new JPanel(new BorderLayout());
            north.add(summary, BorderLayout.NORTH);
            north.add(visionSettings(), BorderLayout.CENTER);
            add(north, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttons(), BorderLayout.SOUTH);
        }

        private javax.swing.JSpinner fiberX;
        private javax.swing.JSpinner fiberY;
        private javax.swing.JSpinner holeX;
        private javax.swing.JSpinner holeY;

        /**
         * The camera is over the fiber spot (or a hole): store its offset from the selected feeder's
         * large fiducial, so the offset does not have to be measured and typed.
         */
        private void teachOffset(boolean fiber) {
            Row r = selected();
            if (r == null || r.feeder == null || r.feeder.getFiducialNominal() == null) {
                JOptionPane.showMessageDialog(this, "Select a feeder whose large fiducial position is "
                        + "known (use \"Locate selected here\" over its fiducial first), then jog the camera "
                        + "over the " + (fiber ? "fiber spot" : "sprocket hole") + " and press Teach here.");
                return;
            }
            org.openpnp.machine.orion.OrionRailSettings rs = mgr.getSettings().getRailSettings(rail);
            OrionUi.run("Teach offset", () -> {
                org.openpnp.model.Location cam = Configuration.get().getMachine().getDefaultHead()
                        .getDefaultCamera().getLocation()
                        .convertToUnits(org.openpnp.model.LengthUnit.Millimeters);
                org.openpnp.model.Location fid = r.feeder.getFiducialNominalOrDefault(rs)
                        .convertToUnits(org.openpnp.model.LengthUnit.Millimeters);
                return new double[] {cam.getX() - fid.getX(), cam.getY() - fid.getY()};
            }, d -> {
                double dx = Math.round(d[0] * 100) / 100.0;
                double dy = Math.round(d[1] * 100) / 100.0;
                if (fiber) {
                    fiberX.setValue(dx);
                    fiberY.setValue(dy);
                } else {
                    holeX.setValue(dx);
                    holeY.setValue(dy);
                }
            });
        }

        private javax.swing.JSpinner dspin(double v, double min, double max, double step,
                java.util.function.DoubleConsumer set) {
            javax.swing.JSpinner sp = new javax.swing.JSpinner(new SpinnerNumberModel(v, min, max, step));
            sp.setPreferredSize(new java.awt.Dimension(80, sp.getPreferredSize().height));
            sp.addChangeListener(e -> set.accept(((Number) sp.getValue()).doubleValue()));
            return sp;
        }

        private JPanel visionSettings() {
            org.openpnp.machine.orion.OrionRailSettings rs = mgr.getSettings().getRailSettings(rail);
            JPanel p = new JPanel(new java.awt.GridLayout(4, 1));
            p.setBorder(BorderFactory.createTitledBorder(
                    "Vision scan of this rail (large fiducials; machine coordinates, mm)"));
            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            p.add(row1);
            p.add(row2);
            JPanel row4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            p.add(row3);
            p.add(row4);
            row1.add(new JLabel("X from"));
            row1.add(dspin(rs.getXMin(), -5000, 5000, 1, rs::setXMin));
            row1.add(new JLabel("to"));
            row1.add(dspin(rs.getXMax(), -5000, 5000, 1, rs::setXMax));
            row1.add(new JLabel("fiducial Y"));
            row1.add(dspin(rs.getFiducialY(), -5000, 5000, 0.1, rs::setFiducialY));
            row1.add(new JLabel("Z"));
            row1.add(dspin(rs.getFiducialZ(), -500, 500, 0.1, rs::setFiducialZ));
            row2.add(new JLabel("step"));
            row2.add(dspin(rs.getScanStepMm(), 1, 50, 0.5, rs::setScanStepMm));
            row2.add(new JLabel("exclusion"));
            row2.add(dspin(rs.getExclusionMm(), 1, 50, 0.5, rs::setExclusionMm));
            row2.add(new JLabel("save shifts >"));
            row2.add(dspin(rs.getMinSaveMm(), 0, 5, 0.01, rs::setMinSaveMm));
            row2.add(new JLabel("reject >"));
            row2.add(dspin(rs.getMaxShiftMm(), 0.1, 50, 0.1, rs::setMaxShiftMm));
            fiberX = dspin(rs.getFiberOffsetX(), -500, 500, 0.1, rs::setFiberOffsetX);
            fiberY = dspin(rs.getFiberOffsetY(), -500, 500, 0.1, rs::setFiberOffsetY);
            holeX = dspin(rs.getHoleOffsetX(), -500, 500, 0.1, rs::setHoleOffsetX);
            holeY = dspin(rs.getHoleOffsetY(), -500, 500, 0.1, rs::setHoleOffsetY);
            row3.add(new JLabel("fiber spot from fiducial dX/dY"));
            row3.add(fiberX);
            row3.add(fiberY);
            row3.add(button("Teach here", () -> teachOffset(true)));
            row3.add(new JLabel("hole dX/dY"));
            row3.add(holeX);
            row3.add(holeY);
            row3.add(button("Teach here", () -> teachOffset(false)));
            JCheckBox auto = new JCheckBox("Rescan automatically when a feeder is missing", rs.isAutoRescan());
            auto.addActionListener(e -> rs.setAutoRescan(auto.isSelected()));
            row4.add(auto);
            return p;
        }

        private void verifyRail() {
            OrionUi.run("Verify rail", () -> mgr.verifyRail(rail, (text, done, total) -> OrionUi.onEdt(() -> {
                progress.setVisible(true);
                progress.setMaximum(Math.max(1, total));
                progress.setValue(done);
                progress.setString("Rail " + (rail + 1) + ": " + text);
            })), result -> {
                progress.setVisible(false);
                StringBuilder sb = new StringBuilder(result.summary()).append("\n\n");
                for (String l : result.log) {
                    sb.append(l).append('\n');
                }
                if (result.rescanNeeded && !result.rescanRan) {
                    sb.append("\nSome feeders are missing. Use \"Locate selected here\" (manual) or enable "
                            + "automatic rescan.");
                }
                if (!result.in(org.openpnp.machine.orion.vision.OrionRailScanner.State.UNRESOLVED).isEmpty()) {
                    sb.append("\nUnresolved feeders: move the camera over each one's large fiducial and "
                            + "use \"Locate selected here\".");
                }
                JOptionPane.showMessageDialog(MainFrame.get(), sb.toString(),
                        "Rail " + (rail + 1) + " vision verify", JOptionPane.INFORMATION_MESSAGE);
                mgr.refresh();
            });
        }

        private JPanel buttons() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
            p.add(btn("Verify rail (vision)", "Check each feeder's large fiducial, save small X shifts, "
                    + "one rescan if something is missing", this::verifyRail));
            p.add(btn("Locate selected here", "Camera is over the selected feeder's large fiducial: "
                    + "measure and save its X", () -> {
                        Row r = selected();
                        if (r == null || r.feeder == null) {
                            JOptionPane.showMessageDialog(this, "Select a row that has a feeder.");
                            return;
                        }
                        OrionUi.run("Locate here", () -> mgr.locateHere(r.feeder));
                    }));
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
            JPanel q = new JPanel(new FlowLayout(FlowLayout.LEFT));
            q.add(feederAction("Identify", f -> f.identify()));
            q.add(feederAction("Feed", f -> f.feedOnce()));
            q.add(feederAction("Unfeed", f -> f.unfeedOnce()));
            q.add(feederAction("Peel", f -> f.peel(false)));
            q.add(feederAction("Unpeel", f -> f.peel(true)));
            q.add(btn("Create feeders for all unbound", "One OpenPnP feeder per unit that has none yet, "
                    + "ordered by slot X", () -> OrionUi.run("Create feeders", () -> mgr.createFeedersForUnbound(rail),
                            made -> JOptionPane.showMessageDialog(this, made.size() + " feeder(s) created. "
                                    + "Assign a part to each in the Feeders tab."))));
            q.add(btn("Create feeder", "Add an OrionFeeder for the selected unbound unit", this::createFeeder));
            q.add(btn("Forget", "Drop the selected unit from the table", () -> {
                Row r = selected();
                if (r != null && r.info != null) {
                    mgr.getBus(rail).forgetAddress(r.info.address);
                    mgr.refresh();
                }
            }));
            JButton power = new JButton("Rail power...");
            power.addActionListener(e -> railPower());
            q.add(power);
            JPanel both = new JPanel(new java.awt.GridLayout(2, 1));
            both.add(p);
            both.add(q);
            return both;
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

        OrionFeeder feederForSelected() {
            Row r = selected();
            if (r == null) {
                return null;
            }
            if (r.feeder != null) {
                return r.feeder;
            }
            if (r.info == null) {
                return null;
            }
            OrionFeeder tmp = new OrionFeeder();
            tmp.setName("unit " + r.info.address);
            tmp.setSerial(r.info.serial);
            tmp.setRail(rail);
            tmp.setPitchMm(0);
            return tmp;
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
            String problem = mgr.railCheckProblem(rail);
            org.openpnp.machine.orion.OrionManager.RailCheck check = mgr.getRailCheck(rail);
            String verified = problem == null && check != null
                    ? "  VERIFIED by vision at " + new java.text.SimpleDateFormat("HH:mm:ss").format(
                            new java.util.Date(check.time)) + "."
                    : "  NOT VERIFIED: " + problem;
            summary.setForeground(problem == null ? new Color(0x006400) : Color.RED.darker());
            summary.setText(String.format("<html><body style='width:1000px'>%d unit(s), %d online, %d unbound, "
                    + "%d with problems.%s</body></html>", rows.size(), online, unbound, problems, verified));
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
