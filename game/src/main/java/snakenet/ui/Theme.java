package snakenet.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Цвета и шрифты. Тёмный фон, тёплый янтарный акцент, без неона. */
public final class Theme {

    public static final Color BG = new Color(0x0D, 0x10, 0x15);
    public static final Color BG2 = new Color(0x12, 0x17, 0x1E);
    public static final Color PANEL = new Color(0x18, 0x1E, 0x27);
    public static final Color PANEL_2 = new Color(0x1F, 0x27, 0x32);
    public static final Color LINE = new Color(0x2C, 0x36, 0x42);
    public static final Color TEXT = new Color(0xF1, 0xEA, 0xDE);
    public static final Color DIM = new Color(0x9E, 0xA8, 0xB3);
    public static final Color MUTED = new Color(0x66, 0x71, 0x7D);

    public static final Color ACCENT = new Color(0xF2, 0xAF, 0x5C);
    public static final Color ACCENT_DARK = new Color(0xB8, 0x77, 0x2F);
    public static final Color ACCENT2 = new Color(0x6F, 0xC9, 0xBC);
    public static final Color MAGENTA = new Color(0xD9, 0x6B, 0x77);
    public static final Color YELLOW = new Color(0xE8, 0xC9, 0x6A);
    public static final Color DANGER = new Color(0xE0, 0x6C, 0x75);
    public static final Color GOLD = new Color(0xE8, 0xC9, 0x6A);

    /** Цвета игроков: индекс приходит по сети. */
    public static final Color[] PLAYER = {
            new Color(0xE8, 0xB4, 0x5C),
            new Color(0x63, 0xC7, 0xB2),
            new Color(0xE8, 0xD9, 0xB5),
            new Color(0xDD, 0x6C, 0x75),
            new Color(0xA4, 0x8B, 0xD4),
            new Color(0xE6, 0x83, 0x6B),
            new Color(0x6F, 0xA8, 0xDC),
            new Color(0xB5, 0xC4, 0x6B),
            new Color(0xD7, 0x7F, 0xA8),
            new Color(0x8F, 0xD9, 0xC0),
    };

    public static final String FAMILY = pick(
            "Segoe UI", "Inter", "Noto Sans", "Tahoma", "Arial");
    public static final String DISPLAY = pick(
            "Segoe UI Black", "Segoe UI", "Arial Black", "Arial");
    public static final String MONO = pick(
            "Cascadia Mono", "Consolas", "Courier New");

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

    /** Затемнить/высветлить цвет для объёма сегментов. */
    public static Color shade(Color c, double amount) {
        if (amount >= 0) {
            return mix(c, Color.WHITE, (float) Math.min(1, amount));
        }
        return mix(c, Color.BLACK, (float) Math.min(1, -amount));
    }
}
