package netcore.predict;

/** Итог сверки предсказания с сервером. */
public record Correction(boolean snapped, double error, int replayed, int tick, int ackSeq) {

    public static final Correction NONE = new Correction(false, 0, 0, -1, -1);

    public boolean minor() {
        return !snapped && error > 0;
    }
}
