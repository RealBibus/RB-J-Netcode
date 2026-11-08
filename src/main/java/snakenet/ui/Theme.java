package snakenet.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Палитра и шрифты интерфейса. */
public final class Theme {

    public static final Color BG = new Color(0x07, 0x0B, 0x14);
    public static final Color BG2 = new Color(0x0A, 0x10, 0x1E);
    public static final Color PANEL = new Color(0x0F, 0x19, 0x2B);
    public static final Color PANEL_2 = new Color(0x14, 0x21, 0x38);
    public static final Color LINE = new Color(0x20, 0x31, 0x4C);
    public static final Color TEXT = new Color(0xE9, 0xF2, 0xFF);
    public static final Color DIM = new Color(0x88, 0x9C, 0xBC);
    public static final Color MUTED = new Color(0x5C, 0x6E, 0x8C);

    public static final Color ACCENT = new Color(0x4C, 0xFF, 0x9B);
    public static final Color ACCENT_DARK = new Color(0x1E, 0x8F, 0x53);
    public static final Color ACCENT2 = new Color(0x3D, 0xDC, 0xFF);
    public static final Color MAGENTA = new Color(0xFF, 0x4D, 0x8D);
    public static final Color YELLOW = new Color(0xFF, 0xD1, 0x66);
    public static final Color DANGER = new Color(0xFF, 0x6B, 0x6B);
    public static final Color GOLD = new Color(0xFF, 0xC8, 0x4C);

    /** Цвета игроков: индекс приходит по сети. */
    public static final Color[] PLAYER = {
            new Color(0x4C, 0xFF, 0x9B),
            new Color(0x3D, 0xDC, 0xFF),
            new Color(0xFF, 0xD1, 0x66),
            new Color(0xFF, 0x4D, 0x8D),
            new Color(0xA7, 0x8B, 0xFA),
            new Color(0xFF, 0x9F, 0x43),
            new Color(0x4E, 0xA8, 0xFF),
            new Color(0x9C, 0xFF, 0x5C),
            new Color(0xFF, 0x6B, 0xD6),
            new Color(0x5C, 0xE8, 0xC4),
    };

    public static final String FAMILY = pick(
            "Segoe UI Variable Display", "Segoe UI", "Inter", "Noto Sans", "Tahoma", "Arial");
    public static final String DISPLAY = pick(
            "Segoe UI Black", "Segoe UI Variable Display", "Segoe UI", "Arial Black", "Arial");
    public static final String MONO = pick(
            "Cascadia Mono", "Consolas", "JetBrains Mono", "Courier New");

    private Theme() {
    }

    private static String pick(String... names) {
        try {
            Set<String> available = new HashSet<>(
                    Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String n : names) {
                if (available.contains(n)) return n;
            }
        } catch (Throwable ignored) {
            // headless / нет шрифтов
        }
        return Font.SANS_SERIF;
    }

    public static Font font(float size) {
        return new Font(FAMILY, Font.PLAIN, 100).deriveFont(size);
    }

    public static Font bold(float size) {
        return new Font(FAMILY, Font.BOLD, 100).deriveFont(size);
    }

    public static Font semibold(float size) {
        return new Font(FAMILY, Font.PLAIN, 100).deriveFont(Font.BOLD, size);
    }

    public static Font display(float size) {
        return new Font(DISPLAY, Font.BOLD, 100).deriveFont(size);
    }

    public static Font mono(float size) {
        return new Font(MONO, Font.PLAIN, 100).deriveFont(size);
    }

    public static Color player(int index) {
        return PLAYER[Math.floorMod(index, PLAYER.length)];
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    public static Color mix(Color a, Color b, float t) {
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }
}
