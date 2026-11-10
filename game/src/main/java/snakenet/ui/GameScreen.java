package snakenet.ui;

import netcore.predict.Correction;
import netcore.predict.InputCommand;
import netcore.predict.Predictor;
import netcore.sync.ClockSync;
import snakenet.audio.Sfx;
import snakenet.client.ServerClock;
import snakenet.client.SnakePredictState;
import snakenet.client.SnakeRenderMath;
import snakenet.client.Snapshot;
import snakenet.game.Chess;
import snakenet.game.GameConfig;
import snakenet.game.SnakeState;
import netcore.netcode.Msg;
import snakenet.net.Net;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


public final class GameScreen extends Screen {

    
    
    private static final double AREA_X = 4;
    private static final double AREA_Y = 44;
    private static final double AREA_W = 1272;
    private static final double AREA_H = 708;

    private static final int TAB_TABLE = 0;
    private static final int TAB_NET = 1;
    private static final int TAB_CONSOLE = 2;
    private static final int TAB_CHAT = 3;

    private static final double PANEL_X = 250;
    private static final double PANEL_Y = 92;
    private static final double PANEL_W = 780;
    private static final double PANEL_H = 620;
    private static final double PANEL_INNER_X = PANEL_X + 24;
    private static final double PANEL_INNER_Y = PANEL_Y + 62;
    private static final double PANEL_INNER_W = PANEL_W - 48;

    private static final class PlayerInfo {
        String name = "";
        int color;
        boolean bot;
        int ping = -1;
    }

    private static final class Result {
        int id;
        String name = "";
        int score;
        int place;
        int color;
        boolean alive;
    }

    private static final class Particle {
        double x;
        double y;
        double vx;
        double vy;
        double life;
        double max;
        double size;
        Color color;
    }

    private static final class FloatText {
        double x;
        double y;
        double life;
        double max;
        String text;
        Color color;
    }

    private final Map<Integer, PlayerInfo> names = new HashMap<>();
    private final java.util.List<String[]> chatLines = new java.util.ArrayList<>();
    private final Map<Integer, Integer> sentTicks = new HashMap<>();
    private final Graph gRtt = new Graph("RTT", "ms", 120);
    private final Graph gJitter = new Graph("JITTER", "ms", 120);
    private final Graph gTrafficIn = new Graph("TRAFFIC IN", "KB/s", 120);
    private final Graph gTrafficOut = new Graph("TRAFFIC OUT", "KB/s", 120);
    private final Graph gSnapRate = new Graph("SNAPSHOTS", "/s", 120);
    private final Graph gRecon = new Graph("CORRECTIONS", "/s", 120);
    private InputField chatField;
    private boolean chatOpen;
    private boolean panelOpen;
    private int tab = TAB_TABLE;
    private int lastTab = TAB_TABLE;
    private double telemetryTimer;
    private double chatScroll;
    private int snapCount;
    private int rttPrev = -1;
    private double jitter;
    private long telemetryBytesIn;
    private long telemetryBytesOut;
    private int telemetrySnapCount;
    private int telemetryReconTotal;
    private final java.util.ArrayDeque<Snapshot> snapHistory = new java.util.ArrayDeque<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<FloatText> floats = new ArrayList<>();
    private List<Result> results;

    private GameConfig cfg = new GameConfig();
    private int cols = 32;
    private int rows = 22;
    private int phase = Net.PHASE_LOBBY;
    private int countdown;
    private double countdownAnim;
    private double goFlash;
    private double shake;
    private double deadBanner;
    private boolean pauseMenu;
    private double pauseAnim;
    private double runTime;
    private long lastPingAt;
    private Snapshot prev;
    private Snapshot cur;

    private Chess chess = new Chess();
    private int chessTurn = Chess.WHITE;
    private int chessMyColor = Chess.WHITE;
    private int chessWinner = -1;
    private String chessEndReason = "";
    private String lastSyncFen = "";
    private int chessSelected = -1;

    
    private final ServerClock serverClock = new ServerClock();
    private Predictor<SnakePredictState> predictor;
    private SnakePredictState predicted;
    private double correctionFlash;
    private int corrections;
    private int snaps;

    private volatile int renderTick;
    private volatile double renderSub;

    
    private double[] localRender;
    private double[] localDelta;
    private double localBlend = 1;

    private double snapshotIntervalMs = 50;
    private long lastSnapRecv;

    
    private long renderServerNanos;
    private boolean renderClockInit;
    private double interpDelayMs = 66;
    private double snapshotGapMs = 33;
    private long lastSnapSt;
    private double lastReconPeak = 4;

    private boolean subDebug;
    private int subSent;
    private int[] subSentBuckets = new int[16];
    private float subLastSent = -1f;
    private long lastSubLogNanos;

    private double cell = 28;
    private double boardX = 56;
    private double boardY = 112;
    private double boardW = 896;
    private double boardH = 616;

    private final NeonButton bAgain;
    private final NeonButton bLobby;
    private final NeonButton bResume;
    private final NeonButton bQuit;
    private final NeonButton bTabTable;
    private final NeonButton bTabNet;
    private final NeonButton bTabConsole;
    private final NeonButton bTabChat;

    public GameScreen(App app) {
        super(app);

        bAgain = add(new NeonButton("PLAY AGAIN", NeonButton.Style.PRIMARY, 372, 556, 240, 54, () -> app.send(Net.AGAIN.newMsg())));
        bLobby = add(new NeonButton("LOBBY", NeonButton.Style.SECONDARY, 636, 556, 200, 54, app::leaveLobby));
        bResume = add(new NeonButton("Resume", NeonButton.Style.PRIMARY, 500, 392, 280, 52, () -> pauseMenu = false));
        bQuit = add(new NeonButton("Quit to menu", NeonButton.Style.DANGER, 500, 456, 280, 52, app::leaveToHome));
        bAgain.visible = false;
        bLobby.visible = false;
        bResume.visible = false;
        bQuit.visible = false;

        bTabTable = add(new NeonButton("TABLE", NeonButton.Style.CHIP, 424, 758, 112, 30,
                () -> toggleTab(TAB_TABLE)));
        bTabNet = add(new NeonButton("NET", NeonButton.Style.CHIP, 544, 758, 84, 30,
                () -> toggleTab(TAB_NET)));
        bTabConsole = add(new NeonButton("CONSOLE", NeonButton.Style.CHIP, 636, 758, 112, 30,
                () -> toggleTab(TAB_CONSOLE)));
        bTabChat = add(new NeonButton("CHAT", NeonButton.Style.CHIP, 756, 758, 100, 30,
                () -> toggleTab(TAB_CHAT)));

        bind("UP", 0);
        bind("W", 0);
        bind("RIGHT", 1);
        bind("D", 1);
        bind("DOWN", 2);
        bind("S", 2);
        bind("LEFT", 3);
        bind("A", 3);

        chatField = new InputField("message…");
        chatField.setVisible(false);
        addField(chatField, (int) PANEL_INNER_X, (int) (PANEL_Y + PANEL_H - 58),
                (int) PANEL_INNER_W, 40, 14);
        chatField.addActionListener(e -> sendChat());

        setFocusTraversalKeysEnabled(false);
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ENTER"), "chatopen");
        getActionMap().put("chatopen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (pauseMenu || phase == Net.PHASE_OVER) return;
                openChat();
            }
        });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("TAB"), "paneltoggle");
        getActionMap().put("paneltoggle", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (pauseMenu) return;
                if (panelOpen) closePanel();
                else openTab(lastTab);
            }
        });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "escape");
        getActionMap().put("escape", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (panelOpen || chatOpen) {
                    closePanel();
                } else {
                    pauseMenu = !pauseMenu;
                }
            }
        });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F3"), "subdebug");
        getActionMap().put("subdebug", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleSubDebug();
            }
        });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("pressed K"), "ptt_on");
        getActionMap().put("ptt_on", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (chatOpen || app.voice() == null) return;
                app.voice().setTalking(true);
            }
        });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("released K"), "ptt_off");
        getActionMap().put("ptt_off", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (app.voice() != null) app.voice().setTalking(false);
            }
        });
    }

    private void toggleTab(int which) {
        if (pauseMenu) return;
        if (panelOpen && tab == which) {
            closePanel();
        } else {
            openTab(which);
        }
    }

    private void toggleSubDebug() {
        subDebug = !subDebug;
        if (subDebug) {
            int steps = Math.max(1, cfg.subSteps);
            app.log("subtick debug on: " + (cfg.subtick ? "on" : "off")
                    + ", tick rate " + cfg.tickRate + " Hz"
                    + ", input slots " + (cfg.subtick ? cfg.tickRate * steps : cfg.tickRate) + "/s");
            app.toast("Subtick debug ON · F3 to hide");
        } else {
            app.log("subtick debug off");
        }
    }

    public void setPreviewSubtick(int sent, float lastSub) {
        subSent = Math.max(0, sent);
        subLastSent = lastSub;
    }

    private void openTab(int which) {
        panelOpen = true;
        lastTab = which;
        tab = which;
        if (which == TAB_CHAT) {
            chatOpen = true;
            chatField.setVisible(true);
            chatField.requestFocusInWindow();
        } else {
            chatOpen = false;
            chatField.setVisible(false);
            requestFocusInWindow();
        }
    }

    private void openChat() {
        openTab(TAB_CHAT);
    }

    private void closePanel() {
        panelOpen = false;
        chatOpen = false;
        chatField.setVisible(false);
        chatField.setText("");
        requestFocusInWindow();
    }

    private void bind(String key, int dir) {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), "dir" + dir);
        getActionMap().put("dir" + dir, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (pauseMenu || chatOpen) return;
                if (phase != Net.PHASE_RUNNING) return;
                handleInput(dir);
            }
        });
    }

    @Override
    public void mousePressed(java.awt.event.MouseEvent e) {
        if (phase == Net.PHASE_RUNNING && cfg.chess) {
            double vx = (e.getX() - ox) / Math.max(0.001, scale);
            double vy = (e.getY() - oy) / Math.max(0.001, scale);
            double cs = Math.min(boardW, boardH) / 8.0;
            int bx = (int) Math.floor((vx - (boardX + (boardW - cs * 8) / 2)) / cs);
            int by = (int) Math.floor((vy - (boardY + (boardH - cs * 8) / 2)) / cs);
            if (bx >= 0 && bx < Chess.SIZE && by >= 0 && by < Chess.SIZE) {
                boolean flip = chessMyColor == Chess.BLACK;
                int file = flip ? Chess.SIZE - 1 - bx : bx;
                int rank = flip ? by : Chess.SIZE - 1 - by;
                int sq = rank * Chess.SIZE + file;
                int pc = chess.at(sq);
                if (pc != 0 && ((pc >= 7) == (chessTurn == Chess.BLACK))) {
                    if (chessSelected < 0) {
                        chessSelected = sq;
                    } else if (sq == chessSelected) {
                        chessSelected = -1;
                    } else {
                        sendChessMove(chessSelected, sq, 0);
                        chessSelected = -1;
                    }
                }
            }
        }
        super.mousePressed(e);
    }

    @Override
    public void onShow() {
        requestFocusInWindow();
        pauseMenu = false;
        closePanel();
    }

    @Override
    public void onHide() {
        pauseMenu = false;
        closePanel();
    }

    
    
    

    @Override
    public void onFrame(List<Msg> frame) {
        for (Msg m : frame) {
            if (m.is(Net.LOBBY)) {
                readRoster(frame);
            } else if (m.is(Net.STARTED)) {
                applyStart(m);
            } else if (m.is(Net.COUNT)) {
                countdown = m.num("n", countdown);
                countdownAnim = 1;
                Sfx.play(Sfx.Sound.COUNT);
            } else if (m.is(Net.SNAP)) {
                applySnapshot(frame);
            } else if (m.is(Net.CHESS_MOVE_OUT)) {
                int from = m.num("f", -1);
                int to = m.num("t", -1);
                int promo = m.num("pr", 0);
                if (!m.raw("fen").isEmpty()) {
                    chess = Chess.fromFen(m.str("fen"));
                    chessTurn = chess.turn();
                } else {
                    Chess.Move move = null;
                    for (Chess.Move candidate : chess.legalMoves()) {
                        if (candidate.from == from && candidate.to == to && candidate.promotion == promo) {
                            move = candidate;
                            break;
                        }
                    }
                    if (move != null) {
                        chess = chess.apply(move);
                        chessTurn = chess.turn();
                    }
                }
            } else if (m.is(Net.CHESS_END)) {
                phase = Net.PHASE_OVER;
                chessWinner = m.num("w", -1);
                chessEndReason = m.raw("r");
                chessSelected = -1;
            } else if (m.is(Net.OVER)) {
                results = new ArrayList<>();
                phase = Net.PHASE_OVER;
                countdown = 0;
                closePanel();
                app.log("round over");
            } else if (m.is(Net.RESULT)) {
                addResult(m);
            } else if (m.is(Net.ACK)) {
                int n = m.num("n", -1);
                int at = m.num("t", -1);
                Integer sent = sentTicks.remove(n);
                if (sent != null) serverClock.observeApply(sent, at);
                if (predictor != null) predictor.retime(n, at);
            } else if (m.is(Net.CHAT_OUT)) {
                String text = m.str("text");
                if (!text.isEmpty()) {
                    boolean system = m.num("kind", 0) == 1;
                    chatLines.add(new String[]{m.str("from"), text, system ? "1" : "0", String.valueOf(m.num("c", 0))});
                    if (chatLines.size() > 80) chatLines.remove(0);
                    app.log(system ? text : m.str("from") + ": " + text);
                    if (system) {
                        String lower = text.toLowerCase(java.util.Locale.ROOT);
                        if (lower.contains("joined") || lower.contains("reconnected")) Sfx.play(Sfx.Sound.JOIN);
                    }
                }
            } else if (m.is(Net.KICK)) {
                app.toastError(m.str("msg"));
                app.log("KICK: " + m.str("msg"));
            }
        }
        if (app.gameKind() == Net.KIND_CHESS) {
            String fen = app.roomView().fen.get();
            if (fen != null && !fen.isBlank() && !fen.equals(lastSyncFen)) {
                lastSyncFen = fen;
                chess = Chess.fromFen(fen);
            }
            chessTurn = app.roomView().turn.get();
            if (phase == Net.PHASE_COUNT && app.roomView().phase.get() == Net.PHASE_RUNNING) {
                phase = Net.PHASE_RUNNING;
                countdown = 0;
            }
        }
        boolean chessOver = app.gameKind() == Net.KIND_CHESS && phase == Net.PHASE_OVER;
        if ((results != null || chessOver) && phase == Net.PHASE_OVER) {
            boolean host = app.isHost();
            bAgain.visible = true;
            bLobby.visible = true;
            bAgain.enabled = host;
            bAgain.label = host ? "PLAY AGAIN" : "WAITING FOR HOST";
        } else {
            bAgain.visible = false;
            bLobby.visible = false;
        }
        bResume.visible = pauseMenu;
        bQuit.visible = pauseMenu;
        boolean tabs = phase != Net.PHASE_OVER && !pauseMenu;
        bTabTable.visible = tabs;
        bTabNet.visible = tabs;
        bTabConsole.visible = tabs;
        bTabChat.visible = tabs;
    }

    private void readRoster(List<Msg> frame) {
        Map<Integer, PlayerInfo> map = new HashMap<>();
        for (Msg m : frame) {
            if (!m.is(Net.PLAYER)) continue;
            PlayerInfo info = new PlayerInfo();
            info.name = m.str("name");
            info.color = m.num("c", 0);
            info.bot = m.bool("bot");
            info.ping = m.num("pg", -1);
            map.put(m.num("id", -1), info);
        }
        if (!map.isEmpty()) {
            names.clear();
            names.putAll(map);
        }
    }

    private void applyStart(Msg m) {
        cfg = GameConfig.read(m, cfg);
        cols = cfg.cols;
        rows = cfg.rows;
        phase = Net.PHASE_COUNT;
        countdown = 3;
        countdownAnim = 1;
        prev = null;
        cur = null;
        snapHistory.clear();
        sentTicks.clear();
        particles.clear();
        floats.clear();
        results = null;
        runTime = 0;
        shake = 0;
        pauseMenu = false;
        predictor = new Predictor<>(new SnakePredictState.Model(cols, rows, cfg.unitsPerTick(), cfg.walls),
                cfg.predictionConfig());
        predicted = null;
        localRender = null;
        localDelta = null;
        localBlend = 1;
        corrections = 0;
        snaps = 0;
        correctionFlash = 0;
        snapshotIntervalMs = 1000.0 / Math.min(cfg.tickRate, 30);
        lastSnapRecv = 0;
        renderClockInit = false;
        interpDelayMs = cfg.interpolationDelayMs;
        snapshotGapMs = 1000.0 / Math.min(cfg.tickRate, 30);
        lastSnapSt = 0;
        serverClock.onSnapshot(0, 0, cfg.tickRate, System.nanoTime());
        bAgain.visible = false;
        bLobby.visible = false;
        bResume.visible = false;
        bQuit.visible = false;
        closePanel();
        snapCount = 0;
        lastSyncFen = "";
        if (cfg.chess) {
            chess = new Chess();
            chessTurn = Chess.WHITE;
            chessMyColor = app.myChessColor();
            chessWinner = -1;
            chessEndReason = "";
            chessSelected = -1;
        }
        telemetrySnapCount = 0;
        telemetryReconTotal = 0;
        telemetryBytesIn = 0;
        telemetryBytesOut = 0;
        rttPrev = -1;
        jitter = 0;
        subSent = 0;
        subLastSent = -1f;
        java.util.Arrays.fill(subSentBuckets, 0);
        lastSubLogNanos = 0;
        if (cfg.chess) {
            app.log("chess round started: " + app.gameKindName());
        } else {
            app.log("round started: " + cols + "×" + rows + ", " + cfg.cellsPerSec + " cells/s, "
                    + cfg.tickRate + " Hz" + (cfg.subtick ? ", subtick" : ""));
        }
        Sfx.play(Sfx.Sound.START);
    }

    private void applySnapshot(List<Msg> frame) {
        Snapshot snap = Snapshot.parse(frame, System.nanoTime());
        if (snap == null) return;
        
        if (cur != null && (snap.tick <= cur.tick
                || (snap.serverNanos > 0 && snap.serverNanos <= cur.serverNanos))) return;
        boolean first = cur == null;
        long now = System.nanoTime();
        if (lastSnapRecv != 0) {
            double dt = (now - lastSnapRecv) / 1_000_000.0;
            if (dt > 5 && dt < 400) snapshotIntervalMs = snapshotIntervalMs * 0.8 + dt * 0.2;
        }
        lastSnapRecv = now;
        if (lastSnapSt != 0) {
            double gap = (snap.serverNanos - lastSnapSt) / 1_000_000.0;
            if (gap > 3 && gap < 250) snapshotGapMs = snapshotGapMs * 0.85 + gap * 0.15;
            double tickMs = 1000.0 / Math.max(1, cfg.tickRate);
            interpDelayMs = Math.max(cfg.interpolationDelayMs,
                    Math.min(tickMs * 6.0, snapshotGapMs * 1.6 + tickMs * 0.4));
        }
        lastSnapSt = snap.serverNanos;
        prev = cur;
        cur = snap;
        snapCount++;
        snapHistory.addLast(snap);
        while (snapHistory.size() > 12) snapHistory.removeFirst();
        serverClock.onSnapshot(snap.tick, snap.serverNanos, snap.tickRate, snap.recvNanos);

        if (subDebug && now - lastSubLogNanos > 1_000_000_000L) {
            lastSubLogNanos = now;
            app.log("sub: " + (snap.subtick ? "timestamps" : "off")
                    + " in=" + snap.subIn
                    + " applied=" + snap.subApplied
                    + " merged=" + snap.subMerged
                    + " same-tick=" + snap.subSameTick
                    + " order=" + (snap.subOrderErrors == 0 ? "ok" : "ERR" + snap.subOrderErrors)
                    + " last=" + (snap.subIn == 0 ? "—" : String.format(java.util.Locale.ROOT, "%.3f", snap.subLastPermille / 1000.0))
                    + " buckets=" + bucketText(snap.subBuckets)
                    + " sent=" + subSent);
        }

        reconcile(snap);

        if (phase != Net.PHASE_RUNNING) {
            phase = Net.PHASE_RUNNING;
            countdown = 0;
            if (first || goFlash <= 0) goFlash = 1;
        }
        for (Snapshot.EventView e : snap.events) handleEvent(e);
    }

    
    private void reconcile(Snapshot snap) {
        if (predictor == null) return;
        Snapshot.SnakeView mine = snap.snake(app.myId());
        if (mine == null) return;
        SnakePredictState auth = SnakePredictState.of(mine);
        if (!predictor.ready() || predictor.authoritativeTick() > snap.tick) {
            predictor.reset(auth, snap.tick, mine.ackSeq);
            predicted = predictor.predicted();
            return;
        }
        Correction correction = predictor.onServerState(snap.tick, mine.ackSeq, auth);
        if (correction.snapped()) {
            snaps++;
            correctionFlash = 1;
            shake = Math.max(shake, 0.45);
            Sfx.play(Sfx.Sound.LEAVE);
        } else if (correction.minor()) {
            corrections++;
        }
        predicted = predictor.predicted();
    }

    
    private void sendChessMove(int from, int to, int promotion) {
        app.send(Msg.of(Net.CHESS_MOVE).put("p", app.myId()).put("f", from).put("t", to).put("pr", promotion));
    }

    private void handleInput(int dir) {
        if (chatOpen || predictor == null || app.client() == null) return;
        if (cfg.chess) return;
        ClockSync clock = app.client().clock();
        int tick = serverClock.tickAt(clock);
        float sub = (float) serverClock.subAt(clock);
        sub = Math.max(0f, Math.min(0.999f, sub));
        InputCommand command = predictor.push(dir, tick, sub);
        if (command == null) return;
        long stamp = serverClock.timeOfTickNanos(command.tick, command.sub, clock);
        predicted = predictor.predicted();
        sentTicks.put(command.seq, command.tick);
        subSent++;
        subLastSent = command.sub;
        int bucket = (int) (command.sub * Math.max(1, cfg.subSteps));
        bucket = Math.max(0, Math.min(Math.max(1, cfg.subSteps) - 1, bucket));
        subSentBuckets[bucket]++;
        if (subDebug) {
            String name = dir >= 0 && dir < SnakeState.DIR_NAMES.length ? SnakeState.DIR_NAMES[dir] : String.valueOf(dir);
            app.log("IN #" + command.seq + " t=" + command.tick
                    + " sub=" + String.format(java.util.Locale.ROOT, "%.3f", command.sub)
                    + " dir=" + name);
        }
        app.send(Msg.of(Net.IN)
                .put("d", dir)
                .put("t", command.tick)
                .put("s", String.format(java.util.Locale.ROOT, "%.3f", command.sub))
                .put("n", command.seq)
                .put("ts", stamp));
    }

    private void handleEvent(Snapshot.EventView e) {
        PlayerInfo info = names.get(e.player);
        int colorIdx = info == null ? 0 : info.color;
        Color c = Theme.player(colorIdx);
        boolean mine = e.player == app.myId();
        if (e.kind == Net.EV_EAT) {
            burst(e.x, e.y, c, 10, 2.2);
            if (mine) {
                Sfx.play(Sfx.Sound.EAT);
                FloatText ft = new FloatText();
                ft.x = e.x + 0.5;
                ft.y = e.y;
                ft.text = "+10";
                ft.color = Theme.ACCENT;
                ft.max = 1.1;
                ft.life = 1.1;
                floats.add(ft);
            }
        } else {
            burst(e.x, e.y, c, 34, 4.2);
            if (mine) {
                Sfx.play(Sfx.Sound.DIE);
                shake = 1;
                deadBanner = 1;
            }
        }
    }

    private void addResult(Msg m) {
        if (results == null) results = new ArrayList<>();
        Result r = new Result();
        r.id = m.num("p", -1);
        r.name = m.str("name");
        r.score = m.num("sc", 0);
        r.place = m.num("place", 0);
        r.color = m.num("c", 0);
        r.alive = m.num("a", 0) == 1;
        results.add(r);
        if (r.id == app.myId() && r.place == 1) Sfx.play(Sfx.Sound.WIN);
    }

    private void burst(double cx, double cy, Color color, int count, double speed) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            double ang = Math.random() * Math.PI * 2;
            double v = speed * (0.35 + Math.random() * 0.9);
            p.x = cx + 0.5 + (Math.random() - 0.5) * 0.5;
            p.y = cy + 0.5 + (Math.random() - 0.5) * 0.5;
            p.vx = Math.cos(ang) * v;
            p.vy = Math.sin(ang) * v;
            p.max = 0.5 + Math.random() * 0.6;
            p.life = p.max;
            p.size = 0.10 + Math.random() * 0.16;
            p.color = color;
            particles.add(p);
        }
    }

    
    
    

    @Override
    protected void onTick(double dt) {
        countdownAnim = Math.max(0, countdownAnim - dt * 1.6);
        goFlash = Math.max(0, goFlash - dt * 1.4);
        shake = Math.max(0, shake - dt * 2.4);
        pauseAnim += ((pauseMenu ? 1 : 0) - pauseAnim) * Math.min(1, dt * 14);
        if (phase == Net.PHASE_RUNNING) runTime += dt;

        boolean dead = cur != null && cur.snake(app.myId()) != null && !cur.snake(app.myId()).alive;
        deadBanner += ((dead ? 1 : 0) - deadBanner) * Math.min(1, dt * 5);

        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) {
                it.remove();
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 0.965;
            p.vy *= 0.965;
        }
        for (Iterator<FloatText> it = floats.iterator(); it.hasNext(); ) {
            FloatText f = it.next();
            f.life -= dt;
            if (f.life <= 0) {
                it.remove();
                continue;
            }
            f.y -= dt * 1.2;
        }

        
        if (cur != null) {
            long nowNanos = serverNanosNow();
            long target = nowNanos - (long) (interpDelayMs * 1_000_000.0);
            if (!renderClockInit) {
                renderServerNanos = cur != null && cur.serverNanos != 0 ? cur.serverNanos : target;
                renderClockInit = true;
            } else {
                renderServerNanos += (long) (Math.min(0.1, dt) * 1_000_000_000.0);
                long diff = target - renderServerNanos;
                if (Math.abs(diff) > 500_000_000L) {
                    renderServerNanos = target;
                } else {
                    renderServerNanos += (long) (diff * Math.min(1, dt * 4));
                }
            }
        }

        correctionFlash = Math.max(0, correctionFlash - dt * 2.2);

        telemetryTimer += dt;
        if (telemetryTimer >= 0.25) {
            double span = telemetryTimer;
            telemetryTimer = 0;
            int rtt = app.client() == null ? -1 : Math.max(0, app.client().rttMs());
            gRtt.push(rtt);
            if (rttPrev >= 0 && rtt >= 0) jitter = jitter * 0.7 + Math.abs(rtt - rttPrev) * 0.3;
            rttPrev = rtt;
            gJitter.push(jitter);

            int total = corrections + snaps;
            int reconDelta = Math.max(0, total - telemetryReconTotal);
            int snapDelta = Math.max(0, snapCount - telemetrySnapCount);
            telemetryReconTotal = total;
            telemetrySnapCount = snapCount;
            gRecon.push(reconDelta / span);
            gSnapRate.push(snapDelta / span);
            lastReconPeak = Math.max(reconDelta, lastReconPeak * 0.9);

            if (app.client() != null) {
                long in = app.client().stats().bytesIn();
                long out = app.client().stats().bytesOut();
                if (telemetryBytesIn > 0) {
                    gTrafficIn.push((in - telemetryBytesIn) / 1024.0 / span);
                    gTrafficOut.push((out - telemetryBytesOut) / 1024.0 / span);
                }
                telemetryBytesIn = in;
                telemetryBytesOut = out;
            }
        }

        boolean tabs = phase != Net.PHASE_OVER && !pauseMenu;
        bTabTable.visible = tabs;
        bTabNet.visible = tabs;
        bTabConsole.visible = tabs;
        bTabChat.visible = tabs;
        bTabTable.selected = panelOpen && tab == TAB_TABLE;
        bTabNet.selected = panelOpen && tab == TAB_NET;
        bTabConsole.selected = panelOpen && tab == TAB_CONSOLE;
        bTabChat.selected = panelOpen && tab == TAB_CHAT;

        if (predictor != null && predictor.ready() && app.client() != null
                && phase == Net.PHASE_RUNNING) {
            ClockSync clock = app.client().clock();
            double pos = serverClock.tickPosition(clock);
            renderTick = (int) Math.floor(pos);
            renderSub = pos - renderTick;
            predictor.advanceTo(renderTick);
            predicted = predictor.predicted();
            updateLocalRender(dt);
        }
    }

    private void computeBoard() {
        
        
        cell = Math.max(4, Math.min(AREA_W / cols, AREA_H / rows));
        boardW = cell * cols;
        boardH = cell * rows;
        boardX = AREA_X + (AREA_W - boardW) / 2.0;
        boardY = AREA_Y + (AREA_H - boardH) / 2.0;
    }

    
    
    

    @Override
    protected void paintScreen(Graphics2D g) {
        computeBoard();
        paintTopBar(g);
        Graphics2D board = (Graphics2D) g.create();
        if (shake > 0.01) {
            board.translate((Math.random() - 0.5) * shake * 16, (Math.random() - 0.5) * shake * 16);
        }
        paintBoard(board);
        board.dispose();
        paintChatHud(g);
        if (subDebug && !panelOpen && !pauseMenu) paintSubtickDebug(g);
        paintOverlays(g);
        if (panelOpen) paintPanel(g);
    }

    private void sendChat() {
        String text = chatField.getText().trim();
        if (!text.isEmpty()) app.send(Msg.of(Net.CHAT).putStr("text", text));
        chatField.setText("");
        closePanel();
    }

    
    private void paintChatHud(Graphics2D g) {
        if (!panelOpen && !pauseMenu && phase != Net.PHASE_OVER) {
            if (!chatLines.isEmpty()) {
                int from = Math.max(0, chatLines.size() - 3);
                int count = chatLines.size() - from;
                double lineH = 17;
                double bottom = VH - 44;
                double top = bottom - count * lineH;
                double width = 470;
                g.setColor(new Color(0, 0, 0, 145));
                g.fill(new Rectangle2D.Double(12, top - 10, width + 16, count * lineH + 8));
                g.setColor(Theme.LINE);
                g.draw(new Rectangle2D.Double(12.5, top - 9.5, width + 15, count * lineH + 7));
                double y = top + 5;
                for (int i = from; i < chatLines.size(); i++) {
                    String[] line = chatLines.get(i);
                    boolean system = "1".equals(line[2]);
                    String text = system ? line[1] : line[0] + ": " + line[1];
                    Color color = system ? Theme.MUTED : Theme.player(Integer.parseInt(line[3]));
                    Brush.text(g, Brush.ellipsize(g, text, width), 20, y, Theme.font(11), color);
                    y += lineH;
                }
            }
            Brush.text(g, "ENTER — CHAT    TAB — PANEL    K — VOICE    F3 — SUBTICK", 16, VH - 10,
                    Theme.font(9), Theme.MUTED);
        }
    }

    private void paintSubtickDebug(Graphics2D g) {
        double x = 12;
        double y = 48;
        double w = 356;
        double h = 78;
        Brush.panel(g, x, y, w, h, 10);
        g.setColor(Theme.alpha(Theme.ACCENT2, 140));
        g.fill(new Rectangle2D.Double(x, y + 8, 2, h - 16));

        int steps = Math.max(1, cfg.subSteps);
        double pos = renderSub;
        int srvIn = cur == null ? 0 : cur.subIn;
        int srvApplied = cur == null ? 0 : cur.subApplied;
        int srvMerged = cur == null ? 0 : cur.subMerged;
        int srvSame = cur == null ? 0 : cur.subSameTick;
        int srvOrder = cur == null ? 0 : cur.subOrderErrors;
        int lastPermille = cur == null || cur.subIn == 0 ? -1 : cur.subLastPermille;

        Brush.text(g, "SUBTICK " + (cfg.subtick ? "ON" : "OFF") + "   ·   F3", x + 12, y + 19,
                Theme.semibold(11), Theme.ACCENT2);
        Brush.text(g, "tick " + (cur == null ? "—" : cur.tick) + "."
                        + String.format(java.util.Locale.ROOT, "%.2f", pos)
                        + "   sent " + subSent
                        + "   last " + (subLastSent < 0 ? "—"
                        : String.format(java.util.Locale.ROOT, "%.3f", subLastSent)),
                x + 12, y + 37, Theme.font(10), Theme.DIM);
        Brush.text(g, "server in " + srvIn + "   applied " + srvApplied + "   merged " + srvMerged,
                x + 12, y + 55, Theme.font(10), Theme.DIM);
        Brush.text(g, "same tick " + srvSame + "   order " + (srvOrder == 0 ? "ok" : "ERR " + srvOrder)
                        + "   last " + (lastPermille < 0 ? "—"
                        : String.format(java.util.Locale.ROOT, "%.3f", lastPermille / 1000.0)),
                x + 12, y + 73, Theme.font(10), Theme.DIM);
    }

    
    
    

    private void paintPanel(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, VW, VH);

        Brush.panel(g, PANEL_X, PANEL_Y, PANEL_W, PANEL_H, 0);
        g.setColor(Theme.PANEL_2);
        g.fill(new Rectangle2D.Double(PANEL_X, PANEL_Y, PANEL_W, 40));
        g.setColor(Theme.ACCENT);
        g.fill(new Rectangle2D.Double(PANEL_X, PANEL_Y + 39, PANEL_W, 1));
        g.setColor(Theme.LINE);
        g.draw(new Rectangle2D.Double(PANEL_X + 0.5, PANEL_Y + 0.5, PANEL_W - 1, PANEL_H - 1));

        String title;
        switch (tab) {
            case TAB_NET: title = "NETWORK · GRAPHS"; break;
            case TAB_CONSOLE: title = "CONSOLE · LOG"; break;
            case TAB_CHAT: title = "CHAT"; break;
            default: title = "TABLE · PLAYERS AND PING"; break;
        }
        Brush.text(g, title, PANEL_INNER_X, PANEL_Y + 26, Theme.semibold(13), Theme.TEXT);
        Brush.right(g, "ESC — CLOSE", PANEL_X + PANEL_W - 24, PANEL_Y + 26, Theme.font(10), Theme.MUTED);

        switch (tab) {
            case TAB_NET: paintNetTab(g); break;
            case TAB_CONSOLE: paintConsoleTab(g); break;
            case TAB_CHAT: paintChatTab(g); break;
            default: paintTableTab(g); break;
        }
    }

    private static final class Row {
        int id;
        String name;
        int color;
        int score;
        int ping;
        Boolean alive;
    }

    private List<Row> tableRows() {
        List<Row> rows = new ArrayList<>();
        if (cur != null && !cur.snakes.isEmpty()) {
            for (Snapshot.SnakeView s : cur.snakes) {
                Row r = new Row();
                r.id = s.id;
                PlayerInfo info = names.get(s.id);
                r.name = info != null ? info.name : ("Player " + s.id);
                r.color = s.color;
                r.score = s.score;
                r.ping = s.ping;
                r.alive = s.alive;
                rows.add(r);
            }
        } else {
            for (Map.Entry<Integer, PlayerInfo> e : names.entrySet()) {
                Row r = new Row();
                r.id = e.getKey();
                r.name = e.getValue().name;
                r.color = e.getValue().color;
                r.score = 0;
                r.ping = e.getValue().ping;
                r.alive = null;
                rows.add(r);
            }
        }
        rows.sort((a, b) -> Integer.compare(b.score, a.score));
        for (Row r : rows) {
            if (r.id == app.myId() && app.client() != null) {
                int mine = app.client().rttMs();
                if (mine >= 0) r.ping = mine;
            }
        }
        return rows;
    }

    private void paintTableTab(Graphics2D g) {
        double x = PANEL_INNER_X;
        double y = PANEL_INNER_Y;
        double w = PANEL_INNER_W;

        Brush.text(g, "PLACE", x + 6, y, Theme.bold(10), Theme.MUTED);
        Brush.text(g, "PLAYER", x + 78, y, Theme.bold(10), Theme.MUTED);
        Brush.right(g, "SCORE", x + 470, y, Theme.bold(10), Theme.MUTED);
        Brush.right(g, "PING", x + 580, y, Theme.bold(10), Theme.MUTED);
        Brush.right(g, "STATUS", x + w - 8, y, Theme.bold(10), Theme.MUTED);
        y += 12;
        g.setColor(Theme.LINE);
        g.draw(new Line2D.Double(x, y, x + w, y));

        List<Row> rows = tableRows();
        int place = 1;
        double rowY = y + 10;
        for (Row r : rows) {
            double top = rowY + (place - 1) * 34;
            if (top > PANEL_Y + PANEL_H - 70) break;
            if (r.id == app.myId()) {
                g.setColor(new Color(0xFF, 0xFF, 0xFF, 12));
                g.fill(new Rectangle2D.Double(x, top, w, 30));
                g.setColor(Theme.alpha(Theme.ACCENT, 120));
                g.draw(new Rectangle2D.Double(x + 0.5, top + 0.5, w - 1, 29));
            }
            Color c = Theme.player(r.color);
            String medal = place == 1 ? "1" : place == 2 ? "2" : place == 3 ? "3" : String.valueOf(place);
            Color medalColor = place == 1 ? Theme.GOLD : place == 2 ? new Color(0xC8, 0xD4, 0xE4)
                    : place == 3 ? new Color(0xD8, 0x9B, 0x6A) : Theme.MUTED;
            Brush.centered(g, medal, x + 22, top + 21, Theme.semibold(14), medalColor);
            g.setColor(c);
            g.fill(new Ellipse2D.Double(x + 50, top + 10, 11, 11));
            Brush.text(g, Brush.ellipsize(g, r.name, 260), x + 72, top + 21,
                    Theme.semibold(14), r.id == app.myId() ? Theme.ACCENT : Theme.TEXT);
            Brush.right(g, String.valueOf(r.score), x + 470, top + 21, Theme.semibold(14), Theme.ACCENT);
            String ping = r.ping < 0 ? "—" : r.ping + " ms";
            Color pingColor = r.ping < 0 ? Theme.MUTED : r.ping < 60 ? Theme.ACCENT
                    : r.ping < 120 ? Theme.YELLOW : Theme.DANGER;
            Brush.right(g, ping, x + 580, top + 21, Theme.font(13), pingColor);
            String status = r.alive == null ? "LOBBY" : r.alive ? "PLAYING" : "OUT";
            Color statusColor = r.alive == null ? Theme.MUTED : r.alive ? Theme.ACCENT2 : Theme.DANGER;
            Brush.right(g, status, x + w - 8, top + 21, Theme.font(12), statusColor);
            place++;
        }
        if (rows.isEmpty()) {
            Brush.text(g, "No one here yet", x + 8, y + 34, Theme.font(14), Theme.MUTED);
        }
        Brush.text(g, "Ping is measured by the server · updated twice per second",
                x + 8, PANEL_Y + PANEL_H - 22, Theme.font(10), Theme.MUTED);
    }

    private void paintNetTab(Graphics2D g) {
        double x = PANEL_INNER_X;
        double y = PANEL_INNER_Y;
        double gap = 12;
        double colW = (PANEL_INNER_W - gap) / 2;
        double h = 132;

        gRtt.paint(g, x, y, colW, h, Theme.ACCENT2, Math.max(60, gRtt.last() * 1.4));
        gJitter.paint(g, x + colW + gap, y, colW, h, Theme.YELLOW, Math.max(10, gJitter.last() * 1.5));
        gTrafficIn.paint(g, x, y + h + gap, colW, h, Theme.ACCENT, Math.max(4, gTrafficIn.last() * 1.4));
        gTrafficOut.paint(g, x + colW + gap, y + h + gap, colW, h, Theme.MAGENTA,
                Math.max(4, gTrafficOut.last() * 1.4));
        gSnapRate.paint(g, x, y + (h + gap) * 2, colW, h, Theme.ACCENT2,
                Math.max(10, gSnapRate.last() * 1.4));
        gRecon.paint(g, x + colW + gap, y + (h + gap) * 2, colW, h, Theme.YELLOW,
                Math.max(4, Math.max(lastReconPeak, gRecon.last()) * 1.4));

        double statsY = y + (h + gap) * 3 + 4;
        String protocol = app.client() == null ? "—" : app.client().protocol();
        String voiceBackend = app.voice() == null ? "off" : app.voice().backend();
        String stats = "PROTOCOL " + protocol
                + "   PACKETS " + packetsIn() + "/" + packetsOut()
                + "   TRAFFIC " + kbTotalIn() + "/" + kbTotalOut() + " KB"
                + "   RATE " + cfg.tickRate + " Hz"
                + "   VOICE " + voiceBackend;
        Brush.text(g, stats, x, statsY, Theme.font(11), Theme.DIM);
        int rtt = app.client() == null ? -1 : app.client().rttMs();
        int loss = app.client() == null ? 0 : app.client().lossPercent();
        int quality = app.client() == null ? 0 : app.client().quality();
        String stats2 = "RTT " + (rtt < 0 ? "—" : rtt + " ms")
                + "   JITTER " + Math.round(jitter) + " ms"
                + "   LOSS " + loss + "%"
                + "   QUALITY " + quality + "%";
        Brush.text(g, stats2, x, statsY + 18, Theme.font(11), Theme.DIM);
        String stats3 = "SNAPSHOTS " + snapCount
                + "   CORRECTIONS " + corrections
                + "   RESETS " + snaps
                + "   INTERP " + Math.round(interpDelayMs) + " ms";
        Brush.text(g, stats3, x, statsY + 36, Theme.font(11), Theme.DIM);

        int steps = Math.max(1, cur == null ? cfg.subSteps : cur.subSteps);
        boolean subOn = cur == null ? cfg.subtick : cur.subtick;
        int srvIn = cur == null ? 0 : cur.subIn;
        int srvApplied = cur == null ? 0 : cur.subApplied;
        int srvMerged = cur == null ? 0 : cur.subMerged;
        int srvSame = cur == null ? 0 : cur.subSameTick;
        int srvOrder = cur == null ? 0 : cur.subOrderErrors;
        int lastPermille = cur == null ? -1 : (cur.subIn == 0 ? -1 : cur.subLastPermille);

        String stats4 = "SUBTICK " + (subOn ? "ON" : "OFF")
                + "   SENT " + subSent
                + "   SRV IN " + srvIn
                + "   APPLIED " + srvApplied
                + "   MERGED " + srvMerged;
        Brush.text(g, stats4, x, statsY + 54, Theme.font(11), subOn ? Theme.ACCENT2 : Theme.DIM);
        String stats5 = "SAME TICK " + srvSame
                + "   ORDER " + (srvOrder == 0 ? "OK" : "ERR " + srvOrder)
                + "   LAST SUB " + (lastPermille < 0 ? "—"
                : String.format(java.util.Locale.ROOT, "%.3f", lastPermille / 1000.0))
                + "   IN DROP " + (cur == null ? 0 : cur.inputDropped)
                + "   VOICE " + (cur == null ? 0 : cur.voiceAccepted)
                + "/" + (cur == null ? 0 : cur.voiceRejected);
        Brush.text(g, stats5, x, statsY + 72, Theme.font(11), Theme.DIM);
        if (subOn) paintSubBuckets(g, x, statsY + 92, steps);
    }

    private static String bucketText(int[] buckets) {
        if (buckets == null || buckets.length == 0) return "—";
        StringBuilder sb = new StringBuilder(buckets.length * 3);
        for (int i = 0; i < buckets.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(buckets[i]);
        }
        return sb.toString();
    }

    private void paintSubBuckets(Graphics2D g, double x, double y, int steps) {
        int n = Math.max(1, Math.min(16, steps));
        int[] counts = cur == null ? new int[0] : cur.subBuckets;
        int max = 1;
        for (int i = 0; i < n && i < counts.length; i++) max = Math.max(max, counts[i]);
        double gap = 4;
        double bw = Math.min(52, (PANEL_INNER_W - gap * (n - 1)) / n);
        for (int i = 0; i < n; i++) {
            double bx = x + i * (bw + gap);
            int count = i < counts.length ? counts[i] : 0;
            double bh = 16.0 * count / max;
            g.setColor(new Color(0x1B, 0x22, 0x2C, 160));
            g.fill(new RoundRectangle2D.Double(bx, y, bw, 16, 3, 3));
            g.setColor(Theme.alpha(Theme.ACCENT2, 120));
            g.fill(new RoundRectangle2D.Double(bx, y + 16 - Math.max(1, bh), bw, Math.max(1, bh), 3, 3));
            Brush.text(g, String.valueOf(count), bx + 3, y + 11, Theme.font(9), Theme.TEXT);
            if (n <= 8) {
                Brush.text(g, String.format(java.util.Locale.ROOT, "%.2f", (i + 0.5) / n),
                        bx + bw - 18, y + 26, Theme.font(9), Theme.MUTED);
            }
        }
    }

    private long packetsIn() {
        return app.client() == null ? 0 : app.client().stats().packetsIn();
    }

    private long packetsOut() {
        return app.client() == null ? 0 : app.client().stats().packetsOut();
    }

    private long kbTotalIn() {
        return app.client() == null ? 0 : app.client().stats().bytesIn() / 1024;
    }

    private long kbTotalOut() {
        return app.client() == null ? 0 : app.client().stats().bytesOut() / 1024;
    }

    private void paintConsoleTab(Graphics2D g) {
        List<String> lines = app.logLines();
        int maxLines = 25;
        int from = Math.max(0, lines.size() - maxLines);
        double lineH = 20;
        double y = PANEL_INNER_Y + 4;
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            Color c = Theme.DIM;
            if (lower.contains("error") || lower.contains("kick") || lower.contains("lost")
                    || lower.contains("loss"))
                c = Theme.DANGER;
            else if (lower.contains("round") || lower.contains("joined") || lower.contains("reconnected"))
                c = Theme.ACCENT2;
            else if (lower.contains("chat") || line.contains(":")) c = Theme.TEXT;
            Brush.text(g, Brush.ellipsize(g, line, PANEL_INNER_W - 8), PANEL_INNER_X, y,
                    Theme.mono(11), c);
            y += lineH;
        }
        if (lines.isEmpty()) {
            Brush.text(g, "The log is empty", PANEL_INNER_X, PANEL_INNER_Y + 24, Theme.font(13), Theme.MUTED);
        }
        Brush.text(g, "NETWORK EVENTS, CHAT AND ERRORS · " + lines.size() + " LINES",
                PANEL_INNER_X, PANEL_Y + PANEL_H - 22, Theme.font(10), Theme.MUTED);
    }

    private void paintChatTab(Graphics2D g) {
        double width = PANEL_INNER_W;
        double fieldTop = PANEL_Y + PANEL_H - 58;
        int maxLines = 20;
        int from = Math.max(0, chatLines.size() - maxLines);
        double lineH = 19;
        double y = PANEL_INNER_Y + 6;
        for (int i = from; i < chatLines.size(); i++) {
            String[] line = chatLines.get(i);
            boolean system = "1".equals(line[2]);
            String text = system ? line[1] : line[0] + ": " + line[1];
            Color color = system ? Theme.MUTED : Theme.player(Integer.parseInt(line[3]));
            Brush.text(g, Brush.ellipsize(g, text, width - 8), PANEL_INNER_X, y,
                    Theme.font(13), color);
            y += lineH;
        }
        if (chatLines.isEmpty()) {
            Brush.text(g, "No messages yet. Say hi first.", PANEL_INNER_X, PANEL_INNER_Y + 14,
                    Theme.font(13), Theme.MUTED);
        }
        Brush.text(g, "ENTER — send   ESC — close", PANEL_INNER_X, fieldTop - 12,
                Theme.font(10), Theme.MUTED);
    }

    private void paintTopBar(Graphics2D g) {
        g.setColor(new Color(0x08, 0x0A, 0x0C, 225));
        g.fill(new Rectangle2D.Double(0, 0, VW, 42));
        g.setColor(Theme.LINE);
        g.draw(new Line2D.Double(0, 42.5, VW, 42.5));

        Brush.text(g, "SNAKE NET", 16, 27, Theme.display(15), Theme.TEXT);
        g.setColor(Theme.ACCENT);
        g.fill(new Ellipse2D.Double(119, 17, 6, 6));

        String info;
        if (cfg.chess) {
            info = "CHESS  ·  " + (chessTurn == Chess.WHITE ? "white to move" : "black to move")
                    + (app.client() == null ? "" : "  ·  " + app.client().protocol());
        } else {
            info = cols + "×" + rows + "  ·  " + cfg.cellsPerSec + " cells/s  ·  " + cfg.tickRate + " Hz"
                    + (cfg.subtick ? " · subtick" : "") + "  ·  "
                    + (cfg.walls ? "walls" : "wrap")
                    + (app.client() == null ? "" : "  ·  " + app.client().protocol());
        }
        Brush.text(g, info, 136, 27, Theme.font(11), Theme.MUTED);

        int total = cfg.chess ? 2 : 0;
        if (!cfg.chess && cur != null) total = cur.snakes.size();
        int ping = app.client() == null ? -1 : app.client().rttMs();
        String right = "PLAYERS " + total
                + "   ·   " + timeString(runTime)
                + "   ·   FPS " + win.fps()
                + "   ·   PING " + (ping < 0 ? "—" : ping + " ms");
        Brush.right(g, right, VW - 16, 27, Theme.font(11), Theme.MUTED);
    }

    private String timeString(double seconds) {
        int s = (int) seconds;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void paintBoard(Graphics2D g) {
        Shape outer = Brush.round(boardX, boardY, boardW, boardH, 8);
        g.setColor(new Color(0x0A, 0x0D, 0x12));
        g.fill(outer);
        g.setColor(Theme.alpha(Theme.LINE, 200));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(outer);

        
        g.setColor(new Color(255, 255, 255, 12));
        for (int x = 1; x < cols; x++) {
            for (int y = 1; y < rows; y++) {
                g.fill(new Ellipse2D.Double(boardX + x * cell - 1.1, boardY + y * cell - 1.1, 2.2, 2.2));
            }
        }

        if (cfg.chess) {
            paintChessBoard(g);
            return;
        }

        if (cur == null) {
            Brush.centered(g, "Waiting for the start…", boardX + boardW / 2, boardY + boardH / 2, Theme.font(18), Theme.MUTED);
            return;
        }

        paintFood(g);
        paintSnakes(g);
        paintParticles(g);
    }

    private Color foodColor(int kind) {
        switch (kind) {
            case 1:
                return Theme.MAGENTA;
            case 2:
                return new Color(0xE6, 0x83, 0x6B);
            case 3:
                return new Color(0xA4, 0x8B, 0xD4);
            default:
                return Theme.YELLOW;
        }
    }

    private void paintChessBoard(Graphics2D g) {
        Shape outer = Brush.round(boardX, boardY, boardW, boardH, 8);
        g.setColor(new Color(0x0A, 0x0D, 0x12));
        g.fill(outer);
        g.setColor(Theme.alpha(Theme.LINE, 200));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(outer);
        double cs = Math.min(boardW, boardH) / 8.0;
        double bx0 = boardX + (boardW - cs * 8) / 2;
        double by0 = boardY + (boardH - cs * 8) / 2;
        boolean flip = chessMyColor == Chess.BLACK;
        java.util.List<Chess.Move> targets = chessSelected >= 0 && chessTurn == chessMyColor
                ? chess.legalMoves() : java.util.List.of();
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                int rank = flip ? r : 7 - r;
                int file = flip ? 7 - f : f;
                int sq = rank * 8 + file;
                Color bg = (r + f) % 2 == 0 ? new Color(0x2E, 0x38, 0x46) : new Color(0x22, 0x2B, 0x3A);
                if (sq == chessSelected) bg = Theme.shade(bg, 0.35);
                g.setColor(bg);
                g.fill(new Rectangle2D.Double(bx0 + f * cs, by0 + r * cs, cs, cs));
                boolean target = false;
                for (Chess.Move move : targets) if (move.to == sq) target = true;
                int p = chess.at(sq);
                if (target) {
                    g.setColor(Theme.alpha(Theme.ACCENT, p == 0 ? 150 : 90));
                    double rr = p == 0 ? cs * 0.16 : cs * 0.42;
                    g.fill(new Ellipse2D.Double(bx0 + f * cs + cs / 2 - rr, by0 + r * cs + cs / 2 - rr, rr * 2, rr * 2));
                }
                if (p == 0) continue;
                int type = (p - 1) % 6 + 1;
                char letter = "KQRBNP".charAt(type - 1);
                if (p >= 7) letter = Character.toLowerCase(letter);
                float size = (float) Math.min(34, cs * 0.8);
                g.setFont(Theme.semibold(size));
                g.setColor(p >= 7 ? new Color(0xE0, 0x8A, 0x44) : new Color(0xE6, 0xEC, 0xF7));
                g.drawString(String.valueOf(letter), (float) (bx0 + f * cs + cs * 0.28), (float) (by0 + r * cs + cs * 0.5));
            }
        }
    }

    private void paintFood(Graphics2D g) {
        for (Snapshot.FoodView f : cur.food) {
            double cx = boardX + (f.x + 0.5) * cell;
            double cy = boardY + (f.y + 0.5) * cell;
            double pulse = 1 + 0.06 * Math.sin(time * 3 + f.x * 1.7 + f.y * 0.9);
            Color c = foodColor(f.kind);
            double r = cell * 0.26 * pulse;

            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new Ellipse2D.Double(cx - r + 1.5, cy - r + 2.5, r * 2, r * 2));
            g.setColor(c);
            g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            g.setColor(Theme.alpha(Theme.shade(c, -0.4), 190));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            g.setColor(new Color(255, 255, 255, 170));
            double hl = r * 0.55;
            g.fill(new Ellipse2D.Double(cx - r * 0.45, cy - r * 0.55, hl, hl));
            
            g.setColor(Theme.shade(c, 0.25));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(cx + r * 0.25, cy - r * 0.85, cx + r * 0.55, cy - r * 1.25));
        }
    }

    private long serverNanosNow() {
        if (app.client() != null && app.client().clock() != null && app.client().clock().synced()) {
            return app.client().clock().serverNanosNow();
        }
        if (cur == null) return System.nanoTime();
        return cur.serverNanos + (System.nanoTime() - cur.recvNanos);
    }

    





    private double[] forecastPoints(int[] xs, int[] ys, int dir, int acc, double unitsAhead) {
        return SnakeRenderMath.points(xs, ys, dir, acc, unitsAhead);
    }
    private double[] pointsFor(Snapshot.SnakeView s) {
        if (s.id == app.myId() && predicted != null && predicted.alive && predicted.length() > 0) {
            double[] local = localRender != null ? localRender : computePredictedPoints();
            if (local != null) return local;
        }
        return remotePoints(s.id);
    }

    




    







    private double[] remotePoints(int snakeId) {
        if (snapHistory.isEmpty()) return null;
        Snapshot first = snapHistory.peekFirst();
        Snapshot last = snapHistory.peekLast();
        if (snapHistory.size() == 1) return viewPoints(first, snakeId);

        Snapshot base = null;
        for (Snapshot s : snapHistory) {
            if (s.serverNanos <= renderServerNanos) base = s;
            else break;
        }
        if (base == null) base = first;
        Snapshot.SnakeView v = base.snake(snakeId);
        if (v == null || v.length() == 0) {
            v = last.snake(snakeId);
            base = last;
        }
        if (v == null || v.length() == 0) return null;
        double npt = 1_000_000_000.0 / Math.max(1, cfg.tickRate);
        double phase = (renderServerNanos - base.serverNanos) / npt;
        phase = Math.max(0, Math.min(3, phase));
        double ahead = cfg.unitsPerTick() * phase;
        return SnakeRenderMath.pointsAt(v.xs, v.ys, v.dir, v.acc, ahead);
    }

    private Snapshot secondLastSnapshot() {
        Snapshot last = null;
        Snapshot prev = null;
        for (Snapshot s : snapHistory) {
            prev = last;
            last = s;
        }
        return prev;
    }

    private double[] viewPoints(Snapshot snap, int snakeId) {
        Snapshot.SnakeView v = snap.snake(snakeId);
        if (v == null || v.length() == 0) return null;
        return SnakeRenderMath.points(v.xs, v.ys, v.dir, v.acc, 0);
    }

    private double[] blendViews(Snapshot a, Snapshot b, int snakeId, double phase) {
        Snapshot.SnakeView va = a.snake(snakeId);
        Snapshot.SnakeView vb = b.snake(snakeId);
        if (va == null) va = vb;
        if (vb == null) vb = va;
        if (va == null) return null;
        double[] pa = SnakeRenderMath.points(va.xs, va.ys, va.dir, va.acc, 0);
        double[] pb = SnakeRenderMath.points(vb.xs, vb.ys, vb.dir, vb.acc, 0);
        return SnakeRenderMath.blend(pa, pb, phase);
    }
    private void paintSnakes(Graphics2D g) {
        List<Snapshot.SnakeView> list = new ArrayList<>(cur.snakes);
        list.sort((x, y) -> Boolean.compare(x.alive, y.alive));

        for (Snapshot.SnakeView s : list) {
            if (s.length() == 0) continue;
            Color base = Theme.player(s.color);
            Color color = s.alive ? base : Theme.mix(base, Theme.BG, 0.45f);
            double[] pts = pointsFor(s);
            if (pts == null) continue;
            int n = pts.length / 2;

            double[] px = new double[n];
            double[] py = new double[n];
            for (int i = 0; i < n; i++) {
                px[i] = boardX + (pts[i * 2] + 0.5) * cell;
                py[i] = boardY + (pts[i * 2 + 1] + 0.5) * cell;
            }

            
            for (int i = n - 1; i >= 0; i--) {
                double size = cell * (i == 0 ? 1.0 : 0.94);
                double rr = size * 0.30;
                Color c;
                if (!s.alive) c = Theme.mix(base, Theme.BG, 0.45f);
                else if (i == 0) c = Theme.shade(base, 0.10);
                else c = (i % 2 == 0) ? base : Theme.shade(base, -0.13);

                g.setColor(new Color(0, 0, 0, s.alive ? 80 : 28));
                g.fill(Brush.round(px[i] - size / 2 + 1.5, py[i] - size / 2 + 2.5, size, size, rr));
                g.setColor(c);
                g.fill(Brush.round(px[i] - size / 2, py[i] - size / 2, size, size, rr));
                g.setColor(Theme.alpha(Theme.shade(c, -0.45), s.alive ? 150 : 70));
                g.setStroke(new BasicStroke(1.3f));
                g.draw(Brush.round(px[i] - size / 2, py[i] - size / 2, size, size, rr));
            }

            double hx = px[0];
            double hy = py[0];
            if (s.alive) {
                double size = cell * 1.0;
                g.setColor(new Color(255, 255, 255, 38));
                g.fill(Brush.round(hx - size * 0.36, hy - size * 0.40, size * 0.58, size * 0.30, size * 0.15));

                int dxs = SnakeState.DX[s.dir];
                int dys = SnakeState.DY[s.dir];
                double eyeX = hx + dxs * cell * 0.12;
                double eyeY = hy + dys * cell * 0.12;
                double perpX = -dys * cell * 0.17;
                double perpY = dxs * cell * 0.17;
                double er = Math.max(1.7, cell * 0.09);
                g.setColor(new Color(0x12, 0x0E, 0x0A));
                g.fill(new Ellipse2D.Double(eyeX + perpX - er, eyeY + perpY - er, er * 2, er * 2));
                g.fill(new Ellipse2D.Double(eyeX - perpX - er, eyeY - perpY - er, er * 2, er * 2));
                g.setColor(new Color(255, 255, 255, 220));
                double tr = er * 0.42;
                g.fill(new Ellipse2D.Double(eyeX + perpX - tr - 0.5, eyeY + perpY - tr - 0.5, tr * 2, tr * 2));
                g.fill(new Ellipse2D.Double(eyeX - perpX - tr - 0.5, eyeY - perpY - tr - 0.5, tr * 2, tr * 2));

                
                if (Math.sin(time * 5 + s.id * 1.7) > 0.55) {
                    double bx = hx + dxs * cell * 0.44;
                    double by = hy + dys * cell * 0.44;
                    double ex = bx + dxs * cell * 0.24;
                    double ey = by + dys * cell * 0.24;
                    g.setColor(new Color(0xD9, 0x5B, 0x66));
                    g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(bx, by, ex, ey));
                    double fx = -dys * cell * 0.09;
                    double fy = dxs * cell * 0.09;
                    g.draw(new Line2D.Double(ex, ey, ex + dxs * cell * 0.09 + fx, ey + dys * cell * 0.09 + fy));
                    g.draw(new Line2D.Double(ex, ey, ex + dxs * cell * 0.09 - fx, ey + dys * cell * 0.09 - fy));
                }
            }
            
            if (s.id == app.myId()) {
                Brush.text(g, "you", hx + cell * 0.5, hy - cell * 0.55, Theme.semibold(10), Theme.alpha(Theme.ACCENT, 200));
            }
        }
    }

    




    private void updateLocalRender(double dt) {
        double[] target = computePredictedPoints();
        if (target == null) {
            localRender = null;
            localDelta = null;
            localBlend = 1;
            return;
        }
        if (localRender == null || localRender.length != target.length) {
            localRender = target.clone();
            localDelta = null;
            localBlend = 1;
            return;
        }
        if (localDelta == null) {
            double jump = maxDistance(localRender, target);
            if (jump > 0.45 && jump < 3.0) {
                localDelta = new double[target.length];
                for (int i = 0; i < target.length; i++) localDelta[i] = localRender[i] - target[i];
                localBlend = 0;
            } else {
                System.arraycopy(target, 0, localRender, 0, target.length);
            }
        }
        if (localDelta != null) {
            double smooth = Math.max(0.016, cfg.predictionSmoothMs / 1000.0);
            localBlend = Math.min(1, localBlend + dt / smooth);
            double k = 1 - localBlend;
            if (cfg.nonlinearInterp) k = k * k * (3 - 2 * k);
            for (int i = 0; i < target.length && i < localDelta.length; i++) {
                localRender[i] = target[i] + localDelta[i] * k;
            }
            if (localBlend >= 1) {
                localDelta = null;
                System.arraycopy(target, 0, localRender, 0, target.length);
            }
        }
    }

    private static double maxDistance(double[] a, double[] b) {
        double max = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }

    private double[] computePredictedPoints() {
        SnakePredictState b = predictor == null ? null : predictor.stateAt(renderTick);
        if (b == null) b = predicted;
        if (b == null || b.length() == 0) return null;
        double ahead = cfg.unitsPerTick() * Math.max(0, Math.min(1.5, renderSub));
        return SnakeRenderMath.pointsAt(b.xs, b.ys, b.dir, b.acc, ahead);
    }

    private void paintParticles(Graphics2D g) {
        for (Particle p : particles) {
            double k = p.life / p.max;
            double cx = boardX + p.x * cell;
            double cy = boardY + p.y * cell;
            g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), (int) (220 * k)));
            double r = p.size * cell * (0.5 + k * 0.5);
            g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        }
        for (FloatText f : floats) {
            double k = f.life / f.max;
            g.setFont(Theme.semibold(17));
            g.setColor(new Color(f.color.getRed(), f.color.getGreen(), f.color.getBlue(), (int) (255 * k)));
            double cx = boardX + f.x * cell;
            double cy = boardY + f.y * cell;
            g.drawString(f.text, (float) cx, (float) cy);
        }
    }

    private void paintOverlays(Graphics2D g) {
        
        if (phase == Net.PHASE_COUNT && countdown > 0) {
            g.setColor(new Color(0, 0, 0, 110));
            g.fill(Brush.round(boardX, boardY, boardW, boardH, 8));
            double cx = boardX + boardW / 2;
            double cy = boardY + boardH / 2;
            Brush.centered(g, "GET READY", cx, cy - 96, Theme.semibold(18), Theme.DIM);
            float size = (float) (128 + countdownAnim * 40);
            int alpha = (int) (120 + 135 * countdownAnim);
            Brush.glowText(g, String.valueOf(countdown), cx, cy + 40, Theme.display(size),
                    Theme.alpha(Theme.TEXT, alpha), Theme.ACCENT, 4);
        }
        if (goFlash > 0.01 && phase == Net.PHASE_RUNNING && countdown == 0) {
            double k = goFlash;
            double cx = boardX + boardW / 2;
            double cy = boardY + boardH * 0.30;
            Brush.centered(g, "GO!", cx, cy, Theme.display((float) (28 + k * 6)),
                    Theme.alpha(Theme.ACCENT, (int) (255 * k)));
        }

        
        if (deadBanner > 0.02 && phase == Net.PHASE_RUNNING) {
            int a = (int) (200 * deadBanner);
            g.setColor(new Color(0x18, 0x08, 0x0C, a));
            g.fill(new RoundRectangle2D.Double(boardX + boardW / 2 - 150, boardY + 16, 300, 38, 19, 19));
            Brush.centered(g, "You are out · spectating", boardX + boardW / 2, boardY + 41,
                    Theme.semibold(14), new Color(0xFF, 0xA0, 0xA0, Math.min(255, a + 40)));
        }

        
        if (phase == Net.PHASE_OVER && results != null) {
            g.setColor(new Color(0, 0, 0, 175));
            g.fillRect(0, 0, VW, VH);
            g.setColor(new Color(0x0C, 0x15, 0x24, 250));
            g.fill(Brush.round(340, 150, 600, 480, 26));
            Brush.panel(g, 340, 150, 600, 480, 26);
            Brush.centered(g, "GAME OVER", 640, 206, Theme.semibold(13), Theme.MUTED);

            String winner = "";
            for (Result r : results) {
                if (r.place == 1) winner = r.name;
            }
            Brush.glowText(g, winner.isEmpty() ? "Draw" : winner, 640, 262, Theme.display(42),
                    Theme.TEXT, Theme.ACCENT, 4);
            Brush.centered(g, "wins", 640, 288, Theme.font(14), Theme.DIM);

            int shown = Math.min(results.size(), 6);
            for (int i = 0; i < shown; i++) {
                Result r = results.get(i);
                double y = 322 + i * 36;
                Color c = Theme.player(r.color);
                g.setColor(new Color(0xFF, 0xFF, 0xFF, r.id == app.myId() ? 16 : 6));
                g.fill(new RoundRectangle2D.Double(400, y - 22, 480, 32, 10, 10));
                String medal = r.place == 1 ? "1" : r.place == 2 ? "2" : r.place == 3 ? "3" : String.valueOf(r.place);
                Color medalColor = r.place == 1 ? Theme.GOLD : r.place == 2 ? new Color(0xC8, 0xD4, 0xE4) : r.place == 3 ? new Color(0xD8, 0x9B, 0x6A) : Theme.MUTED;
                Brush.centered(g, medal, 420, y, Theme.semibold(15), medalColor);
                g.setColor(c);
                g.fill(new Ellipse2D.Double(438, y - 15, 10, 10));
                g.setFont(Theme.semibold(15));
                Brush.text(g, Brush.ellipsize(g, r.name, 240), 458, y, Theme.semibold(15), Theme.TEXT);
                Brush.right(g, String.valueOf(r.score), 856, y, Theme.semibold(15), Theme.ACCENT);
            }
        }

        
        if (pauseAnim > 0.01) {
            g.setColor(new Color(0, 0, 0, (int) (170 * pauseAnim)));
            g.fillRect(0, 0, VW, VH);
            Brush.panel(g, 440, 270, 400, 260, 24);
            Brush.centered(g, "Leave the game?", 640, 330, Theme.display(26), Theme.TEXT);
            Brush.centered(g, app.isHost() ? "You are the host — the server will close for everyone" : "The round will continue without you",
                    640, 358, Theme.font(13), Theme.MUTED);
        }
    }
}
