package netcore.net;


public final class Packet {

    public static final byte DATA = 0;

    public static final byte PING = 1;

    public static final byte PONG = 2;

    public static final byte VOICE = 3;

    public final byte type;
    public final int seq;
    public final byte[] payload;

    public final boolean reliable;

    public Packet(byte type, int seq, byte[] payload, boolean reliable) {
        this.type = type;
        this.seq = seq;
        this.payload = payload == null ? new byte[0] : payload;
        this.reliable = reliable;
    }

    public Packet(byte type, int seq, byte[] payload) {
        this(type, seq, payload, true);
    }

    public boolean isData() {
        return type == DATA;
    }

    public boolean isControl() {
        return type == PING || type == PONG;
    }

    public boolean isVoice() {
        return type == VOICE;
    }
}
