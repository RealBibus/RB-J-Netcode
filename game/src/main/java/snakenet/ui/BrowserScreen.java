package snakenet.ui;

import snakenet.game.GameConfig;
import snakenet.net.Net;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * Hub browser: lists the lobbies advertised by the server we are connected to and lets the
 * player create a new one or join an existing one.
 */
public final class BrowserScreen extends Screen {

    private final NeonButton bRefresh;
    private final NeonButton bCreate;

    public BrowserScreen(App app) {
        super(app);

        bRefresh = add(new NeonButton("REFRESH", NeonButton.Style.SECONDARY, 820, 720, 150, 52,
                app::refreshHub));
        bCreate = add(new NeonButton("CREATE LOBBY", NeonButton.Style.PRIMARY, 980, 720, 200, 52,
                () -> app.createLobby(defaultConfig())));
        add(new NeonButton("Back", NeonButton.Style.SECONDARY, 56, 44, 100, 42, app::leaveToHome));
    }

    private GameConfig defaultConfig() {
        GameConfig cfg = new GameConfig();
        cfg.serverName = app.myName() + (app.gameKind() == Net.KIND_CHESS ? "'s chess" : "'s snake");
        if (app.gameKind() == Net.KIND_CHESS) {
            cfg.chess = true;
            cfg.maxPlayers = 2;
            cfg.chessSide = 0;
            cfg.chessBotDepth = 2;
        }
        return cfg;
    }

    public void refresh() {
        clearHotspots();
        List<App.RoomInfo> rooms = app.hubRooms();
        for (int i = 0; i < rooms.size() && i < 8; i++) {
            final int id = rooms.get(i).id;
            addHotspot(76, 210 + i * 64, 1120, 54, () -> app.send(Net.LOBBY_JOIN.newMsg().put("id", id)));
        }
        bCreate.enabled = app.client() != null;
        bRefresh.enabled = app.client() != null;
    }

    @Override
    public void onShow() {
        refresh();
        if (app.client() != null) app.refreshHub();
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        Brush.text(g, "Lobbies", 56, 120, Theme.display(42), Theme.TEXT);
        String server = app.serverName().isBlank() ? "hub" : app.serverName();
        Brush.text(g, "Connected to " + server + " — pick a lobby or create your own", 58, 148,
                Theme.font(15), Theme.MUTED);

        g.setColor(new Color(0x1B, 0x22, 0x2C, 230));
        g.fill(new RoundRectangle2D.Double(900, 88, 324, 46, 23, 23));
        g.setColor(new Color(0x3A, 0x44, 0x52));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new RoundRectangle2D.Double(900, 88, 324, 46, 23, 23));
        Brush.text(g, "SERVER", 918, 108, Theme.semibold(9), Theme.MUTED);
        String addr = app.gameAddress().isBlank() ? "—" : app.gameAddress();
        Brush.text(g, addr, 918, 126, Theme.mono(16), Theme.ACCENT);

        Brush.panel(g, 56, 178, 1168, 520, 26);

        g.setFont(Theme.semibold(11));
        Brush.text(g, "GAME", 86, 206, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "LOBBY", 220, 206, Theme.semibold(11), Theme.MUTED);
        Brush.right(g, "PLAYERS", 980, 206, Theme.semibold(11), Theme.MUTED);
        Brush.right(g, "STATUS", 1180, 206, Theme.semibold(11), Theme.MUTED);

        List<App.RoomInfo> rooms = app.hubRooms();
        if (rooms.isEmpty()) {
            Brush.text(g, "No lobbies yet.", 86, 270, Theme.font(17), Theme.TEXT);
            Brush.text(g, "Create one and your friends can join it from this list.",
                    86, 298, Theme.font(14), Theme.MUTED);
            return;
        }
        int shown = Math.min(rooms.size(), 8);
        for (int i = 0; i < shown; i++) {
            paintRow(g, rooms.get(i), 210 + i * 64);
        }
    }

    private void paintRow(Graphics2D g, App.RoomInfo room, double y) {
        g.setColor(new Color(0x24, 0x2C, 0x38, 225));
        g.fill(new RoundRectangle2D.Double(76, y, 1120, 54, 14, 14));
        g.setColor(new Color(0x24, 0x36, 0x50));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new RoundRectangle2D.Double(76, y, 1120, 54, 14, 14));

        boolean chess = room.kind == Net.KIND_CHESS;
        Color accent = chess ? Theme.GOLD : Theme.ACCENT;
        g.setColor(Theme.alpha(accent, 40));
        g.fill(new RoundRectangle2D.Double(90, y + 14, 86, 26, 13, 13));
        g.setColor(accent);
        g.setFont(Theme.semibold(10));
        FontMetrics fm = g.getFontMetrics();
        String kind = chess ? "CHESS" : "SNAKE";
        g.drawString(kind, (float) (133 - fm.stringWidth(kind) / 2.0), (float) (y + 32));

        Brush.text(g, room.name, 220, y + 33, Theme.semibold(16), Theme.TEXT);
        Brush.right(g, room.players + " / " + room.max, 980, y + 33, Theme.font(15), Theme.DIM);

        String status;
        Color color;
        switch (room.phase) {
            case 1, 2 -> {
                status = "playing";
                color = Theme.YELLOW;
            }
            case 3 -> {
                status = "finished";
                color = Theme.MUTED;
            }
            default -> {
                status = room.players >= room.max ? "full" : "open";
                color = room.players >= room.max ? Theme.DANGER : Theme.ACCENT;
            }
        }
        Brush.right(g, status, 1180, y + 33, Theme.semibold(12), color);
    }
}
