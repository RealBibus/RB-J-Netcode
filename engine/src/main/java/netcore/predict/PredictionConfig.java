package netcore.predict;


public final class PredictionConfig {

    public static final PredictionConfig DEFAULT = new PredictionConfig(1.5, 48);

    private final double snapThreshold;
    private final int historyTicks;

    public PredictionConfig(double snapThreshold, int historyTicks) {
        this.snapThreshold = Math.max(0.01, snapThreshold);
        this.historyTicks = Math.max(4, historyTicks);
    }

    public static PredictionConfig of(double snapThreshold) {
        return new PredictionConfig(snapThreshold, 48);
    }

    
    public double snapThreshold() {
        return snapThreshold;
    }

    public int historyTicks() {
        return historyTicks;
    }
}
