package snakenet.server;

import netcore.time.TickConfig;
import snakenet.game.BotBrain;
import snakenet.game.Cell;
import snakenet.game.Food;
import snakenet.game.GameConfig;
import snakenet.game.SnakeState;
import snakenet.net.Msg;
import snakenet.net.Protocol;
import snakenet.net.Wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Игровая комната поверх движка: лобби, отсчёт, раунд, итоги.
 *
 * <p>Симуляция живёт в {@link netcore.time.TickRunner}: частота задаётся
 * настройками (16..128), скорость змейки — в клетках в секунду, поэтому
 * тикрейт не влияет на геймплей, только на отзывчивость.</p>
 */
public final class Room {

    public enum Phase { LOBBY, COUNT, RUNNING, OVER }

    /** Ввод клиента: номер, тик, доля тика, направление. */
    private static final class Input {
        final int seq;
        final int tick;
        final float sub;
        final int dir;

        Input(int seq, int tick, float sub, int dir) {
            this.seq = seq;
            this.tick = tick;
            this.sub = sub;
            this.dir = dir;
        }
    }

    public static final class Player {
        public final int id;
        public final String name;
        public final int color;
        public final boolean bot;
        public volatile boolean ready;
        public volatile netcore.net.Connection connection;
        SnakeState snake;
        int place;

        final List<Input> inputs = new ArrayList<>();
        int ackSeq;

        Player(int id, String name, int color, boolean bot, netcore.net.Connection connection) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.bot = bot;
            this.connection = connection;
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

    private netcore.time.TickRunner runner;
    private Phase phase = Phase.LOBBY;
    private int tick;
    private int countTicksLeft;
    private int lastCountShown = -1;
    private int nextId = 1;
    private int botsAdded;
    private volatile boolean closed;

    private List<Msg> lastOver = List.of();
    private int snapshotEvery = 1;

    Room(GameServer server, GameConfig cfg) {
        this.server = server;
        this.cfg = cfg.copy();
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

    public TickConfig tickConfig() {
        return runner == null ? cfg.tickConfig() : runner.config();
    }

    public int tick() {
        return tick;
    }

    void start() {
        runner = new netcore.time.TickRunner(cfg.tickConfig(), this::onTick);
        snapshotEvery = Math.max(1, Math.round(cfg.tickRate / 30f));
        runner.start();
    }

    void stop() {
        closed = true;
        if (runner != null) runner.stop();
    }

    private void onTick(int tickNumber, double lagMs) {
        synchronized (this) {
            if (closed) return;
            tick = tickNumber;
            try {
                if (phase == Phase.COUNT) countdownTick();
                else if (phase == Phase.RUNNING) gameTick();
            } catch (Throwable t) {
                System.err.println("[room] tick error: " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // подключение / отключение
    // ------------------------------------------------------------------

    synchronized void onConnect(netcore.net.Connection connection) {
        // игрок появится после HELLO
    }

    synchronized void onDisconnect(netcore.net.Connection connection) {
        Player p = connection.attachment instanceof Player ? (Player) connection.attachment : null;
        if (p != null) leave(p.id);
    }

    synchronized void onJoin(netcore.net.Connection connection, String rawName, int color) {
        if (closed) return;
        if (players.size() >= cfg.maxPlayers) {
            server.send(connection, Wire.encode(List.of(
                    Msg.of(Protocol.ERR).putStr("msg", "Лобби заполнено"))));
            connection.close();
            return;
        }
        String name = uniqueName(GameConfig.sanitizeName(rawName));
        Player p = new Player(nextId++, name, Math.floorMod(color, 10), false, connection);
        players.add(p);
        byId.put(p.id, p);
        connection.attachment = p;

        List<Msg> hello = new ArrayList<>();
        hello.add(Msg.of(Protocol.WELCOME)
                .put("pid", p.id)
                .put("host", isHost(p) ? 1 : 0)
                .put("phase", phase.ordinal())
                .put("rate", cfg.tickRate));
        cfg.write(hello.get(0));
        server.send(connection, Wire.encode(hello));

        if (phase == Phase.COUNT || phase == Phase.RUNNING) {
            server.send(connection, Wire.encode(startFrame()));
        } else if (phase == Phase.OVER && !lastOver.isEmpty()) {
            server.send(connection, Wire.encode(lastOver));
        }

        broadcastLobby();
        system(name + " подключился");
    }

    synchronized void leave(int playerId) {
        Player p = byId.remove(playerId);
        if (p == null) return;
        players.remove(p);
        if (p.connection != null) p.connection.attachment = null;
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

    synchronized void onMessage(netcore.net.Connection connection, List<Msg> frame) {
        boolean restart = handleMessages(connection, frame);
        // перезапуск тика — только после выхода из-под замка комнаты,
        // иначе поток тика не сможет войти в onTick и stop() будет ждать
        if (restart) {
            restartRunner();
        }
    }

    /** @return true, если нужно перезапустить цикл тиков (сменился тикрейт) */
    private boolean handleMessages(netcore.net.Connection connection, List<Msg> frame) {
        Player p = connection.attachment instanceof Player ? (Player) connection.attachment : null;
        boolean restart = false;
        for (Msg m : frame) {
            if (m.is(Protocol.HELLO)) {
                if (p == null) onJoin(connection, m.str("name"), m.num("color", 0));
                continue;
            }
            if (p == null) continue;
            switch (m.type()) {
                case Protocol.IN:
                    if (p.snake != null && p.snake.alive) {
                        p.inputs.add(new Input(m.num("n", 0), m.num("t", tick),
                                (float) Double.parseDouble(m.raw("s").isEmpty() ? "0" : m.raw("s")),
                                m.num("d", -1)));
                    }
                    break;
                case Protocol.READY:
                    p.ready = m.bool("v");
                    broadcastLobby();
                    break;
                case Protocol.CHAT:
                    chat(p, m.str("text"));
                    break;
                case Protocol.SET:
                    if (isHost(p)) restart |= applySetting(m.raw("k"), m.num("v", 0));
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
        return restart;
    }

    private void chat(Player from, String text) {
        String clean = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 200) clean = clean.substring(0, 200);
        Msg m = Msg.of(Protocol.CHAT).putStr("from", from.name).putStr("text", clean).put("kind", 0)
                .put("p", from.id).put("c", from.color);
        server.broadcast(Wire.encode(List.of(m)));
    }

    private void system(String text) {
        server.broadcast(Wire.encode(List.of(
                Msg.of(Protocol.CHAT).putStr("from", "").putStr("text", text).put("kind", 1))));
    }

    private boolean applySetting(String key, int value) {
        if (phase != Phase.LOBBY) return false;
        boolean restart = false;
        switch (key) {
            case "size":
                cfg.applySize(value);
                break;
            case "speed":
                cfg.applySpeed(value);
                break;
            case "rate":
                cfg.applyTickRate(value);
                restart = true;
                break;
            case "subtick":
                cfg.subtick = value != 0;
                restart = true;
                break;
            case "walls":
                cfg.walls = value != 0;
                break;
            default:
                return false;
        }
        broadcastLobby();
        return restart;
    }

    /** Смена тикрейта перезапускает цикл — в лобби это безопасно. */
    private void restartRunner() {
        netcore.time.TickRunner old;
        synchronized (this) {
            old = runner;
            runner = null;
        }
        if (old != null) old.stop();
        synchronized (this) {
            if (closed) return;
            tick = 0;
            snapshotEvery = Math.max(1, Math.round(cfg.tickRate / 30f));
            runner = new netcore.time.TickRunner(cfg.tickConfig(), this::onTick);
            runner.start();
        }
    }
    private void botCommand(String op) {
        if (phase != Phase.LOBBY) return;
        if ("add".equals(op)) {
            if (players.size() >= cfg.maxPlayers) return;
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

    // ------------------------------------------------------------------
    // лобби / раунд
    // ------------------------------------------------------------------

    private void startCountdown() {
        if (phase != Phase.LOBBY && phase != Phase.OVER) return;
        if (players.isEmpty()) return;
        resetRound();
        phase = Phase.COUNT;
        countTicksLeft = 3 * cfg.tickRate;
        lastCountShown = 4;
        server.broadcast(Wire.encode(startFrame()));
        server.broadcast(Wire.encode(List.of(Msg.of(Protocol.COUNT).put("n", 3))));
        lastCountShown = 3;
        broadcastLobby();
    }

    private List<Msg> startFrame() {
        Msg m = Msg.of(Protocol.START).put("phase", Protocol.PHASE_COUNT).put("n", 3);
        cfg.write(m);
        return new ArrayList<>(List.of(m));
    }

    private void countdownTick() {
        countTicksLeft--;
        int seconds = (int) Math.ceil(countTicksLeft / (double) cfg.tickRate);
        if (seconds > 0 && seconds < lastCountShown) {
            lastCountShown = seconds;
            server.broadcast(Wire.encode(List.of(Msg.of(Protocol.COUNT).put("n", seconds))));
        }
        if (countTicksLeft <= 0) beginRun();
    }

    private void resetRound() {
        tick = 0;
        foods.clear();
        events.clear();
        lastOver = List.of();
        int index = 0;
        for (Player p : players) {
            p.ready = false;
            p.inputs.clear();
            p.ackSeq = 0;
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
        countTicksLeft = 0;
        tick = 0;
        broadcastSnapshot();
    }

    private void backToLobby() {
        phase = Phase.LOBBY;
        foods.clear();
        events.clear();
        for (Player p : players) {
            p.snake = null;
            p.ready = false;
            p.inputs.clear();
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
        server.broadcast(Wire.encode(frame));
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
    // игровой тик
    // ------------------------------------------------------------------

    private void gameTick() {
        events.clear();

        // 1. ввод игроков: применяем всё, что «дозрело» до этого тика
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive) {
                p.inputs.clear();
                continue;
            }
            Input chosen = null;
            Iterator<Input> it = p.inputs.iterator();
            while (it.hasNext()) {
                Input in = it.next();
                if (in.tick > tick) continue;
                if (chosen == null
                        || in.tick > chosen.tick
                        || (in.tick == chosen.tick && in.sub >= chosen.sub)) {
                    chosen = in;
                }
                p.ackSeq = Math.max(p.ackSeq, in.seq);
                it.remove();
            }
            if (chosen != null && !SnakeState.isOpposite(chosen.dir, s.dir)) {
                s.dir = chosen.dir;
            }
        }

        // 2. боты
        Set<Integer> occupiedForBots = occupancy(Collections.emptySet());
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive || !s.bot) continue;
            int d = BotBrain.decide(s, cfg.cols, cfg.rows, cfg.walls, occupiedForBots, foods, rnd);
            if (!SnakeState.isOpposite(d, s.dir)) s.dir = d;
        }

        // 3. кто двигается в этом тике (накопитель долей клетки)
        List<Player> movers = new ArrayList<>();
        int units = cfg.unitsPerTick();
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive) continue;
            s.acc += units;
            if (s.acc >= SnakeState.UNITS) {
                s.acc -= SnakeState.UNITS;
                movers.add(p);
            }
        }
        if (!movers.isEmpty()) {
            applyMovement(movers);
        }

        while (foods.size() < foodTarget()) spawnFood();

        if (tick % snapshotEvery == 0) broadcastSnapshot();
        checkEnd();
    }

    private void applyMovement(List<Player> movers) {
        Map<Player, Cell> newHeads = new IdentityHashMap<>();
        Set<Player> dying = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Player p : movers) {
            SnakeState s = p.snake;
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

        Set<Integer> occupied = occupancy(new HashSet<>(movers));
        Map<Integer, Integer> headCount = new HashMap<>();
        for (Cell c : newHeads.values()) headCount.merge(c.key(cfg.cols), 1, Integer::sum);

        for (Map.Entry<Player, Cell> e : newHeads.entrySet()) {
            Cell c = e.getValue();
            int key = c.key(cfg.cols);
            if (occupied.contains(key)) dying.add(e.getKey());
            if (headCount.getOrDefault(key, 0) > 1) dying.add(e.getKey());
            boolean foodHere = false;
            for (Food f : foods) {
                if (f.x == c.x && f.y == c.y) {
                    foodHere = true;
                    break;
                }
            }
            if (foodHere) {
                SnakeState s = e.getKey().snake;
                s.score += 10;
                s.grow += 2;
                events.add(Msg.of(Protocol.EVENT).put("k", Protocol.EV_EAT)
                        .put("p", e.getKey().id).put("x", c.x).put("y", c.y));
                foods.removeIf(f -> f.x == c.x && f.y == c.y);
            }
        }

        for (Map.Entry<Player, Cell> e : newHeads.entrySet()) {
            if (dying.contains(e.getKey())) continue;
            SnakeState s = e.getKey().snake;
            s.body.addFirst(e.getValue());
            if (s.grow > 0) s.grow--;
            else s.body.removeLast();
        }

        for (Player p : dying) kill(p);
    }

    /** Занятые клетки; хвосты змей, которые двигаются и не растут, освобождаются. */
    private Set<Integer> occupancy(Set<Player> movers) {
        Set<Integer> set = new HashSet<>();
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive) continue;
            boolean skipTail = s.grow == 0 && movers.contains(p) && s.body.size() > 1;
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
        frame.add(Msg.of(Protocol.SNAP)
                .put("t", tick)
                .put("st", System.nanoTime())
                .put("rate", cfg.tickRate)
                .put("ms", cfg.unitsPerTick()));
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
                    .put("c", p.color)
                    .put("ac", s.acc)
                    .put("ls", p.ackSeq);
            StringBuilder sb = new StringBuilder(s.body.size() * 7);
            for (Cell c : s.body) {
                if (sb.length() > 0) sb.append(',');
                sb.append(c.x).append(':').append(c.y);
            }
            m.put("g", sb.toString());
            frame.add(m);
        }
        server.broadcastUnreliable(Wire.encode(frame));
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
        server.broadcast(Wire.encode(frame));
    }
}
