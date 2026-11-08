package netcore.net;

/** Подключение на стороне сервера. */
public final class Connection {

    private final int id;
    private final Transport transport;

    /** Сюда игра кладёт свои данные (сессию, игрока). */
    public volatile Object attachment;

    Connection(int id, Transport transport) {
        this.id = id;
        this.transport = transport;
    }

    public int id() {
        return id;
    }

    /** Надёжное сообщение (UDP — с подтверждением, TCP — всегда надёжно). */
    public void send(byte[] payload) {
        send(payload, true);
    }

    public void send(byte[] payload, boolean reliable) {
        transport.send(new Packet(Packet.DATA, 0, payload, reliable));
    }

    /** Служебный пакет движка (PING/PONG), игре он не виден. */
    public void sendPacket(byte type, byte[] payload) {
        transport.send(new Packet(type, 0, payload, false));
    }

    public boolean isOpen() {
        return transport.isOpen();
    }

    public String remote() {
        return transport.remote();
    }

    public String protocol() {
        return transport.protocol();
    }

    public NetStats stats() {
        return transport.stats();
    }

    public void close() {
        transport.close();
    }
}
