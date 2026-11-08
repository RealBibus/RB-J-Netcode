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

/** Помощники рисования: панели, свечение, текст. */
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

    /** Панель с мягким градиентом, рамкой и бликом сверху. */
    public static void panel(Graphics2D g, double x, double y, double w, double h, double r) {
        Shape s = round(x, y, w, h, r);
        g.setPaint(new GradientPaint(
                (float) x, (float) y, new Color(0x16, 0x23, 0x3A, 232),
                (float) x, (float) (y + h), new Color(0x0D, 0x16, 0x26, 224)));
        g.fill(s);
        border(g, s, new Color(0x25, 0x38, 0x55, 180), 1.2f);
        // тонкий блик по верхней кромке
        g.setColor(new Color(255, 255, 255, 16));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new RoundRectangle2D.Double(x + 1, y + 1, w - 2, h - 2, Math.min(r, h / 2) * 2 - 2, Math.min(r, h / 2) * 2 - 2));
    }

    /** Мягкое свечение вокруг фигуры (несколько полупрозрачных обводок). */
    public static void glow(Graphics2D g, Shape s, Color c, int layers, double spread) {
        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (26 * (1 - t) + 10)));
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

    /** Неоновый текст: несколько смещённых копий + яркое ядро. */
    public static void glowText(Graphics2D g, String s, double cx, double baseline, Font f, Color core, Color halo, int strength) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double x = cx - fm.stringWidth(s) / 2.0;
        for (int layer = strength; layer >= 1; layer--) {
            int a = Math.max(8, 34 / layer);
            g.setColor(new Color(halo.getRed(), halo.getGreen(), halo.getBlue(), a));
            double off = layer * 1.1;
            g.drawString(s, (float) (x - off), (float) baseline);
            g.drawString(s, (float) (x + off), (float) baseline);
            g.drawString(s, (float) x, (float) (baseline - off));
            g.drawString(s, (float) x, (float) (baseline + off));
        }
        g.setColor(core);
        g.drawString(s, (float) x, (float) baseline);
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
