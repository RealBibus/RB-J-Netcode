package netcore.predict;


public final class PredictionConfig {

    public static final PredictionConfig DEFAULT = new PredictionConfig(1.5, 48, 64, 66);

    private final double snapThreshold;
    private final int historyTicks;
    private final int maxAdvanceTicks;
    private final int interpolationDelayMs;

    public PredictionConfig(double snapThreshold, int historyTicks) {
        this(snapThreshold, historyTicks, 64, 66);
    }

    public PredictionConfig(double snapThreshold, int historyTicks, int maxAdvanceTicks,
                            int interpolationDelayMs) {
        this.snapThreshold = Math.max(0.01, snapThreshold);
        this.historyTicks = Math.max(4, historyTicks);
        this.maxAdvanceTicks = Math.max(4, Math.min(4096, maxAdvanceTicks));
        this.interpolationDelayMs = Math.max(0, Math.min(1000, interpolationDelayMs));
    }

    public static PredictionConfig of(double snapThreshold) {
        return new PredictionConfig(snapThreshold, 48, 64, 66);
    }

    
    public double snapThreshold() {
        return snapThreshold;
    }

    public int historyTicks() {
        return historyTicks;
    }

    public int maxAdvanceTicks() {
        return maxAdvanceTicks;
    }

    public int interpolationDelayMs() {
        return interpolationDelayMs;
    }

    public PredictionConfig withHistory(int ticks) {
        return new PredictionConfig(snapThreshold, ticks, maxAdvanceTicks, interpolationDelayMs);
    }

    public PredictionConfig withMaxAdvance(int ticks) {
        return new PredictionConfig(snapThreshold, historyTicks, ticks, interpolationDelayMs);
    }

    public PredictionConfig withInterpolationDelay(int ms) {
        return new PredictionConfig(snapThreshold, historyTicks, maxAdvanceTicks, ms);
    }
}
