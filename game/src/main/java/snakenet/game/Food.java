package snakenet.game;

/** Еда на поле. kind влияет только на внешний вид. */
public final class Food {
    public final int x;
    public final int y;
    public final int kind;

    public Food(int x, int y, int kind) {
        this.x = x;
        this.y = y;
        this.kind = kind;
    }
}
