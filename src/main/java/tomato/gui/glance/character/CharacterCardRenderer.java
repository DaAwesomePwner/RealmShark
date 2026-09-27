package tomato.gui.glance.character;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
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
 * Paints one character card (spec §6.2, §9): one component reused for every cell of a gallery list, no per-card component tree.
 * A 34 px skin sprite, class, level and fame, the name (and, in Analyst, the character ID), an 8-pip maxed meter ("—" with
 * outlined pips when unknown, never 0/8), a Seasonal chip, when the character last played and a "Playing now" marker; dead
 * characters are dimmed and say "Dead". Sizes follow the body font; colors come from Tokens at paint time.
 */
public final class CharacterCardRenderer extends JComponent implements ListCellRenderer<CharacterCardModel>, Accessible {
    static final int SPRITE = 34, PIPS = 8, PIP_GAP = 3, GAP = 10; // GAP: space between cards (spec §5.2)
    private CharacterCardModel card;
    private Lines lines;
    private boolean selected, focused, analyst;

    /** The text one card paints (painted text is not in the component tree, so tests read it here); {@code pips} -1 = unknown. */
    record Lines(String title, String meta, String identity, String maxed, int pips, String seen, String status, String chip) {}

    public CharacterCardRenderer() { setOpaque(false); }

    void setAnalyst(boolean value) { analyst = value; }

    static Lines lines(CharacterCardModel card, boolean analyst) {
        String name = card.name() == null ? "" : card.name();
        String identity = analyst ? (name.isEmpty() ? "" : name + " · ") + "#" + card.characterId() : name;
        Integer maxed = card.maxed();
        return new Lines(card.className(),
            "Level " + (card.level() == null ? DisplayFormat.UNAVAILABLE : card.level()) + " · Fame " + DisplayFormat.formatInteger(card.fame()),
            identity, maxed == null ? DisplayFormat.UNAVAILABLE : maxed + "/8", maxed == null ? -1 : maxed,
            seen(card),
            card.playingNow() ? "Playing now" : card.dead() ? "Dead" : "",
            Boolean.TRUE.equals(card.seasonal()) ? "Seasonal" : "");
    }

    Lines shown() { return lines; }

    /** "Played 2 h ago" from the last time in game, as the Last played sort; "Seen …" only for a character never played. */
    static String seen(CharacterCardModel card) {
        if (card.lastPlayed() > 0) return "Played " + KitFormat.relative(card.lastPlayed());
        return card.lastSeen() > 0 ? "Seen " + KitFormat.relative(card.lastSeen()) : "Last played unknown";
    }

    /** The list cell: the card plus half the gap between cards on each side, at the current body font. */
    public Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption());
        int height = Tokens.M + Math.max(SPRITE, title.getHeight() + 2 * caption.getHeight()) + Tokens.S
            + Math.max(pip(), caption.getHeight()) + Tokens.XS + caption.getHeight() + Tokens.M;
        int width = Math.max(200, Math.round(ContentStyle.body().getSize2D() * 18f));
        return new Dimension(width + GAP, height + GAP);
    }

    @Override public Component getListCellRendererComponent(JList<? extends CharacterCardModel> list, CharacterCardModel value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        card = value;
        selected = isSelected;
        focused = cellHasFocus;
        lines = value == null ? null : lines(value, analyst);
        // The name stays stable while time passes; "Played N ago" is the description.
        getAccessibleContext().setAccessibleName(value == null ? null : value.accessibleName());
        getAccessibleContext().setAccessibleDescription(value == null ? null : lines.seen() + (lines.status().isEmpty() ? "" : " · " + lines.status()));
        setToolTipText(value == null ? null : tooltip(value));
        return this;
    }

    private static String tooltip(CharacterCardModel card) {
        return card.className() + " #" + card.characterId() + " · Level " + (card.level() == null ? DisplayFormat.UNAVAILABLE : card.level())
            + " · Fame " + DisplayFormat.formatInteger(card.fame()) + " · "
            + (card.maxed() == null ? "Maxed stats unknown (needs all 8 base stats and class caps)" : card.maxed() + " of 8 stats maxed")
            + " · Season " + (card.seasonal() == null ? "unknown" : card.seasonal() ? "seasonal" : "regular") + " · "
            + (card.lastPlayed() > 0 ? "Last played " + DisplayFormat.formatTimestamp(card.lastPlayed())
                : card.lastSeen() > 0 ? "Last seen " + DisplayFormat.formatTimestamp(card.lastSeen()) : "Last played unknown");
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
            Color ink = Tokens.color(card.dead() ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            int left = x + Tokens.M, top = y + Tokens.M, right = x + w - Tokens.M;
            Graphics2D sprite = (Graphics2D) g.create();
            if (card.dead()) sprite.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f));
            Sprites.sprite(card.skin() != null && card.skin() > 0 ? card.skin() : card.classId(), SPRITE).paintIcon(this, sprite, left, top);
            sprite.dispose();
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics titleMetrics = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int textLeft = left + SPRITE + Tokens.S, titleRight = right;
            if (!lines.chip().isEmpty()) titleRight = chip(g, lines.chip(), Tokens.Tone.INFO, right, top, caption) - Tokens.XS;
            int baseline = top + titleMetrics.getAscent();
            text(g, lines.title(), titleFont, titleMetrics, ink, textLeft, baseline, titleRight - textLeft);
            baseline += titleMetrics.getDescent() + caption.getAscent();
            text(g, lines.meta(), captionFont, caption, ink, textLeft, baseline, right - textLeft);
            text(g, lines.identity(), captionFont, caption, muted, textLeft, baseline + caption.getHeight(), right - textLeft);
            int row = top + Math.max(SPRITE, titleMetrics.getHeight() + 2 * caption.getHeight()) + Tokens.S;
            int pip = pip(), rowHeight = Math.max(pip, caption.getHeight()), pipY = row + (rowHeight - pip) / 2;
            for (int i = 0; i < PIPS; i++) {
                int px = left + i * (pip + PIP_GAP);
                if (lines.pips() < 0) {
                    g.setStroke(new BasicStroke(1f));
                    g.setColor(Tokens.color(Tokens.Role.BORDER));
                    g.drawRoundRect(px, pipY, pip - 1, pip - 1, 3, 3);
                } else {
                    g.setColor(Tokens.color(i >= lines.pips() ? Tokens.Role.CONTROL : lines.pips() >= PIPS ? Tokens.Role.GOOD : Tokens.Role.ACCENT));
                    g.fillRoundRect(px, pipY, pip, pip, 3, 3);
                }
            }
            int after = left + PIPS * (pip + PIP_GAP) + Tokens.XS;
            text(g, lines.maxed(), captionFont, caption, lines.pips() < 0 ? muted : ink, after,
                row + (rowHeight - caption.getHeight()) / 2 + caption.getAscent(), right - after);
            int bottom = row + rowHeight + Tokens.XS + caption.getAscent(), statusLeft = right;
            if (!lines.status().isEmpty()) {
                statusLeft = right - caption.stringWidth(lines.status());
                g.setFont(captionFont);
                g.setColor(Tokens.tone(card.playingNow() ? Tokens.Tone.GOOD : Tokens.Tone.BAD));
                g.drawString(lines.status(), statusLeft, bottom);
                if (card.playingNow()) {
                    int dot = Math.max(6, caption.getAscent() / 2);
                    statusLeft -= dot + Tokens.XS;
                    g.fillOval(statusLeft, bottom - caption.getAscent() / 2 - dot / 2, dot, dot);
                }
            }
            text(g, lines.seen(), captionFont, caption, muted, left, bottom, statusLeft - Tokens.S - left);
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
        g.drawString(fit(value, metrics, width), x, baseline);
    }

    /** The text, or its longest prefix plus "…" that fits the width. */
    static String fit(String value, FontMetrics metrics, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + "…") > width) end--;
        return end == 0 ? "" : value.substring(0, end) + "…";
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
