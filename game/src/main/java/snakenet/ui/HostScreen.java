package snakenet.ui;

import netcore.net.NetInfo;
import snakenet.game.GameConfig;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;

/** Экран создания игры: имя, цвет, порт, поле, скорость, тикрейт. */
public final class HostScreen extends Screen {

    private final InputField nameField;
    private final InputField portField;

    private int sizeIndex;
    private int speedIndex;
    private int tickIndex;
    private boolean subtick = true;
    private boolean tcpFallback = true;
    private boolean walls = true;
    private int color;

    private final NeonButton[] sizeChips = new NeonButton[4];
    private final NeonButton[] speedChips = new NeonButton[4];
    private final NeonButton[] tickChips = new NeonButton[4];
    private final NeonButton bWalls;
    private final NeonButton bSubtick;
    private final NeonButton bTcp;
    private final NeonButton bStart;

    public HostScreen(App app) {
        super(app);
        color = app.myColor();

        nameField = new InputField("Как тебя зовут?");
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
        }
        bWalls = add(new NeonButton("Смерть о стену", NeonButton.Style.CHIP, 660, 486, 260, 44, () -> {
            walls = !walls;
            syncChips();
        }));
        bSubtick = add(new NeonButton("subtick ×4", NeonButton.Style.CHIP, 930, 486, 160, 44, () -> {
            subtick = !subtick;
            syncChips();
        }));
        bTcp = add(new NeonButton("TCP запасной: вкл", NeonButton.Style.CHIP, 100, 540, 340, 44, () -> {
            tcpFallback = !tcpFallback;
            syncChips();
        }));
        add(new NeonButton("Назад", NeonButton.Style.SECONDARY, 56, 44, 100, 42, () -> win.show(app.homeScreen())));
        bStart = add(new NeonButton("ЗАПУСТИТЬ СЕРВЕР", NeonButton.Style.PRIMARY, 56, 676, 360, 64, this::startServer));

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            addHotspot(116 + i * 38 - 19, 378 - 19, 38, 38, () -> color = idx);
        }
        syncChips();
    }

    private void syncChips() {
        for (int i = 0; i < 4; i++) {
            sizeChips[i].selected = i == sizeIndex;
            speedChips[i].selected = i == speedIndex;
            tickChips[i].selected = i == tickIndex;
        }
        bWalls.selected = walls;
        bWalls.label = walls ? "Смерть о стену" : "Сквозь стены";
        bSubtick.selected = subtick;
        bTcp.selected = tcpFallback;
        bTcp.label = tcpFallback ? "TCP запасной: вкл" : "TCP запасной: выкл";
    }

    private void startServer() {
        String name = GameConfig.sanitizeName(nameField.getText());
        if (name.isEmpty()) name = "Хост";
        int port;
        String raw = portField.getText().trim();
        if (raw.isEmpty()) raw = "0";
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            app.toastError("Порт — это число, например 45800");
            return;
        }
        if (port < 0 || port > 65535) {
            app.toastError("Порт должен быть от 0 до 65535");
            return;
        }
        GameConfig cfg = new GameConfig();
        cfg.applySize(sizeIndex);
        cfg.applySpeed(speedIndex);
        cfg.applyTickRate(tickIndex);
        cfg.subtick = subtick;
        cfg.walls = walls;
        cfg.tcpFallback = tcpFallback;
        cfg.serverName = name;
        app.prefs().set("port", port);
        try {
            app.hostGame(port, cfg, name, color);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "неизвестная ошибка" : e.getMessage();
            if (msg.toLowerCase().contains("address already in use")) {
                app.toastError("Порт " + port + " занят — попробуй другой");
            } else {
                app.toastError("Не удалось открыть сервер: " + msg);
            }
        }
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        Brush.text(g, "Создать игру", 56, 136, Theme.display(42), Theme.TEXT);
        Brush.text(g, "Ты будешь хостом: остальным нужен только твой адрес", 58, 166, Theme.font(15), Theme.MUTED);

        Brush.panel(g, 56, 196, 1168, 440, 26);

        // левая колонка
        Brush.text(g, "ИМЯ ИГРОКА", 100, 244, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "ЦВЕТ", 100, 348, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "ПОРТ", 100, 436, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "0 — любой свободный", 264, 483, Theme.font(13), Theme.MUTED);
        Brush.text(g, "СЕТЬ", 100, 526, Theme.semibold(11), Theme.MUTED);

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

        Brush.divider(g, 620, 226, 620, 606, new Color(0x20, 0x31, 0x4C, 190));

        // правая колонка
        Brush.text(g, "РАЗМЕР ПОЛЯ", 660, 244, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "СКОРОСТЬ", 660, 320, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "ТИКРЕЙТ", 660, 396, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "СТЕНЫ", 660, 472, Theme.semibold(11), Theme.MUTED);

        int[] size = GameConfig.SIZE[sizeIndex];
        Brush.text(g, "Поле " + size[0] + "×" + size[1] + " · " + GameConfig.SPEED_CELLS[speedIndex] + " кл/с · "
                        + GameConfig.TICK_RATES[tickIndex] + " тик/с",
                1000, 244, Theme.font(13), Theme.MUTED);

        // карточка адреса
        g.setColor(new Color(0x0C, 0x16, 0x26, 235));
        g.fill(new RoundRectangle2D.Double(660, 548, 470, 74, 16, 16));
        g.setColor(new Color(0x25, 0x38, 0x55));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(660, 548, 470, 74, 16, 16));
        Brush.text(g, "ДРУЗЬЯМ НУЖНО ВВЕСТИ", 682, 574, Theme.semibold(10), Theme.MUTED);
        String portText = portField.getText().trim();
        String addr = NetInfo.primaryIp() + ":" + (portText.isEmpty() ? "45800" : portText);
        Brush.text(g, addr, 682, 606, Theme.mono(22), Theme.ACCENT);

        Brush.text(g, "Порт откроется на этом компьютере. Windows может спросить про брандмауэр — разреши доступ.",
                444, 714, Theme.font(13), Theme.MUTED);
    }
}
