package snakenet.tools;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ChaosProxy {

    public static void main(String[] args) throws Exception {
        int listenPort = intArg(args, 0, 45990);
        String targetHost = args.length > 1 ? args[1] : "127.0.0.1";
        int targetPort = intArg(args, 2, 45800);
        int latencyMs = intArg(args, 3, 80);
        int jitterMs = intArg(args, 4, 20);
        int lossPercent = intArg(args, 5, 2);

        DatagramSocket clientSide = new DatagramSocket(listenPort);
        DatagramSocket serverSide = new DatagramSocket();
        clientSide.setReceiveBufferSize(256 * 1024);
        serverSide.setReceiveBufferSize(256 * 1024);
        InetSocketAddress target = new InetSocketAddress(targetHost, targetPort);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "chaos-proxy");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<SocketAddress> client = new AtomicReference<>();
        AtomicLong up = new AtomicLong();
        AtomicLong down = new AtomicLong();
        AtomicLong dropped = new AtomicLong();

        Thread toServer = new Thread(() -> {
            byte[] buffer = new byte[65535];
            while (!clientSide.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    clientSide.receive(packet);
                } catch (Exception e) {
                    return;
                }
                client.set(packet.getSocketAddress());
                byte[] data = copy(packet.getData(), packet.getLength());
                up.incrementAndGet();
                if (drop(lossPercent)) {
                    dropped.incrementAndGet();
                    continue;
                }
                long delay = delay(latencyMs, jitterMs);
                scheduler.schedule(() -> send(serverSide, data, target), delay, TimeUnit.MILLISECONDS);
            }
        }, "chaos-up");
        toServer.setDaemon(true);
        toServer.start();

        Thread toClient = new Thread(() -> {
            byte[] buffer = new byte[65535];
            while (!serverSide.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    serverSide.receive(packet);
                } catch (Exception e) {
                    return;
                }
                SocketAddress destination = client.get();
                if (destination == null) continue;
                byte[] data = copy(packet.getData(), packet.getLength());
                down.incrementAndGet();
                if (drop(lossPercent)) {
                    dropped.incrementAndGet();
                    continue;
                }
                long delay = delay(latencyMs, jitterMs);
                scheduler.schedule(() -> send(clientSide, data, destination), delay, TimeUnit.MILLISECONDS);
            }
        }, "chaos-down");
        toClient.setDaemon(true);
        toClient.start();

        System.out.println("ChaosProxy listening on UDP " + listenPort + " -> " + targetHost + ":" + targetPort);
        System.out.println("latency " + latencyMs + " ms, jitter " + jitterMs + " ms, loss " + lossPercent + "%");
        System.out.println("point the game at 127.0.0.1:" + listenPort + "; Ctrl+C to stop");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            clientSide.close();
            serverSide.close();
            scheduler.shutdownNow();
            System.out.println();
            System.out.println("up=" + up.get() + " down=" + down.get() + " dropped=" + dropped.get());
        }));

        while (true) {
            Thread.sleep(5000);
            System.out.println("up=" + up.get() + " down=" + down.get() + " dropped=" + dropped.get());
        }
    }

    private static int intArg(String[] args, int index, int def) {
        try {
            return index < args.length ? Integer.parseInt(args[index]) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean drop(int lossPercent) {
        return lossPercent > 0 && ThreadLocalRandom.current().nextInt(100) < lossPercent;
    }

    private static long delay(int latencyMs, int jitterMs) {
        int extra = jitterMs <= 0 ? 0 : ThreadLocalRandom.current().nextInt(jitterMs + 1);
        return Math.max(0, latencyMs + extra);
    }

    private static byte[] copy(byte[] data, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, 0, out, 0, length);
        return out;
    }

    private static void send(DatagramSocket socket, byte[] data, SocketAddress destination) {
        try {
            socket.send(new DatagramPacket(data, data.length, destination));
        } catch (Exception ignored) {
        }
    }

    private ChaosProxy() {
    }
}
