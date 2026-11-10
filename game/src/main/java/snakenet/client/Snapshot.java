package snakenet.client;

import netcore.netcode.Msg;
import snakenet.net.Net;

import java.util.ArrayList;
import java.util.List;


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
        
        public int ackSeq;
        
        public int acc;
        
        public int ping = -1;
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
    public final long serverNanos;
    public final int tickRate;
    public final int unitsPerTick;
    public final long recvNanos;
    public boolean subtick;
    public int subSteps = 1;
    public int subIn;
    public int subApplied;
    public int subMerged;
    public int subSameTick;
    public int subOrderErrors;
    public int subLastPermille;
    public int[] subBuckets = new int[0];
    public long inputDropped;
    public long voiceAccepted;
    public long voiceRejected;
    public final List<FoodView> food = new ArrayList<>();
    public final List<SnakeView> snakes = new ArrayList<>();
    public final List<EventView> events = new ArrayList<>();

    private Snapshot(int tick, long serverNanos, int tickRate, int unitsPerTick, long recvNanos) {
        this.tick = tick;
        this.serverNanos = serverNanos;
        this.tickRate = tickRate;
        this.unitsPerTick = unitsPerTick;
        this.recvNanos = recvNanos;
    }

    public SnakeView snake(int id) {
        for (SnakeView s : snakes) if (s.id == id) return s;
        return null;
    }

    public static Snapshot parse(List<Msg> frame, long recvNanos) {
        Snapshot snap = null;
        Msg head = null;
        for (Msg m : frame) {
            if (m.is(Net.SNAP)) {
                head = m;
                snap = new Snapshot(m.num("t", 0), m.numL("st", 0L), m.num("rate", 32), m.num("ms", 0), recvNanos);
                break;
            }
        }
        if (snap == null) return null;
        snap.subtick = head.num("se", 0) == 1;
        snap.subSteps = Math.max(1, Math.min(16, head.num("ss", 1)));
        snap.subIn = head.num("si", 0);
        snap.subApplied = head.num("sa", 0);
        snap.subMerged = head.num("sm", 0);
        snap.subSameTick = head.num("sx", 0);
        snap.subOrderErrors = head.num("so", 0);
        snap.subLastPermille = head.num("sl", 0);
        snap.inputDropped = head.numL("ix", 0L);
        snap.voiceAccepted = head.numL("vok", 0L);
        snap.voiceRejected = head.numL("vdr", 0L);
        String buckets = head.raw("sb");
        if (!buckets.isEmpty()) {
            String[] parts = buckets.split(",");
            snap.subBuckets = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    snap.subBuckets[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException ignored) {
                    snap.subBuckets[i] = 0;
                }
            }
        }
        for (Msg m : frame) {
            if (m.is(Net.FOOD)) {
                FoodView f = new FoodView();
                f.x = m.num("x", 0);
                f.y = m.num("y", 0);
                f.kind = m.num("k", 0);
                snap.food.add(f);
            } else if (m.is(Net.SNAKE)) {
                SnakeView s = new SnakeView();
                s.id = m.num("p", -1);
                s.score = m.num("sc", 0);
                s.dir = m.num("d", 1);
                s.color = m.num("c", 0);
                s.alive = m.num("a", 0) == 1;
                s.ackSeq = m.num("ls", 0);
                s.acc = m.num("ac", 0);
                s.ping = m.num("pg", -1);
                String g = m.raw("g");
                if (!g.isEmpty()) {
                    String[] parts = g.split(",");
                    int n = parts.length;
                    s.xs = new int[n];
                    s.ys = new int[n];
                    for (int i = 0; i < n; i++) {
                        int c = parts[i].indexOf(':');
                        if (c <= 0) continue;
                        s.xs[i] = Integer.parseInt(parts[i].substring(0, c));
                        s.ys[i] = Integer.parseInt(parts[i].substring(c + 1));
                    }
                }
                snap.snakes.add(s);
            } else if (m.is(Net.EVENT)) {
                EventView e = new EventView();
                e.kind = m.num("k", 0);
                e.player = m.num("p", -1);
                e.x = m.num("x", 0);
                e.y = m.num("y", 0);
                snap.events.add(e);
            }
        }
        return snap;
    }
}
