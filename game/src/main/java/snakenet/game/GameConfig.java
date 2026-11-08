package snakenet.game;

import netcore.time.TickConfig;
import snakenet.net.Msg;

public final class GameConfig {

    public static final String[] SIZE_NAMES = {"Mini", "Classic", "Large", "Arena"};
    public static final int[][] SIZE = {{24, 16}, {32, 22}, {40, 26}, {44, 18}};

    public static final String[] SPEED_NAMES = {"Chill", "Normal", "Fast", "Insane"};
    public static final int[] SPEED_CELLS = {6, 9, 12, 15};

    public static final String[] TICK_NAMES = {"16 Hz", "32 Hz", "64 Hz", "128 Hz"};
    public static final int[] TICK_RATES = {16, 32, 64, 128};

    public int cols = SIZE[1][0];
    public int rows = SIZE[1][1];
    public int cellsPerSec = SPEED_CELLS[1];
    public int tickRate = 32;
    public boolean subtick = true;
    public int subSteps = 4;
    public int maxPlayers = 8;
    public boolean walls = true;
    public boolean udp = true;
    public String serverName = "Game";

    public GameConfig copy() {
        GameConfig c = new GameConfig();
        c.cols = cols;
        c.rows = rows;
        c.cellsPerSec = cellsPerSec;
        c.tickRate = tickRate;
        c.subtick = subtick;
        c.subSteps = subSteps;
        c.maxPlayers = maxPlayers;
        c.walls = walls;
        c.udp = udp;
        c.serverName = serverName;
        return c;
    }

    public TickConfig tickConfig() {
        return subtick ? TickConfig.subtick(tickRate, subSteps) : TickConfig.fixed(tickRate);
    }

    public int unitsPerTick() {
        return Math.max(1, Math.round(cellsPerSec * SnakeState.UNITS / (float) tickRate));
    }

    public double cellsPerTick() {
        return cellsPerSec / (double) tickRate;
    }

    public int sizeIndex() {
        for (int i = 0; i < SIZE.length; i++) {
            if (SIZE[i][0] == cols && SIZE[i][1] == rows) return i;
        }
        return -1;
    }

    public int speedIndex() {
        for (int i = 0; i < SPEED_CELLS.length; i++) {
            if (SPEED_CELLS[i] == cellsPerSec) return i;
        }
        return -1;
    }

    public int tickIndex() {
        for (int i = 0; i < TICK_RATES.length; i++) {
            if (TICK_RATES[i] == tickRate) return i;
        }
        return -1;
    }

    public String sizeLabel() {
        int i = sizeIndex();
        return (i >= 0 ? SIZE_NAMES[i] : "Custom") + " " + cols + "×" + rows;
    }

    public String speedLabel() {
        int i = speedIndex();
        return (i >= 0 ? SPEED_NAMES[i] : "Custom") + " · " + cellsPerSec + " cells/s";
    }

    public String tickLabel() {
        return tickRate + " Hz" + (subtick ? " · subtick ×" + subSteps : "");
    }

    public void applySize(int i) {
        i = Math.floorMod(i, SIZE.length);
        cols = SIZE[i][0];
        rows = SIZE[i][1];
    }

    public void applySpeed(int i) {
        i = Math.floorMod(i, SPEED_CELLS.length);
        cellsPerSec = SPEED_CELLS[i];
    }

    public void applyTickRate(int i) {
        i = Math.floorMod(i, TICK_RATES.length);
        tickRate = TICK_RATES[i];
    }

    public Msg write(Msg m) {
        return m.put("cols", cols).put("rows", rows)
                .put("cps", cellsPerSec).put("rate", tickRate)
                .put("sub", subtick ? 1 : 0).put("subs", subSteps)
                .put("walls", walls ? 1 : 0).put("max", maxPlayers)
                .put("udp", udp ? 1 : 0);
    }

    public static GameConfig read(Msg m, GameConfig def) {
        GameConfig c = def == null ? new GameConfig() : def.copy();
        c.cols = clamp(m.num("cols", c.cols), 12, 80);
        c.rows = clamp(m.num("rows", c.rows), 10, 60);
        c.cellsPerSec = clamp(m.num("cps", c.cellsPerSec), 2, 16);
        c.tickRate = clamp(m.num("rate", c.tickRate), 8, TickConfig.MAX_RATE);
        c.subtick = m.num("sub", c.subtick ? 1 : 0) == 1;
        c.subSteps = clamp(m.num("subs", c.subSteps), 1, 16);
        if (m.has("walls")) c.walls = m.bool("walls");
        if (m.has("udp")) c.udp = m.num("udp", 1) == 1;
        c.maxPlayers = clamp(m.num("max", c.maxPlayers), 1, 8);
        return c;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String sanitizeName(String raw) {
        if (raw == null) return "Player";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 16; i++) {
            char ch = raw.charAt(i);
            if (ch < 32 || ch == 127) continue;
            sb.append(ch);
        }
        String s = sb.toString().trim().replaceAll("\\s{2,}", " ");
        return s.isEmpty() ? "Player" : s;
    }
}
