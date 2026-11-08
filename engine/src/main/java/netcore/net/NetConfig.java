package netcore.net;

public final class NetConfig {

    public enum Protocol { UDP, TCP }

    public static final NetConfig DEFAULT = udp(true);

    private final Protocol preferred;
    private final boolean allowTcp;
    private final int mtu;
    private final int connectTimeoutMs;
    private final int resendMs;
    private final int deadAfterMs;
    private final boolean compression;
    private final int maxPacketsPerSec;
    private final int maxBytesPerSec;
    private final boolean autoReconnect;
    private final int reconnectWindowMs;
    private final int silentLossMs;

    private NetConfig(Protocol preferred, boolean allowTcp, int mtu,
                      int connectTimeoutMs, int resendMs, int deadAfterMs,
                      boolean compression, int maxPacketsPerSec, int maxBytesPerSec,
                      boolean autoReconnect, int reconnectWindowMs, int silentLossMs) {
        this.preferred = preferred;
        this.allowTcp = allowTcp;
        this.mtu = Math.max(256, Math.min(65000, mtu));
        this.connectTimeoutMs = Math.max(200, connectTimeoutMs);
        this.resendMs = Math.max(20, resendMs);
        this.deadAfterMs = Math.max(1000, deadAfterMs);
        this.compression = compression;
        this.maxPacketsPerSec = Math.max(16, maxPacketsPerSec);
        this.maxBytesPerSec = Math.max(4096, maxBytesPerSec);
        this.autoReconnect = autoReconnect;
        this.reconnectWindowMs = Math.max(1000, reconnectWindowMs);
        this.silentLossMs = Math.max(300, silentLossMs);
    }

    public static NetConfig udp(boolean allowTcp) {
        return new NetConfig(Protocol.UDP, allowTcp, 1200, 900, 90, 8000,
                true, 400, 1024 * 1024, true, 30_000, 800);
    }

    public static NetConfig tcp() {
        return new NetConfig(Protocol.TCP, true, 1200, 4000, 90, 8000,
                true, 400, 1024 * 1024, true, 30_000, 800);
    }

    public NetConfig withMtu(int mtu) {
        return new NetConfig(preferred, allowTcp, mtu, connectTimeoutMs, resendMs, deadAfterMs,
                compression, maxPacketsPerSec, maxBytesPerSec, autoReconnect, reconnectWindowMs, silentLossMs);
    }

    public NetConfig withConnectTimeout(int ms) {
        return new NetConfig(preferred, allowTcp, mtu, ms, resendMs, deadAfterMs,
                compression, maxPacketsPerSec, maxBytesPerSec, autoReconnect, reconnectWindowMs, silentLossMs);
    }

    public NetConfig withCompression(boolean on) {
        return new NetConfig(preferred, allowTcp, mtu, connectTimeoutMs, resendMs, deadAfterMs,
                on, maxPacketsPerSec, maxBytesPerSec, autoReconnect, reconnectWindowMs, silentLossMs);
    }

    public NetConfig withRateLimit(int packetsPerSec, int bytesPerSec) {
        return new NetConfig(preferred, allowTcp, mtu, connectTimeoutMs, resendMs, deadAfterMs,
                compression, packetsPerSec, bytesPerSec, autoReconnect, reconnectWindowMs, silentLossMs);
    }

    public NetConfig withReconnect(boolean on, int windowMs) {
        return new NetConfig(preferred, allowTcp, mtu, connectTimeoutMs, resendMs, deadAfterMs,
                compression, maxPacketsPerSec, maxBytesPerSec, on, windowMs, silentLossMs);
    }

    public NetConfig withSilentLoss(int ms) {
        return new NetConfig(preferred, allowTcp, mtu, connectTimeoutMs, resendMs, deadAfterMs,
                compression, maxPacketsPerSec, maxBytesPerSec, autoReconnect, reconnectWindowMs, ms);
    }

    public NetConfig withDeadAfter(int ms) {
        return new NetConfig(preferred, allowTcp, mtu, connectTimeoutMs, resendMs, ms,
                compression, maxPacketsPerSec, maxBytesPerSec, autoReconnect, reconnectWindowMs, silentLossMs);
    }

    public Protocol preferred() {
        return preferred;
    }

    public boolean allowTcp() {
        return allowTcp;
    }

    public int mtu() {
        return mtu;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int resendMs() {
        return resendMs;
    }

    public int deadAfterMs() {
        return deadAfterMs;
    }

    public boolean compression() {
        return compression;
    }

    public int maxPacketsPerSec() {
        return maxPacketsPerSec;
    }

    public int maxBytesPerSec() {
        return maxBytesPerSec;
    }

    public boolean autoReconnect() {
        return autoReconnect;
    }

    public int reconnectWindowMs() {
        return reconnectWindowMs;
    }

    public int silentLossMs() {
        return silentLossMs;
    }

    public boolean listenUdp() {
        return preferred == Protocol.UDP;
    }

    public boolean listenTcp() {
        return preferred == Protocol.TCP || allowTcp;
    }

    @Override
    public String toString() {
        return preferred == Protocol.UDP
                ? "UDP" + (allowTcp ? " (+TCP fallback)" : "")
                : "TCP";
    }
}
