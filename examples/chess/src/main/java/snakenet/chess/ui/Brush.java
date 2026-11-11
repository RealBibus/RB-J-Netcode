package snakenet.chess.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;


public final class Brush {

    private Brush() {
    }

    
    public static Shape round(double x, double y, double w, double h, double r) {
        double rr = Math.min(r, 3);
        return new Rectangle2D.Double(x, y, w, h);
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

    
    public static void panel(Graphics2D g, double x, double y, double w, double h, double r) {
        g.setColor(Theme.PANEL);
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(Theme.LINE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1));
    }

    
    public static void panelTitled(Graphics2D g, double x, double y, double w, double h, String title) {
        panel(g, x, y, w, h, 0);
        g.setColor(Theme.PANEL_2);
        g.fill(new Rectangle2D.Double(x, y, w, 26));
        g.setColor(Theme.LINE);
        g.draw(new Rectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1));
        g.draw(new java.awt.geom.Line2D.Double(x, y + 26.5, x + w, y + 26.5));
        text(g, title, x + 10, y + 18, Theme.bold(11), Theme.DIM);
    }

    public static void glow(Graphics2D g, Shape s, Color c, int layers, double spread) {
        
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

    public static void shadowText(Graphics2D g, String s, double x, double baseline, Font f, Color c, Color shadow) {
        text(g, s, x, baseline, f, c);
    }

    public static void glowText(Graphics2D g, String s, double cx, double baseline, Font f, Color core, Color halo, int strength) {
        centered(g, s, cx, baseline, f, core);
    }

    public static void gradientText(Graphics2D g, String s, double x, double baseline, Font f, Color a, Color b) {
        text(g, s, x, baseline, f, b);
    }

    public static void divider(Graphics2D g, double x1, double y1, double x2, double y2, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1f));
        g.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
    }

    
    public static void bar(Graphics2D g, double x, double y, double w, double h, double value, Color color) {
        g.setColor(Theme.PANEL_2);
        g.fill(new Rectangle2D.Double(x, y, w, h));
        double v = Math.max(0, Math.min(1, value));
        g.setColor(color);
        g.fill(new Rectangle2D.Double(x, y, w * v, h));
    }

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
        while (cut.length() > 1 && fm.stringWidth(cut + "~") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "~";
    }
}

