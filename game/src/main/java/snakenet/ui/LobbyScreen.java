package snakenet.ui;

import snakenet.game.GameConfig;
import netcore.netcode.Msg;
import snakenet.net.Net;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

public final class LobbyScreen extends Screen {

    private static final class Roster {
        int id;
        String name = "";
        int color;
        boolean ready;
        boolean bot;
        boolean host;
    }

    private static final class ChatLine {
        String from;
        String text;
        int color;
        boolean system;
    }

    private final List<Roster> players = new ArrayList<>();
    private final List<ChatLine> chat = new ArrayList<>();
    private GameConfig cfg = new GameConfig();
    private int hostId = -1;
    private double chatBlink;

    private final InputField chatField;
    private final NeonButton bReady;
    private final NeonButton bStart;
    private final NeonButton bBotAdd;
    private final NeonButton bBotDel;
    private final NeonButton bField;
    private final NeonButton bSpeed;
    private final NeonButton bTick;
    private final NeonButton bSub;
    private final NeonButton bWalls;
    private final NeonButton bSide;
    private final NeonButton bBotDepth;

    public LobbyScreen(App app) {
        super(app);

        chatField = new InputField("Message…");
        addField(chatField, 858, 596, 300, 40, 15);
        chatField.addActionListener(e -> sendChat());

        bField = add(new NeonButton("Field", NeonButton.Style.CHIP, 56, 664, 200, 40, this::cycleField));
        bSpeed = add(new NeonButton("Speed", NeonButton.Style.CHIP, 264, 664, 180, 40, this::cycleSpeed));
        bTick = add(new NeonButton("Tick", NeonButton.Style.CHIP, 452, 664, 130, 40, this::cycleTick));
        bSub = add(new NeonButton("SUB", NeonButton.Style.CHIP, 590, 664, 110, 40, this::toggleSubtick));
        bWalls = add(new NeonButton("Walls", NeonButton.Style.CHIP, 708, 664, 150, 40, this::toggleWalls));
        bSide = add(new NeonButton("White", NeonButton.Style.CHIP, 56, 664, 200, 40, this::cycleSide));
        bBotDepth = add(new NeonButton("Bot · 2 ply", NeonButton.Style.CHIP, 264, 664, 200, 40, this::cycleBotDepth));
        bBotAdd = add(new NeonButton("+ Bot", NeonButton.Style.CHIP, 866, 664, 80, 40, () -> app.send(Msg.of(Net.BOT).put("op", "add"))));
        bBotDel = add(new NeonButton("− Bot", NeonButton.Style.CHIP, 954, 664, 80, 40, () -> app.send(Msg.of(Net.BOT).put("op", "del"))));

        bReady = add(new NeonButton("Ready", NeonButton.Style.PRIMARY, 960, 720, 156, 52, this::toggleReady));
        bStart = add(new NeonButton("START GAME", NeonButton.Style.PRIMARY, 900, 720, 216, 52, () -> app.send(Msg.of(Net.START))));
        add(new NeonButton("→", NeonButton.Style.SECONDARY, 1166, 596, 46, 40, this::sendChat));
        add(new NeonButton("Leave", NeonButton.Style.DANGER, 1128, 720, 96, 52, () -> app.leaveLobby()));

        addHotspot(900, 92, 324, 46, this::copyAddress);

        getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("pressed K"), "pttOn");
        getActionMap().put("pttOn", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (chatField.hasFocus() || app.voice() == null) return;
                app.voice().setTalking(true);
            }
        });
        getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("released K"), "pttOff");
        getActionMap().put("pttOff", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (app.voice() != null) app.voice().setTalking(false);
            }
        });
    }

    private void copyAddress() {
        String addr = app.gameAddress();
        if (addr == null || addr.isBlank()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(addr), null);
        app.toast("Address copied: " + addr);
    }

    private void toggleReady() {
        Roster me = me();
        boolean next = me == null || !me.ready;
        app.send(Msg.of(Net.READY).put("v", next ? 1 : 0));
    }

    private void cycleField() {
        int idx = cfg.sizeIndex();
        app.send(Msg.of(Net.SET).put("k", "size").put("v", idx < 0 ? 1 : (idx + 1) % GameConfig.SIZE.length));
    }

    private void cycleSpeed() {
        int idx = cfg.speedIndex();
        app.send(Msg.of(Net.SET).put("k", "speed").put("v", idx < 0 ? 1 : (idx + 1) % GameConfig.SPEED_CELLS.length));
    }

    private void cycleTick() {
        int idx = cfg.tickIndex();
        app.send(Msg.of(Net.SET).put("k", "rate").put("v", idx < 0 ? 1 : (idx + 1) % GameConfig.TICK_RATES.length));
    }

    private void toggleSubtick() {
        app.send(Msg.of(Net.SET).put("k", "subtick").put("v", cfg.subtick ? 0 : 1));
    }

    private void toggleWalls() {
        app.send(Msg.of(Net.SET).put("k", "walls").put("v", cfg.walls ? 0 : 1));
    }

    private void cycleSide() {
        app.send(Msg.of(Net.SET).put("k", "chessSide").put("v", (cfg.chessSide + 1) % 3));
    }

    private void cycleBotDepth() {
        app.send(Msg.of(Net.SET).put("k", "chessBot").put("v", cfg.chessBotDepth >= 4 ? 1 : cfg.chessBotDepth + 1));
    }

    private void sendChat() {
        String text = chatField.getText().trim();
        if (text.isEmpty()) return;
        app.send(Msg.of(Net.CHAT).putStr("text", text));
        chatField.setText("");
    }

    private Roster me() {
        for (Roster r : players) if (r.id == app.myId()) return r;
        return null;
    }

    @Override
    public void onShow() {
        refreshButtons();
        chatField.requestFocusInWindow();
    }

    @Override
    public void onFrame(List<Msg> frame) {
        boolean lobby = false;
        for (Msg m : frame) {
            if (m.is(Net.LOBBY)) {
                readLobby(frame);
                lobby = true;
                break;
            }
        }
        for (Msg m : frame) {
            if (m.is(Net.CHAT_OUT)) {
                ChatLine line = new ChatLine();
                line.from = m.str("from");
                line.text = m.str("text");
                line.color = m.num("c", 0);
                line.system = m.num("kind", 0) == 1;
                chat.add(line);
                if (chat.size() > 200) chat.remove(0);
            }
        }
        if (lobby) refreshButtons();
    }

    private void readLobby(List<Msg> frame) {
        List<Roster> list = new ArrayList<>();
        int host = -1;
        for (Msg m : frame) {
            if (m.is(Net.LOBBY)) {
                host = m.num("host", -1);
                cfg = GameConfig.read(m, cfg);
            } else if (m.is(Net.PLAYER)) {
                Roster r = new Roster();
                r.id = m.num("id", 0);
                r.name = m.str("name");
                r.color = m.num("c", 0);
                r.ready = m.bool("ready");
                r.bot = m.bool("bot");
                r.host = m.bool("host");
                list.add(r);
            }
        }
        players.clear();
        players.addAll(list);
        hostId = host;
    }

    private int pendingKick = -1;
    private long pendingKickAt;

    private void requestKick(int id) {
        long now = System.currentTimeMillis();
        if (pendingKick == id && now - pendingKickAt < 3000) {
            app.send(Msg.of(Net.KICK_PLAYER).put("p", id));
            pendingKick = -1;
        } else {
            pendingKick = id;
            pendingKickAt = now;
            app.toast("Click the player again to kick");
        }
    }

    private void refreshButtons() {
        boolean host = app.isHost();
        boolean chess = app.gameKind() == Net.KIND_CHESS;
        Roster me = me();
        bField.visible = host && !chess;
        bSpeed.visible = host && !chess;
        bTick.visible = host && !chess;
        bSub.visible = host && !chess;
        bWalls.visible = host && !chess;
        bSide.visible = host && chess;
        bBotDepth.visible = host && chess;
        bBotAdd.visible = host;
        bBotDel.visible = host;
        bStart.visible = host;
        bReady.visible = !host;

        bField.label = "Field · " + cfg.sizeLabel();
        bSpeed.label = cfg.cellsPerSec + " cells/s";
        bTick.label = cfg.tickRate + " Hz";
        bSub.label = cfg.subtick ? "SUB on" : "SUB off";
        bSub.selected = cfg.subtick;
        bWalls.label = cfg.walls ? "Walls · solid" : "Walls · wrap";
        bWalls.selected = cfg.walls;
        String[] sides = {"White", "Black", "Random"};
        bSide.label = "Side · " + sides[Math.max(0, Math.min(2, cfg.chessSide))];
        bBotDepth.label = "Bot · " + cfg.chessBotDepth + " ply";

        bReady.selected = me != null && me.ready;
        bReady.label = me != null && me.ready ? "Not ready" : "Ready";
        bStart.enabled = !players.isEmpty();
        bStart.label = chess ? "START MATCH" : "START GAME";

        clearHotspots();
        addHotspot(900, 92, 324, 46, this::copyAddress);
        if (host) {
            for (int i = 0; i < players.size(); i++) {
                Roster r = players.get(i);
                if (r.id == app.myId() || r.bot) continue;
                final int id = r.id;
                addHotspot(76, 236 + i * 50, 720, 44, () -> requestKick(id));
            }
        }
    }

    @Override
    protected void onTick(double dt) {
        chatBlink += dt;
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        String title = app.roomName().isBlank() ? "Lobby" : app.roomName();
        Brush.text(g, title, 56, 132, Theme.display(40), Theme.TEXT);
        boolean chess = app.gameKind() == Net.KIND_CHESS;
        Brush.text(g, chess ? "Chess match — invite a friend or play the bot"
                        : "Invite friends with the address — or add bots and play solo",
                58, 160, Theme.font(15), Theme.MUTED);

        paintAddress(g);

        Brush.panel(g, 56, 186, 760, 464, 26);
        Brush.panel(g, 840, 186, 384, 464, 26);

        Brush.text(g, "PLAYERS · " + players.size() + " / " + Math.max(2, cfg.maxPlayers), 86, 222, Theme.semibold(11), Theme.MUTED);
        Brush.right(g, "STATUS", 786, 222, Theme.semibold(11), Theme.MUTED);

        int slots = 8;
        for (int i = 0; i < slots; i++) {
            double y = 236 + i * 50;
            if (i < players.size()) {
                paintRosterRow(g, players.get(i), y);
            } else {
                g.setColor(new Color(0x1B, 0x22, 0x2C, 120));
                g.fill(new RoundRectangle2D.Double(76, y, 720, 44, 14, 14));
                g.setColor(new Color(0x2E, 0x38, 0x46, 150));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5, 6}, 0));
                g.draw(new RoundRectangle2D.Double(76.5, y + 0.5, 719, 43, 14, 14));
                Brush.text(g, "Free slot", 100, y + 27, Theme.font(14), new Color(0x5C, 0x66, 0x72));
            }
        }

        paintChat(g);
    }

    private void paintAddress(Graphics2D g) {
        String addr = app.gameAddress();
        if (addr == null || addr.isBlank()) addr = "—";
        g.setColor(new Color(0x1B, 0x22, 0x2C, 230));
        g.fill(new RoundRectangle2D.Double(900, 92, 324, 46, 23, 23));
        g.setColor(new Color(0x3A, 0x44, 0x52));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new RoundRectangle2D.Double(900, 92, 324, 46, 23, 23));
        Brush.text(g, "ADDRESS FOR FRIENDS", 918, 112, Theme.semibold(9), Theme.MUTED);
        Brush.text(g, addr, 918, 130, Theme.mono(16), Theme.ACCENT);
        Brush.text(g, "copy", 1120, 121, Theme.font(11), Theme.MUTED);
    }

    private void paintRosterRow(Graphics2D g, Roster r, double y) {
        boolean self = r.id == app.myId();
        g.setColor(new Color(0x24, 0x2C, 0x38, self ? 245 : 225));
        g.fill(new RoundRectangle2D.Double(76, y, 720, 44, 14, 14));
        g.setColor(self ? Theme.alpha(Theme.ACCENT, 90) : new Color(0x24, 0x36, 0x50));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new RoundRectangle2D.Double(76, y, 720, 44, 14, 14));

        Color c = Theme.player(r.color);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 45));
        g.fill(new Ellipse2D.Double(92, y + 10, 26, 26));
        g.setColor(c);
        g.fill(new Ellipse2D.Double(99, y + 17, 12, 12));

        g.setFont(Theme.semibold(16));
        String name = Brush.ellipsize(g, r.name + (self ? " (you)" : ""), 320);
        Brush.text(g, name, 130, y + 28, Theme.semibold(16), Theme.TEXT);

        double badgeX = 130 + g.getFontMetrics().stringWidth(name) + 14;
        if (r.bot) {
            badge(g, badgeX, y + 12, "BOT", Theme.ACCENT2);
        }

        String status;
        Color statusColor;
        if (r.host) {
            status = "host";
            statusColor = Theme.GOLD;
        } else if (r.ready) {
            status = "ready";
            statusColor = Theme.ACCENT;
        } else {
            status = "waiting";
            statusColor = Theme.MUTED;
        }
        Brush.right(g, status, 786, y + 28, Theme.semibold(13), statusColor);
    }

    private double badge(Graphics2D g, double x, double y, String text, Color color) {
        g.setFont(Theme.semibold(9));
        FontMetrics fm = g.getFontMetrics();
        double w = fm.stringWidth(text) + 14;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 32));
        g.fill(new RoundRectangle2D.Double(x, y, w, 20, 10, 10));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 150));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x, y, w, 20, 10, 10));
        g.setColor(color);
        g.drawString(text, (float) (x + 7), (float) (y + 14));
        return x + w + 8;
    }

    private void paintChat(Graphics2D g) {
        Brush.text(g, "CHAT", 866, 222, Theme.semibold(11), Theme.MUTED);

        double bottom = 586;
        double top = 240;
        double y = bottom;
        int shown = 0;
        for (int i = chat.size() - 1; i >= 0 && shown < 40; i--) {
            ChatLine line = chat.get(i);
            g.setFont(line.system ? Theme.font(13) : Theme.font(14));
            List<String> lines = wrap(g, line.text, 320);
            double height = lines.size() * 18 + (line.system ? 6 : 10);
            if (y - height < top) break;
            y -= height;
            if (line.system) {
                for (int k = 0; k < lines.size(); k++) {
                    Brush.text(g, lines.get(k), 866, y + 14 + k * 18, Theme.font(13), new Color(0x6E, 0x84, 0xA4));
                }
            } else {
                Brush.text(g, line.from, 866, y + 14, Theme.semibold(13), Theme.player(line.color));
                double nameWidth = g.getFontMetrics(Theme.semibold(13)).stringWidth(line.from) + 6;
                for (int k = 0; k < lines.size(); k++) {
                    Brush.text(g, lines.get(k), 866 + (k == 0 ? nameWidth : 0), y + 14 + k * 18,
                            Theme.font(14), Theme.TEXT);
                }
            }
            y -= 8;
            shown++;
        }
        if (chat.isEmpty()) {
            Brush.text(g, "It's quiet here.", 866, 300, Theme.font(14), Theme.MUTED);
            Brush.text(g, "Say hi first — your friends will see it.", 866, 322, Theme.font(13), Theme.MUTED);
        }
    }

    private List<String> wrap(Graphics2D g, String text, double width) {
        List<String> out = new ArrayList<>();
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) > width && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
