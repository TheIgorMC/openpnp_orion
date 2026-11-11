package org.openpnp.machine.orion.sheets;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.UIManager;

import org.openpnp.machine.orion.protocol.OrionDeviceInfo;

/**
 * One feeder as a small rectangle: its address, its component id and a status LED. Everything
 * else is in the details panel, the tooltip, or the table-free overview around it.
 */
class FeederTile extends JComponent {
    static final int W = 92;
    static final int H = 46;

    final OrionBusManagerPanel.Row row;
    private boolean selected;

    FeederTile(OrionBusManagerPanel.Row row, Runnable onSelect, Runnable onOpen) {
        this.row = row;
        setPreferredSize(new Dimension(W, H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(tooltip());
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                onSelect.run();
                requestFocusInWindow();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    onOpen.run();
                }
            }
        });
        setFocusable(true);
    }

    void setSelected(boolean s) {
        if (selected != s) {
            selected = s;
            repaint();
        }
    }

    Color ledColor() {
        OrionDeviceInfo d = row.info;
        if (d == null) {
            return OrionLed.RED;
        }
        switch (d.state) {
            case ONLINE:
                return OrionLed.GREEN;
            case LOST:
                return OrionLed.RED;
            default:
                return OrionLed.AMBER;
        }
    }

    static String stateText(OrionBusManagerPanel.Row row) {
        OrionDeviceInfo d = row.info;
        if (d == null) {
            return "Not found on the rail";
        }
        switch (d.state) {
            case ONLINE:
                return "Answers";
            case LOST:
                return "No answer";
            case CONFLICT:
                return "Address conflict";
            default:
                return "No readable serial";
        }
    }

    static String componentText(OrionDeviceInfo d) {
        return d == null || d.componentId == 0xFFFF || d.componentId == 0 ? "no part" : "part " + d.componentId;
    }

    private String tooltip() {
        OrionDeviceInfo d = row.info;
        StringBuilder sb = new StringBuilder("<html><b>");
        sb.append(d == null ? "Missing feeder" : "Feeder #" + d.address).append("</b> &nbsp;").append(stateText(row));
        if (row.feeder != null) {
            sb.append("<br>OpenPnP feeder: ").append(row.feeder.getName());
        } else if (d != null) {
            sb.append("<br><i>no OpenPnP feeder yet</i>");
        }
        if (d != null) {
            sb.append("<br>").append(componentText(d));
            if (d.lastPingMs >= 0) {
                sb.append("<br>ping ").append(d.lastPingMs).append(" ms");
            }
            if (d.note != null && !d.note.isEmpty()) {
                sb.append("<br>").append(d.note);
            }
        }
        return sb.append("</html>").toString();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color panel = UIManager.getColor("Panel.background");
        boolean dark = panel != null && panel.getRed() + panel.getGreen() + panel.getBlue() < 384;
        OrionDeviceInfo d = row.info;
        boolean lost = d == null || d.state == OrionDeviceInfo.State.LOST;
        Color fill = dark ? new Color(0x3C3F41) : Color.WHITE;
        if (lost) {
            fill = dark ? new Color(0x4A3535) : new Color(0xFDEDEC);
        }
        Color edge = lost ? new Color(0xE5A5A0) : (dark ? new Color(0x5A5D5F) : new Color(0xC9CED3));
        Color text = dark ? new Color(0xE6E6E6) : new Color(0x20262B);
        Color sub = dark ? new Color(0xA8ADB2) : new Color(0x5F6872);
        RoundRectangle2D shape = new RoundRectangle2D.Double(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
        g.setColor(fill);
        g.fill(shape);
        g.setColor(selected ? new Color(0x1A73E8) : edge);
        g.setStroke(new java.awt.BasicStroke(selected ? 2.2f : 1f));
        g.draw(shape);

        OrionLed.paint(g, ledColor(), getWidth() - 19, 9, 10);

        Font base = getFont() == null ? UIManager.getFont("Label.font") : getFont();
        g.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 3f));
        g.setColor(text);
        String title = d == null ? "?" : "#" + d.address;
        g.drawString(title, 9, 21);
        g.setFont(base.deriveFont(base.getSize2D() - 1f));
        g.setColor(sub);
        FontMetrics fm = g.getFontMetrics();
        String line2 = d == null ? "missing" : componentText(d);
        g.drawString(clip(line2, fm, getWidth() - 16), 9, 38);
        if (d != null && row.feeder == null) {
            // small blue corner mark: a unit that has no OpenPnP feeder yet
            g.setColor(new Color(0x1A73E8));
            g.fillRoundRect(getWidth() - 17, getHeight() - 15, 8, 8, 3, 3);
        }
        g.dispose();
    }

    private static String clip(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) {
            return s;
        }
        while (s.length() > 1 && fm.stringWidth(s + "...") > w) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }
}
