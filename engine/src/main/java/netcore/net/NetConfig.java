package netcore.net;

public final class NetConfig {

    public enum Protocol { UDP, TCP }

    public static final NetConfig DEFAULT = udp(true);

    public static final class Builder {
        private Protocol preferred = Protocol.UDP;
        private boolean allowTcp = true;
        private int mtu = 1200;
        private int connectTimeoutMs = 900;
        private int resendMs = 90;
        private int deadAfterMs = 8000;
        private boolean compression = true;
        private int maxPacketsPerSec = 400;
        private int maxBytesPerSec = 1024 * 1024;
        private int controlPacketsPerSec = 200;
        private int controlBytesPerSec = 256 * 1024;
        private int voicePacketsPerSec = 60;
        private int voiceBytesPerSec = 96 * 1024;
        private boolean autoReconnect = true;
        private int reconnectWindowMs = 30_000;
        private int silentLossMs = 800;
        private int heartbeatMs = 300;
        private int reconnectBackoffBaseMs = 100;
        private int reconnectBackoffMaxMs = 900;
        private int maxDatagramBytes = 65535;

        private Builder() {
        }

        private Builder(NetConfig c) {
            preferred = c.preferred;
            allowTcp = c.allowTcp;
            mtu = c.mtu;
            connectTimeoutMs = c.connectTimeoutMs;
            resendMs = c.resendMs;
            deadAfterMs = c.deadAfterMs;
            compression = c.compression;
            maxPacketsPerSec = c.maxPacketsPerSec;
            maxBytesPerSec = c.maxBytesPerSec;
            controlPacketsPerSec = c.controlPacketsPerSec;
            controlBytesPerSec = c.controlBytesPerSec;
            voicePacketsPerSec = c.voicePacketsPerSec;
            voiceBytesPerSec = c.voiceBytesPerSec;
            autoReconnect = c.autoReconnect;
            reconnectWindowMs = c.reconnectWindowMs;
            silentLossMs = c.silentLossMs;
            heartbeatMs = c.heartbeatMs;
            reconnectBackoffBaseMs = c.reconnectBackoffBaseMs;
            reconnectBackoffMaxMs = c.reconnectBackoffMaxMs;
            maxDatagramBytes = c.maxDatagramBytes;
        }

        public Builder protocol(Protocol protocol) {
            preferred = protocol == null ? Protocol.UDP : protocol;
            return this;
        }

        public Builder allowTcp(boolean on) {
            allowTcp = on;
            return this;
        }

        public Builder mtu(int value) {
            mtu = value;
            return this;
        }

        public Builder connectTimeoutMs(int value) {
            connectTimeoutMs = value;
            return this;
        }

        public Builder resendMs(int value) {
            resendMs = value;
            return this;
        }

        public Builder deadAfterMs(int value) {
            deadAfterMs = value;
            return this;
        }

        public Builder compression(boolean on) {
            compression = on;
            return this;
        }

        public Builder dataRate(int packetsPerSec, int bytesPerSec) {
            maxPacketsPerSec = packetsPerSec;
            maxBytesPerSec = bytesPerSec;
            return this;
        }

        public Builder controlRate(int packetsPerSec, int bytesPerSec) {
            controlPacketsPerSec = packetsPerSec;
            controlBytesPerSec = bytesPerSec;
            return this;
        }

        public Builder voiceRate(int packetsPerSec, int bytesPerSec) {
            voicePacketsPerSec = packetsPerSec;
            voiceBytesPerSec = bytesPerSec;
            return this;
        }

        public Builder reconnect(boolean on, int windowMs) {
            autoReconnect = on;
            reconnectWindowMs = windowMs;
            return this;
        }

        public Builder silentLossMs(int value) {
            silentLossMs = value;
            return this;
        }

        public Builder heartbeatMs(int value) {
            heartbeatMs = value;
            return this;
        }

        public Builder reconnectBackoff(int baseMs, int maxMs) {
            reconnectBackoffBaseMs = baseMs;
            reconnectBackoffMaxMs = maxMs;
            return this;
        }

        public Builder maxDatagramBytes(int value) {
            maxDatagramBytes = value;
            return this;
        }

        public NetConfig build() {
            return new NetConfig(this);
        }
    }

    private final Protocol preferred;
    private final boolean allowTcp;
    private final int mtu;
    private final int connectTimeoutMs;
    private final int resendMs;
    private final int deadAfterMs;
    private final boolean compression;
    private final int maxPacketsPerSec;
    private final int maxBytesPerSec;
    private final int controlPacketsPerSec;
    private final int controlBytesPerSec;
    private final int voicePacketsPerSec;
    private final int voiceBytesPerSec;
    private final boolean autoReconnect;
    private final int reconnectWindowMs;
    private final int silentLossMs;
    private final int heartbeatMs;
    private final int reconnectBackoffBaseMs;
    private final int reconnectBackoffMaxMs;
    private final int maxDatagramBytes;

    private NetConfig(Builder b) {
        this.preferred = b.preferred;
        this.allowTcp = b.allowTcp;
        this.mtu = clamp(b.mtu, 256, 65000);
        this.connectTimeoutMs = Math.max(200, b.connectTimeoutMs);
        this.resendMs = Math.max(20, b.resendMs);
        this.deadAfterMs = Math.max(1000, b.deadAfterMs);
        this.compression = b.compression;
        this.maxPacketsPerSec = Math.max(16, b.maxPacketsPerSec);
        this.maxBytesPerSec = Math.max(4096, b.maxBytesPerSec);
        this.controlPacketsPerSec = Math.max(16, b.controlPacketsPerSec);
        this.controlBytesPerSec = Math.max(4096, b.controlBytesPerSec);
        this.voicePacketsPerSec = Math.max(4, b.voicePacketsPerSec);
        this.voiceBytesPerSec = Math.max(1024, b.voiceBytesPerSec);
        this.autoReconnect = b.autoReconnect;
        this.reconnectWindowMs = Math.max(1000, b.reconnectWindowMs);
        this.silentLossMs = Math.max(300, b.silentLossMs);
        this.heartbeatMs = clamp(b.heartbeatMs, 50, 5000);
        this.reconnectBackoffBaseMs = Math.max(20, b.reconnectBackoffBaseMs);
        this.reconnectBackoffMaxMs = Math.max(this.reconnectBackoffBaseMs, b.reconnectBackoffMaxMs);
        this.maxDatagramBytes = clamp(b.maxDatagramBytes, 256, 65535);
    }

    public static NetConfig udp(boolean allowTcp) {
        return builder().protocol(Protocol.UDP).allowTcp(allowTcp).build();
    }

    public static NetConfig tcp() {
        return builder().protocol(Protocol.TCP).allowTcp(true).connectTimeoutMs(4000).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private NetConfig with(java.util.function.Consumer<Builder> change) {
        Builder b = toBuilder();
        change.accept(b);
        return b.build();
    }

    public NetConfig withMtu(int value) {
        return with(b -> b.mtu(value));
    }

    public NetConfig withConnectTimeout(int ms) {
        return with(b -> b.connectTimeoutMs(ms));
    }

    public NetConfig withCompression(boolean on) {
        return with(b -> b.compression(on));
    }

    public NetConfig withRateLimit(int packetsPerSec, int bytesPerSec) {
        return with(b -> b.dataRate(packetsPerSec, bytesPerSec));
    }

    public NetConfig withControlRate(int packetsPerSec, int bytesPerSec) {
        return with(b -> b.controlRate(packetsPerSec, bytesPerSec));
    }

    public NetConfig withVoiceRate(int packetsPerSec, int bytesPerSec) {
        return with(b -> b.voiceRate(packetsPerSec, bytesPerSec));
    }

    public NetConfig withReconnect(boolean on, int windowMs) {
        return with(b -> b.reconnect(on, windowMs));
    }

    public NetConfig withSilentLoss(int ms) {
        return with(b -> b.silentLossMs(ms));
    }

    public NetConfig withDeadAfter(int ms) {
        return with(b -> b.deadAfterMs(ms));
    }

    public NetConfig withHeartbeat(int ms) {
        return with(b -> b.heartbeatMs(ms));
    }

    public NetConfig withReconnectBackoff(int baseMs, int maxMs) {
        return with(b -> b.reconnectBackoff(baseMs, maxMs));
    }

    public NetConfig withMaxDatagram(int bytes) {
        return with(b -> b.maxDatagramBytes(bytes));
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

    public int controlPacketsPerSec() {
        return controlPacketsPerSec;
    }

    public int controlBytesPerSec() {
        return controlBytesPerSec;
    }

    public int voicePacketsPerSec() {
        return voicePacketsPerSec;
    }

    public int voiceBytesPerSec() {
        return voiceBytesPerSec;
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

    public int heartbeatMs() {
        return heartbeatMs;
    }

    public int reconnectBackoffBaseMs() {
        return reconnectBackoffBaseMs;
    }

    public int reconnectBackoffMaxMs() {
        return reconnectBackoffMaxMs;
    }

    public int maxDatagramBytes() {
        return maxDatagramBytes;
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

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
