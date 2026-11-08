package snakenet.tools;

import netcore.net.Connection;
import netcore.net.NetClient;
import netcore.net.NetCodec;
import netcore.net.NetConfig;
import netcore.net.NetServer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class NetBench {

    public static void main(String[] args) throws Exception {
        System.out.println("Snake NET / NetCore benchmark");
        System.out.println();
        compression();
        System.out.println();
        qualityTable();
        System.out.println();
        loopback(args.length > 0 ? Integer.parseInt(args[0]) : 400);
    }

    private static void compression() {
        System.out.println("[compression]");
        String json = "{\"players\":[{\"id\":1,\"name\":\"Vasya\",\"score\":120,\"alive\":true},"
                + "{\"id\":2,\"name\":\"Anna\",\"score\":90,\"alive\":true}],"
                + "\"food\":[[18,9,0],[6,4,1],[27,4,3],[12,19,2]],\"tick\":4096}";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 16_384) sb.append(json);
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);

        long start = System.nanoTime();
        byte[] packed = NetCodec.compress(payload);
        long elapsed = System.nanoTime() - start;
        int size = packed == null ? payload.length : packed.length;
        double ratio = 100.0 * size / payload.length;
        System.out.printf(Locale.ROOT, "  payload %d bytes -> %d bytes (%.1f%% of original), %.2f ms%n",
                payload.length, size, ratio, elapsed / 1_000_000.0);
        byte[] restored = packed == null ? payload : NetCodec.decompress(packed);
        System.out.println("  round trip: " + (java.util.Arrays.equals(payload, restored) ? "ok" : "FAILED"));

        byte[] noise = new byte[4096];
        new java.util.Random(42).nextBytes(noise);
        byte[] noisePacked = NetCodec.compress(noise);
        System.out.println("  incompressible payload: "
                + (noisePacked == null ? "sent raw (correct)" : "compressed to " + noisePacked.length + " bytes"));
    }

    private static void qualityTable() {
        System.out.println("[quality score]");
        int[][] samples = {
                {10, 0, 0},
                {40, 2, 0},
                {80, 8, 1},
                {150, 15, 5},
                {250, 30, 12},
                {400, 60, 25},
        };
        System.out.println("  RTT   JITTER   LOSS   SCORE");
        for (int[] s : samples) {
            System.out.printf(Locale.ROOT, "  %3d ms  %3d ms  %3d%%   %3d%n",
                    s[0], s[1], s[2], Connection.score(s[0], s[1], s[2]));
        }
    }

    private static void loopback(int messages) throws Exception {
        System.out.println("[loopback]");
        AtomicInteger echoed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(messages);

        NetServer server = new NetServer(0, NetConfig.udp(false), new NetServer.Listener() {
            @Override
            public void onConnect(Connection connection) {
            }

            @Override
            public void onMessage(Connection connection, byte[] payload) {
                connection.send(payload, false);
            }

            @Override
            public void onDisconnect(Connection connection, String reason) {
            }
        });
        server.start();

        NetClient client = NetClient.connect("127.0.0.1", server.port(), NetConfig.udp(false),
                new NetClient.Listener() {
                    @Override
                    public void onMessage(byte[] payload) {
                        if (echoed.incrementAndGet() <= messages) done.countDown();
                    }

                    @Override
                    public void onClosed(String reason) {
                    }
                });

        byte[] payload = new byte[96];
        java.util.Arrays.fill(payload, (byte) 'x');
        long start = System.nanoTime();
        for (int i = 0; i < messages; i++) {
            client.sendUnreliable(payload);
            Thread.sleep(1);
        }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;
        int received = echoed.get();
        double seconds = Math.max(0.001, elapsed / 1_000_000_000.0);
        System.out.printf(Locale.ROOT, "  %d/%d messages in %.0f ms, %.0f msg/s%n",
                received, messages, elapsed / 1_000_000.0, received / seconds);
        System.out.println("  protocol " + client.protocol()
                + ", rtt " + client.rttMs() + " ms"
                + ", jitter " + client.jitterMs() + " ms"
                + ", loss " + client.lossPercent() + "%"
                + ", quality " + client.quality() + "%");

        client.close();
        server.stop();
    }
}
