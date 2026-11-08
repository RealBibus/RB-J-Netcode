package snakenet.ui;

import snakenet.Prefs;
import snakenet.audio.Sfx;
import snakenet.client.GameClient;
import snakenet.game.GameConfig;
import snakenet.net.Msg;
import netcore.net.NetInfo;
import snakenet.net.Protocol;
import snakenet.server.GameServer;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;

/** Состояние приложения: соединение, сервер, навигация между экранами. */
public final class App {

    private final Prefs prefs;
    private final Backdrop backdrop = new Backdrop();

    private MainWindow window;
    private GameClient client;
    private GameServer server;

    private int myId = -1;
    private String myName = "";
    private int myColor;
    private String gameAddress = "";
    private boolean hostGame;

    private HomeScreen home;
    private HostScreen hostScreen;
    private JoinScreen joinScreen;
    private LobbyScreen lobby;
    private GameScreen game;

    private String toast;
    private long toastUntil;
    private boolean toastError;

    public App(Prefs prefs) {
        this.prefs = prefs;
        this.myName = prefs.get("name", "Игрок");
        this.myColor = prefs.getInt("color", 0);
        Sfx.setEnabled(prefs.getBool("sound", true));
    }

    public void start() {
        prepareOffscreenWindow();
        window.setVisible(true);
        window.show(homeScreen());
    }

    /** Создать окно, но не показывать — нужно для офскрин-рендера скриншотов. */
    public void prepareOffscreenWindow() {
        if (window == null) window = new MainWindow(this);
    }

    /** Только для превью: включить вид «хост». */
    public void setPreviewHost(boolean host) {
        this.hostGame = host;
    }

    /** Только для превью: подставить свой id. */
    public void setPreviewId(int id) {
        this.myId = id;
    }

    // ------------------------------------------------------------------
    // доступ к экранам
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // соединение
    // ------------------------------------------------------------------

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

    /** Создать сервер и сразу подключиться к нему самому. */
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
        toast("Сервер запущен на порту " + s.port());
    }

    /** Подключиться к чужой игре. */
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
            public void onClosed(String reason) {
                if (holder[0] != null && holder[0] == client) {
                    onConnectionLost(reason);
                }
            }
        });
        client = holder[0];
        myId = -1;
        client.send(Msg.of(Protocol.HELLO).putStr("name", myName).put("color", myColor));
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
        GameClient c = client;
        client = null;
        if (c != null) c.close();
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

    /** Демо без друзей: сервер + три бота, чтобы посмотреть игру сразу. */
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
            toastError("Демо не запустилось: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // входящие кадры
    // ------------------------------------------------------------------

    private void handleFrame(List<Msg> frame) {
        boolean start = false;
        int lobbyPhase = -1;
        for (Msg m : frame) {
            switch (m.type()) {
                case Protocol.WELCOME:
                    myId = m.num("pid", -1);
                    break;
                case Protocol.START:
                    start = true;
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
            window.show(gameScreen());
        } else if (lobbyPhase == 0 && window.current() == gameScreen()) {
            window.show(lobbyScreen());
        }
    }

    private void onConnectionLost(String reason) {
        if (client == null) return;
        toastError("Связь потеряна: " + (reason == null || reason.isBlank() ? "сервер закрылся" : reason));
        SwingUtilities.invokeLater(this::leaveToHome);
    }

    // ------------------------------------------------------------------
    // всплывающие сообщения
    // ------------------------------------------------------------------

    public void toast(String text) {
        toast = text;
        toastError = false;
        toastUntil = System.currentTimeMillis() + 3000;
    }

    public void toastError(String text) {
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
