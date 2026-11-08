package snakenet.ui;

import snakenet.audio.Sfx;
import netcore.net.NetInfo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Главное меню. */
public final class HomeScreen extends Screen {

    private final NeonButton bCreate;
    private final NeonButton bJoin;
    private final NeonButton bHelp;
    private final NeonButton bClose;
    private final NeonButton bSound;

    private boolean help;
    private double helpAnim;

    public HomeScreen(App app) {
        super(app);
        bCreate = add(new NeonButton("Создать игру", NeonButton.Style.PRIMARY, 96, 506, 340, 64, this::openHost));
        bJoin = add(new NeonButton("Подключиться", NeonButton.Style.SECONDARY, 96, 584, 340, 64, this::openJoin));
        bHelp = add(new NeonButton("Как играть", NeonButton.Style.GHOST, 96, 660, 200, 38, this::toggleHelp));
        bClose = add(new NeonButton("Понятно", NeonButton.Style.PRIMARY, 500, 566, 280, 56, this::toggleHelp));
        bClose.visible = false;
        bSound = add(new NeonButton("", NeonButton.Style.CHIP, 1120, 40, 120, 40, this::toggleSound));
        updateSoundLabel();
    }

    private void updateSoundLabel() {
        bSound.label = Sfx.isEnabled() ? "Звук: вкл" : "Звук: выкл";
        bSound.selected = false;
    }

    private void toggleSound() {
        boolean on = !Sfx.isEnabled();
        Sfx.setEnabled(on);
        app.prefs().set("sound", on);
        updateSoundLabel();
    }

    private void openHost() {
        win.show(app.hostScreen());
    }

    private void openJoin() {
        win.show(app.joinScreen());
    }

    private void toggleHelp() {
        help = !help;
        bCreate.visible = !help;
        bJoin.visible = !help;
        bHelp.visible = !help;
        bClose.visible = help;
    }

    @Override
    protected void onTick(double dt) {
        helpAnim += ((help ? 1 : 0) - helpAnim) * Math.min(1, dt * 12);
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        paintTag(g, 96, 128, "ЛОКАЛЬНАЯ СЕТЬ · ДО 8 ИГРОКОВ");
        Brush.gradientText(g, "SNAKE", 92, 300, Theme.display(116), Color.WHITE, new Color(0x8F, 0xFF, 0xCB));
        // NET с неоновым свечением
        for (int i = 4; i >= 1; i--) {
            g.setColor(new Color(0x4C, 0xFF, 0x9B, 18));
            g.setFont(Theme.display(116));
            g.drawString("NET", 92 - i * 2f, 416);
            g.drawString("NET", 92 + i * 2f, 416);
            g.drawString("NET", 92, 416 - i * 2f);
            g.drawString("NET", 92, 416 + i * 2f);
        }
        Brush.text(g, "NET", 92, 416, Theme.display(116), Theme.ACCENT);

        Brush.wrapped(g, "Змейка для своих: один создаёт игру, остальные заходят по адресу в той же сети.",
                100, 470, 450, Theme.font(18), Theme.DIM, 26);

        paintDecor(g);
        paintFooter(g);
        if (helpAnim > 0.01) paintHelp(g);
    }

    private void paintTag(Graphics2D g, double x, double y, String text) {
        g.setFont(Theme.semibold(12));
        int w = g.getFontMetrics().stringWidth(text) + 28;
        g.setColor(new Color(0x12, 0x22, 0x38, 220));
        g.fill(new RoundRectangle2D.Double(x, y - 20, w, 30, 30, 30));
        g.setColor(new Color(0x2A, 0x40, 0x60));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x, y - 20, w, 30, 30, 30));
        g.setColor(Theme.ACCENT);
        g.drawString(text, (float) x + 14, (float) y);
    }

    private void paintDecor(Graphics2D g) {
        double x = 700;
        double y = 120;
        double w = 500;
        double h = 560;
        Brush.panel(g, x, y, w, h, 28);

        double bx = x + 30;
        double by = y + 30;
        double bw = w - 60;
        double bh = h - 130;

        g.setColor(new Color(0x08, 0x0E, 0x18, 235));
        g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 18, 18));
        g.setColor(new Color(255, 255, 255, 9));
        g.setStroke(new BasicStroke(1f));
        for (double gx = bx + 37; gx < bx + bw; gx += 37) {
            g.draw(new Line2D.Double(gx, by + 4, gx, by + bh - 4));
        }
        for (double gy = by + 37; gy < by + bh; gy += 37) {
            g.draw(new Line2D.Double(bx + 4, gy, bx + bw - 4, gy));
        }
        g.setColor(new Color(255, 255, 255, 22));
        g.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 18, 18));

        // еда
        double[][] food = {{0.24, 0.24}, {0.72, 0.30}, {0.58, 0.78}};
        for (int i = 0; i < food.length; i++) {
            double fx = bx + bw * food[i][0];
            double fy = by + bh * food[i][1];
            double pulse = 1 + Math.sin(time * 2.4 + i * 1.7) * 0.12;
            Color c = i == 1 ? Theme.YELLOW : Theme.MAGENTA;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
            g.fill(new Ellipse2D.Double(fx - 14 * pulse, fy - 14 * pulse, 28 * pulse, 28 * pulse));
            g.setColor(c);
            g.fill(new Ellipse2D.Double(fx - 6, fy - 6, 12, 12));
        }

        // декоративная змейка
        int n = 24;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            double tt = time - i * 0.085;
            xs[i] = bx + bw / 2 + Math.sin(tt * 1.05) * bw * 0.30 + Math.sin(tt * 0.47 + 1.1) * bw * 0.10;
            ys[i] = by + bh / 2 + Math.cos(tt * 0.83) * bh * 0.30 + Math.cos(tt * 0.41) * bh * 0.10;
        }
        // мягкое свечение под телом
        Path2D path = new Path2D.Double();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) path.lineTo(xs[i], ys[i]);
        g.setColor(new Color(0x4C, 0xFF, 0x9B, 28));
        g.setStroke(new BasicStroke(32f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);

        for (int i = n - 1; i > 0; i--) {
            float t = i / (float) n;
            Color c = Theme.mix(Theme.ACCENT, Theme.ACCENT2, t);
            g.setColor(c);
            g.setStroke(new BasicStroke((float) (22 - i * 0.62), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(xs[i], ys[i], xs[i - 1], ys[i - 1]));
        }
        // голова
        double hx = xs[0];
        double hy = ys[0];
        g.setColor(new Color(0x4C, 0xFF, 0x9B, 55));
        g.fill(new Ellipse2D.Double(hx - 22, hy - 22, 44, 44));
        g.setColor(Theme.ACCENT);
        g.fill(new Ellipse2D.Double(hx - 14, hy - 14, 28, 28));
        g.setColor(new Color(0x06, 0x14, 0x0E));
        double ang = Math.atan2(ys[0] - ys[1], xs[0] - xs[1]);
        double ex = Math.cos(ang) * 4;
        double ey = Math.sin(ang) * 4;
        double px = -Math.sin(ang) * 4.5;
        double py = Math.cos(ang) * 4.5;
        g.fill(new Ellipse2D.Double(hx + ex + px - 2.2, hy + ey + py - 2.2, 4.4, 4.4));
        g.fill(new Ellipse2D.Double(hx + ex - px - 2.2, hy + ey - py - 2.2, 4.4, 4.4));

        Brush.text(g, "ПРИМЕР ПАРТИИ", x + 32, y + h - 62, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, "3 игрока  ·  2 бота  ·  Wi-Fi", x + 32, y + h - 34, Theme.font(16), Theme.DIM);

        // статус-точка
        double pulse = 0.5 + 0.5 * Math.sin(time * 3);
        g.setColor(new Color(0x4C, 0xFF, 0x9B, (int) (90 + pulse * 120)));
        g.fill(new Ellipse2D.Double(x + w - 44, y + h - 56, 9, 9));
    }

    private void paintFooter(Graphics2D g) {
        String ip = NetInfo.primaryIp();
        Brush.text(g, "ТВОЙ АДРЕС В СЕТИ", 96, 738, Theme.semibold(11), Theme.MUTED);
        Brush.text(g, ip, 96, 768, Theme.mono(20), Theme.DIM);
        Brush.text(g, "Друзьям нужен этот адрес и порт игры", 300, 768, Theme.font(14), Theme.MUTED);
        Brush.text(g, "v1.0", 1184, 768, Theme.font(13), Theme.MUTED);
    }

    private void paintHelp(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, (int) (170 * helpAnim)));
        g.fillRect(0, 0, VW, VH);
        double a = helpAnim;
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, (float) Math.min(1, a * 1.2)));
        Brush.panel(g, 300, 170, 680, 470, 26);
        Brush.text(g, "Как играть", 350, 236, Theme.display(34), Theme.TEXT);
        Brush.text(g, "60 секунд на освоение", 352, 266, Theme.font(15), Theme.MUTED);

        String[][] items = {
                {"1", "Хост жмёт «Создать игру»", "Выбирает порт и настройки. Его компьютер становится сервером."},
                {"2", "Друзья вводят адрес", "Тот же Wi-Fi: адрес вида 192.168.0.5 и порт, например 45800."},
                {"3", "Или находят сами", "Экран «Подключиться» ловит игры в локальной сети автоматически."},
                {"4", "Готовы — играем", "Стрелки или WASD. Ешь точки, не врезайся в стены и чужие хвосты."},
        };
        double y = 320;
        for (String[] item : items) {
            g.setColor(new Color(0x18, 0x2A, 0x42));
            g.fill(new Ellipse2D.Double(352, y - 22, 34, 34));
            Brush.centered(g, item[0], 369, y + 1, Theme.semibold(16), Theme.ACCENT);
            Brush.text(g, item[1], 404, y - 2, Theme.semibold(18), Theme.TEXT);
            Brush.text(g, item[2], 404, y + 22, Theme.font(14), Theme.DIM);
            y += 68;
        }
        g.setComposite(java.awt.AlphaComposite.SrcOver);
    }
}
