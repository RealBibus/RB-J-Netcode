package snakenet.chess.server;

import netcore.net.Connection;
import netcore.net.LanDiscovery;
import netcore.net.NetServer;
import netcore.netcode.Msg;
import netcore.netcode.NetworkServer;
import netcore.netcode.Wire;
import snakenet.chess.core.GameConfig;
import snakenet.chess.net.Net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A dedicated chess server that owns a hub and any number of lobbies. Clients first connect
 * to the hub, then create or join a chess lobby.
 */
public final class GameServer {

    public static final class Session {
        public final int id;
        public final String token;
        public String name;
        public int color;
        public volatile Connection connection;
        public volatile Room room;

        Session(int id, String token, String name, int color) {
            this.id = id;
            this.token = token == null ? "" : token;
            this.name = name;
            this.color = color;
        }
    }

    private final String name;
    private final GameConfig defaultConfig;
    private final NetServer net;
    private final NetworkServer netcode;
    private final Map<Connection, Session> byConnection = new ConcurrentHashMap<>();
    private final Map<String, Session> byToken = new ConcurrentHashMap<>();
    private final Map<Integer, Room> rooms = new LinkedHashMap<>();
    private LanDiscovery.Beacon beacon;
    private volatile boolean running;
    private volatile String lastError;
    private int nextSessionId = 1;
    private int nextRoomId = 1;

    public GameServer(int port, String name, GameConfig cfg) {
        this.name = name == null || name.isBlank() ? "Game" : name;
        this.defaultConfig = cfg == null ? new GameConfig() : cfg.copy();
        this.netcode = new NetworkServer((connection, payload, reliable) -> {
            if (connection != null && connection.isOpen()) connection.send(payload, reliable);
        });
        this.net = new NetServer(port, this.defaultConfig.netConfig(true), new NetServer.Listener() {
            @Override
            public void onConnect(Connection connection) {
                System.out.println("[room] client connected: id=" + connection.id());
            }

            @Override
            public void onMessage(Connection connection, byte[] payload) {
                if (Wire.isVoice(payload)) {
                    onVoice(connection, payload);
                    return;
                }
                handle(connection, Wire.decode(payload));
            }

            @Override
            public void onDisconnect(Connection connection, String reason) {
                GameServer.this.onDisconnect(connection, reason);
            }
        });
    }

    public void start() throws IOException {
        System.out.println("[snakenet] server '" + name + "' starting on port " + net.port());
        net.start();
        beacon = new LanDiscovery.Beacon(name, net.port(), this::playerCount, () -> defaultConfig.maxPlayers);
        beacon.start();
        running = true;
        System.out.println("[snakenet] server '" + name + "' listening on port " + net.port()
                + " protocols=" + net.protocols());
    }

    public int port() {
        return net.port();
    }

    public String name() {
        return name;
    }

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return lastError;
    }

    public GameConfig config() {
        return defaultConfig;
    }

    public NetworkServer netcode() {
        return netcode;
    }

    public synchronized List<Room> rooms() {
        return new ArrayList<>(rooms.values());
    }

    public synchronized Room room(int id) {
        return rooms.get(id);
    }

    public synchronized int playerCount() {
        int n = 0;
        for (Room room : rooms.values()) n += room.playerCount();
        return n;
    }

    // ------------------------------------------------------------------ hub

    private void handle(Connection connection, List<Msg> frame) {
        Session session = byConnection.get(connection);
        for (Msg m : frame) {
            if (m.is(Net.HELLO)) {
                session = hello(connection, session, m);
                continue;
            }
            if (session == null) {
                send(connection, Net.ERR.newMsg().putStr("msg", "Say hello before using the hub"));
                continue;
            }
            if (m.is(Net.LOBBY_LIST)) {
                sendHub(session);
            } else if (m.is(Net.LOBBY_CREATE)) {
                createRoom(session, m);
            } else if (m.is(Net.LOBBY_JOIN)) {
                joinRoom(session, m.num("id", -1));
            } else if (m.is(Net.LOBBY_LEAVE)) {
                leaveRoom(session);
            } else if (session.room != null) {
                session.room.onMessage(connection, m);
            }
        }
    }

    private Session hello(Connection connection, Session current, Msg m) {
        String token = m.str("token");
        Session known = token.isEmpty() ? null : byToken.get(token);
        if (known != null) {
            Connection old = known.connection;
            known.connection = connection;
            known.name = GameConfig.sanitizeName(m.str("name"));
            known.color = Math.floorMod(m.num("color", known.color), 10);
            byConnection.put(connection, known);
            if (old != null && old != connection) {
                byConnection.remove(old, known);
                send(old, Net.KICK.newMsg().putStr("msg", "Session resumed in another window"));
                old.close();
            }
            if (known.room != null) {
                known.room.resume(known);
            } else {
                sendHub(known);
            }
            return known;
        }
        if (current != null) {
            if (current.room != null) {
                current.room.resume(current);
            } else {
                sendHub(current);
            }
            return current;
        }
        Session session = new Session(nextSessionId++, token,
                GameConfig.sanitizeName(m.str("name")), Math.floorMod(m.num("color", 0), 10));
        session.connection = connection;
        byConnection.put(connection, session);
        if (!session.token.isEmpty()) byToken.put(session.token, session);
        sendHub(session);
        System.out.println("[room] session " + session.id + " '" + session.name + "' joined the hub");
        return session;
    }

    private void sendHub(Session session) {
        Connection c = session.connection;
        if (c == null) return;
        Msg hub = Net.HUB.newMsg()
                .put("sid", session.id)
                .putStr("server", name)
                .put("rooms", rooms.size());
        List<Msg> frame = new ArrayList<>();
        frame.add(hub);
        synchronized (this) {
            for (Room room : rooms.values()) {
                frame.add(room.describe());
            }
        }
        net.send(c, Wire.encode(frame));
    }

    public void sendHub(Connection connection) {
        Session s = byConnection.get(connection);
        if (s != null) sendHub(s);
    }

    public synchronized void createRoom(Session session, Msg m) {
        if (session.room != null) {
            send(session.connection, Net.ERR.newMsg().putStr("msg", "Leave the current lobby first"));
            return;
        }
        int kind = Net.KIND_CHESS;
        String roomName = GameConfig.sanitizeName(m.str("name"));
        if (roomName.isBlank() || "Player".equals(roomName)) roomName = session.name + "'s lobby";
        GameConfig cfg = GameConfig.read(m, defaultConfig);
        cfg.maxPlayers = 2;
        cfg.serverName = roomName;
        Room room = new Room(this, nextRoomId++, roomName, kind, cfg);
        rooms.put(room.id(), room);
        room.start();
        attach(session, room, true);
        System.out.println("[room] lobby " + room.id() + " '" + roomName + "' created by " + session.name
                + " kind=chess"));
    }

    private synchronized void joinRoom(Session session, int roomId) {
        if (session.room != null) {
            send(session.connection, Net.ERR.newMsg().putStr("msg", "Leave the current lobby first"));
            return;
        }
        Room room = rooms.get(roomId);
        if (room == null || room.closed()) {
            send(session.connection, Net.ERR.newMsg().putStr("msg", "Lobby not found"));
            return;
        }
        if (!room.canJoin()) {
            send(session.connection, Net.ERR.newMsg().putStr("msg", "Lobby is full or already playing"));
            return;
        }
        attach(session, room, false);
    }

    private void attach(Session session, Room room, boolean creator) {
        session.room = room;
        send(session.connection, Net.JOINED.newMsg()
                .put("id", room.id())
                .put("kind", room.kind())
                .putStr("name", room.name())
                .put("host", creator ? 1 : 0));
        room.addSession(session, creator);
    }

    private synchronized void leaveRoom(Session session) {
        Room room = session.room;
        if (room == null) return;
        room.removeSession(session);
        session.room = null;
        send(session.connection, Net.LEFT.newMsg());
        sendHub(session);
    }

    synchronized void onRoomEmpty(Room room) {
        if (!rooms.remove(room.id(), room)) return;
        room.stop();
        System.out.println("[room] lobby " + room.id() + " closed");
    }

    private void onDisconnect(Connection connection, String reason) {
        Session session = byConnection.remove(connection);
        if (session == null) return;
        if (session.connection == connection) session.connection = null;
        Room room = session.room;
        if (room != null) {
            room.onDisconnect(session, connection);
        } else {
            byToken.remove(session.token, session);
        }
        System.out.println("[room] client disconnected: id=" + connection.id() + " (" + reason + ")");
    }

    private void onVoice(Connection connection, byte[] payload) {
        Session session = byConnection.get(connection);
        if (session != null && session.room != null) session.room.onVoice(connection, payload);
    }

    // ------------------------------------------------------- room callbacks

    void send(Connection connection, Msg message) {
        net.send(connection, Wire.encode(message));
    }

    void send(Connection connection, byte[] payload) {
        if (connection != null && connection.isOpen()) net.send(connection, payload);
    }

    public void broadcast(byte[] payload) {
        net.broadcast(payload, true);
    }

    void broadcastUnreliable(byte[] payload) {
        net.broadcast(payload, false);
    }

    void broadcastVoice(byte[] payload, Connection except) {
        for (Connection c : net.connections()) {
            if (c != except && c.isOpen()) c.sendVoice(payload);
        }
    }

    void broadcastVoice(Collection<Connection> targets, byte[] payload, Connection except) {
        for (Connection c : targets) {
            if (c != null && c != except && c.isOpen()) c.sendVoice(payload);
        }
    }

    void sendTo(Collection<Connection> connections, byte[] payload, boolean reliable) {
        for (Connection c : connections) {
            if (c != null && c.isOpen()) c.send(payload, reliable);
        }
    }

    public String protocols() {
        return net.protocols();
    }

    public void stop() {
        if (!running) return;
        running = false;
        System.out.println("[snakenet] server '" + name + "' stopping");
        List<Room> copy;
        synchronized (this) {
            copy = new ArrayList<>(rooms.values());
            rooms.clear();
        }
        for (Room room : copy) room.stop();
        net.stop();
        if (beacon != null) beacon.close();
    }
}

