package snakenet.ui;

import snakenet.audio.Sfx;
import snakenet.net.Msg;

import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Базовый экран. Всё рисуется в виртуальной системе координат 1280×800
 * и масштабируется под окно; поля ввода — настоящие компоненты поверх.
 */
public abstract class Screen extends JPanel implements MouseListener, MouseMotionListener {

    public static final int VW = 1280;
    public static final int VH = 800;

    protected final App app;
    protected final MainWindow win;
    protected double scale = 1;
    protected double ox;
    protected double oy;
    protected double time;
    protected int mouseX = -1000;
    protected int mouseY = -1000;

    private final List<NeonButton> buttons = new ArrayList<>();
    private final List<Hotspot> hotspots = new ArrayList<>();
    private final List<FieldSlot> fields = new ArrayList<>();
    private double lastScale = -1;

    /** Кликабельная область без кнопки: строки списка, кружки цвета. */
    private static final class Hotspot {
        final double x;
        final double y;
        final double w;
        final double h;
        final Runnable action;

        Hotspot(double x, double y, double w, double h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private static final class FieldSlot {
        final JTextField field;
        final int x;
        final int y;
        final int w;
        final int h;
        final float baseFont;

        FieldSlot(JTextField field, int x, int y, int w, int h, float baseFont) {
            this.field = field;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.baseFont = baseFont;
        }
    }

    protected Screen(App app) {
        this.app = app;
        this.win = app.window();
        setOpaque(true);
        setBackground(Theme.BG);
        setLayout(null);
        setFocusable(true);
        addMouseListener(this);
        addMouseMotionListener(this);
    }

    protected abstract void paintScreen(Graphics2D g);

    protected void onTick(double dt) {
    }

    public void onShow() {
    }

    public void onHide() {
    }

    public void onFrame(List<Msg> frame) {
    }

    public final void tick(double dt) {
        time += dt;
        for (NeonButton b : buttons) b.update(dt, mouseX, mouseY);
        onTick(dt);
        updateCursor();
        repaint();
    }

    private void updateCursor() {
        boolean hand = false;
        for (NeonButton b : buttons) {
            if (b.visible && b.enabled && b.contains(mouseX, mouseY)) {
                hand = true;
                break;
            }
        }
        if (!hand) {
            for (Hotspot h : hotspots) {
                if (h.contains(mouseX, mouseY)) {
                    hand = true;
                    break;
                }
            }
        }
        setCursor(java.awt.Cursor.getPredefinedCursor(
                hand ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
    }

    protected final NeonButton add(NeonButton b) {
        buttons.add(b);
        return b;
    }

    protected final void addField(JTextField field, int x, int y, int w, int h, float baseFont) {
        fields.add(new FieldSlot(field, x, y, w, h, baseFont));
        add(field);
    }

    protected final List<NeonButton> buttons() {
        return buttons;
    }

    protected final void clearButtons() {
        buttons.clear();
    }

    protected final void addHotspot(double x, double y, double w, double h, Runnable action) {
        hotspots.add(new Hotspot(x, y, w, h, action));
    }

    protected final void clearHotspots() {
        hotspots.clear();
    }

    @Override
    public void doLayout() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        scale = Math.min(w / (double) VW, h / (double) VH);
        ox = (w - VW * scale) / 2.0;
        oy = (h - VH * scale) / 2.0;
        if (Math.abs(scale - lastScale) > 0.0005) {
            lastScale = scale;
            for (FieldSlot slot : fields) {
                int bx = (int) Math.round(ox + slot.x * scale);
                int by = (int) Math.round(oy + slot.y * scale);
                int bw = (int) Math.round(slot.w * scale);
                int bh = (int) Math.round(slot.h * scale);
                slot.field.setBounds(bx, by, bw, bh);
                slot.field.setFont(Theme.font((float) (slot.baseFont * scale)));
                if (slot.field instanceof InputField) {
                    ((InputField) slot.field).setUiScale(scale);
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        Graphics2D g = (Graphics2D) g0;
        app.backdrop().paint(g, w, h);

        scale = Math.min(w / (double) VW, h / (double) VH);
        ox = (w - VW * scale) / 2.0;
        oy = (h - VH * scale) / 2.0;

        Graphics2D v = (Graphics2D) g.create();
        v.translate(ox, oy);
        v.scale(scale, scale);
        v.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        v.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        v.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        v.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        v.clipRect(0, 0, VW, VH);
        paintScreen(v);
        for (NeonButton b : buttons) b.paint(v);
        v.dispose();

        drawToast(g);
    }

    private void drawToast(Graphics g0) {
        String text = app.toastText();
        if (text == null) return;
        float alpha = app.toastAlpha();
        if (alpha <= 0.01f) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(Theme.semibold((float) (16 * scale)));
        FontMetrics fm = g.getFontMetrics();
        double tw = fm.stringWidth(text) + 48 * scale;
        double th = 48 * scale;
        double x = (getWidth() - tw) / 2.0;
        double y = getHeight() - th - 28 * scale;
        Color accent = app.toastIsError() ? Theme.DANGER : Theme.ACCENT;
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
        g.setColor(new Color(0x0C, 0x14, 0x22, 240));
        g.fill(Brush.round(x, y, tw, th, th / 2));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 160));
        g.setStroke(new BasicStroke((float) Math.max(1, 1.2 * scale)));
        g.draw(Brush.round(x, y, tw, th, th / 2));
        g.setColor(accent);
        g.drawString(text, (float) (x + 24 * scale), (float) (y + th / 2 + (fm.getAscent() - fm.getDescent()) / 2.0));
        g.dispose();
    }

    // ------------------------------------------------------------------
    // мышь
    // ------------------------------------------------------------------

    private double vx(MouseEvent e) {
        return (e.getX() - ox) / Math.max(0.001, scale);
    }

    private double vy(MouseEvent e) {
        return (e.getY() - oy) / Math.max(0.001, scale);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) return;
        double x = vx(e);
        double y = vy(e);
        for (int i = buttons.size() - 1; i >= 0; i--) {
            NeonButton b = buttons.get(i);
            if (b.contains(x, y) && b.enabled) {
                Sfx.play(Sfx.Sound.CLICK);
                if (b.action != null) b.action.run();
                return;
            }
        }
        for (int i = hotspots.size() - 1; i >= 0; i--) {
            Hotspot h = hotspots.get(i);
            if (h.contains(x, y)) {
                Sfx.play(Sfx.Sound.CLICK);
                h.action.run();
                return;
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        mouseX = (int) vx(e);
        mouseY = (int) vy(e);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        mouseMoved(e);
    }
}
