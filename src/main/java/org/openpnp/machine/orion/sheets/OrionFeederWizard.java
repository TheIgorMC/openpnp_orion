package org.openpnp.machine.orion.sheets;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.jdesktop.beansbinding.AutoBinding.UpdateStrategy;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.LocationButtonsPanel;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.gui.support.IdentifiableListCellRenderer;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.MutableLocationProxy;
import org.openpnp.gui.support.PartsComboBoxModel;
import org.openpnp.machine.orion.OrionFeeder;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;

/** Per-feeder tab: configuration (Apply/Reset like other feeders) plus live hardware controls. */
public class OrionFeederWizard extends AbstractConfigurationWizard implements OrionManager.Listener {
    private final OrionFeeder feeder;

    private final JLabel serialLabel = new JLabel();
    private final JLabel stateLabel = new JLabel();
    private final JComboBox<Part> partCb = new JComboBox<>();
    private final JTextField pitchTf = new JTextField(6);
    private final JTextField peelRateTf = new JTextField(6);
    private final JTextField peelTimeTf = new JTextField(6);
    private final JTextField ledTf = new JTextField(6);
    private final JTextField retriesTf = new JTextField(6);
    private final JTextField xTf = new JTextField(8);
    private final JTextField yTf = new JTextField(8);
    private final JTextField zTf = new JTextField(8);
    private final JTextField rotTf = new JTextField(8);
    private final LocationButtonsPanel locationPanel;

    private final JComboBox<OrionFeeder.VisionMode> visionModeCb = new JComboBox<>(
            OrionFeeder.VisionMode.values());
    private final JComboBox<Part> fiducialPartCb = new JComboBox<>();
    private final javax.swing.JCheckBox xOnlyCb = new javax.swing.JCheckBox("Only correct X");
    private final JTextField maxCorrTf = new JTextField(6);
    private final JTextField fxTf = new JTextField(8);
    private final JTextField fyTf = new JTextField(8);
    private final JTextField fzTf = new JTextField(8);
    private final LocationButtonsPanel fiducialPanel;
    private final JLabel visionResult = new JLabel(" ");
    private final JLabel slotLabel = new JLabel(" ");
    private final javax.swing.JCheckBox followCb = new javax.swing.JCheckBox(
            "Shift pick X when the unit's slot X changes");

    public OrionFeederWizard(OrionFeeder feeder) {
        this.feeder = feeder;
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        // ---- hardware binding
        JPanel hw = OrionUi.grid();
        hw.setBorder(BorderFactory.createTitledBorder("Hardware"));
        JPanel serialRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        serialRow.add(serialLabel);
        JButton bind = new JButton("Bind to unit...");
        bind.setToolTipText("Pick one of the units found on the rails and tie this feeder to it");
        bind.addActionListener(e -> bindToUnit());
        serialRow.add(bind);
        JButton unbind = new JButton("Unbind");
        unbind.addActionListener(e -> {
            feeder.setSerial(null);
            refreshState();
        });
        serialRow.add(unbind);
        OrionUi.row(hw, 0, "Serial", serialRow);
        OrionUi.row(hw, 1, "State", stateLabel);
        contentPanel.add(hw);

        // ---- settings
        JPanel cfg = OrionUi.grid();
        cfg.setBorder(BorderFactory.createTitledBorder("Settings (Apply pushes them to the unit)"));
        partCb.setModel(new PartsComboBoxModel());
        partCb.setRenderer(new IdentifiableListCellRenderer<Part>());
        OrionUi.row(cfg, 0, "Part", partCb);
        OrionUi.row(cfg, 1, "Pitch (mm, even 2..24)", pitchTf);
        OrionUi.row(cfg, 2, "Peel coupling (ms of peel per mm, 0=off, -1=leave)", peelRateTf);
        OrionUi.row(cfg, 3, "Peel time for Peel button (ms, -1=leave)", peelTimeTf);
        OrionUi.row(cfg, 4, "LED brightness (1..255, 0=leave)", ledTf);
        OrionUi.row(cfg, 5, "Feed retries", retriesTf);
        contentPanel.add(cfg);

        // ---- location
        JPanel loc = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        loc.setBorder(BorderFactory.createTitledBorder("Pick location (X = position along the rail)"));
        loc.add(new JLabel("X"));
        loc.add(xTf);
        loc.add(new JLabel("Y"));
        loc.add(yTf);
        loc.add(new JLabel("Z"));
        loc.add(zTf);
        loc.add(new JLabel("Rot"));
        loc.add(rotTf);
        locationPanel = new LocationButtonsPanel(xTf, yTf, zTf, rotTf);
        loc.add(locationPanel);
        contentPanel.add(loc);

        // ---- slot position
        JPanel slot = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        slot.setBorder(BorderFactory.createTitledBorder("Slot position X stored on the unit (mm along the rail)"));
        JTextField slotTf = new JTextField(7);
        slot.add(slotTf);
        slot.add(slotLabel);
        JButton slotRead = new JButton("Read");
        slotRead.addActionListener(e -> OrionUi.run("Read slot position", () -> feeder.readSlotPosition(),
                v -> {
                    slotTf.setText(Double.isNaN(v) ? "" : String.format("%.1f", v));
                    refreshState();
                }));
        slot.add(slotRead);
        JButton slotWrite = new JButton("Write to unit");
        slotWrite.addActionListener(e -> {
            double v;
            try {
                v = slotTf.getText().trim().isEmpty() ? Double.NaN : Double.parseDouble(slotTf.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Enter the slot X in mm, or leave empty to clear.");
                return;
            }
            final double mm = v;
            OrionUi.run("Write slot position", () -> feeder.writeSlotPosition(mm));
        });
        slot.add(slotWrite);
        JButton teach = new JButton("Teach (current slot = taught)");
        teach.setToolTipText("Remember the unit's slot X as the one the pick location was taught at");
        teach.addActionListener(e -> OrionUi.run("Teach slot position", () -> feeder.teachSlotPosition()));
        slot.add(teach);
        slot.add(followCb);
        contentPanel.add(slot);

        // ---- vision
        JPanel vis = OrionUi.grid();
        vis.setBorder(BorderFactory.createTitledBorder("Vision: fine X position from a fiducial"));
        fiducialPartCb.setModel(new PartsComboBoxModel());
        fiducialPartCb.setRenderer(new IdentifiableListCellRenderer<Part>());
        OrionUi.row(vis, 0, "When", visionModeCb);
        OrionUi.row(vis, 1, "Fiducial part (its vision settings are used)", fiducialPartCb);
        JPanel fl = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fl.add(new JLabel("X"));
        fl.add(fxTf);
        fl.add(new JLabel("Y"));
        fl.add(fyTf);
        fl.add(new JLabel("Z"));
        fl.add(fzTf);
        fiducialPanel = new LocationButtonsPanel(fxTf, fyTf, fzTf, new JTextField());
        fl.add(fiducialPanel);
        OrionUi.row(vis, 2, "Fiducial location (taught with the pick location)", fl);
        OrionUi.row(vis, 3, "", xOnlyCb);
        OrionUi.row(vis, 4, "Reject corrections larger than (mm)", maxCorrTf);
        JPanel vb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton locate = new JButton("Locate fiducial now");
        locate.addActionListener(e -> OrionUi.run("Locate fiducial", () -> feeder.locateByFiducial(),
                d -> visionResult.setText(String.format("Correction dX=%.3f dY=%.3f mm (not yet permanent)",
                        d.getX(), d.getY()))));
        vb.add(locate);
        JButton commit = new JButton("Make correction permanent");
        commit.setToolTipText("Move the taught pick location and fiducial position by the last correction");
        commit.addActionListener(e -> {
            feeder.commitVisionCorrection();
            visionResult.setText("Correction applied to the pick location.");
            loadFromModel();
        });
        vb.add(commit);
        vb.add(visionResult);
        OrionUi.row(vis, 5, "", vb);
        contentPanel.add(vis);

        // ---- live controls
        JPanel live = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        live.setBorder(BorderFactory.createTitledBorder("Live control"));
        live.add(button("Identify", "Blink the unit's white LED", () -> feeder.identify()));
        live.add(button("Feed", "Advance one pitch (with coupled peel)", () -> feeder.feedOnce()));
        live.add(button("Unfeed", "Back up one pitch (peel reverses first)", () -> feeder.unfeedOnce()));
        live.add(button("Peel", "Peel motor forward for the calibrated time", () -> feeder.peel(false)));
        live.add(button("Unpeel", "Peel motor in reverse for the calibrated time", () -> feeder.peel(true)));
        live.add(button("Stop", "Brake both motors", () -> feeder.stop()));
        contentPanel.add(live);
        JPanel jog = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        jog.setBorder(BorderFactory.createTitledBorder("Sprocket jog / tape zero"));
        live = jog;
        live.add(button("Jog -1 mm", null, () -> feeder.jogTenthsMm(-10)));
        live.add(button("Jog +1 mm", null, () -> feeder.jogTenthsMm(10)));
        live.add(button("Jog -0.1", null, () -> feeder.jogTenthsMm(-1)));
        live.add(button("Jog +0.1", null, () -> feeder.jogTenthsMm(1)));
        live.add(button("Zero here", "Capture the current sprocket position as the tape zero",
                () -> feeder.zeroHere()));
        JButton read = new JButton("Read from unit");
        read.setToolTipText("Load pitch / peel settings from the unit into this form's feeder");
        read.addActionListener(e -> OrionUi.run("Read settings", () -> feeder.readSettingsFromUnit()
        ));
        live.add(read);
        contentPanel.add(live);
        for (java.awt.Component c : contentPanel.getComponents()) {
            OrionUi.compact((javax.swing.JComponent) c);
        }

        locationPanel.setBaseLocation(feeder.getLocation());
        OrionManager.get().addListener(this);
        refreshState();
    }

    private JButton button(String text, String tip, org.openpnp.util.UiUtils.Thrunnable action) {
        JButton b = new JButton(text);
        if (tip != null) {
            b.setToolTipText(tip);
        }
        b.addActionListener(e -> OrionUi.run(text, action));
        return b;
    }

    private void bindToUnit() {
        OrionManager mgr = OrionManager.get();
        if (!mgr.isConnected()) {
            JOptionPane.showMessageDialog(this, "Not connected. Use the Orion Bus Manager tab to connect and scan first.");
            return;
        }
        List<OrionDeviceInfo> free = new ArrayList<>();
        for (OrionBus b : mgr.getBuses()) {
            for (OrionDeviceInfo d : b.getDevices()) {
                if (d.state != OrionDeviceInfo.State.LOST && boundFeeder(d.serial) == null) {
                    free.add(d);
                }
            }
        }
        if (free.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No unbound units found. Scan the rail first.");
            return;
        }
        String[] labels = new String[free.size()];
        for (int i = 0; i < free.size(); i++) {
            OrionDeviceInfo d = free.get(i);
            labels[i] = String.format("Rail %d / address %d / %s", d.rail + 1, d.address, d.serial);
        }
        Object choice = JOptionPane.showInputDialog(MainFrame.get(), "Unit to bind to this feeder:",
                "Bind", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (choice == null) {
            return;
        }
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(choice)) {
                feeder.setSerial(free.get(i).serial);
                feeder.setRail(free.get(i).rail);
                OrionUi.run("Identify", () -> feeder.identify());
            }
        }
        refreshState();
    }

    static OrionFeeder boundFeeder(String serial) {
        if (serial == null) {
            return null;
        }
        for (Feeder f : Configuration.get().getMachine().getFeeders()) {
            if (f instanceof OrionFeeder && serial.equalsIgnoreCase(((OrionFeeder) f).getSerial())) {
                return (OrionFeeder) f;
            }
        }
        return null;
    }

    private void refreshState() {
        OrionUi.onEdt(() -> {
            String serial = feeder.getSerial();
            serialLabel.setText(serial == null ? "(not bound)" : serial);
            if (serial == null) {
                stateLabel.setText("Not bound to a unit");
                return;
            }
            OrionManager mgr = OrionManager.get();
            if (!mgr.isConnected()) {
                stateLabel.setText("Bus not connected");
                return;
            }
            for (OrionBus b : mgr.getBuses()) {
                OrionDeviceInfo d = b.findBySerial(serial);
                if (d != null) {
                    stateLabel.setText(String.format("%s on rail %d, address %d %s", d.state,
                            d.rail + 1, d.address, d.note.isEmpty() ? "" : "(" + d.note + ")"));
                    double t = feeder.getTaughtSlotXMm();
                    String msg = Double.isNaN(d.slotXMm) ? "unit slot X: not set"
                            : String.format("unit slot X: %.1f mm", d.slotXMm);
                    if (!Double.isNaN(t) && !Double.isNaN(d.slotXMm) && Math.abs(d.slotXMm - t) > 0.05) {
                        msg += String.format("  -- MOVED %.1f mm since teaching", d.slotXMm - t);
                    }
                    slotLabel.setText(msg);
                    return;
                }
            }
            stateLabel.setText("Not seen on any rail (scan?)");
        });
    }

    @Override
    public void orionChanged() {
        refreshState();
    }

    @Override
    public void createBindings() {
        IntegerConverter intConverter = new IntegerConverter();
        DoubleConverter doubleConverter = new DoubleConverter(Configuration.get().getLengthDisplayFormat());
        DoubleConverter plain = new DoubleConverter("%.1f");
        LengthConverter lengthConverter = new LengthConverter();

        addWrappedBinding(feeder, "part", partCb, "selectedItem");
        addWrappedBinding(feeder, "pitchMm", pitchTf, "text", intConverter);
        addWrappedBinding(feeder, "peelMsPerMm", peelRateTf, "text", plain);
        addWrappedBinding(feeder, "peelTimeMs", peelTimeTf, "text", intConverter);
        addWrappedBinding(feeder, "ledBrightness", ledTf, "text", intConverter);
        addWrappedBinding(feeder, "feedRetries", retriesTf, "text", intConverter);

        MutableLocationProxy location = new MutableLocationProxy();
        addWrappedBinding(feeder, "location", location, "location");
        bind(UpdateStrategy.READ_WRITE, location, "lengthX", xTf, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, location, "lengthY", yTf, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, location, "lengthZ", zTf, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, location, "rotation", rotTf, "text", doubleConverter);
        bind(UpdateStrategy.READ, location, "location", locationPanel, "baseLocation");

        addWrappedBinding(feeder, "followSlotPosition", followCb, "selected");
        addWrappedBinding(feeder, "visionMode", visionModeCb, "selectedItem");
        addWrappedBinding(feeder, "fiducialPart", fiducialPartCb, "selectedItem");
        addWrappedBinding(feeder, "visionXOnly", xOnlyCb, "selected");
        addWrappedBinding(feeder, "maxCorrectionMm", maxCorrTf, "text", plain);
        MutableLocationProxy fid = new MutableLocationProxy();
        addWrappedBinding(feeder, "fiducialNominal", fid, "location");
        bind(UpdateStrategy.READ_WRITE, fid, "lengthX", fxTf, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, fid, "lengthY", fyTf, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, fid, "lengthZ", fzTf, "text", lengthConverter);
        bind(UpdateStrategy.READ, fid, "location", fiducialPanel, "baseLocation");
    }

    @Override
    protected void saveToModel() {
        super.saveToModel();
        // Live update: if the unit is reachable, push the new settings right away.
        if (feeder.getSerial() != null && OrionManager.get().isConnected()) {
            OrionUi.run("Push settings", () -> feeder.link());
        }
    }

    @Override
    public void dispose() {
        OrionManager.get().removeListener(this);
        super.dispose();
    }
}
