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
    private final JTabbedPane main = new JTabbedPane();
    private final JTabbedPane railTabs = new JTabbedPane();
    private final JPanel visionHolder = new JPanel(new BorderLayout());
    private final JLabel connLabel = new JLabel();
    private final JLabel monitorLabel = new JLabel();
    private final JButton connectButton = new JButton("Connect");
    private final JProgressBar progress = new JProgressBar();
    private int builtRails = -1;
    /** Selected tile per rail, kept here so it survives refreshes and rebuilds of the rail views. */
    private final java.util.Map<Integer, String> selections = new java.util.HashMap<>();

    public OrionBusManagerPanel() {
        setLayout(new BorderLayout());
        JPanel feeders = new JPanel(new BorderLayout());
        feeders.add(buildHeader(), BorderLayout.NORTH);
        feeders.add(railTabs, BorderLayout.CENTER);
        progress.setStringPainted(true);
        progress.setVisible(false);
        feeders.add(progress, BorderLayout.SOUTH);
        main.addTab("Feeders", feeders);
        main.addTab("Connection and monitor", new JScrollPane(top(buildConnectionPanel(), buildMonitorPanel(), buildSetupPanel())));
        main.addTab("Rail vision scan", visionHolder);
        main.addTab("Fiber / tape identification", top(buildIdentifyPanel()));
        main.addTab("Nozzle trim", top(buildNozzleTrimPanel()));
        main.addTab("Bus log", new OrionDebugPanel());
        add(main, BorderLayout.CENTER);
        mgr.addListener(this);
        rebuildRails();
        refreshHeader();
    }

    /** Switch to the bus log / raw command tab. */
    public void showLog() {
        main.setSelectedIndex(main.getTabCount() - 1);
    }

    /** Stack panels at the top of their tab instead of stretching them over the whole height. */
    private static JPanel top(JPanel... ps) {
        JPanel box = new JPanel();
        box.setLayout(new javax.swing.BoxLayout(box, javax.swing.BoxLayout.Y_AXIS));
        for (JPanel p : ps) {
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.add(p);
        }
        JPanel out = new JPanel(new BorderLayout());
        out.add(box, BorderLayout.NORTH);
        return out;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        mgr.addListener(this);
        clock.start();
        rebuildRails();
    }

    @Override
    public void removeNotify() {
        clock.stop();
        mgr.removeListener(this);
        super.removeNotify();
    }

    /** Once a second: "pinged 2 s ago" and the countdown of the automatic scan. */
    private final javax.swing.Timer clock = new javax.swing.Timer(1000, e -> {
        refreshHeader();
        for (Component c : railTabs.getComponents()) {
            if (c instanceof RailPanel) {
                ((RailPanel) c).refreshPending();
            }
        }
    });

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
        String libProblem = JSerialCommChannel.getLibraryProblem();
        if (libProblem != null) {
            JLabel warn = new JLabel("<html><body style='width:700px;color:#b00000'>" + libProblem
                    + "</body></html>");
            OrionUi.row(p, 10, "Serial library", warn);
        }
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
        JCheckBox autoCreate = new JCheckBox("...and create a feeder for every unit that has none",
                s.isAutoCreateFeeders());
        autoCreate.addActionListener(e -> s.setAutoCreateFeeders(autoCreate.isSelected()));
        OrionUi.row(p, 9, "", autoCreate);

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
        OrionUi.row(p, 8, "Pocket / part detection", OrionUi.line(
                button("Edit pocket pipeline...", () -> editPipeline("Orion pocket", s.getPocketPipeline())),
                button("Reset", () -> s.resetPocketPipeline())));
        OrionUi.row(p, 7, "Sprocket hole detection", OrionUi.line(
                button("Edit hole pipeline...", () -> editPipeline("Orion sprocket hole",
                        s.getHolePipeline())),
                button("Reset", () -> s.resetHolePipeline()),
                new JLabel("hole diameter (mm)"),
                spinner(s.getHoleDiameterMm(), 0.5, 5, 0.1, s::setHoleDiameterMm),
                new JLabel("search radius (mm)"),
                spinner(s.getHoleSearchMm(), 2, 20, 0.5, s::setHoleSearchMm)));
        return p;
    }

    /** One X / Y pick trim per nozzle, added to the pick location of every Orion feeder. */
    private JPanel buildNozzleTrimPanel() {
        OrionSettings s = mgr.getSettings();
        JPanel p = OrionUi.grid();
        p.setBorder(BorderFactory.createTitledBorder("Pick trim per nozzle"));
        int row = 0;
        OrionUi.row(p, row++, "", new JLabel("<html><body style='width:560px'>Added to the pick location of every "
                + "Orion feeder when that nozzle picks (not to the camera's look position). If N2 lands 0.05 mm "
                + "left of where N1 lands on the same pocket, give N2 +0.05 in X.</body></html>"));
        try {
            java.util.List<org.openpnp.spi.Nozzle> nozzles = org.openpnp.model.Configuration.get().getMachine()
                    .getDefaultHead().getNozzles();
            for (org.openpnp.spi.Nozzle nz : nozzles) {
                final String name = nz.getName();
                final javax.swing.JSpinner[] sp = new javax.swing.JSpinner[2];
                Runnable save = () -> s.setNozzleTrim(name, ((Number) sp[0].getValue()).doubleValue(),
                        ((Number) sp[1].getValue()).doubleValue());
                sp[0] = spinner(s.getNozzleTrimX(name), -2, 2, 0.01, v -> save.run());
                sp[1] = spinner(s.getNozzleTrimY(name), -2, 2, 0.01, v -> save.run());
                javax.swing.JCheckBox always = new javax.swing.JCheckBox("always check the pocket");
                always.setSelected(s.isAlwaysCheck(name));
                always.addActionListener(e -> s.setAlwaysCheck(name, always.isSelected()));
                OrionUi.row(p, row++, "Nozzle " + name + " (mm)", OrionUi.line(new JLabel("X"), sp[0],
                        new JLabel("Y"), sp[1], always));
            }
            OrionUi.row(p, row++, "", new JLabel("<html><body style='width:560px'>Nozzle tips that need constant "
                    + "alignment (fine tips): when one of them is on the picking nozzle, the camera check of the "
                    + "pocket runs before every pick, whatever the Check setting of the feeder says.</body></html>"));
            for (org.openpnp.spi.NozzleTip tip : org.openpnp.model.Configuration.get().getMachine()
                    .getNozzleTips()) {
                final String tipName = tip.getName();
                javax.swing.JCheckBox cb = new javax.swing.JCheckBox("always check the pocket");
                cb.setSelected(s.isAlwaysCheck(tipName));
                cb.addActionListener(e -> s.setAlwaysCheck(tipName, cb.isSelected()));
                OrionUi.row(p, row++, "Tip " + tipName, OrionUi.line(cb, new JLabel("pick Z trim (mm)"),
                        spinner(s.getTipTrimZ(tipName), -3, 3, 0.01, v -> s.setTipTrimZ(tipName, v))));
            }
            if (nozzles.isEmpty()) {
                OrionUi.row(p, row++, "", new JLabel("The default head has no nozzles."));
            }
        } catch (Exception e) {
            OrionUi.row(p, row++, "", new JLabel("Cannot list the nozzles: " + e));
        }
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
            OrionUi.run("Test fiber", () -> mgr.testFiber(f), r -> {
                boolean circles = r[4] > 0;
                String values = circles
                        ? String.format("Circles found with fiber off: %.0f\nCircles found with fiber on:  %.0f\n",
                                r[5], r[6])
                        : String.format("Fiber off: %.0f\nFiber on:  %.0f\nRise:      %.0f   (threshold %.0f)\n",
                                r[0], r[1], r[2], r[3]);
                String verdict = r[2] >= r[3] ? "DETECTED"
                        : (circles ? "NOT detected: no circle of the set diameter with the fiber on"
                                : "NOT detected: brighter LED, bigger mask, or lower threshold");
                JOptionPane.showMessageDialog(this, values + "\nVerdict: " + verdict,
                        "Fiber detection test", JOptionPane.INFORMATION_MESSAGE);
            });
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

    // ------------------------------------------------------------------ header (connection, monitor, reset)

    private JPanel buildHeader() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, javax.swing.UIManager.getColor("Component.borderColor") == null
                        ? Color.LIGHT_GRAY : javax.swing.UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JPanel left = new JPanel();
        left.setLayout(new javax.swing.BoxLayout(left, javax.swing.BoxLayout.Y_AXIS));
        connLabel.setFont(connLabel.getFont().deriveFont(java.awt.Font.BOLD, connLabel.getFont().getSize2D() + 1f));
        monitorLabel.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        left.add(connLabel);
        left.add(monitorLabel);
        bar.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        connectButton.addActionListener(e -> {
            if (mgr.isConnected()) {
                mgr.disconnect();
            } else {
                OrionUi.run("Connect", () -> {
                    mgr.connect();
                    for (OrionBus b : mgr.getBuses()) {
                        b.scan(null);
                    }
                    mgr.refresh();
                });
            }
        });
        right.add(connectButton);
        right.add(button("Scan all rails", () -> scan(null)));
        JButton reset = new JButton("Reset bus ▾");
        reset.setToolTipText("Bring a hung bus back without unplugging the adapter");
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem soft = new javax.swing.JMenuItem("Reset bus (reopen the port)");
        soft.setToolTipText("Closes and reopens the USB port, clears stale data, then re-scans. Feeders keep power.");
        soft.addActionListener(e -> resetBus(false));
        javax.swing.JMenuItem hard = new javax.swing.JMenuItem("Hard reset (also power-cycle the rails)");
        hard.setToolTipText("Host board only: switches the rail supply off and on, which also frees a feeder "
                + "that is stuck talking. Feeders restart and get their old addresses back.");
        hard.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Power-cycle the feeder rails? Feeders restart (about 3 s).",
                    "Hard reset", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                resetBus(true);
            }
        });
        menu.add(soft);
        menu.add(hard);
        reset.addActionListener(e -> menu.show(reset, 0, reset.getHeight()));
        right.add(reset);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void resetBus(boolean hard) {
        OrionUi.run(hard ? "Hard reset" : "Reset bus", () -> {
            if (!mgr.resetBus(hard, "requested by the user")) {
                throw new Exception("The port could not be reopened: " + mgr.getLastError());
            }
            for (OrionBus b : mgr.getBuses()) {
                b.scan(null);
            }
            if (mgr.getSettings().isAutoCreateFeeders()) {
                for (OrionBus b : mgr.getBuses()) {
                    mgr.createFeedersForUnbound(b.getRail());
                }
            }
            mgr.refresh();
        });
    }

    private void refreshHeader() {
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
        java.awt.Color c = !connected ? OrionLed.RED : (online < total ? OrionLed.AMBER : OrionLed.GREEN);
        connLabel.setIcon(new OrionLed(c, 12));
        connLabel.setIconTextGap(8);
        connLabel.setText(connected ? mgr.describeConnection() + "   ·   " + online + " of " + total
                + " feeder(s) answering" : "Not connected" + (mgr.getLastError().isEmpty() ? ""
                        : "   (" + mgr.getLastError() + ")"));
        org.openpnp.machine.orion.OrionBusMonitor mon = mgr.getMonitor();
        String ago = mon.getLastSweepMs() == 0 ? "" : "   ·   last ping sweep "
                + Math.max(0, (System.currentTimeMillis() - mon.getLastSweepMs()) / 1000) + " s ago";
        monitorLabel.setText(connected ? "Monitor: " + mon.getStatus() + ago
                : "Choose the interface in the Connection tab, then press Connect.");
        connectButton.setText(connected ? "Disconnect" : "Connect");
    }

    /** Background pings, new feeder detection and bus recovery settings. */
    private JPanel buildMonitorPanel() {
        OrionSettings s = mgr.getSettings();
        JPanel p = OrionUi.grid();
        p.setBorder(BorderFactory.createTitledBorder("Monitor (runs only while no job or machine task is active)"));
        JCheckBox on = new JCheckBox("Ping every feeder regularly, look for new ones and recover a hung bus",
                s.isMonitorEnabled());
        on.addActionListener(e -> s.setMonitorEnabled(on.isSelected()));
        OrionUi.row(p, 0, "", on);
        OrionUi.row(p, 1, "Seconds between ping sweeps", spinner(s.getMonitorIntervalS(), 1, 60, 1,
                v -> s.setMonitorIntervalS((int) v)));
        OrionUi.row(p, 2, "Scan the rail automatically after (s)", spinner(s.getAutoScanDelayS(), 5, 600, 5,
                v -> s.setAutoScanDelayS((int) v)));
        OrionUi.row(p, 3, "", new JLabel("<html><body style='width:520px'>A feeder that stops answering turns red "
                + "after two missed pings. A newly plugged feeder is announced; if no other one is added for the "
                + "time above, the rail is scanned and it gets its address. If nothing on a rail answers for "
                + "three sweeps, the port is reopened (then the rails are power-cycled on a host board).</body></html>"));
        return p;
    }

    /** Setup cycle limits and the vacuum probe that finds the pick height. */
    private JPanel buildSetupPanel() {
        OrionSettings s = mgr.getSettings();
        JPanel p = OrionUi.grid();
        p.setBorder(BorderFactory.createTitledBorder("Setup cycle and pick height probe"));
        int r = 0;
        JCheckBox prompt = new JCheckBox("Offer the setup cycle when the machine has been homed", s.isPromptSetupOnHome());
        prompt.addActionListener(e -> s.setPromptSetupOnHome(prompt.isSelected()));
        OrionUi.row(p, r++, "", prompt);
        OrionUi.row(p, r++, "Vision up to a part of (mm)", OrionUi.line(new JLabel("width"),
                spinner(s.getMaxVisionWidthMm(), 0.1, 50, 0.1, s::setMaxVisionWidthMm), new JLabel("length"),
                spinner(s.getMaxVisionLengthMm(), 0.1, 50, 0.1, s::setMaxVisionLengthMm),
                new JLabel("larger parts go semi blind (offset only)")));
        OrionUi.row(p, r++, "Pocket counts as in place within (mm)", spinner(s.getPocketTolMm(), 0.01, 2, 0.01,
                s::setPocketTolMm));
        OrionUi.row(p, r++, "Semi blind: report a pocket further off than (mm)", spinner(s.getBlindCheckTolMm(), 0.1,
                10, 0.1, s::setBlindCheckTolMm));

        JCheckBox probe = new JCheckBox("Find each feeder's pick height with the vacuum sensor at its first feed of a "
                + "session", s.isZProbeEnabled());
        probe.addActionListener(e -> s.setZProbeEnabled(probe.isSelected()));
        OrionUi.row(p, r++, "", probe);
        java.util.List<String> names = new ArrayList<>();
        names.add("");
        try {
            for (org.openpnp.spi.Actuator a : Configuration.get().getMachine().getActuators()) {
                names.add(a.getName());
            }
            for (org.openpnp.spi.Head h : Configuration.get().getMachine().getHeads()) {
                for (org.openpnp.spi.Actuator a : h.getActuators()) {
                    if (!names.contains(a.getName())) {
                        names.add(a.getName());
                    }
                }
            }
        } catch (Exception e) {
            // no machine yet: the list stays empty
        }
        JComboBox<String> act = new JComboBox<>(names.toArray(new String[0]));
        act.setSelectedItem(s.getVacuumSenseActuator());
        act.addActionListener(e -> s.setVacuumSenseActuator((String) act.getSelectedItem()));
        JLabel reading = new JLabel(" ");
        OrionUi.row(p, r++, "Vacuum sensor", OrionUi.line(act, button("Read now", () -> {
            String name = (String) act.getSelectedItem();
            OrionUi.run("Read vacuum sensor", () -> {
                org.openpnp.spi.Actuator a = Configuration.get().getMachine().getActuatorByName(name);
                if (a == null) {
                    a = Configuration.get().getMachine().getDefaultHead().getActuatorByName(name);
                }
                if (a == null) {
                    throw new Exception("Choose the vacuum sensor actuator first.");
                }
                return a.read();
            }, v -> reading.setText("reads " + v));
        }), reading));
        JComboBox<String> sealed = new JComboBox<>(new String[] {"the reading goes down when sealed (pressure)",
                "the reading goes up when sealed (vacuum level)"});
        sealed.setSelectedIndex(s.isVacuumSealedBelow() ? 0 : 1);
        sealed.addActionListener(e -> s.setVacuumSealedBelow(sealed.getSelectedIndex() == 0));
        OrionUi.row(p, r++, "Sealed when", OrionUi.line(sealed, new JLabel("threshold"),
                spinner(s.getVacuumThreshold(), -100000, 100000, 1, s::setVacuumThreshold)));
        OrionUi.row(p, r++, "Search (mm)", OrionUi.line(new JLabel("start above the expected Z"),
                spinner(s.getZSearchAboveMm(), 0, 20, 0.1, s::setZSearchAboveMm), new JLabel("end below"),
                spinner(s.getZSearchBelowMm(), 0, 20, 0.1, s::setZSearchBelowMm), new JLabel("step"),
                spinner(s.getZStepMm(), 0.01, 1, 0.01, s::setZStepMm)));
        OrionUi.row(p, r++, "Go deeper than the seal by (mm)", spinner(s.getZExtraMm(), 0, 2, 0.01, s::setZExtraMm));
        OrionUi.row(p, r++, "Settle per step (ms) / speed", OrionUi.line(
                spinner(s.getZSettleMs(), 0, 2000, 10, v -> s.setZSettleMs((int) v)),
                spinner(s.getZSpeed(), 0.02, 1, 0.05, s::setZSpeed), new JLabel("of full speed")));
        OrionUi.row(p, r++, "", new JLabel("<html><body style='width:520px'>Z is taken to decrease going down. The "
                + "nozzle is lowered step by step over the pick spot with the vacuum on; the first reading past the "
                + "threshold marks the part, and the pick height is that Z minus the extra depth. Every feeder does "
                + "this at its first feed of each session, and new or replaced feeders start from the rail's default "
                + "Z.</body></html>"));
        return p;
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
                    mgr.getMonitor().cancelAutoScan(b.getRail());
                }
            } finally {
                OrionUi.onEdt(() -> progress.setVisible(false));
                mgr.refresh();
            }
        });
    }

    // ------------------------------------------------------------------ per-rail views

    @Override
    public void orionChanged() {
        OrionUi.onEdt(() -> {
            rebuildRails();
            refreshHeader();
        });
    }

    private void rebuildRails() {
        int rails = mgr.getRailCount();
        if (rails != builtRails) {
            railTabs.removeAll();
            visionHolder.removeAll();
            JPanel visions = new JPanel();
            visions.setLayout(new javax.swing.BoxLayout(visions, javax.swing.BoxLayout.Y_AXIS));
            for (int r = 0; r < rails; r++) {
                RailPanel rp = new RailPanel(r);
                railTabs.addTab("Rail " + (r + 1), rp);
                JPanel v = rp.visionSettings();
                v.setBorder(BorderFactory.createTitledBorder("Rail " + (r + 1)
                        + ": vision scan (large fiducials; machine coordinates, mm)"));
                v.setAlignmentX(Component.LEFT_ALIGNMENT);
                visions.add(v);
            }
            builtRails = rails;
            if (rails == 0) {
                JLabel hint = new JLabel("<html><body style='width:420px;padding:24px'><b>Not connected.</b><br>"
                        + "Choose the interface in the Connection tab and press Connect (top right).</body></html>");
                hint.setVerticalAlignment(javax.swing.SwingConstants.TOP);
                railTabs.addTab("Rails", hint);
                visions.add(new JLabel("  Connect first: the vision scan settings are per rail."));
            }
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.add(visions, BorderLayout.NORTH);
            visionHolder.add(wrap, BorderLayout.CENTER);
            visionHolder.revalidate();
        }
        for (Component c : railTabs.getComponents()) {
            if (c instanceof RailPanel) {
                ((RailPanel) c).refresh();
            }
        }
    }

    /** One unit and/or one configured feeder. */
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

        String key() {
            return info != null ? "A" + info.address : "S" + (feeder == null ? "" : feeder.getSerial());
        }
    }

    private class RailPanel extends JPanel {
        private final int rail;
        private final List<Row> rows = new ArrayList<>();
        private final JPanel tiles = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
        private final JLabel counts = new JLabel();
        private final JLabel verified = new JLabel();
        private final JPanel pendingBar = new JPanel(new BorderLayout(8, 0));
        private final JLabel pendingText = new JLabel();
        private final JPanel details = new JPanel(new BorderLayout());
        private final List<FeederTile> tileList = new ArrayList<>();
        private String selectedKey;
        private JButton createAll;

        RailPanel(int rail) {
            this.rail = rail;
            this.selectedKey = selections.get(rail);
            setLayout(new BorderLayout());

            JPanel north = new JPanel();
            north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
            north.setBorder(BorderFactory.createEmptyBorder(8, 10, 2, 10));
            counts.setAlignmentX(Component.LEFT_ALIGNMENT);
            verified.setAlignmentX(Component.LEFT_ALIGNMENT);
            north.add(counts);
            north.add(javax.swing.Box.createVerticalStrut(2));
            north.add(verified);
            pendingBar.setBackground(new Color(0xFFF4CE));
            pendingBar.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0xE8C860)), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
            pendingText.setForeground(new Color(0x5C4400));
            pendingBar.add(pendingText, BorderLayout.CENTER);
            JPanel pb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            pb.setOpaque(false);
            pb.add(button("Scan now", () -> mgr.getMonitor().scanNow(rail)));
            pb.add(button("Not now", () -> {
                mgr.getMonitor().cancelAutoScan(rail);
                refreshPending();
            }));
            pendingBar.add(pb, BorderLayout.EAST);
            pendingBar.setAlignmentX(Component.LEFT_ALIGNMENT);
            pendingBar.setVisible(false);
            north.add(javax.swing.Box.createVerticalStrut(6));
            north.add(pendingBar);
            add(north, BorderLayout.NORTH);

            tiles.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
            JScrollPane sp = new JScrollPane(tiles);
            sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, javax.swing.UIManager.getColor(
                    "Component.borderColor") == null ? Color.LIGHT_GRAY : javax.swing.UIManager.getColor(
                            "Component.borderColor")));
            sp.getVerticalScrollBar().setUnitIncrement(FeederTile.H);
            add(sp, BorderLayout.CENTER);

            details.setPreferredSize(new java.awt.Dimension(310, 100));
            details.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            add(details, BorderLayout.EAST);
            add(railButtons(), BorderLayout.SOUTH);
        }

        // ---- vision scan settings, teach, verify, create (shown in the "Rail vision scan" tab / actions)

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
            JPanel p = new JPanel(new java.awt.GridLayout(6, 1));
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
            row2.add(new JLabel("fiducial spacing (0 = off)"));
            row2.add(dspin(rs.getFiducialSpacingMm(), 0, 100, 0.5, rs::setFiducialSpacingMm));
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

            JPanel row5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            JPanel row6 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            p.add(row5);
            p.add(row6);
            JCheckBox win = new JCheckBox("Pick window: peel edge at", rs.isPickWindowSet());
            win.setToolTipText("Where the cover tape is peeled back, along the tape axis. The setup cycle brings "
                    + "each pocket to this edge + margin + half its length.");
            win.addActionListener(e -> rs.setPickWindowSet(win.isSelected()));
            JSpinner edge = dspin(rs.getPeelEdge(), -5000, 5000, 0.1, v -> {
                rs.setPeelEdge(v);
                rs.setPickWindowSet(true);
                win.setSelected(true);
            });
            row5.add(win);
            row5.add(edge);
            row5.add(button("= camera X", () -> edge.setValue(Math.round(cameraAxis(0) * 100) / 100.0)));
            row5.add(button("= camera Y", () -> edge.setValue(Math.round(cameraAxis(1) * 100) / 100.0)));
            row5.add(new JLabel("margin"));
            row5.add(dspin(rs.getWindowMarginMm(), 0, 20, 0.1, rs::setWindowMarginMm));
            JComboBox<String> dir = new JComboBox<>(new String[] {"pockets on the + side of the edge",
                    "pockets on the - side of the edge"});
            dir.setSelectedIndex(rs.getWindowDirection() >= 0 ? 0 : 1);
            dir.addActionListener(e -> rs.setWindowDirection(dir.getSelectedIndex() == 0 ? 1 : -1));
            row5.add(dir);
            JCheckBox zc = new JCheckBox("Start pick Z at", rs.isPickZSet());
            zc.setToolTipText("Pick height every new feeder of this rail starts with, until the vacuum probe has "
                    + "measured its own at the first feed.");
            zc.addActionListener(e -> rs.setPickZSet(zc.isSelected()));
            JSpinner zs = dspin(rs.getDefaultPickZ(), -500, 500, 0.05, v -> {
                rs.setDefaultPickZ(v);
                rs.setPickZSet(true);
                zc.setSelected(true);
            });
            row6.add(zc);
            row6.add(zs);
            row6.add(button("= nozzle Z", () -> {
                try {
                    zs.setValue(Math.round(Configuration.get().getMachine().getDefaultHead().getDefaultNozzle()
                            .getLocation().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getZ() * 100)
                            / 100.0);
                } catch (Exception ex) {
                    org.openpnp.gui.support.MessageBoxes.errorBox(MainFrame.get(), "Nozzle Z", ex);
                }
            }));
            return p;
        }

        private double cameraAxis(int axis) {
            try {
                org.openpnp.model.Location l = Configuration.get().getMachine().getDefaultHead().getDefaultCamera()
                        .getLocation().convertToUnits(org.openpnp.model.LengthUnit.Millimeters);
                return axis == 0 ? l.getX() : l.getY();
            } catch (Exception e) {
                return 0;
            }
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
                OrionUi.run("Identify", () -> {
                    f.identify();
                    return f.readSettingsFromUnit(); // mirror the unit's own settings
                }, summary -> mgr.refresh());
                mgr.refresh();
            } catch (Exception e) {
                org.openpnp.gui.support.MessageBoxes.errorBox(MainFrame.get(), "Create feeder", e);
            }
        }


        private JPanel railButtons() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
            p.add(btn("Scan rail", "Probe every address, then discover and address new units", () -> scan(rail)));
            p.add(btn("Verify rail by vision", "Check each feeder's large fiducial, save small X shifts, "
                    + "one rescan if something is missing", this::verifyRail));
            p.add(btn("Setup cycle", "Verify this rail by vision, confirm each feeder with its fiber, then bring "
                    + "every pocket to the pick window. Moves the camera and the tapes.", () -> OrionSetupUi.start(rail,
                            (text, done, total) -> OrionUi.onEdt(() -> {
                                progress.setVisible(true);
                                progress.setMaximum(Math.max(1, total));
                                progress.setValue(done);
                                progress.setString("Rail " + (rail + 1) + ": " + text);
                            }))));
            createAll = btn("Create feeders for all unbound", "One OpenPnP feeder per unit that has none yet, "
                    + "ordered by slot X", () -> OrionUi.run("Create feeders", () -> mgr.createFeedersForUnbound(rail),
                            made -> JOptionPane.showMessageDialog(this, made.size() + " feeder(s) created. "
                                    + "Assign a part to each in the Feeders tab.")));
            p.add(createAll);
            JButton power = new JButton("Rail power...");
            power.setToolTipText("Host board only: switch this rail's supply");
            power.addActionListener(e -> railPower());
            p.add(power);
            JLabel legend = new JLabel("   ");
            legend.setIcon(new OrionLed(OrionLed.GREEN, 9));
            legend.setText("answers    ");
            JLabel l2 = new JLabel("no answer    ");
            l2.setIcon(new OrionLed(OrionLed.RED, 9));
            JLabel l3 = new JLabel("needs a look    ");
            l3.setIcon(new OrionLed(OrionLed.AMBER, 9));
            JLabel l4 = new JLabel("no OpenPnP feeder yet");
            l4.setIcon(new javax.swing.Icon() {
                public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                    g.setColor(new Color(0x1A73E8));
                    g.fillRoundRect(x, y + 1, 8, 8, 3, 3);
                }

                public int getIconWidth() {
                    return 10;
                }

                public int getIconHeight() {
                    return 10;
                }
            });
            JPanel leg = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 6));
            for (JLabel l : new JLabel[] {legend, l2, l3, l4}) {
                l.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
                leg.add(l);
            }
            JPanel out = new JPanel(new BorderLayout());
            out.add(p, BorderLayout.WEST);
            out.add(leg, BorderLayout.CENTER);
            return out;
        }

        private JButton btn(String text, String tip, Runnable r) {
            JButton b = new JButton(text);
            b.setToolTipText(tip);
            b.addActionListener(e -> r.run());
            return b;
        }

        private JButton feederAction(String text, String tip, FeederThrunnable a) {
            JButton b = btn(text, tip, () -> {
                Row r = selected();
                if (r == null) {
                    return;
                }
                OrionFeeder f = feederForSelected();
                if (f != null) {
                    OrionUi.run(text, () -> a.run(f));
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
            // Unbound unit: drive it through a temporary, unregistered feeder object.
            OrionFeeder tmp = new OrionFeeder();
            tmp.setName("unit " + r.info.address);
            tmp.setSerial(r.info.serial);
            tmp.setRail(rail);
            tmp.setPitchMm(0);
            return tmp;
        }

        private Row selected() {
            if (selectedKey == null) {
                return null;
            }
            for (Row r : rows) {
                if (selectedKey.equals(r.key())) {
                    return r;
                }
            }
            return null;
        }

        private void select(String key) {
            selectedKey = key;
            selections.put(rail, key);
            for (FeederTile t : tileList) {
                t.setSelected(t.row.key().equals(key));
            }
            rebuildDetails();
        }

        // ---- details panel for the selected tile

        private void rebuildDetails() {
            details.removeAll();
            Row r = selected();
            JPanel box = new JPanel();
            box.setLayout(new javax.swing.BoxLayout(box, javax.swing.BoxLayout.Y_AXIS));
            box.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            if (r == null) {
                JLabel hint = new JLabel("<html><body style='width:240px;color:gray'>Click a feeder tile to see its "
                        + "details and to feed, peel or identify it.<br><br>Double click a tile to blink its LED.</body></html>");
                hint.setVerticalAlignment(javax.swing.SwingConstants.TOP);
                box.add(hint);
            } else {
                OrionDeviceInfo d = r.info;
                JLabel title = new JLabel(d == null ? "Missing feeder" : "Feeder #" + d.address);
                title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, title.getFont().getSize2D() + 4f));
                FeederTile probe = new FeederTile(r, () -> {
                }, () -> {
                });
                title.setIcon(new OrionLed(probe.ledColor(), 12));
                title.setIconTextGap(8);
                title.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.add(title);
                JLabel st = new JLabel(FeederTile.stateText(r));
                st.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
                st.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.add(st);
                box.add(javax.swing.Box.createVerticalStrut(8));
                JPanel g = new JPanel(new java.awt.GridBagLayout());
                g.setAlignmentX(Component.LEFT_ALIGNMENT);
                int row = 0;
                row = kv(g, row, "Component", d == null ? "" : (d.componentId == 0xFFFF ? "not set"
                        : String.valueOf(d.componentId) + partName(d.componentId)));
                row = kv(g, row, "OpenPnP feeder", r.feeder == null ? (d == null ? "" : "none yet") : r.feeder.getName());
                row = kv(g, row, "Tape width", d == null ? "" : (d.tapeWidthMm == 0xFF ? "unknown" : d.tapeWidthMm + " mm"));
                row = kv(g, row, "Slot X on unit", d == null || Double.isNaN(d.slotXMm) ? "not set"
                        : String.format("%.1f mm", d.slotXMm));
                row = kv(g, row, "Pick X", r.feeder == null ? "" : String.format("%.2f mm", r.feeder.getLocation().getX()));
                row = kv(g, row, "Last answer", d == null ? "" : (d.state == OrionDeviceInfo.State.LOST
                        ? "none for " + Math.max(0, (System.currentTimeMillis() - d.lastSeenMs) / 1000) + " s"
                        : (d.lastPingMs >= 0 ? d.lastPingMs + " ms" : "not pinged yet")));
                row = kv(g, row, "Serial", d == null ? (r.feeder == null ? "" : r.feeder.getSerial()) : d.serial);
                if (d != null && d.note != null && !d.note.isEmpty()) {
                    row = kv(g, row, "Note", d.note);
                }
                box.add(g);
                box.add(javax.swing.Box.createVerticalStrut(10));

                boolean live = d != null && d.state != OrionDeviceInfo.State.LOST;
                JPanel acts = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
                acts.setAlignmentX(Component.LEFT_ALIGNMENT);
                acts.add(feederAction("Identify", "Blink the unit's LEDs", f -> f.identify()));
                acts.add(btn("Ping", "Ping this unit once", () -> {
                    if (d != null) {
                        final OrionBus b = mgr.getBus(rail);
                        OrionUi.run("Ping", () -> {
                            long ms = b.ping(d.address);
                            mgr.addLog(rail, org.openpnp.machine.orion.protocol.OrionBusListener.Direction.INFO,
                                    ms < 0 ? "Ping address " + d.address + ": no reply"
                                            : "Ping address " + d.address + ": " + ms + " ms");
                            mgr.refresh();
                        });
                    }
                }));
                acts.add(feederAction("Feed", "Advance the tape one pitch", f -> f.feedOnce()));
                acts.add(feederAction("Unfeed", "Move the tape back one pitch", f -> f.unfeedOnce()));
                acts.add(feederAction("Peel", "Peel the cover tape", f -> f.peel(false)));
                acts.add(feederAction("Unpeel", "Run the peel motor backwards", f -> f.peel(true)));
                acts.add(btn("Locate here", "The camera is over this feeder's large fiducial: measure and save its X",
                        () -> {
                            if (r.feeder == null) {
                                JOptionPane.showMessageDialog(this, "This unit has no OpenPnP feeder yet. Create one first.");
                                return;
                            }
                            OrionUi.run("Locate here", () -> mgr.locateHere(r.feeder));
                        }));
                acts.add(btn("Create feeder", "Add an OpenPnP feeder for this unit", this::createFeeder));
                for (Component c : acts.getComponents()) {
                    c.setEnabled(live || c instanceof JButton && ((JButton) c).getText().equals("Ping"));
                }
                ((JButton) acts.getComponent(7)).setEnabled(d != null && r.feeder == null);
                ((JButton) acts.getComponent(6)).setEnabled(live && r.feeder != null);
                box.add(acts);
                box.add(javax.swing.Box.createVerticalStrut(8));
                JButton forget = btn("Forget this unit", "Drop it from the table (it comes back if it answers again)",
                        () -> {
                            if (d != null) {
                                mgr.getBus(rail).forgetAddress(d.address);
                            }
                            selectedKey = null;
                            selections.remove(rail);
                            mgr.refresh();
                        });
                forget.setEnabled(d != null);
                forget.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.add(forget);
            }
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, javax.swing.UIManager.getColor(
                    "Component.borderColor") == null ? Color.LIGHT_GRAY : javax.swing.UIManager.getColor(
                            "Component.borderColor")));
            wrap.add(box, BorderLayout.NORTH);
            details.add(wrap, BorderLayout.CENTER);
            details.revalidate();
            details.repaint();
        }

        private String partName(int comp) {
            String part = mgr.getSettings().partFor(comp);
            return part == null || part.isEmpty() ? "" : "  (" + part + ")";
        }

        private int kv(JPanel g, int row, String k, String v) {
            java.awt.GridBagConstraints a = new java.awt.GridBagConstraints();
            a.gridx = 0;
            a.gridy = row;
            a.anchor = java.awt.GridBagConstraints.NORTHWEST;
            a.insets = new java.awt.Insets(1, 0, 1, 10);
            JLabel kl = new JLabel(k);
            kl.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
            g.add(kl, a);
            java.awt.GridBagConstraints b = new java.awt.GridBagConstraints();
            b.gridx = 1;
            b.gridy = row;
            b.weightx = 1;
            b.anchor = java.awt.GridBagConstraints.NORTHWEST;
            b.insets = new java.awt.Insets(1, 0, 1, 0);
            JLabel vl = new JLabel("<html><body style='width:170px'>" + v.replace("<", "&lt;") + "</body></html>");
            if (k.equals("Serial")) {
                vl.setText("<html><body style='width:170px;font-size:9px'>" + v + "</body></html>");
            }
            g.add(vl, b);
            return row + 1;
        }

        // ---- the pending "found a new feeder" bar

        void refreshPending() {
            org.openpnp.machine.orion.OrionBusMonitor.Pending p = mgr.getMonitor().getPending(rail);
            boolean show = p != null;
            if (show) {
                long left = p.scanAtMs == Long.MAX_VALUE ? -1 : Math.max(0, (p.scanAtMs - System.currentTimeMillis()) / 1000);
                pendingText.setText(p.returning
                        ? p.count + " feeder(s) restarted and are being restored"
                                + (left >= 0 ? " in " + left + " s" : "")
                        : "<html>Found <b>" + p.count + " new feeder(s)</b> on this rail. "
                                + (left >= 0 ? "Scanning automatically in <b>" + left + " s</b> unless more are plugged in."
                                        : "Automatic scan cancelled, use Scan rail.") + "</html>");
            }
            if (pendingBar.isVisible() != show) {
                pendingBar.setVisible(show);
                revalidate();
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

            tiles.removeAll();
            tileList.clear();
            int online = 0;
            int unbound = 0;
            int silent = 0;
            int attention = 0;
            for (Row r : rows) {
                final String key = r.key();
                FeederTile t = new FeederTile(r, () -> select(key), () -> {
                    select(key);
                    OrionFeeder f = feederForSelected();
                    if (f != null && r.info != null && r.info.state != OrionDeviceInfo.State.LOST) {
                        OrionUi.run("Identify", () -> f.identify());
                    }
                });
                t.setSelected(key.equals(selectedKey));
                tiles.add(t);
                tileList.add(t);
                if (r.info != null && r.info.state == OrionDeviceInfo.State.ONLINE) {
                    online++;
                } else if (r.info == null || r.info.state == OrionDeviceInfo.State.LOST) {
                    silent++;
                } else {
                    attention++;
                }
                if (r.info != null && r.feeder == null) {
                    unbound++;
                }
            }
            if (rows.isEmpty()) {
                JLabel none = new JLabel("<html><body style='width:420px;color:gray'>No feeder on this rail yet. Plug "
                        + "one in: it is announced here within a few seconds and added automatically, or press "
                        + "<b>Scan rail</b>.</body></html>");
                tiles.add(none);
            }
            tiles.revalidate();
            tiles.repaint();

            counts.setText(String.format("<html><b>%d feeder(s)</b> &nbsp;·&nbsp; %d answering &nbsp;·&nbsp; "
                    + "<span style='color:%s'>%d silent</span> &nbsp;·&nbsp; %d need a look &nbsp;·&nbsp; "
                    + "%d without OpenPnP feeder</html>", rows.size(), online, silent > 0 ? "#D93025" : "gray",
                    silent, attention, unbound));
            String problem = mgr.railCheckProblem(rail);
            org.openpnp.machine.orion.OrionManager.RailCheck check = mgr.getRailCheck(rail);
            boolean ok = problem == null && check != null;
            verified.setIcon(new OrionLed(ok ? OrionLed.GREEN : OrionLed.AMBER, 9));
            verified.setIconTextGap(6);
            verified.setText(ok ? "Verified by vision at " + new java.text.SimpleDateFormat("HH:mm:ss").format(
                    new java.util.Date(check.time))
                    : "<html><body style='width:560px'>Not verified by vision: " + (problem == null ? "" : problem) + "</body></html>");
            createAll.setText(unbound > 0 ? "Create feeders for " + unbound + " unbound" : "Create feeders for all unbound");
            createAll.setEnabled(unbound > 0);
            refreshPending();
            if (selectedKey != null && selected() == null) {
                selectedKey = null;
                selections.remove(rail);
            }
            rebuildDetails();
        }

        private boolean seenElsewhere(OrionFeeder f) {
            for (OrionBus b : mgr.getBuses()) {
                if (b.getRail() != rail && b.findBySerial(f.getSerial()) != null) {
                    return true;
                }
            }
            return false;
        }

        private void railPower() {
            Object[] opts = {"Power ON", "Power OFF", "Cancel"};
            int c = JOptionPane.showOptionDialog(this, "Rail " + (rail + 1)
                    + " supply (host board only; has no effect on a plain USB-RS485 adapter).",
                    "Rail power", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    opts, opts[2]);
            if (c == 0 || c == 1) {
                OrionUi.run("Rail power", () -> {
                    boolean ok = mgr.setRailPower(rail, c == 0);
                    if (!ok) {
                        throw new Exception("This interface cannot switch rail power.");
                    }
                });
            }
        }
    }

    interface FeederThrunnable {
        void run(OrionFeeder f) throws Exception;
    }
}
