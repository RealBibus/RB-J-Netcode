package snakenet.snake;


public final class Cell {
    public final int x;
    public final int y;

    public Cell(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int key(int cols) {
        return y * cols + x;
    }

    public static int key(int x, int y, int cols) {
        return y * cols + x;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cell)) return false;
        Cell c = (Cell) o;
        return x == c.x && y == c.y;
    }

    @Override
    public int hashCode() {
        return x * 73856093 ^ y * 19349663;
    }

    @Override
    public String toString() {
        return x + ":" + y;
    }
}
