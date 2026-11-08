package snakenet.ui;

import snakenet.Prefs;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;


public final class MainWindow extends JFrame {

    private final App app;
    private final JPanel root = new JPanel(new BorderLayout());
    private final Timer timer;
    private Screen current;
    private long lastNanos;
    private long frames;
    private long fpsAt;
    private int fps = 60;

    public MainWindow(App app) {
        super("SNAKE NET — networked snake");
        this.app = app;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1300, 820);
        setLocationRelativeTo(null);
        root.setBackground(Theme.BG);
        setContentPane(root);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                app.shutdown();
            }
        });
        lastNanos = System.nanoTime();
        fpsAt = lastNanos;
        timer = new Timer(16, e -> tick());
        timer.start();
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.12, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        frames++;
        if (now - fpsAt > 1_000_000_000L) {
            fps = (int) frames;
            frames = 0;
            fpsAt = now;
        }
        app.backdrop().update(dt);
        if (current != null) current.tick(dt);
    }

    public void show(Screen screen) {
        if (screen == null) return;
        if (screen == current) {
            screen.revalidate();
            screen.repaint();
            return;
        }
        if (current != null) current.onHide();
        current = screen;
        root.removeAll();
        root.add(screen, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        screen.onShow();
        screen.requestFocusInWindow();
    }

    public Screen current() {
        return current;
    }

    public int fps() {
        return fps;
    }
}
