package tomato.gui.glance.character;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Map;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Paints one class tile of the account Exalts grid (spec §6.2, §9): one component reused for every cell of the kit TileList, no
 * per-tile component tree. A 34 px class sprite, the class name and total completions, a 5-pip meter of the lowest tier, an
 * 8-cell heat strip of every stat's tier (canonical order; none CONTROL, tiers 1–4 toward ACCENT, maxed GOOD, as PipMeter and
 * the character cards color progress) and the class's loot boost ("Loot —" when unknown, never 0). Sizes follow the body font;
 * colors come from Tokens at paint time.
 */
final class ExaltTileRenderer extends JComponent implements ListCellRenderer<AccountExalts.Tile>, Accessible {
    static final int SPRITE = 34, PIPS = 5, PIP_GAP = 3, HEAT_GAP = 2, GAP = 10; // GAP: space between tiles (spec §5.2)
    /** The "—" tooltip reason: the boost needs every class of the weapon group and the class data from the assets. */
    static final String UNKNOWN_BOOST = "Needs saved exalts for every class that shares this class's weapon, and class data from the selected game assets";
    private AccountExalts.Tile tile;
    private Lines lines;
    private boolean selected, focused;

    /** The text one tile paints (painted text is not in the component tree, so tests read it here); {@code heat} the 8 tiers. */
    record Lines(String title, String total, int lowest, String tier, List<Integer> heat, String loot) {}

    ExaltTileRenderer() { setOpaque(false); }

    static Lines lines(AccountExalts.Tile tile) {
        return new Lines(tile.className(), "Total " + DisplayFormat.formatInteger(tile.total()), tile.lowest(), "Lowest " + tile.lowest() + "/5",
            tile.tiers(), tile.lootBoost() == null ? "Loot " + DisplayFormat.UNAVAILABLE : "+" + tile.lootBoost() + "% loot");
    }

    /** "Wizard: 312 completions, lowest tier 3 of 5, loot boost 15%" (or "loot boost unknown"): stable while time passes. */
    static String accessibleName(AccountExalts.Tile tile) {
        return tile.className() + ": " + DisplayFormat.formatInteger(tile.total()) + (tile.total() == 1 ? " completion" : " completions")
            + ", lowest tier " + tile.lowest() + " of 5, loot boost " + (tile.lootBoost() == null ? "unknown" : tile.lootBoost() + "%");
    }

    /** A heat cell's color for a tier: CONTROL for none, evenly stronger toward ACCENT for tiers 1–4, GOOD once maxed. */
    static Color heat(int tier) {
        if (tier <= 0) return Tokens.color(Tokens.Role.CONTROL);
        if (tier >= 5) return Tokens.color(Tokens.Role.GOOD);
        return Tokens.blend(Tokens.color(Tokens.Role.CONTROL), Tokens.color(Tokens.Role.ACCENT), .3f + .7f * (tier - 1) / 3f);
    }

    /**
     * The list cell: the tile plus half the gap between tiles on each side, at the current body font. It is at least as wide as
     * its widest row (the pips, "Lowest 5/5" and "+35% loot"), so that row is never cut at any font size.
     */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption());
        int pip = pip();
        int height = Tokens.M + Math.max(SPRITE, title.getHeight() + caption.getHeight()) + Tokens.S + Math.max(pip, caption.getHeight())
            + Tokens.S + pip + Tokens.M;
        int row = PIPS * (pip + PIP_GAP) + Tokens.XS + caption.stringWidth("Lowest 5/5") + Tokens.S + caption.stringWidth("+35% loot");
        int width = Math.max(Math.max(190, Math.round(ContentStyle.body().getSize2D() * 15f)), Tokens.M + row + Tokens.M);
        return new Dimension(width + GAP, height + GAP);
    }

    /** The kit TileList sizes its fixed cells from the renderer's preferred size: the cell. */
    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends AccountExalts.Tile> list, AccountExalts.Tile value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        tile = value;
        selected = isSelected;
        focused = cellHasFocus;
        lines = value == null ? null : lines(value);
        getAccessibleContext().setAccessibleName(value == null ? null : accessibleName(value));
        getAccessibleContext().setAccessibleDescription(value == null ? null : stats(value));
        setToolTipText(value == null ? null : tooltip(value));
        return this;
    }

    /** "Life 5/5 · Mana 4/5 · …", every stat's tier in canonical order. */
    private static String stats(AccountExalts.Tile tile) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8 && i < tile.tiers().size(); i++) text.append(i == 0 ? "" : " · ").append(SheetViews.STATS[i]).append(' ').append(tile.tiers().get(i)).append("/5");
        return text.toString();
    }

    private static String tooltip(AccountExalts.Tile tile) {
        return tile.className() + " · Total " + DisplayFormat.formatInteger(tile.total()) + " · Lowest tier " + tile.lowest() + "/5 · " + stats(tile)
            + " · " + (tile.lootBoost() == null ? "Loot boost unknown: " + UNKNOWN_BOOST : "Loot boost +" + tile.lootBoost() + "%")
            + " · From saved exalt counts, changed " + changed(tile.seenAt());
    }

    /** "2 h ago", or "at an unknown time" for 0: when saved counts last changed (the tooltip is built when shown, so it stays current). */
    static String changed(long seenAt) { return seenAt > 0 ? KitFormat.relative(seenAt) : "at an unknown time"; }

    @Override protected void paintComponent(Graphics graphics) {
        if (tile == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map) g.addRenderingHints((Map<?, ?>) hints);
            int x = GAP / 2, y = GAP / 2, w = getWidth() - GAP, h = getHeight() - GAP;
            RoundRectangle2D shape = new RoundRectangle2D.Float(x + .5f, y + .5f, w - 1, h - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
            g.setColor(Tokens.color(selected ? Tokens.Role.ACCENT_WASH : Tokens.Role.RAISED));
            g.fill(shape);
            g.setColor(Tokens.color(selected || focused ? Tokens.Role.ACCENT : Tokens.Role.BORDER_SUBTLE));
            g.setStroke(new BasicStroke(focused ? 2f : 1f));
            g.draw(shape);
            Color ink = Tokens.color(Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            int left = x + Tokens.M, top = y + Tokens.M, right = x + w - Tokens.M;
            Sprites.sprite(tile.classId(), SPRITE).paintIcon(this, g, left, top);
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics titleMetrics = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int textLeft = left + SPRITE + Tokens.S, baseline = top + titleMetrics.getAscent();
            text(g, lines.title(), titleFont, titleMetrics, ink, textLeft, baseline, right - textLeft);
            text(g, lines.total(), captionFont, caption, muted, textLeft, baseline + titleMetrics.getDescent() + caption.getAscent(), right - textLeft);
            // The lowest tier as pips, its "Lowest n/5" text, and the loot boost at the right edge.
            int row = top + Math.max(SPRITE, titleMetrics.getHeight() + caption.getHeight()) + Tokens.S;
            int pip = pip(), rowHeight = Math.max(pip, caption.getHeight()), pipY = row + (rowHeight - pip) / 2;
            for (int i = 0; i < PIPS; i++) {
                g.setColor(Tokens.color(i >= lines.lowest() ? Tokens.Role.CONTROL : lines.lowest() >= PIPS ? Tokens.Role.GOOD : Tokens.Role.ACCENT));
                g.fillRoundRect(left + i * (pip + PIP_GAP), pipY, pip, pip, 3, 3);
            }
            int textBaseline = row + (rowHeight - caption.getHeight()) / 2 + caption.getAscent();
            int lootLeft = right - caption.stringWidth(lines.loot());
            text(g, lines.loot(), captionFont, caption, tile.lootBoost() == null ? muted : ink, Math.max(left, lootLeft), textBaseline, right - left);
            int after = left + PIPS * (pip + PIP_GAP) + Tokens.XS;
            text(g, lines.tier(), captionFont, caption, muted, after, textBaseline, lootLeft - Tokens.S - after);
            // The heat strip: one cell per stat across the tile's width.
            int stripTop = row + rowHeight + Tokens.S, cells = lines.heat().size();
            int cell = cells == 0 ? 0 : Math.max(1, (right - left - (cells - 1) * HEAT_GAP) / cells);
            for (int i = 0; i < cells; i++) {
                g.setColor(heat(lines.heat().get(i)));
                g.fillRoundRect(left + i * (cell + HEAT_GAP), stripTop, cell, pip, 3, 3);
            }
        } finally {
            g.dispose();
        }
    }

    /** PipMeter's pip size: follows the body font. */
    private static int pip() { return Math.max(7, Math.round(ContentStyle.body().getSize2D() * .7f)); }

    private static void text(Graphics2D g, String value, Font font, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value.isEmpty() || width <= 0) return;
        g.setFont(font);
        g.setColor(color);
        g.drawString(CharacterCardRenderer.fit(value, metrics, width), x, baseline);
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
