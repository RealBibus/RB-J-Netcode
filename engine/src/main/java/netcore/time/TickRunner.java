package netcore.time;


public final class TickRunner implements AutoCloseable {

    public interface Listener {
        void onTick(int tick, double lagMs);
    }

    private final TickConfig config;
    private final Listener listener;
    private final int maxCatchUp;
    private final long spinWindowNanos;
    private final TickStats stats = new TickStats();

    private Thread thread;
    private volatile boolean running;
    private volatile boolean precise;
    private volatile int tick;
    private volatile long originNanos;

    public TickRunner(TickConfig config, Listener listener) {
        this(config, listener, config.maxCatchUp());
    }

    public TickRunner(TickConfig config, Listener listener, int maxCatchUp) {
        this.config = config;
        this.listener = listener;
        this.maxCatchUp = Math.max(1, maxCatchUp);
        this.spinWindowNanos = config.spinWindowNanos();
        this.precise = config.precise();
    }

    public TickConfig config() {
        return config;
    }

    public TickStats stats() {
        return stats;
    }

    public int currentTick() {
        return tick;
    }

    public long originNanos() {
        return originNanos;
    }

    public boolean precise() {
        return precise;
    }

    public void setPrecise(boolean precise) {
        this.precise = precise;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "netcore-tick");
        thread.setDaemon(true);
        thread.setPriority(config.threadPriority());
        thread.start();
    }

    private void loop() {
        originNanos = System.nanoTime();
        long period = config.tickNanos();
        long next = originNanos + period;
        int local = 0;
        while (running) {
            long now = System.nanoTime();
            if (now < next) {
                waitUntil(next);
                continue;
            }
            long lag = now - next;
            local++;
            tick = local;
            stats.tick(lag);
            try {
                listener.onTick(local, lag / 1_000_000.0);
            } catch (Throwable t) {
                System.err.println("[netcore] tick " + local + " failed: " + t);
            }
            next += period;
            if (System.nanoTime() - next > period * maxCatchUp) {
                next = System.nanoTime() + period;
                stats.resync();
            }
        }
    }

    private void waitUntil(long deadline) {
        while (running) {
            long remain = deadline - System.nanoTime();
            if (remain <= 0) return;
            if (precise && remain <= spinWindowNanos) {
                Thread.onSpinWait();
            } else {
                long millis = (remain - spinWindowNanos) / 1_000_000L;
                if (millis < 1) millis = 1;
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
