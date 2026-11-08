package snakenet.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;

/** Кнопка: плотная заливка, рамка и жёсткая тень. Без свечения. */
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
        double hov = hover;
        double r = style == Style.CHIP ? 8 : 11;
        Shape shape = Brush.round(x, y, w, h, r);
        boolean pressable = style != Style.GHOST;

        if (pressable) {
            // тень-подложка
            g.setColor(new Color(0, 0, 0, enabled ? 110 : 60));
            g.fill(Brush.round(x + 2, y + 3 + hov, w, h, r));
        }

        switch (style) {
            case PRIMARY: {
                Color base = enabled ? Theme.ACCENT : Theme.mix(Theme.ACCENT, Theme.PANEL, 0.65f);
                g.setColor(Theme.shade(base, 0.08 * hov));
                g.fill(shape);
                g.setColor(Theme.alpha(Theme.shade(base, -0.35), 200));
                g.setStroke(new BasicStroke(1.4f));
                g.draw(shape);
                drawLabel(g, new Color(0x1A, 0x12, 0x08), 19, true);
                break;
            }
            case SECONDARY: {
                g.setColor(hov > 0.05 ? Theme.PANEL_2 : Theme.PANEL);
                g.fill(shape);
                g.setColor(hov > 0.05 ? Theme.alpha(Theme.ACCENT, 180) : Theme.LINE);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(shape);
                drawLabel(g, enabled ? Theme.TEXT : Theme.MUTED, 18, true);
                break;
            }
            case GHOST: {
                drawLabel(g, Theme.alpha(Theme.DIM, (int) (200 + 55 * hov)), 17, false);
                break;
            }
            case CHIP: {
                if (selected) {
                    g.setColor(Theme.ACCENT);
                    g.fill(shape);
                    g.setColor(Theme.alpha(Theme.ACCENT_DARK, 220));
                    g.setStroke(new BasicStroke(1.3f));
                    g.draw(shape);
                    drawLabel(g, new Color(0x1A, 0x12, 0x08), 15, true);
                } else {
                    g.setColor(hov > 0.05 ? Theme.PANEL_2 : Theme.PANEL);
                    g.fill(shape);
                    g.setColor(hov > 0.05 ? Theme.alpha(Theme.ACCENT, 140) : Theme.LINE);
                    g.setStroke(new BasicStroke(1.2f));
                    g.draw(shape);
                    drawLabel(g, hov > 0.05 ? Theme.TEXT : Theme.DIM, 15, false);
                }
                break;
            }
            case DANGER: {
                g.setColor(hov > 0.05 ? Theme.shade(Theme.DANGER, 0.1) : Theme.mix(Theme.DANGER, Theme.BG, 0.25f));
                g.fill(shape);
                g.setColor(Theme.alpha(Theme.shade(Theme.DANGER, -0.4), 220));
                g.setStroke(new BasicStroke(1.4f));
                g.draw(shape);
                drawLabel(g, new Color(0x1C, 0x0C, 0x0E), 17, true);
                break;
            }
            default:
                break;
        }
    }

    private void drawLabel(Graphics2D g, Color color, float size, boolean bold) {
        Font f = bold ? Theme.semibold(size) : Theme.font(size);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double tx = x + (w - fm.stringWidth(label)) / 2.0;
        double ty = y + h / 2.0 + (fm.getAscent() - fm.getDescent()) / 2.0;
        g.setColor(color);
        g.drawString(label, (float) tx, (float) ty);
    }
}
