package tomato.gui.runs;

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
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;

/**
 * Paints one saved run of the feed (spec §6.3 Feed, §9, §10): one component reused for every cell of a day's TileList, no
 * per-card component tree. The card's left edge is in the outcome's tone; beside the 40 px portal sit the map name, the outcome
 * chip, the entry time ("14:32" today, "Yesterday 22:10", else the date) with the observed span and the observed party ("Party —"
 * when none was observed). The combat line is "Your DPS 12.3k · #2 of 6 · 34%" over a share bar, only from the linked recording's
 * verified local row; otherwise the model's reason, muted (no recording, an unverified local row, unreadable records): another
 * player's row is never shown as yours. The loot strip holds at most eight sprites in wells colored by their bag, "+N" and the
 * summary, which moves to its own line under the sprites when it does not fit beside them (it is never cut); unknown loot shows
 * its reason (never "no loot"), a known none "No loot recorded in this run". The last line holds
 * "+1,240 fame" (nothing when unknown), "Deaths 1" (only with a verified local row) and "Exalt progress +1" (only when positive).
 * Every shown fact and every missing link is in the accessible name, in words. The cell is fixed (the widest loot strip and
 * summary at the body font); colors come from Tokens at paint time.
 */
public final class RunCardRenderer extends JComponent implements ListCellRenderer<RunCardModel>, Accessible {
    /** Portal and loot sprite sizes, the well around a loot sprite, the space between cards, the outcome edge and the share bar. */
    static final int PORTAL = 40, LOOT = 20, WELL = Sprites.WELL, GAP = 10, EDGE = 4, BAR = 4;
    /** A known run without a bag: its loot is a real none. */
    static final String NO_LOOT = "No loot recorded in this run";
    /**
     * The summary the cell's width reserves room for beside eight sprites; with "+N" or a longer summary it takes the line under
     * the sprites, which the cell's height reserves.
     */
    private static final String WIDEST_SUMMARY = "1 UT · 1 ST · 2 potions";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH),
        DATE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH), DATE_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    /**
     * The text one card paints (painted text is not in the component tree, so tests read it here). {@code combat} is the
     * "Your DPS" line and {@code share} its bar (percent), or {@code combatNote} the reason there is none; {@code lootNote} is
     * the unknown-loot reason or {@link #NO_LOOT}, else {@code loot} and {@code lootMore} ("+3") beside {@code lootSummary}.
     */
    record Lines(String title, String chip, Tokens.Tone tone, String when, String combat, Double share, String combatNote,
                 List<RunCardModel.LootItem> loot, String lootMore, String lootSummary, String lootNote, String facts) {}

    private final ZoneId zone;
    private final LongSupplier now;
    private RunCardModel card;
    private Lines lines;
    private boolean selected, focused;

    /** Times are written in {@code zone} against {@code now}'s day there (the feed passes its model's read time). */
    public RunCardRenderer(ZoneId zone, LongSupplier now) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.now = Objects.requireNonNull(now, "now");
        setOpaque(false);
    }

    /** The card's painted text in {@code zone} on {@code now}'s day. */
    static Lines lines(RunCardModel card, ZoneId zone, long now) {
        String when = time(card.entered(), zone, now) + " · " + (card.durationMs() == null ? "duration —" : RunFeedModel.duration(card.durationMs()) + " observed")
            + " · " + (card.partySize() == null ? "Party —" : "Party " + card.partySize());
        String combat = "", note = card.combatReason() == null ? "" : card.combatReason();
        Double share = null;
        RunCardModel.Combat line = card.combat();
        if (line != null && card.combatReason() == null) {
            combat = "Your DPS " + (line.localDps() == null ? "—" : KitFormat.compact(line.localDps()))
                + (line.rank() == null ? " · rank —" : " · #" + line.rank() + " of " + line.contributors())
                + (line.share() == null ? "" : " · " + percent(line.share()));
            share = line.share();
        } else if (note.isEmpty()) {
            note = RunCardModel.NO_RECORDING;   // defensive: a model without either says the link is missing
        }
        String lootNote = card.lootReason() != null ? card.lootReason() : card.lootCount() == 0 ? NO_LOOT : "";
        String more = lootNote.isEmpty() && card.lootCount() > card.loot().size() ? "+" + (card.lootCount() - card.loot().size()) : "";
        List<String> facts = new ArrayList<>();
        if (card.fameGained() != null) facts.add("+" + String.format(Locale.ENGLISH, "%,d", card.fameGained()) + " fame");
        if (line != null && card.combatReason() == null && line.localDeaths() != null) facts.add("Deaths " + line.localDeaths());
        if (card.exaltProgress() != null) facts.add("Exalt progress +" + card.exaltProgress());
        return new Lines(card.mapName(), card.outcome().label(), card.outcome().tone(), when, combat, share, combat.isEmpty() ? note : "",
            lootNote.isEmpty() ? card.loot() : List.of(), more, lootNote.isEmpty() ? card.lootSummary() : "", lootNote, String.join(" · ", facts));
    }

    /**
     * "Lost Halls, Completed; entered today at 14:32; 12 m observed; party of 6; your DPS 12.3k, rank 2 of 6, 30% of the damage,
     * from the longest of 2 recordings; 6 loot items: 1 UT · 1 ST · 2 potions; 240 fame gained; 1 death; exalt progress +2":
     * every fact the card shows, and each missing one in words ("party not observed", "fame gained unknown", the reasons).
     */
    static String accessibleName(RunCardModel card, ZoneId zone, long now) {
        List<String> parts = new ArrayList<>();
        parts.add(card.mapName() + ", " + card.outcome().label());
        parts.add("entered " + spokenTime(card.entered(), zone, now));
        parts.add(card.durationMs() == null ? "duration unknown" : RunFeedModel.duration(card.durationMs()) + " observed");
        parts.add(card.partySize() == null ? "party not observed" : "party of " + card.partySize());
        RunCardModel.Combat line = card.combat();
        if (line != null && card.combatReason() == null) {
            String combat = "your DPS " + (line.localDps() == null ? "unknown" : KitFormat.compact(line.localDps()))
                + (line.rank() == null ? ", rank unknown" : ", rank " + line.rank() + " of " + line.contributors())
                + (line.share() == null ? ", share unknown" : ", " + percent(line.share()) + " of the damage");
            if (line.recordings() > 1) combat += ", from the longest of " + line.recordings() + " recordings";
            parts.add(combat);
        } else {
            parts.add(lower(card.combatReason() == null ? RunCardModel.NO_RECORDING : card.combatReason()));
        }
        if (card.lootReason() != null) parts.add("loot unknown: " + lower(card.lootReason()));
        else if (card.lootCount() == 0) parts.add("no loot recorded in this run");
        else parts.add(card.lootCount() + (card.lootCount() == 1 ? " loot item" : " loot items")
            + (card.lootSummary().isEmpty() ? "" : ": " + card.lootSummary()));
        parts.add(card.fameGained() == null ? "fame gained unknown" : String.format(Locale.ENGLISH, "%,d", card.fameGained()) + " fame gained");
        if (line != null && card.combatReason() == null)
            parts.add(line.localDeaths() == null ? "deaths unknown" : line.localDeaths() + (line.localDeaths() == 1 ? " death" : " deaths"));
        parts.add(card.exaltProgress() == null ? "no exalt progress recorded" : "exalt progress +" + card.exaltProgress());
        return String.join("; ", parts);
    }

    /** "14:32" on {@code now}'s day, "Yesterday 22:10", else "13 Jan 14:32" (with the year when it is not this year's). */
    static String time(long entered, ZoneId zone, long now) {
        ZonedDateTime at = Instant.ofEpochMilli(entered).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), day = at.toLocalDate();
        if (day.equals(today)) return TIME.format(at);
        if (day.equals(today.minusDays(1))) return "Yesterday " + TIME.format(at);
        return (day.getYear() == today.getYear() ? DATE : DATE_YEAR).format(at);
    }

    private static String spokenTime(long entered, ZoneId zone, long now) {
        String time = time(entered, zone, now);
        ZonedDateTime at = Instant.ofEpochMilli(entered).atZone(zone);
        if (at.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())) return "today at " + time;
        if (time.startsWith("Yesterday ")) return "yesterday at " + time.substring("Yesterday ".length());
        return time;
    }

    /** "34%", "5.3%" and "0.4%" below 10 (a small share never reads as none), "0%" only for a real zero. */
    static String percent(double share) {
        if (share >= 10 || share == 0) return Math.round(share) + "%";
        return String.format(Locale.ENGLISH, "%.1f%%", share);
    }

    private static String lower(String sentence) {
        String text = sentence.endsWith(".") ? sentence.substring(0, sentence.length() - 1) : sentence;
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    Lines shown() { return lines; }

    /**
     * Where the loot line paints in a loot row {@code width} wide, from the row's left and top: the wells' top, "+N" and the
     * summary with the text it shows.
     */
    record Strip(int wellTop, int moreX, int moreBaseline, int summaryX, int summaryBaseline, String summary) {}

    /** The loot row's height: the wells and, under them, a line for a summary that does not fit beside them (or a two-line reason). */
    static int lootHeight(FontMetrics caption) { return Math.max(LOOT + WELL + Tokens.XS + caption.getHeight(), 2 * caption.getHeight()); }

    /**
     * The loot line of {@code lines} in a row {@code width} wide: the wells at the top, "+N" after the last one and the summary after
     * that when it fits there whole; otherwise the summary takes its own line under the wells (the row reserves it, and the cell is
     * wide enough for the widest summary), so its counts are never cut.
     */
    static Strip strip(Lines lines, FontMetrics caption, int width) {
        int well = LOOT + WELL, baseline = (well - caption.getHeight()) / 2 + caption.getAscent();
        int x = lines.loot().size() * (well + Tokens.XS) + Tokens.S - Tokens.XS, more = x;
        if (!lines.lootMore().isEmpty()) x += caption.stringWidth(lines.lootMore()) + Tokens.S;
        String summary = lines.lootSummary();
        if (caption.stringWidth(summary) <= width - x) return new Strip(0, more, baseline, x, baseline, summary);
        return new Strip(0, more, baseline, 0, well + Tokens.XS + caption.getAscent(), fit(summary, caption, width));
    }

    /** The list cell: the card plus half the gap between cards on each side, at the current body font. */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption()), body = getFontMetrics(Type.body());
        int header = Math.max(PORTAL, title.getHeight() + Tokens.XS + caption.getHeight());
        int combat = Math.max(body.getHeight() + Tokens.XS + BAR, 2 * caption.getHeight());
        int loot = lootHeight(caption);
        int height = Tokens.M + header + Tokens.S + combat + Tokens.S + loot + Tokens.S + caption.getHeight() + Tokens.M;
        int strip = RunCardModel.LOOT_ICONS * (LOOT + WELL) + (RunCardModel.LOOT_ICONS - 1) * Tokens.XS;
        int width = Math.max(Math.round(ContentStyle.body().getSize2D() * 24f),
            EDGE + 2 * Tokens.M + strip + Tokens.S + caption.stringWidth(WIDEST_SUMMARY));
        return new Dimension(width + GAP, height + GAP);
    }

    /** The kit TileList sizes its fixed cells from the renderer's preferred size: the cell. */
    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends RunCardModel> list, RunCardModel value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        card = value;
        selected = isSelected;
        focused = cellHasFocus;
        long at = now.getAsLong();
        lines = value == null ? null : lines(value, zone, at);
        String name = value == null ? null : accessibleName(value, zone, at);
        getAccessibleContext().setAccessibleName(name);
        getAccessibleContext().setAccessibleDescription(value == null ? null : "Enter, Space or double-click opens the run");
        setToolTipText(name == null ? null : name + " · Enter or double-click opens the run");
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
            // The outcome edge: the tone at the card's left, inside its rounded corners (the chip also names the outcome).
            Shape clip = g.getClip();
            g.clip(shape);
            g.setColor(Tokens.tone(lines.tone()));
            g.fillRect(x, y, EDGE, h);
            g.setClip(clip);
            g.setColor(Tokens.color(selected || focused ? Tokens.Role.ACCENT : Tokens.Role.BORDER_SUBTLE));
            g.setStroke(new BasicStroke(focused ? 2f : 1f));
            g.draw(shape);
            g.setStroke(new BasicStroke(1f));
            Color ink = Tokens.color(Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            Font titleFont = Type.emphasis(), captionFont = Type.caption(), bodyFont = Type.body();
            FontMetrics titleMetrics = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont), body = g.getFontMetrics(bodyFont);
            int left = x + EDGE + Tokens.M, right = x + w - Tokens.M, top = y + Tokens.M;
            // Header: the portal, then the map name with the outcome chip at the right, and the time line below.
            int header = Math.max(PORTAL, titleMetrics.getHeight() + Tokens.XS + caption.getHeight());
            Sprites.sprite(card.portalId(), PORTAL).paintIcon(this, g, left, top + (header - PORTAL) / 2);
            int textLeft = left + PORTAL + Tokens.S, textTop = top + (header - titleMetrics.getHeight() - Tokens.XS - caption.getHeight()) / 2;
            int chipHeight = caption.getHeight() + 2, max = (right - textLeft) / 2, chipLeft = right - chipWidth(lines.chip(), caption, max);
            chip(g, lines.chip(), lines.tone(), chipLeft, textTop + (titleMetrics.getHeight() - chipHeight) / 2, caption, max);
            text(g, lines.title(), titleFont, titleMetrics, ink, textLeft, textTop + titleMetrics.getAscent(), chipLeft - Tokens.S - textLeft);
            text(g, lines.when(), captionFont, caption, muted, textLeft, textTop + titleMetrics.getHeight() + Tokens.XS + caption.getAscent(), right - textLeft);
            // Combat: your line over the share bar, or why there is none (two lines at most).
            int row = top + header + Tokens.S, combatHeight = Math.max(body.getHeight() + Tokens.XS + BAR, 2 * caption.getHeight());
            if (!lines.combat().isEmpty()) {
                text(g, lines.combat(), bodyFont, body, ink, left, row + body.getAscent(), right - left);
                int barTop = row + body.getHeight() + Tokens.XS;
                g.setColor(Tokens.color(Tokens.Role.SURFACE_ALT));
                g.fillRoundRect(left, barTop, right - left, BAR, BAR, BAR);
                if (lines.share() != null && lines.share() > 0) {
                    g.setColor(Tokens.color(Tokens.Role.ACCENT));
                    g.fillRoundRect(left, barTop, Math.max(BAR, (int) Math.round((right - left) * Math.min(100d, lines.share()) / 100d)), BAR, BAR, BAR);
                }
            } else {
                wrapped(g, lines.combatNote(), caption, muted, left, row, right - left, 2);
            }
            // Loot: bag-colored wells, "+N" and the summary; or the reason / the known none.
            row += combatHeight + Tokens.S;
            int lootHeight = lootHeight(caption);
            if (!lines.lootNote().isEmpty()) {
                wrapped(g, lines.lootNote(), caption, muted, left, row + (lootHeight - 2 * caption.getHeight()) / 2, right - left, 2);
            } else {
                Strip strip = strip(lines, caption, right - left);
                int well = LOOT + WELL, slotX = left;
                for (RunCardModel.LootItem item : lines.loot()) {
                    Sprites.paintWell(this, g, EnchantPips.glow(Sprites.sprite(item.id(), LOOT), item.enchant()), item.bag(), slotX, row + strip.wellTop(), well);
                    EnchantPips.paintCorner(g, item.enchant(), item.tier() == null || item.tier().isEmpty() ? ItemTiers.label(item.id()) : item.tier(), slotX, row + strip.wellTop(), well - 1);
                    slotX += well + Tokens.XS;
                }
                text(g, lines.lootMore(), captionFont, caption, muted, left + strip.moreX(), row + strip.moreBaseline(), right - left - strip.moreX());
                text(g, strip.summary(), captionFont, caption, ink, left + strip.summaryX(), row + strip.summaryBaseline(), right - left - strip.summaryX());
            }
            row += lootHeight + Tokens.S;
            text(g, lines.facts(), captionFont, caption, ink, left, row + caption.getAscent(), right - left);
        } finally {
            g.dispose();
        }
    }

    /** Up to {@code lines} word-wrapped lines from {@code top}; the last one ends with "…" when the text goes on. */
    private static void wrapped(Graphics2D g, String value, FontMetrics metrics, Color color, int x, int top, int width, int lines) {
        if (value.isEmpty() || width <= 0) return;
        List<String> rows = wrap(value, metrics, width, lines);
        for (int i = 0; i < rows.size(); i++)
            text(g, rows.get(i), metrics.getFont(), metrics, color, x, top + i * metrics.getHeight() + metrics.getAscent(), width);
    }

    /** Word-wraps {@code value} into at most {@code lines} rows at {@code width}; the last row keeps the rest (painted cut with "…"). */
    static List<String> wrap(String value, FontMetrics metrics, int width, int lines) {
        List<String> rows = new ArrayList<>();
        String rest = value.trim();
        while (!rest.isEmpty()) {
            if (rows.size() == lines - 1 || metrics.stringWidth(rest) <= width) { rows.add(rest); break; }
            int cut = -1;
            for (int space = rest.indexOf(' '); space > 0; space = rest.indexOf(' ', space + 1)) {
                if (metrics.stringWidth(rest.substring(0, space)) > width) break;
                cut = space;
            }
            if (cut <= 0) { rows.add(rest); break; }   // one word wider than the row: cut with "…"
            rows.add(rest.substring(0, cut));
            rest = rest.substring(cut + 1).trim();
        }
        return rows;
    }

    private static void text(Graphics2D g, String value, Font font, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value == null || value.isEmpty() || width <= 0) return;
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

    private static int chipWidth(String label, FontMetrics metrics, int max) {
        return Math.min(metrics.stringWidth(label), Math.max(0, max - 14)) + 14;
    }

    /** A tinted chip from {@code left}, at most {@code max} wide (its label cut with "…"). */
    private static void chip(Graphics2D g, String label, Tokens.Tone tone, int left, int top, FontMetrics metrics, int max) {
        int width = chipWidth(label, metrics, max);
        if (width <= 14) return;
        g.setColor(Tokens.tint(Tokens.tone(tone)));
        g.fillRoundRect(left, top, width, metrics.getHeight() + 2, Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.setFont(metrics.getFont());
        g.setColor(Tokens.tone(tone));
        g.drawString(fit(label, metrics, width - 14), left + 7, top + 1 + metrics.getAscent());
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
