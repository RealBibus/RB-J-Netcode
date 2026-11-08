package snakenet;

import snakenet.client.GameClient;
import snakenet.game.GameConfig;
import snakenet.net.LanDiscovery;
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
 * Автотест без интерфейса: протокол, настройки, полный сетевой раунд,
 * обнаружение в локальной сети. Запуск: java -Djava.awt.headless=true snakenet.SelfTest
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("Snake NET self-test");
        testMsg();
        testConfig();
        testDiscovery();
        testBeacon();
        testFullRound();
        System.out.println();
        System.out.println("ИТОГО: passed=" + passed + ", failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  OK   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failed++;
            System.out.println("  FAIL " + name + " — " + detail);
        } else {
            passed++;
            System.out.println("  OK   " + name);
        }
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    // ------------------------------------------------------------------

    private static void testMsg() {
        System.out.println("[протокол]");
        Msg m = Msg.of("CHAT").putStr("from", "Вася|Пупкин, 100%").putStr("text", "привет\nмир").put("c", 3);
        Msg parsed = Msg.parse(m.encode());
        check("тип сохраняется", parsed != null && parsed.is("CHAT"));
        check("кириллица и разделители", parsed != null && parsed.str("from").equals("Вася|Пупкин, 100%"));
        check("перевод строки выживает", parsed != null && parsed.str("text").equals("привет\nмир"));
        check("числа", parsed != null && parsed.num("c", -1) == 3);
        Msg empty = Msg.parse(Msg.of("PING").encode());
        check("пустые поля", empty != null && empty.raw("t").isEmpty());
        check("мусор отбрасывается", Msg.parse("|||") == null);
    }

    private static void testConfig() {
        System.out.println("[настройки]");
        GameConfig c = new GameConfig();
        c.applySize(2);
        c.applySpeed(3);
        check("размер применён", c.cols == 40 && c.rows == 26);
        check("скорость применена", c.tickMs == 62);
        check("подпись размера", c.sizeLabel().contains("40×26"), c.sizeLabel());

        Msg m = c.write(Msg.of("T"));
        GameConfig back = GameConfig.read(m, new GameConfig());
        check("сериализация", back.cols == 40 && back.rows == 26 && back.tickMs == 62);

        check("ник чистится", GameConfig.sanitizeName("  Ва\u0007ся   Пупкин  ").equals("Вася Пупкин"));
        check("ник не пустой", !GameConfig.sanitizeName("   ").isEmpty());
        check("ник не длиннее 16", GameConfig.sanitizeName("а".repeat(50)).length() == 16);
    }

    private static void testDiscovery() throws Exception {
        System.out.println("[обнаружение в сети]");
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
        System.out.println("[маяк сервера]");
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
        check("имя игры передано", got && found != null && found.name.equals("Моя игра"),
                found == null ? "нет" : found.name);
        check("счётчик игроков передан", got && found != null && found.players == 3 && found.max == 8,
                found == null ? "нет" : found.players + "/" + found.max);
        beacon.close();
        scanner.close();
    }

    // ------------------------------------------------------------------

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
                for (List<Msg> f : frames) {
                    for (Msg m : f) if (m.is(type)) n++;
                }
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
                    for (Msg m : frames.get(i)) {
                        if (m.is(type)) return frames.get(i);
                    }
                }
            }
            return null;
        }

        String headOf(int playerId) {
            List<Msg> frame = lastFrameWith(Protocol.SNAP);
            if (frame == null) return null;
            for (Msg m : frame) {
                if (m.is(Protocol.SNAKE) && m.num("p", -1) == playerId) {
                    String g = m.raw("g");
                    int comma = g.indexOf(',');
                    return comma > 0 ? g.substring(0, comma) : g;
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

        int lobbyPlayers() {            List<Msg> frame = lastFrameWith(Protocol.LOBBY);
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
        cfg.applySpeed(3);
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

        String head1 = clients.get(1).headOf(1);
        check("голова есть", head1 != null, "нет головы");
        check("змейка двигается", clients.get(1).headsOf(1).size() > 1,
                "уникальных голов: " + clients.get(1).headsOf(1).size());

        check("раунд закончился", waitFor(() -> host.count(Protocol.OVER) > 0, 20000),
                "снимков: " + host.count(Protocol.SNAP));
        int results = host.lastCount(Protocol.RESULT);
        check("есть результаты на 3 игроков", results == 3, "строк: " + results);

        Msg over = host.last(Protocol.OVER);
        check("кадр OVER получен", over != null);

        host.client.send(Msg.of(Protocol.AGAIN));
        check("вернулись в лобби", waitFor(() -> {
            Msg l = host.last(Protocol.LOBBY);
            return l != null && l.num("phase", -1) == Protocol.PHASE_LOBBY;
        }, 4000));

        // поздний зритель
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
}
