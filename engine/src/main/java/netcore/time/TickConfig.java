package netcore.time;

import java.util.Objects;


public final class TickConfig {

    public static final int MIN_RATE = 1;
    public static final int MAX_RATE = 128;

    public static final TickConfig DEFAULT = fixed(64);

    private final int rate;
    private final TickMode mode;
    private final int subSteps;
    private final boolean precise;
    private final int maxCatchUp;
    private final long spinWindowNanos;
    private final int threadPriority;

    private TickConfig(int rate, TickMode mode, int subSteps, boolean precise,
                       int maxCatchUp, long spinWindowNanos, int threadPriority) {
        this.rate = Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
        this.mode = mode;
        this.subSteps = Math.max(1, Math.min(16, subSteps));
        this.precise = precise;
        this.maxCatchUp = Math.max(1, Math.min(64, maxCatchUp));
        this.spinWindowNanos = Math.max(0, Math.min(20_000_000L, spinWindowNanos));
        this.threadPriority = Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, threadPriority));
    }

    public static TickConfig fixed(int rate) {
        return new TickConfig(rate, TickMode.FIXED, 1, true, 4, 900_000L, Thread.NORM_PRIORITY + 1);
    }

    public static TickConfig subtick(int rate, int subSteps) {
        return new TickConfig(rate, TickMode.SUBTICK, subSteps, true, 4, 900_000L, Thread.NORM_PRIORITY + 1);
    }

    public TickConfig withPrecise(boolean on) {
        return new TickConfig(rate, mode, subSteps, on, maxCatchUp, spinWindowNanos, threadPriority);
    }

    public TickConfig withCatchUp(int ticks) {
        return new TickConfig(rate, mode, subSteps, precise, ticks, spinWindowNanos, threadPriority);
    }

    public TickConfig withSpinWindow(long nanos) {
        return new TickConfig(rate, mode, subSteps, precise, maxCatchUp, nanos, threadPriority);
    }

    public TickConfig withPriority(int priority) {
        return new TickConfig(rate, mode, subSteps, precise, maxCatchUp, spinWindowNanos, priority);
    }

    public int rate() {
        return rate;
    }

    public TickMode mode() {
        return mode;
    }

    public int subSteps() {
        return mode == TickMode.SUBTICK ? subSteps : 1;
    }

    public boolean isSubtick() {
        return mode == TickMode.SUBTICK;
    }

    public boolean precise() {
        return precise;
    }

    public int maxCatchUp() {
        return maxCatchUp;
    }

    public long spinWindowNanos() {
        return spinWindowNanos;
    }

    public int threadPriority() {
        return threadPriority;
    }

    
    public double dt() {
        return 1.0 / rate;
    }

    
    public long tickNanos() {
        return 1_000_000_000L / rate;
    }

    
    public int effectiveStepsPerSecond() {
        return rate * subSteps();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TickConfig)) return false;
        TickConfig c = (TickConfig) o;
        return rate == c.rate && mode == c.mode && subSteps() == c.subSteps()
                && precise == c.precise && maxCatchUp == c.maxCatchUp
                && spinWindowNanos == c.spinWindowNanos && threadPriority == c.threadPriority;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rate, mode, subSteps(), precise, maxCatchUp, spinWindowNanos, threadPriority);
    }

    @Override
    public String toString() {
        return mode == TickMode.SUBTICK
                ? rate + " ticks/s, subtick (" + subSteps + " input slots)"
                : rate + " ticks/s";
    }
}
