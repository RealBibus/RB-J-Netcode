package snakenet.ui;

import snakenet.Prefs;
import snakenet.audio.Sfx;
import snakenet.client.GameClient;
import snakenet.client.RoomView;
import snakenet.game.Chess;
import snakenet.game.GameConfig;
import netcore.netcode.Msg;
import netcore.net.NetInfo;
import netcore.voice.VoiceEngine;
import snakenet.net.Net;
import snakenet.server.GameServer;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class App {

    /** One lobby as advertised by the hub. */
    public static final class RoomInfo {
        public int id;
        public int kind;
        public int players;
        public int max;
        public int phase;
        public String name = "";
    }

    private final Prefs prefs;
    private final Backdrop backdrop = new Backdrop();
    private final GameConfig netDefaults = new GameConfig();
    private final RoomView roomView = new RoomView();
    private String voiceKey = "";
    private boolean standaloneServer = true;

    private MainWindow window;
    private GameClient client;
    private VoiceEngine voice;
    private GameServer server;

    private int myId = -1;
    private String myName = "";
    private int myColor;
    private int myChessColor = Chess.WHITE;
    private String gameAddress = "";
    private int gameKind = Net.KIND_SNAKE;
    private boolean roomHost;

    private int hubSid = -1;
    private String serverName = "";
    private int roomId = -1;
    private String roomName = "";
    private boolean inLobby;
    private GameConfig pendingCreate;
    private final List<RoomInfo> hubRooms = new ArrayList<>();

    private final String sessionToken = java.util.UUID.randomUUID().toString();

    private HomeScreen home;
    private HostScreen hostScreen;
    private JoinScreen joinScreen;
    private BrowserScreen browser;
    private LobbyScreen lobby;
    private GameScreen game;

    private final java.util.List<String> log = new java.util.ArrayList<>();

    private String toast;
    private long toastUntil;
    private boolean toastError;

    private volatile boolean linkLost;
    private volatile boolean previewLink;
    private volatile long linkLostNanos;
    private volatile long linkWindowMs = 30_000;
    private volatile long linkRestoredNanos;
    private volatile boolean linkRestoredVisible;

    public App(Prefs prefs) {
        this.prefs = prefs;
        this.myName = prefs.get("name", "Player");
        this.myColor = prefs.getInt("color", 0);
        Sfx.setEnabled(prefs.getBool("sound", true));
    }

    public void start() {
        prepareOffscreenWindow();
        window.setVisible(true);
        window.show(homeScreen());
    }

    public void prepareOffscreenWindow() {
        if (window == null) window = new MainWindow(this);
    }

    public void setPreviewHost(boolean host) {
        this.roomHost = host;
    }

    public void setPreviewId(int id) {
        this.myId = id;
    }

    public void setPreviewAddress(String address) {
        this.gameAddress = address == null ? "" : address;
    }

    public void setPreviewLinkLost(boolean on) {
        previewLink = on;
        linkLost = on;
        linkLostNanos = System.nanoTime() - 3_000_000_000L;
    }

    public MainWindow window() {
        return window;
    }

    public Backdrop backdrop() {
        return backdrop;
    }

    public Prefs prefs() {
        return prefs;
    }

    public HomeScreen homeScreen() {
        if (home == null) home = new HomeScreen(this);
        return home;
    }

    public HostScreen hostScreen() {
        if (hostScreen == null) hostScreen = new HostScreen(this);
        return hostScreen;
    }

    public JoinScreen joinScreen() {
        if (joinScreen == null) joinScreen = new JoinScreen(this);
        return joinScreen;
    }

    public BrowserScreen browserScreen() {
        if (browser == null) browser = new BrowserScreen(this);
        return browser;
    }

    public LobbyScreen lobbyScreen() {
        if (lobby == null) lobby = new LobbyScreen(this);
        return lobby;
    }

    public GameScreen gameScreen() {
        if (game == null) game = new GameScreen(this);
        return game;
    }

    public GameClient client() {
        return client;
    }

    public GameServer server() {
        return server;
    }

    public RoomView roomView() {
        return roomView;
    }

    public int myId() {
        return myId;
    }

    public String myName() {
        return myName;
    }

    public int myColor() {
        return myColor;
    }

    public int myChessColor() {
        return myChessColor;
    }

    public String gameAddress() {
        return gameAddress;
    }

    public int gameKind() {
        return gameKind;
    }

    public void setGameKind(int kind) {
        this.gameKind = kind == Net.KIND_CHESS ? Net.KIND_CHESS : Net.KIND_SNAKE;
    }

    public String gameKindName() {
        return Net.kindName(gameKind);
    }

    /** Room host (lobby owner), not the owner of the machine running the server. */
    public boolean isHost() {
        return roomHost;
    }

    public boolean ownsServer() {
        return server != null;
    }

    public boolean standaloneServer() {
        return standaloneServer;
    }

    public void setStandaloneServer(boolean standaloneServer) {
        this.standaloneServer = standaloneServer;
    }

    public int hubSid() {
        return hubSid;
    }

    public String serverName() {
        return serverName;
    }

    public int roomId() {
        return roomId;
    }

    public String roomName() {
        return roomName;
    }

    public boolean inLobby() {
        return inLobby;
    }

    public List<RoomInfo> hubRooms() {
        return hubRooms;
    }

    public void setNameAndColor(String name, int color) {
        myName = GameConfig.sanitizeName(name);
        myColor = Math.floorMod(color, 10);
        prefs.set("name", myName);
        prefs.set("color", myColor);
    }

    /**
     * Starts a dedicated server on this machine, connects to it and asks the hub for a
     * lobby. This is the "create game" path used by the Snake and Chess menus.
     */
    public void hostGame(int port, GameConfig cfg, String name, int color) throws IOException {
        closeConnections();
        setNameAndColor(name, color);
        setGameKind(cfg.chess ? Net.KIND_CHESS : Net.KIND_SNAKE);
        GameServer s = new GameServer(port, myName, cfg);
        s.start();
        server = s;
        gameAddress = NetInfo.primaryIp() + ":" + s.port();
        pendingCreate = cfg.copy();
        String selfHost = standaloneServer ? NetInfo.primaryIp() : "127.0.0.1";
        try {
            connect(selfHost, s.port());
        } catch (IOException e) {
            try {
                connect("127.0.0.1", s.port());
            } catch (IOException fallbackError) {
                s.stop();
                server = null;
                pendingCreate = null;
                throw fallbackError;
            }
        }
        window.show(browserScreen());
        toast("Server started on port " + s.port());
    }

    /** Connects to an existing hub and opens the lobby browser. */
    public void joinGame(String host, int port, String name, int color) throws IOException {
        closeConnections();
        setNameAndColor(name, color);
        pendingCreate = null;
        gameAddress = host + ":" + port;
        connect(host, port);
        window.show(browserScreen());
    }

    private void connect(String host, int port) throws IOException {
        final GameClient[] holder = new GameClient[1];
        holder[0] = GameClient.connect(host, port, new GameClient.Listener() {
            @Override
            public void onFrame(List<Msg> frame) {
                handleFrame(frame);
            }

            @Override
            public void onVoice(int playerId, int sequence, short[] pcm) {
                VoiceEngine v = voice;
                if (v != null) v.onVoice(playerId, sequence, pcm);
            }

            @Override
            public void onClosed(String reason) {
                if (holder[0] != null && holder[0] == client) {
                    onConnectionLost(reason);
                }
            }

            @Override
            public void onLinkLost(String reason, long retryForMs) {
                if (holder[0] != null && holder[0] == client) {
                    linkLost = true;
                    linkLostNanos = System.nanoTime();
                    linkWindowMs = retryForMs;
                    linkRestoredVisible = false;
                    log("link lost: " + reason);
                }
            }

            @Override
            public void onLinkRestored() {
                if (holder[0] != null && holder[0] == client) {
                    long lostMs = (System.nanoTime() - linkLostNanos) / 1_000_000L;
                    linkLost = false;
                    linkRestoredNanos = System.nanoTime();
                    linkRestoredVisible = lostMs > 750;
                    log("link restored after " + lostMs + " ms");
                    if (linkRestoredVisible) toast("Connection restored");
                    GameClient c = client;
                    if (c != null) {
                        c.send(Msg.of(Net.HELLO).putStr("name", myName)
                                .put("color", myColor).putStr("token", sessionToken()));
                    }
                }
            }
        }, netDefaults.netConfig(false));
        client = holder[0];
        client.netcode().addBehaviour(roomView);
        client.netcode().start();
        myId = -1;
        roomId = -1;
        inLobby = false;
        roomHost = false;
        hubSid = -1;
        hubRooms.clear();
        if (voice != null) voice.stop();
        voice = null;
        voiceKey = "";
        applyVoice(netDefaults);
        client.send(Msg.of(Net.HELLO).putStr("name", myName).put("color", myColor)
                .putStr("token", sessionToken()));
    }

    /** Creates a lobby on the hub we are already connected to. */
    public void createLobby(GameConfig cfg) {
        if (client == null) {
            toastError("Not connected to a server");
            return;
        }
        GameConfig c = cfg == null ? new GameConfig() : cfg.copy();
        c.chess = gameKind == Net.KIND_CHESS;
        if (c.chess) {
            c.maxPlayers = 2;
            if (c.serverName == null || c.serverName.isBlank()) c.serverName = myName + "'s chess";
        } else if (c.serverName == null || c.serverName.isBlank()) {
            c.serverName = myName + "'s snake";
        }
        Msg m = c.write(Net.LOBBY_CREATE.newMsg())
                .put("kind", gameKind)
                .putStr("name", c.serverName);
        send(m);
    }

    public void leaveLobby() {
        if (client != null && (inLobby || roomId >= 0)) send(Net.LOBBY_LEAVE.newMsg());
        inLobby = false;
        roomId = -1;
        roomName = "";
        roomHost = false;
        roomView.reset();
        window.show(browserScreen());
    }

    public void refreshHub() {
        send(Net.LOBBY_LIST.newMsg());
    }

    private void applyVoice(GameConfig cfg) {
        String key = cfg.voice + ":" + cfg.voiceMode + ":" + cfg.voiceJitterTarget + ":"
                + cfg.voiceJitterMax + ":" + cfg.voiceAdaptive + ":" + cfg.voiceActivity
                + ":" + cfg.voiceMaxPacketBytes + ":" + cfg.voiceMaxPacketsPerSec;
        if (key.equals(voiceKey)) return;
        voiceKey = key;
        if (voice != null) voice.stop();
        voice = null;
        if (!cfg.voice) return;
        VoiceEngine engine = new VoiceEngine(cfg.voiceConfig(), frame -> {
            GameClient c = client;
            if (c != null) c.sendVoice(frame);
        });
        engine.start();
        voice = engine;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public VoiceEngine voice() {
        return voice;
    }

    public void send(Msg m) {
        GameClient c = client;
        if (c != null && c.isOpen()) c.send(m);
    }

    public void leaveToHome() {
        closeConnections();
        window.show(homeScreen());
    }

    private void closeConnections() {
        linkLost = false;
        linkRestoredVisible = false;
        GameClient c = client;
        client = null;
        if (c != null) {
            log("disconnected");
            c.close();
        }
        if (voice != null) {
            voice.stop();
            voice = null;
        }
        voiceKey = "";
        GameServer s = server;
        server = null;
        if (s != null) s.stop();
        roomHost = false;
        myId = -1;
        roomId = -1;
        roomName = "";
        inLobby = false;
        hubSid = -1;
        serverName = "";
        hubRooms.clear();
        roomView.reset();
    }

    public void shutdown() {
        closeConnections();
        if (window != null) window.dispose();
        System.exit(0);
    }

    public void startDemo(boolean autoStart) {
        try {
            GameConfig cfg = new GameConfig();
            cfg.applySize(1);
            cfg.applySpeed(1);
            hostGame(NetInfo.freePortFrom(45900), cfg, myName, myColor);
            final int[] waited = {0};
            javax.swing.Timer wait = new javax.swing.Timer(100, null);
            wait.addActionListener(e -> {
                waited[0] += 100;
                if (roomId < 0 && waited[0] < 6000) return;
                wait.stop();
                for (int i = 0; i < 3; i++) send(Msg.of(Net.BOT).put("op", "add"));
                if (autoStart) {
                    javax.swing.Timer timer = new javax.swing.Timer(700, ev -> send(Net.START.newMsg()));
                    timer.setRepeats(false);
                    timer.start();
                }
            });
            wait.start();
        } catch (IOException e) {
            toastError("Demo failed to start: " + e.getMessage());
        }
    }

    private void handleFrame(List<Msg> frame) {
        boolean start = false;
        Msg startMsg = null;
        int lobbyPhase = -1;
        boolean hub = false;
        List<Msg> roomMsgs = null;
        for (Msg m : frame) {
            if (m.is(Net.HUB)) {
                hubSid = m.num("sid", -1);
                serverName = m.str("server");
                hub = true;
            } else if (m.is(Net.ROOM)) {
                if (roomMsgs == null) roomMsgs = new ArrayList<>();
                roomMsgs.add(m);
            } else if (m.is(Net.JOINED)) {
                roomId = m.num("id", -1);
                roomName = m.str("name");
                setGameKind(m.num("kind", gameKind));
                inLobby = true;
                log("joined lobby " + roomName + " (#" + roomId + ")");
            } else if (m.is(Net.LEFT)) {
                inLobby = false;
                roomId = -1;
                roomName = "";
                roomHost = false;
                roomView.reset();
                log("left the lobby");
            } else if (m.is(Net.WELCOME)) {
                myId = m.num("pid", -1);
                roomHost = m.num("host", 0) == 1;
                roomId = m.num("room", roomId);
                String rn = m.str("roomName");
                if (!rn.isBlank()) roomName = rn;
                setGameKind(m.num("kind", gameKind));
                myChessColor = m.num("cc", myChessColor);
                applyVoice(GameConfig.read(m, netDefaults));
                log("connected, pid=" + myId + (roomHost ? " (host)" : " (client)"));
            } else if (m.is(Net.STARTED)) {
                start = true;
                startMsg = m;
            } else if (m.is(Net.LOBBY)) {
                lobbyPhase = m.num("phase", -1);
            } else if (m.is(Net.ERR)) {
                String text = m.str("msg");
                toastError(text);
                if (pendingCreate != null) pendingCreate = null;
            } else if (m.is(Net.KICK)) {
                toastError(m.str("msg"));
                leaveToHome();
                return;
            }
        }

        updateRooms(hub, roomMsgs);

        if (pendingCreate != null && hub) {
            GameConfig cfg = pendingCreate;
            pendingCreate = null;
            createLobby(cfg);
        }

        if (lobby != null) lobby.onFrame(frame);
        if (game != null) game.onFrame(frame);
        if (browser != null) browser.onFrame(frame);

        if (start) {
            if (startMsg != null) applyVoice(GameConfig.read(startMsg, netDefaults));
            window.show(gameScreen());
        } else if (inLobby && window.current() != lobbyScreen()) {
            window.show(lobbyScreen());
        } else if (!inLobby && roomId < 0 && window.current() == gameScreen()) {
            window.show(browserScreen());
        } else if (lobbyPhase == 0 && window.current() == gameScreen()) {
            window.show(lobbyScreen());
        }
    }

    private void updateRooms(boolean hubFrame, List<Msg> roomMsgs) {
        if (hubFrame) hubRooms.clear();
        if (roomMsgs == null) {
            if (hubFrame) window.show(browserScreen());
            return;
        }
        for (Msg m : roomMsgs) {
            RoomInfo info = new RoomInfo();
            info.id = m.num("id", -1);
            info.kind = m.num("kind", Net.KIND_SNAKE);
            info.players = m.num("players", 0);
            info.max = m.num("max", 0);
            info.phase = m.num("phase", 0);
            info.name = m.str("name");
            RoomInfo found = null;
            for (RoomInfo r : hubRooms) {
                if (r.id == info.id) {
                    found = r;
                    break;
                }
            }
            if (found == null) hubRooms.add(info);
            else {
                found.kind = info.kind;
                found.players = info.players;
                found.max = info.max;
                found.phase = info.phase;
                found.name = info.name;
            }
        }
        if (browser != null) browser.refresh();
    }

    private void onConnectionLost(String reason) {
        if (client == null) return;
        linkLost = false;
        linkRestoredVisible = false;
        toastError("Connection lost: " + (reason == null || reason.isBlank() ? "server closed" : reason));
        SwingUtilities.invokeLater(this::leaveToHome);
    }

    public boolean connectionLost() {
        return linkLost && (client != null || previewLink);
    }

    public float connectionDim() {
        if (!linkLost || (client == null && !previewLink)) return 0f;
        long ms = (System.nanoTime() - linkLostNanos) / 1_000_000L;
        if (ms < 500) return 0f;
        float alpha = (float) Math.min(1.0, (ms - 500) / 350.0);
        return alpha * 0.72f;
    }

    public long connectionMillisLeft() {
        if (!linkLost) return 0;
        long elapsed = (System.nanoTime() - linkLostNanos) / 1_000_000L;
        return Math.max(0, linkWindowMs - elapsed);
    }

    public float connectionRestoredAlpha() {
        if (!linkRestoredVisible) return 0f;
        long ms = (System.nanoTime() - linkRestoredNanos) / 1_000_000L;
        if (ms < 0 || ms > 1400) return 0f;
        return 1f - ms / 1400f;
    }

    public void log(String text) {
        String t = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.add(t + "  " + text);
        if (log.size() > 300) log.remove(0);
    }

    public java.util.List<String> logLines() {
        return log;
    }

    public void toast(String text) {
        toast = text;
        toastError = false;
        toastUntil = System.currentTimeMillis() + 3000;
    }

    public void toastError(String text) {
        log("error: " + text);
        toast = text;
        toastError = true;
        toastUntil = System.currentTimeMillis() + 4200;
    }

    public String toastText() {
        return System.currentTimeMillis() < toastUntil ? toast : null;
    }

    public float toastAlpha() {
        long left = toastUntil - System.currentTimeMillis();
        if (left <= 0) return 0f;
        return Math.min(1f, left / 450f);
    }

    public boolean toastIsError() {
        return toastError;
    }
}
