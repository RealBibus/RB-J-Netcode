package snakenet.snake.core;

import netcore.net.NetConfig;
import netcore.predict.PredictionConfig;
import netcore.time.TickConfig;
import netcore.voice.VoiceConfig;
import netcore.voice.VoiceMode;
import netcore.netcode.Msg;

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
    public int subSteps = 8;
    public int maxPlayers = 8;
    public boolean walls = true;
    /** When true, a snake may pass through its own body without dying. */
    public boolean safeSelf = true;
    public boolean udp = true;
    public String serverName = "Game";

    public boolean preciseSpin = true;
    public int maxCatchUp = 4;
    public int spinWindowMicros = 900;
    public int tickPriority = Thread.NORM_PRIORITY + 1;

    public double predictionSnap = 2.2;
    public int predictionHistory = 48;
    public int maxAdvanceTicks = 64;
    public int interpolationDelayMs = 66;
    public boolean nonlinearInterp = true;
    public int predictionSmoothMs = 70;

    public int inputMaxPerSec = 60;
    public int inputMaxAheadTicks = 8;
    public int inputMaxBehindTicks = 2;
    public int inputMaxQueue = 48;

    public int mtu = 1200;
    public int heartbeatMs = 300;
    public int serverDeadAfterMs = 4000;
    public int clientDeadAfterMs = 8000;
    public int reconnectWindowMs = 30_000;
    public int silentLossMs = 800;
    public int reconnectBackoffBaseMs = 100;
    public int reconnectBackoffMaxMs = 900;
    public int dataPacketsPerSec = 400;
    public int dataBytesPerSec = 1024 * 1024;
    public int controlPacketsPerSec = 200;
    public int controlBytesPerSec = 256 * 1024;

    public boolean voice = true;
    public int voiceMode = 0;
    public int voiceJitterTarget = 3;
    public int voiceJitterMax = 8;
    public boolean voiceAdaptive = true;
    public boolean voiceActivity = false;
    public float voiceVad = 0.02f;
    public int voiceMaxPacketsPerSec = 60;
    public int voiceMaxBytesPerSec = 96 * 1024;
    public int voiceMaxPacketBytes = 640;

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
        c.safeSelf = safeSelf;
        c.udp = udp;
        c.serverName = serverName;
        c.preciseSpin = preciseSpin;
        c.maxCatchUp = maxCatchUp;
        c.spinWindowMicros = spinWindowMicros;
        c.tickPriority = tickPriority;
        c.predictionSnap = predictionSnap;
        c.predictionHistory = predictionHistory;
        c.maxAdvanceTicks = maxAdvanceTicks;
        c.interpolationDelayMs = interpolationDelayMs;
        c.nonlinearInterp = nonlinearInterp;
        c.predictionSmoothMs = predictionSmoothMs;
        c.inputMaxPerSec = inputMaxPerSec;
        c.inputMaxAheadTicks = inputMaxAheadTicks;
        c.inputMaxBehindTicks = inputMaxBehindTicks;
        c.inputMaxQueue = inputMaxQueue;
        c.mtu = mtu;
        c.heartbeatMs = heartbeatMs;
        c.serverDeadAfterMs = serverDeadAfterMs;
        c.clientDeadAfterMs = clientDeadAfterMs;
        c.reconnectWindowMs = reconnectWindowMs;
        c.silentLossMs = silentLossMs;
        c.reconnectBackoffBaseMs = reconnectBackoffBaseMs;
        c.reconnectBackoffMaxMs = reconnectBackoffMaxMs;
        c.dataPacketsPerSec = dataPacketsPerSec;
        c.dataBytesPerSec = dataBytesPerSec;
        c.controlPacketsPerSec = controlPacketsPerSec;
        c.controlBytesPerSec = controlBytesPerSec;
        c.voice = voice;
        c.voiceMode = voiceMode;
        c.voiceJitterTarget = voiceJitterTarget;
        c.voiceJitterMax = voiceJitterMax;
        c.voiceAdaptive = voiceAdaptive;
        c.voiceActivity = voiceActivity;
        c.voiceVad = voiceVad;
        c.voiceMaxPacketsPerSec = voiceMaxPacketsPerSec;
        c.voiceMaxBytesPerSec = voiceMaxBytesPerSec;
        c.voiceMaxPacketBytes = voiceMaxPacketBytes;
        return c;
    }

    public TickConfig tickConfig() {
        TickConfig base = subtick ? TickConfig.subtick(tickRate, subSteps) : TickConfig.fixed(tickRate);
        return base.withPrecise(preciseSpin)
                .withCatchUp(maxCatchUp)
                .withSpinWindow(Math.max(0, spinWindowMicros) * 1000L)
                .withPriority(tickPriority);
    }

    public PredictionConfig predictionConfig() {
        return new PredictionConfig(predictionSnap, predictionHistory, maxAdvanceTicks, interpolationDelayMs);
    }

    public VoiceMode voiceModeValue() {
        return voiceMode == 1 ? VoiceMode.WEBRTC : VoiceMode.UDP;
    }

    public VoiceConfig voiceConfig() {
        return new VoiceConfig(16000f, 320, 20, voiceJitterTarget, voiceJitterMax, voiceAdaptive,
                voiceActivity, voiceVad, voiceMaxPacketBytes, voiceMaxPacketsPerSec,
                voiceMaxBytesPerSec, voiceModeValue());
    }

    public NetConfig netConfig(boolean server) {
        NetConfig.Builder b = udp
                ? NetConfig.builder().protocol(NetConfig.Protocol.UDP).allowTcp(!server)
                : NetConfig.builder().protocol(NetConfig.Protocol.TCP).allowTcp(true);
        return b.mtu(mtu)
                .heartbeatMs(heartbeatMs)
                .deadAfterMs(server ? serverDeadAfterMs : clientDeadAfterMs)
                .reconnect(true, reconnectWindowMs)
                .silentLossMs(silentLossMs)
                .reconnectBackoff(reconnectBackoffBaseMs, reconnectBackoffMaxMs)
                .dataRate(dataPacketsPerSec, dataBytesPerSec)
                .controlRate(controlPacketsPerSec, controlBytesPerSec)
                .voiceRate(voiceMaxPacketsPerSec, voiceMaxBytesPerSec)
                .compression(true)
                .build();
    }

    public int unitsPerTick() {
        return Math.max(1, Math.round(cellsPerSec * 1024 / (float) tickRate));
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
        return tickRate + " Hz" + (subtick ? " · subtick" : "");
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
                .put("self", safeSelf ? 1 : 0)
                .put("udp", udp ? 1 : 0)
                .put("vo", voice ? 1 : 0).put("vm", voiceMode)
                .put("vj", voiceJitterTarget).put("vx", voiceJitterMax);
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
        if (m.has("self")) c.safeSelf = m.num("self", c.safeSelf ? 1 : 0) == 1;
        if (m.has("udp")) c.udp = m.num("udp", 1) == 1;
        c.maxPlayers = clamp(m.num("max", c.maxPlayers), 1, 8);
        if (m.has("vo")) c.voice = m.num("vo", c.voice ? 1 : 0) == 1;
        if (m.has("vm")) c.voiceMode = clamp(m.num("vm", c.voiceMode), 0, 1);
        if (m.has("vj")) c.voiceJitterTarget = clamp(m.num("vj", c.voiceJitterTarget), 1, 16);
        if (m.has("vx")) c.voiceJitterMax = clamp(m.num("vx", c.voiceJitterMax), c.voiceJitterTarget, 64);
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

