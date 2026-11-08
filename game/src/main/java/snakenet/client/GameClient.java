package snakenet.client;

import netcore.net.NetClient;
import netcore.sync.ClockSync;
import snakenet.net.Msg;
import snakenet.net.Wire;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;

/** Клиент змейки: обёртка над {@link NetClient} движка. */
public final class GameClient implements NetClient.Listener {

    public interface Listener {
        void onFrame(List<Msg> frame);

        void onClosed(String reason);
    }

    private final Listener listener;
    private NetClient net;

    private GameClient(Listener listener) {
        this.listener = listener;
    }

    public static GameClient connect(String host, int port, Listener listener) throws IOException {
        return connect(host, port, listener, netcore.net.NetConfig.udp(true));
    }

    public static GameClient connect(String host, int port, Listener listener,
                                     netcore.net.NetConfig config) throws IOException {
        GameClient client = new GameClient(listener);
        client.net = NetClient.connect(host, port, config, client);
        return client;
    }

    /** "UDP" или "TCP" — чем реально подключились. */
    public String protocol() {
        return net == null ? "?" : net.protocol();
    }

    @Override
    public void onMessage(byte[] payload) {
        List<Msg> frame = Wire.decode(payload);
        SwingUtilities.invokeLater(() -> listener.onFrame(frame));
    }

    @Override
    public void onClosed(String reason) {
        SwingUtilities.invokeLater(() -> listener.onClosed(reason));
    }

    public void send(Msg message) {
        if (net != null && net.isOpen()) net.send(Wire.encode(message));
    }

    public ClockSync clock() {
        return net == null ? null : net.clock();
    }

    public int rttMs() {
        ClockSync c = clock();
        return c == null ? -1 : c.rttMs();
    }

    public boolean isOpen() {
        return net != null && net.isOpen();
    }

    public void close() {
        if (net != null) net.close();
    }
}
