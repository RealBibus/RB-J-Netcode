package snakenet;

import netcore.net.Connection;
import netcore.net.LanDiscovery;
import netcore.net.NetClient;
import netcore.net.NetCodec;
import netcore.net.NetConfig;
import netcore.net.NetInfo;
import netcore.net.NetServer;
import netcore.predict.Correction;
import netcore.predict.InputCommand;
import netcore.predict.PredictionConfig;
import netcore.predict.Predictor;
import netcore.predict.SimModel;
import netcore.time.TickClock;
import netcore.time.TickConfig;
import netcore.voice.VoiceConfig;
import netcore.voice.VoiceGuard;
import netcore.voice.VoiceJitterBuffer;
import netcore.voice.VoiceMode;
import snakenet.client.GameClient;
import snakenet.client.SnakePredictState;
import snakenet.game.GameConfig;
import snakenet.game.SnakeState;
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
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("Snake NET / NetCore self-test");
        testTickConfig();
        testTickClock();
        testSmoothMotion();
        testGridTurns();
        testTurnRendering();
        testPredictor();
        testCompression();
        testQuality();
        testSubtick();
        testVoiceEngine();
        testRenderContinuous();
        testRenderRates();
        testGameFactories();
        testTransport();
        testFloodProtection();
        testAutoReconnect();
        testUdpReconnectSameAddress();
        testDiscovery();
        testBeacon();
        testMsg();
        testConfig();
        testReconnect();
        testReconnectDuringRound();
        testSecondRound();
        testFullRound();
        System.out.println();
        System.out.println("TOTAL: passed=" + passed + ", failed=" + failed);
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

    private static void testTickConfig() {
        System.out.println("[engine: tick rate]");
        TickConfig c = TickConfig.fixed(64);
        check("dt = 1/64", Math.abs(c.dt() - 1 / 64.0) < 1e-9);
        check("128 Hz is allowed", TickConfig.fixed(128).rate() == 128);
        check("above 128 is clamped", TickConfig.fixed(999).rate() == TickConfig.MAX_RATE);
        check("below 1 is clamped", TickConfig.fixed(0).rate() == TickConfig.MIN_RATE);
        TickConfig s = TickConfig.subtick(64, 4);
        check("subtick enables sub-steps", s.isSubtick() && s.subSteps() == 4);
        check("fixed has one sub-step", TickConfig.fixed(64).subSteps() == 1);
        check("effective steps per second", s.effectiveStepsPerSecond() == 256);
        check("tickNanos", TickConfig.fixed(128).tickNanos() == 7_812_500L);
    }

    private static void testTickClock() {
        System.out.println("[engine: tick clock]");
        TickConfig cfg = TickConfig.fixed(100);
        long origin = 1_000_000_000L;
        TickClock clock = new TickClock(cfg, origin);
        check("tick 0 at start", clock.tickAt(origin) == 0);
        check("tick 5 after 50 ms", clock.tickAt(origin + 50_000_000L) == 5);
        check("tick fraction 0.5", Math.abs(clock.alphaAt(origin + 5_000_000L) - 0.5) < 0.01);
        check("tick time", clock.timeOfTick(7) == origin + 70_000_000L);
        check("before start is tick 0", clock.tickAt(origin - 500) == 0);
    }

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

    private static void testSmoothMotion() {
        System.out.println("[motion smoothness]");
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
        check("motion has no jumps", maxJump < 0.3,
                "max jump " + String.format("%.3f", maxJump) + " cells per frame");
        check("speed is preserved", Math.abs(prevX - 5 - 9) < 1.5, "travelled " + String.format("%.2f", prevX - 5) + " cells");
    }

    private static void testGridTurns() {
        System.out.println("[grid-aligned turns]");
        SnakePredictState.Model model = new SnakePredictState.Model(20, 20, 200, true);
        SnakePredictState s = new SnakePredictState(
                new int[]{5, 4, 3}, new int[]{5, 5, 5}, SnakeState.RIGHT, 800, true);

        s = model.step(s, new InputCommand(1, 1, 0f, SnakeState.DOWN));
        check("heading does not change inside a cell",
                s.dir == SnakeState.RIGHT && s.pendingDir == SnakeState.DOWN);
        check("head keeps moving right", s.headX() == 5 && s.headY() == 5 && s.acc == 1000);

        s = model.step(s, null);
        check("turn happens on the cell boundary", s.dir == SnakeState.DOWN && s.pendingDir == -1);
        check("head entered the next cell", s.headX() == 6 && s.headY() == 5);

        for (int i = 0; i < 5; i++) s = model.step(s, null);
        check("movement continues downward", s.headX() == 6 && s.headY() == 6,
                "head " + s.headX() + ":" + s.headY());
    }

    private static void testTurnRendering() {
        System.out.println("[turn smoothness]");
        int rate = 32;
        int units = Math.round(9 * 1024f / rate);
        SnakePredictState.Model model = new SnakePredictState.Model(30, 30, units, true);
        SnakePredictState s = new SnakePredictState(
                new int[]{8, 7, 6, 5}, new int[]{8, 8, 8, 8}, SnakeState.RIGHT, 0, true);

        List<SnakePredictState> states = new ArrayList<>();
        states.add(s.copy());
        for (int k = 1; k <= 40; k++) {
            InputCommand in = k == 4 ? new InputCommand(1, k, 0f, SnakeState.DOWN) : null;
            s = model.step(s, in);
            states.add(s.copy());
        }

        double px = Double.NaN;
        double py = Double.NaN;
        double maxJump = 0;
        for (int frame = 0; frame <= 60; frame++) {
            double t = frame / 60.0;
            int tick = (int) Math.floor(t * rate);
            double phase = t * rate - tick;
            double[] pts;
            if (tick <= 0) {
                SnakePredictState a = states.get(0);
                pts = snakenet.client.SnakeRenderMath.points(a.xs, a.ys, a.dir, a.acc, 0);
            } else {
                SnakePredictState a = states.get(Math.min(tick - 1, states.size() - 1));
                SnakePredictState b = states.get(Math.min(tick, states.size() - 1));
                double[] pa = snakenet.client.SnakeRenderMath.points(a.xs, a.ys, a.dir, a.acc, 0);
                double[] pb = snakenet.client.SnakeRenderMath.points(b.xs, b.ys, b.dir, b.acc, 0);
                pts = snakenet.client.SnakeRenderMath.blend(pa, pb, phase);
            }
            if (!Double.isNaN(px)) {
                maxJump = Math.max(maxJump, Math.hypot(pts[0] - px, pts[1] - py));
            }
            px = pts[0];
            py = pts[1];
        }
        check("head does not jump on a turn", maxJump < 0.45,
                "max " + String.format("%.3f", maxJump) + " cells per frame");
    }

    private static void testPredictor() {
        System.out.println("[engine: prediction]");
        Predictor<int[]> p = new Predictor<>(new PosModel(), PredictionConfig.of(5));
        p.reset(new int[]{0, 0}, 10, 0);

        p.push(1, 11, 0.2f);
        p.push(2, 12, 0.4f);
        p.push(3, 13, 0.6f);
        int[] predicted = p.predicted();
        check("prediction applies input", predicted[0] == 6 && predicted[1] == 3,
                "got " + predicted[0] + "/" + predicted[1]);
        check("last input number", p.lastCorrection() != null && p.predictedTick() == 13);

        Correction ok = p.onServerState(12, 2, new int[]{3, 2});
        check("correct state does not reset", !ok.snapped(), "error=" + ok.error());
        check("input after ack is replayed", p.predicted()[2 - 1] == 3,
                "y=" + p.predicted()[1]);

        Correction bad = p.onServerState(13, 3, new int[]{-20, 2});
        check("large error causes a reset", bad.snapped(), "error=" + bad.error());
        check("reset counter", p.snaps() == 1);
        check("position equals server state plus replayed input", p.predicted()[1] == 2);
    }

    private static void testCompression() {
        System.out.println("[engine: compression]");
        byte[] repetitive = new byte[4096];
        byte[] pattern = "SNAKE NET ".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < repetitive.length; i++) repetitive[i] = pattern[i % pattern.length];
        byte[] packed = NetCodec.compress(repetitive);
        check("repetitive payload compresses", packed != null && packed.length < repetitive.length,
                packed == null ? "not compressed" : packed.length + " bytes");
        check("compressed payload restores", packed != null && java.util.Arrays.equals(repetitive, NetCodec.decompress(packed)));
        check("small payload stays raw", NetCodec.compress(new byte[32]) == null);
        check("corrupt payload is rejected", NetCodec.decompress(new byte[]{1, 2, 3, 4, 5}) == null);
        byte[] random = new byte[2048];
        new Random(7).nextBytes(random);
        check("incompressible payload stays raw", NetCodec.compress(random) == null);
    }

    private static void testQuality() {
        System.out.println("[engine: connection quality]");
        check("perfect link scores 100", Connection.score(10, 0, 0) == 100);
        check("high RTT lowers the score", Connection.score(300, 0, 0) < 60,
                "score=" + Connection.score(300, 0, 0));
        check("packet loss lowers the score", Connection.score(30, 0, 20) < Connection.score(30, 0, 0),
                "score=" + Connection.score(30, 0, 20));
        check("jitter lowers the score", Connection.score(30, 40, 0) < 95,
                "score=" + Connection.score(30, 40, 0));
        check("score stays in range", Connection.score(9999, 9999, 100) == 0);
    }

    private static void testSubtick() throws Exception {
        System.out.println("[subtick]");
        check("sub clamps above 1", new InputCommand(1, 1, 5f, 0).sub == 1f);
        check("sub clamps below 0", new InputCommand(1, 1, -5f, 0).sub == 0f);
        check("sub keeps the fraction", Math.abs(new InputCommand(1, 1, 0.375f, 0).sub - 0.375f) < 1e-6f);

        GameConfig cfg = new GameConfig();
        cfg.applySize(0);
        cfg.cellsPerSec = 2;
        cfg.tickRate = 64;
        cfg.subtick = true;
        cfg.subSteps = 4;
        cfg.walls = false;
        check("config produces subtick ticks",
                cfg.tickConfig().isSubtick() && cfg.tickConfig().subSteps() == 4);
        GameConfig copy = GameConfig.read(cfg.write(Msg.of("T")), new GameConfig());
        check("subtick survives serialisation", copy.subtick && copy.subSteps == 4);

        GameServer server = new GameServer(0, "Test", cfg);
        server.start();
        Collect c = new Collect();
        c.client = GameClient.connect("127.0.0.1", server.port(), c);
        c.client.send(Msg.of(Protocol.HELLO).putStr("name", "Sub").put("color", 0).putStr("token", "sub-tok"));
        check("subtick client joined", waitFor(() -> c.last(Protocol.WELCOME) != null, 3000));
        int pid = c.last(Protocol.WELCOME) == null ? -1 : c.last(Protocol.WELCOME).num("pid", -1);

        c.client.send(Msg.of(Protocol.START));
        check("subtick round started", waitFor(() -> c.last(Protocol.SNAP) != null, 8000));

        Msg first = c.last(Protocol.SNAP);
        int firstTick = first == null ? 1 : first.num("t", 1);
        int dir = c.snakeDir(pid);
        int target = Math.floorMod(dir + 1, 4);
        int alt = Math.floorMod(dir + 3, 4);
        int at = firstTick + 8;
        c.client.send(Msg.of(Protocol.IN).put("d", target).put("t", at).put("s", "0.950").put("n", 1));
        c.client.send(Msg.of(Protocol.IN).put("d", alt).put("t", at).put("s", "0.150").put("n", 2));

        check("same-tick inputs arrived", waitFor(() -> {
            Msg s = c.last(Protocol.SNAP);
            return s != null && s.num("t", 0) >= at && s.num("si", 0) >= 2;
        }, 4000));
        Msg s = c.last(Protocol.SNAP);
        check("server reports subtick mode", s != null && s.num("se", 0) == 1 && s.num("ss", 0) == 4);
        check("both sub inputs received", s != null && s.num("si", 0) >= 2,
                s == null ? "no snapshot" : "in=" + s.num("si", 0));
        check("same-tick inputs merged", s != null && s.num("sm", 0) >= 1 && s.num("sx", 0) >= 1,
                s == null ? "no snapshot" : "merged=" + s.num("sm", 0) + " same=" + s.num("sx", 0));
        check("sub ordering has no errors", s != null && s.num("so", 0) == 0,
                s == null ? "no snapshot" : "errors=" + s.num("so", 0));
        check("bucket histogram is reported", s != null && s.raw("sb").split(",").length == 4,
                s == null ? "no snapshot" : s.raw("sb"));
        check("higher sub wins the turn", waitFor(() -> c.snakeDir(pid) == target, 4000),
                "dir=" + c.snakeDir(pid) + ", expected " + target);

        Msg latest = c.last(Protocol.SNAP);
        int next = (latest == null ? at : latest.num("t", at)) + 6;
        c.client.send(Msg.of(Protocol.IN).put("d", target).put("t", next).put("s", "oops").put("n", 3));
        check("malformed sub does not break the server", waitFor(() -> {
            Msg x = c.last(Protocol.SNAP);
            return x != null && x.num("t", 0) > next;
        }, 3000) && c.client.isOpen());

        c.client.close();
        server.stop();
    }

    private static void testVoiceEngine() {
        System.out.println("[engine: voice]");
        VoiceConfig cfg = new VoiceConfig().withJitter(2, 6, false).withLimits(640, 60, 96_000);
        VoiceJitterBuffer buffer = new VoiceJitterBuffer(cfg);
        short[] pcm = new short[320];
        long t = 1_000_000_000L;
        buffer.push(10, pcm, t);
        check("jitter waits for target", buffer.poll(t + 20_000_000L) == null);
        buffer.push(11, pcm, t + 20_000_000L);
        short[] first = buffer.poll(t + 40_000_000L);
        check("jitter releases in order", first != null && buffer.played() == 1);
        check("duplicate voice frame rejected", !buffer.push(11, pcm, t + 60_000_000L));
        check("late voice frame rejected", !buffer.push(9, pcm, t + 60_000_000L));
        buffer.push(13, pcm, t + 80_000_000L);
        short[] next = buffer.poll(t + 100_000_000L);
        short[] concealed = buffer.poll(t + 120_000_000L);
        check("gap is concealed", next != null && concealed != null && buffer.concealed() >= 1);

        VoiceGuard guard = new VoiceGuard(cfg);
        check("voice guard accepts first frame", guard.accept(1, pcm, t));
        check("voice guard rejects duplicate", !guard.accept(1, pcm, t + 20_000_000L));
        check("voice guard rejects oversized", !guard.accept(2, new short[4000], t + 40_000_000L));
        boolean intervalRejected = !guard.accept(3, pcm, t + 1_000_000L);
        check("voice guard enforces frame spacing", intervalRejected);

        VoiceConfig limited = new VoiceConfig().withJitter(1, 4, false).withLimits(640, 4, 96_000);
        VoiceGuard burst = new VoiceGuard(limited);
        int accepted = 0;
        long now = 2_000_000_000L;
        for (int i = 0; i < 8; i++) {
            if (burst.accept(i + 1, pcm, now + i * 20_000_000L)) accepted++;
        }
        check("voice guard enforces packet rate", accepted == 4, "accepted " + accepted);
        check("voice stats expose rejects", guard.rejected() > 0 && burst.rejectedRate() > 0);
    }

    private static void testRenderContinuous() {
        System.out.println("[render continuity]");
        int[] xs = {5, 4, 3};
        int[] ys = {5, 5, 5};
        double[] at0 = snakenet.client.SnakeRenderMath.pointsAt(xs, ys, SnakeState.RIGHT, 0, 0);
        double[] at1 = snakenet.client.SnakeRenderMath.pointsAt(xs, ys, SnakeState.RIGHT, 0, SnakeState.UNITS);
        double[] at15 = snakenet.client.SnakeRenderMath.pointsAt(xs, ys, SnakeState.RIGHT, 0, SnakeState.UNITS * 1.5);
        check("continuous render starts in cell", Math.abs(at0[0] - 5) < 1e-6);
        check("continuous render crosses one cell", Math.abs(at1[0] - 6) < 1e-6);
        check("continuous render keeps a fraction", Math.abs(at15[0] - 6.5) < 1e-6,
                "x=" + at15[0]);
        check("continuous render keeps body attached", Math.abs(at15[2] - 5.5) < 1e-6,
                "body=" + at15[2]);
    }

    private static void testRenderRates() {
        System.out.println("[render at 16 and 32 Hz]");
        for (int rate : new int[]{16, 32}) {
            int units = Math.round(9 * 1024f / rate);
            List<int[]> xs = new ArrayList<>();
            List<int[]> ys = new ArrayList<>();
            List<Integer> acc = new ArrayList<>();
            int[] x = {5, 4, 3, 2};
            int[] y = {5, 5, 5, 5};
            int a = 0;
            xs.add(x.clone());
            ys.add(y.clone());
            acc.add(a);
            for (int k = 1; k <= 40; k++) {
                a += units;
                if (a >= 1024) {
                    a -= 1024;
                    for (int i = x.length - 1; i > 0; i--) {
                        x[i] = x[i - 1];
                        y[i] = y[i - 1];
                    }
                    x[0]++;
                }
                xs.add(x.clone());
                ys.add(y.clone());
                acc.add(a);
            }
            double prev = Double.NaN;
            double maxJump = 0;
            for (int frame = 0; frame <= 60; frame++) {
                double pos = frame / 60.0 * rate;
                int tick = Math.min(xs.size() - 1, (int) Math.floor(pos));
                double sub = pos - Math.floor(pos);
                double[] pts = snakenet.client.SnakeRenderMath.pointsAt(
                        xs.get(tick), ys.get(tick), SnakeState.RIGHT, acc.get(tick), units * sub);
                if (!Double.isNaN(prev)) maxJump = Math.max(maxJump, Math.abs(pts[0] - prev));
                prev = pts[0];
            }
            check(rate + " Hz render has no jumps", maxJump < 0.3,
                    "max jump " + String.format(java.util.Locale.ROOT, "%.3f", maxJump) + " cells per frame");
            check(rate + " Hz render keeps speed", Math.abs(prev - 5 - 9) < 1.0,
                    "travelled " + String.format(java.util.Locale.ROOT, "%.2f", prev - 5) + " cells");
        }
    }

    private static void testGameFactories() {
        System.out.println("[game: tuning factories]");
        GameConfig cfg = new GameConfig();
        cfg.preciseSpin = false;
        cfg.maxCatchUp = 2;
        cfg.spinWindowMicros = 500;
        cfg.tickPriority = Thread.NORM_PRIORITY;
        cfg.predictionSnap = 3.5;
        cfg.predictionHistory = 24;
        cfg.maxAdvanceTicks = 12;
        cfg.interpolationDelayMs = 90;
        cfg.voiceMode = 1;
        cfg.voiceJitterTarget = 2;
        cfg.voiceJitterMax = 7;

        TickConfig tick = cfg.tickConfig();
        check("game controls spin", !tick.precise());
        check("game controls catch-up", tick.maxCatchUp() == 2);
        check("game controls spin window", tick.spinWindowNanos() == 500_000L);
        check("game controls tick priority", tick.threadPriority() == Thread.NORM_PRIORITY);

        PredictionConfig prediction = cfg.predictionConfig();
        check("game controls prediction snap", prediction.snapThreshold() == 3.5);
        check("game controls prediction history", prediction.historyTicks() == 24);
        check("game controls prediction lead", prediction.maxAdvanceTicks() == 12);
        check("game controls interpolation delay", prediction.interpolationDelayMs() == 90);

        VoiceConfig voice = cfg.voiceConfig();
        check("game controls voice mode", voice.mode() == VoiceMode.WEBRTC);
        check("game controls voice jitter", voice.jitterTargetFrames() == 2 && voice.jitterMaxFrames() == 7);

        NetConfig net = cfg.netConfig(true);
        check("game controls transport mtu", net.mtu() == cfg.mtu);
        check("game controls voice budget", net.voicePacketsPerSec() == cfg.voiceMaxPacketsPerSec);
    }

    private static void testTransport() throws Exception {
        System.out.println("[engine: transport]");
        List<String> received = new CopyOnWriteArrayList<>();
        NetServer server = new NetServer(0, new NetServer.Listener() {
            @Override
            public void onConnect(Connection connection) {
            }

            @Override
            public void onMessage(Connection connection, byte[] payload) {
                received.add(new String(payload, StandardCharsets.UTF_8));
                connection.send("echo".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void onDisconnect(Connection connection, String reason) {
            }
        });
        server.start();
        check("server started", server.port() > 0);

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
        client.send("hello".getBytes(StandardCharsets.UTF_8));
        check("message delivered", waitFor(() -> received.contains("hello"), 2000));
        check("reply delivered", waitFor(() -> echoed.contains("echo"), 2000));
        check("clock synchronised", waitFor(() -> client.clock().synced(), 3000),
                "rtt=" + client.clock().rttMs());
        check("RTT is reasonable", client.clock().rttMs() >= 0 && client.clock().rttMs() < 500,
                client.clock().rttMs() + " ms");
        check("client uses UDP", "UDP".equals(client.protocol()), client.protocol());
        check("server measures ping", waitFor(
                () -> server.connections().stream().anyMatch(c -> c.rttMs() >= 0), 3000),
                "rtt=" + server.connections().stream().mapToInt(c -> c.rttMs()).max().orElse(-1));
        check("quality score is available", waitFor(() -> client.quality() > 0, 3000),
                "quality=" + client.quality());

        List<String> echoedTcp = new CopyOnWriteArrayList<>();
        NetClient tcp = NetClient.connect("127.0.0.1", server.port(), NetConfig.tcp(),
                new NetClient.Listener() {
                    @Override
                    public void onMessage(byte[] payload) {
                        echoedTcp.add(new String(payload, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onClosed(String reason) {
                    }
                });
        tcp.send("over tcp".getBytes(StandardCharsets.UTF_8));
        check("TCP works as a fallback", waitFor(() -> echoedTcp.contains("echo"), 2000));
        check("client uses TCP", "TCP".equals(tcp.protocol()), tcp.protocol());
        tcp.close();

        client.close();
        server.stop();
    }

    private static void testFloodProtection() throws Exception {
        System.out.println("[engine: flood protection]");
        List<String> messages = new CopyOnWriteArrayList<>();
        NetServer server = new NetServer(0, NetConfig.udp(false).withRateLimit(16, 4096), new NetServer.Listener() {
            @Override
            public void onConnect(Connection connection) {
            }

            @Override
            public void onMessage(Connection connection, byte[] payload) {
                messages.add(new String(payload, StandardCharsets.UTF_8));
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
                    }

                    @Override
                    public void onClosed(String reason) {
                    }
                });
        for (int i = 0; i < 80; i++) {
            client.send(("flood-" + i).getBytes(StandardCharsets.UTF_8));
        }
        check("flood is throttled", waitFor(
                () -> server.connections().stream().anyMatch(c -> c.droppedPackets() > 0), 3000));
        check("not every flood packet reaches the app", messages.size() < 80,
                "delivered=" + messages.size());
        client.close();
        server.stop();
    }

    private static void testAutoReconnect() throws Exception {
        System.out.println("[engine: auto-reconnect]");
        AtomicBoolean lost = new AtomicBoolean();
        AtomicBoolean restored = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        NetConfig cfg = NetConfig.udp(false).withReconnect(true, 8000)
                .withSilentLoss(400).withDeadAfter(2000);
        int port = NetInfo.freePortFrom(47500);

        NetServer.Listener listener = new NetServer.Listener() {
            @Override
            public void onConnect(Connection connection) {
            }

            @Override
            public void onMessage(Connection connection, byte[] payload) {
            }

            @Override
            public void onDisconnect(Connection connection, String reason) {
            }
        };

        NetServer server = new NetServer(port, NetConfig.udp(false), listener);
        server.start();
        NetClient client = NetClient.connect("127.0.0.1", port, cfg, new NetClient.Listener() {
            @Override
            public void onMessage(byte[] payload) {
            }

            @Override
            public void onClosed(String reason) {
                closed.set(true);
            }

            @Override
            public void onLinkLost(String reason, long retryForMs) {
                lost.set(true);
            }

            @Override
            public void onLinkRestored() {
                restored.set(true);
            }
        });
        check("auto-reconnect client is online", client.isOpen());

        server.stop();
        check("link loss detected", waitFor(lost::get, 4000));

        NetServer restarted = null;
        long deadline = System.currentTimeMillis() + 4000;
        while (restarted == null && System.currentTimeMillis() < deadline) {
            try {
                NetServer next = new NetServer(port, NetConfig.udp(false), listener);
                next.start();
                restarted = next;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        check("server restarted on the same port", restarted != null);
        check("link restored", waitFor(restored::get, 5000));
        check("session stayed open", client.isOpen() && !closed.get());

        client.close();
        if (restarted != null) restarted.stop();
    }

    private static void testUdpReconnectSameAddress() throws Exception {
        System.out.println("[engine: UDP reconnect]");
        List<Connection> connects = new CopyOnWriteArrayList<>();
        List<String> disconnects = new CopyOnWriteArrayList<>();
        List<String> messages = new CopyOnWriteArrayList<>();
        NetServer server = new NetServer(0, NetConfig.udp(false), new NetServer.Listener() {
            @Override
            public void onConnect(Connection connection) {
                connects.add(connection);
            }

            @Override
            public void onMessage(Connection connection, byte[] payload) {
                messages.add(new String(payload, StandardCharsets.UTF_8));
            }

            @Override
            public void onDisconnect(Connection connection, String reason) {
                disconnects.add(reason);
            }
        });
        server.start();
        try (DatagramSocket socket = new DatagramSocket()) {
            RawUdpClient raw = new RawUdpClient(socket, InetAddress.getByName("127.0.0.1"), server.port());

            raw.connect();
            check("UDP: first connect", raw.awaitAccept(1, 2000));
            check("UDP: one onConnect", waitFor(() -> connects.size() == 1, 2000),
                    "onConnect=" + connects.size());
            Connection first = connects.isEmpty() ? null : connects.get(0);
            Object marker = new Object();
            if (first != null) first.attachment = marker;

            raw.reliable(1, "one".getBytes(StandardCharsets.UTF_8));
            check("UDP: message delivered", waitFor(() -> messages.contains("one"), 2000),
                    "received: " + messages);

            raw.connect();
            check("UDP: server accepted a second CONNECT", raw.awaitAccept(2, 2000));
            check("UDP: no second onConnect", connects.size() == 1, "onConnect=" + connects.size());
            check("UDP: no onDisconnect", disconnects.isEmpty(), String.join(",", disconnects));
            check("UDP: same Connection object", first != null && server.connections().size() == 1
                    && server.connections().get(0) == first, "connections: " + server.connections().size());
            check("UDP: attachment preserved", first != null && first.attachment == marker);

            raw.reliable(1, "two".getBytes(StandardCharsets.UTF_8));
            check("UDP: message after reconnect", waitFor(() -> messages.contains("two"), 2000),
                    "received: " + messages);
        } finally {
            server.stop();
        }
    }

    private static final class RawUdpClient {
        private static final byte T_DATA_REL = 10;
        private static final byte T_ACCEPT = 15;
        private static final byte T_CONNECT = 14;

        private final DatagramSocket socket;
        private final InetAddress host;
        private final int port;
        private int accepts;

        RawUdpClient(DatagramSocket socket, InetAddress host, int port) throws Exception {
            this.socket = socket;
            this.host = host;
            this.port = port;
            socket.setSoTimeout(100);
        }

        void connect() throws Exception {
            byte[] data = new byte[1 + 4 + 4];
            data[0] = T_CONNECT;
            socket.send(new DatagramPacket(data, data.length, host, port));
        }

        void reliable(int id, byte[] payload) throws Exception {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1 + 4 + 4 + 4 + 1 + 1 + payload.length);
            b.put(T_DATA_REL).putInt(0).putInt(0).putInt(id).put((byte) 0).put((byte) 1).put(payload);
            byte[] data = b.array();
            socket.send(new DatagramPacket(data, data.length, host, port));
        }

        boolean awaitAccept(int expected, long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[2048];
            while (accepts < expected && System.currentTimeMillis() < end) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException ignored) {
                    continue;
                }
                if (packet.getLength() >= 1 && buffer[0] == T_ACCEPT) accepts++;
            }
            return accepts >= expected;
        }
    }

    private static void testDiscovery() throws Exception {
        System.out.println("[engine: discovery]");
        List<LanDiscovery.Found> seen = new CopyOnWriteArrayList<>();
        LanDiscovery.Scanner scanner = new LanDiscovery.Scanner(list -> {
            seen.clear();
            seen.addAll(list);
        });
        scanner.start();
        String payload = "SNKN1|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Test Game".getBytes(StandardCharsets.UTF_8)) + "|45999|2|8";
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), LanDiscovery.PORT));
        }
        boolean got = waitFor(() -> !seen.isEmpty(), 3000);
        check("packet discovered", got);
        check("name and port parsed", got && seen.get(0).port == 45999 && seen.get(0).name.equals("Test Game"),
                got ? seen.get(0).name + ":" + seen.get(0).port : "no data");
        scanner.close();
    }

    private static void testBeacon() throws Exception {
        System.out.println("[engine: server beacon]");
        List<LanDiscovery.Found> seen = new CopyOnWriteArrayList<>();
        LanDiscovery.Scanner scanner = new LanDiscovery.Scanner(list -> {
            seen.clear();
            seen.addAll(list);
        });
        scanner.start();
        LanDiscovery.Beacon beacon = new LanDiscovery.Beacon("My Game", 46001, () -> 3, () -> 8);
        beacon.start();
        boolean got = waitFor(() -> seen.stream().anyMatch(f -> f.port == 46001), 6000);
        LanDiscovery.Found found = seen.stream().filter(f -> f.port == 46001).findFirst().orElse(null);
        check("host is visible on the network", got);
        check("game name is broadcast", got && found != null && found.name.equals("My Game"));
        check("player counters are broadcast", got && found != null && found.players == 3 && found.max == 8);
        beacon.close();
        scanner.close();
    }

    private static void testMsg() {
        System.out.println("[protocol]");
        Msg m = Msg.of("CHAT").putStr("from", "Вася|Пупкин, 100%").putStr("text", "привет\nмир").put("c", 3);
        Msg parsed = Msg.parse(m.encode());
        check("cyrillic and separators survive", parsed != null && parsed.str("from").equals("Вася|Пупкин, 100%"));
        check("newline survives", parsed != null && parsed.str("text").equals("привет\nмир"));
        check("garbage is rejected", Msg.parse("|||") == null);
    }

    private static void testConfig() {
        System.out.println("[game settings]");
        GameConfig c = new GameConfig();
        c.applySize(2);
        c.applySpeed(3);
        c.applyTickRate(3);
        check("size applied", c.cols == 40 && c.rows == 26);
        check("speed applied", c.cellsPerSec == 15);
        check("tick rate applied", c.tickRate == 128);
        check("units per tick", c.unitsPerTick() == Math.round(15 * 1024 / 128f));
        Msg m = c.write(Msg.of("T"));
        GameConfig back = GameConfig.read(m, new GameConfig());
        check("serialisation", back.cols == 40 && back.tickRate == 128 && back.cellsPerSec == 15);
        c.voice = false;
        c.voiceMode = 1;
        c.voiceJitterTarget = 2;
        c.voiceJitterMax = 6;
        GameConfig voiceBack = GameConfig.read(c.write(Msg.of("T")), new GameConfig());
        check("voice settings serialise", !voiceBack.voice && voiceBack.voiceMode == 1
                && voiceBack.voiceJitterTarget == 2 && voiceBack.voiceJitterMax == 6);
        check("name is sanitised", GameConfig.sanitizeName("  Ва\u0007ся   Пупкин  ").equals("Вася Пупкин"));
        check("name fits 16 chars", GameConfig.sanitizeName("а".repeat(50)).length() == 16);
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
        public void onVoice(int playerId, int sequence, short[] pcm) {
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

        int snakeDir(int playerId) {
            List<Msg> frame = lastFrameWith(Protocol.SNAP);
            if (frame == null) return -1;
            for (Msg m : frame) {
                if (m.is(Protocol.SNAKE) && m.num("p", -1) == playerId) return m.num("d", -1);
            }
            return -1;
        }

        int maxTick() {
            int max = 0;
            synchronized (frames) {
                for (List<Msg> f : frames) {
                    for (Msg m : f) if (m.is(Protocol.SNAP)) max = Math.max(max, m.num("t", 0));
                }
            }
            return max;
        }

        int lobbyPlayers() {
            List<Msg> frame = lastFrameWith(Protocol.LOBBY);
            if (frame == null) return -1;
            int n = 0;
            for (Msg m : frame) if (m.is(Protocol.PLAYER)) n++;
            return n;
        }
    }

    private static void testSecondRound() throws Exception {
        System.out.println("[second round]");
        GameConfig cfg = new GameConfig();
        cfg.applySize(0);
        cfg.cellsPerSec = 15;
        cfg.tickRate = 64;
        GameServer server = new GameServer(0, "Test", cfg);
        server.start();
        int port = server.port();

        List<Collect> clients = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Collect c = new Collect();
            c.client = GameClient.connect("127.0.0.1", port, c);
            clients.add(c);
            c.client.send(Msg.of(Protocol.HELLO).putStr("name", "P" + i).put("color", i));
            Thread.sleep(120);
        }
        Collect host = clients.get(0);
        check("both players are in the lobby", waitFor(() -> host.lobbyPlayers() == 2, 3000));

        host.client.send(Msg.of(Protocol.START));
        check("first round finished", waitFor(() -> host.count(Protocol.OVER) > 0, 20000));
        int tickRound1 = host.maxTick();

        host.client.send(Msg.of(Protocol.AGAIN));
        check("back in the lobby", waitFor(() -> {
            Msg l = host.last(Protocol.LOBBY);
            return l != null && l.num("phase", -1) == Protocol.PHASE_LOBBY;
        }, 4000));

        host.client.send(Msg.of(Protocol.START));
        int before = host.count(Protocol.SNAP);
        check("second round started", waitFor(() -> host.count(Protocol.SNAP) > before + 5, 6000));
        check("ticks never go backwards", host.maxTick() > tickRound1,
                "before " + tickRound1 + ", now " + host.maxTick());

        int pid = host.last(Protocol.WELCOME).num("pid", -1);
        int tick = host.last(Protocol.SNAP) == null ? 1 : host.last(Protocol.SNAP).num("t", 1);
        host.frames.clear();
        host.client.send(Msg.of(Protocol.IN).put("d", 0).put("t", tick + 4).put("s", 0.5).put("n", 1));
        check("input works in the second round", waitFor(() -> hasAck(host, pid, 1), 4000));

        for (Collect c : clients) c.client.close();
        server.stop();
    }

    private static void testReconnect() throws Exception {
        System.out.println("[session reconnect]");
        GameConfig cfg = new GameConfig();
        cfg.applySize(0);
        GameServer server = new GameServer(0, "Test", cfg);
        server.start();
        int port = server.port();

        Collect first = new Collect();
        first.client = GameClient.connect("127.0.0.1", port, first);
        first.client.send(Msg.of(Protocol.HELLO).putStr("name", "Vasya").put("color", 0).putStr("token", "tok-A"));
        check("first session joined", waitFor(() -> first.lobbyPlayers() == 1, 3000));

        Collect second = new Collect();
        second.client = GameClient.connect("127.0.0.1", port, second);
        second.client.send(Msg.of(Protocol.HELLO).putStr("name", "Vasya").put("color", 0).putStr("token", "tok-A"));
        check("old session kicked", waitFor(() -> first.count(Protocol.KICK) > 0, 2000));
        check("player is not duplicated", waitFor(() -> second.lobbyPlayers() == 1, 3000),
                "was " + second.lobbyPlayers());

        second.client.send(Msg.of(Protocol.HELLO).putStr("name", "Vasya").put("color", 0).putStr("token", "tok-A"));
        Thread.sleep(250);
        check("repeated HELLO does not duplicate", second.lobbyPlayers() == 1,
                "was " + second.lobbyPlayers());

        second.client.close();
        server.stop();
    }

    private static void testReconnectDuringRound() throws Exception {
        System.out.println("[in-game reconnect]");
        GameConfig cfg = new GameConfig();
        cfg.applySize(3);
        cfg.cellsPerSec = 2;
        cfg.tickRate = 32;
        cfg.walls = false;
        GameServer server = new GameServer(0, "Test", cfg);
        server.start();
        int port = server.port();

        Collect host = new Collect();
        host.client = GameClient.connect("127.0.0.1", port, host);
        host.client.send(Msg.of(Protocol.HELLO).putStr("name", "Host").put("color", 0).putStr("token", "host-tok"));
        check("host joined", waitFor(() -> host.lobbyPlayers() == 1, 3000));

        Collect player = new Collect();
        player.client = GameClient.connect("127.0.0.1", port, player);
        player.client.send(Msg.of(Protocol.HELLO).putStr("name", "Player").put("color", 1).putStr("token", "p-tok"));
        check("player joined", waitFor(() -> player.last(Protocol.WELCOME) != null, 3000));
        int oldPid = player.last(Protocol.WELCOME).num("pid", -1);

        host.client.send(Msg.of(Protocol.START));
        check("snake is on the board", waitFor(() -> hasAliveSnake(player, oldPid), 8000));

        Collect back = new Collect();
        back.client = GameClient.connect("127.0.0.1", port, back);
        back.client.send(Msg.of(Protocol.HELLO).putStr("name", "Player").put("color", 1).putStr("token", "p-tok"));
        check("session restored", waitFor(() -> back.last(Protocol.WELCOME) != null, 3000));
        int newPid = back.last(Protocol.WELCOME).num("pid", -1);
        check("player id is kept", newPid == oldPid, "before " + oldPid + ", now " + newPid);
        check("old client received KICK", waitFor(() -> player.closed || player.count(Protocol.KICK) > 0, 3000));
        check("snake is alive after reconnect", waitFor(() -> hasAliveSnake(back, oldPid), 3000));
        check("player is not duplicated after reconnect", waitFor(() -> back.lobbyPlayers() == 2, 3000),
                "was " + back.lobbyPlayers());

        back.client.close();
        host.client.close();
        server.stop();
    }

    private static boolean hasAliveSnake(Collect c, int playerId) {
        List<Msg> frame = c.lastFrameWith(Protocol.SNAP);
        if (frame == null) return false;
        for (Msg m : frame) {
            if (m.is(Protocol.SNAKE) && m.num("p", -1) == playerId) return m.num("a", 0) == 1;
        }
        return false;
    }

    private static void testFullRound() throws Exception {
        System.out.println("[networked round]");
        GameConfig cfg = new GameConfig();
        cfg.applySize(0);
        cfg.cellsPerSec = 15;
        cfg.tickRate = 64;
        cfg.subtick = true;
        GameServer server = new GameServer(0, "Test", cfg);
        server.start();
        int port = server.port();
        check("server listens on a port", port > 0);

        List<Collect> clients = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Collect c = new Collect();
            c.client = GameClient.connect("127.0.0.1", port, c);
            clients.add(c);
            c.client.send(Msg.of(Protocol.HELLO).putStr("name", "Test" + i).put("color", i));
            Thread.sleep(150);
        }
        final Collect host = clients.get(0);
        check("WELCOME received", host.last(Protocol.WELCOME) != null);
        check("tick rate in WELCOME", host.last(Protocol.WELCOME) != null
                && host.last(Protocol.WELCOME).num("rate", 0) == 64);
        check("3 players in the lobby", waitFor(() -> host.lobbyPlayers() == 3, 3000), "was " + host.lobbyPlayers());

        host.client.send(Msg.of(Protocol.BOT).put("op", "add"));
        host.client.send(Msg.of(Protocol.BOT).put("op", "add"));
        check("bots added", waitFor(() -> host.lobbyPlayers() == 5, 3000), "was " + host.lobbyPlayers());
        host.client.send(Msg.of(Protocol.BOT).put("op", "del"));
        host.client.send(Msg.of(Protocol.BOT).put("op", "del"));
        check("bots removed", waitFor(() -> host.lobbyPlayers() == 3, 3000), "was " + host.lobbyPlayers());

        host.client.send(Msg.of(Protocol.START));
        check("countdown started", waitFor(() -> host.count(Protocol.COUNT) >= 1, 4000));
        check("snapshots are flowing", waitFor(() -> clients.get(1).count(Protocol.SNAP) > 5, 6000),
                "snapshots: " + clients.get(1).count(Protocol.SNAP));

        check("snake is moving", clients.get(1).headsOf(1).size() > 1,
                "unique heads: " + clients.get(1).headsOf(1).size());

        Msg snap = clients.get(1).last(Protocol.SNAP);
        int myPid = clients.get(1).last(Protocol.WELCOME).num("pid", -1);
        int tick = snap == null ? 1 : snap.num("t", 1);
        int rate = snap == null ? 64 : snap.num("rate", 64);
        int nx = 1 + (int) Math.ceil(rate * 0.05);
        clients.get(1).client.send(Msg.of(Protocol.IN).put("d", 3).put("t", tick + nx).put("s", 0.5).put("n", 1));
        check("input acknowledged by the server", waitFor(() -> hasAck(clients.get(1), myPid, 1), 3000),
                "pid=" + myPid);

        check("round finished", waitFor(() -> host.count(Protocol.OVER) > 0, 20000),
                "snapshots: " + host.count(Protocol.SNAP));
        check("results for 3 players", host.lastCount(Protocol.RESULT) == 3,
                "rows: " + host.lastCount(Protocol.RESULT));

        host.client.send(Msg.of(Protocol.AGAIN));
        check("back in the lobby", waitFor(() -> {
            Msg l = host.last(Protocol.LOBBY);
            return l != null && l.num("phase", -1) == Protocol.PHASE_LOBBY;
        }, 4000));

        Collect late = new Collect();
        late.client = GameClient.connect("127.0.0.1", port, late);
        late.client.send(Msg.of(Protocol.HELLO).putStr("name", "Late").put("color", 4));
        check("late player joined", waitFor(() -> late.last(Protocol.WELCOME) != null, 3000));
        check("lobby updated to 4", waitFor(() -> host.lobbyPlayers() == 4, 3000), "was " + host.lobbyPlayers());

        server.stop();
        check("clients detect the shutdown", waitFor(() -> host.client.isReconnecting(), 4000));
        for (Collect c : clients) c.client.close();
        late.client.close();
    }

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
