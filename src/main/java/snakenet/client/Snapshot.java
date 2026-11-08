package snakenet.client;

import snakenet.net.Msg;
import snakenet.net.Protocol;

import java.util.ArrayList;
import java.util.List;

/** Снимок игрового поля, каким его увидел клиент. */
public final class Snapshot {

    public static final class FoodView {
        public int x;
        public int y;
        public int kind;
    }

    public static final class SnakeView {
        public int id;
        public int score;
        public int dir;
        public int color;
        public boolean alive;
        public int[] xs = new int[0];
        public int[] ys = new int[0];

        public int length() {
            return xs.length;
        }
    }

    public static final class EventView {
        public int kind;
        public int player;
        public int x;
        public int y;
    }

    public final int tick;
    public final int tickMs;
    public final long recvNanos;
    public final List<FoodView> food = new ArrayList<>();
    public final List<SnakeView> snakes = new ArrayList<>();
    public final List<EventView> events = new ArrayList<>();

    private Snapshot(int tick, int tickMs, long recvNanos) {
        this.tick = tick;
        this.tickMs = tickMs;
        this.recvNanos = recvNanos;
    }

    public SnakeView snake(int id) {
        for (SnakeView s : snakes) if (s.id == id) return s;
        return null;
    }

    public static Snapshot parse(List<Msg> frame, long recvNanos) {
        int tick = 0;
        int tickMs = 100;
        Snapshot snap = null;
        for (Msg m : frame) {
            switch (m.type()) {
                case Protocol.SNAP:
                    tick = m.num("t", 0);
                    tickMs = Math.max(40, m.num("ms", 100));
                    snap = new Snapshot(tick, tickMs, recvNanos);
                    break;
                default:
                    break;
            }
        }
        if (snap == null) return null;
        for (Msg m : frame) {
            switch (m.type()) {
                case Protocol.FOOD: {
                    FoodView f = new FoodView();
                    f.x = m.num("x", 0);
                    f.y = m.num("y", 0);
                    f.kind = m.num("k", 0);
                    snap.food.add(f);
                    break;
                }
                case Protocol.SNAKE: {
                    SnakeView s = new SnakeView();
                    s.id = m.num("p", -1);
                    s.score = m.num("sc", 0);
                    s.dir = m.num("d", 1);
                    s.color = m.num("c", 0);
                    s.alive = m.num("a", 0) == 1;
                    String g = m.raw("g");
                    if (!g.isEmpty()) {
                        String[] parts = g.split(",");
                        int n = parts.length;
                        s.xs = new int[n];
                        s.ys = new int[n];
                        for (int i = 0; i < n; i++) {
                            int c = parts[i].indexOf(':');
                            if (c <= 0) {
                                s.xs[i] = 0;
                                s.ys[i] = 0;
                                continue;
                            }
                            s.xs[i] = Integer.parseInt(parts[i].substring(0, c));
                            s.ys[i] = Integer.parseInt(parts[i].substring(c + 1));
                        }
                    }
                    snap.snakes.add(s);
                    break;
                }
                case Protocol.EVENT: {
                    EventView e = new EventView();
                    e.kind = m.num("k", 0);
                    e.player = m.num("p", -1);
                    e.x = m.num("x", 0);
                    e.y = m.num("y", 0);
                    snap.events.add(e);
                    break;
                }
                default:
                    break;
            }
        }
        return snap;
    }
}
