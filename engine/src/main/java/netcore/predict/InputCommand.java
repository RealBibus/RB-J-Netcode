package netcore.predict;

/** Одно нажатие игрока, привязанное к тику и доле тика. */
public final class InputCommand {

    public final int seq;
    /** Тик, в котором ввод должен быть применён. */
    public final int tick;
    /** Доля тика 0..1 — используется в режиме {@code SUBTICK}. */
    public final float sub;
    /** Код действия; движку всё равно, игра его трактует. */
    public final int action;

    public InputCommand(int seq, int tick, float sub, int action) {
        this.seq = seq;
        this.tick = tick;
        this.sub = Math.max(0f, Math.min(1f, sub));
        this.action = action;
    }

    @Override
    public String toString() {
        return "Input#" + seq + " t=" + tick + " sub=" + String.format("%.2f", sub) + " a=" + action;
    }
}
