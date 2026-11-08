package snakenet.ui;

import snakenet.game.GameConfig;
import netcore.net.LanDiscovery;

import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class JoinScreen extends Screen {

    private static final int MAX_ROWS = 5;

    private final InputField hostField;
    private final InputField portField;
    private final InputField nameField;

    private volatile List<LanDiscovery.Found> servers = new ArrayList<>();
    private LanDiscovery.Scanner scanner;
    private boolean scannerStarted;
    private boolean scannerOk;
    private int color;

    private final NeonButton bJoin;
    private final NeonButton bRefresh;

    public JoinScreen(App app) {
        super(app);
        color = app.myColor();

        hostField = new InputField("192.168.0.5");
        addField(hostField, 806, 250, 388, 52, 19);

        portField = new InputField("45800");
        portField.setText(String.valueOf(app.prefs().getInt("port", 45800)));
        addField(portField, 806, 336, 150, 52, 19);

        nameField = new InputField("What is your name?");
        nameField.setText(app.prefs().get("name", app.myName()));
        addField(nameField, 806, 422, 240, 52, 19);

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            addHotspot(806 + i * 32 - 14, 514 - 14, 28, 28, () -> color = idx);
        }

        bJoin = add(new NeonButton("JOIN GAME", NeonButton.Style.PRIMARY, 806, 542, 388, 54, this::doJoin));
        bRefresh = add(new NeonButton("Refresh", NeonButton.Style.CHIP, 600, 204, 126, 34, this::restartScanner));
        add(new NeonButton("Back", NeonButton.Style.SECONDARY, 56, 44, 100, 42, () -> win.show(app.homeScreen())));
    }

    @Override
    public void onShow() {
        if (nameField.getText().isBlank()) {
            nameField.setText(app.prefs().get("name", app.myName()));
        }
        startScanner();
    }

    @Override
    public void onHide() {
        stopScanner();
    }

    private void startScanner() {
        if (scannerStarted) return;
        scannerStarted = true;
        scanner = new LanDiscovery.Scanner(list -> SwingUtilities.invokeLater(() -> {
            servers = list;
            rebuildHotspots();
        }));
        scannerOk = scanner.start();
    }

    private void stopScanner() {
        scannerStarted = false;
        if (scanner != null) {
            scanner.close();
            scanner = null;
        }
    }

    private void restartScanner() {
        stopScanner();
        servers = new ArrayList<>();
        rebuildHotspots();
        startScanner();
        app.toast("Searching for games on the local network…");
    }

    private void rebuildHotspots() {
        clearHotspots();
        List<LanDiscovery.Found> list = servers;
        int n = Math.min(MAX_ROWS, list.size());
        for (int i = 0; i < n; i++) {
            LanDiscovery.Found f = list.get(i);
            double y = rowY(i);
            addHotspot(86, y, 640, 66, () -> connectTo(f.host, f.port));
        }
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            addHotspot(806 + i * 32 - 14, 514 - 14, 28, 28, () -> color = idx);
        }
    }

    private double rowY(int i) {
        return 222 + i * 74;
    }

    private void doJoin() {
        String raw = hostField.getText().trim();
        if (raw.isEmpty()) {
            app.toastError("Enter an address, for example 192.168.0.5");
            return;
        }
        String host = raw;
        int port = parsePort(portField.getText(), 45800);
        int colon = raw.indexOf(':');
        if (colon > 0 && raw.indexOf(':', colon + 1) < 0) {
            host = raw.substring(0, colon);
            try {
                port = Integer.parseInt(raw.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        connectTo(host, port);
    }

    private void connectTo(String host, int port) {
        String name = GameConfig.sanitizeName(nameField.getText());
        app.prefs().set("port", port);
        try {
            app.joinGame(host, port, name, color);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase().contains("refused")) {
                app.toastError("Server is not responding: " + host + ":" + port);
            } else if (msg.toLowerCase().contains("timed out")) {
                app.toastError("Server is not responding — check the address and Wi-Fi");
            } else {
                app.toastError("Could not connect: " + msg);
            }
        }
    }

    private static int parsePort(String text, int def) {
        try {
            int p = Integer.parseInt(text.trim());
            return p >= 0 && p <= 65535 ? p : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        Brush.text(g, "Join game", 56, 136, Theme.display(42), Theme.TEXT);
        Brush.text(g, "Find a game on the local network or enter the host address", 58, 166, Theme.font(15), Theme.MUTED);

        Brush.panel(g, 56, 196, 700, 440, 26);
        Brush.text(g, "GAMES NEARBY", 86, 232, Theme.semibold(11), Theme.MUTED);

        List<LanDiscovery.Found> list = servers;
        if (list.isEmpty()) {
            double cx = 406;
            double cy = 400;
            g.setColor(new Color(0x2E, 0x38, 0x46));
            g.fill(new Ellipse2D.Double(cx - 34, cy - 34, 68, 68));
            g.setColor(new Color(0x3A, 0x44, 0x52));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(cx - 34, cy - 34, 68, 68));
            Brush.centered(g, "…", cx, cy + 10, Theme.display(30), Theme.MUTED);
            Brush.centered(g, scannerOk ? "No games visible yet" : "Auto-discovery unavailable",
                    cx, cy + 74, Theme.semibold(18), Theme.DIM);
            Brush.centered(g, scannerOk ? "Host a game or enter the address on the right" : "Enter the address and port manually on the right",
                    cx, cy + 100, Theme.font(14), Theme.MUTED);
        } else {
            int n = Math.min(MAX_ROWS, list.size());
            for (int i = 0; i < n; i++) {
                LanDiscovery.Found f = list.get(i);
                double y = rowY(i);
                boolean hot = mouseX >= 86 && mouseX <= 726 && mouseY >= y && mouseY <= y + 66;
                g.setColor(hot ? new Color(0x2E, 0x38, 0x46, 245) : new Color(0x24, 0x2C, 0x38, 235));
                g.fill(new RoundRectangle2D.Double(86, y, 640, 66, 16, 16));
                g.setColor(hot ? Theme.alpha(Theme.ACCENT, 170) : new Color(0x3A, 0x44, 0x52));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new RoundRectangle2D.Double(86, y, 640, 66, 16, 16));

                g.setColor(new Color(0x0B, 0x14, 0x22));
                g.fill(new Ellipse2D.Double(106, y + 17, 32, 32));
                g.setColor(Theme.ACCENT);
                g.fill(new Ellipse2D.Double(114, y + 25, 16, 16));

                g.setFont(Theme.semibold(18));
                Brush.text(g, Brush.ellipsize(g, f.name, 380), 154, y + 32, Theme.semibold(18), Theme.TEXT);
                Brush.text(g, f.address(), 154, y + 54, Theme.mono(13), Theme.DIM);

                String players = f.players + "/" + f.max;
                Brush.right(g, players, 700, y + 40, Theme.semibold(15), Theme.ACCENT);
            }
        }

        Brush.panel(g, 776, 196, 448, 440, 26);
        Brush.text(g, "MANUAL", 806, 232, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "PORT", 806, 322, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "NAME", 806, 408, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "COLOR", 806, 494, Theme.semibold(11), Theme.MUTED);

        for (int i = 0; i < 10; i++) {
            double cx = 806 + i * 32;
            Color c = Theme.player(i);
            if (i == color) {
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                g.fill(new Ellipse2D.Double(cx - 16, 514 - 16, 32, 32));
                g.setColor(c);
                g.setStroke(new BasicStroke(2f));
                g.draw(new Ellipse2D.Double(cx - 14, 514 - 14, 28, 28));
            }
            g.setColor(i == color ? c : Theme.alpha(c, 160));
            g.fill(new Ellipse2D.Double(cx - 11, 514 - 11, 22, 22));
        }
    }
}
