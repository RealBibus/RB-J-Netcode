package snakenet.chess;

import netcore.netcode.Msg;
import netcore.netcode.MsgType;
import netcore.netcode.Rpc;
import netcore.time.TickConfig;
import snakenet.chess.client.GameClient;
import snakenet.chess.client.RoomView;
import snakenet.chess.core.GameConfig;
import snakenet.chess.net.Net;
import snakenet.chess.server.GameServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Self-test for the standalone chess example: the rules, the config, and a real
 * client/server chess round on a dedicated server.
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    private static final MsgType TEST = MsgType.declare("chess/test");

    public static void main(String[] args) throws Exception {
        System.out.println("Chess NET self-test");
        testTickConfig();
        testMsg();
        testConfig();
        testChess();
        testChessServer();
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
        System.out.println("[engine: tick]");
        TickConfig s = TickConfig.subtick(64, 4);
        check("subtick enables sub-steps", s.isSubtick() && s.subSteps() == 4);
        check("fixed has one sub-step", TickConfig.fixed(64).subSteps() == 1);
        check("rate is clamped", TickConfig.fixed(TickConfig.MAX_RATE).rate() == TickConfig.MAX_RATE);
    }

    private static void testMsg() {
        System.out.println("[engine: messages]");
        Msg m = Msg.of(TEST).put("a", 7).putStr("s", "hello");
        check("int field", m.num("a", -1) == 7);
        check("string field", m.str("s").equals("hello"));
        check("missing int uses fallback", m.num("nope", 42) == 42);

        byte[] wire = netcore.netcode.Wire.encode(m);
        check("message encodes", wire.length > 0);
        List<Msg> back = netcore.netcode.Wire.decode(wire);
        check("message round-trips", back.size() == 1 && back.get(0).num("a", -1) == 7);
    }

    private static void testConfig() {
        System.out.println("[chess: config]");
        GameConfig c = new GameConfig();
        c.maxPlayers = 2;
        c.chessSide = 1;
        c.chessBotDepth = 3;
        c.voice = false;
        GameConfig copy = GameConfig.read(c.write(Msg.of(TEST)), new GameConfig());
        check("max players survive", copy.maxPlayers == 2);
        check("side survives", copy.chessSide == 1);
        check("bot depth survives", copy.chessBotDepth == 3);
        check("voice survives", !copy.voice);
        check("names are sanitised", GameConfig.sanitizeName("  a   b  ").equals("a b"));
        check("blank names fall back", GameConfig.sanitizeName("   ").equals("Player"));
    }

    private static void testChess() {
        System.out.println("[chess: rules]");
        Chess chess = new Chess();
        check("starts with 20 legal moves", chess.legalMoves().size() == 20,
                "legal=" + chess.legalMoves().size());
        check("starts with white to move", chess.turn() == Chess.WHITE);
        check("start position has no check", !chess.isCheck());
        check("start position is not checkmate", !chess.isCheckmate());
        check("random bot move exists", chess.randomLegalMove() != null);
        check("square name", Chess.squareName(28).equals("e4"));
        check("square parse", Chess.square("e4") == 28);

        Chess afterF3 = chess.apply(new Chess.Move(13, 21, Chess.WP, 0, 0, false));
        check("f3 applied and black moves", afterF3.turn() == Chess.BLACK);
        Chess afterE5 = afterF3.apply(new Chess.Move(52, 36, Chess.BP, 0, 0, false));
        Chess afterG4 = afterE5.apply(new Chess.Move(14, 30, Chess.WP, 0, 0, false));
        Chess afterQh4 = afterG4.apply(new Chess.Move(59, 31, Chess.BQ, 0, 0, false));
        check("fool's mate leaves white in check", afterQh4.isCheck());
        check("fool's mate is checkmate", afterQh4.isCheckmate());

        Chess replayed = Chess.fromFen(chess.toFen());
        check("fen roundtrip keeps the board", replayed.toFen().equals(chess.toFen()));
        check("fen roundtrip keeps the turn", replayed.turn() == chess.turn());

        Chess.Move bot = new Chess().bestMove(2);
        check("search returns an opening move", bot != null);
        check("search move is legal", bot != null && new Chess().legalMoves().stream()
                .anyMatch(m -> m.from == bot.from && m.to == bot.to && m.promotion == bot.promotion));
    }

    private static void testChessServer() throws Exception {
        System.out.println("[chess: dedicated server]");
        GameConfig base = new GameConfig();
        base.tickRate = 32;
        base.chessSide = 1;      // the human plays black, so the bot opens as white
        base.chessBotDepth = 1;
        base.voice = false;

        GameServer server = new GameServer(0, "Chess test", base);
        server.start();
        int port = server.port();
        check("server listens on a port", port > 0);

        Collect host = new Collect();
        host.client = GameClient.connect("127.0.0.1", port, host);
        RoomView view = new RoomView();
        host.client.netcode().addBehaviour(view);
        host.client.send(Msg.of(Net.HELLO).putStr("name", "Alice").put("color", 0)
                .putStr("token", "chess-self-test"));
        check("client reached the hub", waitFor(() -> host.last(Net.HUB) != null, 4000));

        host.client.send(base.write(Net.LOBBY_CREATE.newMsg())
                .put("kind", Net.KIND_CHESS).putStr("name", "Chess lobby"));
        check("created a chess lobby", waitFor(() -> host.last(Net.WELCOME) != null, 4000));
        check("lobby reports the chess kind", host.last(Net.WELCOME) != null
                && host.last(Net.WELCOME).num("kind", -1) == Net.KIND_CHESS);
        check("server keeps one room", server.rooms().size() == 1);

        host.client.send(Net.START.newMsg());
        check("board is replicated", waitFor(() -> !view.fen.get().isBlank(), 8000),
                "fen=" + view.fen.get());
        check("bot makes a move", waitFor(() -> host.count(Net.CHESS_MOVE_OUT) > 0, 10000));
        check("turn syncs back to the client", waitFor(() -> view.turn.get() == Chess.BLACK, 5000),
                "turn=" + view.turn.get());

        host.client.send(Net.LOBBY_LEAVE.newMsg());
        check("leaving returns to the hub", waitFor(() -> host.count(Net.LEFT) > 0, 4000));
        check("the abandoned lobby closes", waitFor(() -> server.rooms().isEmpty(), 4000),
                "rooms=" + server.rooms().size());

        host.client.close();
        server.stop();
    }

    private static final class Collect implements GameClient.Listener {
        GameClient client;
        final List<List<Msg>> frames = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFrame(List<Msg> frame) {
            frames.add(frame);
        }

        @Override
        public void onVoice(int playerId, int sequence, short[] pcm) {
        }

        @Override
        public void onClosed(String reason) {
        }

        int count(Rpc<?> rpc) {
            int n = 0;
            synchronized (frames) {
                for (List<Msg> f : frames)
                    for (Msg m : f) if (m.is(rpc)) n++;
            }
            return n;
        }

        Msg last(Rpc<?> rpc) {
            synchronized (frames) {
                for (int i = frames.size() - 1; i >= 0; i--) {
                    List<Msg> f = frames.get(i);
                    for (int k = f.size() - 1; k >= 0; k--) {
                        if (f.get(k).is(rpc)) return f.get(k);
                    }
                }
            }
            return null;
        }
    }
}
