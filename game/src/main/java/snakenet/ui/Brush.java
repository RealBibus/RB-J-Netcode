package snakenet.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/** Помощники рисования: панели, кнопки, текст. */
public final class Brush {

    private Brush() {
    }

    public static Shape round(double x, double y, double w, double h, double r) {
        double rr = Math.min(r, Math.min(w, h) / 2);
        return new RoundRectangle2D.Double(x, y, w, h, rr * 2, rr * 2);
    }

    public static void fill(Graphics2D g, Shape s, Paint p) {
        g.setPaint(p);
        g.fill(s);
    }

    public static void border(Graphics2D g, Shape s, Color c, float width) {
        g.setColor(c);
        g.setStroke(new BasicStroke(width));
        g.draw(s);
    }

    /** Панель: плотная заливка, тонкая рамка и жёсткая тень — как наклейка. */
    public static void panel(Graphics2D g, double x, double y, double w, double h, double r) {
        double rr = Math.min(r, 18);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(round(x + 4, y + 5, w, h, rr));
        g.setColor(Theme.PANEL);
        g.fill(round(x, y, w, h, rr));
        border(g, round(x + 0.5, y + 0.5, w - 1, h - 1, rr), Theme.LINE, 1.4f);
    }

    /** Мягкое свечение — теперь почти не используется, оставлено для акцентов. */
    public static void glow(Graphics2D g, Shape s, Color c, int layers, double spread) {
        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (16 * (1 - t) + 6)));
            g.setStroke(new BasicStroke((float) (spread * t * 2 + 1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
        }
    }

    public static void text(Graphics2D g, String s, double x, double baseline, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, (float) x, (float) baseline);
    }

    public static void centered(Graphics2D g, String s, double cx, double baseline, Font f, Color c) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baseline);
    }

    public static void right(Graphics2D g, String s, double rx, double baseline, Font f, Color c) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, (float) (rx - fm.stringWidth(s)), (float) baseline);
    }

    /** Текст с лёгкой тенью — читается на любом фоне. */
    public static void shadowText(Graphics2D g, String s, double x, double baseline, Font f, Color c, Color shadow) {
        g.setFont(f);
        g.setColor(shadow);
        g.drawString(s, (float) (x + 2), (float) (baseline + 2));
        g.setColor(c);
        g.drawString(s, (float) x, (float) baseline);
    }

    public static void glowText(Graphics2D g, String s, double cx, double baseline, Font f, Color core, Color halo, int strength) {
        centered(g, s, cx, baseline, f, core);
    }

    public static void gradientText(Graphics2D g, String s, double x, double baseline, Font f, Color a, Color b) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int w = Math.max(1, fm.stringWidth(s));
        g.setPaint(new GradientPaint((float) x, (float) baseline - f.getSize(), a,
                (float) x + w, (float) baseline, b));
        g.drawString(s, (float) x, (float) baseline);
    }

    public static void divider(Graphics2D g, double x1, double y1, double x2, double y2, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1f));
        g.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
    }

    /** Текст с переносом по словам в заданную ширину. Возвращает высоту. */
    public static double wrapped(Graphics2D g, String text, double x, double y, double maxWidth,
                                 Font f, Color c, double lineHeight) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        double yy = y;
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                    g.drawString(line.toString(), (float) x, (float) yy);
                    yy += lineHeight;
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            if (!line.isEmpty()) {
                g.drawString(line.toString(), (float) x, (float) yy);
                yy += lineHeight;
            }
        }
        return yy - y;
    }

    public static String ellipsize(Graphics2D g, String s, double maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth) return s;
        String cut = s;
        while (cut.length() > 1 && fm.stringWidth(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
