package tomato.gui.quest;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.ItemTiers;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;

/**
 * Paints one quest card of the Board (spec §6.5, §9): one component reused for every cell of a group's TileList, no per-card
 * component tree. A pin star when pinned, the name, the badges (↻ Repeatable or One-time, ✓ Done) and the user's type label as a
 * chip (none when unlabeled, never guessed; none in a type-label section, whose header names it); "You get" or "Pick 1 of N" over at
 * most four 28 px reward sprites (a repeated reward shows its count) and "+N"; "Bring" over at most four 20 px requirement sprites,
 * each with "×count", and "+N". An unknown list says
 * "Rewards not captured" / "Requirements not captured" and a captured empty one "None listed by the server": unknown is never none
 * (spec §1). The cell is fixed so every list reserves the same four slots; the full lists are in the detail drawer. Sprites come from
 * the page's lookup (a missing or failing one paints the kit placeholder) in a well whose border is the item's tier
 * (ItemTiers.label, resolved at paint time). Sizes follow the body font; colors come from Tokens at paint time.
 */
final class QuestCardRenderer extends JComponent implements ListCellRenderer<QuestCardModel>, Accessible {
    /** Sprite sizes (the spec's large rewards and small requirements), slots per list, the well around a sprite, space between cards. */
    static final int REWARD = 28, REQUIREMENT = 20, SLOTS = 4, WELL = 6, GAP = 10;
    static final String NOT_LISTED = "None listed by the server";

    /** The page's sprite lookup (the kit Sprites in production, injected in tests); null or a failure paints the placeholder. */
    interface SpriteLookup { Icon sprite(int id, int size); }

    /** One painted slot: an item id and the count text beside it ("" for a single reward; requirements always "×n"). */
    record Slot(int id, String count) {}

    /** The text one card paints (painted text is not in the component tree, so tests read it here); lists hold at most four slots. */
    record Lines(String pin, String title, List<String> badges, String chip, String rewardsTitle, List<Slot> rewards, String rewardsMore,
                 String rewardsNote, String requirementsTitle, List<Slot> requirements, String requirementsMore, String requirementsNote) {}

    private final SpriteLookup sprites;
    /** Whether cards paint the type chip: not in a type-label section, whose header already names the type. */
    private final boolean chip;
    private QuestCardModel card;
    private Lines lines;
    private boolean selected, focused;

    QuestCardRenderer(SpriteLookup sprites) { this(sprites, true); }

    /** {@code chip} false leaves the type chip out (a type-label section); the accessible name still says the type. */
    QuestCardRenderer(SpriteLookup sprites, boolean chip) { this.sprites = sprites; this.chip = chip; setOpaque(false); }

    static Lines lines(QuestCardModel card) { return lines(card, true); }

    /** The card's painted text; {@code chip} false paints no type chip (an empty chip line). */
    static Lines lines(QuestCardModel card, boolean chip) {
        return new Lines(card.pinned() ? "★" : "", card.name(), card.badges(), chip ? card.typeLabel() : "", card.rewardsTitle(),
            slots(card.rewards(), false), more(card.rewards()), note(card.rewards(), card.rewardsKnown(), "Rewards not captured"),
            "Bring", slots(card.requirements(), true), more(card.requirements()),
            note(card.requirements(), card.requirementsKnown(), "Requirements not captured"));
    }

    /**
     * "Festival exchange, pinned, repeatable, done, Event; pick 1 of 3: Mighty Quest Chest, 2 Royal Epic Quest Chest and 1 more;
     * bring: 3 Festival Token": stable while time passes, and "not captured" rather than silence for unknown lists.
     */
    static String accessibleName(QuestCardModel card) {
        StringBuilder text = new StringBuilder(card.name());
        if (card.pinned()) text.append(", pinned");
        text.append(card.repeatable() ? ", repeatable" : ", one-time");
        if (card.completed()) text.append(", done");
        if (!card.typeLabel().isEmpty()) text.append(", ").append(card.typeLabel());
        text.append("; ");
        if (!card.rewardsKnown()) text.append("rewards not captured");
        else if (card.rewards().isEmpty()) text.append("no rewards listed");
        else text.append(card.rewardsTitle().toLowerCase(java.util.Locale.ROOT)).append(": ").append(spoken(card.rewards(), false));
        text.append("; ");
        if (!card.requirementsKnown()) text.append("requirements not captured");
        else if (card.requirements().isEmpty()) text.append("nothing listed to bring");
        else text.append("bring: ").append(spoken(card.requirements(), true));
        return text.toString();
    }

    Lines shown() { return lines; }

    /** The four reward wells and "+99": the widest the reward row gets. */
    int rewardRowWidth() {
        FontMetrics caption = getFontMetrics(Type.caption());
        return SLOTS * (REWARD + WELL) + (SLOTS - 1) * Tokens.XS + Tokens.S + caption.stringWidth("+99");
    }

    /** The four requirement wells with "×99" beside each and "+99": the row the cell reserves (a larger count ends the row early with "+N"). */
    int requirementRowWidth() {
        FontMetrics caption = getFontMetrics(Type.caption());
        return SLOTS * (REQUIREMENT + WELL + 2 + caption.stringWidth("×99")) + (SLOTS - 1) * Tokens.S + Tokens.S + caption.stringWidth("+99");
    }

    /** The list cell: the card plus half the gap between cards on each side, at the current body font, wide enough for both rows. */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption());
        int chip = caption.getHeight() + 2;
        int height = Tokens.M + Math.max(title.getHeight(), chip) + Tokens.XS + chip + Tokens.S
            + caption.getHeight() + Tokens.XS + REWARD + WELL + Tokens.S
            + caption.getHeight() + Tokens.XS + Math.max(REQUIREMENT + WELL, caption.getHeight()) + Tokens.M;
        int width = Math.max(Math.round(ContentStyle.body().getSize2D() * 19f),
            2 * Tokens.M + Math.max(rewardRowWidth(), requirementRowWidth()));
        return new Dimension(width + GAP, height + GAP);
    }

    /** The kit TileList sizes its fixed cells from the renderer's preferred size: the cell. */
    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends QuestCardModel> list, QuestCardModel value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        card = value;
        selected = isSelected;
        focused = cellHasFocus;
        lines = value == null ? null : lines(value, chip);
        String name = value == null ? null : accessibleName(value);
        getAccessibleContext().setAccessibleName(name);
        getAccessibleContext().setAccessibleDescription(value == null ? null : "Enter or double-click shows the quest's details");
        setToolTipText(name == null ? null : name + " · Enter or double-click for details");
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
            g.setStroke(new BasicStroke(1f));
            Color ink = Tokens.color(Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics titleMetrics = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int left = x + Tokens.M, top = y + Tokens.M, right = x + w - Tokens.M, chipHeight = caption.getHeight() + 2;
            // Title row: the pin star, the name, and the user's type label as a chip at the right.
            int rowHeight = Math.max(titleMetrics.getHeight(), chipHeight), textLeft = left, titleRight = right;
            if (!lines.chip().isEmpty()) { // at most half the card, so a long label never hides the name
                int max = (right - left) / 2, chipLeft = right - chipWidth(lines.chip(), caption, max);
                chip(g, lines.chip(), Tokens.Tone.ACCENT, chipLeft, top + (rowHeight - chipHeight) / 2, caption, max);
                titleRight = chipLeft - Tokens.S;
            }
            if (!lines.pin().isEmpty()) {
                int star = titleMetrics.getAscent() - 2;
                star(g, left, top + (rowHeight - star) / 2, star);
                textLeft += star + Tokens.XS;
            }
            text(g, lines.title(), titleFont, titleMetrics, ink, textLeft, top + (rowHeight - titleMetrics.getHeight()) / 2 + titleMetrics.getAscent(), titleRight - textLeft);
            // Badges: kind (INFO for repeatable, NEUTRAL for one-time) and done (GOOD), left to right.
            int row = top + rowHeight + Tokens.XS, chipLeft = left;
            for (String badge : lines.badges()) {
                String label = glyph(badge, captionFont);
                Tokens.Tone tone = QuestCardModel.DONE.equals(badge) ? Tokens.Tone.GOOD : QuestCardModel.REPEATABLE.equals(badge) ? Tokens.Tone.INFO : Tokens.Tone.NEUTRAL;
                chipLeft = chip(g, label, tone, chipLeft, row, caption, right - chipLeft) + Tokens.XS;
            }
            // You get / Pick 1 of N, then the reward wells or the note.
            row += chipHeight + Tokens.S;
            text(g, lines.rewardsTitle(), captionFont, caption, muted, left, row + caption.getAscent(), right - left);
            row += caption.getHeight() + Tokens.XS;
            int well = REWARD + WELL;
            if (!lines.rewardsNote().isEmpty()) {
                text(g, lines.rewardsNote(), captionFont, caption, muted, left, row + (well - caption.getHeight()) / 2 + caption.getAscent(), right - left);
            } else {
                int slotX = left;
                for (Slot slot : lines.rewards()) {
                    slot(this, g, icon(slot.id(), REWARD), ItemTiers.label(slot.id()), slotX, row, well);
                    if (!slot.count().isEmpty()) badge(g, slot.count(), caption, slotX + well - 3, row + well - 3);
                    slotX += well + Tokens.XS;
                }
                if (!lines.rewardsMore().isEmpty())
                    text(g, lines.rewardsMore(), captionFont, caption, muted, slotX - Tokens.XS + Tokens.S, row + (well - caption.getHeight()) / 2 + caption.getAscent(), right - slotX);
            }
            // Bring, then the requirement wells with their counts or the note.
            row += well + Tokens.S;
            text(g, lines.requirementsTitle(), captionFont, caption, muted, left, row + caption.getAscent(), right - left);
            row += caption.getHeight() + Tokens.XS;
            well = REQUIREMENT + WELL;
            int requirementRow = Math.max(well, caption.getHeight());
            int baseline = row + (requirementRow - caption.getHeight()) / 2 + caption.getAscent();
            if (!lines.requirementsNote().isEmpty()) {
                text(g, lines.requirementsNote(), captionFont, caption, muted, left, baseline, right - left);
            } else {
                // The cell reserves "×99" per slot; a larger count that would push a slot past the edge ends the row with "+N"
                // for every item not drawn, so the card never shows fewer items without saying so.
                int slotX = left, drawn = 0, more = lines.requirementsMore().isEmpty() ? 0 : Integer.parseInt(lines.requirementsMore().substring(1));
                for (Slot slot : lines.requirements()) {
                    int hidden = lines.requirements().size() - drawn - 1 + more, reserve = hidden > 0 ? Tokens.S + caption.stringWidth("+" + hidden) : 0;
                    if (drawn > 0 && slotX + well + 2 + caption.stringWidth(slot.count()) + reserve > right) break;
                    slot(this, g, icon(slot.id(), REQUIREMENT), ItemTiers.label(slot.id()), slotX, row + (requirementRow - well) / 2, well);
                    slotX += well + 2;
                    text(g, slot.count(), captionFont, caption, ink, slotX, baseline, right - slotX);
                    slotX += caption.stringWidth(slot.count()) + Tokens.S;
                    drawn++;
                }
                int hidden = lines.requirements().size() - drawn + more;
                if (hidden > 0) text(g, "+" + hidden, captionFont, caption, muted, slotX, baseline, right - slotX);
            }
        } finally {
            g.dispose();
        }
    }

    /** The lookup's sprite, or the kit placeholder when it has none or fails. */
    private Icon icon(int id, int size) {
        Icon icon;
        try { icon = sprites == null ? null : sprites.sprite(id, size); } catch (RuntimeException e) { icon = null; }
        return icon == null || icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0 ? Sprites.sprite(0, size) : icon;
    }

    /** A rounded well with the tier's border (subtle when the item has none) and the sprite centered, scaled down when larger. */
    static void slot(Component owner, Graphics2D g, Icon sprite, String tier, int x, int y, int side) {
        g.setColor(Tokens.color(Tokens.Role.SURFACE_ALT));
        g.fillRoundRect(x, y, side - 1, side - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        g.setColor(tier == null || tier.isEmpty() ? Tokens.color(Tokens.Role.BORDER_SUBTLE) : Tokens.tier(tier));
        g.drawRoundRect(x, y, side - 1, side - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        int room = side - WELL, width = sprite.getIconWidth(), height = sprite.getIconHeight();
        double scale = Math.min(1d, (double) room / Math.max(width, height));
        Graphics2D icon = (Graphics2D) g.create();
        try {
            icon.translate(x + (side - width * scale) / 2, y + (side - height * scale) / 2);
            icon.scale(scale, scale);
            sprite.paintIcon(owner, icon, 0, 0);
        } finally {
            icon.dispose();
        }
    }

    /** A sprite of {@code size} in a tier-bordered well, for the detail drawer's rows (the same drawing as the card's slots). */
    static Icon slotIcon(SpriteLookup sprites, int id, int size) {
        int side = size + WELL;
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Icon sprite;
                try { sprite = sprites == null ? null : sprites.sprite(id, size); } catch (RuntimeException e) { sprite = null; }
                if (sprite == null || sprite.getIconWidth() <= 0 || sprite.getIconHeight() <= 0) sprite = Sprites.sprite(0, size);
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    slot(c, g, sprite, ItemTiers.label(id), x, y, side);
                } finally {
                    g.dispose();
                }
            }
            @Override public int getIconWidth() { return side; }
            @Override public int getIconHeight() { return side; }
        };
    }

    /** The badge text without its leading symbol when the font cannot draw it ("↻ Repeatable" → "Repeatable"), as HomeViews.glyph. */
    static String glyph(String badge, Font font) {
        if (badge.length() > 2 && badge.charAt(1) == ' ' && !Character.isLetterOrDigit(badge.charAt(0)) && !font.canDisplay(badge.charAt(0)))
            return badge.substring(2);
        return badge;
    }

    private static List<Slot> slots(List<QuestCardModel.Item> items, boolean requirement) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < items.size() && i < SLOTS; i++) {
            QuestCardModel.Item item = items.get(i);
            slots.add(new Slot(item.id(), requirement || item.count() > 1 ? "×" + item.count() : ""));
        }
        return List.copyOf(slots);
    }

    private static String more(List<QuestCardModel.Item> items) { return items.size() > SLOTS ? "+" + (items.size() - SLOTS) : ""; }

    private static String note(List<QuestCardModel.Item> items, boolean known, String unknown) {
        return !known ? unknown : items.isEmpty() ? NOT_LISTED : "";
    }

    /** "10 Mark of Malus, Festival Token and 2 more": the first four items; a single reward is read without its count. */
    private static String spoken(List<QuestCardModel.Item> items, boolean requirement) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < items.size() && i < SLOTS; i++) {
            QuestCardModel.Item item = items.get(i);
            parts.add(requirement || item.count() > 1 ? item.count() + " " + item.name() : item.name());
        }
        return String.join(", ", parts) + (items.size() > SLOTS ? " and " + (items.size() - SLOTS) + " more" : "");
    }

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

    /** A chip's width for {@code label} within {@code max} (a long label is cut with "…"). */
    private static int chipWidth(String label, FontMetrics metrics, int max) {
        return Math.min(metrics.stringWidth(label), Math.max(0, max - 14)) + 14;
    }

    /** A tinted chip from {@code left}, at most {@code max} wide (its label cut with "…"); returns its right edge. */
    private static int chip(Graphics2D g, String label, Tokens.Tone tone, int left, int top, FontMetrics metrics, int max) {
        int width = chipWidth(label, metrics, max);
        if (width <= 14) return left;
        g.setColor(Tokens.tint(Tokens.tone(tone)));
        g.fillRoundRect(left, top, width, metrics.getHeight() + 2, Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.setFont(metrics.getFont());
        g.setColor(Tokens.tone(tone));
        g.drawString(fit(label, metrics, width - 14), left + 7, top + 1 + metrics.getAscent());
        return left + width;
    }

    /** A small count ("×2") inside a reward well's bottom-right corner, on a surface backing so it reads over the sprite. */
    private static void badge(Graphics2D g, String count, FontMetrics metrics, int right, int bottom) {
        int width = metrics.stringWidth(count) + 4, height = metrics.getAscent();
        g.setColor(Tokens.color(Tokens.Role.SURFACE));
        g.fillRoundRect(right - width, bottom - height, width, height, 4, 4);
        g.setFont(metrics.getFont());
        g.setColor(Tokens.color(Tokens.Role.TEXT));
        g.drawString(count, right - width + 2, bottom - 2);
    }

    /** A filled five-point star, {@code size} square, in the accent color: the painted pin mark (no font glyph needed). */
    private static void star(Graphics2D g, int x, int y, int size) {
        Path2D.Float path = new Path2D.Float();
        double outer = size / 2d, inner = outer * .45, cx = x + outer, cy = y + outer;
        for (int i = 0; i < 10; i++) {
            double radius = i % 2 == 0 ? outer : inner, angle = -Math.PI / 2 + i * Math.PI / 5;
            double px = cx + radius * Math.cos(angle), py = cy + radius * Math.sin(angle);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.closePath();
        g.setColor(Tokens.color(Tokens.Role.ACCENT));
        g.fill(path);
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
