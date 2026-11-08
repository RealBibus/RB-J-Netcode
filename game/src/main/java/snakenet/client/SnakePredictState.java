package snakenet.client;

import netcore.predict.InputCommand;
import netcore.predict.SimModel;

/**
 * Состояние для предсказания: только своя змейка. Чужие змейки и еду клиент
 * не предсказывает — их присылает сервер.
 */
public final class SnakePredictState {

    public int[] xs;
    public int[] ys;
    public int dir;
    public int acc;
    public boolean alive;

    public SnakePredictState(int[] xs, int[] ys, int dir, int acc, boolean alive) {
        this.xs = xs;
        this.ys = ys;
        this.dir = dir;
        this.acc = acc;
        this.alive = alive;
    }

    public static SnakePredictState of(Snapshot.SnakeView v) {
        return new SnakePredictState(v.xs.clone(), v.ys.clone(), v.dir, v.acc, v.alive);
    }

    public SnakePredictState copy() {
        return new SnakePredictState(xs.clone(), ys.clone(), dir, acc, alive);
    }

    public int length() {
        return xs.length;
    }

    public int headX() {
        return xs.length == 0 ? 0 : xs[0];
    }

    public int headY() {
        return ys.length == 0 ? 0 : ys[0];
    }

    /** Детерминированная модель движения — та же арифметика, что на сервере. */
    public static final class Model implements SimModel<SnakePredictState> {

        private final int cols;
        private final int rows;
        private final int unitsPerTick;
        private final boolean walls;

        public Model(int cols, int rows, int unitsPerTick, boolean walls) {
            this.cols = cols;
            this.rows = rows;
            this.unitsPerTick = unitsPerTick;
            this.walls = walls;
        }

        @Override
        public SnakePredictState copy(SnakePredictState state) {
            return state.copy();
        }

        @Override
        public SnakePredictState step(SnakePredictState state, InputCommand input) {
            SnakePredictState s = state.copy();
            if (input != null && input.action >= 0 && !isOpposite(input.action, s.dir)) {
                s.dir = input.action;
            }
            if (!s.alive || s.xs.length == 0) return s;

            s.acc += unitsPerTick;
            if (s.acc < 1024) return s;
            s.acc -= 1024;

            int dx = dx(s.dir);
            int dy = dy(s.dir);
            int nx = s.xs[0] + dx;
            int ny = s.ys[0] + dy;
            if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) {
                if (walls) {
                    s.alive = false;
                    return s;
                }
                nx = Math.floorMod(nx, cols);
                ny = Math.floorMod(ny, rows);
            }
            for (int i = s.xs.length - 1; i > 0; i--) {
                s.xs[i] = s.xs[i - 1];
                s.ys[i] = s.ys[i - 1];
            }
            s.xs[0] = nx;
            s.ys[0] = ny;
            return s;
        }

        @Override
        public double error(SnakePredictState authoritative, SnakePredictState predicted) {
            if (authoritative.alive != predicted.alive) return 99;
            if (authoritative.length() == 0 || predicted.length() == 0) return 0;
            int dx = Math.abs(authoritative.headX() - predicted.headX());
            int dy = Math.abs(authoritative.headY() - predicted.headY());
            if (!walls) {
                dx = Math.min(dx, cols - dx);
                dy = Math.min(dy, rows - dy);
            }
            return Math.max(dx, dy);
        }

        @Override
        public SnakePredictState lerp(SnakePredictState from, SnakePredictState to, double t) {
            return t >= 0.5 ? to.copy() : from.copy();
        }

        private static int dx(int dir) {
            return dir == 1 ? 1 : dir == 3 ? -1 : 0;
        }

        private static int dy(int dir) {
            return dir == 2 ? 1 : dir == 0 ? -1 : 0;
        }

        private static boolean isOpposite(int a, int b) {
            return (a + 2) % 4 == b;
        }
    }
}
