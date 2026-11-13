package org.openpnp.machine.orion.sheets;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.UIManager;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.ConveyorStatus;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionConveyor;
import org.openpnp.machine.orion.protocol.OrionError;
import org.openpnp.machine.orion.protocol.OrionException;

/**
 * The Conveyor tab: the belt drawn from above, the state in words, prompts (board inserted, board waiting, width not
 * confirmed) and the manual controls grouped by what they do.
 */
public class OrionConveyorPanel extends JPanel implements OrionManager.Listener {
    private final OrionManager mgr = OrionManager.get();
    private final ConveyorView view = new ConveyorView();
    private final JLabel title = new JLabel();
    private final JLabel subtitle = new JLabel();
    private final JPanel banners = new JPanel();
    private final JPanel details = new JPanel(new BorderLayout());
    private final JPanel controls = new JPanel(new GridLayout(1, 4, 10, 0));
    private final JLabel absent = new JLabel();
    private final JPanel body = new JPanel(new BorderLayout());
    private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(100.0, 10, 400, 0.5));
    private final java.util.List<JButton> motionButtons = new java.util.ArrayList<>();
    private volatile ConveyorStatus last;
    private volatile int unloadDir;
    private volatile boolean notNow;
    private boolean configRead;
    private final Timer poll = new Timer(500, e -> pollNow());

    public OrionConveyorPanel() {
        setLayout(new BorderLayout());
        JPanel head = new JPanel(new BorderLayout());
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                UIManager.getColor("Component.borderColor") == null ? Color.LIGHT_GRAY
                        : UIManager.getColor("Component.borderColor")), BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, title.getFont().getSize2D() + 1f));
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        left.add(title);
        left.add(subtitle);
        head.add(left, BorderLayout.CENTER);
        JPanel hr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        hr.add(button("Find conveyor", "Scan the conveyor's rail for it", this::findConveyor));
        hr.add(button("Identify", "Blink the conveyor's LED", () -> OrionConveyorUi.run("identify",
                c -> c.identify(3), null, null)));
        head.add(hr, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        banners.setLayout(new BoxLayout(banners, BoxLayout.Y_AXIS));
        JPanel center = new JPanel(new BorderLayout());
        center.add(banners, BorderLayout.NORTH);
        center.add(view, BorderLayout.CENTER);
        details.setPreferredSize(new java.awt.Dimension(400, 100));
        body.add(center, BorderLayout.CENTER);
        body.add(details, BorderLayout.EAST);
        buildControls();
        body.add(controls, BorderLayout.SOUTH);
        absent.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        add(body, BorderLayout.CENTER);
        refresh(mgr.getConveyorStatus());
    }

    @Override
    public void addNotify() {
        super.addNotify();
        mgr.addListener(this);
        poll.start();
        refresh(mgr.getConveyorStatus());
    }

    @Override
    public void removeNotify() {
        poll.stop();
        mgr.removeListener(this);
        super.removeNotify();
    }

    @Override
    public void orionChanged() {
        OrionUi.onEdt(() -> refresh(mgr.getConveyorStatus()));
    }

    private JButton button(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(e -> r.run());
        return b;
    }

    // ------------------------------------------------------------------ polling

    private void pollNow() {
        OrionConveyor c = mgr.getConveyor();
        if (c == null || OrionConveyorUi.isBusy()) {
            if (c == null) {
                refresh(null);
            }
            return;
        }
        OrionConveyorUi.run("status", cc -> {
            ConveyorStatus s = cc.getBus().pollConveyorStatus();
            mgr.setConveyorStatus(s);
            OrionUi.onEdt(() -> refresh(s));
        }, null, null);
    }

    private void afterAction() {
        pollNow();
    }

    /** Status of a running action, shown while the belt moves. */
    private void live(ConveyorStatus s) {
        mgr.setConveyorStatus(s);
        OrionUi.onEdt(() -> refresh(s));
    }

    // ------------------------------------------------------------------ controls

    private void buildControls() {
        controls.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        JPanel board = group("Board");
        board.add(motion("Load board", "Run the board at the entry to the stop and clamp it", this::load));
        board.add(motion("Measure", "Pass the board over the sensor to find its length, no positioning",
                () -> act("measure", c -> {
                    c.measure();
                    c.awaitIdle(60000, this::live);
                })));
        board.add(motion("Go to stop", "Move the board's leading edge to the pin", () -> act("go to stop", c -> {
            c.gotoStop();
            c.awaitIdle(60000, this::live);
        })));
        board.add(motion("Unload", "Release the clamp and take the board out in the configured direction", this::unload));
        board.add(motion("Nothing on belt", "Tell the conveyor the belt is empty (forget the board)",
                () -> act("clear", OrionConveyor::clear)));

        JPanel hold = group("Clamp and stopper");
        hold.add(motion("Clamp", "Close the cams on the board", () -> act("clamp", c -> {
            c.clamp(true);
            c.awaitIdle(10000, this::live);
        })));
        hold.add(motion("Release", "Open the cams", () -> act("release", c -> {
            c.clamp(false);
            c.awaitIdle(10000, this::live);
        })));
        hold.add(motion("Stopper out", "Put the pin in the board's way", () -> act("stopper", c -> {
            c.stopper(true);
            c.awaitIdle(10000, this::live);
        })));
        hold.add(motion("Stopper in", "Pull the pin back", () -> act("stopper", c -> {
            c.stopper(false);
            c.awaitIdle(10000, this::live);
        })));

        JPanel belt = group("Belt");
        for (int mm : new int[] {-10, -1, 1, 10}) {
            belt.add(motion((mm > 0 ? "+" : "") + mm + " mm", "Move the belt " + mm + " mm (refused while clamped)",
                    () -> act("move", c -> {
                        c.move(mm * 100);
                        c.awaitIdle(30000, this::live);
                    })));
        }
        JButton stop = button("STOP", "Stop the belt and the width axis at once", () -> OrionConveyorUi.run("stop",
                OrionConveyor::stop, null, this::afterAction));
        stop.setForeground(Color.WHITE);
        stop.setBackground(new Color(0xD93025));
        belt.add(stop);

        JPanel width = group("Width");
        JPanel wr = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        wr.add(widthSpinner);
        wr.add(new JLabel("mm"));
        width.add(wr);
        width.add(motion("Set width", "Move the side rail to this width", () -> widthWindow(false, true)));
        width.add(motion("Home width", "Run the width axis to its switch", () -> widthWindow(true, false)));
        width.add(button("I set it by hand", "No motion: tell the conveyor the width you adjusted by hand",
                () -> widthWindow(false, false)));

        controls.add(board);
        controls.add(hold);
        controls.add(belt);
        controls.add(width);
    }

    private JPanel group(String name) {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(name),
                BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        return p;
    }

    private JButton motion(String text, String tip, Runnable r) {
        JButton b = button(text, tip, r);
        motionButtons.add(b);
        return b;
    }

    private void act(String what, OrionConveyorUi.Action a) {
        OrionConveyorUi.run(what, a, this::live, this::afterAction);
    }

    private void load() {
        OrionConveyorUi.loadBoard(this::live, this::afterAction);
    }

    private void unload() {
        ConveyorStatus s = last;
        if (s != null && s.boardWaiting() && s.entryBlocked() && unloadDir == 0) {
            JOptionPane.showMessageDialog(MainFrame.get(), "A board is waiting at the entry and blocks the way "
                    + "out. Take it away first, then unload.", "Conveyor", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        act("unload", c -> {
            ConveyorStatus r = c.unloadBoard(this::live);
            mgr.addLog(0, org.openpnp.machine.orion.protocol.OrionBusListener.Direction.INFO,
                    "Conveyor: board unloaded" + (r.boardWaiting() ? ", the next board is ready" : ""));
        });
    }

    private void findConveyor() {
        OrionUi.run("Find conveyor", () -> {
            mgr.connectIfNeeded();
            for (OrionBus b : mgr.getBuses()) {
                b.scan(null);
            }
            mgr.refresh();
        });
    }

    /**
     * The clear window: before the width moves, the user says whether the belt is empty. Answers: clear (go),
     * a board is on it (remove it, ask again) or the width was set by hand (no motion).
     */
    private void widthWindow(boolean home, boolean toValue) {
        Object[] opts = {"Yes, it is clear", "A board is on it", "I set the width by hand", "Cancel"};
        boolean manualOnly = !home && !toValue;
        while (true) {
            ConveyorStatus s = last;
            String warn = s == null ? "" : (s.clamped() ? "\nThe clamp is engaged." : "")
                    + (s.entryBlocked() ? "\nThe entry sensor sees a board." : "")
                    + (s.boardLoaded() ? "\nThe conveyor believes a board is loaded." : "");
            if (manualOnly) {
                break;
            }
            int a = JOptionPane.showOptionDialog(MainFrame.get(), "Look at the conveyor window. Is the belt clear of "
                    + "boards?" + warn, "Clear window", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    opts, opts[1]);
            if (a == 0) {
                final int w = (int) Math.round(((Number) widthSpinner.getValue()).doubleValue() * 10);
                if (!home) {
                    String warn2 = OrionConveyorUi.sizeWarning(last, (double) w);
                    if (warn2 != null && JOptionPane.showConfirmDialog(MainFrame.get(), warn2 + "\n\nSet this width "
                            + "anyway?", "Width does not fit the board", JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                act(home ? "home width" : "set width", c -> {
                    if (home) {
                        c.homeWidth(true);
                    } else {
                        c.setWidth(w, true);
                    }
                    c.awaitIdle(60000, this::live);
                });
                return;
            } else if (a == 1) {
                JOptionPane.showMessageDialog(MainFrame.get(), "Release the clamp and take the board off the belt "
                        + "(Unload does it), then press OK.", "Clear window", JOptionPane.INFORMATION_MESSAGE);
                pollNow();
                continue;
            } else if (a == 2) {
                break;
            } else {
                return;
            }
        }
        String v = JOptionPane.showInputDialog(MainFrame.get(), "Width you set by hand (mm):",
                String.format("%.1f", ((Number) widthSpinner.getValue()).doubleValue()));
        if (v != null) {
            try {
                final int w = (int) Math.round(Double.parseDouble(v.trim().replace(',', '.')) * 10);
                act("define width", c -> c.defineWidth(w));
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(MainFrame.get(), "Not a number: " + v);
            }
        }
    }

    // ------------------------------------------------------------------ display

    private void refresh(ConveyorStatus s) {
        last = s;
        OrionConveyor c = mgr.getConveyor();
        boolean found = c != null;
        if (found && !configRead) {
            configRead = true;
            OrionConveyorUi.run("read settings", cc -> {
                int stop = cc.getConfig(OrionConveyor.CFG_STOP_DISTANCE);
                int dir = cc.getConfig(OrionConveyor.CFG_UNLOAD_DIR);
                unloadDir = dir;
                OrionUi.onEdt(() -> view.setGeometry(stop / 100.0, cc.hasStopper(), cc.hasClamp()));
            }, null, null);
        }
        if (!found) {
            configRead = false;
        }
        body.setVisible(true);
        view.setStatus(found ? s : null);
        title.setIcon(new OrionLed(!found ? OrionLed.RED : (s != null && (s.state == ConveyorStatus.ERROR
                || s.driverFault()) ? OrionLed.AMBER : OrionLed.GREEN), 12));
        title.setIconTextGap(8);
        title.setText(found ? "Conveyor on rail " + (c.getBus().getRail() + 1) + "   ·   address "
                + c.getAddress() : "No conveyor found");
        subtitle.setText(!found ? "It is looked for at address 240 on rail " + (mgr.getSettings().getConveyorRail() + 1)
                + " when the rail is scanned." : s == null ? "waiting for the first status..." : stateText(s));
        for (JButton b : motionButtons) {
            b.setEnabled(found);
        }
        rebuildBanners(found ? s : null);
        rebuildDetails(found ? s : null);
    }

    private static String stateText(ConveyorStatus s) {
        switch (s.state) {
            case ConveyorStatus.BUSY:
                return "Moving...";
            case ConveyorStatus.ERROR:
                return "Last action failed: " + OrionError.fromCode(s.lastErr).description;
            case ConveyorStatus.BOARD_AT_ENTRY:
                return s.boardLoaded() ? "A board waits at the entry" : "A board is at the entry";
            default:
                return s.boardLoaded() ? (s.clamped() ? "Board loaded and clamped" : "Board loaded") : "Idle, no board";
        }
    }

    private void banner(String text, String... buttons) {
        JPanel b = new JPanel(new BorderLayout(8, 0));
        b.setBackground(new Color(0xFFF4CE));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xE8C860)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JLabel l = new JLabel("<html>" + text + "</html>");
        l.setForeground(new Color(0x5C4400));
        b.add(l, BorderLayout.CENTER);
        banners.add(b);
        banners.add(javax.swing.Box.createVerticalStrut(2));
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        bp.setOpaque(false);
        for (int i = 0; i < buttons.length; i++) {
            final int k = i;
            JButton x = new JButton(buttons[i]);
            x.addActionListener(e -> bannerAction(text, k));
            bp.add(x);
        }
        b.add(bp, BorderLayout.EAST);
    }

    private void bannerAction(String text, int k) {
        if (text.contains("inserted") || text.contains("next board is ready")) {
            if (k == 0) {
                load();
            } else {
                notNow = true;
                refresh(last);
            }
        } else if (text.contains("width")) {
            widthWindow(true, false);
        }
    }

    private void rebuildBanners(ConveyorStatus s) {
        banners.removeAll();
        if (s != null) {
            boolean loadable = !s.boardLoaded() && !s.busy() && (s.insertedBoard() || s.boardWaiting());
            if (!loadable) {
                notNow = false;
            }
            if (loadable && !notNow) {
                banner(s.boardWaiting() ? "The <b>next board is ready</b> on the belt." : "A board was <b>inserted</b>.",
                        "Load it", "Not now");
            }
            if (s.boardLoaded() && s.boardWaiting()) {
                banner("A board is <b>waiting at the entry</b>. " + (unloadDir == 0
                        ? "It blocks the way out: take it away before unloading."
                        : "It will be the next board after this one leaves."));
            }
            if (!s.widthConfirmed() && s.width != ConveyorStatus.UNKNOWN_WIDTH) {
                banner("The <b>width</b> is the last saved value (" + String.format("%.1f", s.widthMm())
                        + " mm), not confirmed since power-up.", "Clear window...");
            }
            String size = OrionConveyorUi.sizeWarning(s, null);
            if (size != null) {
                banner("<b>Check the board.</b> " + size.replace("<", "&lt;"));
            }
            if (s.driverFault()) {
                banner("<b>Motor driver fault</b> reported by the conveyor.");
            }
        }
        banners.revalidate();
        banners.repaint();
    }

    /** Expected board size, tolerance, guide clearance and the edge strip. */
    private JPanel sizePanel() {
        org.openpnp.machine.orion.OrionSettings st = mgr.getSettings();
        JPanel p = new JPanel(new java.awt.GridBagLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Board size check"),
                BorderFactory.createEmptyBorder(0, 4, 4, 4)));
        javax.swing.JCheckBox on = new javax.swing.JCheckBox("Warn when the board does not fit", st.isBoardSizeCheck());
        on.addActionListener(e -> {
            st.setBoardSizeCheck(on.isSelected());
            refresh(last);
        });
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = java.awt.GridBagConstraints.WEST;
        p.add(on, c);
        int r = 1;
        r = sizeRow(p, r, "Sides A / B (mm)", sp(st.getExpectedBoardA(), 0, 1000, 1, v -> {
            st.setExpectedBoardA(v);
            refresh(last);
        }), sp(st.getExpectedBoardB(), 0, 1000, 1, v -> {
            st.setExpectedBoardB(v);
            refresh(last);
        }));
        OrionConveyorUi.Expected ex = OrionConveyorUi.expected();
        JLabel src = new JLabel(ex == null ? "from the job (none open)" : String.format("%.1f x %.1f mm, %s", ex.a,
                ex.b, ex.source));
        src.setForeground(UIManager.getColor("Label.disabledForeground"));
        r = sizeRow(p, r, "Expected", src, null);
        r = sizeRow(p, r, "Tolerance / clearance", sp(st.getBoardSizeTolMm(), 0.1, 50, 0.5, v -> {
            st.setBoardSizeTolMm(v);
            refresh(last);
        }), sp(st.getWidthClearanceMm(), 0, 10, 0.1, v -> {
            st.setWidthClearanceMm(v);
            refresh(last);
        }));
        r = sizeRow(p, r, "Edge strip (mm)", sp(st.getBoardEdgeMm(), 0, 30, 0.5, v -> {
            st.setBoardEdgeMm(v);
            refresh(last);
        }), null);
        if (ex != null && st.getBoardEdgeMm() > 0) {
            JLabel u = new JLabel(String.format("usable %.1f x %.1f mm", Math.max(0, ex.a - 2 * st.getBoardEdgeMm()),
                    Math.max(0, ex.b - 2 * st.getBoardEdgeMm())));
            u.setForeground(UIManager.getColor("Label.disabledForeground"));
            r = sizeRow(p, r, "", u, null);
        }
        return p;
    }

    private JSpinner sp(double v, double min, double max, double step, java.util.function.DoubleConsumer set) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(v, min, max, step));
        s.setPreferredSize(new java.awt.Dimension(70, s.getPreferredSize().height));
        s.addChangeListener(e -> set.accept(((Number) s.getValue()).doubleValue()));
        return s;
    }

    private int sizeRow(JPanel p, int row, String label, Component a, Component b) {
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.insets = new java.awt.Insets(1, 0, 1, 8);
        JLabel l = new JLabel(label);
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        l.setMinimumSize(l.getPreferredSize());
        p.add(l, c);
        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cell.add(a);
        if (b != null) {
            cell.add(b);
        }
        c = new java.awt.GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.anchor = java.awt.GridBagConstraints.WEST;
        p.add(cell, c);
        return row + 1;
    }

    private void kv(JPanel g, int row, String k, String v) {
        java.awt.GridBagConstraints a = new java.awt.GridBagConstraints();
        a.gridx = 0;
        a.gridy = row;
        a.anchor = java.awt.GridBagConstraints.NORTHWEST;
        a.insets = new java.awt.Insets(1, 0, 1, 10);
        JLabel kl = new JLabel(k);
        kl.setForeground(UIManager.getColor("Label.disabledForeground"));
        g.add(kl, a);
        java.awt.GridBagConstraints b = new java.awt.GridBagConstraints();
        b.gridx = 1;
        b.gridy = row;
        b.weightx = 1;
        b.anchor = java.awt.GridBagConstraints.NORTHWEST;
        g.add(new JLabel(v), b);
    }

    private void rebuildDetails(ConveyorStatus s) {
        details.removeAll();
        JPanel g = new JPanel(new java.awt.GridBagLayout());
        g.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
        if (s != null) {
            int r = 0;
            kv(g, r++, "State", stateText(s));
            kv(g, r++, "Board", s.boardLoaded() ? "loaded" : s.boardWaiting() ? "next ready" : s.entryBlocked()
                    ? "at the entry" : "none");
            kv(g, r++, "Length", s.length >= 0 ? String.format("%.1f mm", s.lengthMm()) : "not measured");
            kv(g, r++, "Position", s.posValid() ? String.format("%.1f mm", s.posMm()) : "unknown");
            kv(g, r++, "Width", s.width == ConveyorStatus.UNKNOWN_WIDTH ? "unknown" : String.format("%.1f mm%s",
                    s.widthMm(), s.widthConfirmed() ? "" : " (saved)"));
            kv(g, r++, "Entry sensor", s.entryBlocked() ? "blocked" : "free");
            kv(g, r++, "Clamp", s.clamped() ? "engaged" : "open");
            kv(g, r++, "Stopper", s.stopperOut() ? "out" : "in");
            if (s.stopSensor()) {
                kv(g, r++, "Stop sensor", "board at the pin");
            }
            kv(g, r++, "Unload", unloadDir == 0 ? "back out of the entry" : "forward, far side");
            if (s.lastErr != 0) {
                kv(g, r++, "Last error", OrionError.fromCode(s.lastErr).description);
            }
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, UIManager.getColor("Component.borderColor") == null
                ? Color.LIGHT_GRAY : UIManager.getColor("Component.borderColor")));
        wrap.add(g, BorderLayout.NORTH);
        details.add(wrap, BorderLayout.CENTER);
        details.add(sizePanel(), BorderLayout.SOUTH);
        details.revalidate();
        details.repaint();
    }
}
