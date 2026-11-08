package netcore.net;

/** Транспорт: TCP или UDP с надёжностью. */
public interface Transport extends AutoCloseable {

    interface Listener {
        void onPacket(Packet packet);

        void onClosed(String reason);
    }

    void send(Packet packet);

    boolean isOpen();

    String remote();

    String protocol();

    NetStats stats();

    @Override
    void close();
}
