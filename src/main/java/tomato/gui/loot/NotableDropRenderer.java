package tomato.gui.loot;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.EnchantPips;
import tomato.gui.kit.ItemTiers;
import tomato.gui.kit.EnchantTooltip;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootFacts;
import tomato.realmshark.EnchantInfo;

/**
 * Paints one notable drop of Loot › Highlights (spec §6.4, §9, §10): one component reused for every cell of the grid's TileList,
 * no per-drop component tree. The item's sprite sits in a well tinted and outlined in its bag's color ({@link Sprites#paintWell},
 * shared with the run cards; muted when no bag name was saved); beside it: the item's name on up to two lines (wrapped at its
 * spaces; only a name longer than two lines is cut with "…"; every card keeps room for both, P6b); the type chip (UT, ST, Potion,
 * tier or Gear) and optional rarity chip. Enchanted sprites have a rarity glow and one bottom-right pip per unlocked slot.
 * The next line starts with the time ("14:32" today, "Yesterday 22:10", else the date), then " · " and the area ("Unknown area"
 * when none was recorded, {@link LootFacts#areaLabel}); only the end of this combined line is cut when it exceeds the card.
 * Finally, "Not linked to a run" appears when the drop recorded no exact run (the line stays empty otherwise, so every card is
 * the same height). Every fact
 * is in the accessible name in words and, in full, in the tooltip. The cell is fixed at 17 em of the body font (five columns at
 * 1240×800 font 13, two at 680 px and font 18); colors come from Tokens at paint time, so both themes and every font work, and
 * the light theme outlines the card ({@link Tokens#outline}).
 */
public final class NotableDropRenderer extends JComponent implements ListCellRenderer<HighlightsModel.Notable>, Accessible {
    /** The well, its sprite, the space between cells, the card's inner padding and the space between the chip row and the area. */
    static final int WELL_SIDE = 40, SPRITE = WELL_SIDE - Sprites.WELL - 2, GAP = 8, PAD = Tokens.S, LINE = 2;
    /** The item's name wraps to this many lines (P6b: a long name read "Synthetic Crystal M…" on one). */
    static final int NAME_LINES = 2;
    static final String NOT_LINKED = "Not linked to a run";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH),
        DATE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH), DATE_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    /** The cell's text fields; when and where paint together as one line, separated by " · ". */
    record Lines(String name, String chip, Tokens.Tone tone, String when, String where, String link) {}

    private final ZoneId zone;
    private final LongSupplier now;
    private final String opens, opensFully;
    private HighlightsModel.Notable drop;
    private Lines lines;
    private long renderedAt;
    private boolean selected, focused;
    private List<String> painted = List.of();

    /** Times are written in {@code zone} against {@code now}'s day there (the view passes its model's read time). */
    public NotableDropRenderer(ZoneId zone, LongSupplier now) {
        this(zone, now, "Enter opens the run recap", "Enter, double-click or the context menu opens the run recap");
    }

    /** As above, with the action wording of the view that hosts these drop cards. */
    public NotableDropRenderer(ZoneId zone, LongSupplier now, String opens, String opensFully) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.now = Objects.requireNonNull(now, "now");
        this.opens = Objects.requireNonNull(opens, "opens");
        this.opensFully = Objects.requireNonNull(opensFully, "opensFully");
        setOpaque(false);
    }

    static Lines lines(HighlightsModel.Notable drop, ZoneId zone, long now) {
        String area = LootFacts.areaLabel(drop.dungeon());
        String type = itemType(drop);
        return new Lines(Sprites.name(drop.itemId()), type, tone(type), time(drop.time(), zone, now), area,
            drop.visit() == null ? NOT_LINKED : "");
    }

    /** The item's type is independent of the reason it was notable; missing tier definitions never turn enchantment into a type. */
    static String itemType(HighlightsModel.Notable drop) {
        if (drop.kind() == HighlightsModel.Kind.POTION) return "Potion";
        String tier = tier(drop);
        return !tier.isEmpty() ? tier : drop.kind() == null ? "Item" : drop.kind() == HighlightsModel.Kind.ENCHANTED ? "Gear" : drop.kind().label();
    }

    /** The tier saved with the drop, else the current definitions' label ("" when neither knows it): old records keep their tier. */
    static String tier(HighlightsModel.Notable drop) {
        return drop.tier() != null ? drop.tier() : ItemTiers.label(drop.itemId());
    }

    /** UT and ST follow tier borders; potions are INFO and tiered gear stays neutral. */
    static Tokens.Tone tone(String type) {
        switch (type) {
            case "UT": return Tokens.Tone.WARN;
            case "ST": return Tokens.Tone.BAD;
            case "Potion": return Tokens.Tone.INFO;
            default: return Tokens.Tone.NEUTRAL;
        }
    }

    /**
     * "Potion of Life, stat potion; Lost Halls, today at 09:05; Orange bag; Enter opens the run recap": the item, its kind, where
     * and when it dropped, its bag and whether it links to a run, in words. Enchanted drops include their recorded rarity,
     * for example "T12 (Rare · 2 enchant slots)". (public: Explore reuses it)
     */
    public static String accessibleName(HighlightsModel.Notable drop, ZoneId zone, long now) {
        return accessibleName(drop, zone, now, "Enter opens the run recap");
    }

    /** The drop's facts with the host view's wording for opening a linked run. */
    public static String accessibleName(HighlightsModel.Notable drop, ZoneId zone, long now, String opens) {
        return facts(drop, zone, now, true, opens);
    }

    /** The drop's facts in words; {@code withEnchant} adds the enchant summary after the kind (the enchant tooltip has its own line for it). */
    private static String facts(HighlightsModel.Notable drop, ZoneId zone, long now, boolean withEnchant, String opens) {
        String kind = drop.kind() == HighlightsModel.Kind.POTION ? "stat potion" : itemType(drop);
        if (withEnchant && drop.enchant().state() != EnchantInfo.State.NOT_RECORDED) kind += " (" + drop.enchant().summary() + ")";
        return Sprites.name(drop.itemId()) + ", " + kind + "; " + LootFacts.areaLabel(drop.dungeon())
            + ", " + spokenTime(drop.time(), zone, now) + "; " + (drop.bag() == null ? "bag not saved" : drop.bag() + " bag") + "; "
            + (drop.visit() == null ? "not linked to a run" : opens);
    }

    /** "14:32" on {@code now}'s day, "Yesterday 22:10", else "13 Jan 14:32" (with the year when it is not this year's). */
    static String time(long at, ZoneId zone, long now) {
        ZonedDateTime when = Instant.ofEpochMilli(at).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), day = when.toLocalDate();
        if (day.equals(today)) return TIME.format(when);
        if (day.equals(today.minusDays(1))) return "Yesterday " + TIME.format(when);
        return (day.getYear() == today.getYear() ? DATE : DATE_YEAR).format(when);
    }

    private static String spokenTime(long at, ZoneId zone, long now) {
        String time = time(at, zone, now);
        if (Instant.ofEpochMilli(at).atZone(zone).toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())) return "today at " + time;
        if (time.startsWith("Yesterday ")) return "yesterday at " + time.substring("Yesterday ".length());
        return time;
    }

    Lines shown() { return lines; }

    /** The strings the last paint drew, in order, after fitting (painted text is not in the component tree, so tests read it here). */
    List<String> painted() { return List.copyOf(painted); }

    /** Where the well paints in a cell {@code width} × {@code height}: at the card's left, vertically centered. */
    static Rectangle well(int width, int height) {
        int top = GAP / 2 + PAD, inner = height - GAP - 2 * PAD;
        return new Rectangle(GAP / 2 + PAD, top + Math.max(0, (inner - WELL_SIDE) / 2), WELL_SIDE, WELL_SIDE);
    }

    /** The list cell: the card plus half the gap between cells on each side, at the current body font. */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption());
        int height = 2 * PAD + Math.max(WELL_SIDE, textHeight(title, caption));
        int width = Math.max(Math.round(ContentStyle.body().getSize2D() * 17f), WELL_SIDE + 2 * PAD + 120);
        return new Dimension(width + GAP, height + GAP);
    }

    /** The kit TileList sizes its fixed cells from the renderer's preferred size: the cell. */
    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends HighlightsModel.Notable> list, HighlightsModel.Notable value,
                                                            int index, boolean isSelected, boolean cellHasFocus) {
        drop = value;
        selected = isSelected;
        focused = cellHasFocus;
        long at = now.getAsLong();
        renderedAt = at;
        lines = value == null ? null : lines(value, zone, at);
        String name = value == null ? null : accessibleName(value, zone, at, opens);
        getAccessibleContext().setAccessibleName(name);
        getAccessibleContext().setAccessibleDescription(value == null ? null
            : value.visit() == null ? NOT_LINKED : opensFully);
        setToolTipText(name == null ? null : name + " · " + HighlightsModel.OBSERVED);
        return this;
    }

    /** Built when the list asks (on hover): the drop's facts, then its enchant lines when it has enchant data. */
    @Override public String getToolTipText() {
        String facts = super.getToolTipText();
        if (facts == null || drop == null || drop.enchant().state() == EnchantInfo.State.NOT_RECORDED) return facts;
        // The enchant tooltip says the rarity on its own line, so its heading leaves it out.
        return EnchantTooltip.html(facts(drop, zone, renderedAt, false, opens) + " · " + HighlightsModel.OBSERVED, drop.enchant());
    }

    @Override protected void paintComponent(Graphics graphics) {
        painted = List.of();
        if (drop == null || lines == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map) g.addRenderingHints((Map<?, ?>) hints);
            int x = GAP / 2, y = GAP / 2, w = getWidth() - GAP, h = getHeight() - GAP;
            RoundRectangle2D shape = new RoundRectangle2D.Float(x + .5f, y + .5f, w - 1, h - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
            g.setColor(Tokens.color(selected ? Tokens.Role.ACCENT_WASH : Tokens.Role.RAISED));
            g.fill(shape);
            edge(g, shape, selected, focused);
            Rectangle well = well(getWidth(), getHeight());
            Sprites.paintWell(this, g, EnchantPips.glow(Sprites.sprite(drop.itemId(), SPRITE), drop.enchant()), drop.bag(), well.x, well.y, well.width);
            // paintWell's side covers side px; the pip painter takes ItemSlot's side + 1 convention.
            EnchantPips.paintCorner(g, drop.enchant(), tier(drop), well.x, well.y, well.width - 1);
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics title = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int left = well.x + well.width + Tokens.S, right = x + w - PAD;
            int top = y + PAD + Math.max(0, (h - 2 * PAD - textHeight(title, caption)) / 2);
            Color ink = Tokens.color(Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            List<String> drawn = new ArrayList<>(6);
            // The name on up to two lines (every card keeps both, so the rows below line up across the grid).
            List<String> name = wrap(lines.name(), title, right - left, NAME_LINES);
            for (int i = 0; i < name.size(); i++) text(g, drawn, name.get(i), titleFont, title, ink, left, top + i * title.getHeight() + title.getAscent(), right - left);
            // Chips have their own row; time leads the area line below, followed by the run link note.
            int row = top + NAME_LINES * title.getHeight() + Tokens.XS, chipHeight = caption.getHeight() + 2;
            boolean enchanted = drop.enchant().enchanted();
            int available = right - left;
            int chipsRoom = enchanted ? Math.max(0, available - Tokens.XS) : available;
            int typeRoom = enchanted ? Math.min(caption.stringWidth(lines.chip()) + 14, chipsRoom / 2) : available;
            int chipWidth = chip(g, drawn, lines.chip(), Tokens.tone(lines.tone()), left, row, caption, typeRoom);
            if (enchanted) {
                int after = left + chipWidth + Tokens.XS;
                chip(g, drawn, drop.enchant().rarity().label, Tokens.rarity(drop.enchant().rarity()),
                    after, row, caption, chipsRoom - chipWidth);
            }
            int area = row + chipHeight + LINE;
            text(g, drawn, lines.when() + " · " + lines.where(), captionFont, caption, muted, left, area + caption.getAscent(), right - left);
            text(g, drawn, lines.link(), captionFont, caption, muted, left, area + caption.getHeight() + caption.getAscent(), right - left);
            painted = drawn;
        } finally {
            g.dispose();
        }
    }

    /** The lines: the name's two, the chip row (2 px taller than the caption), time with area, and the run link note. */
    private static int textHeight(FontMetrics title, FontMetrics caption) {
        return NAME_LINES * title.getHeight() + Tokens.XS + caption.getHeight() + 2 + LINE + 2 * caption.getHeight();
    }

    /**
     * The card's edge: the accent when selected or focused (2 px when focused); else in the light theme {@link Tokens#outline}
     * (BORDER_SUBTLE, BORDER under Increase contrast) and in the dark theme its subtle border, as before (dark pixels never move).
     * Leaves a 1 px stroke.
     */
    static void edge(Graphics2D g, Shape shape, boolean selected, boolean focused) {
        if (selected || focused || Tokens.dark()) {
            g.setColor(Tokens.color(selected || focused ? Tokens.Role.ACCENT : Tokens.Role.BORDER_SUBTLE));
            g.setStroke(new BasicStroke(focused ? 2f : 1f));
            g.draw(shape);
        } else Tokens.outline(g, shape);
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * {@code value} on at most {@code max} lines of {@code width}: broken at spaces, each line as full as fits; the last line takes
     * the rest, cut with "…" when it is still too wide, as is a first word wider than a line. Empty for no text.
     */
    static List<String> wrap(String value, FontMetrics metrics, int width, int max) {
        List<String> lines = new ArrayList<>(max);
        String rest = value == null ? "" : value.trim();
        while (!rest.isEmpty() && width > 0) {
            if (lines.size() == max - 1 || metrics.stringWidth(rest) <= width) { lines.add(fit(rest, metrics, width)); break; }
            int cut = -1;   // the last space before which the line still fits
            for (int space = rest.indexOf(' '); space > 0 && metrics.stringWidth(rest.substring(0, space)) <= width; space = rest.indexOf(' ', space + 1)) cut = space;
            if (cut < 0) { lines.add(fit(rest, metrics, width)); break; }
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut + 1).trim();
        }
        return lines;
    }

    private static void text(Graphics2D g, List<String> drawn, String value, Font font, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value == null || value.isEmpty() || width <= 0) return;
        String fitted = fit(value, metrics, width);
        g.setFont(font);
        g.setColor(color);
        g.drawString(fitted, x, baseline);
        drawn.add(fitted);
    }

    /** The text, or its longest prefix plus "…" that fits the width. */
    static String fit(String value, FontMetrics metrics, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + "…") > width) end--;
        return end == 0 ? "" : value.substring(0, end) + "…";
    }

    /** A tinted chip from {@code left}, at most {@code max} wide (its label cut with "…"); returns its width (0 when it does not fit). */
    private static int chip(Graphics2D g, List<String> drawn, String label, Color color, int left, int top, FontMetrics metrics, int max) {
        int width = Math.min(metrics.stringWidth(label), Math.max(0, max - 14)) + 14;
        if (width <= 14) return 0;
        String fitted = fit(label, metrics, width - 14);
        g.setColor(Tokens.tint(color));
        g.fillRoundRect(left, top, width, metrics.getHeight() + 2, Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.setFont(metrics.getFont());
        g.setColor(color);
        g.drawString(fitted, left + 7, top + 1 + metrics.getAscent());
        drawn.add(fitted);
        return width;
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
