package tomato.gui.quest;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.SegmentBar;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Paints one plan card of the quest Planner (spec §6.5, §9): one component reused for every cell of the kit TileList, no per-card
 * component tree. The plan's name with its repeats chip, its status and readiness (QuestPlanning's own texts), then up to
 * {@link #ROWS} requirement rows: a 20 px sprite, the item name and its need, and below them a SegmentBar of reserved / covered /
 * missing with "missing N" or "covered", or "Stock unconfirmed" and no bar when no manual held entry exists (an empty track would
 * read as 0%). More items end in "+N more"; unknown requirements say "Requirements not captured", never an empty list. The tooltip
 * lists every row's numbers. The card is fixed-size (TileList cells): sizes follow the body font, colors come from Tokens at paint
 * time.
 */
final class PlanCardRenderer extends JComponent implements ListCellRenderer<PlanCardModel>, Accessible {
    static final int SPRITE = 20, ROWS = 4, BAR = 6, GAP = 10; // GAP: space between cards (spec §5.2)
    private PlanCardModel card;
    private Lines lines;
    private boolean selected, focused;

    /** One painted requirement row: {@code state} is "missing N", "covered" or "Stock unconfirmed"; {@code bar} whether a bar draws. */
    record RowLine(int itemId, String name, String need, String state, boolean bar, long reserved, Long covered, Long missing, long total) {}

    /** The text one card paints (painted text is not in the component tree, so tests read it here). */
    record Lines(String title, String repeats, String status, String readiness, List<RowLine> rows, String note) {}

    PlanCardRenderer() { setOpaque(false); }

    static Lines lines(PlanCardModel card) {
        List<RowLine> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(ROWS, card.rows().size()); i++) {
            PlanCardModel.Row row = card.rows().get(i);
            String state = !row.stockKnown() ? PlanCardModel.UNCONFIRMED : row.missing() > 0 ? "missing " + DisplayFormat.formatInteger(row.missing().longValue()) : "covered";
            rows.add(new RowLine(row.itemId(), row.name(), "need " + DisplayFormat.formatInteger(row.need()), state, row.stockKnown(),
                row.reserved(), row.covered(), row.missing(), row.need()));
        }
        int more = card.rows().size() - rows.size();
        String note = !card.requirementsKnown() ? "Requirements not captured" : card.rows().isEmpty() ? "No items required (observed empty)"
            : more > 0 ? "+" + more + " more" : "";
        return new Lines(card.name(), card.repeats(), card.status(), card.readiness(), List.copyOf(rows), note);
    }

    /** "Royal tribute: Saved requirements; verify server; More items needed": stable while the card is shown. */
    static String accessibleName(PlanCardModel card) { return card.name() + ": " + card.status() + "; " + card.readiness(); }

    /** Every requirement's numbers, including those beyond the painted rows. */
    static String details(PlanCardModel card) {
        StringBuilder text = new StringBuilder(card.repeats());
        if (!card.requirementsKnown()) text.append(" · Requirements not captured");
        for (PlanCardModel.Row row : card.rows()) text.append(" · ").append(row.label()).append(": ").append(row.numbers());
        return text.append(" · Available stock is manually confirmed held stock only").toString();
    }

    /**
     * The list cell: the card plus half the gap between cards on each side, at the current body font. Its height holds the header
     * lines, {@link #ROWS} requirement rows and the note line, whatever the plan, so every cell of the list is the same.
     */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), body = getFontMetrics(Type.body()), caption = getFontMetrics(Type.caption());
        int height = Tokens.M + title.getHeight() + Tokens.XS + 2 * caption.getHeight() + Tokens.S
            + ROWS * row(body, caption) + (ROWS - 1) * Tokens.XS + Tokens.XS + caption.getHeight() + Tokens.M;
        int width = Math.max(260, Math.round(ContentStyle.body().getSize2D() * 20f));
        return new Dimension(width + GAP, height + GAP);
    }

    /** A requirement row: the name line and the bar line beside a sprite. */
    private static int row(FontMetrics body, FontMetrics caption) { return Math.max(SPRITE, body.getHeight() + caption.getHeight()); }

    /** The kit TileList sizes its fixed cells from the renderer's preferred size: the cell. */
    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends PlanCardModel> list, PlanCardModel value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        card = value;
        selected = isSelected;
        focused = cellHasFocus;
        lines = value == null ? null : lines(value);
        getAccessibleContext().setAccessibleName(value == null ? null : accessibleName(value));
        getAccessibleContext().setAccessibleDescription(value == null ? null : details(value));
        setToolTipText(value == null ? null : value.name() + " · " + value.readiness() + " · " + details(value));
        return this;
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (card == null) return;
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
            Font titleFont = Type.emphasis(), bodyFont = Type.body(), captionFont = Type.caption();
            FontMetrics title = g.getFontMetrics(titleFont), body = g.getFontMetrics(bodyFont), caption = g.getFontMetrics(captionFont);
            Color ink = Tokens.color(Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            int left = x + Tokens.M, top = y + Tokens.M, right = x + w - Tokens.M;
            // Name, with the repeats chip at the right edge.
            int chipLeft = chip(g, lines.repeats(), Tokens.Tone.NEUTRAL, right, top + (title.getHeight() - caption.getHeight() - 2) / 2, caption);
            text(g, lines.title(), titleFont, title, ink, left, top + title.getAscent(), chipLeft - Tokens.S - left);
            // Status and readiness: warn while the plan cannot be acted on or needs more items, good once covered.
            int line = top + title.getHeight() + Tokens.XS;
            text(g, lines.status(), captionFont, caption, card.actionable() ? muted : Tokens.tone(Tokens.Tone.WARN), left, line + caption.getAscent(), right - left);
            line += caption.getHeight();
            text(g, lines.readiness(), captionFont, caption, readiness(card), left, line + caption.getAscent(), right - left);
            line += caption.getHeight() + Tokens.S;
            int rowHeight = row(body, caption);
            for (RowLine row : lines.rows()) {
                Sprites.sprite(row.itemId(), SPRITE).paintIcon(this, g, left, line + (rowHeight - SPRITE) / 2);
                int textLeft = left + SPRITE + Tokens.S, needWidth = caption.stringWidth(row.need());
                int nameBaseline = line + body.getAscent();
                text(g, row.need(), captionFont, caption, muted, Math.max(textLeft, right - needWidth), nameBaseline, right - textLeft);
                text(g, row.name(), bodyFont, body, ink, textLeft, nameBaseline, right - needWidth - Tokens.S - textLeft);
                int barLine = line + body.getHeight(), stateBaseline = barLine + caption.getAscent();
                if (row.bar()) {
                    int stateWidth = caption.stringWidth(row.state()), barRight = right - stateWidth - Tokens.S;
                    SegmentBar.paint(g, textLeft, barLine + (caption.getHeight() - BAR) / 2, barRight - textLeft, BAR,
                        row.reserved(), row.covered(), row.missing(), row.total());
                    text(g, row.state(), captionFont, caption, Tokens.tone(row.missing() > 0 ? Tokens.Tone.WARN : Tokens.Tone.GOOD),
                        Math.max(textLeft, right - stateWidth), stateBaseline, right - textLeft);
                } else {
                    text(g, row.state(), captionFont, caption, muted, textLeft, stateBaseline, right - textLeft); // no bar: unknown is not 0%
                }
                line += rowHeight + Tokens.XS;
            }
            text(g, lines.note(), captionFont, caption, muted, left, line + caption.getAscent(), right - left);
        } finally {
            g.dispose();
        }
    }

    /** Readiness color: WARN when unavailable or short, muted while unknown, GOOD when manual stock covers every requirement. */
    private static Color readiness(PlanCardModel card) {
        if (!card.actionable() || "More items needed".equals(card.readiness())) return Tokens.tone(Tokens.Tone.WARN);
        return Tokens.tone(card.readiness().startsWith("Unknown") ? Tokens.Tone.NEUTRAL : Tokens.Tone.GOOD);
    }

    /** The text, or its longest prefix plus "…" that fits the width. */
    static String fit(String value, FontMetrics metrics, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + "…") > width) end--;
        return end == 0 ? "" : value.substring(0, end) + "…";
    }

    private static void text(Graphics2D g, String value, Font font, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value.isEmpty() || width <= 0) return;
        g.setFont(font);
        g.setColor(color);
        g.drawString(fit(value, metrics, width), x, baseline);
    }

    /** A tinted chip right-aligned at {@code right}; returns its left edge. */
    private static int chip(Graphics2D g, String label, Tokens.Tone tone, int right, int top, FontMetrics metrics) {
        int width = metrics.stringWidth(label) + 14, left = right - width;
        g.setColor(Tokens.tint(Tokens.tone(tone)));
        g.fillRoundRect(left, top, width, metrics.getHeight() + 2, Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.setFont(metrics.getFont());
        g.setColor(Tokens.tone(tone));
        g.drawString(label, left + 7, top + 1 + metrics.getAscent());
        return left;
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
