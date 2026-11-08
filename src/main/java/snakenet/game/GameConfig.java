package snakenet.game;

import snakenet.net.Msg;

/** Настройки партии: размер поля, скорость, стены. */
public final class GameConfig {

    public static final String[] SIZE_NAMES = {"Мини", "Классика", "Большое", "Арена"};
    public static final int[][] SIZE = {{24, 16}, {32, 22}, {40, 26}, {44, 18}};

    public static final String[] SPEED_NAMES = {"Спокойно", "Норма", "Быстро", "Хардкор"};
    public static final int[] SPEED_TICK = {130, 100, 78, 62};

    public int cols = SIZE[1][0];
    public int rows = SIZE[1][1];
    public int tickMs = SPEED_TICK[1];
    public boolean walls = true;
    public int maxPlayers = 8;
    public String serverName = "Игра";

    public GameConfig copy() {
        GameConfig c = new GameConfig();
        c.cols = cols;
        c.rows = rows;
        c.tickMs = tickMs;
        c.walls = walls;
        c.maxPlayers = maxPlayers;
        c.serverName = serverName;
        return c;
    }

    public int sizeIndex() {
        for (int i = 0; i < SIZE.length; i++) {
            if (SIZE[i][0] == cols && SIZE[i][1] == rows) return i;
        }
        return -1;
    }

    public int speedIndex() {
        for (int i = 0; i < SPEED_TICK.length; i++) {
            if (SPEED_TICK[i] == tickMs) return i;
        }
        return -1;
    }

    public String sizeLabel() {
        int i = sizeIndex();
        return (i >= 0 ? SIZE_NAMES[i] : "Своё") + " " + cols + "×" + rows;
    }

    public String speedLabel() {
        int i = speedIndex();
        return (i >= 0 ? SPEED_NAMES[i] : "Своя") + " · " + (int) Math.round(1000.0 / tickMs) + "/сек";
    }

    public void applySize(int i) {
        i = Math.floorMod(i, SIZE.length);
        cols = SIZE[i][0];
        rows = SIZE[i][1];
    }

    public void applySpeed(int i) {
        i = Math.floorMod(i, SPEED_TICK.length);
        tickMs = SPEED_TICK[i];
    }

    public Msg write(Msg m) {
        return m.put("cols", cols).put("rows", rows).put("ms", tickMs)
                .put("walls", walls ? 1 : 0).put("max", maxPlayers);
    }

    public static GameConfig read(Msg m, GameConfig def) {
        GameConfig c = def == null ? new GameConfig() : def.copy();
        c.cols = clamp(m.num("cols", c.cols), 12, 80);
        c.rows = clamp(m.num("rows", c.rows), 10, 60);
        c.tickMs = clamp(m.num("ms", c.tickMs), 40, 400);
        if (m.has("walls")) c.walls = m.bool("walls");
        c.maxPlayers = clamp(m.num("max", c.maxPlayers), 1, 8);
        return c;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Чистим ник: без управляющих символов, не длиннее 16 знаков. */
    public static String sanitizeName(String raw) {
        if (raw == null) return "Игрок";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 16; i++) {
            char ch = raw.charAt(i);
            if (ch < 32 || ch == 127) continue;
            sb.append(ch);
        }
        String s = sb.toString().trim().replaceAll("\\s{2,}", " ");
        return s.isEmpty() ? "Игрок" : s;
    }
}
