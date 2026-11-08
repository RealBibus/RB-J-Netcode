package netcore.time;


public final class TickStats {

    private long ticks;
    private long lateTicks;
    private long resyncs;
    private long maxLagNanos;
    private long lastLagNanos;
    private long windowStart;
    private long windowTicks;
    private double rate;

    synchronized void tick(long lagNanos) {
        ticks++;
        lastLagNanos = lagNanos;
        if (lagNanos > maxLagNanos) maxLagNanos = lagNanos;
        if (lagNanos > 2_000_000L) lateTicks++;
        long now = System.nanoTime();
        if (windowStart == 0) windowStart = now;
        windowTicks++;
        if (now - windowStart >= 1_000_000_000L) {
            rate = windowTicks * 1_000_000_000.0 / (now - windowStart);
            windowStart = now;
            windowTicks = 0;
        }
    }

    synchronized void resync() {
        resyncs++;
    }

    public synchronized long ticks() {
        return ticks;
    }

    public synchronized long lateTicks() {
        return lateTicks;
    }

    public synchronized long resyncs() {
        return resyncs;
    }

    public synchronized double maxLagMs() {
        return maxLagNanos / 1_000_000.0;
    }

    public synchronized double lastLagMs() {
        return lastLagNanos / 1_000_000.0;
    }

    
    public synchronized double actualRate() {
        return rate;
    }
}
