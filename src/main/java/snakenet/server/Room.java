package snakenet.server;

import snakenet.game.BotBrain;
import snakenet.game.Cell;
import snakenet.game.Food;
import snakenet.game.GameConfig;
import snakenet.game.SnakeState;
import snakenet.net.Msg;
import snakenet.net.Protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Игровая комната: лобби, отсчёт, раунд, результаты.
 * Вся логика тикает в одном потоке, публичные методы синхронизированы —
 * так сетевые колбэки и игровой цикл не гоняют состояние.
 */
public final class Room {

    public enum Phase { LOBBY, COUNT, RUNNING, OVER }

    public static final class Player {
        public final int id;
        public final String name;
        public final int color;
        public final boolean bot;
        public volatile boolean ready;
        public volatile Session session;
        public SnakeState snake;

        Player(int id, String name, int color, boolean bot, Session session) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.bot = bot;
            this.session = session;
        }
    }

    private static final String[] BOT_NAMES = {
            "Бот Вася", "Бот Люся", "Бот Гоша", "Бот Кобра",
            "Бот Питон", "Бот Удав", "Бот Гадюка", "Бот Спайк"
    };

    private static final int SPAWN_LEN = 4;

    private final GameServer server;
    private final GameConfig cfg;
    private final Random rnd = new Random();

    private final List<Player> players = new ArrayList<>();
    private final Map<Integer, Player> byId = new LinkedHashMap<>();
    private final List<Food> foods = new ArrayList<>();
    private final List<Msg> events = new ArrayList<>();

    private final ScheduledExecutorService loop;
    private Phase phase = Phase.LOBBY;
    private int tick;
    private int countLeft;
    private long countAt;
    private long lastTick;
    private int nextId = 1;
    private int botsAdded;
    private volatile boolean closed;

    private List<Msg> lastOver = List.of();

    public Room(GameServer server, GameConfig cfg) {
        this.server = server;
        this.cfg = cfg.copy();
        this.loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snake-room");
            t.setDaemon(true);
            return t;
        });
    }

    public GameConfig config() {
        return cfg;
    }

    public synchronized int playerCount() {
        return players.size();
    }

    public synchronized int humanCount() {
        int n = 0;
        for (Player p : players) if (!p.bot) n++;
        return n;
    }

    void start() {
        loop.scheduleAtFixedRate(this::step, 5, 5, TimeUnit.MILLISECONDS);
    }

    void stop() {
        closed = true;
        loop.shutdownNow();
    }

    // ------------------------------------------------------------------
    // подключение / отключение
    // ------------------------------------------------------------------

    synchronized void onJoin(Session session, String rawName, int color) {
        if (closed) return;
        if (players.size() >= cfg.maxPlayers) {
            session.error("Лобби заполнено (" + cfg.maxPlayers + " максимум)");
            session.close();
            return;
        }
        String name = GameConfig.sanitizeName(rawName);
        name = uniqueName(name);
        Player p = new Player(nextId++, name, Math.floorMod(color, 10), false, session);
        players.add(p);
        byId.put(p.id, p);
        session.playerId = p.id;

        List<Msg> hello = new ArrayList<>();
        hello.add(Msg.of(Protocol.WELCOME)
                .put("pid", p.id)
                .put("host", isHost(p) ? 1 : 0)
                .put("phase", phase.ordinal())
                .put("you", 1));
        cfg.write(hello.get(0));
        session.sendFrame(hello);

        if (phase == Phase.COUNT || phase == Phase.RUNNING) {
            session.sendFrame(startFrame());
        } else if (phase == Phase.OVER && !lastOver.isEmpty()) {
            session.sendFrame(lastOver);
        }

        broadcastLobby();
        system(name + " подключился");
    }

    synchronized void leave(int playerId) {
        Player p = byId.remove(playerId);
        if (p == null) return;
        players.remove(p);
        boolean wasAlive = p.snake != null && p.snake.alive;
        if (wasAlive) {
            Cell h = p.snake.head();
            if (h != null) {
                events.add(Msg.of(Protocol.EVENT).put("k", Protocol.EV_DIE)
                        .put("p", p.id).put("x", h.x).put("y", h.y));
            }
            p.snake.alive = false;
        }
        if (!p.bot) system(p.name + " отключился");
        broadcastLobby();
        if (phase == Phase.RUNNING) {
            checkEnd();
            if (phase == Phase.RUNNING) broadcastSnapshot();
        }
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

    public synchronized int hostId() {
        for (Player p : players) if (!p.bot) return p.id;
        return -1;
    }

    // ------------------------------------------------------------------
    // сообщения от клиентов
    // ------------------------------------------------------------------

    synchronized void onClientMessage(int playerId, Msg m) {
        Player p = byId.get(playerId);
        if (p == null) return;
        switch (m.type()) {
            case Protocol.DIR:
                if (p.snake != null && p.snake.alive) p.snake.queueDir(m.num("d", -1));
                break;
            case Protocol.READY:
                p.ready = m.bool("v");
                broadcastLobby();
                break;
            case Protocol.CHAT:
                chat(p, m.str("text"));
                break;
            case Protocol.SET:
                if (isHost(p)) applySetting(m.raw("k"), m.num("v", 0));
                break;
            case Protocol.BOT:
                if (isHost(p)) botCommand(m.raw("op"));
                break;
            case Protocol.START:
                if (isHost(p)) startCountdown();
                break;
            case Protocol.AGAIN:
                if (isHost(p) && phase == Phase.OVER) backToLobby();
                break;
            default:
                break;
        }
    }

    private void chat(Player from, String text) {
        String clean = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 200) clean = clean.substring(0, 200);
        Msg m = Msg.of(Protocol.CHAT).putStr("from", from.name).putStr("text", clean).put("kind", 0)
                .put("p", from.id).put("c", from.color);
        server.broadcast(List.of(m));
    }

    private void system(String text) {
        server.broadcast(List.of(Msg.of(Protocol.CHAT).putStr("from", "").putStr("text", text).put("kind", 1)));
    }

    private void applySetting(String key, int value) {
        if (phase != Phase.LOBBY) return;
        switch (key) {
            case "size":
                cfg.applySize(value);
                break;
            case "speed":
                cfg.applySpeed(value);
                break;
            case "walls":
                cfg.walls = value != 0;
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
            if (humanCount() + botCount() >= 8) return;
            String base = BOT_NAMES[botsAdded % BOT_NAMES.length];
            botsAdded++;
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

    private int botCount() {
        int n = 0;
        for (Player p : players) if (p.bot) n++;
        return n;
    }

    // ------------------------------------------------------------------
    // лобби / раунд
    // ------------------------------------------------------------------

    private void startCountdown() {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return;
        if (players.isEmpty()) return;
        resetRound();
        phase = Phase.COUNT;
        countLeft = 3;
        countAt = System.nanoTime() + 1_000_000_000L;
        server.broadcast(startFrame());
        broadcastLobby();
    }

    private List<Msg> startFrame() {
        Msg m = Msg.of(Protocol.START).put("phase", Protocol.PHASE_COUNT).put("n", 3);
        cfg.write(m);
        return new ArrayList<>(List.of(m));
    }

    private void resetRound() {
        tick = 0;
        foods.clear();
        events.clear();
        lastOver = List.of();
        int index = 0;
        for (Player p : players) {
            p.ready = false;
            p.snake = new SnakeState(p.id, p.name, p.color, p.bot);
            placeSnake(p.snake, index++, Math.max(1, players.size()));
        }
        int target = foodTarget();
        for (int i = 0; i < target; i++) spawnFood();
    }

    private void placeSnake(SnakeState s, int index, int total) {
        int cols = cfg.cols;
        int rows = cfg.rows;
        int x;
        int y;
        int dir;
        switch (index % 8) {
            case 0:
                x = cols / 4; y = rows / 2; dir = SnakeState.RIGHT; break;
            case 1:
                x = cols - 1 - cols / 4; y = rows / 2; dir = SnakeState.LEFT; break;
            case 2:
                x = cols / 2; y = rows / 4; dir = SnakeState.DOWN; break;
            case 3:
                x = cols / 2; y = rows - 1 - rows / 4; dir = SnakeState.UP; break;
            case 4:
                x = cols / 4; y = rows / 4; dir = SnakeState.DOWN; break;
            case 5:
                x = cols - 1 - cols / 4; y = rows / 4; dir = SnakeState.DOWN; break;
            case 6:
                x = cols / 4; y = rows - 1 - rows / 4; dir = SnakeState.UP; break;
            default:
                x = cols - 1 - cols / 4; y = rows - 1 - rows / 4; dir = SnakeState.UP; break;
        }
        x = Math.max(SPAWN_LEN, Math.min(cols - 1, x));
        y = Math.max(SPAWN_LEN, Math.min(rows - 1, y));
        s.place(x, y, dir, SPAWN_LEN);
    }

    private void beginRun() {
        phase = Phase.RUNNING;
        tick = 0;
        lastTick = System.nanoTime();
        broadcastSnapshot();
    }

    private void backToLobby() {
        phase = Phase.LOBBY;
        foods.clear();
        for (Player p : players) {
            p.snake = null;
            p.ready = false;
        }
        broadcastLobby();
        system("Возвращаемся в лобби");
    }

    private void endGame() {
        phase = Phase.OVER;
        foods.clear();
        List<Player> ranked = new ArrayList<>();
        for (Player p : players) if (p.snake != null) ranked.add(p);
        ranked.sort(Comparator.comparingInt((Player p) -> p.snake.score).reversed());

        List<Msg> frame = new ArrayList<>();
        frame.add(Msg.of(Protocol.OVER).put("n", ranked.size()));
        int place = 1;
        for (Player p : ranked) {
            Msg r = Msg.of(Protocol.RESULT)
                    .put("p", p.id)
                    .put("sc", p.snake.score)
                    .put("place", place++)
                    .put("c", p.color)
                    .put("a", p.snake.alive ? 1 : 0);
            r.putStr("name", p.name);
            frame.add(r);
        }
        lastOver = frame;
        server.broadcast(frame);
    }

    private int foodTarget() {
        return Math.max(3, Math.min(9, players.size() + 2));
    }

    private void spawnFood() {
        int cols = cfg.cols;
        int rows = cfg.rows;
        for (int attempt = 0; attempt < 300; attempt++) {
            int x = rnd.nextInt(cols);
            int y = rnd.nextInt(rows);
            if (!freeCell(x, y)) continue;
            foods.add(new Food(x, y, rnd.nextInt(4)));
            return;
        }
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                if (freeCell(x, y)) {
                    foods.add(new Food(x, y, rnd.nextInt(4)));
                    return;
                }
            }
        }
    }

    private boolean freeCell(int x, int y) {
        for (Food f : foods) if (f.x == x && f.y == y) return false;
        for (Player p : players) {
            if (p.snake == null || !p.snake.alive) continue;
            for (Cell c : p.snake.body) if (c.x == x && c.y == y) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // игровой цикл
    // ------------------------------------------------------------------

    private void step() {
        if (closed) return;
        long now = System.nanoTime();
        try {
            synchronized (this) {
                if (closed) return;
                if (phase == Phase.COUNT) {
                    while (now >= countAt) {
                        countLeft--;
                        if (countLeft <= 0) {
                            beginRun();
                            break;
                        }
                        server.broadcast(List.of(Msg.of(Protocol.COUNT).put("n", countLeft)));
                        countAt += 1_000_000_000L;
                    }
                } else if (phase == Phase.RUNNING) {
                    long period = cfg.tickMs * 1_000_000L;
                    int guard = 0;
                    while (now - lastTick >= period && guard++ < 3) {
                        lastTick += period;
                        gameTick();
                    }
                    if (now - lastTick > 400_000_000L) lastTick = now;
                }
            }
        } catch (Throwable t) {
            // один сбойный тик не должен убивать комнату
            System.err.println("[room] tick error: " + t);
        }
    }

    private void gameTick() {
        tick++;
        events.clear();

        // 1. повороты игроков
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive) continue;
            Integer d = s.inputs.pollFirst();
            if (d != null && !SnakeState.isOpposite(d, s.dir)) s.dir = d;
        }

        Set<Integer> occupiedAll = occupancy(true);
        // 2. боты
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive || !s.bot) continue;
            int d = BotBrain.decide(s, cfg.cols, cfg.rows, cfg.walls, occupiedAll, foods, rnd);
            if (!SnakeState.isOpposite(d, s.dir)) s.dir = d;
        }

        // 3. новые головы
        Map<Player, Cell> newHeads = new IdentityHashMap<>();
        Set<Player> dying = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive) continue;
            Cell h = s.head();
            if (h == null) continue;
            int nx = h.x + SnakeState.DX[s.dir];
            int ny = h.y + SnakeState.DY[s.dir];
            if (nx < 0 || ny < 0 || nx >= cfg.cols || ny >= cfg.rows) {
                if (cfg.walls) {
                    dying.add(p);
                    continue;
                }
                nx = Math.floorMod(nx, cfg.cols);
                ny = Math.floorMod(ny, cfg.rows);
            }
            newHeads.put(p, new Cell(nx, ny));
        }

        // 4. занятые клетки (хвост уезжает, если змейка не растёт)
        Set<Integer> occupied = occupancy(false);

        Map<Integer, Integer> headCount = new HashMap<>();
        for (Cell c : newHeads.values()) headCount.merge(c.key(cfg.cols), 1, Integer::sum);

        for (Map.Entry<Player, Cell> e : newHeads.entrySet()) {
            Cell c = e.getValue();
            int key = c.key(cfg.cols);
            if (occupied.contains(key)) dying.add(e.getKey());
            if (headCount.getOrDefault(key, 0) > 1) dying.add(e.getKey());
            boolean foodHere = false;
            for (Food f : foods) if (f.x == c.x && f.y == c.y) { foodHere = true; break; }
            if (foodHere) {
                SnakeState s = e.getKey().snake;
                s.score += 10;
                s.grow += 2;
                events.add(Msg.of(Protocol.EVENT).put("k", Protocol.EV_EAT)
                        .put("p", e.getKey().id).put("x", c.x).put("y", c.y));
                foods.removeIf(f -> f.x == c.x && f.y == c.y);
            }
        }

        // 5. движение выживших
        for (Map.Entry<Player, Cell> e : newHeads.entrySet()) {
            if (dying.contains(e.getKey())) continue;
            SnakeState s = e.getKey().snake;
            s.body.addFirst(e.getValue());
            if (s.grow > 0) {
                s.grow--;
            } else {
                s.body.removeLast();
            }
        }

        // 6. смерти
        for (Player p : dying) kill(p);

        // 7. еда
        while (foods.size() < foodTarget()) spawnFood();

        // 8. рассылка
        broadcastSnapshot();

        // 9. конец раунда
        checkEnd();
    }

    private Set<Integer> occupancy(boolean includeMovingTails) {
        Set<Integer> set = new HashSet<>();
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive) continue;
            boolean skipTail = !includeMovingTails && s.grow == 0 && s.body.size() > 1;
            int i = 0;
            int n = s.body.size();
            for (Cell c : s.body) {
                if (!(skipTail && i == n - 1)) set.add(c.key(cfg.cols));
                i++;
            }
        }
        return set;
    }

    private void kill(Player p) {
        SnakeState s = p.snake;
        if (s == null || !s.alive) return;
        s.alive = false;
        Cell h = s.head();
        if (h != null) {
            events.add(Msg.of(Protocol.EVENT).put("k", Protocol.EV_DIE)
                    .put("p", p.id).put("x", h.x).put("y", h.y));
        }
        int dropped = 0;
        for (Cell c : s.body) {
            if (dropped >= 3) break;
            if (freeCell(c.x, c.y) && rnd.nextFloat() < 0.5f) {
                foods.add(new Food(c.x, c.y, 2));
                dropped++;
            }
        }
    }

    private void checkEnd() {
        int participants = 0;
        int alive = 0;
        for (Player p : players) {
            if (p.snake == null) continue;
            participants++;
            if (p.snake.alive) alive++;
        }
        if (participants == 0) return;
        int threshold = participants > 1 ? 1 : 0;
        if (alive <= threshold) endGame();
    }

    // ------------------------------------------------------------------
    // рассылки
    // ------------------------------------------------------------------

    private void broadcastSnapshot() {
        List<Msg> frame = new ArrayList<>();
        frame.add(Msg.of(Protocol.SNAP).put("t", tick).put("ms", cfg.tickMs));
        for (Food f : foods) {
            frame.add(Msg.of(Protocol.FOOD).put("x", f.x).put("y", f.y).put("k", f.kind));
        }
        frame.addAll(events);
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null) continue;
            Msg m = Msg.of(Protocol.SNAKE)
                    .put("p", p.id)
                    .put("a", s.alive ? 1 : 0)
                    .put("sc", s.score)
                    .put("d", s.dir)
                    .put("c", p.color);
            StringBuilder sb = new StringBuilder(s.body.size() * 7);
            for (Cell c : s.body) {
                if (sb.length() > 0) sb.append(',');
                sb.append(c.x).append(':').append(c.y);
            }
            m.put("g", sb.toString());
            frame.add(m);
        }
        server.broadcast(frame);
        events.clear();
    }

    void broadcastLobby() {
        List<Msg> frame = new ArrayList<>();
        Msg head = Msg.of(Protocol.LOBBY)
                .put("phase", phase.ordinal())
                .put("host", hostId())
                .put("tick", tick);
        cfg.write(head);
        frame.add(head);
        for (Player p : players) {
            Msg m = Msg.of(Protocol.PLAYER)
                    .put("id", p.id)
                    .put("c", p.color)
                    .put("ready", p.ready ? 1 : 0)
                    .put("bot", p.bot ? 1 : 0)
                    .put("host", isHost(p) ? 1 : 0)
                    .put("alive", p.snake == null ? 1 : (p.snake.alive ? 1 : 0));
            m.putStr("name", p.name);
            frame.add(m);
        }
        server.broadcast(frame);
    }
}
