package tomato.gui.kit;

import java.awt.*;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.modern.ContentStyle;

/** N small squares, the first `filled` in color: exalt tiers, maxed stats. Scales with the body font. */
public class PipMeter extends JComponent implements Accessible {
    private static final int GAP = 3;

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PROGRESS_BAR; }
        };
        return accessibleContext;
    }
    private final int total;
    private int filled;
    private Tokens.Role color = Tokens.Role.ACCENT;

    public PipMeter(int total) {
        if (total < 1) throw new IllegalArgumentException("A pip meter needs at least one pip");
        this.total = total;
        setOpaque(false);
        setFilled(0);
    }

    public void setFilled(int value) {
        filled = Math.max(0, Math.min(total, value));
        String text = filled + " of " + total;
        setToolTipText(text);
        getAccessibleContext().setAccessibleDescription(text);
        repaint();
    }

    public int filled() { return filled; }
    public void setColor(Tokens.Role role) { color = role; repaint(); }

    private int pip() { return Math.max(7, Math.round(ContentStyle.body().getSize2D() * 0.7f)); }

    @Override public Dimension getPreferredSize() { int pip = pip(); return new Dimension(total * pip + (total - 1) * GAP, pip); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int pip = pip(), y = (getHeight() - pip) / 2;
        for (int i = 0; i < total; i++) {
            g.setColor(Tokens.color(i < filled ? color : Tokens.Role.CONTROL));
            g.fillRoundRect(i * (pip + GAP), y, pip, pip, 3, 3);
        }
        g.dispose();
    }
}
