package tomato.gui.kit;

import java.awt.*;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;

/** One equipment or inventory slot. Empty and not-captured are drawn differently; items show their sprite and tier edge. */
public class ItemSlot extends JComponent implements Accessible {
    public enum State { ITEM, EMPTY, UNKNOWN }

    /** Plain JComponent has no accessible context; without this, getAccessibleContext() returns null. */
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
        };
        return accessibleContext;
    }

    private final int size;
    private State state = State.UNKNOWN;
    private int itemId = -1;
    private String tier = "";

    public ItemSlot(int size) {
        this.size = size;
        setOpaque(false);
        setUnknown();
    }

    public void setItem(int objectId, String tierLabel) {
        if (objectId <= 0) { setEmpty(); return; }
        state = State.ITEM;
        itemId = objectId;
        tier = tierLabel == null ? "" : tierLabel;
        String name = Sprites.name(objectId);
        describe(tier.isEmpty() ? name : name + " · " + tier);
    }

    public void setEmpty() { state = State.EMPTY; itemId = -1; tier = ""; describe("Empty slot"); }
    public void setUnknown() { state = State.UNKNOWN; itemId = -1; tier = ""; describe("Slot not captured"); }
    public State state() { return state; }
    public int itemId() { return itemId; }

    private void describe(String text) {
        setToolTipText(text);
        getAccessibleContext().setAccessibleName(text);
        repaint();
    }

    @Override public Dimension getPreferredSize() { return new Dimension(size + 6, size + 6); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int side = Math.min(getWidth(), getHeight()) - 1, x = (getWidth() - 1 - side) / 2, y = (getHeight() - 1 - side) / 2;
        g.setColor(Tokens.color(state == State.EMPTY ? Tokens.Role.SURFACE_ALT : Tokens.Role.RAISED));
        g.fillRoundRect(x, y, side, side, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        g.setColor(state == State.ITEM && !tier.isEmpty() ? Tokens.tier(tier) : Tokens.color(Tokens.Role.BORDER_SUBTLE));
        g.drawRoundRect(x, y, side, side, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        if (state == State.ITEM) {
            Icon icon = Sprites.sprite(itemId, size);
            icon.paintIcon(this, g, x + (side + 1 - icon.getIconWidth()) / 2, y + (side + 1 - icon.getIconHeight()) / 2);
        } else if (state == State.UNKNOWN) {
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            g.setFont(Type.caption());
            FontMetrics metrics = g.getFontMetrics();
            g.drawString("?", x + (side + 1 - metrics.stringWidth("?")) / 2, y + (side + 1 + metrics.getAscent() - metrics.getDescent()) / 2);
        }
        g.dispose();
    }
}
