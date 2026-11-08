package snakenet.ui;

import netcore.predict.Correction;
import netcore.predict.InputCommand;
import netcore.predict.PredictionConfig;
import netcore.predict.Predictor;
import netcore.sync.ClockSync;
import snakenet.audio.Sfx;
import snakenet.client.ServerClock;
import snakenet.client.SnakePredictState;
import snakenet.client.SnakeRenderMath;
import snakenet.client.Snapshot;
import snakenet.game.GameConfig;
import snakenet.game.SnakeState;
import snakenet.net.Msg;
import snakenet.net.Protocol;

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

/** Игровое поле: доска, змейки, эффекты, счёт, отсчёт и результаты. */
public final class GameScreen extends Screen {

    private static final double AREA_X = 56;
    private static final double AREA_Y = 112;
    private static final double AREA_W = 916;
    private static final double AREA_H = 620;

    private static final class PlayerInfo {
        String name = "";
        int color;
        boolean bot;
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
    private final Graph gRtt = new Graph("RTT", "мс", 110);
    private final Graph gRecon = new Graph("РЕКОНСАЙЛ", "", 110);
    private InputField chatField;
    private boolean chatOpen;
    private double telemetryTimer;
    private double chatScroll;
    private final java.util.ArrayDeque<Snapshot> snapHistory = new java.util.ArrayDeque<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<FloatText> floats = new ArrayList<>();
    private List<Result> results;

    private GameConfig cfg = new GameConfig();
    private int cols = 32;
    private int rows = 22;
    private int phase = Protocol.PHASE_LOBBY;
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

    // предсказание своего игрока (движок netcore)
    private final ServerClock serverClock = new ServerClock();
    private Predictor<SnakePredictState> predictor;
    private SnakePredictState predicted;
    private double correctionFlash;
    private int corrections;
    private int snaps;

    private volatile int renderTick;
    private volatile double renderSub;

    // сглаживание своей змейки: резкие правки доводим за ~90 мс, а не телепортом
    private double[] localRender;
    private double[] localDelta;
    private double localBlend = 1;

    private double snapshotIntervalMs = 50;
    private long lastSnapRecv;
    private int lastReconTotal;
    private double lastReconPeak = 4;

    private double cell = 28;
    private double boardX = 56;
    private double boardY = 112;
    private double boardW = 896;
    private double boardH = 616;

    private final NeonButton bAgain;
    private final NeonButton bLobby;
    private final NeonButton bResume;
    private final NeonButton bQuit;

    public GameScreen(App app) {
        super(app);

        bAgain = add(new NeonButton("ЕЩЁ РАЗ", NeonButton.Style.PRIMARY, 372, 556, 240, 54, () -> app.send(Msg.of(Protocol.AGAIN))));
        bLobby = add(new NeonButton("В МЕНЮ", NeonButton.Style.SECONDARY, 636, 556, 200, 54, app::leaveToHome));
        bResume = add(new NeonButton("Продолжить", NeonButton.Style.PRIMARY, 500, 392, 280, 52, () -> pauseMenu = false));
        bQuit = add(new NeonButton("Выйти в меню", NeonButton.Style.DANGER, 500, 456, 280, 52, app::leaveToHome));
        bAgain.visible = false;
        bLobby.visible = false;
        bResume.visible = false;
        bQuit.visible = false;

        bind("UP", 0);
        bind("W", 0);
        bind("RIGHT", 1);
        bind("D", 1);
        bind("DOWN", 2);
        bind("S", 2);
        bind("LEFT", 3);
        bind("A", 3);

        chatField = new InputField("сообщение…");
        chatField.setVisible(false);
        addField(chatField, 380, 700, 520, 36, 12);
        chatField.addActionListener(e -> sendChat());

        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ENTER"), "chatopen");
        getActionMap().put("chatopen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (phase != Protocol.PHASE_RUNNING) return;
                chatOpen = true;
                chatField.setVisible(true);
                chatField.requestFocusInWindow();
            }
        });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "chatclose");
        getActionMap().put("chatclose", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (chatOpen) {
                    chatOpen = false;
                    chatField.setVisible(false);
                    chatField.setText("");
                    requestFocusInWindow();
                } else {
                    pauseMenu = !pauseMenu;
                }
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
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "pause");
        getActionMap().put("pause", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pauseMenu = !pauseMenu;
            }
        });
    }

    private void bind(String key, int dir) {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), "dir" + dir);
        getActionMap().put("dir" + dir, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (pauseMenu || chatOpen) return;
                if (phase != Protocol.PHASE_RUNNING) return;
                handleInput(dir);
            }
        });
    }

    @Override
    public void onShow() {
        requestFocusInWindow();
        pauseMenu = false;
    }

    @Override
    public void onHide() {
        pauseMenu = false;
    }

    // ------------------------------------------------------------------
    // сеть
    // ------------------------------------------------------------------

    @Override
    public void onFrame(List<Msg> frame) {
        for (Msg m : frame) {
            switch (m.type()) {
                case Protocol.LOBBY:
                    readRoster(frame);
                    break;
                case Protocol.START:
                    applyStart(m);
                    break;
                case Protocol.COUNT:
                    countdown = m.num("n", countdown);
                    countdownAnim = 1;
                    Sfx.play(Sfx.Sound.COUNT);
                    break;
                case Protocol.SNAP:
                    applySnapshot(frame);
                    break;
                case Protocol.OVER:
                    results = new ArrayList<>();
                    phase = Protocol.PHASE_OVER;
                    countdown = 0;
                    break;
                case Protocol.RESULT:
                    addResult(m);
                    break;
                case Protocol.ACK: {
                    int n = m.num("n", -1);
                    int at = m.num("t", -1);
                    Integer sent = sentTicks.remove(n);
                    if (sent != null) serverClock.observeApply(sent, at);
                    if (predictor != null) predictor.retime(n, at);
                    break;
                }
                case Protocol.CHAT: {
                    String text = m.str("text");
                    if (text.isEmpty()) break;
                    boolean system = m.num("kind", 0) == 1;
                    chatLines.add(new String[]{m.str("from"), text, system ? "1" : "0", String.valueOf(m.num("c", 0))});
                    if (chatLines.size() > 80) chatLines.remove(0);
                    if (system) {
                        String lower = text.toLowerCase(java.util.Locale.ROOT);
                        if (lower.contains("подключ") || lower.contains("выкинут")) Sfx.play(Sfx.Sound.JOIN);
                    }
                    break;
                }
                case Protocol.KICK:
                    app.toastError(m.str("msg"));
                    break;
                default:
                    break;
            }
        }
        if (results != null && phase == Protocol.PHASE_OVER) {
            boolean host = app.isHost();
            bAgain.visible = true;
            bLobby.visible = true;
            bAgain.enabled = host;
            bAgain.label = host ? "ЕЩЁ РАЗ" : "ЖДЁМ ХОСТА";
        } else {
            bAgain.visible = false;
            bLobby.visible = false;
        }
        bResume.visible = pauseMenu;
        bQuit.visible = pauseMenu;
    }

    private void readRoster(List<Msg> frame) {
        Map<Integer, PlayerInfo> map = new HashMap<>();
        for (Msg m : frame) {
            if (!m.is(Protocol.PLAYER)) continue;
            PlayerInfo info = new PlayerInfo();
            info.name = m.str("name");
            info.color = m.num("c", 0);
            info.bot = m.bool("bot");
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
        phase = Protocol.PHASE_COUNT;
        countdown = 3;
        countdownAnim = 1;
        prev = null;
        cur = null;
        particles.clear();
        floats.clear();
        results = null;
        runTime = 0;
        shake = 0;
        pauseMenu = false;
        predictor = new Predictor<>(new SnakePredictState.Model(cols, rows, cfg.unitsPerTick(), cfg.walls),
                PredictionConfig.of(2.2));
        predicted = null;
        localRender = null;
        localDelta = null;
        localBlend = 1;
        corrections = 0;
        snaps = 0;
        correctionFlash = 0;
        snapshotIntervalMs = 1000.0 / Math.min(cfg.tickRate, 30);
        lastSnapRecv = 0;
        serverClock.onSnapshot(0, 0, cfg.tickRate, System.nanoTime());
        bAgain.visible = false;
        bLobby.visible = false;
        bResume.visible = false;
        bQuit.visible = false;
        Sfx.play(Sfx.Sound.START);
    }

    private void applySnapshot(List<Msg> frame) {
        Snapshot snap = Snapshot.parse(frame, System.nanoTime());
        if (snap == null) return;
        // UDP может переставить пакеты — старый снимок не должен откатывать состояние
        if (cur != null && snap.tick <= cur.tick) return;
        boolean first = cur == null;
        long now = System.nanoTime();
        if (lastSnapRecv != 0) {
            double dt = (now - lastSnapRecv) / 1_000_000.0;
            if (dt > 5 && dt < 400) snapshotIntervalMs = snapshotIntervalMs * 0.8 + dt * 0.2;
        }
        lastSnapRecv = now;
        prev = cur;
        cur = snap;
        snapHistory.addLast(snap);
        while (snapHistory.size() > 12) snapHistory.removeFirst();
        serverClock.onSnapshot(snap.tick, snap.serverNanos, snap.tickRate, snap.recvNanos);

        reconcile(snap);

        if (phase != Protocol.PHASE_RUNNING) {
            phase = Protocol.PHASE_RUNNING;
            countdown = 0;
            if (first || goFlash <= 0) goFlash = 1;
        }
        for (Snapshot.EventView e : snap.events) handleEvent(e);
    }

    /** Сверка предсказания с сервером: мелкие расхождения — плавно, большие — «тпха». */
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

    /** Локальное нажатие: сразу в предсказание и на сервер. */
    private void handleInput(int dir) {
        if (chatOpen || predictor == null || app.client() == null) return;
        ClockSync clock = app.client().clock();
        int tick = serverClock.tickAt(clock) + serverClock.leadTicks(clock);
        float sub = (float) serverClock.subAt(clock);
        InputCommand command = predictor.push(dir, tick, sub);
        if (command == null) return;
        predicted = predictor.predicted();
        sentTicks.put(command.seq, command.tick);
        app.send(Msg.of(Protocol.IN)
                .put("d", dir)
                .put("t", command.tick)
                .put("s", String.format(java.util.Locale.ROOT, "%.3f", command.sub))
                .put("n", command.seq));
    }

    private void handleEvent(Snapshot.EventView e) {
        PlayerInfo info = names.get(e.player);
        int colorIdx = info == null ? 0 : info.color;
        Color c = Theme.player(colorIdx);
        boolean mine = e.player == app.myId();
        if (e.kind == Protocol.EV_EAT) {
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

    // ------------------------------------------------------------------
    // анимация
    // ------------------------------------------------------------------

    @Override
    protected void onTick(double dt) {
        countdownAnim = Math.max(0, countdownAnim - dt * 1.6);
        goFlash = Math.max(0, goFlash - dt * 1.4);
        shake = Math.max(0, shake - dt * 2.4);
        pauseAnim += ((pauseMenu ? 1 : 0) - pauseAnim) * Math.min(1, dt * 14);
        if (phase == Protocol.PHASE_RUNNING) runTime += dt;

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

        correctionFlash = Math.max(0, correctionFlash - dt * 2.2);

        telemetryTimer += dt;
        if (telemetryTimer >= 0.25) {
            telemetryTimer = 0;
            gRtt.push(app.client() == null ? 0 : Math.max(0, app.client().rttMs()));
            int total = corrections + snaps;
            gRecon.push(Math.max(0, total - lastReconTotal));
            lastReconTotal = total;
            lastReconPeak = Math.max(lastReconPeak * 0.98, Math.max(1, total - lastReconTotal + 1));
        }

        if (predictor != null && predictor.ready() && app.client() != null
                && phase == Protocol.PHASE_RUNNING) {
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
        cell = Math.floor(Math.min(AREA_W / cols, AREA_H / rows));
        cell = Math.max(4, cell);
        boardW = cell * cols;
        boardH = cell * rows;
        boardX = AREA_X + (AREA_W - boardW) / 2.0;
        boardY = AREA_Y + (AREA_H - boardH) / 2.0;
    }

    // ------------------------------------------------------------------
    // рисование
    // ------------------------------------------------------------------

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
        paintSidebar(g);
        paintChat(g);
        paintOverlays(g);
    }

    private void sendChat() {
        String text = chatField.getText().trim();
        if (!text.isEmpty()) app.send(Msg.of(Protocol.CHAT).putStr("text", text));
        chatField.setText("");
        chatOpen = false;
        chatField.setVisible(false);
        requestFocusInWindow();
    }

    /** Чат поверх поля: последние сообщения и подсказка. */
    private void paintChat(Graphics2D g) {
        if (chatLines.isEmpty() && !chatOpen) return;
        int from = Math.max(0, chatLines.size() - 7);
        int count = chatLines.size() - from;
        double lineH = 18;
        double top = boardY + boardH - 18 - count * lineH;
        double width = 420;

        g.setColor(new Color(0, 0, 0, 150));
        g.fill(new Rectangle2D.Double(boardX + 8, top - 16, width + 16, count * lineH + 12));
        g.setColor(Theme.LINE);
        g.draw(new Rectangle2D.Double(boardX + 8.5, top - 15.5, width + 15, count * lineH + 11));

        double y = top;
        for (int i = from; i < chatLines.size(); i++) {
            String[] line = chatLines.get(i);
            boolean system = "1".equals(line[2]);
            String text = system ? line[1] : line[0] + ": " + line[1];
            Color color = system ? Theme.MUTED : Theme.player(Integer.parseInt(line[3]));
            g.setFont(Theme.font(11));
            Brush.text(g, Brush.ellipsize(g, text, width), boardX + 16, y, Theme.font(11), color);
            y += lineH;
        }

        if (chatOpen) {
            Brush.text(g, "ENTER - отправить, ESC - закрыть", boardX + 380, 690, Theme.font(10), Theme.MUTED);
        } else {
            Brush.text(g, "ENTER - чат   K - голос", boardX + 16, boardY + boardH - 16, Theme.font(9), Theme.MUTED);
        }
    }

    private void paintTopBar(Graphics2D g) {
        Brush.text(g, "SNAKE NET", 56, 74, Theme.display(17), Theme.TEXT);
        g.setColor(Theme.ACCENT);
        g.fill(new Ellipse2D.Double(170, 63, 7, 7));

        String info = cols + "×" + rows + "  ·  " + cfg.cellsPerSec + " кл/с  ·  " + cfg.tickRate + " тик"
                + (cfg.subtick ? " · subtick ×" + cfg.subSteps : "") + "  ·  "
                + (cfg.walls ? "стены" : "сквозь стены")
                + (app.client() == null ? "" : "  ·  " + app.client().protocol());
        Brush.text(g, info, 196, 74, Theme.font(12), Theme.MUTED);

        int total = 0;
        if (cur != null) total = cur.snakes.size();
        String right = "ИГРОКОВ " + total + "   ·   " + timeString(runTime);
        Brush.right(g, right, 1224, 74, Theme.font(12), Theme.MUTED);
    }

    private String timeString(double seconds) {
        int s = (int) seconds;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void paintBoard(Graphics2D g) {
        Shape outer = Brush.round(boardX - 14, boardY - 14, boardW + 28, boardH + 28, 14);
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(Brush.round(boardX - 10, boardY - 9, boardW + 28, boardH + 28, 14));
        g.setColor(new Color(0x0A, 0x0D, 0x12));
        g.fill(outer);
        g.setColor(Theme.LINE);
        g.setStroke(new BasicStroke(2.6f));
        g.draw(outer);

        // точки на пересечениях клеток вместо линий
        g.setColor(new Color(255, 255, 255, 12));
        for (int x = 1; x < cols; x++) {
            for (int y = 1; y < rows; y++) {
                g.fill(new Ellipse2D.Double(boardX + x * cell - 1.1, boardY + y * cell - 1.1, 2.2, 2.2));
            }
        }

        if (cur == null) {
            Brush.centered(g, "Ждём старт…", boardX + boardW / 2, boardY + boardH / 2, Theme.font(18), Theme.MUTED);
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
            // хвостик
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

    /**
     * Непрерывные точки змейки. Позиция головы — это клетка плюс доля
     * накопителя движения; каждый следующий сегмент подтягивается к впереди
     * идущему ровно на ту же долю. Поэтому движение идёт «на рельсах»,
     * а поворот происходит вместе с движением, а не до него.
     */
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

    /**
     * Чужие змейки: рендерим с задержкой в пару снимков и интерполируем
     * непрерывные позиции между соседними снимками — так нет ни замираний,
     * ни прыжков на границе клетки.
     */
    private double[] remotePoints(int snakeId) {
        if (snapHistory.isEmpty()) return null;
        long now = serverNanosNow();
        long delay = (long) (Math.max(2 * snapshotIntervalMs, 45) * 1_000_000.0);
        long target = now - delay;

        Snapshot before = null;
        Snapshot after = null;
        for (Snapshot snap : snapHistory) {
            if (snap.serverNanos <= target) before = snap;
            else {
                after = snap;
                break;
            }
        }
        if (before == null) before = snapHistory.peekFirst();
        if (after == null) after = snapHistory.peekLast();
        if (before == null || after == null) return null;

        Snapshot.SnakeView a = before.snake(snakeId);
        Snapshot.SnakeView b = after.snake(snakeId);
        if (a == null) a = b;
        if (b == null) b = a;
        if (a == null) return null;

        double[] pa = SnakeRenderMath.points(a.xs, a.ys, a.dir, a.acc, 0);
        double[] pb = SnakeRenderMath.points(b.xs, b.ys, b.dir, b.acc, 0);
        if (a == b || after.serverNanos <= before.serverNanos) return pb;
        double phase = (target - before.serverNanos) / (double) (after.serverNanos - before.serverNanos);
        phase = Math.max(0, Math.min(1, phase));
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

            // тело: хвост -> голова, каждый сегмент — скруглённый квадратик
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

                // язык: высовывается рывками
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
            // подпись своего ника над головой
            if (s.id == app.myId()) {
                Brush.text(g, "ты", hx + cell * 0.5, hy - cell * 0.55, Theme.semibold(10), Theme.alpha(Theme.ACCENT, 200));
            }
        }
    }

    /**
     * Сглаживание своей змейки. Обычные кадры рисуются как есть, но если
     * сервер прислал правку и позиция скакнула — доводим её за ~90 мс,
     * чтобы поворот не выглядел рывком.
     */
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
            if (jump > 0.45 && jump < 6) {
                localDelta = new double[target.length];
                for (int i = 0; i < target.length; i++) localDelta[i] = localRender[i] - target[i];
                localBlend = 0;
            } else {
                System.arraycopy(target, 0, localRender, 0, target.length);
            }
        }
        if (localDelta != null) {
            localBlend = Math.min(1, localBlend + dt / 0.09);
            double k = 1 - localBlend * (2 - localBlend);
            for (int i = 0; i < target.length && i < localDelta.length; i++) {
                localRender[i] = target[i] + localDelta[i] * k;
            }
            if (localBlend >= 1) localDelta = null;
        }
    }

    private static double maxDistance(double[] a, double[] b) {
        double max = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }
    /** Своя змейка: смешиваем непрерывные позиции двух соседних предсказанных тиков. */
    private double[] computePredictedPoints() {
        SnakePredictState a = predictor == null ? null : predictor.stateAt(renderTick - 1);
        SnakePredictState b = predictor == null ? null : predictor.stateAt(renderTick);
        if (b == null) b = predicted;
        if (b == null || b.length() == 0) return null;
        double[] pb = SnakeRenderMath.points(b.xs, b.ys, b.dir, b.acc, 0);
        if (a == null || a.length() != b.length()) return pb;
        double[] pa = SnakeRenderMath.points(a.xs, a.ys, a.dir, a.acc, 0);
        return SnakeRenderMath.blend(pa, pb, renderSub);
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

    private void paintSidebar(Graphics2D g) {
        Brush.panel(g, 1000, 112, 224, 620, 22);
        Brush.text(g, "СЧЁТ", 1022, 142, Theme.semibold(11), Theme.MUTED);

        List<Snapshot.SnakeView> list = new ArrayList<>();
        if (cur != null) list.addAll(cur.snakes);
        list.sort((a, b) -> Integer.compare(b.score, a.score));
        int max = 10;
        for (Snapshot.SnakeView s : list) max = Math.max(max, s.score);

        int shown = Math.min(list.size(), 8);
        for (int i = 0; i < shown; i++) {
            Snapshot.SnakeView s = list.get(i);
            PlayerInfo info = names.get(s.id);
            String name = info != null ? info.name : "Игрок " + s.id;
            Color c = Theme.player(s.color);
            double y = 158 + i * 52;

            if (!s.alive) {
                g.setColor(new Color(255, 255, 255, 8));
                g.fill(new RoundRectangle2D.Double(1012, y, 200, 44, 12, 12));
            }
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), s.alive ? 48 : 20));
            g.fill(new Ellipse2D.Double(1016, y + 9, 26, 26));
            g.setColor(s.alive ? c : Theme.alpha(c, 90));
            g.fill(new Ellipse2D.Double(1023, y + 16, 12, 12));

            g.setFont(Theme.semibold(14));
            String label = Brush.ellipsize(g, name, 118);
            Brush.text(g, label, 1052, y + 20, Theme.semibold(14), s.alive ? Theme.TEXT : Theme.MUTED);
            Brush.right(g, String.valueOf(s.score), 1206, y + 20, Theme.semibold(15), Theme.ACCENT);

            g.setColor(new Color(0x2E, 0x38, 0x46));
            g.fill(new RoundRectangle2D.Double(1052, y + 28, 154, 4, 4, 4));
            g.setColor(Theme.alpha(c, s.alive ? 220 : 90));
            g.fill(new RoundRectangle2D.Double(1052, y + 28, 154 * (s.score / (double) max), 4, 4, 4));

            if (s.id == app.myId()) {
                g.setColor(Theme.alpha(Theme.ACCENT, 140));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new RoundRectangle2D.Double(1012, y, 200, 44, 12, 12));
            }
        }

        int ping = app.client() == null ? -1 : app.client().rttMs();
        String stats = "PING " + (ping < 0 ? "--" : ping + "ms")
                + "   TICK " + cfg.tickRate
                + "   FPS " + win.fps();
        Brush.text(g, stats, 1016, 640, Theme.font(10), Theme.DIM);
        int recon = corrections + snaps;
        Brush.text(g, "RECON " + recon + "   SNAP " + snaps, 1016, 658, Theme.font(10),
                recon > 0 ? Theme.YELLOW : Theme.MUTED);

        gRtt.paint(g, 1016, 668, 192, 32, Theme.ACCENT2, 120);
        gRecon.paint(g, 1016, 706, 192, 24, Theme.YELLOW, Math.max(4, lastReconPeak));
    }
    private void paintOverlays(Graphics2D g) {
        // отсчёт
        if (phase == Protocol.PHASE_COUNT && countdown > 0) {
            g.setColor(new Color(0, 0, 0, 110));
            g.fill(Brush.round(boardX, boardY, boardW, boardH, 8));
            double cx = boardX + boardW / 2;
            double cy = boardY + boardH / 2;
            Brush.centered(g, "ПРИГОТОВЬСЯ", cx, cy - 96, Theme.semibold(18), Theme.DIM);
            float size = (float) (128 + countdownAnim * 40);
            int alpha = (int) (120 + 135 * countdownAnim);
            Brush.glowText(g, String.valueOf(countdown), cx, cy + 40, Theme.display(size),
                    Theme.alpha(Theme.TEXT, alpha), Theme.ACCENT, 4);
        }
        if (goFlash > 0.01 && phase == Protocol.PHASE_RUNNING && countdown == 0) {
            double k = goFlash;
            double cx = boardX + boardW / 2;
            double cy = boardY + boardH * 0.30;
            Brush.centered(g, "ПОЕХАЛИ!", cx, cy, Theme.display((float) (28 + k * 6)),
                    Theme.alpha(Theme.ACCENT, (int) (255 * k)));
        }

        // «ты выбыл»
        if (deadBanner > 0.02 && phase == Protocol.PHASE_RUNNING) {
            int a = (int) (200 * deadBanner);
            g.setColor(new Color(0x18, 0x08, 0x0C, a));
            g.fill(new RoundRectangle2D.Double(boardX + boardW / 2 - 150, boardY + 16, 300, 38, 19, 19));
            Brush.centered(g, "Ты выбыл · наблюдаешь", boardX + boardW / 2, boardY + 41,
                    Theme.semibold(14), new Color(0xFF, 0xA0, 0xA0, Math.min(255, a + 40)));
        }

        // результаты
        if (phase == Protocol.PHASE_OVER && results != null) {
            g.setColor(new Color(0, 0, 0, 175));
            g.fillRect(0, 0, VW, VH);
            g.setColor(new Color(0x0C, 0x15, 0x24, 250));
            g.fill(Brush.round(340, 150, 600, 480, 26));
            Brush.panel(g, 340, 150, 600, 480, 26);
            Brush.centered(g, "ИГРА ОКОНЧЕНА", 640, 206, Theme.semibold(13), Theme.MUTED);

            String winner = "";
            for (Result r : results) {
                if (r.place == 1) winner = r.name;
            }
            Brush.glowText(g, winner.isEmpty() ? "Ничья" : winner, 640, 262, Theme.display(42),
                    Theme.TEXT, Theme.ACCENT, 4);
            Brush.centered(g, "побеждает", 640, 288, Theme.font(14), Theme.DIM);

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

        // меню по Esc
        if (pauseAnim > 0.01) {
            g.setColor(new Color(0, 0, 0, (int) (170 * pauseAnim)));
            g.fillRect(0, 0, VW, VH);
            Brush.panel(g, 440, 270, 400, 260, 24);
            Brush.centered(g, "Выйти из игры?", 640, 330, Theme.display(26), Theme.TEXT);
            Brush.centered(g, app.isHost() ? "Ты хост — сервер закроется для всех" : "Партия продолжится без тебя",
                    640, 358, Theme.font(13), Theme.MUTED);
        }
    }
}
