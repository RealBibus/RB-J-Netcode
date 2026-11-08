package netcore.net;


public final class NetStats {

    private volatile long packetsIn;
    private volatile long packetsOut;
    private volatile long bytesIn;
    private volatile long bytesOut;
    private volatile long lastPacketNanos;

    void onIn(int bytes) {
        packetsIn++;
        bytesIn += bytes;
        lastPacketNanos = System.nanoTime();
    }

    void onOut(int bytes) {
        packetsOut++;
        bytesOut += bytes;
    }

    public long packetsIn() {
        return packetsIn;
    }

    public long packetsOut() {
        return packetsOut;
    }

    public long bytesIn() {
        return bytesIn;
    }

    public long bytesOut() {
        return bytesOut;
    }

    public double kbpsIn() {
        return bytesIn * 8.0 / 1024.0;
    }
}
