package snakenet;

import netcore.net.LanDiscovery;
import netcore.net.NetClient;
import netcore.net.NetServer;
import netcore.predict.Correction;
import netcore.predict.InputCommand;
import netcore.predict.PredictionConfig;
import netcore.predict.Predictor;
import netcore.predict.SimModel;
import netcore.time.TickClock;
import netcore.time.TickConfig;
import snakenet.client.GameClient;
import snakenet.game.GameConfig;
import snakenet.net.Msg;
import snakenet.net.Protocol;
import snakenet.server.GameServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Автотест без интерфейса: движок (тики, предсказание, транспорт, часы,
 * обнаружение) и полный сетевой раунд змейки.
 * Запуск: java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.SelfTest
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("Snake NET / NetCore self-test");
        testTickConfig();
        testTickClock();
        testSmoothMotion();
        testPredictor();
        testTransport();
        testDiscovery();
        testBeacon();
        testMsg();
        testConfig();
        testFullRound();
        System.out.println();
        System.out.println("ИТОГО: passed=" + passed + ", failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  OK   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    // ------------------------------------------------------------------
    // движок
    // ------------------------------------------------------------------

    private static void testTickConfig() {
        System.out.println("[движок: тикрейт]");
        TickConfig c = TickConfig.fixed(64);
        check("dt = 1/64", Math.abs(c.dt() - 1 / 64.0) < 1e-9);
        check("128 тик разрешён", TickConfig.fixed(128).rate() == 128);
        check("выше 128 обрезается", TickConfig.fixed(999).rate() == TickConfig.MAX_RATE);
        check("ниже 1 обрезается", TickConfig.fixed(0).rate() == TickConfig.MIN_RATE);
        TickConfig s = TickConfig.subtick(64, 4);
        check("subtick включает подшаги", s.isSubtick() && s.subSteps() == 4);
        check("у fixed один подшаг", TickConfig.fixed(64).subSteps() == 1);
        check("эффективные шаги", s.effectiveStepsPerSecond() == 256);
        check("tickNanos", TickConfig.fixed(128).tickNanos() == 7_812_500L);
    }

    private static void testTickClock() {
        System.out.println("[движок: часы тиков]");
        TickConfig cfg = TickConfig.fixed(100); // 10 мс
        long origin = 1_000_000_000L;
        TickClock clock = new TickClock(cfg, origin);
        check("тик 0 на старте", clock.tickAt(origin) == 0);
        check("тик 5 через 50 мс", clock.tickAt(origin + 50_000_000L) == 5);
        check("доля тика 0.5", Math.abs(clock.alphaAt(origin + 5_000_000L) - 0.5) < 0.01);
        check("время тика", clock.timeOfTick(7) == origin + 70_000_000L);
        check("до старта — тик 0", clock.tickAt(origin - 500) == 0);
    }

    /** Модель для проверки предсказания: x += action, y += 1 каждый тик. */
    private static final class PosModel implements SimModel<int[]> {
        @Override
        public int[] copy(int[] state) {
            return state.clone();
        }

        @Override
        public int[] step(int[] state, InputCommand input) {
            int[] s = state.clone();
            if (input != null) s[0] += input.action;
            s[1]++;
            return s;
        }

        @Override
        public double error(int[] authoritative, int[] predicted) {
            return Math.abs(authoritative[0] - predicted[0]);
        }

        @Override
        public int[] lerp(int[] from, int[] to, double t) {
            return t < 0.5 ? from.clone() : to.clone();
        }
    }

    /** Проверка плавности: голова не должна прыгать на клетку за кадр. */
    private static void testSmoothMotion() {
        System.out.println("[плавность движения]");
        int rate = 32;
        int units = Math.round(9 * 1024f / rate);
        List<int[]> statesX = new ArrayList<>();
        List<int[]> statesY = new ArrayList<>();
        List<Integer> statesAcc = new ArrayList<>();

        int[] xs = {5, 4, 3, 2};
        int[] ys = {5, 5, 5, 5};
        int acc = 0;
        statesX.add(xs.clone());
        statesY.add(ys.clone());
        statesAcc.add(acc);
        for (int k = 1; k <= 40; k++) {
            acc += units;
            if (acc >= 1024) {
                acc -= 1024;
                for (int i = xs.length - 1; i > 0; i--) {
                    xs[i] = xs[i - 1];
                    ys[i] = ys[i - 1];
                }
                xs[0]++;
            }
            statesX.add(xs.clone());
            statesY.add(ys.clone());
            statesAcc.add(acc);
        }

        double prevX = Double.NaN;
        double maxJump = 0;
        for (int frame = 0; frame <= 60; frame++) {
            double t = frame / 60.0;
            int tick = (int) Math.floor(t * rate);
            double phase = t * rate - tick;
            double[] pts;
            if (tick <= 0) {
                pts = snakenet.client.SnakeRenderMath.points(statesX.get(0), statesY.get(0), 1, statesAcc.get(0), 0);
            } else {
                double[] a = snakenet.client.SnakeRenderMath.points(
                        statesX.get(tick - 1), statesY.get(tick - 1), 1, statesAcc.get(tick - 1), 0);
                double[] b = snakenet.client.SnakeRenderMath.points(
                        statesX.get(tick), statesY.get(tick), 1, statesAcc.get(tick), 0);
                pts = snakenet.client.SnakeRenderMath.blend(a, b, phase);
            }
            if (!Double.isNaN(prevX)) maxJump = Math.max(maxJump, Math.abs(pts[0] - prevX));
            prevX = pts[0];
        }
        check("движение без рывков", maxJump < 0.3,
                "макс прыжок " + String.format("%.3f", maxJump) + " клетки за кадр");
        check("скорость соблюдена", Math.abs(prevX - 5 - 9) < 1.5, "прошла " + String.format("%.2f", prevX - 5) + " клеток");
    }

    private static void testPredictor() {
        System.out.println("[движок: предсказание]");
        Predictor<int[]> p = new Predictor<>(new PosModel(), PredictionConfig.of(5));
        p.reset(new int[]{0, 0}, 10, 0);

        p.push(1, 11, 0.2f);
        p.push(2, 12, 0.4f);
        p.push(3, 13, 0.6f);
        int[] predicted = p.predicted();
        check("предсказание применяет ввод", predicted[0] == 6 && predicted[1] == 3,
                "получилось " + predicted[0] + "/" + predicted[1]);
        check("номер последнего ввода", p.lastCorrection() != null && p.predictedTick() == 13);

        // сервер подтвердил тик 12 и прислал верное состояние
        Correction ok = p.onServerState(12, 2, new int[]{3, 2});
        check("верное состояние не даёт тпху", !ok.snapped(), "error=" + ok.error());
        check("ввод после ack переигран", p.predicted()[2 - 1] == 3,
                "y=" + p.predicted()[1]);

        // сервер прислал совсем другое состояние — должна быть тпха
        Correction bad = p.onServerState(13, 3, new int[]{-20, 2});
        check("большая ошибка = тпха", bad.snapped(), "error=" + bad.error());
        check("счётчик тпх", p.snaps() == 1);
        check("позиция равна серверной плюс переигранный ввод", p.predicted()[1] == 2);
    }

    private static void testTransport() throws Exception {
        System.out.println("[движок: транспорт]");
        List<String> received = new CopyOnWriteArrayList<>();
        NetServer server = new NetServer(0, new NetServer.Listener() {
            @Override
            public void onConnect(netcore.net.Connection connection) {
            }

            @Override
            public void onMessage(netcore.net.Connection connection, byte[] payload) {
                received.add(new String(payload, StandardCharsets.UTF_8));
                connection.send("эхо".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void onDisconnect(netcore.net.Connection connection, String reason) {
            }
        });
        server.start();
        check("сервер поднялся", server.port() > 0);

        List<String> echoed = new CopyOnWriteArrayList<>();
        NetClient client = NetClient.connect("127.0.0.1", server.port(), new NetClient.Listener() {
            @Override
            public void onMessage(byte[] payload) {
                echoed.add(new String(payload, StandardCharsets.UTF_8));
            }

            @Override
            public void onClosed(String reason) {
            }
        });
        client.send("привет".getBytes(StandardCharsets.UTF_8));
        check("сообщение дошло", waitFor(() -> received.contains("привет"), 2000));
        check("ответ дошёл", waitFor(() -> echoed.contains("эхо"), 2000));
        check("часы синхронизировались", waitFor(() -> client.clock().synced(), 3000),
                "rtt=" + client.clock().rttMs());
        check("RTT разумный", client.clock().rttMs() >= 0 && client.clock().rttMs() < 500,
                client.clock().rttMs() + " мс");
        check("клиент на UDP", "UDP".equals(client.protocol()), client.protocol());

        // тот же сервер слушает и TCP — проверяем запасной канал
        List<String> echoedTcp = new CopyOnWriteArrayList<>();
        NetClient tcp = NetClient.connect("127.0.0.1", server.port(), netcore.net.NetConfig.tcp(),
                new NetClient.Listener() {
                    @Override
                    public void onMessage(byte[] payload) {
                        echoedTcp.add(new String(payload, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onClosed(String reason) {
                    }
                });
        tcp.send("по tcp".getBytes(StandardCharsets.UTF_8));
        check("TCP работает как запасной", waitFor(() -> echoedTcp.contains("эхо"), 2000));
        check("клиент на TCP", "TCP".equals(tcp.protocol()), tcp.protocol());
        tcp.close();

        client.close();
        server.stop();
    }

    private static void testDiscovery() throws Exception {
        System.out.println("[движок: обнаружение]");
        List<LanDiscovery.Found> seen = new CopyOnWriteArrayList<>();
        LanDiscovery.Scanner scanner = new LanDiscovery.Scanner(list -> {
            seen.clear();
            seen.addAll(list);
        });
        scanner.start();
        String payload = "SNKN1|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Тестовая игра".getBytes(StandardCharsets.UTF_8)) + "|45999|2|8";
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), LanDiscovery.PORT));
        }
        boolean got = waitFor(() -> !seen.isEmpty(), 3000);
        check("пакет найден", got);
        check("имя и порт разобраны", got && seen.get(0).port == 45999 && seen.get(0).name.equals("Тестовая игра"),
                got ? seen.get(0).name + ":" + seen.get(0).port : "нет данных");
        scanner.close();
    }

    private static void testBeacon() throws Exception {
        System.out.println("[движок: маяк сервера]");
        List<LanDiscovery.Found> seen = new CopyOnWriteArrayList<>();
        LanDiscovery.Scanner scanner = new LanDiscovery.Scanner(list -> {
            seen.clear();
            seen.addAll(list);
        });
        scanner.start();
        LanDiscovery.Beacon beacon = new LanDiscovery.Beacon("Моя игра", 46001, () -> 3, () -> 8);
        beacon.start();
        boolean got = waitFor(() -> seen.stream().anyMatch(f -> f.port == 46001), 6000);
        LanDiscovery.Found found = seen.stream().filter(f -> f.port == 46001).findFirst().orElse(null);
        check("хост виден в сети", got);
        check("имя игры передано", got && found != null && found.name.equals("Моя игра"));
        check("счётчик игроков передан", got && found != null && found.players == 3 && found.max == 8);
        beacon.close();
        scanner.close();
    }

    // ------------------------------------------------------------------
    // игра
    // ------------------------------------------------------------------

    private static void testMsg() {
        System.out.println("[протокол]");
        Msg m = Msg.of("CHAT").putStr("from", "Вася|Пупкин, 100%").putStr("text", "привет\nмир").put("c", 3);
        Msg parsed = Msg.parse(m.encode());
        check("кириллица и разделители", parsed != null && parsed.str("from").equals("Вася|Пупкин, 100%"));
        check("перевод строки выживает", parsed != null && parsed.str("text").equals("привет\nмир"));
        check("мусор отбрасывается", Msg.parse("|||") == null);
    }

    private static void testConfig() {
        System.out.println("[настройки]");
        GameConfig c = new GameConfig();
        c.applySize(2);
        c.applySpeed(3);
        c.applyTickRate(3);
        check("размер применён", c.cols == 40 && c.rows == 26);
        check("скорость применена", c.cellsPerSec == 15);
        check("тикрейт применён", c.tickRate == 128);
        check("единиц за тик", c.unitsPerTick() == Math.round(15 * 1024 / 128f));
        Msg m = c.write(Msg.of("T"));
        GameConfig back = GameConfig.read(m, new GameConfig());
        check("сериализация", back.cols == 40 && back.tickRate == 128 && back.cellsPerSec == 15);
        check("ник чистится", GameConfig.sanitizeName("  Ва\u0007ся   Пупкин  ").equals("Вася Пупкин"));
        check("ник не длиннее 16", GameConfig.sanitizeName("а".repeat(50)).length() == 16);
    }

    private static final class Collect implements GameClient.Listener {
        GameClient client;
        final List<List<Msg>> frames = Collections.synchronizedList(new ArrayList<>());
        volatile boolean closed;

        @Override
        public void onFrame(List<Msg> frame) {
            frames.add(frame);
        }

        @Override
        public void onClosed(String reason) {
            closed = true;
        }

        int count(String type) {
            int n = 0;
            synchronized (frames) {
                for (List<Msg> f : frames)
                    for (Msg m : f) if (m.is(type)) n++;
            }
            return n;
        }

        int lastCount(String type) {
            synchronized (frames) {
                for (int i = frames.size() - 1; i >= 0; i--) {
                    int n = 0;
                    for (Msg m : frames.get(i)) if (m.is(type)) n++;
                    if (n > 0) return n;
                }
            }
            return 0;
        }

        Msg last(String type) {
            synchronized (frames) {
                for (int i = frames.size() - 1; i >= 0; i--) {
                    for (int k = frames.get(i).size() - 1; k >= 0; k--) {
                        if (frames.get(i).get(k).is(type)) return frames.get(i).get(k);
                    }
                }
            }
            return null;
        }

        List<Msg> lastFrameWith(String type) {
            synchronized (frames) {
                for (int i = frames.size() - 1; i >= 0; i--) {
                    for (Msg m : frames.get(i)) if (m.is(type)) return frames.get(i);
                }
            }
            return null;
        }

        java.util.Set<String> headsOf(int playerId) {
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            synchronized (frames) {
                for (List<Msg> f : frames) {
                    for (Msg m : f) {
                        if (m.is(Protocol.SNAKE) && m.num("p", -1) == playerId) {
                            String g = m.raw("g");
                            int comma = g.indexOf(',');
                            set.add(comma > 0 ? g.substring(0, comma) : g);
                        }
                    }
                }
            }
            return set;
        }

        int lobbyPlayers() {
            List<Msg> frame = lastFrameWith(Protocol.LOBBY);
            if (frame == null) return -1;
            int n = 0;
            for (Msg m : frame) if (m.is(Protocol.PLAYER)) n++;
            return n;
        }
    }

    private static void testFullRound() throws Exception {
        System.out.println("[сетевой раунд]");
        GameConfig cfg = new GameConfig();
        cfg.applySize(0);
        cfg.cellsPerSec = 15;
        cfg.tickRate = 64;
        cfg.subtick = true;
        GameServer server = new GameServer(0, "Тест", cfg);
        server.start();
        int port = server.port();
        check("сервер слушает порт", port > 0);

        List<Collect> clients = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Collect c = new Collect();
            c.client = GameClient.connect("127.0.0.1", port, c);
            clients.add(c);
            c.client.send(Msg.of(Protocol.HELLO).putStr("name", "Тест" + i).put("color", i));
            Thread.sleep(150);
        }
        final Collect host = clients.get(0);
        check("WELCOME получен", host.last(Protocol.WELCOME) != null);
        check("тикрейт в приветствии", host.last(Protocol.WELCOME) != null
                && host.last(Protocol.WELCOME).num("rate", 0) == 64);
        check("в лобби 3 игрока", waitFor(() -> host.lobbyPlayers() == 3, 3000), "было " + host.lobbyPlayers());

        host.client.send(Msg.of(Protocol.BOT).put("op", "add"));
        host.client.send(Msg.of(Protocol.BOT).put("op", "add"));
        check("боты добавились", waitFor(() -> host.lobbyPlayers() == 5, 3000), "было " + host.lobbyPlayers());
        host.client.send(Msg.of(Protocol.BOT).put("op", "del"));
        host.client.send(Msg.of(Protocol.BOT).put("op", "del"));
        check("боты убрались", waitFor(() -> host.lobbyPlayers() == 3, 3000), "было " + host.lobbyPlayers());

        host.client.send(Msg.of(Protocol.START));
        check("отсчёт пошёл", waitFor(() -> host.count(Protocol.COUNT) >= 1, 4000));
        check("снимки поля идут", waitFor(() -> clients.get(1).count(Protocol.SNAP) > 5, 6000),
                "снимков: " + clients.get(1).count(Protocol.SNAP));

        check("змейка двигается", clients.get(1).headsOf(1).size() > 1,
                "уникальных голов: " + clients.get(1).headsOf(1).size());

        // ввод с номером и тиком доходит и подтверждается
        Msg snap = clients.get(1).last(Protocol.SNAP);
        int myPid = clients.get(1).last(Protocol.WELCOME).num("pid", -1);
        int tick = snap == null ? 1 : snap.num("t", 1);
        int rate = snap == null ? 64 : snap.num("rate", 64);
        int nx = 1 + (int) Math.ceil(rate * 0.05);
        clients.get(1).client.send(Msg.of(Protocol.IN).put("d", 3).put("t", tick + nx).put("s", 0.5).put("n", 1));
        check("ввод подтверждён сервером", waitFor(() -> hasAck(clients.get(1), myPid, 1), 3000),
                "pid=" + myPid);

        check("раунд закончился", waitFor(() -> host.count(Protocol.OVER) > 0, 20000),
                "снимков: " + host.count(Protocol.SNAP));
        check("есть результаты на 3 игроков", host.lastCount(Protocol.RESULT) == 3,
                "строк: " + host.lastCount(Protocol.RESULT));

        host.client.send(Msg.of(Protocol.AGAIN));
        check("вернулись в лобби", waitFor(() -> {
            Msg l = host.last(Protocol.LOBBY);
            return l != null && l.num("phase", -1) == Protocol.PHASE_LOBBY;
        }, 4000));

        Collect late = new Collect();
        late.client = GameClient.connect("127.0.0.1", port, late);
        late.client.send(Msg.of(Protocol.HELLO).putStr("name", "Опоздавший").put("color", 4));
        check("поздний игрок вошёл", waitFor(() -> late.last(Protocol.WELCOME) != null, 3000));
        check("лобби обновилось до 4", waitFor(() -> host.lobbyPlayers() == 4, 3000), "было " + host.lobbyPlayers());

        server.stop();
        check("клиенты отключились", waitFor(() -> host.closed, 4000));
        for (Collect c : clients) c.client.close();
        late.client.close();
    }

    /** Сервер подтвердил ввод с номером {@code seq} (в снимке есть ls >= seq). */
    private static boolean hasAck(Collect c, int playerId, int seq) {
        List<Msg> frame = c.lastFrameWith(Protocol.SNAP);
        if (frame == null) return false;
        for (Msg m : frame) {
            if (m.is(Protocol.SNAKE) && m.num("p", -1) == playerId) {
                return m.num("ls", 0) >= seq;
            }
        }
        return false;
    }
}
