package netcore.net;

/**
 * Настройки транспорта.
 *
 * <p>По умолчанию — UDP: надёжные сообщения (ввод, чат, лобби) движок
 * досылает с подтверждениями, снимки состояния летят как есть, без ожидания
 * потерянных пакетов. TCP можно оставить как запасной канал — клиент сам
 * откатится на него, если UDP не отвечает.</p>
 */
public final class NetConfig {

    public enum Protocol { UDP, TCP }

    public static final NetConfig DEFAULT = udp(true);

    private final Protocol preferred;
    private final boolean allowTcp;
    private final int mtu;
    private final int connectTimeoutMs;
    private final int resendMs;
    private final int deadAfterMs;

    private NetConfig(Protocol preferred, boolean allowTcp, int mtu,
                      int connectTimeoutMs, int resendMs, int deadAfterMs) {
        this.preferred = preferred;
        this.allowTcp = allowTcp;
        this.mtu = Math.max(256, Math.min(65000, mtu));
        this.connectTimeoutMs = Math.max(200, connectTimeoutMs);
        this.resendMs = Math.max(20, resendMs);
        this.deadAfterMs = Math.max(1000, deadAfterMs);
    }

    /** UDP основной, TCP — запасной (если разрешён). */
    public static NetConfig udp(boolean allowTcp) {
        return new NetConfig(Protocol.UDP, allowTcp, 1200, 900, 90, 8000);
    }

    public static NetConfig tcp() {
        return new NetConfig(Protocol.TCP, true, 1200, 4000, 90, 8000);
    }

    public NetConfig withMtu(int mtu) {
        return new NetConfig(preferred, allowTcp, mtu, connectTimeoutMs, resendMs, deadAfterMs);
    }

    public NetConfig withConnectTimeout(int ms) {
        return new NetConfig(preferred, allowTcp, mtu, ms, resendMs, deadAfterMs);
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
