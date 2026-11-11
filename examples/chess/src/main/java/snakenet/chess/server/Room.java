package snakenet.chess.server;

import netcore.time.TickConfig;
import netcore.voice.VoiceGuard;
import netcore.voice.VoicePacket;
import snakenet.chess.Chess;
import snakenet.chess.core.GameConfig;
import netcore.netcode.Msg;
import netcore.netcode.NetworkBehaviour;
import netcore.netcode.NetworkObject;
import netcore.netcode.SyncVar;
import netcore.netcode.ValueCodecs;
import netcore.netcode.Wire;
import snakenet.chess.net.Net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One chess lobby. The server owns the board; clients only send moves and receive the
 * resulting position. There is no snake simulation in this class.
 */
public final class Room {

    public enum Phase { LOBBY, COUNT, RUNNING, OVER }

    public static final class Player {
        public final int id;
        public final String name;
        public final int color;
        public final boolean bot;
        public volatile boolean ready;
        public volatile netcore.net.Connection connection;
        GameServer.Session session;
        int chessColor = Chess.WHITE;

        VoiceGuard voiceGuard;
        long inputDropped;
        String token = "";
        volatile boolean reconnecting;
        long reconnectDeadline;

        Player(int id, String name, int color, boolean bot, netcore.net.Connection connection) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.bot = bot;
            this.connection = connection;
        }
    }

    private static final String[] BOT_NAMES = {
            "Bot Vasya", "Bot Lucy", "Bot Gosha", "Bot Cobra",
            "Bot Python", "Bot Boa", "Bot Viper", "Bot Spike"
    };

    private static final long RECONNECT_GRACE_MS = 30_000L;

    /** Replicated lobby/game state. This is the FishNet-style sync-var surface. */
    public static final class RoomSync extends NetworkBehaviour {
        public final SyncVar<Integer> phase = syncVar("phase", ValueCodecs.INT, 0);
        public final SyncVar<String> fen = syncVar("fen", ValueCodecs.STRING, "");
        public final SyncVar<Integer> turn = syncVar("turn", ValueCodecs.INT, 0);
        public final SyncVar<Integer> winner = syncVar("winner", ValueCodecs.INT, -1);
        public final SyncVar<String> reason = syncVar("reason", ValueCodecs.STRING, "");
        public final SyncVar<Integer> players = syncVar("players", ValueCodecs.INT, 0);
        public final SyncVar<Integer> host = syncVar("host", ValueCodecs.INT, -1);

        public RoomSync() {
            super("room");
        }
    }

    private final GameServer server;
    private final GameConfig cfg;
    private final Random rnd = new Random();
    private final int id;
    private final String name;
    private final int kind;
    private final RoomSync sync = new RoomSync();
    private final NetworkObject netObject;

    private final List<Player> players = new ArrayList<>();
    private final Map<Integer, Player> byId = new LinkedHashMap<>();
    private final Map<String, Player> byToken = new HashMap<>();
    private final Map<netcore.net.Connection, Player> byConnection = new HashMap<>();

    private netcore.time.TickRunner runner;
    private Phase phase = Phase.LOBBY;
    private int tick;
    private int tickBase;
    private int lastRunnerTick;
    private int countTicksLeft;
    private int lastCountShown = -1;
    private int nextId = 1;
    private int botsAdded;
    private volatile boolean closed;

    private Chess chess = new Chess();
    private int chessTurn = Chess.WHITE;
    private long nextBotMoveAt;
    /** When non-zero, a finished chess game returns to the lobby at this nanoTime. */
    private long chessLobbyAt;

    private long voiceAccepted;
    private long voiceRejected;

    Room(GameServer server, int id, String name, int kind, GameConfig cfg) {
        this.server = server;
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.cfg = cfg.copy();
        this.netObject = new NetworkObject(10_000 + id);
        this.netObject.addBehaviour(sync);
        server.netcode().spawn(netObject, null);
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int kind() {
        return kind;
    }

    public boolean closed() {
        return closed;
    }

    public boolean canJoin() {
        return !closed && phase == Phase.LOBBY && players.size() < cfg.maxPlayers;
    }

    public GameConfig config() {
        return cfg;
    }

    public RoomSync sync() {
        return sync;
    }

    Msg describe() {
        Msg m = Net.ROOM.newMsg()
                .put("id", id)
                .put("kind", kind)
                .put("players", players.size())
                .put("max", cfg.maxPlayers)
                .put("phase", phase.ordinal());
        m.putStr("name", name);
        return m;
    }

    public synchronized int playerCount() {
        return players.size();
    }

    public synchronized int humanCount() {
        int n = 0;
        for (Player p : players) if (!p.bot) n++;
        return n;
    }

    public TickConfig tickConfig() {
        return runner == null ? cfg.tickConfig() : runner.config();
    }

    public int tick() {
        return tick;
    }

    void start() {
        System.out.println("[room] '" + server.name() + "' chess tick runner started, phase=" + phase
                + " tickRate=" + cfg.tickRate);
        runner = new netcore.time.TickRunner(cfg.tickConfig(), this::onTick);
        runner.start();
    }

    void stop() {
        closed = true;
        if (runner != null) runner.stop();
    }

    private void onTick(int tickNumber, double lagMs) {
        synchronized (this) {
            if (closed) return;
            lastRunnerTick = tickNumber;
            tick = tickBase + tickNumber;
            try {
                expireReconnects();
                if (phase == Phase.COUNT) countdownTick();
                else if (phase == Phase.RUNNING) gameTick();
                else if (phase == Phase.OVER && chessLobbyAt > 0
                        && System.nanoTime() >= chessLobbyAt) {
                    backToLobby();
                }
                server.netcode().replicate();
            } catch (Throwable t) {
                System.err.println("[room] tick error: " + t);
            }
        }
    }

    private void syncState() {
        sync.phase.set(phase.ordinal());
        sync.fen.set(chess.toFen());
        sync.turn.set(chessTurn);
        sync.players.set(players.size());
        sync.host.set(hostId());
        if (phase != Phase.OVER) {
            sync.winner.set(-1);
            sync.reason.set("");
        }
    }

    private void expireReconnects() {
        long now = System.currentTimeMillis();
        List<Integer> gone = null;
        for (Player p : players) {
            if (p.reconnecting && p.reconnectDeadline > 0 && now >= p.reconnectDeadline) {
                if (gone == null) gone = new ArrayList<>();
                gone.add(p.id);
            }
        }
        if (gone != null) {
            for (int id : gone) leave(id);
        }
    }

    synchronized void addSession(GameServer.Session session, boolean creator) {
        if (closed || session.connection == null) return;
        Player attached = byId.get(session.id);
        if (attached != null) {
            attached.session = session;
            resume(session);
            return;
        }
        if (players.size() >= cfg.maxPlayers) {
            server.send(session.connection, Net.ERR.newMsg().putStr("msg", "Lobby is full"));
            return;
        }
        Player p = new Player(session.id, uniqueName(session.name), session.color, false, session.connection);
        p.session = session;
        p.token = session.token;
        p.voiceGuard = new VoiceGuard(cfg.voiceConfig());
        players.add(p);
        byId.put(p.id, p);
        nextId = Math.max(nextId, p.id + 1);
        if (!p.token.isEmpty()) byToken.put(p.token, p);
        if (p.connection != null) {
            byConnection.put(p.connection, p);
            p.connection.attachment = p;
            netObject.addObserver(p.connection);
        }
        resendWelcome(p.connection, p);
        broadcastLobby();
        system(p.name + (creator ? " created the lobby" : " joined"));
    }

    synchronized void onDisconnect(GameServer.Session session, netcore.net.Connection connection) {
        Player p = byId.get(session.id);
        if (p == null || (connection != null && p.connection != connection)) return;
        if (connection != null) {
            byConnection.remove(connection);
            netObject.removeObserver(connection);
        }
        p.connection = null;
        if (phase == Phase.LOBBY) {
            leave(p.id);
            return;
        }
        p.reconnecting = true;
        p.reconnectDeadline = System.currentTimeMillis() + RECONNECT_GRACE_MS;
        if (!p.bot) system(p.name + " lost connection");
        broadcastLobby();
    }

    synchronized void resume(GameServer.Session session) {
        if (closed) return;
        Player p = byId.get(session.id);
        if (p == null) {
            addSession(session, false);
            return;
        }
        netcore.net.Connection prev = p.connection;
        if (prev != null && prev != session.connection) {
            if (prev.attachment == p) prev.attachment = null;
            byConnection.remove(prev);
            netObject.removeObserver(prev);
        }
        p.session = session;
        p.connection = session.connection;
        p.reconnecting = false;
        p.reconnectDeadline = 0;
        p.voiceGuard = null;
        if (session.connection != null) {
            session.connection.attachment = p;
            byConnection.put(session.connection, p);
            netObject.addObserver(session.connection);
        }
        resendWelcome(session.connection, p);
        system(p.name + " reconnected");
        broadcastLobby();
    }

    private void resendWelcome(netcore.net.Connection connection, Player p) {
        if (connection == null) return;
        Msg welcome = Net.WELCOME.newMsg()
                .put("pid", p.id)
                .put("host", isHost(p) ? 1 : 0)
                .put("phase", phase.ordinal())
                .put("rate", cfg.tickRate)
                .put("room", id)
                .put("kind", kind)
                .put("cc", p.chessColor);
        cfg.write(welcome);
        welcome.putStr("roomName", name);
        server.send(connection, welcome);

        if (phase == Phase.COUNT || phase == Phase.RUNNING) {
            server.send(connection, Wire.encode(startFrame()));
        } else if (phase == Phase.OVER) {
            server.send(connection, Wire.encode(Net.CHESS_END.newMsg()
                    .put("w", sync.winner.get()).putStr("r", sync.reason.get())));
        }
        server.netcode().sendFull(connection, netObject);
    }

    synchronized void removeSession(GameServer.Session session) {
        Player p = byId.get(session.id);
        if (p != null) leave(p.id);
    }

    synchronized void leave(int playerId) {
        Player p = byId.remove(playerId);
        if (p == null) return;
        players.remove(p);
        if (p.token != null && !p.token.isEmpty()) byToken.remove(p.token, p);
        if (p.connection != null) {
            byConnection.remove(p.connection);
            netObject.removeObserver(p.connection);
            p.connection.attachment = null;
        }
        if (p.session != null) p.session.room = null;
        if (!p.bot) {
            if (phase == Phase.LOBBY) system(p.name + " left");
            else system(p.name + " disconnected");
        }
        if (phase == Phase.RUNNING) {
            if (players.isEmpty()) {
                backToLobby();
                if (hostId() < 0) server.onRoomEmpty(this);
                return;
            }
            if (players.size() == 1 && !players.get(0).bot) {
                finishChess(players.get(0).id, "opponent left");
            }
        }
        syncState();
        broadcastLobby();
        if (hostId() < 0) server.onRoomEmpty(this);
    }

    private String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (nameTaken(name)) {
            name = base + " " + n++;
            if (name.length() > 18) name = base.substring(0, Math.min(base.length(), 14)) + " " + n;
        }
        return name;
    }

    private boolean nameTaken(String name) {
        for (Player p : players) if (p.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    private boolean isHost(Player p) {
        return p != null && !p.bot && p.id == hostId();
    }

    private static int pingMs(Player p) {
        if (p.bot) return -1;
        netcore.net.Connection c = p.connection;
        return c == null ? -1 : c.rttMs();
    }

    public synchronized int hostId() {
        for (Player p : players) if (!p.bot) return p.id;
        return -1;
    }

    synchronized void onMessage(netcore.net.Connection connection, Msg m) {
        Player p = byConnection.get(connection);
        if (p == null || closed) return;
        if (m.is(Net.READY)) {
            p.ready = m.bool("v");
            broadcastLobby();
        } else if (m.is(Net.CHAT)) {
            chat(p, m.str("text"));
        } else if (m.is(Net.SET)) {
            if (isHost(p)) applySetting(m.raw("k"), m.num("v", 0));
        } else if (m.is(Net.BOT)) {
            if (isHost(p)) botCommand(m.raw("op"));
        } else if (m.is(Net.CHESS_MOVE)) {
            if (phase != Phase.RUNNING || p.bot || p.chessColor != chessTurn) return;
            Chess.Move move = null;
            for (Chess.Move candidate : chess.legalMoves()) {
                if (candidate.from == m.num("f", -1) && candidate.to == m.num("t", -1)
                        && candidate.promotion == m.num("pr", 0)) {
                    move = candidate;
                    break;
                }
            }
            if (move != null) applyChessMove(p, move);
        } else if (m.is(Net.KICK_PLAYER)) {
            if (isHost(p)) kick(p, m.num("p", -1));
        } else if (m.is(Net.START)) {
            if (isHost(p)) startCountdown();
        } else if (m.is(Net.AGAIN)) {
            if (isHost(p) && phase == Phase.OVER) backToLobby();
        }
    }

    private void kick(Player host, int targetId) {
        Player target = byId.get(targetId);
        if (target == null || target.id == host.id || target.bot) return;
        if (target.connection != null) {
            server.send(target.connection, Wire.encode(List.of(
                    Msg.of(Net.KICK).putStr("msg", "The host kicked you from the game"))));
            target.connection.close();
        }
        String name = target.name;
        leave(target.id);
        system(name + " was kicked by the host");
    }

    synchronized void onVoice(netcore.net.Connection connection, byte[] payload) {
        if (!cfg.voice) {
            voiceRejected++;
            return;
        }
        Player p = connection.attachment instanceof Player ? (Player) connection.attachment : null;
        if (p == null || p.bot || phase != Phase.RUNNING) {
            voiceRejected++;
            return;
        }
        if (payload.length < 5) {
            voiceRejected++;
            return;
        }
        int sequence = ((payload[1] & 0xff) << 8) | (payload[2] & 0xff);
        int payloadBytes = payload.length - 3;
        if (payloadBytes < 2 || (payloadBytes & 1) != 0 || payloadBytes > cfg.voiceMaxPacketBytes) {
            voiceRejected++;
            return;
        }
        if (p.voiceGuard == null) p.voiceGuard = new VoiceGuard(cfg.voiceConfig());
        short[] pcm = VoicePacket.readPcm(payload, 3, payloadBytes);
        if (!p.voiceGuard.accept(sequence, pcm, System.nanoTime())) {
            voiceRejected++;
            return;
        }
        voiceAccepted++;
        byte[] out = new byte[payload.length + 2];
        out[0] = Wire.VOICE;
        out[1] = (byte) (p.id >> 8);
        out[2] = (byte) p.id;
        System.arraycopy(payload, 1, out, 3, payload.length - 1);
        server.broadcastVoice(members(), out, connection);
    }

    private void chat(Player from, String text) {
        String clean = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 200) clean = clean.substring(0, 200);
        Msg m = Net.CHAT_OUT.newMsg().putStr("from", from.name).putStr("text", clean).put("kind", 0)
                .put("p", from.id).put("c", from.color);
        broadcastRoom(Wire.encode(m));
    }

    private void system(String text) {
        broadcastRoom(Wire.encode(
                Net.CHAT_OUT.newMsg().putStr("from", "").putStr("text", text).put("kind", 1)));
    }

    private void applySetting(String key, int value) {
        if (phase != Phase.LOBBY) return;
        switch (key) {
            case "chessSide":
                cfg.chessSide = Math.max(0, Math.min(2, value));
                break;
            case "chessBot":
                cfg.chessBotDepth = Math.max(1, Math.min(4, value));
                break;
            default:
                return;
        }
        broadcastLobby();
    }

    private void botCommand(String op) {
        if (phase != Phase.LOBBY) return;
        if ("add".equals(op)) {
            if (players.size() >= cfg.maxPlayers) return;
            String base = BOT_NAMES[botsAdded % BOT_NAMES.length];
            botsAdded++;
            while (byId.containsKey(nextId)) nextId++;
            Player p = new Player(nextId++, uniqueName(base), (botsAdded + 3) % 10, true, null);
            players.add(p);
            byId.put(p.id, p);
            broadcastLobby();
        } else if ("del".equals(op)) {
            Player last = null;
            for (Player p : players) if (p.bot) last = p;
            if (last != null) {
                players.remove(last);
                byId.remove(last.id);
                broadcastLobby();
            }
        }
    }

    private void startCountdown() {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return;
        if (players.isEmpty()) return;
        if (players.size() < 2) botCommand("add");
        resetRound();
        phase = Phase.COUNT;
        syncState();
        countTicksLeft = 3 * cfg.tickRate;
        lastCountShown = 4;
        broadcastRoom(Wire.encode(startFrame()));
        broadcastRoom(Wire.encode(Net.COUNT.newMsg().put("n", 3)));
        lastCountShown = 3;
        broadcastLobby();
    }

    private List<Msg> startFrame() {
        Msg m = Net.STARTED.newMsg().put("phase", Net.PHASE_COUNT).put("n", 3);
        cfg.write(m);
        return new ArrayList<>(List.of(m));
    }

    private void countdownTick() {
        countTicksLeft--;
        int seconds = (int) Math.ceil(countTicksLeft / (double) cfg.tickRate);
        if (seconds > 0 && seconds < lastCountShown) {
            lastCountShown = seconds;
            broadcastRoom(Wire.encode(List.of(Msg.of(Net.COUNT).put("n", seconds))));
        }
        if (countTicksLeft <= 0) beginRun();
    }

    private void resetRound() {
        chessLobbyAt = 0;
        int index = 0;
        int firstColor = cfg.chessSide == 0 ? Chess.WHITE
                : cfg.chessSide == 1 ? Chess.BLACK : rnd.nextBoolean() ? Chess.WHITE : Chess.BLACK;
        for (Player p : players) {
            p.ready = false;
            p.voiceGuard = null;
            p.chessColor = index == 0 ? firstColor
                    : (firstColor == Chess.WHITE ? Chess.BLACK : Chess.WHITE);
            index++;
        }
        chess = new Chess();
        chessTurn = Chess.WHITE;
        syncState();
        for (Player p : players) resendWelcome(p.connection, p);
    }

    private void beginRun() {
        phase = Phase.RUNNING;
        countTicksLeft = 0;
        syncState();
        chessTick();
    }

    private void gameTick() {
        chessTick();
    }

    private void chessTick() {
        if (phase != Phase.RUNNING) return;
        Player mover = playerForColor(chessTurn);
        if (mover == null || !mover.bot) return;
        long now = System.nanoTime();
        if (now < nextBotMoveAt) return;
        nextBotMoveAt = now + 450_000_000L;
        Chess.Move move = chess.bestMove(cfg.chessBotDepth);
        if (move == null) {
            finishChess();
            return;
        }
        applyChessMove(mover, move);
    }

    private Player playerForColor(int color) {
        for (Player p : players) {
            if (p.chessColor == color) return p;
        }
        return null;
    }

    private void applyChessMove(Player p, Chess.Move move) {
        Chess.Move legalMove = null;
        for (Chess.Move candidate : chess.legalMoves()) {
            if (candidate.from == move.from && candidate.to == move.to && candidate.promotion == move.promotion) {
                legalMove = candidate;
                break;
            }
        }
        if (legalMove == null) {
            p.inputDropped++;
            return;
        }
        chess = chess.apply(legalMove);
        Msg moveMsg = Net.CHESS_MOVE_OUT.newMsg()
                .put("p", p.id).put("f", legalMove.from).put("t", legalMove.to)
                .put("pr", legalMove.promotion)
                .put("fen", chess.toFen());
        broadcastRoom(Wire.encode(moveMsg));
        chessTurn = chess.turn();
        syncState();
        if (chess.isCheckmate() || chess.isStalemate() || chess.isDraw()) {
            finishChess();
        }
    }

    private void finishChess() {
        if (phase != Phase.RUNNING) return;
        int winnerColor = chessTurn == Chess.WHITE ? Chess.BLACK : Chess.WHITE;
        int winnerPlayerId = -1;
        for (Player p : players) if (p.chessColor == winnerColor) winnerPlayerId = p.id;
        if (chess.isStalemate() || chess.isDraw()) winnerPlayerId = -1;
        String reason = chess.isCheckmate() ? "checkmate" : chess.isDraw() ? "draw" : "stalemate";
        finishChess(winnerPlayerId, reason);
    }

    private void finishChess(int winnerPlayerId, String reason) {
        if (phase == Phase.OVER) return;
        phase = Phase.OVER;
        syncState();
        sync.winner.set(winnerPlayerId);
        sync.reason.set(reason);
        broadcastRoom(Wire.encode(Net.CHESS_END.newMsg().put("w", winnerPlayerId).putStr("r", reason)));
        system("Chess finished: " + reason);
        chessLobbyAt = System.nanoTime() + 4_000_000_000L;
    }

    private void backToLobby() {
        phase = Phase.LOBBY;
        chessLobbyAt = 0;
        chess = new Chess();
        chessTurn = Chess.WHITE;
        for (Player p : players) {
            p.ready = false;
        }
        syncState();
        broadcastLobby();
        system("Back to the lobby");
    }

    /** Connections that belong to this room, never the other lobbies on the hub. */
    private List<netcore.net.Connection> members() {
        List<netcore.net.Connection> list = new ArrayList<>();
        for (Player p : players) {
            netcore.net.Connection c = p.connection;
            if (c != null && c.isOpen()) list.add(c);
        }
        return list;
    }

    private void broadcastRoom(byte[] payload) {
        server.sendTo(members(), payload, true);
    }

    void broadcastLobby() {
        List<Msg> frame = new ArrayList<>();
        Msg head = Msg.of(Net.LOBBY)
                .put("phase", phase.ordinal())
                .put("host", hostId())
                .put("tick", tick);
        cfg.write(head);
        frame.add(head);
        for (Player p : players) {
            Msg m = Msg.of(Net.PLAYER)
                    .put("id", p.id)
                    .put("c", p.color)
                    .put("ready", p.ready ? 1 : 0)
                    .put("bot", p.bot ? 1 : 0)
                    .put("host", isHost(p) ? 1 : 0)
                    .put("cc", p.chessColor)
                    .put("alive", 1)
                    .put("rc", p.reconnecting ? 1 : 0)
                    .put("pg", pingMs(p));
            m.putStr("name", p.name);
            frame.add(m);
        }
        broadcastRoom(Wire.encode(frame));
    }
}
