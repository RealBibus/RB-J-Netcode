package snakenet.chess.tools;

import snakenet.chess.Prefs;
import snakenet.chess.core.GameConfig;
import netcore.netcode.Msg;
import snakenet.chess.net.Net;
import snakenet.chess.ui.App;
import snakenet.chess.ui.GameScreen;
import snakenet.chess.ui.LobbyScreen;
import snakenet.chess.ui.Screen;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the chess client screens to PNG files for the documentation.
 */
public final class UiShots {

    private static App app;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "docs/screens");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir.getAbsolutePath());
        }
        netcore.net.NetInfo.setPreviewIp("192.168.1.42");
        Prefs preview = new Prefs();
        preview.setPreview("name", "Player");
        preview.setPreview("port.chess", "45801");
        app = new App(preview);
        app.prepareOffscreenWindow();
        app.setPreviewId(1);
        app.setPreviewAddress("192.168.1.42:45801");

        app.log("engine netcore 2.0 initialised (UDP + TCP)");
        app.log("dedicated server connection only - no local server is created");
        app.log("enter the chess server address, then connect");
        app.connectScreen().onShow();
        shoot(app.connectScreen(), new File(dir, "1-connect.png"), 900);
        shoot(app.browserScreen(), new File(dir, "2-hub.png"), 30);

        app.setPreviewHost(true);
        LobbyScreen lobby = app.lobbyScreen();
        feedLobby(lobby);
        shoot(lobby, new File(dir, "3-lobby.png"), 30);

        GameScreen game = app.gameScreen();
        feedGame(game);
        shoot(game, new File(dir, "4-chess.png"), 12);

        game.getActionMap().get("chesshelp").actionPerformed(
                new java.awt.event.ActionEvent(game, java.awt.event.ActionEvent.ACTION_PERFORMED, "help"));
        shoot(game, new File(dir, "4a-help.png"), 6);
        game.getActionMap().get("chesshelp").actionPerformed(
                new java.awt.event.ActionEvent(game, java.awt.event.ActionEvent.ACTION_PERFORMED, "help"));

        game.getActionMap().get("escape").actionPerformed(
                new java.awt.event.ActionEvent(game, java.awt.event.ActionEvent.ACTION_PERFORMED, "escape"));
        shoot(game, new File(dir, "4b-pause.png"), 6);
        game.getActionMap().get("escape").actionPerformed(
                new java.awt.event.ActionEvent(game, java.awt.event.ActionEvent.ACTION_PERFORMED, "escape"));

        app.setPreviewLinkLost(true);
        shoot(game, new File(dir, "7-connection.png"), 8);
        app.setPreviewLinkLost(false);

        feedOver(game);
        shoot(game, new File(dir, "8-results.png"), 20);

        System.out.println("done: " + dir.getAbsolutePath());
        System.exit(0);
    }

    private static void shoot(Screen screen, File file, int ticks) throws Exception {
        screen.setSize(1280, 800);
        screen.doLayout();
        for (int i = 0; i < ticks; i++) screen.tick(0.0145 + (i % 7) * 0.0009);
        BufferedImage img = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        screen.paint(g);
        g.dispose();
        ImageIO.write(img, "png", file);
        System.out.println("  " + file.getName());
    }

    private static Msg player(int id, String name, int color, boolean ready, boolean bot, boolean host) {
        return Msg.of(Net.PLAYER)
                .put("id", id)
                .putStr("name", name)
                .put("c", color)
                .put("ready", ready ? 1 : 0)
                .put("bot", bot ? 1 : 0)
                .put("host", host ? 1 : 0)
                .put("cc", bot ? 1 : 0)
                .put("alive", 1)
                .put("pg", bot ? -1 : 28);
    }

    private static Msg chat(String from, String text, int color, int kind) {
        return Msg.of(Net.CHAT_OUT).putStr("from", from).putStr("text", text).put("c", color).put("kind", kind);
    }

    private static void feedLobby(LobbyScreen lobby) {
        List<Msg> frame = new ArrayList<>();
        Msg head = Msg.of(Net.LOBBY).put("phase", 0).put("host", 1)
                .put("cs", 0).put("cd", 2).put("max", 2);
        frame.add(head);
        frame.add(player(1, "Vasya", 0, true, false, true));
        frame.add(player(3, "Bot Cobra", 5, false, true, false));
        lobby.onFrame(frame);

        lobby.onFrame(List.of(chat("", "Bot Cobra added", 0, 1)));
        lobby.onFrame(List.of(chat("Vasya", "you are black, good luck", 0, 0)));
    }

    private static void feedGame(GameScreen game) {
        app.roomView().phase.set(Net.PHASE_RUNNING);
        app.roomView().fen.set("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2");
        app.roomView().turn.set(0);
        app.roomView().players.set(2);
        app.roomView().host.set(1);

        List<Msg> roster = new ArrayList<>();
        roster.add(Msg.of(Net.LOBBY).put("phase", 2).put("host", 1)
                .put("cs", 0).put("cd", 2).put("max", 2));
        roster.add(player(1, "Vasya", 0, true, false, true));
        roster.add(player(3, "Bot Cobra", 5, false, true, false));
        game.onFrame(roster);

        GameConfig cfg = new GameConfig();
        cfg.maxPlayers = 2;
        Msg start = cfg.write(Net.STARTED.newMsg()).put("phase", Net.PHASE_RUNNING).put("n", 0);
        game.onFrame(List.of(start));
        game.onFrame(List.of(chat("Vasya", "your move", 0, 0)));
    }

    private static void feedOver(GameScreen game) {
        List<Msg> frame = new ArrayList<>();
        frame.add(Msg.of(Net.LOBBY).put("phase", 3).put("host", 1)
                .put("cs", 0).put("cd", 2).put("max", 2).put("winner", 1).putStr("reason", "checkmate"));
        frame.add(Msg.of(Net.CHESS_END).put("w", 1).putStr("r", "checkmate"));
        game.onFrame(frame);
    }
}
