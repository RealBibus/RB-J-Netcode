package snakenet.game;

import java.util.List;
import java.util.Random;
import java.util.Set;






public final class BotBrain {

    private BotBrain() {
    }

    public static int decide(SnakeState s, int cols, int rows, boolean walls,
                             Set<Integer> blocked, List<Food> foods, Random rnd) {
        Cell head = s.head();
        if (head == null) return s.dir;

        int bestDir = s.dir;
        double bestScore = -1e9;

        for (int d = 0; d < 4; d++) {
            if (SnakeState.isOpposite(d, s.dir)) continue;

            int nx = head.x + SnakeState.DX[d];
            int ny = head.y + SnakeState.DY[d];
            boolean outside = nx < 0 || ny < 0 || nx >= cols || ny >= rows;
            if (outside) {
                if (walls) continue;
                nx = Math.floorMod(nx, cols);
                ny = Math.floorMod(ny, rows);
            }

            double score = 0;
            if (blocked.contains(Cell.key(nx, ny, cols))) {
                score -= 1000; 
            }

            
            int free = 0;
            for (int k = 0; k < 4; k++) {
                int ax = nx + SnakeState.DX[k];
                int ay = ny + SnakeState.DY[k];
                boolean out = ax < 0 || ay < 0 || ax >= cols || ay >= rows;
                if (out) {
                    if (walls) continue;
                    ax = Math.floorMod(ax, cols);
                    ay = Math.floorMod(ay, rows);
                }
                if (!blocked.contains(Cell.key(ax, ay, cols))) free++;
            }
            score += free * 12;

            
            int bestDist = Integer.MAX_VALUE;
            for (Food f : foods) {
                int dx = Math.abs(f.x - nx);
                int dy = Math.abs(f.y - ny);
                if (!walls) {
                    dx = Math.min(dx, cols - dx);
                    dy = Math.min(dy, rows - dy);
                }
                bestDist = Math.min(bestDist, dx + dy);
            }
            if (bestDist != Integer.MAX_VALUE) score += 140 - bestDist * 4.0;

            if (d == s.dir) score += 8; 
            score += rnd.nextDouble() * 6;

            if (score > bestScore) {
                bestScore = score;
                bestDir = d;
            }
        }
        return bestDir;
    }
}
