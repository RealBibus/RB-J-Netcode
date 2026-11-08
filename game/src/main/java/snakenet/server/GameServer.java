package snakenet.server;

import netcore.net.Connection;
import netcore.net.LanDiscovery;
import netcore.net.NetServer;
import snakenet.game.GameConfig;
import snakenet.net.Wire;

import java.io.IOException;

/**
 * Игровой сервер змейки: тонкая обёртка над {@link NetServer} движка.
 * Вся игровая логика — в {@link Room}.
 */
public final class GameServer {

    private final String name;
    private final Room room;
    private final NetServer net;
    private LanDiscovery.Beacon beacon;
    private volatile boolean running;
    private volatile String lastError;

    public GameServer(int port, String name, GameConfig cfg) {
        this.name = name == null || name.isBlank() ? "Игра" : name;
        this.room = new Room(this, cfg);
        this.net = new NetServer(port, netcore.net.NetConfig.udp(cfg.tcpFallback), new NetServer.Listener() {
            @Override
            public void onConnect(Connection connection) {
                room.onConnect(connection);
            }

            @Override
            public void onMessage(Connection connection, byte[] payload) {
                room.onMessage(connection, Wire.decode(payload));
            }

            @Override
            public void onDisconnect(Connection connection, String reason) {
                room.onDisconnect(connection);
            }
        });
    }

    public void start() throws IOException {
        net.start();
        room.start();
        beacon = new LanDiscovery.Beacon(name, net.port(), room::playerCount, () -> room.config().maxPlayers);
        beacon.start();
        running = true;
    }

    public int port() {
        return net.port();
    }

    public String name() {
        return name;
    }

    public Room room() {
        return room;
    }

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return lastError;
    }

    void broadcast(byte[] payload) {
        net.broadcast(payload, true);
    }

    /** Снимки состояния: в UDP не досылаем, потеря не критична. */
    void broadcastUnreliable(byte[] payload) {
        net.broadcast(payload, false);
    }

    public String protocols() {
        return net.protocols();
    }

    void send(Connection connection, byte[] payload) {
        net.send(connection, payload);
    }

    public void stop() {
        if (!running) return;
        running = false;
        room.stop();
        net.stop();
        if (beacon != null) beacon.close();
    }
}
