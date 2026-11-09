package netcore.voice;

public final class VoiceConfig {

    public static final VoiceConfig DEFAULT = new VoiceConfig();

    private final float sampleRate;
    private final int frameSamples;
    private final int frameMs;
    private final int jitterTargetFrames;
    private final int jitterMaxFrames;
    private final boolean adaptiveJitter;
    private final boolean voiceActivity;
    private final float vadThreshold;
    private final int maxPacketBytes;
    private final int maxPacketsPerSec;
    private final int maxBytesPerSec;
    private final VoiceMode mode;

    public VoiceConfig() {
        this(16000f, 320, 20, 3, 8, true, false, 0.02f, 640, 60, 96 * 1024, VoiceMode.UDP);
    }

    public VoiceConfig(float sampleRate, int frameSamples, int frameMs, int jitterTargetFrames,
                       int jitterMaxFrames, boolean adaptiveJitter, boolean voiceActivity,
                       float vadThreshold, int maxPacketBytes, int maxPacketsPerSec,
                       int maxBytesPerSec, VoiceMode mode) {
        this.sampleRate = Math.max(8000f, Math.min(48000f, sampleRate));
        this.frameSamples = Math.max(80, Math.min(4096, frameSamples));
        this.frameMs = Math.max(10, Math.min(120, frameMs));
        this.jitterTargetFrames = Math.max(1, Math.min(32, jitterTargetFrames));
        this.jitterMaxFrames = Math.max(this.jitterTargetFrames, Math.min(128, jitterMaxFrames));
        this.adaptiveJitter = adaptiveJitter;
        this.voiceActivity = voiceActivity;
        this.vadThreshold = Math.max(0f, Math.min(1f, vadThreshold));
        this.maxPacketBytes = Math.max(64, Math.min(8192, maxPacketBytes));
        this.maxPacketsPerSec = Math.max(4, Math.min(400, maxPacketsPerSec));
        this.maxBytesPerSec = Math.max(1024, maxBytesPerSec);
        this.mode = mode == null ? VoiceMode.UDP : mode;
    }

    public float sampleRate() {
        return sampleRate;
    }

    public int frameSamples() {
        return frameSamples;
    }

    public int frameMs() {
        return frameMs;
    }

    public long frameNanos() {
        return frameMs * 1_000_000L;
    }

    public int jitterTargetFrames() {
        return jitterTargetFrames;
    }

    public int jitterMaxFrames() {
        return jitterMaxFrames;
    }

    public boolean adaptiveJitter() {
        return adaptiveJitter;
    }

    public boolean voiceActivity() {
        return voiceActivity;
    }

    public float vadThreshold() {
        return vadThreshold;
    }

    public int maxPacketBytes() {
        return maxPacketBytes;
    }

    public int maxPacketsPerSec() {
        return maxPacketsPerSec;
    }

    public int maxBytesPerSec() {
        return maxBytesPerSec;
    }

    public VoiceMode mode() {
        return mode;
    }

    public VoiceConfig withMode(VoiceMode value) {
        return new VoiceConfig(sampleRate, frameSamples, frameMs, jitterTargetFrames, jitterMaxFrames,
                adaptiveJitter, voiceActivity, vadThreshold, maxPacketBytes, maxPacketsPerSec,
                maxBytesPerSec, value);
    }

    public VoiceConfig withJitter(int targetFrames, int maxFrames, boolean adaptive) {
        return new VoiceConfig(sampleRate, frameSamples, frameMs, targetFrames, maxFrames,
                adaptive, voiceActivity, vadThreshold, maxPacketBytes, maxPacketsPerSec,
                maxBytesPerSec, mode);
    }

    public VoiceConfig withVoiceActivity(boolean on, float threshold) {
        return new VoiceConfig(sampleRate, frameSamples, frameMs, jitterTargetFrames, jitterMaxFrames,
                adaptiveJitter, on, threshold, maxPacketBytes, maxPacketsPerSec,
                maxBytesPerSec, mode);
    }

    public VoiceConfig withLimits(int maxPacketBytes, int maxPacketsPerSec, int maxBytesPerSec) {
        return new VoiceConfig(sampleRate, frameSamples, frameMs, jitterTargetFrames, jitterMaxFrames,
                adaptiveJitter, voiceActivity, vadThreshold, maxPacketBytes, maxPacketsPerSec,
                maxBytesPerSec, mode);
    }
}
