package tomato.gui.kit;

import java.awt.*;
import java.util.Objects;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.realmshark.EnchantInfo;

/** One equipment or inventory slot. Empty and not-captured are drawn differently; items show their sprite, tier edge and, where the surface knows it, an enchant rarity gem. */
public class ItemSlot extends JComponent implements Accessible {
    /** UNKNOWN is a slot that was not captured ("Slot not captured"), never an empty one. */
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
    /** The item's enchantments where this surface knows them; null otherwise (and always null unless an item is shown). */
    private EnchantInfo enchant;

    /**
     * The slot as a painted {@link Icon} for table and list renderers, drawn exactly as an {@code ItemSlot(size)} component and as
     * large ({@code size + Sprites.WELL} square). ITEM draws {@code sprite} (the kit placeholder when null) centered, scaled down to
     * {@code size} when larger, in a well bordered by its tier ({@link ItemTiers#label} → {@link Tokens#tier}; subtle when "");
     * EMPTY and UNKNOWN (not captured) ignore the sprite and tier and look different from each other. Colors resolve while painting.
     * It is a stamp, not a component: it announces nothing to assistive technology, so the renderer's cell must say what it shows.
     */
    public static Icon icon(Icon sprite, String tier, State state, int size) {
        Objects.requireNonNull(state, "state");
        int side = size + Sprites.WELL;
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Icon shown = state != State.ITEM ? null
                    : sprite == null || sprite.getIconWidth() <= 0 || sprite.getIconHeight() <= 0 ? Sprites.sprite(0, size) : sprite;
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    paint(c, g, state, shown, tier, x, y, side - 1);
                } finally {
                    g.dispose();
                }
            }
            @Override public int getIconWidth() { return side; }
            @Override public int getIconHeight() { return side; }
        };
    }

    /**
     * One slot's drawing, shared by the component and {@link #icon}: a rounded well {@code side + 1} px square at x, y (the fill,
     * then the edge: the tier's color for an item with a tier, subtle otherwise), then the sprite centered (scaled down only when
     * it would cover the well's border) or, when not captured, a muted "?".
     */
    private static void paint(Component owner, Graphics2D g, State state, Icon sprite, String tier, int x, int y, int side) {
        boolean tiered = state == State.ITEM && tier != null && !tier.isEmpty();
        g.setColor(Tokens.color(state == State.EMPTY ? Tokens.Role.SURFACE_ALT : Tokens.Role.RAISED));
        g.fillRoundRect(x, y, side, side, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        g.setColor(tiered ? Tokens.tier(tier) : Tokens.color(Tokens.Role.BORDER_SUBTLE));
        g.drawRoundRect(x, y, side, side, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        if (state == State.ITEM && sprite != null) {
            int room = side + 1 - Sprites.WELL, width = sprite.getIconWidth(), height = sprite.getIconHeight();
            if (width <= room && height <= room) {
                sprite.paintIcon(owner, g, x + (side + 1 - width) / 2, y + (side + 1 - height) / 2);
            } else {
                double scale = (double) room / Math.max(width, height);
                Graphics2D icon = (Graphics2D) g.create();
                try {
                    icon.translate(x + (side + 1 - width * scale) / 2, y + (side + 1 - height * scale) / 2);
                    icon.scale(scale, scale);
                    sprite.paintIcon(owner, icon, 0, 0);
                } finally {
                    icon.dispose();
                }
            }
        } else if (state == State.UNKNOWN) {
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            g.setFont(Type.caption());
            FontMetrics metrics = g.getFontMetrics();
            g.drawString("?", x + (side + 1 - metrics.stringWidth("?")) / 2, y + (side + 1 + metrics.getAscent() - metrics.getDescent()) / 2);
        }
    }

    public ItemSlot(int size) {
        this.size = size;
        setOpaque(false);
        setUnknown();
    }

    /** The description last announced to assistive technology; a refill that reads the same announces nothing. */
    private String described;

    public void setItem(int objectId, String tierLabel) { setItem(objectId, tierLabel, null); }

    /** As {@link #setItem(int, String)}, with the item's enchantments (null when this surface has none): a gem and the enchant tooltip. */
    public void setItem(int objectId, String tierLabel, EnchantInfo enchantments) {
        if (objectId <= 0) { setEmpty(); return; }
        show(State.ITEM, objectId, tierLabel == null ? "" : tierLabel, enchantments);
    }

    public void setEmpty() { show(State.EMPTY, -1, "", null); }
    public void setUnknown() { show(State.UNKNOWN, -1, "", null); }
    public State state() { return state; }
    public int itemId() { return itemId; }
    public EnchantInfo enchant() { return enchant; }

    /** The item's name and tier; "Unknown item #id" only while assets cannot name it. */
    private String itemText() { return EnchantTooltip.heading(Sprites.name(itemId), tier); }

    /** The slot's description with the item's current name, and its enchant summary when known. */
    private String text() {
        switch (state) {
            case ITEM: return enchant == null ? itemText() : itemText() + " · " + enchant.summary();
            case EMPTY: return "Empty slot";
            default: return "Slot not captured";
        }
    }

    /** Refilling a slot with what it already shows repaints and announces nothing (pages refresh slots every second). */
    private void show(State next, int id, String label, EnchantInfo enchantments) {
        boolean redraw = state != next || itemId != id || !tier.equals(label) || !Objects.equals(enchant, enchantments);
        state = next;
        itemId = id;
        tier = label;
        enchant = enchantments;
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

    /** Built when the tooltip is asked for, so its colors follow the theme and nothing is built per paint. */
    @Override public String getToolTipText() {
        return state == State.ITEM && enchant != null ? EnchantTooltip.html(itemText(), enchant) : text();
    }

    @Override public Dimension getPreferredSize() { return new Dimension(size + 6, size + 6); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int side = Math.min(getWidth(), getHeight()) - 1, x = (getWidth() - 1 - side) / 2, y = (getHeight() - 1 - side) / 2;
        paint(this, g, state, state == State.ITEM ? Sprites.sprite(itemId, size) : null, tier, x, y, side);
        if (state == State.ITEM) EnchantGem.paint(g, enchant, x, y, side);
        g.dispose();
    }
}
