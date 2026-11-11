package snakenet.snake.client;

import netcore.net.NetClient;
import netcore.sync.ClockSync;
import netcore.voice.VoicePacket;
import netcore.netcode.Msg;
import netcore.netcode.NetworkClient;
import snakenet.snake.net.Net;
import netcore.netcode.Wire;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;

public final class GameClient implements NetClient.Listener {

    public interface Listener {
        void onFrame(List<Msg> frame);

        void onVoice(int playerId, int sequence, short[] pcm);

        void onClosed(String reason);

        default void onLinkLost(String reason, long retryForMs) {
        }

        default void onLinkRestored() {
        }
    }

    private final Listener listener;
    private final NetworkClient netcode = new NetworkClient();
    private NetClient net;

    private GameClient(Listener listener) {
        this.listener = listener;
    }

    public static GameClient connect(String host, int port, Listener listener) throws IOException {
        return connect(host, port, listener, netcore.net.NetConfig.udp(true).withConnectTimeout(450));
    }

    public static GameClient connect(String host, int port, Listener listener,
                                     netcore.net.NetConfig config) throws IOException {
        GameClient client = new GameClient(listener);
        client.net = NetClient.connect(host, port, config, client);
        return client;
    }

    public String protocol() {
        return net == null ? "?" : net.protocol();
    }

    @Override
    public void onMessage(byte[] payload) {
        if (Wire.isVoice(payload)) {
            if (payload.length < 7) return;
            int playerId = ((payload[1] & 0xff) << 8) | (payload[2] & 0xff);
            int sequence = ((payload[3] & 0xff) << 8) | (payload[4] & 0xff);
            int payloadBytes = payload.length - 5;
            if (payloadBytes < 2 || (payloadBytes & 1) != 0) return;
            short[] pcm = VoicePacket.readPcm(payload, 5, payloadBytes);
            SwingUtilities.invokeLater(() -> listener.onVoice(playerId, sequence, pcm));
            return;
        }
        List<Msg> frame = Wire.decode(payload);
        for (Msg m : frame) netcode.apply(m);
        SwingUtilities.invokeLater(() -> listener.onFrame(frame));
        for (Msg m : frame) {
            if (m.is(Net.KICK)) {
                close();
                break;
            }
        }
    }

    public NetworkClient netcode() {
        return netcode;
    }

    public void sendVoice(byte[] frame) {
        if (net != null && net.isOpen()) net.sendVoice(frame);
    }

    @Override
    public void onClosed(String reason) {
        SwingUtilities.invokeLater(() -> listener.onClosed(reason));
    }

    @Override
    public void onLinkLost(String reason, long retryForMs) {
        SwingUtilities.invokeLater(() -> listener.onLinkLost(reason, retryForMs));
    }

    @Override
    public void onLinkRestored() {
        SwingUtilities.invokeLater(listener::onLinkRestored);
    }

    public void send(Msg message) {
        if (net != null && net.isOpen()) net.send(Wire.encode(message));
    }

    public ClockSync clock() {
        return net == null ? null : net.clock();
    }

    public netcore.net.NetStats stats() {
        return net == null ? null : net.stats();
    }

    public int rttMs() {
        ClockSync c = clock();
        return c == null ? -1 : c.rttMs();
    }

    public int jitterMs() {
        ClockSync c = clock();
        return c == null ? -1 : c.jitterMs();
    }

    public int lossPercent() {
        ClockSync c = clock();
        return c == null ? 0 : c.lossPercent();
    }

    public int quality() {
        ClockSync c = clock();
        return c == null ? 0 : c.quality();
    }

    public boolean isOpen() {
        return net != null && net.isOpen();
    }

    public boolean isReconnecting() {
        return net != null && net.isReconnecting();
    }

    public void close() {
        if (net != null) net.close();
    }
}

