package snakenet.tools;

import snakenet.client.GameClient;
import snakenet.game.GameConfig;
import snakenet.game.SnakeState;
import netcore.netcode.Msg;
import snakenet.net.Net;
import snakenet.server.GameServer;

import java.util.List;
import java.util.Locale;

public final class SubtickProbe {

    private static volatile Msg lastSnap;
    private static volatile int pid = -1;
    private static volatile int myDir = -1;
    private static volatile boolean hubSeen;

    public static void main(String[] args) throws Exception {
        int rate = args.length > 0 ? parseInt(args[0], 64) : 64;
        int steps = args.length > 1 ? parseInt(args[1], 4) : 4;

        System.out.println("Subtick probe");
        System.out.println("  config     " + rate + " Hz, subtick"
                + " (" + (rate * steps) + " input slots/s)");

        GameConfig cfg = new GameConfig();
        cfg.applySize(0);
        cfg.cellsPerSec = 2;
        cfg.tickRate = rate;
        cfg.subtick = true;
        cfg.subSteps = steps;
        cfg.walls = false;

        GameServer server = new GameServer(0, "SubtickProbe", cfg);
        server.start();
        GameClient client = GameClient.connect("127.0.0.1", server.port(), new GameClient.Listener() {
            @Override
            public void onFrame(List<Msg> frame) {
                for (Msg m : frame) {
                    if (m.is(Net.HUB)) hubSeen = true;
                    if (m.is(Net.SNAP)) lastSnap = m;
                    if (m.is(Net.WELCOME)) pid = m.num("pid", -1);
                    if (m.is(Net.SNAKE) && m.num("p", -1) == pid) myDir = m.num("d", -1);
                }
            }

            @Override
            public void onVoice(int playerId, int sequence, short[] pcm) {
            }

            @Override
            public void onClosed(String reason) {
            }
        });
        client.send(Msg.of(Net.HELLO).putStr("name", "Probe").put("color", 0).putStr("token", "probe-tok"));

        if (!waitFor(() -> hubSeen, 4000)) {
            fail("no HUB from the server");
            client.close();
            server.stop();
            System.exit(1);
        }
        client.send(cfg.write(Net.LOBBY_CREATE.newMsg())
                .put("kind", Net.KIND_SNAKE)
                .putStr("name", "Probe lobby"));
        if (!waitFor(() -> pid >= 0, 4000)) {
            fail("no WELCOME from the server");
            client.close();
            server.stop();
            System.exit(1);
        }
        client.send(Msg.of(Net.START));
        if (!waitFor(() -> lastSnap != null, 8000)) {
            fail("round did not start");
            client.close();
            server.stop();
            System.exit(1);
        }

        Msg first = lastSnap;
        int at = first.num("t", 1) + 8;
        int dir = myDir;
        int target = Math.floorMod(dir + 1, 4);
        int alt = Math.floorMod(dir + 3, 4);

        System.out.println("  start      tick " + first.num("t", 1) + ", direction " + name(dir));
        System.out.println("  sending    4 inputs for tick " + at);

        float[] subs = {0.050f, 0.350f, 0.650f, 0.950f};
        int[] dirs = {alt, alt, alt, target};
        for (int i = 0; i < subs.length; i++) {
            client.send(Msg.of(Net.IN)
                    .put("d", dirs[i])
                    .put("t", at)
                    .put("s", String.format(Locale.ROOT, "%.3f", subs[i]))
                    .put("n", i + 1));
            System.out.println("    #" + (i + 1) + " sub=" + String.format(Locale.ROOT, "%.3f", subs[i])
                    + " dir=" + name(dirs[i]));
            Thread.sleep(10);
        }

        boolean arrived = waitFor(() -> {
            Msg s = lastSnap;
            return s != null && s.num("t", 0) >= at && s.num("si", 0) >= 4;
        }, 5000);
        if (!arrived) {
            fail("server never reported the sent inputs");
            client.close();
            server.stop();
            System.exit(1);
        }

        Msg s = lastSnap;
        int in = s.num("si", 0);
        int applied = s.num("sa", 0);
        int merged = s.num("sm", 0);
        int same = s.num("sx", 0);
        int errors = s.num("so", 0);
        int last = s.num("sl", 0);
        String buckets = s.raw("sb");

        System.out.println();
        System.out.println("  server     in=" + in + " applied=" + applied + " merged=" + merged
                + " same-tick=" + same + " order=" + (errors == 0 ? "ok" : "ERR" + errors));
        System.out.println("  buckets    " + buckets.replace(',', '/')
                + "   last sub " + String.format(Locale.ROOT, "%.3f", last / 1000.0));

        boolean turned = waitFor(() -> myDir == target, 5000);
        int finalDir = myDir;
        System.out.println("  direction  " + name(finalDir) + " (expected " + name(target) + ")");

        boolean ok = in >= 4 && merged >= 3 && same >= 3 && errors == 0 && turned;
        System.out.println();
        System.out.println("  RESULT     " + (ok ? "PASS" : "FAIL"));
        if (!ok) {
            System.out.println("             check: subtick input counters and the winning sub");
        }

        client.close();
        server.stop();
        System.exit(ok ? 0 : 1);
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static String name(int dir) {
        if (dir < 0 || dir >= SnakeState.DIR_NAMES.length) return "?";
        return SnakeState.DIR_NAMES[dir].toUpperCase(Locale.ROOT);
    }

    private static int parseInt(String raw, int def) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void fail(String message) {
        System.out.println();
        System.out.println("  RESULT     FAIL — " + message);
    }

    private SubtickProbe() {
    }
}
