package snakenet.ui;

import snakenet.audio.Sfx;
import snakenet.client.Snapshot;
import snakenet.game.GameConfig;
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
    private final List<Particle> particles = new ArrayList<>();
    private final List<FloatText> floats = new ArrayList<>();
    private List<Result> results;

    private GameConfig cfg = new GameConfig();
    private int cols = 32;
    private int rows = 22;
    private int tickMs = 100;
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
                if (pauseMenu) return;
                app.send(Msg.of(Protocol.DIR).put("d", dir));
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
        tickMs = cfg.tickMs;
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
        bAgain.visible = false;
        bLobby.visible = false;
        bResume.visible = false;
        bQuit.visible = false;
        Sfx.play(Sfx.Sound.START);
    }

    private void applySnapshot(List<Msg> frame) {
        Snapshot snap = Snapshot.parse(frame, System.nanoTime());
        if (snap == null) return;
        boolean first = cur == null;
        prev = cur;
        cur = snap;
        tickMs = Math.max(40, snap.tickMs);
        if (phase != Protocol.PHASE_RUNNING) {
            phase = Protocol.PHASE_RUNNING;
            countdown = 0;
            if (first || goFlash <= 0) goFlash = 1;
        }
        for (Snapshot.EventView e : snap.events) handleEvent(e);
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

        long now = System.nanoTime();
        if (now - lastPingAt > 1_000_000_000L && app.client() != null) {
            lastPingAt = now;
            app.client().ping();
        }
    }

    private double alpha() {
        if (cur == null) return 0;
        double a = (System.nanoTime() - cur.recvNanos) / (tickMs * 1_000_000.0);
        return Math.max(0, Math.min(1, a));
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
        paintOverlays(g);
    }

    private void paintTopBar(Graphics2D g) {
        Brush.text(g, "SNAKE NET", 56, 74, Theme.display(17), Theme.TEXT);
        g.setColor(Theme.ACCENT);
        g.fill(new Ellipse2D.Double(170, 63, 7, 7));

        String info = cols + "×" + rows + "  ·  " + Math.round(1000f / tickMs) + " шагов/с  ·  "
                + (cfg.walls ? "стены" : "сквозь стены");
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
        Shape outer = Brush.round(boardX - 12, boardY - 12, boardW + 24, boardH + 24, 22);
        Brush.glow(g, outer, new Color(0x3D, 0xDC, 0xFF), 4, 12);
        g.setColor(new Color(0x07, 0x0C, 0x15, 255));
        g.fill(outer);
        g.setColor(new Color(0x17, 0x27, 0x3E));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(outer);

        g.setColor(new Color(255, 255, 255, 7));
        g.setStroke(new BasicStroke(1f));
        for (int x = 1; x < cols; x++) {
            double px = boardX + x * cell;
            g.draw(new Line2D.Double(px, boardY, px, boardY + boardH));
        }
        for (int y = 1; y < rows; y++) {
            double py = boardY + y * cell;
            g.draw(new Line2D.Double(boardX, py, boardX + boardW, py));
        }
        g.setColor(new Color(0x1C, 0x2E, 0x47));
        g.draw(new Rectangle2D.Double(boardX, boardY, boardW, boardH));

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
                return new Color(0xFF, 0x8A, 0x4C);
            case 3:
                return new Color(0xA7, 0x8B, 0xFA);
            default:
                return Theme.YELLOW;
        }
    }

    private void paintFood(Graphics2D g) {
        for (Snapshot.FoodView f : cur.food) {
            double cx = boardX + (f.x + 0.5) * cell;
            double cy = boardY + (f.y + 0.5) * cell;
            double pulse = 1 + 0.13 * Math.sin(time * 4 + f.x * 1.7 + f.y * 0.9);
            Color c = foodColor(f.kind);
            double halo = cell * 0.66 * pulse;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 46));
            g.fill(new Ellipse2D.Double(cx - halo, cy - halo, halo * 2, halo * 2));
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
            double mid = cell * 0.38 * pulse;
            g.fill(new Ellipse2D.Double(cx - mid, cy - mid, mid * 2, mid * 2));
            double r = cell * 0.27 * pulse;
            g.setColor(c);
            g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            g.setColor(new Color(255, 255, 255, 220));
            double core = r * 0.45;
            g.fill(new Ellipse2D.Double(cx - core * 0.4, cy - core * 0.7, core, core));
        }
    }

    private double[] interpolated(Snapshot.SnakeView s, double a) {
        int n = s.length();
        double[] out = new double[n * 2];
        Snapshot.SnakeView p = prev == null ? null : prev.snake(s.id);
        for (int i = 0; i < n; i++) {
            double x = s.xs[i];
            double y = s.ys[i];
            if (p != null && i < p.length()) {
                double dx = x - p.xs[i];
                double dy = y - p.ys[i];
                if (Math.abs(dx) <= 1.5 && Math.abs(dy) <= 1.5) {
                    x = p.xs[i] + dx * a;
                    y = p.ys[i] + dy * a;
                }
            }
            out[i * 2] = x;
            out[i * 2 + 1] = y;
        }
        return out;
    }

    private void paintSnakes(Graphics2D g) {
        double a = alpha();
        List<Snapshot.SnakeView> list = new ArrayList<>(cur.snakes);
        list.sort((x, y) -> Boolean.compare(x.alive, y.alive));

        for (Snapshot.SnakeView s : list) {
            if (s.length() == 0) continue;
            Color base = Theme.player(s.color);
            Color color = s.alive ? base : Theme.mix(base, Theme.BG, 0.45f);
            double[] pts = interpolated(s, a);
            int n = pts.length / 2;

            double[] px = new double[n];
            double[] py = new double[n];
            for (int i = 0; i < n; i++) {
                px[i] = boardX + (pts[i * 2] + 0.5) * cell;
                py[i] = boardY + (pts[i * 2 + 1] + 0.5) * cell;
            }

            int glowAlpha = s.alive ? 40 : 10;
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), glowAlpha));
            g.setStroke(new BasicStroke((float) (cell * 1.02), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < n; i++) {
                if (Math.abs(pts[i * 2] - pts[(i - 1) * 2]) > 1.5 || Math.abs(pts[i * 2 + 1] - pts[(i - 1) * 2 + 1]) > 1.5) continue;
                g.draw(new Line2D.Double(px[i - 1], py[i - 1], px[i], py[i]));
            }

            g.setColor(color);
            g.setStroke(new BasicStroke((float) (cell * 0.70), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < n; i++) {
                if (Math.abs(pts[i * 2] - pts[(i - 1) * 2]) > 1.5 || Math.abs(pts[i * 2 + 1] - pts[(i - 1) * 2 + 1]) > 1.5) continue;
                g.draw(new Line2D.Double(px[i - 1], py[i - 1], px[i], py[i]));
            }

            g.setColor(new Color(
                    Math.min(255, color.getRed() + 70),
                    Math.min(255, color.getGreen() + 70),
                    Math.min(255, color.getBlue() + 70),
                    s.alive ? 110 : 30));
            g.setStroke(new BasicStroke((float) (cell * 0.24), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < n; i++) {
                if (Math.abs(pts[i * 2] - pts[(i - 1) * 2]) > 1.5 || Math.abs(pts[i * 2 + 1] - pts[(i - 1) * 2 + 1]) > 1.5) continue;
                g.draw(new Line2D.Double(px[i - 1], py[i - 1], px[i], py[i]));
            }

            // голова
            double hx = px[0];
            double hy = py[0];
            double hr = cell * 0.40;
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), s.alive ? 90 : 24));
            g.fill(new Ellipse2D.Double(hx - hr * 1.35, hy - hr * 1.35, hr * 2.7, hr * 2.7));
            g.setColor(color);
            g.fill(new Ellipse2D.Double(hx - hr, hy - hr, hr * 2, hr * 2));

            if (s.alive) {
                double dx = snakenet.game.SnakeState.DX[s.dir];
                double dy = snakenet.game.SnakeState.DY[s.dir];
                double eyeX = hx + dx * cell * 0.10;
                double eyeY = hy + dy * cell * 0.10;
                double perpX = -dy * cell * 0.16;
                double perpY = dx * cell * 0.16;
                double er = Math.max(1.6, cell * 0.085);
                g.setColor(new Color(0x05, 0x10, 0x0C));
                g.fill(new Ellipse2D.Double(eyeX + perpX - er, eyeY + perpY - er, er * 2, er * 2));
                g.fill(new Ellipse2D.Double(eyeX - perpX - er, eyeY - perpY - er, er * 2, er * 2));
                g.setColor(new Color(255, 255, 255, 210));
                double tr = er * 0.45;
                g.fill(new Ellipse2D.Double(eyeX + perpX - tr - 0.6, eyeY + perpY - tr - 0.6, tr * 2, tr * 2));
                g.fill(new Ellipse2D.Double(eyeX - perpX - tr - 0.6, eyeY - perpY - tr - 0.6, tr * 2, tr * 2));
            }

            // подпись своего ника над головой
            if (s.id == app.myId()) {
                Brush.text(g, "ты", hx + cell * 0.5, hy - cell * 0.55, Theme.semibold(10), Theme.alpha(Theme.ACCENT, 200));
            }
        }
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

            g.setColor(new Color(0x1B, 0x2A, 0x40));
            g.fill(new RoundRectangle2D.Double(1052, y + 28, 154, 4, 4, 4));
            g.setColor(Theme.alpha(c, s.alive ? 220 : 90));
            g.fill(new RoundRectangle2D.Double(1052, y + 28, 154 * (s.score / (double) max), 4, 4, 4));

            if (s.id == app.myId()) {
                g.setColor(Theme.alpha(Theme.ACCENT, 140));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new RoundRectangle2D.Double(1012, y, 200, 44, 12, 12));
            }
        }

        long ping = app.client() == null ? -1 : app.client().latencyMs();
        String pingText = ping < 0 ? "—" : ping + " мс";
        Brush.text(g, "ПИНГ", 1022, 654, Theme.semibold(10), Theme.MUTED);
        Brush.text(g, pingText, 1022, 674, Theme.font(13), Theme.DIM);
        Brush.text(g, "FPS", 1090, 654, Theme.semibold(10), Theme.MUTED);
        Brush.text(g, String.valueOf(win.fps()), 1090, 674, Theme.font(13), Theme.DIM);
        Brush.text(g, "ESC — меню", 1022, 704, Theme.font(12), Theme.MUTED);
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
            g.setColor(new Color(0x05, 0x12, 0x0C, (int) (170 * k)));
            g.fill(new RoundRectangle2D.Double(cx - 150, cy - 38, 300, 54, 27, 27));
            Brush.glowText(g, "ПОЕХАЛИ!", cx, cy + 4,
                    Theme.display((float) (26 + k * 5)), Theme.alpha(Theme.ACCENT, (int) (255 * k)), Theme.ACCENT, 4);
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
