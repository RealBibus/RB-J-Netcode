package netcore.sync;

/**
 * Оценка смещения часов сервера по пингам (как в NTP, только проще):
 * берём выборку с минимальным RTT, чтобы не тянуть задержку в смещение.
 */
public final class ClockSync {

    private volatile long offsetNanos;
    private volatile long rttNanos = -1;
    private volatile double rttSmooth = -1;
    private volatile boolean synced;
    private volatile long lastPongNanos;

    private long bestRttNanos = Long.MAX_VALUE;

    /** Сервер ответил на пинг. Все времена — в наносекундах {@link System#nanoTime()}. */
    public synchronized void onPong(long clientSentNanos, long serverNanos, long clientRecvNanos) {
        long rtt = Math.max(0, clientRecvNanos - clientSentNanos);
        long target = serverNanos + rtt / 2 - clientRecvNanos;
        if (!synced) {
            offsetNanos = target;
            synced = true;
        } else {
            // не прыгаем: часы подкручиваем плавно, иначе рендер дёргается
            long delta = target - offsetNanos;
            if (Math.abs(delta) > 5_000_000L) {
                offsetNanos = target;          // совсем разъехались — берём как есть
            } else {
                offsetNanos += Math.round(delta * 0.2);
            }
        }
        if (rtt < bestRttNanos) bestRttNanos = rtt;
        rttSmooth = rttSmooth < 0 ? rtt : rttSmooth * 0.75 + rtt * 0.25;
        rttNanos = rtt;
        lastPongNanos = clientRecvNanos;
    }

    /** Текущее время сервера в шкале {@link System#nanoTime()}. */
    public long serverNanosNow() {
        return System.nanoTime() + offsetNanos;
    }

    public double serverSecondsNow() {
        return serverNanosNow() / 1_000_000_000.0;
    }

    public int rttMs() {
        double r = rttSmooth >= 0 ? rttSmooth : rttNanos;
        return r < 0 ? -1 : (int) Math.round(r / 1_000_000.0);
    }

    public double rttMsPrecise() {
        double r = rttSmooth >= 0 ? rttSmooth : rttNanos;
        return r < 0 ? -1 : r / 1_000_000.0;
    }

    public long offsetNanos() {
        return offsetNanos;
    }

    public boolean synced() {
        return synced;
    }

    public long millisSincePong() {
        return lastPongNanos == 0 ? -1 : (System.nanoTime() - lastPongNanos) / 1_000_000L;
    }
}
