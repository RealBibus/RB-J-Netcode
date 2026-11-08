package netcore.net;

public final class Connection {

    private static final int PING_WINDOW = 48;
    private static final long LOSS_AGE_NANOS = 1_500_000_000L;

    private final int id;
    private final NetConfig config;
    private volatile Transport transport;

    private volatile int rttMs = -1;
    private volatile int jitterMs = -1;
    private volatile int lossPercent;
    private volatile int quality = 100;
    private double lastRttNanos = -1;
    private double jitterNanos = -1;

    private final long[] pingAt = new long[PING_WINDOW];
    private final boolean[] pingOk = new boolean[PING_WINDOW];
    private int pingSlot;

    private long rateWindowNanos;
    private int ratePackets;
    private long rateBytes;
    private long dropped;

    public volatile Object attachment;

    Connection(int id, Transport transport) {
        this(id, transport, NetConfig.DEFAULT);
    }

    Connection(int id, Transport transport, NetConfig config) {
        this.id = id;
        this.transport = transport;
        this.config = config;
    }

    Transport transport() {
        return transport;
    }

    void replaceTransport(Transport next) {
        this.transport = next;
    }

    public int id() {
        return id;
    }

    public int rttMs() {
        return rttMs;
    }

    public int jitterMs() {
        return jitterMs;
    }

    public int lossPercent() {
        return lossPercent;
    }

    public int quality() {
        return quality;
    }

    public long droppedPackets() {
        return dropped;
    }

    void onPingSent(long sentNanos) {
        synchronized (this) {
            pingAt[pingSlot] = sentNanos;
            pingOk[pingSlot] = false;
            pingSlot = (pingSlot + 1) % PING_WINDOW;
        }
    }

    void onPong(long sentNanos) {
        int ms;
        synchronized (this) {
            long rtt = Math.max(0, System.nanoTime() - sentNanos);
            ms = (int) Math.min(9_999L, Math.round(rtt / 1_000_000.0));
            int prev = rttMs;
            rttMs = prev < 0 ? ms : (prev * 3 + ms) / 4;

            if (lastRttNanos >= 0) {
                double deviation = Math.abs(rtt - lastRttNanos);
                jitterNanos = jitterNanos < 0 ? deviation : jitterNanos * 0.85 + deviation * 0.15;
                jitterMs = (int) Math.min(9_999L, Math.round(jitterNanos / 1_000_000.0));
            }
            lastRttNanos = rtt;

            for (int i = 0; i < PING_WINDOW; i++) {
                if (pingAt[i] == sentNanos) {
                    pingOk[i] = true;
                    break;
                }
            }
            refreshQuality();
        }
    }

    private void refreshQuality() {
        long now = System.nanoTime();
        int total = 0;
        int lost = 0;
        for (int i = 0; i < PING_WINDOW; i++) {
            long at = pingAt[i];
            if (at == 0) continue;
            if (pingOk[i]) {
                total++;
            } else if (now - at > LOSS_AGE_NANOS) {
                total++;
                lost++;
            }
        }
        lossPercent = total == 0 ? 0 : (int) Math.round(lost * 100.0 / total);
        quality = score(rttMs, jitterMs, lossPercent);
    }

    public static int score(int rttMs, int jitterMs, int lossPercent) {
        int q = 100;
        if (rttMs >= 0) q -= Math.min(45, Math.max(0, rttMs - 40) / 4);
        if (jitterMs > 0) q -= Math.min(25, jitterMs / 2);
        q -= Math.min(30, lossPercent * 3);
        return Math.max(0, Math.min(100, q));
    }

    boolean allowIncoming(int bytes) {
        long now = System.nanoTime();
        synchronized (this) {
            if (now - rateWindowNanos > 1_000_000_000L) {
                rateWindowNanos = now;
                ratePackets = 0;
                rateBytes = 0;
            }
            ratePackets++;
            rateBytes += Math.max(0, bytes);
            if (ratePackets > config.maxPacketsPerSec() || rateBytes > config.maxBytesPerSec()) {
                dropped++;
                return false;
            }
            return true;
        }
    }

    public void send(byte[] payload) {
        send(payload, true);
    }

    public void send(byte[] payload, boolean reliable) {
        Transport t = transport;
        if (t != null) t.send(new Packet(Packet.DATA, 0, payload, reliable));
    }

    public void sendPacket(byte type, byte[] payload) {
        Transport t = transport;
        if (t != null) t.send(new Packet(type, 0, payload, false));
    }

    public boolean isOpen() {
        Transport t = transport;
        return t != null && t.isOpen();
    }

    public String remote() {
        Transport t = transport;
        return t == null ? "?" : t.remote();
    }

    public String protocol() {
        Transport t = transport;
        return t == null ? "?" : t.protocol();
    }

    public NetStats stats() {
        Transport t = transport;
        return t == null ? new NetStats() : t.stats();
    }

    public void close() {
        Transport t = transport;
        if (t != null) t.close();
    }
}
