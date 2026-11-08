package snakenet.client;

import netcore.sync.ClockSync;

/**
 * Оценка «где сейчас сервер» по последнему снимку и синхронизации часов.
 * Нужна, чтобы клиент знал, на какой тик ставить свой ввод и до какого тика
 * докручивать предсказание.
 */
public final class ServerClock {

    private volatile int lastTick;
    private volatile long lastServerNanos;
    private volatile long lastRecvNanos;
    private volatile int tickRate = 32;

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

    /** Оценка текущего тика сервера. */
    public int tickAt(ClockSync clock) {
        if (lastServerNanos == 0) return 0;
        long delta = serverNanosNow(clock) - lastServerNanos;
        if (delta < 0) delta = 0;
        return lastTick + (int) (delta / nanosPerTick());
    }

    /** Доля текущего тика 0..1. */
    public double subAt(ClockSync clock) {
        if (lastServerNanos == 0) return 0;
        long delta = serverNanosNow(clock) - lastServerNanos;
        if (delta < 0) delta = 0;
        return (delta % nanosPerTick()) / (double) nanosPerTick();
    }

    /** Сколько тиков добавить к текущему, чтобы ввод успел дойти. */
    public int leadTicks(ClockSync clock) {
        double rttMs = clock == null ? 10 : Math.max(0, clock.rttMsPrecise());
        double tickMs = nanosPerTick() / 1_000_000.0;
        // ровно столько, сколько нужно на путь до сервера; опоздавший ввод
        // сервер применит сразу, так что лишний запас только добавляет лаг
        int lead = 1 + (int) Math.ceil((rttMs * 0.5) / tickMs);
        return Math.max(1, Math.min(6, lead));
    }
}
