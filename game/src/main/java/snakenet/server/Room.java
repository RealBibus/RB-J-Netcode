package snakenet.server;

import netcore.time.TickConfig;
import netcore.voice.VoiceGuard;
import netcore.voice.VoicePacket;
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








public final class Room {

    public enum Phase { LOBBY, COUNT, RUNNING, OVER }

    
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
        VoiceGuard voiceGuard;
        int lastInputSeq;
        long inputWindowNanos;
        int inputWindowCount;
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

    private static final int SPAWN_LEN = 4;
    private static final long RECONNECT_GRACE_MS = 30_000L;

    private final GameServer server;
    private final GameConfig cfg;
    private final Random rnd = new Random();

    private final List<Player> players = new ArrayList<>();
    private final Map<Integer, Player> byId = new LinkedHashMap<>();
    private final Map<String, Player> byToken = new HashMap<>();
    private final List<Food> foods = new ArrayList<>();
    private final List<Msg> events = new ArrayList<>();

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

    private List<Msg> lastOver = List.of();
    private int snapshotEvery = 1;

    private int subIn;
    private int subApplied;
    private int subMerged;
    private int subSameTick;
    private int subOrderErrors;
    private int subLastPermille;
    private final int[] subBuckets = new int[16];

    private long tickOriginNanos;
    private long voiceAccepted;
    private long voiceRejected;

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
            lastRunnerTick = tickNumber;
            tick = tickBase + tickNumber;
            long period = 1_000_000_000L / Math.max(1, cfg.tickRate);
            tickOriginNanos = System.nanoTime() - (long) tick * period;
            try {
                expireReconnects();
                if (phase == Phase.COUNT) countdownTick();
                else if (phase == Phase.RUNNING) gameTick();
            } catch (Throwable t) {
                System.err.println("[room] tick error: " + t);
            }
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

    
    
    

    synchronized void onConnect(netcore.net.Connection connection) {
        
    }

    synchronized void onDisconnect(netcore.net.Connection connection) {
        Player p = connection.attachment instanceof Player ? (Player) connection.attachment : null;
        if (p == null || p.connection != connection) return;
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

    synchronized void onJoin(netcore.net.Connection connection, String rawName, int color, String token) {
        if (closed) return;
        Player attached = connection.attachment instanceof Player ? (Player) connection.attachment : null;
        if (attached != null) {
            
            
            resendWelcome(connection, attached);
            return;
        }
        if (token != null && !token.isEmpty()) {
            Player old = byToken.get(token);
            if (old != null) {
                if (old.connection == connection) {
                    connection.attachment = old;
                    resendWelcome(connection, old);
                } else {
                    resume(old, connection);
                }
                return;
            }
        }
        if (players.size() >= cfg.maxPlayers) {
            server.send(connection, Wire.encode(List.of(
                    Msg.of(Protocol.ERR).putStr("msg", "Lobby is full"))));
            connection.close();
            return;
        }
        String name = uniqueName(GameConfig.sanitizeName(rawName));
        Player p = new Player(nextId++, name, Math.floorMod(color, 10), false, connection);
        p.voiceGuard = new VoiceGuard(cfg.voiceConfig());
        players.add(p);
        byId.put(p.id, p);
        p.token = token == null ? "" : token;
        if (!p.token.isEmpty()) byToken.put(p.token, p);
        connection.attachment = p;

        resendWelcome(connection, p);
        broadcastLobby();
        system(name + " joined");
    }

    




    private void resume(Player p, netcore.net.Connection connection) {
        netcore.net.Connection prev = p.connection;
        if (prev != null && prev.attachment == p) prev.attachment = null;
        p.connection = connection;
        p.reconnecting = false;
        p.reconnectDeadline = 0;
        connection.attachment = p;
        p.inputs.clear();
        p.ackSeq = 0;
        p.lastInputSeq = 0;
        p.voiceGuard = null;

        if (prev != null && prev != connection) {
            server.send(prev, Wire.encode(List.of(
                    Msg.of(Protocol.KICK).putStr("msg", "Session resumed in another window"))));
            prev.close();
        }
        resendWelcome(connection, p);
        system(p.name + " reconnected");
        broadcastLobby();
    }

    
    private void resendWelcome(netcore.net.Connection connection, Player p) {
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
            if (phase == Phase.RUNNING) {
                server.send(connection, Wire.encode(snapshotFrame()));
            }
        } else if (phase == Phase.OVER && !lastOver.isEmpty()) {
            server.send(connection, Wire.encode(lastOver));
        }
    }

    synchronized void leave(int playerId) {
        Player p = byId.remove(playerId);
        if (p == null) return;
        players.remove(p);
        if (p.token != null && !p.token.isEmpty()) byToken.remove(p.token, p);
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
        if (!p.bot) {
            if (phase == Phase.LOBBY) system(p.name + " left");
            else system(p.name + " disconnected");
        }
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

    
    private static int pingMs(Player p) {
        if (p.bot) return -1;
        netcore.net.Connection c = p.connection;
        return c == null ? -1 : c.rttMs();
    }

    public synchronized int hostId() {
        for (Player p : players) if (!p.bot) return p.id;
        return -1;
    }

    
    
    

    synchronized void onMessage(netcore.net.Connection connection, List<Msg> frame) {
        boolean restart = handleMessages(connection, frame);
        
        
        if (restart) {
            restartRunner();
        }
    }

    
    private boolean handleMessages(netcore.net.Connection connection, List<Msg> frame) {
        boolean restart = false;
        for (Msg m : frame) {
            Player p = connection.attachment instanceof Player ? (Player) connection.attachment : null;
            if (m.is(Protocol.HELLO)) {
                if (p == null) onJoin(connection, m.str("name"), m.num("color", 0), m.str("token"));
                else resendWelcome(connection, p);
                continue;
            }
            if (p == null) continue;
            switch (m.type()) {
                case Protocol.IN:
                    if (p.snake != null && p.snake.alive) {
                        readInput(p, m);
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
                case Protocol.KICK_PLAYER:
                    if (isHost(p)) kick(p, m.num("p", -1));
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

    
    private void kick(Player host, int targetId) {
        Player target = byId.get(targetId);
        if (target == null || target.id == host.id || target.bot) return;
        if (target.connection != null) {
            server.send(target.connection, Wire.encode(List.of(
                    Msg.of(Protocol.KICK).putStr("msg", "The host kicked you from the game"))));
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
        server.broadcastVoice(out, connection);
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
                resetSubtickStats();
                system("subtick " + (cfg.subtick ? "on (timestamps)" : "off"));
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

    
    private void restartRunner() {
        netcore.time.TickRunner old;
        synchronized (this) {
            old = runner;
            runner = null;
        }
        if (old != null) old.stop();
        synchronized (this) {
            if (closed) return;
            tickBase += lastRunnerTick;
            lastRunnerTick = 0;
            tick = tickBase;
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
        foods.clear();
        events.clear();
        lastOver = List.of();
        resetSubtickStats();
        int index = 0;
        for (Player p : players) {
            p.ready = false;
            p.inputs.clear();
            p.ackSeq = 0;
            p.lastInputSeq = 0;
            p.inputDropped = 0;
            p.voiceGuard = null;
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
        system("Back to the lobby");
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

    private void resetSubtickStats() {
        subIn = 0;
        subApplied = 0;
        subMerged = 0;
        subSameTick = 0;
        subOrderErrors = 0;
        subLastPermille = 0;
        java.util.Arrays.fill(subBuckets, 0);
    }

    private void trackSubInput(float sub) {
        if (!cfg.subtick) return;
        subIn++;
        subBuckets[subBucket(sub)]++;
    }

    private int subBucket(float sub) {
        int steps = Math.max(1, Math.min(16, cfg.subSteps));
        int bucket = (int) (sub * steps);
        return Math.max(0, Math.min(steps - 1, bucket));
    }

    private String subBucketText() {
        int steps = Math.max(1, Math.min(16, cfg.subSteps));
        StringBuilder sb = new StringBuilder(steps * 3);
        for (int i = 0; i < steps; i++) {
            if (i > 0) sb.append(',');
            sb.append(subBuckets[i]);
        }
        return sb.toString();
    }

    private static float parseSub(String raw) {
        if (raw == null || raw.isEmpty()) return 0f;
        try {
            return (float) Math.max(0, Math.min(1, Double.parseDouble(raw)));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private long nanosPerTick() {
        return 1_000_000_000L / Math.max(1, cfg.tickRate);
    }

    private boolean allowInputRate(Player p, long now) {
        if (now - p.inputWindowNanos > 1_000_000_000L) {
            p.inputWindowNanos = now;
            p.inputWindowCount = 0;
        }
        p.inputWindowCount++;
        return p.inputWindowCount <= Math.max(4, cfg.inputMaxPerSec);
    }

    private void readInput(Player p, Msg m) {
        if (phase != Phase.RUNNING || p.snake == null || !p.snake.alive) return;
        int sequence = m.num("n", 0);
        int dir = m.num("d", -1);
        if (sequence <= 0 || dir < 0 || dir >= SnakeState.DX.length) {
            p.inputDropped++;
            return;
        }
        if (p.lastInputSeq > 0 && sequence > 0 && sequence <= p.lastInputSeq) {
            p.inputDropped++;
            return;
        }
        long now = System.nanoTime();
        if (!allowInputRate(p, now)) {
            p.inputDropped++;
            return;
        }
        long period = nanosPerTick();
        int scheduledTick = m.num("t", tick);
        float sub = parseSub(m.raw("s"));
        long stamp = m.numL("ts", 0L);
        if (cfg.subtick && stamp > 0 && tickOriginNanos > 0) {
            long earliest = now - (long) (cfg.inputMaxBehindTicks + 2) * period;
            long latest = now + (long) (cfg.inputMaxAheadTicks + 1) * period;
            if (stamp < earliest || stamp > latest) {
                p.inputDropped++;
                return;
            }
            long rel = stamp - tickOriginNanos;
            if (rel >= 0) {
                scheduledTick = (int) Math.min(Integer.MAX_VALUE, rel / period);
                sub = (rel % period) / (float) period;
            }
        }
        int minTick = tick - cfg.inputMaxBehindTicks;
        int maxTick = tick + cfg.inputMaxAheadTicks;
        if (scheduledTick < minTick || scheduledTick > maxTick) {
            p.inputDropped++;
            return;
        }
        sub = Math.max(0f, Math.min(0.999f, sub));
        if (p.inputs.size() >= Math.max(4, cfg.inputMaxQueue)) {
            p.inputs.remove(0);
            p.inputDropped++;
        }
        if (sequence > 0) p.lastInputSeq = sequence;
        p.inputs.add(new Input(sequence, scheduledTick, sub, dir));
        trackSubInput(sub);
    }

    
    
    

    private void gameTick() {
        events.clear();

        
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive) {
                p.inputs.clear();
                continue;
            }
            Input chosen = null;
            List<Msg> acks = null;
            int eligible = 0;
            int bestTick = Integer.MIN_VALUE;
            int sameTick = 0;
            float bestSub = -1f;
            Iterator<Input> it = p.inputs.iterator();
            while (it.hasNext()) {
                Input in = it.next();
                if (in.tick > tick) continue;
                eligible++;
                if (in.tick > bestTick) {
                    bestTick = in.tick;
                    sameTick = 1;
                    bestSub = in.sub;
                } else if (in.tick == bestTick) {
                    sameTick++;
                    if (in.sub > bestSub) bestSub = in.sub;
                }
                if (chosen == null
                        || in.tick > chosen.tick
                        || (in.tick == chosen.tick && in.sub >= chosen.sub)) {
                    chosen = in;
                }
                p.ackSeq = Math.max(p.ackSeq, in.seq);
                if (acks == null) acks = new ArrayList<>(2);
                acks.add(Msg.of(Protocol.ACK).put("n", in.seq).put("t", tick));
                it.remove();
            }
            if (cfg.subtick && eligible > 0) {
                if (chosen != null) {
                    subApplied++;
                    subLastPermille = Math.round(chosen.sub * 1000f);
                    subMerged += eligible - 1;
                    if (chosen.sub + 1e-5f < bestSub) subOrderErrors++;
                } else {
                    subMerged += eligible;
                }
                if (sameTick > 1) subSameTick += sameTick - 1;
            }
            if (chosen != null && !SnakeState.isOpposite(chosen.dir, s.dir)) {
                int units = cfg.unitsPerTick();
                double stepAt = (SnakeState.UNITS - s.acc) / (double) units;
                stepAt = Math.max(0, Math.min(1, stepAt));
                boolean beforeStep = chosen.tick < tick || chosen.sub <= stepAt + 1e-4;
                if (beforeStep) {
                    s.pendingDir = chosen.dir;
                } else {
                    s.queuedDir = chosen.dir;
                }
            }
            if (acks != null && p.connection != null) {
                server.send(p.connection, Wire.encode(acks));
            }
        }

        
        Set<Integer> occupiedForBots = occupancy(Collections.emptySet());
        for (Player p : players) {
            SnakeState s = p.snake;
            if (s == null || !s.alive || !s.bot) continue;
            int d = BotBrain.decide(s, cfg.cols, cfg.rows, cfg.walls, occupiedForBots, foods, rnd);
            if (!SnakeState.isOpposite(d, s.dir)) s.pendingDir = d;
        }

        
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
            if (s.pendingDir >= 0) {
                s.dir = s.pendingDir;
                s.pendingDir = -1;
            }
            if (s.queuedDir >= 0) {
                s.pendingDir = s.queuedDir;
                s.queuedDir = -1;
            }
        }

        for (Player p : dying) kill(p);
    }

    
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

    
    
    

    private List<Msg> snapshotFrame() {
        List<Msg> frame = new ArrayList<>();
        long droppedInputs = 0;
        for (Player p : players) droppedInputs += p.inputDropped;
        frame.add(Msg.of(Protocol.SNAP)
                .put("t", tick)
                .put("st", System.nanoTime())
                .put("rate", cfg.tickRate)
                .put("ms", cfg.unitsPerTick())
                .put("se", cfg.subtick ? 1 : 0)
                .put("ss", cfg.subSteps)
                .put("si", subIn)
                .put("sa", subApplied)
                .put("sm", subMerged)
                .put("sx", subSameTick)
                .put("so", subOrderErrors)
                .put("sl", subLastPermille)
                .put("sb", subBucketText())
                .put("ix", droppedInputs)
                .put("vok", voiceAccepted)
                .put("vdr", voiceRejected));
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
                    .put("ls", p.ackSeq)
                    .put("pg", pingMs(p));
            StringBuilder sb = new StringBuilder(s.body.size() * 7);
            for (Cell c : s.body) {
                if (sb.length() > 0) sb.append(',');
                sb.append(c.x).append(':').append(c.y);
            }
            m.put("g", sb.toString());
            frame.add(m);
        }
        events.clear();
        return frame;
    }

    private void broadcastSnapshot() {
        server.broadcastUnreliable(Wire.encode(snapshotFrame()));
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
                    .put("alive", p.snake == null ? 1 : (p.snake.alive ? 1 : 0))
                    .put("rc", p.reconnecting ? 1 : 0)
                    .put("pg", pingMs(p));
            m.putStr("name", p.name);
            frame.add(m);
        }
        server.broadcast(Wire.encode(frame));
    }
}
