package snakenet.ui;

import javax.swing.BorderFactory;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;


public final class InputField extends JTextField {

    private String hint;
    private double uiScale = 1;

    public InputField(String hint) {
        super();
        this.hint = hint;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 16, 2, 16));
        setForeground(Theme.TEXT);
        setCaretColor(Theme.ACCENT);
        setSelectionColor(new Color(0x5C, 0x45, 0x26));
        setSelectedTextColor(Color.WHITE);
        setFont(Theme.font(19));
    }

    public void setHint(String h) {
        this.hint = h;
        repaint();
    }

    public void setUiScale(double s) {
        uiScale = s;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean focused = isFocusOwner();
        float r = (float) (10 * uiScale);
        g2.setColor(focused ? new Color(0x26, 0x2E, 0x3A) : new Color(0x18, 0x1E, 0x27));
        g2.fill(Brush.round(0, 0, getWidth(), getHeight(), r));
        g2.setColor(focused ? Theme.ACCENT : Theme.LINE);
        g2.setStroke(new BasicStroke((float) Math.max(1, 1.4 * uiScale)));
        g2.draw(Brush.round(0.7, 0.7, getWidth() - 1.4, getHeight() - 1.4, r));
        g2.dispose();

        super.paintComponent(g);

        if (getText().isEmpty() && !focused && hint != null && !hint.isEmpty()) {
            Graphics2D g3 = (Graphics2D) g.create();
            g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g3.setFont(getFont().deriveFont(Font.PLAIN));
            g3.setColor(Theme.MUTED);
            Insets in = getInsets();
            FontMetrics fm = g3.getFontMetrics();
            g3.drawString(hint, in.left, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
            g3.dispose();
        }
    }
}
