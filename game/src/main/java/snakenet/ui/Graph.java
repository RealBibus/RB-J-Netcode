package snakenet.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;

/** Спарклайн: история значений и подпись последнего. */
public final class Graph {

    private final double[] values;
    private int count;
    private int head;
    private String label;
    private String unit;

    public Graph(String label, String unit, int capacity) {
        this.label = label;
        this.unit = unit;
        this.values = new double[Math.max(8, capacity)];
    }

    public void push(double value) {
        values[head] = value;
        head = (head + 1) % values.length;
        if (count < values.length) count++;
    }

    public double last() {
        if (count == 0) return 0;
        int idx = (head - 1 + values.length) % values.length;
        return values[idx];
    }

    public String label() {
        return label;
    }

    public void paint(Graphics2D g, double x, double y, double w, double h, Color color, double max) {
        g.setColor(Theme.PANEL_2);
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(Theme.LINE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1));

        g.setColor(Theme.alpha(Theme.LINE, 140));
        for (int i = 1; i < 4; i++) {
            double gy = y + h * i / 4.0;
            g.draw(new Line2D.Double(x, gy, x + w, gy));
        }

        if (count > 1) {
            double scale = max <= 0 ? 1 : max;
            double step = w / (double) (values.length - 1);
            double startX = x + w - step * (count - 1);
            g.setColor(color);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double px = 0;
            double py = 0;
            for (int i = 0; i < count; i++) {
                int idx = (head - count + i + values.length * 2) % values.length;
                double v = Math.max(0, Math.min(scale, values[idx]));
                double vx = startX + step * i;
                double vy = y + h - (v / scale) * (h - 2) - 1;
                if (i > 0) g.draw(new Line2D.Double(px, py, vx, vy));
                px = vx;
                py = vy;
            }
        }

        Brush.text(g, label, x + 6, y + 13, Theme.bold(10), Theme.DIM);
        String value = unit.isEmpty()
                ? String.format("%.0f", last())
                : String.format("%.0f %s", last(), unit);
        Brush.right(g, value, x + w - 6, y + 13, Theme.font(11), color);
    }
}
