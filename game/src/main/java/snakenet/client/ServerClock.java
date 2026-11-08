package snakenet.client;

import netcore.sync.ClockSync;










public final class ServerClock {

    private volatile int lastTick;
    private volatile long lastServerNanos;
    private volatile long lastRecvNanos;
    private volatile int tickRate = 32;

    
    private double tickOffset;
    
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

    
    public long serverNanosNow(ClockSync clock) {
        if (clock != null && clock.synced()) return clock.serverNanosNow();
        if (lastServerNanos == 0) return System.nanoTime();
        return lastServerNanos + (System.nanoTime() - lastRecvNanos);
    }

    



    public double tickPosition(ClockSync clock) {
        slew();
        if (lastServerNanos == 0) return 0;
        long delta = serverNanosNow(clock) - lastServerNanos;
        if (delta < 0) delta = 0;
        return lastTick + delta / (double) nanosPerTick() + tickOffset;
    }

    
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

    




    public void observeApply(int sentTick, int appliedTick) {
        int delta = appliedTick - sentTick;
        if (delta == 0) {
            targetOffset *= 0.95;
            return;
        }
        if (Math.abs(delta) > 6) {
            
            return;
        }
        targetOffset = Math.max(-6, Math.min(6, targetOffset + delta * 0.5));
    }

    public double tickOffset() {
        return tickOffset;
    }

    
    public int leadTicks(ClockSync clock) {
        double rttMs = clock == null ? 10 : Math.max(0, clock.rttMsPrecise());
        double tickMs = nanosPerTick() / 1_000_000.0;
        int lead = 1 + (int) Math.ceil((rttMs * 0.5) / tickMs);
        return Math.max(1, Math.min(6, lead));
    }
}
