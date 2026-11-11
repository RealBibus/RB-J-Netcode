package snakenet.chess.ui;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Random;


public final class Backdrop {

    private final float[][] dots = new float[26][2];
    private double t;

    private BufferedImage base;
    private int baseW = -1;
    private int baseH = -1;

    public Backdrop() {
        Random rnd = new Random(7);
        for (float[] d : dots) {
            d[0] = rnd.nextFloat();
            d[1] = rnd.nextFloat();
        }
    }

    public void update(double dt) {
        t += dt;
    }

    private BufferedImage base(int w, int h) {
        if (base != null && baseW == w && baseH == h) return base;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, Theme.BG, 0, h, Theme.BG2));
        g.fillRect(0, 0, w, h);

        
        int step = 32;
        g.setColor(new Color(255, 255, 255, 8));
        for (int y = step / 2; y < h; y += step) {
            for (int x = step / 2; x < w; x += step) {
                g.fillRect(x, y, 1, 1);
            }
        }

        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(w * 0.5f, h * 0.42f), (float) (Math.max(w, h) * 0.8),
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 30), new Color(0, 0, 0, 165)}));
        g.fillRect(0, 0, w, h);
        g.dispose();
        base = img;
        baseW = w;
        baseH = h;
        return img;
    }

    public void paint(Graphics2D g, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.drawImage(base(w, h), 0, 0, null);

        
        for (float[] d : dots) {
            double tw = 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(t * 0.8 + d[0] * 9));
            g.setColor(new Color(241, 234, 222, (int) (8 + tw * 16)));
            g.fill(new Ellipse2D.Double(d[0] * w, d[1] * h, 1.6, 1.6));
        }
    }
}

