package snakenet.game;

import java.util.ArrayDeque;
import java.util.Deque;


public final class SnakeState {

    public static final int UP = 0;
    public static final int RIGHT = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 3;

    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DY = {-1, 0, 1, 0};
    public static final String[] DIR_NAMES = {"up", "right", "down", "left"};

    
    public static final int UNITS = 1024;

    public final int id;
    public final String name;
    public final int color;
    public final boolean bot;

    
    public final Deque<Cell> body = new ArrayDeque<>();

    public int dir = RIGHT;
    public int pendingDir = -1;
    public int queuedDir = -1;
    
    public int acc;
    public int score;
    public int grow;
    public boolean alive = true;

    public SnakeState(int id, String name, int color, boolean bot) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.bot = bot;
    }

    public Cell head() {
        return body.peekFirst();
    }

    public int length() {
        return body.size();
    }

    
    public void place(int hx, int hy, int direction, int length) {
        body.clear();
        dir = direction;
        pendingDir = -1;
        queuedDir = -1;
        for (int i = 0; i < length; i++) {
            body.addLast(new Cell(hx - DX[direction] * i, hy - DY[direction] * i));
        }
        acc = 0;
        score = 0;
        grow = 0;
        alive = true;
    }

    public static boolean isOpposite(int a, int b) {
        return (a + 2) % 4 == b;
    }
}
