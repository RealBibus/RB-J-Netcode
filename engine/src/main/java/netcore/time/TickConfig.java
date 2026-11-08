package netcore.time;

import java.util.Objects;

/** Настройки тика: частота 1..128 и режим. */
public final class TickConfig {

    public static final int MIN_RATE = 1;
    public static final int MAX_RATE = 128;

    public static final TickConfig DEFAULT = fixed(64);

    private final int rate;
    private final TickMode mode;
    private final int subSteps;

    private TickConfig(int rate, TickMode mode, int subSteps) {
        this.rate = Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
        this.mode = mode;
        this.subSteps = Math.max(1, Math.min(16, subSteps));
    }

    public static TickConfig fixed(int rate) {
        return new TickConfig(rate, TickMode.FIXED, 1);
    }

    public static TickConfig subtick(int rate, int subSteps) {
        return new TickConfig(rate, TickMode.SUBTICK, subSteps);
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

    /** Секунд на тик. */
    public double dt() {
        return 1.0 / rate;
    }

    /** Наносекунд на тик (с точностью до 1 нс). */
    public long tickNanos() {
        return 1_000_000_000L / rate;
    }

    /** Сколько тиков в секунду получает симуляция с учётом подшагов. */
    public int effectiveStepsPerSecond() {
        return rate * subSteps();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TickConfig)) return false;
        TickConfig c = (TickConfig) o;
        return rate == c.rate && mode == c.mode && subSteps() == c.subSteps();
    }

    @Override
    public int hashCode() {
        return Objects.hash(rate, mode, subSteps());
    }

    @Override
    public String toString() {
        return mode == TickMode.SUBTICK
                ? rate + " тик/с, subtick ×" + subSteps
                : rate + " тик/с";
    }
}
