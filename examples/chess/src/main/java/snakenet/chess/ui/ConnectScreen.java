package snakenet.chess.ui;

import snakenet.chess.core.GameConfig;
import snakenet.chess.net.Net;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;

/**
 * The first screen of the chess client. It only connects to an existing dedicated server;
 * it cannot create one. Lobby creation happens later, on the hub browser.
 */
public final class ConnectScreen extends Screen {

    private final InputField nameField;
    private final InputField hostField;
    private final InputField portField;
    private final NeonButton bConnect;

    public ConnectScreen(App app) {
        super(app);

        nameField = new InputField("your name");
        nameField.setText(app.prefs().get("name", app.myName()));
        addField(nameField, 676, 316, 470, 46, 16);
        nameField.addActionListener(e -> doConnect());

        hostField = new InputField("server address");
        hostField.setText(app.prefs().get("host", "127.0.0.1"));
        addField(hostField, 676, 402, 330, 46, 16);
        hostField.addActionListener(e -> doConnect());

        portField = new InputField("port");
        addField(portField, 1026, 402, 120, 46, 16);
        portField.addActionListener(e -> doConnect());

        bConnect = add(new NeonButton("CONNECT", NeonButton.Style.PRIMARY, 676, 548, 470, 56, this::doConnect));

        addHotspot(676, 466, 470, 54, () -> {
            app.toast("Start server-chess.bat on the host machine");
        });
    }

    private void updateKind() {
        portField.setText(String.valueOf(app.prefs().getInt("port.chess", 45801)));
    }

    @Override
    public void onShow() {
        if (nameField.getText().isBlank()) {
            nameField.setText(app.prefs().get("name", app.myName()));
        }
        updateKind();
    }

    private void doConnect() {
        String raw = hostField.getText().trim();
        if (raw.isEmpty()) {
            app.toastError("Enter the server address");
            return;
        }
        String host = raw;
        int port = parsePort(portField.getText(), 45801);
        int colon = raw.indexOf(':');
        if (colon > 0 && raw.indexOf(':', colon + 1) < 0) {
            host = raw.substring(0, colon);
            port = parsePort(raw.substring(colon + 1), port);
        }

        String name = GameConfig.sanitizeName(nameField.getText());
        app.prefs().set("name", name);
        app.prefs().set("host", host);
        app.prefs().set("port.chess", port);
        bConnect.enabled = false;
        try {
            app.joinGame(host, port, name, app.myColor());
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("refused") || lower.contains("timed out") || lower.contains("unreachable")) {
                app.toastError("No server at " + host + ":" + port);
            } else {
                app.toastError("Could not connect: " + msg);
            }
        } finally {
            bConnect.enabled = true;
        }
    }

    private static int parsePort(String text, int fallback) {
        try {
            int p = Integer.parseInt(text.trim());
            return p >= 0 && p <= 65535 ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        paintHeader(g);

        Color accent = Theme.GOLD;

        Brush.text(g, "CHESS", 56, 190,
                Theme.display(82), Theme.TEXT);
        Brush.text(g, "a quiet game of kings",
                60, 232, Theme.font(18), accent);

        Brush.text(g, "CLIENT", 60, 300, Theme.semibold(10), Theme.MUTED);
        Brush.text(g, "Connect to a dedicated server,", 60, 334, Theme.font(15), Theme.DIM);
        Brush.text(g, "then pick a lobby on the hub.", 60, 358, Theme.font(15), Theme.DIM);

        g.setColor(Theme.LINE);
        g.draw(new Line2D.Double(60, 394, 60 + 420, 394));
        paintStep(g, 60, 430, "01", "START THE SERVER", "server-chess.bat", false);
        paintStep(g, 60, 492, "02", "CONNECT HERE", "the address is printed by the server", true);
        paintStep(g, 60, 554, "03", "CREATE OR JOIN", "a lobby opens after connection", false);

        // right card
        g.setColor(new Color(0x0E, 0x14, 0x1D, 236));
        g.fill(new RoundRectangle2D.Double(638, 138, 548, 512, 26, 26));
        g.setColor(Theme.alpha(accent, 105));
        g.setStroke(new BasicStroke(1.3f));
        g.draw(new RoundRectangle2D.Double(638, 138, 548, 512, 26, 26));

        g.setColor(Theme.alpha(accent, 24));
        g.fill(new RoundRectangle2D.Double(638, 138, 548, 64, 26, 26));
        Brush.text(g, "CONNECTION", 676, 178, Theme.semibold(11), accent);

        Brush.text(g, "NAME", 676, 306, Theme.semibold(10), Theme.MUTED);
        Brush.text(g, "SERVER ADDRESS", 676, 392, Theme.semibold(10), Theme.MUTED);
        Brush.text(g, "PORT", 1026, 392, Theme.semibold(10), Theme.MUTED);

        Brush.text(g, "No server is created on this machine.", 676, 480, Theme.font(13), Theme.MUTED);
        Brush.text(g, "Use server-chess.bat on the host.",
                676, 502, Theme.font(13), Theme.DIM);

        g.setColor(Theme.alpha(accent, 60));
        g.setStroke(new BasicStroke(1.0f));
        g.draw(new Line2D.Double(676, 524, 1146, 524));

        Brush.text(g, "Tip: press Enter to connect.  Address may include :port.",
                676, 636, Theme.font(11), Theme.MUTED);

        // footer status
        g.setColor(new Color(0x08, 0x0A, 0x0C, 205));
        g.fill(new RoundRectangle2D.Double(56, 680, 1168, 54, 18, 18));
        g.setColor(app.client() != null ? Theme.ACCENT : Theme.MUTED);
        g.fill(new Ellipse2D.Double(78, 700, 10, 10));
        Brush.text(g, app.client() != null ? "ONLINE" : "OFFLINE", 98, 710,
                Theme.semibold(11), app.client() != null ? Theme.ACCENT : Theme.MUTED);
        Brush.text(g, "engine netcore 2.0  ·  UDP with reliable messaging",
                168, 710, Theme.font(11), Theme.DIM);
        Brush.right(g, "v2.0", 1200, 710, Theme.mono(12), Theme.DIM);
    }

    private void paintHeader(Graphics2D g) {
        g.setColor(new Color(0x08, 0x0A, 0x0C, 205));
        g.fill(new RoundRectangle2D.Double(56, 44, 1168, 58, 20, 20));
        Brush.text(g, "NETCORE", 80, 80, Theme.bold(17), Theme.TEXT);
        Brush.text(g, "//  two games, one engine", 188, 80, Theme.font(12), Theme.MUTED);
        Brush.right(g, "JAVA 25  ·  NO DEPENDENCIES", 1200, 80, Theme.font(10), Theme.DIM);
    }

    private void paintStep(Graphics2D g, double x, double y, String number, String title,
                           String detail, boolean active) {
        Color c = active ? Theme.GOLD : Theme.MUTED;
        g.setColor(Theme.alpha(c, active ? 40 : 18));
        g.fill(new Ellipse2D.Double(x, y - 22, 44, 44));
        g.setColor(c);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Ellipse2D.Double(x, y - 22, 44, 44));
        Brush.text(g, number, x + 12, y + 4, Theme.semibold(12), c);
        Brush.text(g, title, x + 62, y - 2, Theme.semibold(12), active ? Theme.TEXT : Theme.DIM);
        Brush.text(g, detail, x + 62, y + 18, Theme.font(11), Theme.MUTED);
    }
}
