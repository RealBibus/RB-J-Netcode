package snakenet.client;

import netcore.sync.ClockSync;

/**
 * Оценка «где сейчас сервер» по последнему снимку и синхронизации часов.
 * Нужна, чтобы клиент знал, на какой тик ставить свой ввод и до какого тика
 * докручивать предсказание.
 *
 * <p>Позиция в тиках считается непрерывно и плавно: поправки от сервера
 * меняют цель, а не текущее значение, поэтому рендер не дёргается на каждом
 * подтверждении ввода.</p>
 */
public final class ServerClock {

    private volatile int lastTick;
    private volatile long lastServerNanos;
    private volatile long lastRecvNanos;
    private volatile int tickRate = 32;

    /** Текущая поправка (её видит рендер). */
    private double tickOffset;
    /** Куда поправка должна приехать. */
    private double targetOffset;
    private long lastSlewNanos = System.nanoTime();

    public void onSnapshot(int tick, long serverNanos, int tickRate, long recvNanos) {
        this.lastTick = tick;
        this.lastServerNanos = serverNanos;
        this.lastRecvNanos = recvNanos;
        if (tickRate > 0) this.tickRate = tickRate;
    }

    public int tickRate() {
        return tickRate;
    }

    public long nanosPerTick() {
        return 1_000_000_000L / Math.max(1, tickRate);
    }

    /** Время сервера в шкале клиента. */
    public long serverNanosNow(ClockSync clock) {
        if (clock != null && clock.synced()) return clock.serverNanosNow();
        if (lastServerNanos == 0) return System.nanoTime();
        return lastServerNanos + (System.nanoTime() - lastRecvNanos);
    }

    /**
     * Непрерывная позиция во времени сервера, в тиках.
     * Единая точка правды: и рендер, и ввод берут тик отсюда.
     */
    public double tickPosition(ClockSync clock) {
        slew();
        if (lastServerNanos == 0) return 0;
        long delta = serverNanosNow(clock) - lastServerNanos;
        if (delta < 0) delta = 0;
        return lastTick + delta / (double) nanosPerTick() + tickOffset;
    }

    /** Подтягиваем поправку за ~0.7 с, чтобы не было скачка фазы. */
    private void slew() {
        long now = System.nanoTime();
        double dt = (now - lastSlewNanos) / 1_000_000_000.0;
        lastSlewNanos = now;
        if (dt <= 0 || dt > 0.25) return;
        double diff = targetOffset - tickOffset;
        if (Math.abs(diff) < 0.002) {
            tickOffset = targetOffset;
            return;
        }
        tickOffset += diff * Math.min(1, dt / 0.7);
    }

    public int tickAt(ClockSync clock) {
        return (int) Math.floor(tickPosition(clock));
    }

    public double subAt(ClockSync clock) {
        double pos = tickPosition(clock);
        return pos - Math.floor(pos);
    }

    /**
     * Ввод, отправленный на тике {@code sentTick}, сервер применил на {@code appliedTick}.
     * Если применил позже — мы считали часы отстающими; подтягиваем цель,
     * а текущее значение доедет плавно.
     */
    public void observeApply(int sentTick, int appliedTick) {
        int delta = appliedTick - sentTick;
        if (delta == 0) {
            targetOffset *= 0.95;
            return;
        }
        if (Math.abs(delta) > 6) {
            // мусорный ответ (например, старый seq после рестарта раунда) — игнорируем
            return;
        }
        targetOffset = Math.max(-6, Math.min(6, targetOffset + delta * 0.5));
    }

    public double tickOffset() {
        return tickOffset;
    }

    /** Сколько тиков добавить к текущему, чтобы ввод успел дойти. */
    public int leadTicks(ClockSync clock) {
        double rttMs = clock == null ? 10 : Math.max(0, clock.rttMsPrecise());
        double tickMs = nanosPerTick() / 1_000_000.0;
        int lead = 1 + (int) Math.ceil((rttMs * 0.5) / tickMs);
        return Math.max(1, Math.min(6, lead));
    }
}
