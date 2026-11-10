package snakenet.ui;

import netcore.net.NetInfo;
import snakenet.game.GameConfig;
import snakenet.net.Net;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;

public final class HostScreen extends Screen {

    private static final String[] CHESS_SIDES = {"White", "Black", "Random"};
    private static final String[] CHESS_DEPTHS = {"1 ply", "2 ply", "3 ply", "4 ply"};

    private final InputField nameField;
    private final InputField portField;

    private int sizeIndex;
    private int speedIndex;
    private int tickIndex;
    private boolean subtick = true;
    private boolean udp = true;
    private boolean walls = true;
    private int color;

    private int chessSide;
    private int chessBotDepth = 2;

    private final NeonButton[] sizeChips = new NeonButton[4];
    private final NeonButton[] speedChips = new NeonButton[4];
    private final NeonButton[] tickChips = new NeonButton[4];
    private final NeonButton[] sideChips = new NeonButton[3];
    private final NeonButton[] depthChips = new NeonButton[4];
    private final NeonButton bWalls;
    private final NeonButton bSubtick;
    private final NeonButton bTcp;
    private NeonButton bStandalone;
    private final NeonButton bStart;

    public HostScreen(App app) {
        super(app);
        color = app.myColor();

        nameField = new InputField("What is your name?");
        nameField.setText(app.prefs().get("name", app.myName()));
        addField(nameField, 100, 258, 340, 54, 19);

        portField = new InputField("45800");
        portField.setText(String.valueOf(app.prefs().getInt("port", 45800)));
        addField(portField, 100, 450, 150, 54, 19);

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            sizeChips[i] = add(new NeonButton(GameConfig.SIZE_NAMES[i], NeonButton.Style.CHIP,
                    660 + i * 118, 258, 110, 42, () -> {
                sizeIndex = idx;
                syncChips();
            }));
            speedChips[i] = add(new NeonButton(GameConfig.SPEED_NAMES[i], NeonButton.Style.CHIP,
                    660 + i * 118, 334, 110, 42, () -> {
                speedIndex = idx;
                syncChips();
            }));
            tickChips[i] = add(new NeonButton(GameConfig.TICK_NAMES[i], NeonButton.Style.CHIP,
                    660 + i * 118, 410, 110, 42, () -> {
                tickIndex = idx;
                syncChips();
            }));
            depthChips[i] = add(new NeonButton(CHESS_DEPTHS[i], NeonButton.Style.CHIP,
                    660 + i * 118, 334, 110, 42, () -> {
                chessBotDepth = idx + 1;
                syncChips();
            }));
        }
        for (int i = 0; i < CHESS_SIDES.length; i++) {
            final int idx = i;
            sideChips[i] = add(new NeonButton(CHESS_SIDES[i], NeonButton.Style.CHIP,
                    660 + i * 158, 258, 140, 42, () -> {
                chessSide = idx;
                syncChips();
            }));
        }
        bWalls = add(new NeonButton("Wall death", NeonButton.Style.CHIP, 660, 486, 260, 44, () -> {
            walls = !walls;
            syncChips();
        }));
        bSubtick = add(new NeonButton("subtick", NeonButton.Style.CHIP, 930, 486, 160, 44, () -> {
            subtick = !subtick;
            syncChips();
        }));
        bTcp = add(new NeonButton("Transport: UDP", NeonButton.Style.CHIP, 100, 540, 340, 44, () -> {
            udp = !udp;
            syncChips();
        }));
        bStandalone = add(new NeonButton("Standalone server build", NeonButton.Style.CHIP, 100, 594, 340, 44, () -> {
            app.setStandaloneServer(!app.standaloneServer());
            syncChips();
        }));
        add(new NeonButton("Back", NeonButton.Style.SECONDARY, 56, 44, 100, 42, () -> win.show(app.homeScreen())));
        bStart = add(new NeonButton("START SERVER", NeonButton.Style.PRIMARY, 56, 676, 360, 64, this::startServer));

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            addHotspot(116 + i * 38 - 19, 378 - 19, 38, 38, () -> color = idx);
        }
        syncChips();
    }

    private boolean chessMode() {
        return app.gameKind() == Net.KIND_CHESS;
    }

    @Override
    public void onShow() {
        nameField.setText(app.prefs().get("name", app.myName()));
        syncChips();
    }

    private void syncChips() {
        boolean chess = chessMode();
        for (int i = 0; i < 4; i++) {
            sizeChips[i].visible = !chess;
            speedChips[i].visible = !chess;
            tickChips[i].visible = !chess;
            depthChips[i].visible = chess;
            sizeChips[i].selected = i == sizeIndex;
            speedChips[i].selected = i == speedIndex;
            tickChips[i].selected = i == tickIndex;
            depthChips[i].selected = chess && i == chessBotDepth - 1;
            if (i < sideChips.length) {
                sideChips[i].visible = chess;
                sideChips[i].selected = chess && i == chessSide;
            }
        }
        bWalls.visible = !chess;
        bSubtick.visible = !chess;
        bWalls.selected = walls;
        bWalls.label = walls ? "Wall death" : "Pass through walls";
        bSubtick.selected = subtick;
        bTcp.selected = udp;
        bTcp.label = udp ? "Transport: UDP" : "Transport: TCP";
        bStandalone.selected = app.standaloneServer();
        bStart.label = chess ? "CREATE MATCH" : "START SERVER";
    }

    private void startServer() {
        String name = GameConfig.sanitizeName(nameField.getText());
        if (name.isEmpty()) name = "Host";
        int port;
        String raw = portField.getText().trim();
        if (raw.isEmpty()) raw = "0";
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            app.toastError("Port must be a number, for example 45800");
            return;
        }
        if (port < 0 || port > 65535) {
            app.toastError("Port must be between 0 and 65535");
            return;
        }
        GameConfig cfg = new GameConfig();
        cfg.applySize(sizeIndex);
        cfg.applySpeed(speedIndex);
        cfg.applyTickRate(tickIndex);
        cfg.subtick = subtick;
        cfg.walls = walls;
        cfg.udp = udp;
        cfg.chess = chessMode();
        cfg.chessSide = chessSide;
        cfg.chessBotDepth = chessBotDepth;
        if (cfg.chess) cfg.maxPlayers = 2;
        cfg.serverName = name + (cfg.chess ? "'s chess" : "'s snake");
        app.setGameKind(cfg.chess ? Net.KIND_CHESS : Net.KIND_SNAKE);
        app.setStandaloneServer(app.standaloneServer());
        app.prefs().set("port", port);
        try {
            app.hostGame(port, cfg, name, color);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "unknown error" : e.getMessage();
            if (msg.toLowerCase().contains("address already in use")) {
                app.toastError("Port " + port + " is busy — try another one");
            } else {
                app.toastError("Could not start the server: " + msg);
            }
        }
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        boolean chess = chessMode();
        Brush.text(g, chess ? "Create chess match" : "Create snake game", 56, 136,
                Theme.display(42), Theme.TEXT);
        Brush.text(g, chess ? "Start a server and open a two-player chess lobby"
                        : "You are the host: everyone else only needs your address",
                58, 166, Theme.font(15), Theme.MUTED);

        Brush.panel(g, 56, 196, 1168, 440, 26);

        Brush.text(g, "PLAYER NAME", 100, 244, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "COLOR", 100, 348, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "PORT", 100, 436, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "0 — any free port", 264, 483, Theme.font(13), Theme.MUTED);
        Brush.text(g, "NETWORK", 100, 526, Theme.semibold(11), Theme.MUTED);

        for (int i = 0; i < 10; i++) {
            double cx = 116 + i * 38;
            double cy = 378;
            Color c = Theme.player(i);
            if (i == color) {
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                g.fill(new Ellipse2D.Double(cx - 21, cy - 21, 42, 42));
                g.setColor(c);
                g.setStroke(new BasicStroke(2.2f));
                g.draw(new Ellipse2D.Double(cx - 18, cy - 18, 36, 36));
            }
            g.setColor(i == color ? c : Theme.alpha(c, 170));
            g.fill(new Ellipse2D.Double(cx - 14, cy - 14, 28, 28));
        }

        Brush.divider(g, 620, 226, 620, 606, new Color(0x2E, 0x38, 0x46, 190));

        if (chess) {
            Brush.text(g, "YOUR COLOUR", 660, 244, Theme.semibold(11), Theme.MUTED);
            Brush.text(g, "BOT DIFFICULTY", 660, 320, Theme.semibold(11), Theme.MUTED);
            Brush.text(g, "Chess is a separate game: two players, one board,", 660, 430,
                    Theme.font(14), Theme.TEXT);
            Brush.text(g, "a lone host gets a bot opponent automatically.", 660, 452,
                    Theme.font(13), Theme.MUTED);
        } else {
            Brush.text(g, "BOARD SIZE", 660, 244, Theme.semibold(11), Theme.MUTED);
            Brush.text(g, "SPEED", 660, 320, Theme.semibold(11), Theme.MUTED);
            Brush.text(g, "TICK RATE", 660, 396, Theme.semibold(11), Theme.MUTED);
            Brush.text(g, "WALLS", 660, 472, Theme.semibold(11), Theme.MUTED);

            int[] size = GameConfig.SIZE[sizeIndex];
            Brush.text(g, "Board " + size[0] + "×" + size[1] + " · " + GameConfig.SPEED_CELLS[speedIndex]
                            + " cells/s · " + GameConfig.TICK_RATES[tickIndex] + " ticks/s",
                    1000, 244, Theme.font(13), Theme.MUTED);
        }

        g.setColor(new Color(0x1B, 0x22, 0x2C, 235));
        g.fill(new RoundRectangle2D.Double(660, 548, 470, 74, 16, 16));
        g.setColor(new Color(0x3A, 0x44, 0x52));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(660, 548, 470, 74, 16, 16));
        Brush.text(g, "FRIENDS NEED TO ENTER", 682, 574, Theme.semibold(10), Theme.MUTED);
        String portText = portField.getText().trim();
        String addr = NetInfo.primaryIp() + ":" + (portText.isEmpty() ? "45800" : portText);
        Brush.text(g, addr, 682, 606, Theme.mono(22), Theme.ACCENT);

        Brush.text(g, "The port opens on this computer. Windows may ask about the firewall — allow access.",
                444, 714, Theme.font(13), Theme.MUTED);
    }
}
