package snakenet.chess.core;

import netcore.net.NetConfig;
import netcore.predict.PredictionConfig;
import netcore.time.TickConfig;
import netcore.voice.VoiceConfig;
import netcore.voice.VoiceMode;
import netcore.netcode.Msg;

/**
 * Settings shared by the chess client and server. This game has no snake options:
 * just the match rules (side and bot strength) and the network/voice transport.
 */
public final class GameConfig {

    public int tickRate = 32;
    public int maxPlayers = 2;
    public boolean udp = true;
    public String serverName = "Chess";

    public boolean preciseSpin = true;
    public int maxCatchUp = 4;
    public int spinWindowMicros = 900;
    public int tickPriority = Thread.NORM_PRIORITY + 1;

    /** Chess colour choice: 0 = white, 1 = black, 2 = random. */
    public int chessSide = 0;
    /** Chess bot search depth (1..4). */
    public int chessBotDepth = 2;

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
        c.tickRate = tickRate;
        c.maxPlayers = maxPlayers;
        c.udp = udp;
        c.serverName = serverName;
        c.preciseSpin = preciseSpin;
        c.maxCatchUp = maxCatchUp;
        c.spinWindowMicros = spinWindowMicros;
        c.tickPriority = tickPriority;
        c.chessSide = chessSide;
        c.chessBotDepth = chessBotDepth;
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
        return TickConfig.fixed(tickRate)
                .withPrecise(preciseSpin)
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

    public Msg write(Msg m) {
        return m.put("rate", tickRate).put("max", maxPlayers)
                .put("udp", udp ? 1 : 0)
                .put("vo", voice ? 1 : 0).put("vm", voiceMode)
                .put("vj", voiceJitterTarget).put("vx", voiceJitterMax)
                .put("cs", chessSide).put("cd", chessBotDepth);
    }

    public static GameConfig read(Msg m, GameConfig def) {
        GameConfig c = def == null ? new GameConfig() : def.copy();
        c.tickRate = clamp(m.num("rate", c.tickRate), 8, TickConfig.MAX_RATE);
        c.maxPlayers = clamp(m.num("max", c.maxPlayers), 1, 8);
        if (m.has("udp")) c.udp = m.num("udp", 1) == 1;
        if (m.has("vo")) c.voice = m.num("vo", c.voice ? 1 : 0) == 1;
        if (m.has("vm")) c.voiceMode = clamp(m.num("vm", c.voiceMode), 0, 1);
        if (m.has("vj")) c.voiceJitterTarget = clamp(m.num("vj", c.voiceJitterTarget), 1, 16);
        if (m.has("vx")) c.voiceJitterMax = clamp(m.num("vx", c.voiceJitterMax), c.voiceJitterTarget, 64);
        if (m.has("cs")) c.chessSide = clamp(m.num("cs", c.chessSide), 0, 2);
        if (m.has("cd")) c.chessBotDepth = clamp(m.num("cd", c.chessBotDepth), 1, 4);
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
