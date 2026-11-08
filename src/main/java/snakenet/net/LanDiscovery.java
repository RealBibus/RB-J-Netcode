package snakenet.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Автообнаружение игр в локальной сети: хост раз в секунду кричит UDP-бродкастом,
 * клиент слушает и показывает список. Никаких внешних серверов.
 */
public final class LanDiscovery {

    public static final int PORT = 45777;
    private static final String MAGIC = "SNKN1";
    private static final long TTL_MS = 4000;

    private LanDiscovery() {
    }

    public static final class Found {
        public final String name;
        public final String host;
        public final int port;
        public final int players;
        public final int max;
        public final long seenAt;

        Found(String name, String host, int port, int players, int max, long seenAt) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.players = players;
            this.max = max;
            this.seenAt = seenAt;
        }

        public String address() {
            return host + ":" + port;
        }
    }

    /** Хостовая часть: рассылает приглашение. */
    public static final class Beacon {
        private final String name;
        private final int port;
        private final IntSupplier players;
        private final IntSupplier max;
        private DatagramSocket socket;
        private Thread thread;
        private volatile boolean running;

        public Beacon(String name, int port, IntSupplier players, IntSupplier max) {
            this.name = name;
            this.port = port;
            this.players = players;
            this.max = max;
        }

        public void start() {
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
            } catch (IOException e) {
                return;
            }
            running = true;
            thread = new Thread(this::loop, "snake-beacon");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            while (running) {
                String payload = MAGIC + "|" + b64(name) + "|" + port + "|" + players.getAsInt() + "|" + max.getAsInt();
                byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                for (InetAddress addr : broadcastAddresses()) {
                    try {
                        socket.send(new DatagramPacket(data, data.length, addr, PORT));
                    } catch (IOException ignored) {
                    }
                }
                try {
                    Thread.sleep(1100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        public void close() {
            running = false;
            if (thread != null) thread.interrupt();
            if (socket != null) socket.close();
        }
    }

    /** Клиентская часть: слушает приглашения и отдаёт список. */
    public static final class Scanner {
        private final Consumer<List<Found>> listener;
        private final Map<String, Found> found = new ConcurrentHashMap<>();
        private DatagramSocket socket;
        private Thread thread;
        private volatile boolean running;

        public Scanner(Consumer<List<Found>> listener) {
            this.listener = listener;
        }

        /** @return удалось ли занять порт и начать слушать */
        public boolean start() {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(PORT));
                socket.setSoTimeout(600);
            } catch (IOException e) {
                return false; // нет прав или порт занят — покажем ручной ввод
            }
            running = true;
            thread = new Thread(this::loop, "snake-scan");
            thread.setDaemon(true);
            thread.start();
            return true;
        }

        private void loop() {
            byte[] buf = new byte[512];
            long lastNotify = 0;
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    String[] parts = text.split("\\|", -1);
                    if (parts.length >= 5 && MAGIC.equals(parts[0])) {
                        String name = unb64(parts[1]);
                        int port = Integer.parseInt(parts[2]);
                        int players = Integer.parseInt(parts[3]);
                        int max = Integer.parseInt(parts[4]);
                        String host = packet.getAddress().getHostAddress();
                        found.put(host + ":" + port, new Found(name, host, port, players, max, System.currentTimeMillis()));
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // нормально: просто чистим устаревшие
                } catch (Exception e) {
                    if (!running) break;
                }
                long now = System.currentTimeMillis();
                if (now - lastNotify > 400) {
                    lastNotify = now;
                    found.values().removeIf(f -> now - f.seenAt > TTL_MS);
                    if (listener != null) listener.accept(snapshot());
                }
            }
        }

        private List<Found> snapshot() {
            List<Found> list = new ArrayList<>(found.values());
            list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return list;
        }

        public void close() {
            running = false;
            if (thread != null) thread.interrupt();
            if (socket != null) socket.close();
        }
    }

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            // loopback: чтобы две копии игры на одном компьютере тоже видели друг друга
            out.add(InetAddress.getByName("127.0.0.1"));
            out.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) out.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
