package snakenet.ui;

import snakenet.Prefs;
import snakenet.audio.Sfx;
import snakenet.client.GameClient;
import snakenet.game.GameConfig;
import snakenet.net.Msg;
import netcore.net.NetInfo;
import netcore.voice.VoiceEngine;
import snakenet.net.Protocol;
import snakenet.server.GameServer;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;

public final class App {

    private final Prefs prefs;
    private final Backdrop backdrop = new Backdrop();
    private final GameConfig netDefaults = new GameConfig();
    private String voiceKey = "";

    private MainWindow window;
    private GameClient client;
    private VoiceEngine voice;
    private GameServer server;

    private int myId = -1;
    private String myName = "";
    private int myColor;
    private String gameAddress = "";
    private boolean hostGame;

    private final String sessionToken = java.util.UUID.randomUUID().toString();

    private HomeScreen home;
    private HostScreen hostScreen;
    private JoinScreen joinScreen;
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
        this.hostGame = host;
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

    public int myId() {
        return myId;
    }

    public String myName() {
        return myName;
    }

    public int myColor() {
        return myColor;
    }

    public String gameAddress() {
        return gameAddress;
    }

    public boolean isHost() {
        return hostGame;
    }

    public void setNameAndColor(String name, int color) {
        myName = GameConfig.sanitizeName(name);
        myColor = Math.floorMod(color, 10);
        prefs.set("name", myName);
        prefs.set("color", myColor);
    }

    public void hostGame(int port, GameConfig cfg, String name, int color) throws IOException {
        closeConnections();
        setNameAndColor(name, color);
        GameServer s = new GameServer(port, myName, cfg);
        s.start();
        server = s;
        hostGame = true;
        gameAddress = NetInfo.primaryIp() + ":" + s.port();
        try {
            connect("127.0.0.1", s.port());
        } catch (IOException e) {
            s.stop();
            server = null;
            hostGame = false;
            throw e;
        }
        window.show(lobbyScreen());
        toast("Server started on port " + s.port());
    }

    public void joinGame(String host, int port, String name, int color) throws IOException {
        closeConnections();
        setNameAndColor(name, color);
        hostGame = false;
        gameAddress = host + ":" + port;
        connect(host, port);
        window.show(lobbyScreen());
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
                        c.send(Msg.of(Protocol.HELLO).putStr("name", myName)
                                .put("color", myColor).putStr("token", sessionToken()));
                    }
                }
            }
        }, netDefaults.netConfig(false));
        client = holder[0];
        myId = -1;
        if (voice != null) voice.stop();
        voice = null;
        voiceKey = "";
        applyVoice(netDefaults);
        client.send(Msg.of(Protocol.HELLO).putStr("name", myName).put("color", myColor)
                .putStr("token", sessionToken()));
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
        hostGame = false;
        myId = -1;
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
            for (int i = 0; i < 3; i++) {
                send(Msg.of(Protocol.BOT).put("op", "add"));
            }
            if (autoStart) {
                javax.swing.Timer timer = new javax.swing.Timer(700, e -> send(Msg.of(Protocol.START)));
                timer.setRepeats(false);
                timer.start();
            }
        } catch (IOException e) {
            toastError("Demo failed to start: " + e.getMessage());
        }
    }

    private void handleFrame(List<Msg> frame) {
        boolean start = false;
        Msg startMsg = null;
        int lobbyPhase = -1;
        for (Msg m : frame) {
            switch (m.type()) {
                case Protocol.WELCOME:
                    myId = m.num("pid", -1);
                    log("connected, pid=" + myId + (hostGame ? " (host)" : " (client)"));
                    break;
                case Protocol.START:
                    start = true;
                    startMsg = m;
                    break;
                case Protocol.LOBBY:
                    lobbyPhase = m.num("phase", -1);
                    break;
                case Protocol.ERR:
                    toastError(m.str("msg"));
                    break;
                case Protocol.KICK:
                    toastError(m.str("msg"));
                    leaveToHome();
                    return;
                default:
                    break;
            }
        }
        if (lobby != null) lobby.onFrame(frame);
        if (game != null) game.onFrame(frame);

        if (start) {
            if (startMsg != null) applyVoice(GameConfig.read(startMsg, netDefaults));
            window.show(gameScreen());
        } else if (lobbyPhase == 0 && window.current() == gameScreen()) {
            window.show(lobbyScreen());
        }
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
