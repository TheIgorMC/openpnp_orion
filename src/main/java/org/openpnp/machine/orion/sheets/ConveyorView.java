package org.openpnp.machine.orion.sheets;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;

import org.openpnp.machine.orion.protocol.ConveyorStatus;

/**
 * Drawing of the conveyor seen from above: belt with the entry sensor, the stop pin, the clamp cams, the rail width
 * and the boards on it. It only draws what a {@link ConveyorStatus} says, so the same component can sit in a
 * whole-machine overview later. The loaded board glides to its new position instead of jumping.
 */
public class ConveyorView extends JComponent {
    private ConveyorStatus status;
    /** Sensor to pin, mm. */
    private double stopMm = 250;
    private boolean stopperFitted = true;
    private boolean clampFitted = true;
    /** Displayed leading edge of the loaded board, mm, eased toward the real one. */
    private double shownPos = Double.NaN;
    private double shownWidth = Double.NaN;
    private final Timer glide = new Timer(30, e -> step());

    public ConveyorView() {
        setPreferredSize(new Dimension(760, 300));
        setMinimumSize(new Dimension(420, 220));
        glide.start();
    }

    @Override
    public void removeNotify() {
        glide.stop();
        super.removeNotify();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        glide.start();
    }

    public void setStatus(ConveyorStatus s) {
        this.status = s;
        if (s != null && s.posValid() && Double.isNaN(shownPos)) {
            shownPos = s.posMm();
        }
        if (s != null && s.width != ConveyorStatus.UNKNOWN_WIDTH && Double.isNaN(shownWidth)) {
            shownWidth = s.widthMm();
        }
        repaint();
    }

    public void setGeometry(double stopDistanceMm, boolean stopper, boolean clamp) {
        this.stopMm = Math.max(50, stopDistanceMm);
        this.stopperFitted = stopper;
        this.clampFitted = clamp;
        repaint();
    }

    private void step() {
        if (status == null) {
            return;
        }
        boolean changed = false;
        if (status.posValid()) {
            double target = status.posMm();
            if (Double.isNaN(shownPos) || Math.abs(shownPos - target) < 0.2) {
                changed = shownPos != target;
                shownPos = target;
            } else {
                shownPos += (target - shownPos) * 0.2;
                changed = true;
            }
        }
        if (status.width != ConveyorStatus.UNKNOWN_WIDTH) {
            double w = status.widthMm();
            if (Double.isNaN(shownWidth) || Math.abs(shownWidth - w) < 0.1) {
                changed |= shownWidth != w;
                shownWidth = w;
            } else {
                shownWidth += (w - shownWidth) * 0.15;
                changed = true;
            }
        }
        if (changed) {
            repaint();
        }
    }

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color panel = UIManager.getColor("Panel.background");
        boolean dark = panel != null && panel.getRed() + panel.getGreen() + panel.getBlue() < 384;
        Color text = dark ? c(0xE6E6E6) : c(0x20262B);
        Color sub = dark ? c(0xA8ADB2) : c(0x5F6872);
        Color beltCol = dark ? c(0x3A3D40) : c(0xD9DDE1);
        Color railCol = dark ? c(0x6C7278) : c(0x8A929A);
        Color boardCol = c(0x2F8F5B);

        int w = getWidth();
        int h = getHeight();
        double fromMm = -110;                 // room for a board waiting behind the sensor
        double toMm = stopMm + 70;
        int left = 24;
        int right = w - 24;
        double sx = (right - left) / (toMm - fromMm);
        java.util.function.DoubleUnaryOperator px = mm -> left + (mm - fromMm) * sx;

        // vertical: the rails open and close with the width (50..250 mm mapped onto the free height)
        double widthMm = Double.isNaN(shownWidth) ? 100 : shownWidth;
        int cy = h / 2 - 6;
        double gap = 120 + (Math.max(50, Math.min(250, widthMm)) - 50) / 200.0 * Math.max(40, h - 230);
        int top = (int) (cy - gap / 2);
        int bottom = (int) (cy + gap / 2);

        // belt
        g.setColor(beltCol);
        g.fill(new RoundRectangle2D.Double(px.applyAsDouble(fromMm), top - 6, px.applyAsDouble(toMm)
                - px.applyAsDouble(fromMm), bottom - top + 12, 14, 14));
        g.setColor(dark ? c(0x4A4E52) : c(0xC5CACF));
        g.setStroke(new BasicStroke(1f));
        for (double mm = Math.ceil(fromMm / 10) * 10; mm <= toMm; mm += 10) {
            int x = (int) px.applyAsDouble(mm);
            g.drawLine(x, top, x, top + (mm % 50 == 0 ? 8 : 4));
        }
        g.setFont(getFont().deriveFont(getFont().getSize2D() - 2f));
        g.setColor(sub);
        for (double mm = Math.ceil(fromMm / 50) * 50; mm <= toMm; mm += 50) {
            String s = String.valueOf((int) mm);
            int x = (int) px.applyAsDouble(mm);
            g.drawString(s, x - g.getFontMetrics().stringWidth(s) / 2, bottom + 3);
        }

        // rails (side guides)
        g.setColor(railCol);
        g.fill(new RoundRectangle2D.Double(px.applyAsDouble(fromMm), top - 14, px.applyAsDouble(toMm)
                - px.applyAsDouble(fromMm), 8, 6, 6));
        g.fill(new RoundRectangle2D.Double(px.applyAsDouble(fromMm), bottom + 6, px.applyAsDouble(toMm)
                - px.applyAsDouble(fromMm), 8, 6, 6));

        boolean known = status != null;
        boolean blocked = known && status.entryBlocked();
        boolean loaded = known && status.boardLoaded();
        double len = known && status.length >= 0 ? status.lengthMm() : 80;

        // boards
        if (known && loaded && status.posValid() && !Double.isNaN(shownPos)) {
            drawBoard(g, px, shownPos - len, shownPos, top, bottom, boardCol, status.length >= 0, false, "board "
                    + (status.length >= 0 ? String.format("%.1f mm", len) : ""));
        } else if (known && blocked && !loaded && status.boardLeaving()) {
            drawBoard(g, px, -110, -12, top, bottom, c(0xE59400), true, true, "taking it out");
        } else if (known && blocked && !loaded) {
            drawBoard(g, px, -len, 0, top, bottom, c(0x1A73E8), false, true, "inserted");
        } else if (known && !loaded && status.boardWaiting()) {
            drawBoard(g, px, -len, 0, top, bottom, c(0x1A73E8), false, true, "next board");
        }
        if (known && loaded && status.boardWaiting()) {
            drawBoard(g, px, -90, 0, top, bottom, c(0x1A73E8), false, true, "waiting");
        }

        // entry sensor
        int sxp = (int) px.applyAsDouble(0);
        g.setColor(blocked ? OrionLed.RED : OrionLed.GREEN);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(sxp, top - 10, sxp, bottom + 10);
        OrionLed.paint(g, blocked ? OrionLed.RED : OrionLed.GREEN, sxp - 5, top - 28, 10);
        g.setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1f));
        g.setColor(text);
        label(g, "entry sensor", sxp, top - 36, true);

        // stop pin
        int pin = (int) px.applyAsDouble(stopMm);
        boolean out = known && status.stopperOut();
        if (stopperFitted) {
            g.setColor(out ? c(0xD93025) : sub);
            if (out) {
                g.fillRoundRect(pin - 3, top - 10, 7, bottom - top + 20, 4, 4);
            } else {
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                        new float[] {3f, 3f}, 0f));
                g.drawRoundRect(pin - 3, top - 10, 7, bottom - top + 20, 4, 4);
            }
        }
        g.setColor(text);
        label(g, stopperFitted ? (out ? "stop pin (out)" : "stop pin (in)") : "stop", pin, top - 36, true);

        // clamp cams
        if (clampFitted) {
            boolean cl = known && status.clamped();
            int cx = (int) px.applyAsDouble(stopMm - 30);
            drawCam(g, cx, top - 4, true, cl, dark);
            drawCam(g, cx, bottom + 4, false, cl, dark);
            g.setColor(text);
            label(g, cl ? "clamped" : "clamp open", cx, bottom + 36, true);
        }

        // direction
        g.setColor(sub);
        g.setStroke(new BasicStroke(1.6f));
        int ay = bottom + 36;
        int ax1 = (int) px.applyAsDouble(20);
        int ax2 = (int) px.applyAsDouble(stopMm - 70);
        g.drawLine(ax1, ay, ax2, ay);
        Path2D head = new Path2D.Double();
        head.moveTo(ax2, ay);
        head.lineTo(ax2 - 8, ay - 4);
        head.lineTo(ax2 - 8, ay + 4);
        head.closePath();
        g.fill(head);
        g.setFont(getFont().deriveFont(getFont().getSize2D() - 1f));
        label(g, "forward (+), toward the pin", (ax1 + ax2) / 2, ay + 15, true);
        label(g, "entry", (int) px.applyAsDouble(fromMm) + 18, bottom + 36, true);

        // width read-out
        g.setColor(text);
        String ws = status == null || status.width == ConveyorStatus.UNKNOWN_WIDTH ? "width ?"
                : String.format("width %.1f mm%s", status.widthMm(), status.widthConfirmed() ? "" : "  (not confirmed)");
        g.setFont(getFont().deriveFont(Font.BOLD));
        g.drawString(ws, left, h - 10);
        if (status == null) {
            g.setColor(sub);
            String s = "no status";
            g.drawString(s, w - 24 - g.getFontMetrics().stringWidth(s), h - 10);
        }
        g.dispose();
    }

    private void drawBoard(Graphics2D g, java.util.function.DoubleUnaryOperator px, double fromMm, double toMm,
            int top, int bottom, Color col, boolean lengthKnown, boolean dashed, String caption) {
        double x1 = px.applyAsDouble(fromMm);
        double x2 = px.applyAsDouble(toMm);
        RoundRectangle2D r = new RoundRectangle2D.Double(x1, top + 14, x2 - x1, bottom - top - 28, 6, 6);
        g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), dashed ? 70 : 150));
        g.fill(r);
        g.setColor(col.darker());
        g.setStroke(dashed ? new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[] {5f, 4f}, 0f) : new BasicStroke(1.6f));
        g.draw(r);
        // a few "components" so it reads as a PCB
        g.setColor(new Color(255, 255, 255, 90));
        for (int i = 0; i < 4; i++) {
            g.fillRect((int) (x1 + 10 + i * Math.max(10, (x2 - x1 - 30) / 4)), top + 14 + 12, 8, 5);
        }
        g.setColor(Color.WHITE);
        g.setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1f));
        FontMetrics fm = g.getFontMetrics();
        int cx = (int) ((x1 + x2) / 2);
        int cy = (top + bottom) / 2 + fm.getAscent() / 2 - 2;
        if (fm.stringWidth(caption) < x2 - x1 - 6) {
            g.drawString(caption, cx - fm.stringWidth(caption) / 2, cy);
        }
    }

    private void drawCam(Graphics2D g, int x, int y, boolean upper, boolean engaged, boolean dark) {
        Color col = engaged ? c(0x1A73E8) : (dark ? c(0x7B8187) : c(0x9AA0A6));
        g.setColor(col);
        int dir = upper ? 1 : -1;
        int len = engaged ? 20 : 10;
        Path2D p = new Path2D.Double();
        p.moveTo(x - 9, y);
        p.lineTo(x + 9, y);
        p.lineTo(x, y + dir * len);
        p.closePath();
        g.fill(p);
    }

    private void label(Graphics2D g, String s, int x, int y, boolean centered) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, centered ? x - fm.stringWidth(s) / 2 : x, y);
    }
}
