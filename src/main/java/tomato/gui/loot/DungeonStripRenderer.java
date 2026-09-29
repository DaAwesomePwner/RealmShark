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
 * placeholder when unknown), the area's name ("Unknown area" for bags without a known map), and under it its bag count and
 * "1 UT · 2 potions" as one " · " sequence ({@link #caption}) wrapped at its " · " boundaries across the two caption lines
 * (P6b: "1 UT · 1 ST · 3 p…" was cut when the summary had a line of its own). The bag count is muted, the rest in the text color.
 * One component reused for every cell; fixed at 13 em of the body font; colors from Tokens at paint time, and the light theme
 * outlines the card ({@link Tokens#outline}).
 */
public final class DungeonStripRenderer extends JComponent implements ListCellRenderer<HighlightsModel.DungeonCell>, Accessible {
    static final int PORTAL = 32, GAP = 8, PAD = Tokens.S;
    private static final String SEPARATOR = LootHighlights.SubLine.SEPARATOR;

    /** The text one cell paints. */
    record Lines(String name, String bags, String summary) {}

    private HighlightsModel.DungeonCell cell;
    private Lines lines;
    private boolean selected, focused;
    private List<String> painted = List.of();

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

    /** The caption the two lines under the name paint: "3 bags · 1 UT · 2 potions". */
    static String caption(HighlightsModel.DungeonCell cell) {
        Lines lines = lines(cell);
        return lines.bags() + SEPARATOR + lines.summary();
    }

    /**
     * {@code caption} on at most two lines of {@code width}: as many " · " parts a line as fit ({@link LootHighlights.SubLine#lines},
     * which breaks a part at its spaces only when it alone is too wide); the second line takes the rest, cut with "…" only when it
     * still does not fit (the tooltip says it whole).
     */
    static List<String> captionLines(String caption, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>(2);
        if (caption == null || caption.isEmpty() || width <= 0) return lines;
        List<String> wrapped = LootHighlights.SubLine.lines(caption, metrics, width);
        String first = wrapped.get(0);
        lines.add(NotableDropRenderer.fit(first, metrics, width));   // cut only when one word is wider than the line
        if (wrapped.size() == 2) lines.add(NotableDropRenderer.fit(wrapped.get(1), metrics, width));
        else if (wrapped.size() > 2) {   // every line after the first is a prefix of the rest: join them back and cut once
            String rest = caption.substring(first.length());
            lines.add(NotableDropRenderer.fit(rest.startsWith(SEPARATOR) ? rest.substring(SEPARATOR.length()) : rest.trim(), metrics, width));
        }
        return lines;
    }

    Lines shown() { return lines; }

    /** The strings the last paint drew, in order, after fitting (painted text is not in the component tree, so tests read it here). */
    List<String> painted() { return List.copyOf(painted); }

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
        painted = List.of();
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
            NotableDropRenderer.edge(g, shape, selected, focused);
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics title = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int left = x + PAD, inner = h - 2 * PAD;
            Sprites.sprite(cell.portalId(), PORTAL).paintIcon(this, g, left, y + PAD + (inner - PORTAL) / 2);
            int textLeft = left + PORTAL + Tokens.S, right = x + w - PAD, width = right - textLeft, text = title.getHeight() + 2 * caption.getHeight();
            int top = y + PAD + Math.max(0, (inner - text) / 2);
            List<String> drawn = new ArrayList<>(3);
            Color ink = Tokens.color(Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            if (width > 0) {
                String name = NotableDropRenderer.fit(lines.name(), title, width);
                g.setFont(titleFont);
                g.setColor(ink);
                g.drawString(name, textLeft, top + title.getAscent());
                drawn.add(name);
                g.setFont(captionFont);
                int baseline = top + title.getHeight() + caption.getAscent();
                for (String line : captionLines(caption(cell), caption, width)) {
                    // The bag count (and the separator after it) muted, as its own line was before; the summary in the text color.
                    String lead = line.equals(lines.bags()) ? line : line.startsWith(lines.bags() + SEPARATOR) ? lines.bags() + SEPARATOR : "";
                    g.setColor(muted);
                    if (!lead.isEmpty()) g.drawString(lead, textLeft, baseline);
                    g.setColor(ink);
                    if (lead.length() < line.length()) g.drawString(line.substring(lead.length()), textLeft + caption.stringWidth(lead), baseline);
                    drawn.add(line);
                    baseline += caption.getHeight();
                }
            }
            painted = drawn;
        } finally {
            g.dispose();
        }
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
