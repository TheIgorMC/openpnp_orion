package org.openpnp.machine.orion.sheets;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.OrionManager.Notice;

/**
 * Small non-blocking messages in the lower right corner of the main window: "found a new feeder",
 * "feeder lost", "bus reset". They never take focus and go away by themselves, so they can not
 * disturb a running job (the monitor does not even produce them then).
 */
public final class OrionToast {
    private static final List<Toast> shown = new ArrayList<>();
    private static boolean installed;

    private static Window anchor;

    private OrionToast() {}

    /** Use another window than the main frame as the place the messages stack in (tests, screenshots). */
    public static void setAnchor(Window w) {
        anchor = w;
    }

    private static Window anchorWindow() {
        return anchor != null ? anchor : MainFrame.get();
    }

    /** Start listening to the manager's notices. Safe to call more than once. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        OrionManager.get().addNoticeListener(OrionToast::show);
    }

    private static class Toast {
        final Notice notice;
        final JWindow window;
        final JLabel text = new JLabel();
        final Timer life;
        final Timer tick;

        Toast(Notice n, Window owner) {
            notice = n;
            window = new JWindow(owner);
            window.setFocusableWindowState(false);
            JPanel body = new JPanel(new BorderLayout(10, 0));
            Color accent = accent(n.kind);
            body.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x80808080, true)),
                    BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 5, 0, 0, accent),
                            BorderFactory.createEmptyBorder(8, 10, 8, 10))));
            text.setIcon(new OrionLed(accent, 10));
            text.setIconTextGap(8);
            body.add(text, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            buttons.setOpaque(false);
            if (n.kind == Notice.Kind.SETUP_DUE) {
                JButton start = new JButton("Start setup cycle");
                start.addActionListener(e -> {
                    close();
                    OrionSetupUi.start(null, null);
                });
                buttons.add(start);
                JButton later = new JButton("Later");
                later.addActionListener(e -> close());
                buttons.add(later);
            }
            if (n.kind == Notice.Kind.FOUND) {
                JButton now = new JButton("Scan now");
                now.addActionListener(e -> {
                    OrionManager.get().getMonitor().scanNow(n.rail);
                    close();
                });
                buttons.add(now);
            }
            JButton x = new JButton("✕");
            x.setToolTipText("Dismiss");
            x.setBorderPainted(false);
            x.setContentAreaFilled(false);
            x.addActionListener(e -> close());
            buttons.add(x);
            body.add(buttons, BorderLayout.EAST);
            window.setContentPane(body);
            setText(n);
            int ms = n.kind == Notice.Kind.FOUND || n.kind == Notice.Kind.SETUP_DUE ? 0 : n.kind == Notice.Kind.LOST || n.kind == Notice.Kind.ERROR ? 9000 : 6000;
            life = new Timer(Math.max(1, ms), e -> close());
            life.setRepeats(false);
            if (ms > 0) {
                life.start();
            }
            // A "found" message stays until the rail has been scanned; it shows the countdown.
            tick = new Timer(1000, e -> {
                if (notice.kind == Notice.Kind.FOUND
                        && OrionManager.get().getMonitor().getPending(notice.rail) == null) {
                    close();
                }
            });
            tick.start();
        }

        void setText(Notice n) {
            text.setText("<html><body style='width:300px'>" + n.text.replace("<", "&lt;") + "</body></html>");
            window.pack();
            window.setSize(Math.max(n.kind == Notice.Kind.SETUP_DUE ? 520 : 380, window.getWidth()),
                    window.getHeight());
        }

        void close() {
            life.stop();
            tick.stop();
            window.dispose();
            synchronized (shown) {
                shown.remove(this);
            }
            layout();
        }
    }

    private static Color accent(Notice.Kind k) {
        switch (k) {
            case FOUND:
            case SETUP_DUE:
                return new Color(0x1A73E8);
            case ADDED:
            case BACK:
                return OrionLed.GREEN;
            case LOST:
            case ERROR:
                return OrionLed.RED;
            default:
                return OrionLed.AMBER;
        }
    }

    static void show(Notice n) {
        Window frame = anchorWindow();
        if (frame == null || !frame.isShowing()) {
            return;
        }
        synchronized (shown) {
            // One message per rail for "found": the newest replaces the older one.
            for (Toast t : new ArrayList<>(shown)) {
                if (n.kind == Notice.Kind.FOUND && t.notice.kind == Notice.Kind.FOUND && t.notice.rail == n.rail) {
                    t.close();
                }
            }
            while (shown.size() >= 4) {
                shown.get(0).close();
            }
            Toast t = new Toast(n, frame);
            shown.add(t);
            t.window.setVisible(true);
        }
        layout();
    }

    private static void layout() {
        Window frame = anchorWindow();
        if (frame == null) {
            return;
        }
        if (!frame.isShowing()) {
            return;
        }
        Point p = frame.getLocationOnScreen();
        int right = p.x + frame.getWidth() - 16;
        int y = p.y + frame.getHeight() - 40;
        synchronized (shown) {
            for (int i = shown.size() - 1; i >= 0; i--) {
                Toast t = shown.get(i);
                Dimension d = t.window.getSize();
                y -= d.height;
                t.window.setLocation(right - d.width, y);
                y -= 8;
            }
        }
    }
}
