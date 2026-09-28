package tomato.gui.kit;

import java.awt.*;
import java.util.Objects;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * Value toward a cap: mint when maxed, amber while short (a stat below its cap needs potions), an empty track when unknown. A caller
 * whose short value is progress rather than a warning (a pet ability below its max level) sets another in-progress role.
 */
public class StatBar extends JComponent implements Accessible {
    private Integer value, cap;
    /** The fill while short of the cap; WARN unless {@link #setProgressRole} says otherwise. */
    private Tokens.Role progress = Tokens.Role.WARN;

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PROGRESS_BAR; }
        };
        return accessibleContext;
    }

    public StatBar() { setOpaque(false); set(null, null); }

    public void set(Integer value, Integer cap) {
        this.value = value;
        this.cap = cap;
        String text = value == null || cap == null ? "Not captured" : value + " of " + cap + (maxed() ? ", maxed" : "");
        setToolTipText(value == null || cap == null ? text : value + " of " + cap);
        getAccessibleContext().setAccessibleDescription(text);
        repaint();
    }

    /** The fill while the value is short of its cap (default WARN); a maxed bar stays GOOD whatever this is. */
    public void setProgressRole(Tokens.Role role) {
        progress = Objects.requireNonNull(role, "role");
        repaint();
    }

    public Tokens.Role progressRole() { return progress; }

    public boolean maxed() { return value != null && cap != null && cap > 0 && value >= cap; }

    @Override public Dimension getPreferredSize() { return new Dimension(80, 6); }
    @Override public Dimension getMinimumSize() { return new Dimension(24, 6); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int height = Math.min(getHeight(), 6), y = (getHeight() - height) / 2;
        g.setColor(Tokens.color(Tokens.Role.CONTROL));
        g.fillRoundRect(0, y, getWidth(), height, height, height);
        if (value != null && cap != null && cap > 0) {
            int width = (int) Math.round(getWidth() * Math.min(1.0, Math.max(0, value) / (double) cap));
            g.setColor(Tokens.color(maxed() ? Tokens.Role.GOOD : progress));
            g.fillRoundRect(0, y, width, height, height, height);
        }
        g.dispose();
    }
}
