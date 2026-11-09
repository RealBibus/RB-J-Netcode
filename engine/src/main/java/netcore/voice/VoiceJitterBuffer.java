package netcore.voice;

import java.util.Map;
import java.util.TreeMap;

public final class VoiceJitterBuffer {

    private final VoiceConfig config;
    private final TreeMap<Long, short[]> frames = new TreeMap<>();

    private int targetFrames;
    private long lastExtended = -1;
    private long expected = -1;
    private long lastPushNanos;
    private long lastPollNanos;
    private boolean playing;

    private long received;
    private long played;
    private long dropped;
    private long late;
    private long duplicates;
    private long reordered;
    private long concealed;
    private long underruns;

    public VoiceJitterBuffer(VoiceConfig config) {
        this.config = config == null ? VoiceConfig.DEFAULT : config;
        this.targetFrames = this.config.jitterTargetFrames();
    }

    public synchronized boolean push(int sequence, short[] pcm, long nowNanos) {
        if (pcm == null || pcm.length == 0) return false;
        if (pcm.length * 2 > config.maxPacketBytes()) {
            dropped++;
            return false;
        }
        received++;
        long ext = extend(sequence);
        if (frames.containsKey(ext)) {
            duplicates++;
            return false;
        }
        if (ext < expected) {
            late++;
            return false;
        }
        if (expected >= 0 && ext > expected) reordered++;
        frames.put(ext, pcm);
        lastPushNanos = nowNanos;
        while (frames.size() > config.jitterMaxFrames()) {
            Map.Entry<Long, short[]> first = frames.pollFirstEntry();
            if (first != null) dropped++;
        }
        adapt(nowNanos);
        return true;
    }

    public synchronized short[] poll(long nowNanos) {
        if (frames.isEmpty()) {
            if (playing && nowNanos - lastPollNanos > config.frameNanos() * 2) {
                playing = false;
            }
            if (playing) underruns++;
            lastPollNanos = nowNanos;
            return null;
        }
        if (!playing) {
            int target = Math.min(targetFrames, config.jitterMaxFrames());
            if (frames.size() < target) {
                lastPollNanos = nowNanos;
                return null;
            }
            playing = true;
        }
        Map.Entry<Long, short[]> first = frames.firstEntry();
        long ext = first.getKey();
        if (ext < expected) {
            frames.pollFirstEntry();
            late++;
            return null;
        }
        if (expected >= 0 && ext > expected) {
            expected = ext;
            concealed++;
            lastPollNanos = nowNanos;
            return new short[config.frameSamples()];
        }
        frames.pollFirstEntry();
        expected = ext + 1;
        played++;
        lastPollNanos = nowNanos;
        return first.getValue();
    }

    private long extend(int sequence) {
        if (lastExtended < 0) {
            lastExtended = sequence;
            return sequence;
        }
        long base = lastExtended & ~0xffffL;
        long candidate = base | (sequence & 0xffffL);
        if (candidate < lastExtended - 32768L) candidate += 65536L;
        else if (candidate > lastExtended + 32768L) candidate -= 65536L;
        if (candidate > lastExtended) lastExtended = candidate;
        return candidate;
    }

    private void adapt(long nowNanos) {
        if (!config.adaptiveJitter()) return;
        int queued = frames.size();
        if (queued >= targetFrames + 2 && nowNanos - lastPushNanos < config.frameNanos() * 3) {
            targetFrames = Math.min(config.jitterMaxFrames(), targetFrames + 1);
        }
        if (underruns > 0 && queued <= 1 && nowNanos - lastPollNanos > config.frameNanos() * 8) {
            targetFrames = Math.min(config.jitterMaxFrames(), targetFrames + 1);
            underruns = Math.max(0, underruns - 1);
        }
        if (queued <= 1 && nowNanos - lastPushNanos > 2_000_000_000L) {
            targetFrames = Math.max(config.jitterTargetFrames(), targetFrames - 1);
        }
    }

    public synchronized boolean active(long nowNanos) {
        return lastPushNanos > 0 && nowNanos - lastPushNanos < 400_000_000L;
    }

    public synchronized int queued() {
        return frames.size();
    }

    public synchronized int targetFrames() {
        return targetFrames;
    }

    public synchronized long received() {
        return received;
    }

    public synchronized long played() {
        return played;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized long late() {
        return late;
    }

    public synchronized long duplicates() {
        return duplicates;
    }

    public synchronized long reordered() {
        return reordered;
    }

    public synchronized long concealed() {
        return concealed;
    }

    public synchronized long underruns() {
        return underruns;
    }

    public synchronized void reset() {
        frames.clear();
        lastExtended = -1;
        expected = -1;
        playing = false;
        lastPushNanos = 0;
        lastPollNanos = 0;
        targetFrames = config.jitterTargetFrames();
        received = 0;
        played = 0;
        dropped = 0;
        late = 0;
        duplicates = 0;
        reordered = 0;
        concealed = 0;
        underruns = 0;
    }
}
