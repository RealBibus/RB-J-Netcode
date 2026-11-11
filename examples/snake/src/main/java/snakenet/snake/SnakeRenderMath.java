package snakenet.snake;



public final class SnakeRenderMath {

    public static final double MAX_FRAC = 0.995;

    private SnakeRenderMath() {
    }

    public static double fraction(int acc, double unitsAhead) {
        double f = (acc + unitsAhead) / SnakeState.UNITS;
        if (f < 0) return 0;
        return Math.min(f, MAX_FRAC);
    }

    public static double[] points(int[] xs, int[] ys, int dir, double unitsAhead) {
        return points(xs, ys, dir, 0, unitsAhead);
    }

    public static double[] blend(double[] a, double[] b, double t) {
        if (a == null || a.length != b.length) return b;
        double[] out = new double[b.length];
        for (int i = 0; i < b.length; i += 2) {
            double ax = a[i];
            double ay = a[i + 1];
            double bx = b[i];
            double by = b[i + 1];
            if (Math.abs(ax - bx) > 1.5 || Math.abs(ay - by) > 1.5) {
                out[i] = bx;
                out[i + 1] = by;
            } else {
                out[i] = ax + (bx - ax) * t;
                out[i + 1] = ay + (by - ay) * t;
            }
        }
        return out;
    }

    public static double[] points(int[] xs, int[] ys, int dir, int acc, double unitsAhead) {
        int n = Math.min(xs.length, ys.length);
        double[] pts = new double[n * 2];
        if (n == 0) return pts;
        double frac = fraction(acc, unitsAhead);

        pts[0] = xs[0] + SnakeState.DX[dir] * frac;
        pts[1] = ys[0] + SnakeState.DY[dir] * frac;
        for (int i = 1; i < n; i++) {
            int ax = xs[i - 1];
            int ay = ys[i - 1];
            int bx = xs[i];
            int by = ys[i];
            if (Math.abs(ax - bx) > 1.5 || Math.abs(ay - by) > 1.5) {
                pts[i * 2] = bx;
                pts[i * 2 + 1] = by;
            } else {
                pts[i * 2] = bx + (ax - bx) * frac;
                pts[i * 2 + 1] = by + (ay - by) * frac;
            }
        }
        return pts;
    }

    public static double[] pointsAt(int[] xs, int[] ys, int dir, int acc, double unitsAhead) {
        int n = Math.min(xs.length, ys.length);
        if (n == 0) return new double[0];
        if (unitsAhead < 0) unitsAhead = 0;
        double total = acc + unitsAhead;
        int travel = (int) Math.floor(total / SnakeState.UNITS);
        if (travel <= 0) return points(xs, ys, dir, acc, unitsAhead);
        double frac = (total - travel * (double) SnakeState.UNITS) / SnakeState.UNITS;
        int[] vx = new int[n];
        int[] vy = new int[n];
        vx[0] = xs[0] + SnakeState.DX[dir] * travel;
        vy[0] = ys[0] + SnakeState.DY[dir] * travel;
        for (int i = 1; i < n; i++) {
            int idx = i - travel;
            if (idx >= 0) {
                vx[i] = xs[idx];
                vy[i] = ys[idx];
            } else {
                vx[i] = xs[0] + SnakeState.DX[dir] * (travel - i);
                vy[i] = ys[0] + SnakeState.DY[dir] * (travel - i);
            }
        }
        return points(vx, vy, dir, (int) Math.round(frac * SnakeState.UNITS), 0);
    }
}
