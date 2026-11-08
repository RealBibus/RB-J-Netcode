package netcore.time;





public final class TickClock {

    private final TickConfig config;
    private volatile long originNanos;

    public TickClock(TickConfig config, long originNanos) {
        this.config = config;
        this.originNanos = originNanos;
    }

    public TickConfig config() {
        return config;
    }

    public void reset(long originNanos) {
        this.originNanos = originNanos;
    }

    public long originNanos() {
        return originNanos;
    }

    
    public int tickAt(long nanos) {
        long delta = nanos - originNanos;
        if (delta <= 0) return 0;
        return (int) (delta / config.tickNanos());
    }

    
    public double alphaAt(long nanos) {
        long delta = nanos - originNanos;
        if (delta <= 0) return 0;
        long rem = delta % config.tickNanos();
        return rem / (double) config.tickNanos();
    }

    
    public long timeOfTick(int tick) {
        return originNanos + (long) tick * config.tickNanos();
    }
}
