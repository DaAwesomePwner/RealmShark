package tomato.gui.glance.home;

import java.awt.*;
import java.awt.event.*;
import java.util.Objects;
import java.util.function.Consumer;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Sparkline;
import tomato.gui.kit.StatTile;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Today's (local calendar day) or this session's progress from saved history (spec §6.1): completed runs, fame gained with
 * fame/hour and a sparkline, notable loot, potions. The Today / This session choice sits in the header; HomePage persists
 * it as ui.home.window and asks HomeRefresher for the matching read. A failed re-read keeps the last totals, marked stale
 * with a warn banner saying when they were read and why the new read failed. With Home's loot action the Notable loot tile opens
 * Loot › Highlights (P6a).
 */
final class TodayTiles extends HomeCard {
    static final String NO_RUNS = "No runs were saved for this period", NO_LOOT = "No loot was saved for this period";
    /** The Notable loot tile's action, the end of its spoken name. */
    static final String OPEN_LOOT = "Open loot highlights";
    private final SegmentedControl window = new SegmentedControl("home-today-window", "Today", "This session");
    private final StatTile runs = HomeViews.named(new StatTile("Runs"), "home-tile-runs");
    private final StatTile fame = HomeViews.named(new StatTile("Fame"), "home-tile-fame");
    /** Notable loot opens Loot › Highlights when Home has that action (the other tiles are plain). */
    private final OpenTile loot = HomeViews.named(new OpenTile("Notable loot"), "home-tile-loot");
    private final StatTile potions = HomeViews.named(new StatTile("Potions"), "home-tile-potions");
    private final Sparkline trend = HomeViews.named(new Sparkline(), "home-today-fame-trend");
    private final HomeViews.Reason note = new HomeViews.Reason("home-today-note");
    private final HomeViews.Reason unreadable = new HomeViews.Reason("home-today-unreadable");
    private final JPanel banners = HomeViews.stack(Tokens.XS, note, unreadable);
    private final EmptyState emptyToday = HomeViews.named(new EmptyState("Nothing recorded today",
        "Start capture and run a dungeon; today's runs, fame and loot add up here.", null), "home-today-empty");
    private final EmptyState emptySession = HomeViews.named(new EmptyState("Nothing recorded this session",
        "Start capture and run a dungeon; this session's runs, fame and loot add up here.", null), "home-today-empty-session");
    private final JComponent content;
    private HomeModel.Today shown;
    private HomeArchive.Window shownWindow;

    TodayTiles(Consumer<HomeArchive.Window> changed, HomeArchive.Window initial) { this(changed, initial, DisplayModeModel.application()); }

    TodayTiles(Consumer<HomeArchive.Window> changed, HomeArchive.Window initial, DisplayModeModel mode) { this(changed, initial, mode, null); }

    /** {@code openLoot}: what the Notable loot tile opens (Loot › Highlights, P6a); null leaves it a plain tile. */
    TodayTiles(Consumer<HomeArchive.Window> changed, HomeArchive.Window initial, DisplayModeModel mode, Runnable openLoot) {
        super(mode, "home-today", "Totals appear after the first read of saved history.");
        if (openLoot != null) loot.onOpen(OPEN_LOOT, openLoot);
        title("Progress"); // the segmented control already says "Today"
        window.setSelected(initial == HomeArchive.Window.SESSION ? 1 : 0);
        window.setToolTipText("Today is the local calendar day; This session is since RealmShark started");
        header().actions().add(window, 0);
        window.onChange(index -> {
            shown = null;
            status(HomeViews.LOADING, "Progress: loading");
            changed.accept(selected());
        });
        fame.trendSlot().add(trend);
        JPanel grid = ContentStyle.responsiveGrid(4, 180, Tokens.S);
        grid.setOpaque(false);
        for (StatTile tile : new StatTile[] {runs, fame, loot, potions}) grid.add(tile);
        note.setVisible(false);
        unreadable.setVisible(false);
        banners.setVisible(false);
        // The stale and unreadable-sessions banners sit above the tiles and take no space (nor gap) while hidden.
        content = HomeViews.named(HomeViews.stack(0, HomeViews.beside(grid, banners, BorderLayout.NORTH, Tokens.S)), "home-today-content");
        status(HomeViews.LOADING, "Progress: loading");
    }

    HomeArchive.Window selected() { return window.selected() == 1 ? HomeArchive.Window.SESSION : HomeArchive.Window.TODAY; }

    /** EDT only; skips a section that shows exactly what is shown. */
    void apply(HomeModel.Today today) {
        HomeArchive.Window chosen = selected();
        if (today != null && today.equals(shown) && chosen == shownWindow) return;
        shown = today;
        shownWindow = chosen;
        String label = chosen == HomeArchive.Window.SESSION ? "This session" : "Today";
        // A result for the other window predates the switch; its replacement is already being read.
        if (today == null || today.state() == HomeModel.State.LOADING || today.window() != null && today.window() != chosen) {
            status(HomeViews.LOADING, "Progress: loading");
            return;
        }
        if (today.state() == HomeModel.State.UNAVAILABLE) {
            unavailable(text(today.reason(), "Saved history is not available."), "Progress: unavailable");
            return;
        }
        HomeArchive.Totals totals = today.totals();
        if (today.state() == HomeModel.State.EMPTY || totals == null) {
            body(chosen == HomeArchive.Window.SESSION ? emptySession : emptyToday);
            getAccessibleContext().setAccessibleName("Progress: " + label + ", nothing recorded yet");
            getAccessibleContext().setAccessibleDescription(null);
            return;
        }
        boolean stale = today.state() == HomeModel.State.STALE;
        // Sessions that could not be read may hold part of this period: the totals that exist are partial, with a warn line.
        String partial = totals.unreadableSessions() == 0 ? null : unreadableText(totals.unreadableSessions());
        String source = label + " from saved history";
        // Without any saved visit the run counts are unknown, not zero (spec §1); visits but no dungeon run is a real zero.
        boolean visited = totals.runsRecorded();
        runs.setValue(visited ? count(totals.runsCompleted(), "Completed dungeon runs, " + source, stale, partial) : DisplayValue.unknown(NO_RUNS),
            visited ? totals.runsEntered() + " entered" : null);
        Long gain = totals.fameGained();
        fame.setValue(gain == null ? DisplayValue.unknown("No fame readings in this window yet") : gained(gain, source, stale, partial), rate(totals));
        boolean trended = gain != null && totals.fameSeries() != null && totals.fameSeries().length > 1;
        trend.setValues(trended ? totals.fameSeries() : null);
        trend.setVisible(trended);
        // Without any saved loot the counts are unknown, not zero (spec §1).
        boolean looted = totals.lootRecorded();
        loot.setValue(looted ? count(totals.untiered() + totals.setTiered(), "UT and ST drops, " + source, stale, partial) : DisplayValue.unknown(NO_LOOT),
            looted ? totals.untiered() + " UT · " + totals.setTiered() + " ST · " + totals.whiteBags() + (totals.whiteBags() == 1 ? " white bag" : " white bags") : null);
        potions.setValue(looted ? count(totals.potions(), "Potion drops, " + source, stale, partial) : DisplayValue.unknown(NO_LOOT), null);
        String reason = stale ? text(today.reason(), "Showing the last successful read of saved history.") : "";
        note.setText(reason, true);
        note.setVisible(!reason.isEmpty());
        if (partial != null) unreadable.setText(partial, true);
        unreadable.setVisible(partial != null);
        banners.setVisible(note.isVisible() || unreadable.isVisible());
        explain(label + ": saved history from " + DisplayFormat.formatTimestamp(totals.from()) + " to " + DisplayFormat.formatTimestamp(totals.until())
            + ". Runs are dungeon visits in the runs history; completed uses the same rule as the Runs page. Fame is each character's gain"
            + " between its first and last fame reading (decreases and new characters are ignored); fame/hour divides it by the time"
            + " covered by readings in each session. Notable loot counts UT and ST drops by item tier and white bags by bag type; potions"
            + " use the item potion flag. Run counts are unknown when no visit was saved in this period, and loot counts when no loot was."
            + " Saved sessions whose details cannot be read are left out and counted in a warning; the totals are then partial.");
        getAccessibleContext().setAccessibleName("Progress: " + label + (stale ? ", last successful read" : "") + (partial != null ? ", partial" : ""));
        String described = (reason + " " + (partial == null ? "" : partial)).trim();
        getAccessibleContext().setAccessibleDescription(described.isEmpty() ? null : described);
        body(content);
    }

    /** Stale wins; then partial (unreadable sessions) with the missing part as its detail; else a plain count. */
    private static DisplayValue count(long value, String source, boolean stale, String partial) {
        if (stale) return DisplayValue.stale(DisplayFormat.formatInteger(value), source);
        if (partial != null) return DisplayValue.partial(DisplayFormat.formatInteger(value), partial);
        return DisplayValue.count(value, source, null);
    }

    private static DisplayValue gained(long gain, String source, boolean stale, String partial) {
        String text = (gain > 0 ? "+" : "") + DisplayFormat.formatInteger(gain);
        if (stale) return DisplayValue.stale(text, "Fame gained, " + source);
        if (partial != null) return DisplayValue.partial(text, partial);
        return gain == 0 ? DisplayValue.zero("Fame gained, " + source) : DisplayValue.known(text, "Fame gained, " + source);
    }

    /** "1 saved session could not be read", "3 saved sessions could not be read". */
    static String unreadableText(int sessions) { return sessions + (sessions == 1 ? " saved session" : " saved sessions") + " could not be read"; }

    private static String rate(HomeArchive.Totals totals) {
        if (totals.famePerHour() != null) return DisplayFormat.formatInteger(Math.round(totals.famePerHour())) + " fame/hour";
        return totals.fameGained() == null ? null : "Fame/hour after 10 minutes of readings";
    }

    private static String text(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }

    /**
     * A tile that opens a page, as Home's cards and run rows do (P6a: Notable loot opens Loot › Highlights): click (on its labels
     * too, which carry tooltips and so receive their own mouse events), Enter or Space; focusable, with the accent focus ring and a
     * hover wash; spoken as a button whose name ends with the action. Until {@link #onOpen} it is a plain tile.
     */
    static final class OpenTile extends StatTile {
        // No initializers: StatTile's constructor already calls setValue, before this class's field initializers would run.
        private String action, subline;
        private Runnable open;
        private boolean hovered;

        OpenTile(String label) { super(label); }

        void onOpen(String name, Runnable action) {
            this.action = Objects.requireNonNull(name, "name");
            open = Objects.requireNonNull(action, "action");
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            listen(this, new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) open.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover(true); }
                @Override public void mouseExited(MouseEvent e) { hover(contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), OpenTile.this))); }
            });
            for (KeyStroke key : new KeyStroke[] {KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)})
                getInputMap(WHEN_FOCUSED).put(key, "open-tile");
            getActionMap().put("open-tile", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { open.run(); }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
            setValue(value(), subline);   // the spoken name gains the action
        }

        @Override public void setValue(DisplayValue shown, String sub) {
            subline = sub;
            super.setValue(shown, sub);
            if (action != null) getAccessibleContext().setAccessibleName(getAccessibleContext().getAccessibleName() + ". " + action);
        }

        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleJPanel() {
                @Override public AccessibleRole getAccessibleRole() { return open != null ? AccessibleRole.PUSH_BUTTON : super.getAccessibleRole(); }
            };
            return accessibleContext;
        }

        private void hover(boolean value) {
            if (hovered == value) return;
            hovered = value;
            repaint();
        }

        private static void listen(Component component, MouseListener mouse) {
            component.addMouseListener(mouse);
            if (component instanceof Container) for (Component child : ((Container) component).getComponents()) listen(child, mouse);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);   // the raised surface
            boolean focused = open != null && isFocusOwner();
            if (!hovered && !focused) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1, height = getHeight() - 1;
            if (hovered) {
                g.setColor(Tokens.blend(Tokens.color(Tokens.Role.RAISED), Tokens.color(Tokens.Role.ACCENT_WASH), .6f));
                g.fillRoundRect(0, 0, width, height, Tokens.ARC_CARD, Tokens.ARC_CARD);
            }
            if (focused) {
                g.setColor(Tokens.color(Tokens.Role.ACCENT));
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, width - 2, height - 2, Tokens.ARC_CARD, Tokens.ARC_CARD);
            }
            g.dispose();
        }
    }
}
