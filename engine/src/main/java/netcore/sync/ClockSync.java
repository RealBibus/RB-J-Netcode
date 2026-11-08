package netcore.sync;

import netcore.net.Connection;

public final class ClockSync {

    private static final int PING_WINDOW = 48;
    private static final long LOSS_AGE_NANOS = 1_500_000_000L;

    private volatile long offsetNanos;
    private volatile long rttNanos = -1;
    private volatile double rttSmooth = -1;
    private volatile boolean synced;
    private volatile long lastPongNanos;

    private double lastRttNanos = -1;
    private volatile double jitterNanos = -1;
    private volatile int jitterMs = -1;
    private volatile int lossPercent;
    private volatile int quality = 100;

    private final long[] pingAt = new long[PING_WINDOW];
    private final boolean[] pingOk = new boolean[PING_WINDOW];
    private int pingSlot;

    private long bestRttNanos = Long.MAX_VALUE;

    public synchronized void onPingSent(long sentNanos) {
        pingAt[pingSlot] = sentNanos;
        pingOk[pingSlot] = false;
        pingSlot = (pingSlot + 1) % PING_WINDOW;
    }

    public synchronized void onPong(long clientSentNanos, long serverNanos, long clientRecvNanos) {
        long rtt = Math.max(0, clientRecvNanos - clientSentNanos);
        long target = serverNanos + rtt / 2 - clientRecvNanos;
        if (!synced) {
            offsetNanos = target;
            synced = true;
        } else {
            long delta = target - offsetNanos;
            if (Math.abs(delta) > 5_000_000L) {
                offsetNanos = target;
            } else {
                offsetNanos += Math.round(delta * 0.2);
            }
        }
        if (rtt < bestRttNanos) bestRttNanos = rtt;
        rttSmooth = rttSmooth < 0 ? rtt : rttSmooth * 0.75 + rtt * 0.25;
        rttNanos = rtt;
        lastPongNanos = clientRecvNanos;

        if (lastRttNanos >= 0) {
            double deviation = Math.abs(rtt - lastRttNanos);
            jitterNanos = jitterNanos < 0 ? deviation : jitterNanos * 0.85 + deviation * 0.15;
            jitterMs = (int) Math.min(9_999L, Math.round(jitterNanos / 1_000_000.0));
        }
        lastRttNanos = rtt;

        for (int i = 0; i < PING_WINDOW; i++) {
            if (pingAt[i] == clientSentNanos) {
                pingOk[i] = true;
                break;
            }
        }
        refreshQuality();
    }

    private void refreshQuality() {
        long now = System.nanoTime();
        int total = 0;
        int lost = 0;
        for (int i = 0; i < PING_WINDOW; i++) {
            long at = pingAt[i];
            if (at == 0) continue;
            if (pingOk[i]) {
                total++;
            } else if (now - at > LOSS_AGE_NANOS) {
                total++;
                lost++;
            }
        }
        lossPercent = total == 0 ? 0 : (int) Math.round(lost * 100.0 / total);
        quality = Connection.score(rttMs(), jitterMs, lossPercent);
    }

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

    public int jitterMs() {
        return jitterMs;
    }

    public int lossPercent() {
        return lossPercent;
    }

    public int quality() {
        return quality;
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
