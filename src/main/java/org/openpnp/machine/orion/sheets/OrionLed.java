package org.openpnp.machine.orion.sheets;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

import javax.swing.Icon;

/** A round status light: green = answers, red = silent, amber = needs attention, grey = unknown. */
final class OrionLed implements Icon {
    static final Color GREEN = new Color(0x2DB84C);
    static final Color RED = new Color(0xD93025);
    static final Color AMBER = new Color(0xF2A100);
    static final Color GREY = new Color(0x9AA0A6);

    private final Color color;
    private final int size;

    OrionLed(Color color, int size) {
        this.color = color;
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        paint((Graphics2D) g0, color, x, y, size);
    }

    static void paint(Graphics2D g, Color color, int x, int y, int size) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // soft halo, body, darker rim, small highlight: reads as a lit LED at 10 px
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
        g2.fill(new Ellipse2D.Double(x - 2, y - 2, size + 4, size + 4));
        g2.setColor(color);
        g2.fill(new Ellipse2D.Double(x, y, size, size));
        g2.setColor(color.darker().darker());
        g2.draw(new Ellipse2D.Double(x, y, size, size));
        g2.setColor(new Color(255, 255, 255, 140));
        g2.fill(new Ellipse2D.Double(x + size * 0.22, y + size * 0.18, size * 0.32, size * 0.28));
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return size + 2;
    }

    @Override
    public int getIconHeight() {
        return size + 2;
    }
}
