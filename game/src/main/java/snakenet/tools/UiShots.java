package snakenet.tools;

import snakenet.Prefs;
import snakenet.game.GameConfig;
import snakenet.net.Msg;
import snakenet.net.Protocol;
import snakenet.ui.App;
import snakenet.ui.GameScreen;
import snakenet.ui.LobbyScreen;
import snakenet.ui.Screen;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Офскрин-рендер экранов в PNG: удобно смотреть вёрстку без запуска окна.
 * Запуск: java -cp SnakeNET.jar snakenet.tools.UiShots
 */
public final class UiShots {

    private static App app;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "docs/screens");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("не могу создать " + dir.getAbsolutePath());
        }
        netcore.net.NetInfo.setPreviewIp("192.168.1.42");
        Prefs preview = new Prefs();
        preview.setPreview("name", "Игрок");
        preview.setPreview("port", "45800");
        app = new App(preview);
        app.prepareOffscreenWindow();
        app.setPreviewId(1);

        app.log("engine netcore 1.0 initialised (UDP + TCP)");
        app.log("local address 192.168.1.42, port 45800 free");
        app.log("awaiting session: create or join");
        shoot(app.homeScreen(), new File(dir, "1-menu.png"), 900);
        shoot(app.hostScreen(), new File(dir, "2-create.png"), 10);
        shoot(app.joinScreen(), new File(dir, "3-join.png"), 10);

        app.setPreviewHost(true);
        LobbyScreen lobby = app.lobbyScreen();
        feedLobby(lobby);
        shoot(lobby, new File(dir, "4-lobby.png"), 30);

        GameScreen game = app.gameScreen();
        feedGame(game);
        shoot(game, new File(dir, "5-game.png"), 12);

        feedOver(game);
        shoot(game, new File(dir, "6-results.png"), 20);

        System.out.println("готово: " + dir.getAbsolutePath());
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
        return Msg.of(Protocol.PLAYER)
                .put("id", id)
                .putStr("name", name)
                .put("c", color)
                .put("ready", ready ? 1 : 0)
                .put("bot", bot ? 1 : 0)
                .put("host", host ? 1 : 0)
                .put("alive", 1);
    }

    private static Msg chat(String from, String text, int color, int kind) {
        return Msg.of(Protocol.CHAT).putStr("from", from).putStr("text", text).put("c", color).put("kind", kind);
    }

    private static void feedLobby(LobbyScreen lobby) {
        List<Msg> frame = new ArrayList<>();
        Msg head = Msg.of(Protocol.LOBBY).put("phase", 0).put("host", 1)
                .put("cols", 32).put("rows", 22).put("ms", 100).put("walls", 1).put("max", 8);
        frame.add(head);
        frame.add(player(1, "Вася", 0, false, false, true));
        frame.add(player(2, "Аня", 3, true, false, false));
        frame.add(player(3, "Бот Кобра", 5, false, true, false));
        frame.add(player(4, "Макс", 1, true, false, false));
        lobby.onFrame(frame);

        lobby.onFrame(List.of(chat("", "Аня подключилась", 0, 1)));
        lobby.onFrame(List.of(chat("Аня", "го в змейку", 3, 0)));
        lobby.onFrame(List.of(chat("Макс", "я готов", 1, 0)));
        lobby.onFrame(List.of(chat("", "Бот Кобра добавлен", 0, 1)));
        lobby.onFrame(List.of(chat("Вася", "сейчас стартую, только не в стену", 0, 0)));
    }

    private static List<Msg> snapshot(int tick, int ms, boolean second) {
        List<Msg> frame = new ArrayList<>();
        frame.add(Msg.of(Protocol.SNAP).put("t", tick).put("st", System.nanoTime())
                .put("rate", 32).put("ms", ms));
        frame.add(Msg.of(Protocol.FOOD).put("x", 18).put("y", 9).put("k", 0));
        frame.add(Msg.of(Protocol.FOOD).put("x", 6).put("y", 4).put("k", 1));
        frame.add(Msg.of(Protocol.FOOD).put("x", 27).put("y", 4).put("k", 3));
        frame.add(Msg.of(Protocol.FOOD).put("x", 12).put("y", 19).put("k", 2));
        frame.add(Msg.of(Protocol.FOOD).put("x", 17).put("y", 15).put("k", 0));

        frame.add(Msg.of(Protocol.SNAKE).put("p", 1).put("a", 1).put("sc", 30).put("d", 1).put("c", 0).put("ac", 0).put("ls", 0)
                .put("g", "15:12,14:12,13:12,12:12,11:12,10:12,10:11,10:10,10:9,9:9,8:9"));
        frame.add(Msg.of(Protocol.SNAKE).put("p", 2).put("a", 1).put("sc", 20).put("d", 0).put("c", 5).put("ac", 0).put("ls", 0)
                .put("g", "22:5,22:6,22:7,21:7,20:7,20:8"));
        frame.add(Msg.of(Protocol.SNAKE).put("p", 3).put("a", 1).put("sc", 10).put("d", 2).put("c", 3).put("ac", 0).put("ls", 0)
                .put("g", "8:18,9:18,10:18,11:18,11:17,11:16"));
        frame.add(Msg.of(Protocol.SNAKE).put("p", 4).put("a", 0).put("sc", 40).put("d", 1).put("c", 1).put("ac", 0).put("ls", 0)
                .put("g", "25:15,25:16,25:17,24:17,23:17"));
        if (second) {
            frame.add(Msg.of(Protocol.EVENT).put("k", Protocol.EV_EAT).put("p", 1).put("x", 15).put("y", 12));
        }
        return frame;
    }

    private static void feedGame(GameScreen game) {
        List<Msg> roster = new ArrayList<>();
        roster.add(Msg.of(Protocol.LOBBY).put("phase", 2).put("host", 1)
                .put("cols", 32).put("rows", 22).put("ms", 100).put("walls", 1).put("max", 8));
        roster.add(player(1, "Вася", 0, false, false, true));
        roster.add(player(2, "Аня", 3, true, false, false));
        roster.add(player(3, "Бот Кобра", 5, false, true, false));
        roster.add(player(4, "Макс", 1, true, false, false));
        game.onFrame(roster);

        Msg start = Msg.of(Protocol.START).put("phase", 1).put("n", 3);
        new GameConfig().write(start);
        game.onFrame(List.of(start));
        game.onFrame(List.of(chat("Аня", "я справа, не режь", 3, 0)));
        game.onFrame(List.of(chat("", "Бот Кобра выбыл", 0, 1)));
        game.onFrame(List.of(chat("Вася", "ok", 0, 0)));
        game.onFrame(snapshot(1, 100, false));
        game.onFrame(snapshot(2, 100, true));
    }

    private static void feedOver(GameScreen game) {
        List<Msg> frame = new ArrayList<>();
        frame.add(Msg.of(Protocol.OVER).put("n", 4));
        frame.add(Msg.of(Protocol.RESULT).put("p", 1).putStr("name", "Вася").put("sc", 120).put("place", 1).put("c", 0).put("a", 1));
        frame.add(Msg.of(Protocol.RESULT).put("p", 2).putStr("name", "Аня").put("sc", 90).put("place", 2).put("c", 3).put("a", 0));
        frame.add(Msg.of(Protocol.RESULT).put("p", 3).putStr("name", "Бот Кобра").put("sc", 60).put("place", 3).put("c", 5).put("a", 0));
        frame.add(Msg.of(Protocol.RESULT).put("p", 4).putStr("name", "Макс").put("sc", 30).put("place", 4).put("c", 1).put("a", 0));
        game.onFrame(frame);
    }
}
