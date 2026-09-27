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
            /** Resolved when asked, so a slot filled before assets loaded reads its item's name afterwards. */
            @Override public String getAccessibleName() { return accessibleName != null ? accessibleName : text(); }
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

    /** The description last announced to assistive technology; a refill that reads the same announces nothing. */
    private String described;

    public void setItem(int objectId, String tierLabel) {
        if (objectId <= 0) { setEmpty(); return; }
        show(State.ITEM, objectId, tierLabel == null ? "" : tierLabel);
    }

    public void setEmpty() { show(State.EMPTY, -1, ""); }
    public void setUnknown() { show(State.UNKNOWN, -1, ""); }
    public State state() { return state; }
    public int itemId() { return itemId; }

    /** The slot's description with the item's current name; "Unknown item #id" only while assets cannot name it. */
    private String text() {
        switch (state) {
            case ITEM: { String name = Sprites.name(itemId); return tier.isEmpty() ? name : name + " · " + tier; }
            case EMPTY: return "Empty slot";
            default: return "Slot not captured";
        }
    }

    /** Refilling a slot with what it already shows repaints and announces nothing (pages refresh slots every second). */
    private void show(State next, int id, String label) {
        boolean redraw = state != next || itemId != id || !tier.equals(label);
        state = next;
        itemId = id;
        tier = label;
        describe();
        if (redraw) repaint();
    }

    /** Registers the tooltip and announces a changed description; both texts are resolved again on every request. */
    private void describe() {
        String text = text();
        super.setToolTipText(text);
        if (!text.equals(described)) {
            String previous = described;
            described = text;
            getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, previous, text);
        }
    }

    @Override public String getToolTipText() { return text(); }

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
