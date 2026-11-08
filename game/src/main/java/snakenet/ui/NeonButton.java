package snakenet.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;

/** Кнопка, нарисованная вручную: скругления, градиент, свечение при наведении. */
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
        hover += (target - hover) * Math.min(1, dt * 14);
    }

    public void paint(Graphics2D g) {
        if (!visible) return;
        double hov = hover;
        double r = style == Style.CHIP ? 12 : h / 2;
        Shape shape = Brush.round(x, y, w, h, r);

        switch (style) {
            case PRIMARY: {
                Color top = enabled ? Theme.mix(Theme.ACCENT, Color.WHITE, (float) (0.18 * hov)) : Theme.mix(Theme.ACCENT, Theme.PANEL, 0.7f);
                Color bottom = enabled ? Theme.mix(Theme.ACCENT_DARK, Theme.ACCENT, (float) (0.25 + 0.2 * hov)) : Theme.mix(Theme.ACCENT_DARK, Theme.PANEL, 0.7f);
                if (enabled) Brush.glow(g, shape, Theme.ACCENT, 4, 9 + hov * 10);
                g.setPaint(new GradientPaint((float) x, (float) y, top, (float) x, (float) (y + h), bottom));
                g.fill(shape);
                g.setColor(new Color(255, 255, 255, (int) (28 + 30 * hov)));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(shape);
                drawLabel(g, new Color(0x05, 0x14, 0x0D), 19, true);
                break;
            }
            case SECONDARY: {
                g.setColor(new Color(0x16, 0x23, 0x3A, 235));
                g.fill(shape);
                g.setColor(hov > 0.05 ? Theme.alpha(Theme.ACCENT, (int) (60 + 90 * hov)) : new Color(0x25, 0x38, 0x55, 200));
                g.setStroke(new BasicStroke(1.3f));
                g.draw(shape);
                if (hov > 0.05) {
                    Brush.glow(g, shape, Theme.ACCENT, 3, 4 + hov * 6);
                }
                drawLabel(g, enabled ? Theme.TEXT : Theme.MUTED, 18, true);
                break;
            }
            case GHOST: {
                double a = 0.72 + 0.28 * hov;
                drawLabel(g, new Color(Theme.DIM.getRed(), Theme.DIM.getGreen(), Theme.DIM.getBlue(), (int) (255 * a)), 18, false);
                break;
            }
            case CHIP: {
                if (selected) {
                    g.setPaint(new GradientPaint((float) x, (float) y, Theme.ACCENT, (float) x, (float) (y + h), Theme.mix(Theme.ACCENT, Theme.ACCENT_DARK, 0.45f)));
                    g.fill(shape);
                    drawLabel(g, new Color(0x05, 0x14, 0x0D), 15, true);
                } else {
                    g.setColor(hov > 0.05 ? new Color(0x1B, 0x2B, 0x45, 240) : new Color(0x13, 0x1F, 0x33, 235));
                    g.fill(shape);
                    g.setColor(hov > 0.05 ? Theme.alpha(Theme.ACCENT, 120) : new Color(0x27, 0x3A, 0x57));
                    g.setStroke(new BasicStroke(1.1f));
                    g.draw(shape);
                    drawLabel(g, hov > 0.05 ? Theme.TEXT : Theme.DIM, 15, false);
                }
                break;
            }
            case DANGER: {
                g.setColor(hov > 0.05 ? new Color(0x3A, 0x18, 0x1E) : new Color(0x28, 0x14, 0x19));
                g.fill(shape);
                g.setColor(hov > 0.05 ? Theme.DANGER : new Color(0x7A, 0x33, 0x3D));
                g.setStroke(new BasicStroke(1.3f));
                g.draw(shape);
                drawLabel(g, new Color(0xFF, 0x9A, 0x9A), 17, true);
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
