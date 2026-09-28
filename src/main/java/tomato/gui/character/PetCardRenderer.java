package tomato.gui.character;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.backend.data.PetDefinitions;
import tomato.gui.glance.character.PetSummary;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Paints one pet card of the Pets gallery (spec §6.2, §9): one component reused for every cell of the kit TileList. A 34 px skin
 * sprite, the pet's name, a rarity chip (none when the rarity is unknown), its family, three thin ability bars (level of the
 * pet's max level; an outlined "—" track when either is unknown, never an empty 0 bar; "Locked" below the slot's unlock level)
 * and two footer lines: "In the Pet Yard now" while it is, then "Equipped by Wizard #7" while a character carries it (a line too
 * long for the card is ellipsized; the tooltip and the accessible name keep it whole). Every card has room for both lines, so all
 * cells are one height. Sizes follow the body font; colors come from Tokens at paint time.
 */
final class PetCardRenderer extends JComponent implements ListCellRenderer<PetGalleryModel.PetCard>, Accessible {
    static final int SPRITE = 34, GAP = 10, BAR = 6; // GAP: space between cards (spec §5.2); BAR: the ability track's thickness
    private static final String UNKNOWN = DisplayFormat.UNAVAILABLE;
    private PetGalleryModel.PetCard card;
    private Lines lines;
    private boolean selected, focused;
    private PetDefinitions definitions = PetDefinitions.loading();

    /** One ability row: {@code value} "45/70", "45/—", "—" or "Locked"; {@code level} and {@code max} null when unknown. */
    record Bar(String name, String value, Integer level, Integer max, boolean locked) {}
    /**
     * The text one card paints (painted text is not in the component tree, so tests read it here); {@code chip} "" when none;
     * {@code footer} the footer lines that apply, in order (none, one or two).
     */
    record Lines(String title, String chip, String family, List<Bar> bars, List<String> footer) {
        Lines { bars = List.copyOf(bars); footer = List.copyOf(footer); }
    }

    PetCardRenderer() { setOpaque(false); }

    /** The definitions the shown cards were built with; the tooltip names their status when a family is unknown. EDT. */
    void setDefinitions(PetDefinitions value) { definitions = Objects.requireNonNull(value, "value"); }

    static Lines lines(PetGalleryModel.PetCard card) {
        PetSummary pet = card.pet();
        List<Bar> bars = new ArrayList<>(3);
        for (PetSummary.Ability ability : pet.abilities()) {
            Integer level = ability.level(), max = pet.maxLevel();
            String value = ability.locked() ? "Locked" : level == null ? UNKNOWN : level + "/" + (max == null ? UNKNOWN : max);
            // The card's narrow name column paints the short name ("Ability #403"; "Ability —" when the type is not captured);
            // the accessible name and the feeding calculator use the panel's full wording.
            bars.add(new Bar(ability.type() == null ? "Ability " + UNKNOWN : ability.name(), value, level, max, ability.locked()));
        }
        return new Lines(pet.title(), pet.rarity() == null ? "" : pet.rarity(), "Family: " + (pet.family() == null ? UNKNOWN : pet.family()),
            bars, footerLines(card));
    }

    /** The footer lines: "In the Pet Yard now" while it is, then "Equipped by Wizard #7, Knight #8 (dead)" while carried. */
    static List<String> footerLines(PetGalleryModel.PetCard card) {
        List<String> parts = new ArrayList<>(2);
        if (card.inYard()) parts.add("In the Pet Yard now");
        if (!card.equippedBy().isEmpty()) parts.add("Equipped by " + String.join(", ", card.equippedBy()));
        return parts;
    }

    /** The footer lines joined by " · " (the tooltip's wording), "" when none. */
    static String footer(PetGalleryModel.PetCard card) { return String.join(" · ", footerLines(card)); }

    /** What a screen reader announces for one card: every painted value, unknowns said as unknown. */
    static String accessibleName(PetGalleryModel.PetCard card) {
        PetSummary pet = card.pet();
        List<String> parts = new ArrayList<>();
        parts.add(pet.title());
        parts.add(pet.rarity() == null ? "rarity unknown" : pet.rarity());
        parts.add("family " + (pet.family() == null ? "unknown" : pet.family()));
        for (PetSummary.Ability ability : pet.abilities()) {
            String name = CharacterPetsGUI.abilityName(ability.type());
            parts.add(ability.locked() ? name + " locked" : ability.level() == null ? name + " level unknown"
                : name + " level " + ability.level() + (pet.maxLevel() == null ? "" : " of " + pet.maxLevel()));
        }
        if (card.inYard()) parts.add("in the Pet Yard now");
        if (!card.equippedBy().isEmpty()) parts.add("equipped by " + String.join(", ", card.equippedBy()));
        return String.join(", ", parts);
    }

    Lines shown() { return lines; }

    /** The baseline of footer line {@code line} (0 or 1) in the cell, at the current body font (paint uses the same rule). */
    int footerBaseline(int line) { return footerBaseline(getFontMetrics(Type.emphasis()), getFontMetrics(Type.caption()), line); }

    /** Below the sprite or the title and family, the three ability rows, then one caption line per footer line. */
    private static int footerBaseline(FontMetrics title, FontMetrics caption, int line) {
        int rowsTop = GAP / 2 + Tokens.M + Math.max(SPRITE, title.getHeight() + caption.getHeight()) + Tokens.S;
        return rowsTop + 3 * row(caption) + 2 * Tokens.XS + Tokens.S + line * caption.getHeight() + caption.getAscent();
    }

    /** The list cell: the card plus half the gap between cards on each side, at the current body font; room for both footer lines. */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption());
        int height = Tokens.M + Math.max(SPRITE, title.getHeight() + caption.getHeight()) + Tokens.S
            + 3 * row(caption) + 2 * Tokens.XS + Tokens.S + 2 * caption.getHeight() + Tokens.M;
        int width = Math.max(220, Math.round(ContentStyle.body().getSize2D() * 19f));
        return new Dimension(width + GAP, height + GAP);
    }

    /** The kit TileList sizes its fixed cells from the renderer's preferred size: the cell. */
    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends PetGalleryModel.PetCard> list, PetGalleryModel.PetCard value,
                                                            int index, boolean isSelected, boolean cellHasFocus) {
        card = value;
        selected = isSelected;
        focused = cellHasFocus;
        lines = value == null ? null : lines(value);
        getAccessibleContext().setAccessibleName(value == null ? null : accessibleName(value));
        getAccessibleContext().setAccessibleDescription(value == null ? null : observed(value));
        setToolTipText(value == null ? null : tooltip(value));
        return this;
    }

    private static String observed(PetGalleryModel.PetCard card) {
        String source = card.pet().source();
        return "Observed " + (card.seenAt() > 0 ? DisplayFormat.formatTimestamp(card.seenAt()) : "at an unknown time")
            + (source == null ? "" : " · " + source);
    }

    private String tooltip(PetGalleryModel.PetCard card) {
        PetSummary pet = card.pet();
        String instance = pet.instanceId() != null ? "Instance " + pet.instanceId()
            : "Instance not captured (object " + card.key().substring(card.key().indexOf(':') + 1) + ")";
        String family = pet.family() != null ? pet.family() : UNKNOWN + " (" + (pet.type() == null ? "pet type not captured"
            : !definitions.available ? definitions.status : "not named in the selected game assets") + ")";
        String footer = footer(card);
        return pet.title() + " · " + instance + " · " + (pet.rarity() == null ? "Rarity " + UNKNOWN : pet.rarity()) + " · Family: " + family
            + " · Max level " + (pet.maxLevel() == null ? UNKNOWN : pet.maxLevel()) + " · " + observed(card) + (footer.isEmpty() ? "" : " · " + footer);
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
            Color ink = Tokens.color(Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            int left = x + Tokens.M, top = y + Tokens.M, right = x + w - Tokens.M;
            Integer skin = card.pet().skin();
            Sprites.sprite(skin == null ? 0 : skin, SPRITE).paintIcon(this, g, left, top); // unknown skin: the placeholder
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics titleMetrics = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int textLeft = left + SPRITE + Tokens.S, titleRight = right;
            if (!lines.chip().isEmpty()) titleRight = chip(g, lines.chip(), right, top, caption) - Tokens.XS;
            int baseline = top + titleMetrics.getAscent();
            text(g, lines.title(), titleFont, titleMetrics, ink, textLeft, baseline, titleRight - textLeft);
            baseline += titleMetrics.getDescent() + caption.getAscent();
            text(g, lines.family(), captionFont, caption, card.pet().family() == null ? muted : ink, textLeft, baseline, right - textLeft);
            int rowHeight = row(caption), rowTop = top + Math.max(SPRITE, titleMetrics.getHeight() + caption.getHeight()) + Tokens.S;
            int nameWidth = Math.round((right - left) * .42f), valueWidth = caption.stringWidth("100/100");
            for (Bar bar : lines.bars()) {
                int textBaseline = rowTop + (rowHeight - caption.getHeight()) / 2 + caption.getAscent();
                text(g, bar.name(), captionFont, caption, ink, left, textBaseline, nameWidth);
                int trackLeft = left + nameWidth + Tokens.S, trackRight = right - valueWidth - Tokens.S, trackY = rowTop + (rowHeight - BAR) / 2;
                track(g, bar, trackLeft, trackY, Math.max(0, trackRight - trackLeft));
                boolean known = !bar.locked() && bar.level() != null && bar.max() != null;
                g.setFont(captionFont);
                g.setColor(known ? ink : muted);
                String value = fit(bar.value(), caption, valueWidth);
                g.drawString(value, right - caption.stringWidth(value), textBaseline);
                rowTop += rowHeight + Tokens.XS;
            }
            List<String> footer = lines.footer();
            for (int i = 0; i < footer.size(); i++) {
                int footerBaseline = footerBaseline(titleMetrics, caption, i), footerLeft = left; // from the cell's top, as cellSize counts
                if (i == 0 && card.inYard()) { // "In the Pet Yard now" leads with a dot
                    int dot = Math.max(6, caption.getAscent() / 2);
                    g.setColor(Tokens.tone(Tokens.Tone.GOOD));
                    g.fillOval(left, footerBaseline - caption.getAscent() / 2 - dot / 2, dot, dot);
                    footerLeft += dot + Tokens.XS;
                }
                text(g, footer.get(i), captionFont, caption, muted, footerLeft, footerBaseline, right - footerLeft); // ellipsized
            }
        } finally {
            g.dispose();
        }
    }

    /** One ability track: filled to level/max; outlined and dashed when either is unknown; an empty control track when locked. */
    private static void track(Graphics2D g, Bar bar, int x, int y, int width) {
        if (width <= 0) return;
        if (bar.locked()) {
            g.setColor(Tokens.color(Tokens.Role.CONTROL));
            g.fillRoundRect(x, y, width, BAR, BAR, BAR);
            return;
        }
        if (bar.level() == null || bar.max() == null || bar.max() <= 0) {
            Graphics2D dashed = (Graphics2D) g.create();
            dashed.setColor(Tokens.color(Tokens.Role.BORDER));
            dashed.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
            dashed.drawRoundRect(x, y, width - 1, BAR - 1, BAR, BAR);
            dashed.dispose();
            return;
        }
        g.setColor(Tokens.color(Tokens.Role.CONTROL));
        g.fillRoundRect(x, y, width, BAR, BAR, BAR);
        int filled = Math.round(width * Math.max(0f, Math.min(1f, bar.level() / (float) bar.max())));
        if (filled <= 0) return;
        g.setColor(Tokens.color(bar.level() >= bar.max() ? Tokens.Role.GOOD : Tokens.Role.ACCENT));
        g.fillRoundRect(x, y, filled, BAR, BAR, BAR);
    }

    /** An ability row: one caption line, at least the track's thickness. */
    private static int row(FontMetrics caption) { return Math.max(BAR, caption.getHeight()); }

    private static void text(Graphics2D g, String value, Font font, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value.isEmpty() || width <= 0) return;
        g.setFont(font);
        g.setColor(color);
        g.drawString(fit(value, metrics, width), x, baseline);
    }

    /** A neutral chip right-aligned at {@code right}; returns its left edge. Rarity is a fact, not a status: no status tone. */
    private static int chip(Graphics2D g, String label, int right, int top, FontMetrics metrics) {
        int width = metrics.stringWidth(label) + 14, left = right - width;
        Color ink = Tokens.tone(Tokens.Tone.NEUTRAL);
        g.setColor(Tokens.tint(ink));
        g.fillRoundRect(left, top, width, metrics.getHeight() + 2, Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.setFont(metrics.getFont());
        g.setColor(ink);
        g.drawString(label, left + 7, top + 1 + metrics.getAscent());
        return left;
    }

    /** The text, or its longest prefix plus "…" that fits the width (as the character card fits its text). */
    private static String fit(String value, FontMetrics metrics, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + "…") > width) end--;
        return end == 0 ? "" : value.substring(0, end) + "…";
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
