package snakenet.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/** Живой фон: градиент, плавающие пятна света, сетка и звёзды. */
public final class Backdrop {

    private final BufferedImage[] blobs;
    private final Color[] colors = {Theme.ACCENT, Theme.ACCENT2, Theme.MAGENTA};
    private final float[][] stars = new float[90][3];

    private double t;
    private BufferedImage staticLayer;
    private int staticW = -1;
    private int staticH = -1;

    public Backdrop() {
        blobs = new BufferedImage[colors.length];
        for (int i = 0; i < colors.length; i++) blobs[i] = sprite(colors[i], 360);
        Random rnd = new Random(1234);
        for (float[] s : stars) {
            s[0] = rnd.nextFloat();
            s[1] = rnd.nextFloat();
            s[2] = rnd.nextFloat() * 6.283f;
        }
    }

    public void update(double dt) {
        t += dt;
    }

    private static BufferedImage sprite(Color c, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(size / 2f, size / 2f), size / 2f,
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 255),
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 60),
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
        g.fillRect(0, 0, size, size);
        g.dispose();
        return img;
    }

    /**
     * Неподвижная часть фона (градиент, сетка, виньетка) рисуется один раз
     * и потом только копируется — это заметно экономит кадры.
     */
    private BufferedImage staticLayer(int w, int h) {
        if (staticLayer != null && staticW == w && staticH == h) return staticLayer;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, Theme.BG, 0, h, Theme.BG2));
        g.fillRect(0, 0, w, h);

        g.setColor(new Color(255, 255, 255, 6));
        int step = 56;
        for (int x = 0; x < w; x += step) g.drawLine(x, 0, x, h);
        for (int y = 0; y < h; y += step) g.drawLine(0, y, w, y);

        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(w / 2f, h / 2f), (float) (Math.max(w, h) * 0.75),
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 46), new Color(0, 0, 0, 190)}));
        g.fillRect(0, 0, w, h);
        g.dispose();

        staticLayer = img;
        staticW = w;
        staticH = h;
        return img;
    }

    public void paint(Graphics2D g, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.drawImage(staticLayer(w, h), 0, 0, null);

        Composite old = g.getComposite();
        int size = (int) (Math.max(w, h) * 0.78);
        double[] phases = {0.0, 2.2, 4.1};
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.09f));
        for (int i = 0; i < blobs.length; i++) {
            double a = t * (0.05 + i * 0.017) + phases[i];
            double x = w * (0.5 + 0.42 * Math.sin(a * 1.25 + i));
            double y = h * (0.5 + 0.38 * Math.cos(a + i * 1.8));
            g.drawImage(blobs[i], (int) (x - size / 2.0), (int) (y - size / 2.0), size, size, null);
        }
        g.setComposite(old);

        for (float[] s : stars) {
            double tw = 0.5 + 0.5 * Math.sin(t * 1.4 + s[2]);
            g.setColor(new Color(255, 255, 255, (int) (14 + tw * 46)));
            g.fill(new Ellipse2D.Double(s[0] * w, s[1] * h, 1.4, 1.4));
        }
    }
}
