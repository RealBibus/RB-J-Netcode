package netcore.net;


public interface Transport extends AutoCloseable {

    interface Listener {
        void onPacket(Packet packet);

        void onClosed(String reason);
    }

    void send(Packet packet);

    default void start() {
    }

    boolean isOpen();

    String remote();

    String protocol();

    NetStats stats();

    @Override
    void close();
}
