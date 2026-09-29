package tomato.gui.runs;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;

/**
 * Paints one dungeon's card on the Dungeons tab (spec §6.3 Dungeons, §9, §10): one component reused for every cell of the
 * TileList, no per-card component tree. Beside the 40 px portal sit the dungeon's name and "N visits"; below, four facts, each a
 * value line over a muted caption line:
 * - "Completion 67% observed" (the runs not counted named below; its tooltip gives the counts per outcome and the caveat that
 *   Left and App ended runs may include clears the app did not see);
 * - "Avg 12 m 30 s observed" (the mean observed span of the completed runs);
 * - "Loot 3.2 per completed run", with "◐ N excluded" when completed runs whose loot is unknown were left out, captioned
 *   "bags linked to the exact run";
 * - "Best DPS 12.3k" from your verified row in your best completed run.
 * Every unknown is "—" with its reason as the caption (never 0); a caption that does not fit ends with "…" and is whole in its
 * line's tooltip and in the accessible name, which states every fact and every reason in words. The action strip paints
 * Show runs (primary), Open best run (only when a best run is known) and Analyze (Analyst only); the view maps a click on one
 * ({@link #actionAt}) and offers the same actions from the keyboard. The cell is fixed; colors come from Tokens at paint time.
 */
public final class DungeonCardRenderer extends JComponent implements ListCellRenderer<DungeonCardModel>, Accessible {
    /** The portal sprite and the space between cards. */
    static final int PORTAL = 40, GAP = 10;
    /** A known best's caption when every completed run counted: the run it names opens with Open best run. */
    static final String BEST_NOTE = "From your best completed run; Open best run opens it";
    /** The names the widest title reserves room for (the cell never grows with a name; a longer one ends with "…"). */
    private static final String WIDEST_TITLE = "Lost Halls of the Shatters";

    /** A card's actions, in the strip's order. */
    enum Action {
        RUNS("Show runs", "Shows this dungeon's saved runs in the feed"),
        BEST("Open best run", "Opens the recap of the completed run with your best verified DPS"),
        ANALYZE("Analyze", "Shows this dungeon in the Analysis view (session comparison and cohorts)");

        final String label, description;

        Action(String label, String description) { this.label = label; this.description = description; }
    }

    /** One fact's painted value line, its caption (a reason, the loot caption, or "") and its line's tooltip. */
    record Fact(String value, String note, String tip) {}

    /** The text one card paints (painted text is not in the component tree, so tests read it here) and its actions. */
    record Lines(String title, String visits, Fact completion, Fact duration, Fact loot, Fact best, List<Action> actions) {
        List<Fact> facts() { return List.of(completion, duration, loot, best); }
    }

    private final BooleanSupplier analyst;
    private DungeonCardModel card;
    private Lines lines;
    private boolean selected, focused;

    /** {@code analyst}: whether the Analyst-only Analyze action shows (read at each paint). */
    public DungeonCardRenderer(BooleanSupplier analyst) {
        this.analyst = Objects.requireNonNull(analyst, "analyst");
        setOpaque(false);
    }

    /** The card's painted text; {@code analyst} adds Analyze. */
    static Lines lines(DungeonCardModel card, boolean analyst) {
        String reason = card.completionReason();
        String counts = "Completed " + card.completed() + " · Left " + card.left() + " · App ended " + card.appEnded() + " of "
            + runs(card.finished(), "finished run");
        Fact completion = card.completionRate() == null
            ? new Fact("Completion —", orElse(reason, DungeonCardModel.NO_FINISHED_RUN),
                counts + ". " + orElse(reason, DungeonCardModel.NO_FINISHED_RUN) + " " + DungeonCardModel.OBSERVED)
            : new Fact("Completion " + RunCardRenderer.percent(card.completionRate() * 100) + " observed", orElse(reason, ""),
                counts + "." + (reason == null ? "" : " " + reason) + " " + DungeonCardModel.OBSERVED);

        String spanReason = card.durationReason();
        Fact duration = card.averageDurationMs() == null
            ? new Fact("Avg —", orElse(spanReason, DungeonCardModel.NO_OBSERVED_SPAN), orElse(spanReason, DungeonCardModel.NO_OBSERVED_SPAN))
            : new Fact("Avg " + average(card.averageDurationMs()) + " observed", orElse(spanReason, ""),
                "Mean observed span (entry to the last saved observation, not a verified clear time) of "
                    + runs(card.durationRuns(), "completed run") + "." + (spanReason == null ? "" : " " + spanReason));

        String lootReason = card.lootReason();
        Fact loot = card.lootPerCompletedRun() == null
            ? new Fact("Loot —", orElse(lootReason, DungeonCardModel.LOOT_NOT_SAVED),
                orElse(lootReason, DungeonCardModel.LOOT_NOT_SAVED) + " Only " + DungeonCardModel.LOOT_CAPTION + " count.")
            : new Fact("Loot " + decimal(card.lootPerCompletedRun()) + " per completed run"
                    + (card.lootPartial() ? " ◐ " + card.lootExcluded() + " excluded" : ""), DungeonCardModel.LOOT_CAPTION,
                "Items in " + DungeonCardModel.LOOT_CAPTION + ", averaged over " + runs(card.lootRuns(), "completed run")
                    + " whose loot is known (a known none counts)." + (lootReason == null ? "" : " " + lootReason));

        String dpsReason = card.dpsReason();
        Fact best = card.bestLocalDps() == null
            ? new Fact("Best DPS —", orElse(dpsReason, DungeonCardModel.NO_RECORDING), orElse(dpsReason, DungeonCardModel.NO_RECORDING))
            : new Fact("Best DPS " + KitFormat.compact(card.bestLocalDps()), orElse(dpsReason, BEST_NOTE),
                "Your highest verified DPS in a completed run (its linked recording's verified local row, never another player's)."
                    + " Open best run opens that run's recap." + (dpsReason == null ? "" : " " + dpsReason));

        List<Action> actions = new ArrayList<>();
        actions.add(Action.RUNS);
        if (card.bestRun() != null) actions.add(Action.BEST);
        if (analyst) actions.add(Action.ANALYZE);
        return new Lines(card.displayName(), visits(card.visits()), completion, duration, loot, best, List.copyOf(actions));
    }

    /**
     * "Lost Halls; 7 visits; completion 67% observed: 4 completed, 1 left, 1 app ended of 6 finished runs; not counted: 1 in
     * progress; left and App ended runs may include clears the app did not see; average duration 17 m 30 s observed over 4
     * completed runs; loot 6.0 per completed run, bags linked to the exact run; best DPS 12.3k from your best completed run":
     * every fact the card shows, and each unknown or partial one's reason in words.
     */
    static String accessibleName(DungeonCardModel card, boolean analyst) {
        List<String> parts = new ArrayList<>();
        parts.add(card.displayName());
        parts.add(visits(card.visits()));
        String counts = card.completed() + " completed, " + card.left() + " left, " + card.appEnded() + " app ended of "
            + runs(card.finished(), "finished run");
        String reason = card.completionReason();
        if (card.completionRate() == null) parts.add("completion unknown: " + lower(orElse(reason, DungeonCardModel.NO_FINISHED_RUN)) + "; " + counts);
        else parts.add("completion " + RunCardRenderer.percent(card.completionRate() * 100) + " observed: " + counts
            + (reason == null ? "" : "; " + lower(reason)));
        parts.add(lower(DungeonCardModel.OBSERVED.substring(DungeonCardModel.OBSERVED.indexOf(':') + 1).trim()));
        String span = card.durationReason();
        if (card.averageDurationMs() == null) parts.add("average duration unknown: " + lower(orElse(span, DungeonCardModel.NO_OBSERVED_SPAN)));
        else parts.add("average duration " + average(card.averageDurationMs()) + " observed over " + runs(card.durationRuns(), "completed run")
            + (span == null ? "" : ", " + lower(span)));
        String loot = card.lootReason();
        if (card.lootPerCompletedRun() == null) parts.add("loot per completed run unknown: " + lower(orElse(loot, DungeonCardModel.LOOT_NOT_SAVED)));
        else parts.add("loot " + decimal(card.lootPerCompletedRun()) + " per completed run, " + DungeonCardModel.LOOT_CAPTION
            + (loot == null ? "" : (card.lootPartial() ? ", partial, " : ", ") + lower(loot)));
        String dps = card.dpsReason();
        if (card.bestLocalDps() == null) parts.add("best DPS unknown: " + lower(orElse(dps, DungeonCardModel.NO_RECORDING)));
        else parts.add("best DPS " + KitFormat.compact(card.bestLocalDps()) + " from your best completed run" + (dps == null ? "" : ", " + lower(dps)));
        return String.join("; ", parts);
    }

    /** The keyboard's and a reader's description of what the card offers. */
    static String accessibleDescription(DungeonCardModel card, boolean analyst) {
        List<String> more = new ArrayList<>();
        if (card.bestRun() != null) more.add(Action.BEST.label);
        if (analyst) more.add(Action.ANALYZE.label);
        return "Enter, Space or double-click shows this dungeon's runs" + (more.isEmpty() ? "" : "; Shift+F10 for " + String.join(" and ", more));
    }

    /** "45 s", "12 m 30 s", "12 m", "1 h 5 m": a mean span to the second (an average is not rounded to whole minutes). */
    static String average(long millis) {
        long seconds = Math.round(millis / 1000d);
        if (seconds < 60) return seconds + " s";
        long minutes = seconds / 60, rest = seconds % 60;
        if (minutes < 60) return minutes + " m" + (rest == 0 ? "" : " " + rest + " s");
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " m");
    }

    private static String visits(int visits) { return String.format(Locale.ENGLISH, "%,d", visits) + (visits == 1 ? " visit" : " visits"); }
    private static String runs(int count, String noun) { return String.format(Locale.ENGLISH, "%,d", count) + " " + noun + (count == 1 ? "" : "s"); }
    private static String decimal(double value) { return String.format(Locale.ENGLISH, "%.1f", value); }
    private static String orElse(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    /** A sentence as a clause: its first letter lower case and no final period. */
    private static String lower(String sentence) {
        String text = sentence.endsWith(".") ? sentence.substring(0, sentence.length() - 1) : sentence;
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    Lines shown() { return lines; }

    /** Where a card of a cell width × height (gaps included) paints its header, facts and actions, at the current fonts. */
    private record Layout(int left, int right, int top, int header, Rectangle[] facts, Map<Action, Rectangle> actions) {}

    private Layout layout(List<Action> actions, int width, int height) {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption()), body = getFontMetrics(Type.body());
        int x = GAP / 2, y = GAP / 2, w = width - GAP, h = height - GAP;
        int left = x + Tokens.M, right = x + w - Tokens.M, top = y + Tokens.M;
        int header = header(title, caption), fact = body.getHeight() + caption.getHeight();
        Rectangle[] facts = new Rectangle[4];
        int row = top + header + Tokens.S;
        for (int i = 0; i < facts.length; i++) { facts[i] = new Rectangle(left, row, right - left, fact); row += fact + Tokens.XS; }
        int pill = pillHeight(caption), pillTop = y + h - Tokens.M - pill, pillLeft = left;
        Map<Action, Rectangle> bounds = new LinkedHashMap<>();
        for (Action action : actions) {
            int pillWidth = caption.stringWidth(action.label) + 2 * Tokens.S;
            bounds.put(action, new Rectangle(pillLeft, pillTop, pillWidth, pill));
            pillLeft += pillWidth + Tokens.XS;
        }
        return new Layout(left, right, top, header, facts, bounds);
    }

    private static int header(FontMetrics title, FontMetrics caption) { return Math.max(PORTAL, title.getHeight() + Tokens.XS + caption.getHeight()); }
    private static int pillHeight(FontMetrics caption) { return caption.getHeight() + Tokens.XS + 2; }

    /** The actions {@code card} paints in a cell width × height, in the strip's order, with their bounds in the cell. */
    Map<Action, Rectangle> actionBounds(DungeonCardModel card, int width, int height) {
        return layout(lines(card, analyst.getAsBoolean()).actions(), width, height).actions();
    }

    /** The action painted at ({@code x}, {@code y}) of {@code card}'s cell width × height, or null. */
    Action actionAt(DungeonCardModel card, int x, int y, int width, int height) {
        for (Map.Entry<Action, Rectangle> action : actionBounds(card, width, height).entrySet())
            if (action.getValue().contains(x, y)) return action.getKey();
        return null;
    }

    /** Fact {@code index}'s lines (0 completion, 1 duration, 2 loot, 3 best DPS) in a cell width × height. */
    Rectangle factBounds(int index, int width, int height) { return new Rectangle(layout(List.of(), width, height).facts()[index]); }

    /** The list cell: the card plus half the gap between cards on each side, at the current body font. */
    Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption()), body = getFontMetrics(Type.body());
        int fact = body.getHeight() + caption.getHeight();
        int height = Tokens.M + header(title, caption) + Tokens.S + 4 * fact + 3 * Tokens.XS + Tokens.S + pillHeight(caption) + Tokens.M;
        int pills = 0;
        for (Action action : Action.values()) pills += caption.stringWidth(action.label) + 2 * Tokens.S + Tokens.XS;
        int width = Math.max(Math.round(ContentStyle.body().getSize2D() * 24f),
            Math.max(2 * Tokens.M + pills, 2 * Tokens.M + PORTAL + Tokens.S + title.stringWidth(WIDEST_TITLE)));
        return new Dimension(width + GAP, height + GAP);
    }

    /** The kit TileList sizes its fixed cells from the renderer's preferred size: the cell. */
    @Override public Dimension getPreferredSize() { return cellSize(); }

    @Override public Component getListCellRendererComponent(JList<? extends DungeonCardModel> list, DungeonCardModel value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        card = value;
        selected = isSelected;
        focused = cellHasFocus;
        boolean analystMode = analyst.getAsBoolean();
        lines = value == null ? null : lines(value, analystMode);
        String name = value == null ? null : accessibleName(value, analystMode);
        getAccessibleContext().setAccessibleName(name);
        getAccessibleContext().setAccessibleDescription(value == null ? null : accessibleDescription(value, analystMode));
        setToolTipText(name);
        return this;
    }

    /** A fact line's tooltip (its counts, rule and whole reason) or an action's description over it; else the card's name. */
    @Override public String getToolTipText(MouseEvent event) {
        if (lines == null) return super.getToolTipText(event);
        Layout layout = layout(lines.actions(), getWidth(), getHeight());
        for (Map.Entry<Action, Rectangle> action : layout.actions().entrySet())
            if (action.getValue().contains(event.getPoint())) return action.getKey().label + ": " + action.getKey().description;
        List<Fact> facts = lines.facts();
        for (int i = 0; i < facts.size(); i++) if (layout.facts()[i].contains(event.getPoint())) return facts.get(i).tip();
        return super.getToolTipText(event);
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
            Font titleFont = Type.emphasis(), captionFont = Type.caption(), bodyFont = Type.body();
            FontMetrics title = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont), body = g.getFontMetrics(bodyFont);
            Layout layout = layout(lines.actions(), getWidth(), getHeight());
            int left = layout.left(), right = layout.right(), top = layout.top(), header = layout.header();
            // Header: the portal, then the name over "N visits".
            Sprites.sprite(card.portalId(), PORTAL).paintIcon(this, g, left, top + (header - PORTAL) / 2);
            int textLeft = left + PORTAL + Tokens.S, textTop = top + (header - title.getHeight() - Tokens.XS - caption.getHeight()) / 2;
            text(g, lines.title(), title, ink, textLeft, textTop + title.getAscent(), right - textLeft);
            text(g, lines.visits(), caption, muted, textLeft, textTop + title.getHeight() + Tokens.XS + caption.getAscent(), right - textLeft);
            // Facts: the value in the body color (an unknown's "—" too), its caption muted and cut with "…" (whole in the tooltip).
            List<Fact> facts = lines.facts();
            for (int i = 0; i < facts.size(); i++) {
                Rectangle row = layout.facts()[i];
                text(g, facts.get(i).value(), body, ink, row.x, row.y + body.getAscent(), row.width);
                text(g, facts.get(i).note(), caption, muted, row.x, row.y + body.getHeight() + caption.getAscent(), row.width);
            }
            // Actions: Show runs filled as the primary action, the others outlined.
            for (Map.Entry<Action, Rectangle> entry : layout.actions().entrySet()) {
                Rectangle pill = entry.getValue();
                boolean primary = entry.getKey() == Action.RUNS;
                g.setColor(primary ? Tokens.color(Tokens.Role.PRIMARY) : Tokens.color(Tokens.Role.CONTROL));
                g.fillRoundRect(pill.x, pill.y, pill.width, pill.height, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
                g.setColor(primary ? Tokens.color(Tokens.Role.PRIMARY) : Tokens.color(Tokens.Role.BORDER));
                g.drawRoundRect(pill.x, pill.y, pill.width - 1, pill.height - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
                text(g, entry.getKey().label, caption, primary ? Color.WHITE : ink, pill.x + Tokens.S,
                    pill.y + (pill.height - caption.getHeight()) / 2 + caption.getAscent(), pill.width - 2 * Tokens.S + 1);
            }
        } finally {
            g.dispose();
        }
    }

    private static void text(Graphics2D g, String value, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value == null || value.isEmpty() || width <= 0) return;
        g.setFont(metrics.getFont());
        g.setColor(color);
        g.drawString(RunCardRenderer.fit(value, metrics, width), x, baseline);
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
