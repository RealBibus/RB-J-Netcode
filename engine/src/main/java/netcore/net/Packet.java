package netcore.net;

/** Кадр движка: тип, порядковый номер, байты приложения и режим доставки. */
public final class Packet {

    /** Байты игры. */
    public static final byte DATA = 0;
    /** Служебный пинг (отвечает движок, до игры не доходит). */
    public static final byte PING = 1;
    /** Ответ на пинг: время отправителя и время сервера. */
    public static final byte PONG = 2;

    public final byte type;
    public final int seq;
    public final byte[] payload;
    /** Для UDP: досылать с подтверждением (true) или отправить один раз (false). */
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
}
