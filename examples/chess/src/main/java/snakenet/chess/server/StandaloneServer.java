package snakenet.chess.server;

import netcore.net.NetInfo;
import snakenet.chess.core.GameConfig;

import java.util.concurrent.CountDownLatch;

/**
 * Headless dedicated chess server. Start it from the console and let clients create
 * their own lobbies; no window and no local player are created.
 *
 * <pre>
 * java -cp "ChessNET.jar;NetCore.jar" snakenet.chess.server.StandaloneServer --port=45801
 * </pre>
 */
public final class StandaloneServer {

    public static void main(String[] args) throws Exception {
        int port = 45801;
        String name = null;

        for (String arg : args) {
            if (arg.startsWith("--port=")) port = parseInt(arg.substring("--port=".length()), port);
            if (arg.startsWith("--name=")) name = arg.substring("--name=".length());
        }

        GameConfig cfg = new GameConfig();
        cfg.maxPlayers = 2;
        cfg.chessSide = 2;
        cfg.chessBotDepth = 2;
        String serverName = name == null || name.isBlank() ? "Chess server" : name;

        GameServer server = new GameServer(port, serverName, cfg);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "dedicated-server-stop"));

        System.out.println();
        System.out.println("  " + serverName + " ready");
        System.out.println("  game     chess");
        System.out.println("  address  " + NetInfo.primaryIp() + ":" + server.port());
        System.out.println("  clients  join this address, then create a lobby");
        System.out.println("  stop     Ctrl+C");
        System.out.println();

        new CountDownLatch(1).await();
    }

    private static int parseInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 && parsed <= 65535 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
