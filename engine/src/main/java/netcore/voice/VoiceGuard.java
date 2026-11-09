package netcore.voice;

public final class VoiceGuard {

    private final VoiceConfig config;

    private long windowNanos;
    private int packets;
    private long bytes;
    private long lastAcceptNanos;
    private int lastSequence = -1;

    private long accepted;
    private long rejectedRate;
    private long rejectedSize;
    private long rejectedOrder;
    private long rejectedInterval;
    private long silentFrames;

    public VoiceGuard(VoiceConfig config) {
        this.config = config == null ? VoiceConfig.DEFAULT : config;
    }

    public synchronized boolean accept(int sequence, short[] pcm, long nowNanos) {
        if (pcm == null || pcm.length == 0) {
            rejectedSize++;
            return false;
        }
        int size = pcm.length * 2;
        if (size > config.maxPacketBytes()) {
            rejectedSize++;
            return false;
        }
        if (nowNanos - windowNanos > 1_000_000_000L) {
            windowNanos = nowNanos;
            packets = 0;
            bytes = 0;
        }
        if (lastSequence >= 0) {
            int delta = (sequence - lastSequence) & 0xffff;
            if (delta == 0 || delta > 0x8000) {
                rejectedOrder++;
                return false;
            }
        }
        long minInterval = Math.max(1_000_000L, config.frameNanos() / 2);
        if (lastAcceptNanos > 0 && nowNanos - lastAcceptNanos < minInterval) {
            rejectedInterval++;
            return false;
        }
        packets++;
        bytes += size;
        if (packets > config.maxPacketsPerSec() || bytes > config.maxBytesPerSec()) {
            rejectedRate++;
            return false;
        }
        if (config.voiceActivity() && rms(pcm) < config.vadThreshold()) {
            silentFrames++;
        }
        lastSequence = sequence;
        lastAcceptNanos = nowNanos;
        accepted++;
        return true;
    }

    public static double rms(short[] pcm) {
        if (pcm == null || pcm.length == 0) return 0;
        double sum = 0;
        for (short s : pcm) sum += (double) s * s;
        return Math.sqrt(sum / pcm.length) / 32768.0;
    }

    public synchronized long accepted() {
        return accepted;
    }

    public synchronized long rejectedRate() {
        return rejectedRate;
    }

    public synchronized long rejectedSize() {
        return rejectedSize;
    }

    public synchronized long rejectedOrder() {
        return rejectedOrder;
    }

    public synchronized long rejectedInterval() {
        return rejectedInterval;
    }

    public synchronized long silentFrames() {
        return silentFrames;
    }

    public synchronized long rejected() {
        return rejectedRate + rejectedSize + rejectedOrder + rejectedInterval;
    }

    public synchronized void reset() {
        windowNanos = 0;
        packets = 0;
        bytes = 0;
        lastAcceptNanos = 0;
        lastSequence = -1;
        accepted = 0;
        rejectedRate = 0;
        rejectedSize = 0;
        rejectedOrder = 0;
        rejectedInterval = 0;
        silentFrames = 0;
    }
}
