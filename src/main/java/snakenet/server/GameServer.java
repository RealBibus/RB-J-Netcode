package snakenet.server;

import snakenet.game.GameConfig;
import snakenet.net.LanDiscovery;
import snakenet.net.Msg;
import snakenet.net.Protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Встроенный сервер: слушает TCP-порт, принимает клиентов и держит одну игровую
 * комнату. Живёт внутри процесса хоста, поэтому "создать сервер" = запустить его
 * и сразу подключиться самому на 127.0.0.1.
 */
public final class GameServer {

    private final String name;
    private final Room room;
    private final int requestedPort;
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final AtomicInteger sessionIds = new AtomicInteger();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private LanDiscovery.Beacon beacon;
    private volatile boolean running;
    private volatile String lastError;

    public GameServer(int port, String name, GameConfig cfg) {
        this.requestedPort = port;
        this.name = name == null || name.isBlank() ? "Игра" : name;
        this.room = new Room(this, cfg);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(requestedPort));
        serverSocket = ss;
        running = true;

        acceptThread = new Thread(this::acceptLoop, "snake-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        room.start();

        beacon = new LanDiscovery.Beacon(name, ss.getLocalPort(), room::playerCount, () -> room.config().maxPlayers);
        beacon.start();
    }

    /** Реальный порт (может отличаться от запрошенного, если 0 — «любой свободный»). */
    public int port() {
        ServerSocket ss = serverSocket;
        return ss == null ? requestedPort : ss.getLocalPort();
    }

    public String name() {
        return name;
    }

    public Room room() {
        return room;
    }

    public String lastError() {
        return lastError;
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                Session session = new Session(sessionIds.incrementAndGet(), s, this);
                sessions.add(session);
                session.start();
            } catch (IOException e) {
                if (running) lastError = e.getMessage();
                break;
            }
        }
    }

    void onMessage(Session s, Msg m) {
        switch (m.type()) {
            case Protocol.HELLO:
                room.onJoin(s, m.str("name"), m.num("color", 0));
                break;
            case Protocol.PING:
                s.send(Msg.of(Protocol.PONG).put("t", m.raw("t")));
                break;
            default:
                if (s.playerId < 0) {
                    s.error("Сначала нужно представиться");
                    return;
                }
                room.onClientMessage(s.playerId, m);
                break;
        }
    }

    void onDisconnect(Session s) {
        sessions.remove(s);
        if (s.playerId >= 0) room.leave(s.playerId);
    }

    void broadcast(List<Msg> frame) {
        for (Session s : sessions) {
            if (!s.isClosed()) s.sendFrame(frame);
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Session s : sessions) s.close();
        sessions.clear();
        room.stop();
        if (beacon != null) beacon.close();
    }
}
