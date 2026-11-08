package snakenet.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Палитра «инженерного стенда»: тёмный графит, моноширинный шрифт, зелёный акцент. */
public final class Theme {

    public static final Color BG = new Color(0x0A, 0x0C, 0x0E);
    public static final Color BG2 = new Color(0x0C, 0x0F, 0x12);
    public static final Color PANEL = new Color(0x10, 0x14, 0x17);
    public static final Color PANEL_2 = new Color(0x16, 0x1B, 0x1F);
    public static final Color LINE = new Color(0x24, 0x2C, 0x33);
    public static final Color TEXT = new Color(0xD6, 0xDE, 0xE5);
    public static final Color DIM = new Color(0x8B, 0x99, 0xA5);
    public static final Color MUTED = new Color(0x5A, 0x66, 0x70);

    public static final Color ACCENT = new Color(0x7E, 0xE7, 0x87);
    public static final Color ACCENT_DARK = new Color(0x2E, 0xA0, 0x43);
    public static final Color ACCENT2 = new Color(0x58, 0xA6, 0xFF);
    public static final Color MAGENTA = new Color(0xF8, 0x51, 0x49);
    public static final Color YELLOW = new Color(0xD2, 0x99, 0x22);
    public static final Color DANGER = new Color(0xF8, 0x51, 0x49);
    public static final Color GOLD = new Color(0xD2, 0x99, 0x22);

    /** Цвета игроков: индекс приходит по сети. */
    public static final Color[] PLAYER = {
            new Color(0x7E, 0xE7, 0x87),
            new Color(0x58, 0xA6, 0xFF),
            new Color(0xD2, 0x99, 0x22),
            new Color(0xF8, 0x51, 0x49),
            new Color(0xBC, 0x8C, 0xFF),
            new Color(0xF0, 0x88, 0x3E),
            new Color(0x39, 0xC5, 0xCF),
            new Color(0xA5, 0xD6, 0x4E),
            new Color(0xDB, 0x61, 0xA2),
            new Color(0x56, 0xD3, 0x64),
    };

    public static final String FAMILY = pick(
            "Cascadia Mono", "Consolas", "JetBrains Mono", "Courier New", "Monospaced");
    public static final String DISPLAY = pick(
            "Cascadia Mono", "Consolas", "JetBrains Mono", "Courier New", "Monospaced");
    public static final String MONO = FAMILY;

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
        return Font.MONOSPACED;
    }

    public static Font font(float size) {
        return new Font(FAMILY, Font.PLAIN, 100).deriveFont(size);
    }

    public static Font bold(float size) {
        return new Font(FAMILY, Font.BOLD, 100).deriveFont(size);
    }

    public static Font semibold(float size) {
        return new Font(FAMILY, Font.BOLD, 100).deriveFont(size);
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

    public static Color shade(Color c, double amount) {
        if (amount >= 0) {
            return mix(c, Color.WHITE, (float) Math.min(1, amount));
        }
        return mix(c, Color.BLACK, (float) Math.min(1, -amount));
    }
}
