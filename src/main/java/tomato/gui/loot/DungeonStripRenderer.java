package tomato.gui.loot;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;

/**
 * Paints one area of Loot › Highlights' per-dungeon strip (spec §6.4): the portal sprite ({@code DungeonCell.portalId}, the kit's
 * placeholder when unknown), the area's name ("Unknown area" for bags without a known map), its bag count and "1 UT · 2 potions".
 * One component reused for every cell; fixed at 13 em of the body font; colors from Tokens at paint time.
 */
public final class DungeonStripRenderer extends JComponent implements ListCellRenderer<HighlightsModel.DungeonCell>, Accessible {
    static final int PORTAL = 32, GAP = 8, PAD = Tokens.S;

    /** The text one cell paints. */
    record Lines(String name, String bags, String summary) {}

    private HighlightsModel.DungeonCell cell;
    private Lines lines;
    private boolean selected, focused;

    public DungeonStripRenderer() { setOpaque(false); }

    static Lines lines(HighlightsModel.DungeonCell cell) {
        List<String> parts = new ArrayList<>();
        if (cell.ut() > 0) parts.add(cell.ut() + " UT");
        if (cell.st() > 0) parts.add(cell.st() + " ST");
        if (cell.potions() > 0) parts.add(cell.potions() + (cell.potions() == 1 ? " potion" : " potions"));
        return new Lines(cell.name(), cell.bags() + (cell.bags() == 1 ? " bag" : " bags"), parts.isEmpty() ? "No UT, ST or potions" : String.join(" · ", parts));
    }

    /** "Lost Halls; 3 bags; 1 UT · 2 potions". */
    static String accessibleName(HighlightsModel.DungeonCell cell) {
        Lines lines = lines(cell);
        return lines.name() + "; " + lines.bags() + "; " + lines.summary();
    }

    Lines shown() { return lines; }

    /** The list cell: the card plus half the gap between cells on each side, at the current body font. */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption());
        int height = 2 * PAD + Math.max(PORTAL, title.getHeight() + 2 * caption.getHeight());
        int width = Math.max(Math.round(ContentStyle.body().getSize2D() * 13f), PORTAL + 2 * PAD + 100);
        return new Dimension(width + GAP, height + GAP);
    }

    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends HighlightsModel.DungeonCell> list, HighlightsModel.DungeonCell value,
                                                            int index, boolean isSelected, boolean cellHasFocus) {
        cell = value;
        selected = isSelected;
        focused = cellHasFocus;
        lines = value == null ? null : lines(value);
        String name = value == null ? null : accessibleName(value);
        getAccessibleContext().setAccessibleName(name);
        getAccessibleContext().setAccessibleDescription(value == null ? null : "Enter or double-click shows this area's loot");
        setToolTipText(name == null ? null : name + " · " + HighlightsModel.OBSERVED);
        return this;
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (cell == null || lines == null) return;
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
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics title = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int left = x + PAD, inner = h - 2 * PAD;
            Sprites.sprite(cell.portalId(), PORTAL).paintIcon(this, g, left, y + PAD + (inner - PORTAL) / 2);
            int textLeft = left + PORTAL + Tokens.S, right = x + w - PAD, text = title.getHeight() + 2 * caption.getHeight();
            int top = y + PAD + Math.max(0, (inner - text) / 2);
            text(g, lines.name(), titleFont, title, Tokens.color(Tokens.Role.TEXT), textLeft, top + title.getAscent(), right - textLeft);
            text(g, lines.bags(), captionFont, caption, Tokens.color(Tokens.Role.TEXT_MUTED), textLeft, top + title.getHeight() + caption.getAscent(), right - textLeft);
            text(g, lines.summary(), captionFont, caption, Tokens.color(Tokens.Role.TEXT), textLeft,
                top + title.getHeight() + caption.getHeight() + caption.getAscent(), right - textLeft);
        } finally {
            g.dispose();
        }
    }

    private static void text(Graphics2D g, String value, Font font, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value == null || value.isEmpty() || width <= 0) return;
        g.setFont(font);
        g.setColor(color);
        g.drawString(NotableDropRenderer.fit(value, metrics, width), x, baseline);
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
