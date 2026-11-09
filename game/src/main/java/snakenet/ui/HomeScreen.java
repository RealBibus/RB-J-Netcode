package snakenet.ui;

import netcore.net.NetInfo;
import snakenet.audio.Sfx;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

public final class HomeScreen extends Screen {

    private final InputField nameField;
    private final NeonButton bCreate;
    private final NeonButton bJoin;
    private final NeonButton bSound;
    private final NeonButton bFirewall;

    private final Graph gFps = new Graph("FPS", "", 160);
    private final Graph gFrame = new Graph("FRAME", "ms", 160);
    private final Graph gMem = new Graph("MEMORY", "MB", 160);
    private double sampleTimer;
    private double frameMs = 16.0;
    private double fpsSmoothed = 60;

    public HomeScreen(App app) {
        super(app);

        nameField = new InputField("name");
        nameField.setText(app.prefs().get("name", app.myName()));
        addField(nameField, 56, 126, 300, 38, 13);

        bCreate = add(new NeonButton("Create game", NeonButton.Style.PRIMARY, 56, 186, 248, 50, () -> {
            app.setNameAndColor(nameField.getText(), app.myColor());
            win.show(app.hostScreen());
        }));
        bJoin = add(new NeonButton("Join game", NeonButton.Style.SECONDARY, 316, 186, 248, 50, () -> {
            app.setNameAndColor(nameField.getText(), app.myColor());
            win.show(app.joinScreen());
        }));
        bSound = add(new NeonButton("", NeonButton.Style.CHIP, 56, 252, 120, 28, this::toggleSound));
        bFirewall = add(new NeonButton("firewall", NeonButton.Style.CHIP, 184, 252, 120, 28, () ->
                app.toast("If friends cannot see your game, run allow-firewall.bat as administrator")));
        updateSoundLabel();
    }

    private void updateSoundLabel() {
        bSound.label = Sfx.isEnabled() ? "sound: on" : "sound: off";
        bSound.selected = Sfx.isEnabled();
    }

    private void toggleSound() {
        boolean on = !Sfx.isEnabled();
        Sfx.setEnabled(on);
        app.prefs().set("sound", on);
        updateSoundLabel();
    }

    @Override
    protected void onTick(double dt) {
        frameMs = frameMs * 0.9 + Math.min(200, dt * 1000) * 0.1;
        fpsSmoothed = fpsSmoothed * 0.9 + win.fps() * 0.1;
        sampleTimer += dt;
        if (sampleTimer >= 0.15) {
            sampleTimer = 0;
            gFps.push(fpsSmoothed);
            gFrame.push(frameMs);
            Runtime rt = Runtime.getRuntime();
            gMem.push((rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0));
        }
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        paintHeader(g);
        paintSession(g);
        paintSystem(g);
        paintTelemetry(g);
        paintLog(g);
    }

    private void paintHeader(Graphics2D g) {
        g.setColor(Theme.PANEL);
        g.fill(new Rectangle2D.Double(0, 0, VW, 48));
        g.setColor(Theme.LINE);
        g.draw(new Line2D.Double(0, 47.5, VW, 47.5));

        Brush.text(g, "SNAKE.NET", 32, 30, Theme.bold(16), Theme.TEXT);
        Brush.text(g, "// netcode test bench", 148, 30, Theme.font(12), Theme.MUTED);
        Brush.right(g, "v2.0  //  netcore " + netcore.NetCore.VERSION, 1248, 30, Theme.font(11), Theme.DIM);

        boolean connected = app.client() != null;
        g.setColor(connected ? Theme.ACCENT : Theme.MUTED);
        g.fill(new Ellipse2D.Double(1010, 19, 8, 8));
        Brush.text(g, connected ? "ONLINE" : "OFFLINE", 1024, 30, Theme.bold(11),
                connected ? Theme.ACCENT : Theme.MUTED);
    }

    private void paintSession(Graphics2D g) {
        Brush.panelTitled(g, 32, 64, 560, 266, "SESSION");

        Brush.text(g, "PLAYER NAME", 56, 118, Theme.font(10), Theme.MUTED);

        Brush.text(g, "HOST A SERVER ON THIS COMPUTER", 56, 178, Theme.font(10), Theme.MUTED);
        Brush.text(g, "OR JOIN BY HOST ADDRESS", 316, 178, Theme.font(10), Theme.MUTED);

        Brush.text(g, "K — voice in game,  ESC — menu,  arrows/WASD — turn",
                56, 306, Theme.font(11), Theme.DIM);
    }

    private void paintSystem(Graphics2D g) {
        Brush.panelTitled(g, 32, 346, 560, 430, "SYSTEM");

        String[][] rows = {
                {"java", System.getProperty("java.version")},
                {"os", System.getProperty("os.name")},
                {"address", NetInfo.primaryIp()},
                {"default port", String.valueOf(app.prefs().getInt("port", 45800))},
                {"engine", "netcore " + netcore.NetCore.VERSION + " (UDP/TCP)"},
                {"microphone", app.voice() == null ? "not connected" : (app.voice().micOk() ? "ok" : "no device")},
        };
        double y = 400;
        for (String[] row : rows) {
            Brush.text(g, row[0], 56, y, Theme.font(11), Theme.MUTED);
            Brush.text(g, row[1], 236, y, Theme.font(11), Theme.TEXT);
            g.setColor(Theme.LINE);
            g.draw(new Line2D.Double(56, y + 10, 568, y + 10));
            y += 46;
        }
    }

    private void paintTelemetry(Graphics2D g) {
        Brush.panelTitled(g, 616, 64, 632, 394, "TELEMETRY  //  RENDER");

        gFps.paint(g, 636, 104, 592, 84, Theme.ACCENT, 144);
        gFrame.paint(g, 636, 198, 592, 84, Theme.ACCENT2, 50);
        gMem.paint(g, 636, 292, 592, 84, Theme.YELLOW, 1024);

        Brush.text(g, "charts refresh 6 times per second; values come from this process",
                636, 430, Theme.font(10), Theme.MUTED);
    }

    private void paintLog(Graphics2D g) {
        Brush.panelTitled(g, 616, 474, 632, 302, "LOG");
        List<String> lines = app.logLines();
        int from = Math.max(0, lines.size() - 13);
        double y = 512;
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            Brush.text(g, line, 636, y, Theme.font(11), Theme.DIM);
            y += 21;
        }
        if (lines.isEmpty()) {
            Brush.text(g, "nothing yet — create a game or join one", 636, 520, Theme.font(11), Theme.MUTED);
        }
    }
}
