package snakenet.client;

import snakenet.game.SnakeState;

/**
 * Непрерывная геометрия змейки для рендера.
 *
 * <p>В сетке змейка сдвигается на клетку раз в несколько тиков, поэтому
 * рисовать «по снимкам» нельзя — получится замер и рывок. Вместо этого позиция
 * считается от накопителя движения: голова = клетка + доля накопителя, а каждый
 * следующий сегмент подтягивается к впереди идущему на ту же долю.</p>
 */
public final class SnakeRenderMath {

    /** Чуть меньше единицы, чтобы голова не залезала на следующую клетку до сдвига тела. */
    public static final double MAX_FRAC = 0.995;

    private SnakeRenderMath() {
    }

    public static double fraction(int acc, double unitsAhead) {
        double f = (acc + unitsAhead) / SnakeState.UNITS;
        if (f < 0) return 0;
        return Math.min(f, MAX_FRAC);
    }

    /**
     * @param xs         клетки тела, голова первая
     * @param unitsAhead сколько долей клетки прошло с последнего тика
     * @return координаты в клетках (x0,y0,x1,y1,...) с дробной частью
     */
    public static double[] points(int[] xs, int[] ys, int dir, double unitsAhead) {
        int n = Math.min(xs.length, ys.length);
        double[] pts = new double[n * 2];
        if (n == 0) return pts;
        int acc = 0;
        return points(xs, ys, dir, acc, unitsAhead);
    }

    /** Смешать две одинаковые по длине ломаные (позиции в клетках). */
    public static double[] blend(double[] a, double[] b, double t) {
        if (a == null || a.length != b.length) return b;
        double[] out = new double[b.length];
        for (int i = 0; i < b.length; i += 2) {
            double ax = a[i];
            double ay = a[i + 1];
            double bx = b[i];
            double by = b[i + 1];
            // разрыв (телепорт/заворот) не сглаживаем — иначе змейка поедет через всё поле
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
}
