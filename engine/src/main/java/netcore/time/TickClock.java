package netcore.time;

/**
 * Перевод времени в тики. Чистая математика без потоков — используется и
 * сервером, и клиентом (клиент подставляет сюда оценённое время сервера).
 */
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

    /** Номер тика, в котором находится момент {@code nanos}. */
    public int tickAt(long nanos) {
        long delta = nanos - originNanos;
        if (delta <= 0) return 0;
        return (int) (delta / config.tickNanos());
    }

    /** Доля текущего тика 0..1. */
    public double alphaAt(long nanos) {
        long delta = nanos - originNanos;
        if (delta <= 0) return 0;
        long rem = delta % config.tickNanos();
        return rem / (double) config.tickNanos();
    }

    /** Момент начала тика. */
    public long timeOfTick(int tick) {
        return originNanos + (long) tick * config.tickNanos();
    }
}
