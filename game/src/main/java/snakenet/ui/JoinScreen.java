package snakenet.ui;

import snakenet.game.GameConfig;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;

public final class JoinScreen extends Screen {

    private final InputField hostField;
    private final InputField portField;
    private final InputField nameField;

    private final NeonButton bJoin;
    private int color = 0;

    public JoinScreen(App app) {
        super(app);
        hostField = new InputField("192.168.0.5");
        addField(hostField, 126, 216, 580, 50, 19);

        portField = new InputField("45800");
        portField.setText(String.valueOf(app.prefs().getInt("port", 45800)));
        addField(portField, 126, 292, 160, 50, 19);

        nameField = new InputField("What is your name?");
        nameField.setText(app.prefs().get("name", app.myName()));
        addField(nameField, 126, 372, 580, 50, 19);

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            addHotspot(116 + i * 38 - 19, 452 - 19, 38, 38, () -> color = idx);
        }

        bJoin = add(new NeonButton("CONNECT", NeonButton.Style.PRIMARY, 806, 542, 388, 54, this::doJoin));
        add(new NeonButton("Back", NeonButton.Style.SECONDARY, 56, 44, 100, 42, () -> win.show(app.homeScreen())));
    }

    @Override
    public void onShow() {
        if (nameField.getText().isBlank()) {
            nameField.setText(app.prefs().get("name", app.myName()));
        }
    }

    @Override
    public void onHide() {
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
                app.toastError("Server is not responding - check the address and Wi-Fi");
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
        Brush.text(g, "Join game", 56, 96, Theme.display(42), Theme.TEXT);
        Brush.text(g, "Connect to a server — the lobby browser opens next", 58, 130, Theme.font(15), Theme.MUTED);

        Brush.panel(g, 56, 156, 700, 380, 26);
        Brush.text(g, "HOST", 126, 200, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "PORT", 126, 270, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "NAME", 126, 350, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "COLOR", 126, 430, Theme.semibold(11), Theme.MUTED);

        for (int i = 0; i < 10; i++) {
            double cx = 116 + i * 38;
            double cy = 452;
            Color c = Theme.player(i);
            if (i == color) {
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                g.fill(new Ellipse2D.Double(cx - 21, cy - 21, 42, 42));
                g.setColor(c);
                g.setStroke(new BasicStroke(2.2f));
                g.draw(new Ellipse2D.Double(cx - 18, cy - 18, 36, 36));
            }
            g.setColor(i == color ? c : Theme.alpha(c, 170));
            g.fill(new Ellipse2D.Double(cx - 14, cy - 14, 28, 28));
        }
    }
}
