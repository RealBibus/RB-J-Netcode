package snakenet.chess.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;


public final class NeonButton {

    public enum Style { PRIMARY, SECONDARY, GHOST, CHIP, DANGER }

    public double x;
    public double y;
    public double w;
    public double h;
    public String label;
    public Style style;
    public boolean enabled = true;
    public boolean visible = true;
    public boolean selected = false;
    public Runnable action;

    private double hover;

    public NeonButton(String label, Style style, double x, double y, double w, double h, Runnable action) {
        this.label = label;
        this.style = style;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.action = action;
    }

    public NeonButton at(double x, double y, double w, double h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    public NeonButton label(String l) {
        this.label = l;
        return this;
    }

    public boolean contains(double px, double py) {
        return visible && px >= x && px <= x + w && py >= y && py <= y + h;
    }

    public void update(double dt, double mx, double my) {
        double target = contains(mx, my) && enabled ? 1 : 0;
        hover += (target - hover) * Math.min(1, dt * 16);
    }

    public void paint(Graphics2D g) {
        if (!visible) return;
        Shape shape = new Rectangle2D.Double(x, y, w, h);
        boolean hot = hover > 0.05;

        switch (style) {
            case PRIMARY: {
                Color base = enabled ? (hot ? Theme.shade(Theme.ACCENT, 0.12) : Theme.ACCENT)
                        : Theme.mix(Theme.ACCENT, Theme.PANEL, 0.7f);
                g.setColor(base);
                g.fill(shape);
                g.setColor(Theme.alpha(Theme.shade(base, -0.45), 220));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(shape);
                label(g, upper(label), new Color(0x06, 0x14, 0x08), 13, true);
                break;
            }
            case SECONDARY: {
                g.setColor(hot ? Theme.PANEL_2 : Theme.PANEL);
                g.fill(shape);
                g.setColor(hot ? Theme.ACCENT : Theme.LINE);
                g.setStroke(new BasicStroke(1.2f));
                g.draw(shape);
                label(g, upper(label), enabled ? Theme.TEXT : Theme.MUTED, 13, true);
                break;
            }
            case GHOST: {
                label(g, label, Theme.alpha(Theme.DIM, (int) (160 + 95 * hover)), 12, false);
                break;
            }
            case CHIP: {
                if (selected) {
                    g.setColor(Theme.ACCENT);
                    g.fill(shape);
                    label(g, label, new Color(0x06, 0x14, 0x08), 11, true);
                } else {
                    g.setColor(hot ? Theme.PANEL_2 : Theme.PANEL);
                    g.fill(shape);
                    g.setColor(hot ? Theme.ACCENT : Theme.LINE);
                    g.setStroke(new BasicStroke(1f));
                    g.draw(shape);
                    label(g, label, hot ? Theme.TEXT : Theme.DIM, 11, false);
                }
                break;
            }
            case DANGER: {
                g.setColor(hot ? Theme.DANGER : Theme.PANEL);
                g.fill(shape);
                g.setColor(Theme.DANGER);
                g.setStroke(new BasicStroke(1.2f));
                g.draw(shape);
                label(g, upper(label), hot ? new Color(0x14, 0x06, 0x06) : Theme.DANGER, 12, true);
                break;
            }
            default:
                break;
        }
    }

    private static String upper(String s) {
        return s;
    }

    private void label(Graphics2D g, String text, Color color, float size, boolean bold) {
        Font f = bold ? Theme.bold(size) : Theme.font(size);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double tx = x + (w - fm.stringWidth(text)) / 2.0;
        double ty = y + h / 2.0 + (fm.getAscent() - fm.getDescent()) / 2.0;
        g.setColor(color);
        g.drawString(text, (float) tx, (float) ty);
    }
}

