package tomato.gui.runs;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.gui.activity.ActivityRoutes;
import tomato.gui.activity.CombatTimelineChart;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.loot.haul.HaulView;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;
import util.PropertiesManager;

/**
 * The run recap (spec §6.3): one exact saved run, read by {@link RunRecapBuilder} off the EDT and applied here. It replaces the
 * workbench text as the way to read a run.
 * - "‹ Runs" ({@code run-recap-back}) is always shown, also while loading and for an unavailable or unreadable run.
 * - A run that is not in saved history shows {@link #UNAVAILABLE_TITLE} with the archive's wording; a run whose read failed shows
 *   {@link #FAILED_TITLE} with the failure (a damaged file is not an absence). Both offer the way back.
 * - Header: portal sprite, dungeon, outcome chip, entry time and observed span as the run's card writes them (the exact values in
 *   the tooltips), party, character, and for an in-progress run when it was read, with "·" between two facts on one line only;
 *   links "Open in Runs table", "Open in Loot" and "Open in Timeline" carry the exact visit ({@link #onOpenRoute}).
 * - A tile row ({@code run-recap-tile-<id>}): your DPS and rank, damage share, deaths, fame, loot and exalt progress; unknown is
 *   "—" with its reason as the tooltip.
 * - Collapsible sections, each remembering its state ({@code ui.collapse.run-recap-<id>}): Damage ({@link RunDamagePanel}) and
 *   Loot open, Players, Resources and Timeline closed, Evidence (the workbench's text) closed and in Analyst only. A section
 *   without content shows its one-line reason instead of hiding. Loot and Players rows give their labels the section's widest
 *   label width, so the item slots line up from row to row.
 * - The sections can be reordered ({@link #ORDER}): each header's context menu (right-click, Shift+F10 or the context-menu key)
 *   offers Move up, Move down and Reset order, and Ctrl+Shift+Up/Down moves a focused header, as the sidebar's rows move. A move
 *   skips a hidden section (Evidence in Simple), which keeps its place; moving never rebuilds a section or changes its open state.
 * Each section rebuilds only when its own part of the model changed. EDT only.
 */
public final class RunRecapView extends JPanel {
    public static final String LOADING = "Loading this run…";
    public static final String UNAVAILABLE_TITLE = "This run is not in saved history";
    /**
     * The title of a run whose read failed. The route's worker ({@code RunsRouteTarget}) hands a failed read over as an unavailable
     * model whose reason starts with these words ("This run could not be read from saved history: …"); that reason is the body.
     */
    public static final String FAILED_TITLE = "This run could not be read";
    /** Section ids; each Collapsible is named {@code run-recap-<id>} and remembers {@code ui.collapse.run-recap-<id>}. */
    public static final String DAMAGE = "damage", LOOT = "loot", PLAYERS = "players", RESOURCES = "resources", TIMELINE = "timeline",
        EVIDENCE = "evidence";
    /**
     * The section order preference: section ids, comma-separated. Ids that are not sections are ignored, a repeat counts once, and
     * sections it lacks follow in {@link #DEFAULT_ORDER}; empty (Reset order) is the default order.
     */
    public static final String ORDER = "ui.order.run-recap";
    /** The sections' default order; Evidence is Analyst only wherever it stands. */
    public static final List<String> DEFAULT_ORDER = List.of(DAMAGE, LOOT, PLAYERS, RESOURCES, TIMELINE, EVIDENCE);
    /** The page's rows: the header (0) and the tiles (1), then the sections in the user's order. */
    private static final int FIRST_SECTION_ROW = 2;
    private static final String LOADING_CARD = "loading", UNAVAILABLE_CARD = "unavailable", FAILED_CARD = "failed", RECAP_CARD = "recap";
    private static final String[] TILE_IDS = {RunRecapModel.Tile.DPS, RunRecapModel.Tile.SHARE, RunRecapModel.Tile.DEATHS,
        RunRecapModel.Tile.FAME, RunRecapModel.Tile.LOOT, RunRecapModel.Tile.EXALT};
    private static final String[] TILE_LABELS = {"Your DPS", "Damage share", "Deaths", "Fame", "Loot", "Exalt progress"};
    /** The Timeline section's table shows this many rows before it scrolls. */
    private static final int EVENT_ROWS = 10;

    /** Reasons and notes, in the muted text color (re-applied on a theme change). */
    private final List<JTextArea> muted = new ArrayList<>();
    private final KitButton back = KitButton.ghost("‹ Runs");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final Banner status = new Banner("run-recap-status");
    private final EmptyState unavailable, failed;
    private final JScrollPane scroll;
    // Header
    private final JLabel portal = new JLabel();
    private final KitText map = new KitText(" ", Type.title(), Tokens.Role.TEXT);
    private final Chip outcome = new Chip(" ", Tokens.Tone.NEUTRAL);
    private final KitText entered = fact("run-recap-entered"), duration = fact("run-recap-duration"), party = fact("run-recap-party"),
        character = fact("run-recap-character"), asOf = fact("run-recap-asof");
    private final Facts facts = new Facts();
    private final KitButton openTable = link("Open in Runs table", "run-recap-open-table"), openLoot = link("Open in Loot", "run-recap-open-loot"),
        openTimeline = link("Open in Timeline", "run-recap-open-timeline");
    private final Map<KitButton, Route> routesByLink = new HashMap<>();
    private final Map<String, StatTile> tiles = new LinkedHashMap<>();
    private final Map<String, Collapsible> sections = new LinkedHashMap<>();
    /** Every section id in the user's order, hidden ones included ({@link #ORDER}). */
    private final List<String> order = new ArrayList<>();
    /** The scrolling page: header, tiles, then the sections as {@link #arrange} places them. */
    private final JPanel content = new JPanel(new GridBagLayout());
    private final RunDamagePanel damage;
    // Loot
    private final KitText lootSummary = KitText.caption(" ");
    private final JTextArea lootReason = reason("run-recap-loot-reason");
    private final JPanel lootBags = column("run-recap-loot-bags");
    private final HaulView lootHaul = new HaulView(HaulView.Mode.COMPACT);
    // Players
    private final KitText playersCount = KitText.caption(" "), playersDamageLabel = KitText.caption(RunRecapModel.Players.INSPECT_DAMAGE);
    private final JTextArea playersReason = reason("run-recap-players-reason"), playersDamageReason = reason("run-recap-players-damage-reason");
    private final JPanel playerRows = column("run-recap-player-rows");
    // Resources
    private final CombatTimelineChart resourcesChart = new CombatTimelineChart();
    private final JTextArea resourcesReason = reason("run-recap-resources-reason");
    private final JScrollPane resourcesScroll;
    private final JTextArea resourcesHelp = ContentStyle.wrappingText("HP and MP samples of your character, with the conditions observed. "
        + "Focus the chart to inspect samples with the arrow keys; + and − zoom.");
    // Timeline
    private final DefaultTableModel events = new DefaultTableModel(new Object[] {"Time", "Kind", "Event"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable eventTable = new JTable(events) {
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
            return row < 0 || column < 0 ? null : Objects.toString(getValueAt(row, column), null);
        }
    };
    private final JScrollPane eventScroll;
    private final JTextArea timelineReason = reason("run-recap-timeline-reason"), timelineNote = reason("run-recap-timeline-note");
    // Evidence
    private final JTextArea evidenceText = ContentStyle.wrappingText("");

    private Runnable backAction = () -> { };
    private Consumer<Route> routeAction = route -> { };
    private RunRecapModel model;
    private VisitRef ref;
    /** The run of the recap last shown; another run's recap starts at the top, a rebuilt one (another recording) keeps its place. */
    private VisitRef shownRef;
    private boolean loading;
    /** The parts each section last applied; a section rebuilds only when its part changed. */
    private RunRecapModel.Loot shownLoot;
    private RunRecapModel.Players shownPlayers;
    private RunRecapModel.Timeline shownTimeline;

    /** The recap in the application's display mode. */
    public RunRecapView() { this(DisplayModeModel.application()); }

    public RunRecapView(DisplayModeModel mode) {
        super(new BorderLayout(0, Tokens.XS));
        Objects.requireNonNull(mode, "mode");
        setName("run-recap");
        setBorder(BorderFactory.createEmptyBorder(Tokens.S, Tokens.S, 0, Tokens.S));
        back.setName("run-recap-back");
        back.setToolTipText("Back to the saved runs");
        back.getAccessibleContext().setAccessibleName("Back to Runs");
        back.addActionListener(e -> backAction.run());
        JPanel backRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        backRow.setOpaque(false);
        backRow.add(back);
        add(backRow, BorderLayout.NORTH);

        status.setText(LOADING);
        unavailable = new EmptyState(UNAVAILABLE_TITLE, " ", backButton("run-recap-unavailable-back"));
        unavailable.setName("run-recap-unavailable");
        failed = new EmptyState(FAILED_TITLE, " ", backButton("run-recap-failed-back"));
        failed.setName("run-recap-failed");

        damage = new RunDamagePanel(mode);
        JComponent header = header();
        JPanel tileGrid = ContentStyle.responsiveGrid(6, 140, Tokens.S, true);   // wraps to fewer columns before a value is cut
        tileGrid.setOpaque(false);
        tileGrid.setName("run-recap-tiles");
        for (int i = 0; i < TILE_IDS.length; i++) {
            StatTile tile = new StatTile(TILE_LABELS[i]);
            tile.setName("run-recap-tile-" + TILE_IDS[i]);
            tiles.put(TILE_IDS[i], tile);
            tileGrid.add(tile);
        }
        resourcesChart.setName("run-recap-resources-chart");
        resourcesScroll = new JScrollPane(resourcesChart) {
            @Override public Dimension getPreferredSize() {
                Insets insets = getInsets();
                return new Dimension(120, Math.min(resourcesChart.getPreferredSize().height, 420) + insets.top + insets.bottom);
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        resourcesScroll.setName("run-recap-resources-scroll");
        resourcesScroll.getVerticalScrollBar().setUnitIncrement(24);
        resourcesHelp.setName("run-recap-resources-help");
        resourcesHelp.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        muted.add(resourcesHelp);
        eventTable.setName("run-recap-timeline-table");
        eventTable.getAccessibleContext().setAccessibleName("Timeline events of this run");
        ContentStyle.table(eventTable, ContentStyle.Density.DENSE);
        eventTable.getTableHeader().setReorderingAllowed(false);
        KitTables.apply(eventTable, ColumnKind.TIME_RELATIVE, ColumnKind.CLASS, ColumnKind.TEXT);
        eventScroll = new JScrollPane(eventTable) {
            @Override public Dimension getPreferredSize() {
                Insets insets = getInsets();
                int header = eventTable.getTableHeader() == null ? 0 : eventTable.getTableHeader().getPreferredSize().height;
                return new Dimension(120, header + Math.max(1, Math.min(EVENT_ROWS, eventTable.getRowCount())) * eventTable.getRowHeight()
                    + insets.top + insets.bottom);
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        eventScroll.setName("run-recap-timeline-scroll");
        evidenceText.setName("run-recap-evidence-text");
        evidenceText.getAccessibleContext().setAccessibleName("Run evidence");

        lootSummary.setName("run-recap-loot-summary");
        rebuild(lootBags, rows -> rows.add(lootHaul));
        playersCount.setName("run-recap-players-count");
        playersDamageLabel.setName("run-recap-players-damage-label");
        section(DAMAGE, "Damage", KitLayouts.stack(Tokens.S, damage), true);
        section(LOOT, "Loot", KitLayouts.stack(Tokens.S, lootSummary, lootReason, lootBags), true);
        section(PLAYERS, "Players", KitLayouts.stack(Tokens.S, playersCount, playersReason, playersDamageLabel, playersDamageReason, playerRows), false);
        section(RESOURCES, "Resources", KitLayouts.stack(Tokens.S, resourcesReason, resourcesHelp, resourcesScroll), false);
        section(TIMELINE, "Timeline", KitLayouts.stack(Tokens.S, timelineReason, timelineNote, eventScroll), false);
        section(EVIDENCE, "Evidence", KitLayouts.stack(Tokens.S, evidenceText), false);

        // The page stacks its rows as KitLayouts.stack does (full width, Tokens.M apart, hidden rows take no space); the sections'
        // rows follow the saved order and move in place (arrange), so a move keeps each section's content, state and focus.
        content.setOpaque(false);
        content.setName("run-recap-page");
        List<JComponent> rows = new ArrayList<>(List.of(header, tileGrid));
        rows.addAll(sections.values());
        GridBagConstraints row = new GridBagConstraints();
        row.gridx = 0; row.weightx = 1; row.fill = GridBagConstraints.HORIZONTAL; row.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < rows.size(); i++) {
            row.gridy = i;
            row.insets = new Insets(i == 0 ? 0 : Tokens.M, 0, 0, 0);
            content.add(rows.get(i), row);
        }
        row.gridy = rows.size(); row.weighty = 1; row.insets = new Insets(0, 0, 0, 0);
        content.add(Box.createVerticalGlue(), row);
        order.addAll(savedOrder(PropertiesManager.getProperty(ORDER)));
        arrange();
        Column page = new Column(content);
        scroll = new JScrollPane(page);
        scroll.setName("run-recap-scroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());   // not null: a live theme switch would put the outline back
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(32);
        scroll.getAccessibleContext().setAccessibleName("Run recap");
        JPanel recap = new JPanel(new BorderLayout());
        recap.setOpaque(false);
        recap.setName("run-recap-content");
        recap.add(scroll);
        body.setOpaque(false);
        body.add(status, LOADING_CARD);
        body.add(unavailable, UNAVAILABLE_CARD);
        body.add(failed, FAILED_CARD);
        body.add(recap, RECAP_CARD);
        add(body, BorderLayout.CENTER);
        cards.show(body, LOADING_CARD);
        // Provenance is diagnostic (spec §3.2): the workbench's evidence text is for Analyst only.
        mode.bind(this, value -> {
            sections.get(EVIDENCE).setVisible(value == DisplayModeModel.Mode.ANALYST);
            revalidate();
            repaint();
        });
    }

    // ---- API ----

    /** What "‹ Runs" and the unavailable and failed states' buttons do. */
    public void onBack(Runnable action) { backAction = Objects.requireNonNull(action, "action"); }
    /** What choosing another recording in the Damage section asks for: the recording id to rebuild the model with. */
    public void onRecording(Consumer<String> listener) { damage.onRecording(listener); }
    /** What the header's links open: exact-visit routes to the Runs table, Loot and Timeline. */
    public void onOpenRoute(Consumer<Route> action) { routeAction = Objects.requireNonNull(action, "action"); }

    /** EDT: shows "Loading this run…" for {@code ref}; nothing of a previously shown run stays visible. */
    public void showLoading(VisitRef ref) {
        requireEdt();
        this.ref = Objects.requireNonNull(ref, "ref");
        model = null;
        loading = true;
        status.setText(LOADING);
        cards.show(body, LOADING_CARD);
        revalidate();
        repaint();
    }

    /**
     * EDT: applies a model built off the EDT: the recap, the unavailable state with the archive's wording, or the failed state when
     * the reason is a failed read ({@link #FAILED_TITLE}).
     */
    public void show(RunRecapModel next) {
        requireEdt();
        Objects.requireNonNull(next, "model");
        model = next;
        ref = next.ref();
        loading = false;
        if (!next.available()) {
            boolean unreadable = next.unavailable().startsWith(FAILED_TITLE);
            (unreadable ? failed : unavailable).setBody(next.unavailable());
            cards.show(body, unreadable ? FAILED_CARD : UNAVAILABLE_CARD);
            revalidate();
            repaint();
            return;
        }
        applyHeader(next);
        for (int i = 0; i < TILE_IDS.length; i++) {
            RunRecapModel.Tile tile = next.tile(TILE_IDS[i]);
            tiles.get(TILE_IDS[i]).setValue(tile == null ? DisplayValue.unknown("Not available for this run.") : tile.value(),
                tile == null ? null : tile.subline());
        }
        damage.show(next.damage());
        applyLoot(next.loot());
        applyPlayers(next.players());
        applyResources(next.resources());
        applyTimeline(next.timeline());
        if (!next.evidence().equals(evidenceText.getText())) { evidenceText.setText(next.evidence()); evidenceText.setCaretPosition(0); }
        cards.show(body, RECAP_CARD);
        if (!next.ref().equals(shownRef)) SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new Point(0, 0)));
        shownRef = next.ref();
        revalidate();
        repaint();
    }

    /** The shown model, or null while loading (or before the first). */
    public RunRecapModel model() { return model; }
    /** The run shown or loading, or null before the first. */
    public VisitRef ref() { return ref; }
    /** True from {@link #showLoading} until a model for it is shown. */
    public boolean loading() { return loading; }
    /** Opens the Damage section (explicit navigation to the damage breakdown); the choice is remembered as a user's would be. */
    public void expandDamage() { sections.get(DAMAGE).setExpanded(true); }

    // ---- section order ----

    /** Every section id in the page's order, hidden ones (Evidence in Simple) included. */
    public List<String> sectionOrder() { return List.copyOf(order); }

    /**
     * Moves a section up ({@code delta} < 0) or down past as many of the sections shown; a hidden section is not a place to move to
     * and keeps its place in the order. The order is saved ({@link #ORDER}); nothing happens at either end. EDT.
     */
    public void moveSection(String id, int delta) {
        requireEdt();
        if (!canMove(id, delta)) return;
        List<String> shown = shownSections();
        String neighbor = shown.get(shown.indexOf(id) + delta);
        order.remove(id);
        int at = order.indexOf(neighbor);
        order.add(delta > 0 ? at + 1 : at, id);
        PropertiesManager.setProperties(ORDER, String.join(",", order));
        arrange();
        keepInView(id);
    }

    /** Back to {@link #DEFAULT_ORDER}; the saved order is forgotten (an empty {@link #ORDER}). Open states are unchanged. EDT. */
    public void resetSectionOrder() {
        requireEdt();
        order.clear();
        order.addAll(DEFAULT_ORDER);
        PropertiesManager.setProperties(ORDER, "");
        arrange();
    }

    /** The saved order as section ids: unknown ids ignored, a repeat counted once, missing sections appended in the default order. */
    static List<String> savedOrder(String saved) {
        List<String> result = new ArrayList<>();
        if (saved != null)
            for (String id : saved.split(",")) if (DEFAULT_ORDER.contains(id.trim()) && !result.contains(id.trim())) result.add(id.trim());
        for (String id : DEFAULT_ORDER) if (!result.contains(id)) result.add(id);
        return result;
    }

    private boolean canMove(String id, int delta) {
        List<String> shown = shownSections();
        int from = shown.indexOf(id), to = from + delta;
        return from >= 0 && delta != 0 && to >= 0 && to < shown.size();
    }

    /** The sections shown, in order: all but Evidence in Simple (sections without content show their reason, never hide). */
    private List<String> shownSections() {
        List<String> shown = new ArrayList<>();
        for (String id : order) if (sections.get(id).isVisible()) shown.add(id);
        return shown;
    }

    /**
     * Places the sections in {@link #order} below the header and tiles. Each keeps its component (no remove and add): its row and
     * its place among the page's children move, so focus, open state and content stay, and focus traversal and assistive
     * technology read the sections in the order shown.
     */
    private void arrange() {
        GridBagLayout layout = (GridBagLayout) content.getLayout();
        for (int i = 0; i < order.size(); i++) {
            Collapsible section = sections.get(order.get(i));
            GridBagConstraints c = layout.getConstraints(section);
            c.gridy = FIRST_SECTION_ROW + i;
            layout.setConstraints(section, c);
            content.setComponentZOrder(section, FIRST_SECTION_ROW + i);
        }
        content.revalidate();
        content.repaint();
    }

    /** After a move, the moved header stays in view and keeps (or takes) the focus, as a moved sidebar row does. */
    private void keepInView(String id) {
        KitButton header = sections.get(id).toggle();
        SwingUtilities.invokeLater(() -> {
            if (!header.isShowing()) return;
            scroll.validate();
            header.scrollRectToVisible(new Rectangle(header.getSize()));
            header.requestFocusInWindow();
        });
    }

    /** A section header's menu: Move up, Move down (disabled at either end) and Reset order (disabled in the default order). */
    JPopupMenu sectionMenu(String id) {
        String title = sections.get(id).toggle().getText();
        JPopupMenu menu = new JPopupMenu(title);
        menu.setName("run-recap-section-menu");
        menu.getAccessibleContext().setAccessibleName(title + " section options");
        menu.add(menuItem("run-recap-menu-move-up", "Move up", canMove(id, -1), () -> moveSection(id, -1)));
        menu.add(menuItem("run-recap-menu-move-down", "Move down", canMove(id, 1), () -> moveSection(id, 1)));
        menu.addSeparator();
        menu.add(menuItem("run-recap-menu-reset", "Reset order", !order.equals(DEFAULT_ORDER), () -> { resetSectionOrder(); keepInView(id); }));
        return menu;
    }

    private void showSectionMenu(String id, Point at) {
        KitButton header = sections.get(id).toggle();
        if (!header.isShowing()) return;
        JPopupMenu menu = sectionMenu(id);
        Point where = at != null ? at : new Point(0, header.getHeight());
        menu.show(header, where.x, where.y);
        // Keyboard users start on the first available action, as in the sidebar's menu.
        for (Component item : menu.getComponents())
            if (item instanceof JMenuItem && item.isEnabled()) {
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {menu, (MenuElement) item});
                break;
            }
    }

    private static JMenuItem menuItem(String name, String text, boolean enabled, Runnable run) {
        JMenuItem item = new JMenuItem(text);
        item.setName(name);
        item.setEnabled(enabled);
        item.addActionListener(e -> run.run());
        return item;
    }

    private static Action action(Runnable run) {
        return new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { run.run(); } };
    }

    // ---- header ----

    private JComponent header() {
        portal.setName("run-recap-portal");
        map.setName("run-recap-map");
        outcome.setName("run-recap-outcome");
        JPanel identity = new JPanel(new BorderLayout(Tokens.S, 0));
        identity.setOpaque(false);
        identity.add(portal, BorderLayout.WEST);
        identity.add(map, BorderLayout.CENTER);
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        chip.setOpaque(false);
        chip.add(outcome);
        facts.setName("run-recap-facts");
        for (KitText fact : new KitText[] {entered, duration, party, character, asOf}) facts.add(fact);
        JPanel links = ContentStyle.controls();
        links.setOpaque(false);
        links.setName("run-recap-links");
        links.add(openTable); links.add(openLoot); links.add(openTimeline);
        JPanel header = KitLayouts.stack(Tokens.XS, KitLayouts.spread(Tokens.S, identity, chip), facts, links);
        header.setName("run-recap-header");
        return header;
    }

    private void applyHeader(RunRecapModel next) {
        RunRecapModel.Header h = next.header();
        portal.setIcon(Sprites.sprite(h.portalId(), 40));
        portal.getAccessibleContext().setAccessibleName(h.mapName() + " portal");
        map.setText(h.mapName());
        outcome.setText(h.outcome().label());
        outcome.setTone(h.outcome().tone());
        // The card's clock and span (read against the model's own time, as the feed reads against its page's); exact in the tooltip.
        set(entered, h.entered() == null ? "Entered —" : entered(h.entered(), ZoneId.systemDefault(), next.capturedAt()),
            h.entered() == null ? "No entry time was saved for this run"
                : "Entered " + DisplayFormat.formatTimestamp(h.entered()) + ", when the run's area was entered");
        set(duration, h.durationMs() == null ? "Duration —" : RunFeedModel.duration(h.durationMs()) + " observed",
            h.durationMs() == null ? "The run's observed span is unknown"
                : "Observed " + KitFormat.duration(h.durationMs()) + " from entry to last seen; not a verified clear time");
        set(party, h.partySize() == null ? "Party —" : "Party " + h.partySize(),
            h.partySize() == null ? "Party not observed" : "The observed RotMG party");
        set(character, h.character() == null ? "Character —" : h.character(),
            h.character() == null ? "No fame reading was recorded during this run, so the character used is unknown"
                : "The character of the fame readings recorded during this run");
        boolean live = h.outcome() == RunOutcome.IN_PROGRESS;
        set(asOf, live ? "Read at " + DisplayFormat.formatTimestamp(Instant.ofEpochMilli(next.capturedAt()), DisplayFormat.TimestampMode.TIME) : " ",
            live ? "An in-progress run is read from its saved checkpoint (about every 10 s); open it again for newer facts" : null);
        asOf.setVisible(live);
        VisitRef exact = next.ref();
        route(openTable, ActivityRoutes.visit(Destination.RUNS, exact));
        route(openLoot, ActivityRoutes.visit(Destination.LOOT, exact));
        route(openTimeline, ActivityRoutes.visit(Destination.TIMELINE, exact));
    }

    /**
     * "Entered 14:32" today, "Entered yesterday 22:10", else "Entered 13 Jan 14:32": the run card's clock
     * ({@link RunCardRenderer#time}) in {@code zone} on {@code now}'s day.
     */
    static String entered(long entered, ZoneId zone, long now) {
        String time = RunCardRenderer.time(entered, zone, now);
        return "Entered " + (time.startsWith("Yesterday ") ? "yesterday " + time.substring("Yesterday ".length()) : time);
    }

    /** The header facts as laid out, one string per line with " · " between facts (tests). */
    List<String> factLines() { return facts.lines(); }

    private void route(KitButton link, Route route) {
        routesByLink.put(link, route);
        link.setEnabled(route != null);
        link.setToolTipText(route == null ? link.getText() + " needs a saved session and visit; this run's reference is not one, so nothing is matched by name or time"
            : link.getText() + " for this exact session and visit");
        link.getAccessibleContext().setAccessibleDescription(link.getToolTipText());
    }

    private KitButton link(String text, String name) {
        KitButton link = KitButton.ghost(text);
        link.setName(name);
        link.addActionListener(e -> { Route route = routesByLink.get(link); if (route != null) routeAction.accept(route); });
        return link;
    }

    private static KitText fact(String name) {
        KitText fact = KitText.caption(" ");
        fact.setName(name);
        return fact;
    }

    private static void set(KitText label, String text, String tooltip) {
        label.setText(text);
        label.setToolTipText(tooltip);
        label.getAccessibleContext().setAccessibleDescription(tooltip);
    }

    // ---- sections ----

    private void section(String id, String title, JComponent content, boolean open) {
        Collapsible section = new Collapsible("run-recap-" + id, title, content, open);
        section.setName("run-recap-" + id);
        sections.put(id, section);
        // The header reorders its section: right-click, Shift+F10 or the context-menu key opens its menu; Ctrl+Shift+Up/Down moves it.
        KitButton header = section.toggle();
        header.setToolTipText("Ctrl+Shift+Up or Down moves this section; right-click or Shift+F10 for Move up, Move down and Reset order");
        header.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) showSectionMenu(id, e.getPoint()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showSectionMenu(id, e.getPoint()); }
        });
        InputMap keys = header.getInputMap(WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "run-recap-section-menu");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "run-recap-section-menu");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "run-recap-move-up");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "run-recap-move-down");
        header.getActionMap().put("run-recap-section-menu", action(() -> showSectionMenu(id, null)));
        header.getActionMap().put("run-recap-move-up", action(() -> moveSection(id, -1)));
        header.getActionMap().put("run-recap-move-down", action(() -> moveSection(id, 1)));
    }

    private void title(String id, String text) {
        KitButton toggle = sections.get(id).toggle();
        if (!text.equals(toggle.getText())) toggle.setText(text);
    }

    private void applyLoot(RunRecapModel.Loot loot) {
        if (loot.equals(shownLoot)) return;
        shownLoot = loot;
        boolean any = !loot.bags().isEmpty();
        List<HaulModel.Bag> bags = new ArrayList<>();
        for (RunRecapModel.Loot.Bag bag : loot.bags()) bags.add(new HaulModel.Bag(bag.bag(), bag.time(), bag.dropper(), bag.items()));
        HaulModel haul = HaulModel.of(null, bags);
        lootSummary.setText(any ? haul.tally() : " ");
        lootSummary.setVisible(any);
        text(lootReason, any ? null : loot.reason());
        lootHaul.show(haul, null);
        lootBags.setVisible(any);
        title(LOOT, any ? "Loot · " + loot.count() + (loot.count() == 1 ? " item" : " items") : "Loot");
    }

    private void applyPlayers(RunRecapModel.Players players) {
        if (players.equals(shownPlayers)) return;
        shownPlayers = players;
        boolean any = !players.players().isEmpty();
        int seen = players.inspectedPlayerCount();
        playersCount.setText(seen == 1 ? "1 player seen in this run" : seen + " players seen in this run");
        playersCount.setVisible(seen > 0);
        text(playersReason, any ? null : players.reason());
        playersDamageLabel.setVisible(any);
        text(playersDamageReason, any ? players.damageReason() : null);
        SlotColumns columns = new SlotColumns();
        rebuild(playerRows, rows -> { for (RunRecapModel.Players.Player player : players.players()) rows.add(playerRow(player, players.damageReason(), columns)); });
        title(PLAYERS, any ? "Players · " + players.players().size() : "Players");
    }

    private static JComponent playerRow(RunRecapModel.Players.Player player, String damageReason, SlotColumns columns) {
        KitText name = KitText.body(player.name() == null ? "Unnamed player" : player.name());
        name.setName("run-recap-player-name");
        name.setIcon(Sprites.sprite(player.classType(), 24));
        name.setIconTextGap(Tokens.S);
        KitText type = KitText.caption(player.className() == null || player.className().isEmpty() ? "Class unknown" : player.className());
        type.setName("run-recap-player-class");
        JPanel lead = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
        lead.setOpaque(false);
        lead.add(name);
        lead.add(type);
        JPanel slots = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0));
        slots.setOpaque(false);
        List<Integer> equipment = player.equipment();
        for (int i = 0; i < equipment.size(); i++) {
            Integer item = equipment.get(i);
            ItemSlot slot = new ItemSlot(24);
            EnchantInfo enchant = player.enchants().get(i).state() == EnchantInfo.State.NOT_RECORDED ? null : player.enchants().get(i);
            if (item == null) slot.setUnknown(); else slot.setItem(item, ItemTiers.label(item), enchant);
            slots.add(slot);
        }
        Long inspect = player.inspectDamage();
        KitText damage = KitText.caption("Inspect damage " + (inspect == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(inspect)));
        damage.setName("run-recap-player-damage");
        damage.setToolTipText(inspect == null ? damageReason : RunRecapModel.Players.INSPECT_DAMAGE);
        JPanel row = columns.row(lead, slots, damage);
        row.setName("run-recap-player");
        row.getAccessibleContext().setAccessibleName(name.getText() + ", " + type.getText() + ", " + damage.getText());
        return row;
    }

    private void applyResources(RunRecapModel.Resources resources) {
        boolean any = resources.visit() != null;
        if (resourcesChart.getVisit() != resources.visit()) resourcesChart.setVisit(resources.visit());
        text(resourcesReason, resources.reason());
        resourcesScroll.setVisible(any);
        resourcesHelp.setVisible(any);
    }

    private void applyTimeline(RunRecapModel.Timeline timeline) {
        if (timeline.equals(shownTimeline)) return;
        shownTimeline = timeline;
        events.setRowCount(0);
        for (RunRecapModel.Timeline.Event event : timeline.events())
            events.addRow(new Object[] {DisplayFormat.formatTimestamp(Instant.ofEpochMilli(event.time()), DisplayFormat.TimestampMode.TIME), event.kind(), event.text()});
        boolean any = !timeline.events().isEmpty();
        eventScroll.setVisible(any);
        text(timelineReason, any ? null : timeline.reason());
        text(timelineNote, timeline.total() > timeline.events().size() && any
            ? "Showing the first " + DisplayFormat.formatInteger(timeline.events().size()) + " of " + DisplayFormat.formatInteger(timeline.total())
                + " saved events of this run; Open in Timeline lists them all." : null);
        title(TIMELINE, any ? "Timeline · " + DisplayFormat.formatInteger(timeline.total()) + (timeline.total() == 1 ? " event" : " events") : "Timeline");
    }

    // ---- helpers ----

    private KitButton backButton(String name) {
        KitButton button = KitButton.secondary("Back to Runs");
        button.setName(name);
        button.addActionListener(e -> backAction.run());
        return button;
    }

    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Use the run recap on the EDT"); }

    private JTextArea reason(String name) {
        JTextArea area = ContentStyle.wrappingText("");
        area.setName(name);
        area.setVisible(false);
        area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        muted.add(area);
        return area;
    }

    @Override public void updateUI() {
        super.updateUI();
        if (muted == null) return;   // null while JPanel's constructor runs
        for (JTextArea area : muted) area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    private static void text(JTextArea area, String value) {
        boolean show = value != null && !value.isEmpty();
        if (!(show ? value : "").equals(area.getText())) area.setText(show ? value : "");
        area.setVisible(show);
    }

    /** A full-width column of rows, top to bottom, {@link Tokens#S} apart (rebuilt per model). */
    private static JPanel column(String name) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setName(name);
        return panel;
    }

    private static void rebuild(JPanel column, Consumer<List<JComponent>> fill) {
        List<JComponent> rows = new ArrayList<>();
        fill.accept(rows);
        column.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < rows.size(); i++) {
            c.gridy = i;
            c.insets = new Insets(i == 0 ? 0 : Tokens.S, 0, 0, 0);
            column.add(rows.get(i), c);
        }
        column.setVisible(!rows.isEmpty());
        column.revalidate();
        column.repaint();
    }

    /** The recap's scrolling column: as wide as the viewport (nothing scrolls sideways), as tall as its rows. */
    private static final class Column extends JPanel implements Scrollable {
        Column(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(Tokens.XS, 0, Tokens.L, Tokens.XS));
            add(content, BorderLayout.NORTH);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 32; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return Math.max(32, (orientation == SwingConstants.VERTICAL ? r.height : r.width) - 32);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    /**
     * The header's facts as one wrapping line. Each fact is its own item and a "·" is painted between two facts on the same line
     * only, so when the facts wrap (a compact window, a large font) no line ends or starts with a separator. Hidden facts take no
     * space; the line count follows the width.
     */
    static final class Facts extends JPanel {
        /** The space between two facts on one line (the "·" is painted in its middle), and between two lines. */
        private static final String GAP = " · ";
        private static final int LINE_GAP = 2;
        /** The visible facts by line, as the last layout placed them. */
        private List<List<Component>> placed = List.of();

        Facts() {
            super(null);
            setOpaque(false);
        }

        @Override public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (changed) SwingUtilities.invokeLater(this::revalidate);   // the line count depends on this width
        }

        @Override public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int widest = 0, height = 0;
            List<List<Component>> lines = breaks(available());
            for (List<Component> line : lines) {
                widest = Math.max(widest, width(line));
                height += (height == 0 ? 0 : LINE_GAP) + height(line);
            }
            return new Dimension(widest + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        /** No width floor, so the header's stack never falls back to minimum sizes; the height is the wrapped one. */
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

        @Override public void doLayout() {
            Insets insets = getInsets();
            List<List<Component>> lines = breaks(available());
            int y = insets.top, gap = gap();
            for (List<Component> line : lines) {
                int height = height(line), x = insets.left;
                for (Component fact : line) {
                    Dimension size = fact.getPreferredSize();
                    fact.setBounds(x, y + (height - size.height) / 2, size.width, size.height);
                    x += size.width + gap;
                }
                y += height + LINE_GAP;
            }
            placed = lines;
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
                if (hints instanceof Map) g.addRenderingHints((Map<?, ?>) hints);
                g.setFont(Type.caption());
                g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
                FontMetrics metrics = g.getFontMetrics();
                for (List<Component> line : placed)
                    for (int i = 1; i < line.size(); i++) {
                        Component before = line.get(i - 1), after = line.get(i);
                        int middle = (before.getX() + before.getWidth() + after.getX()) / 2;
                        int baseline = after instanceof JComponent ? ((JComponent) after).getBaseline(after.getWidth(), after.getHeight()) : -1;
                        g.drawString("·", middle - metrics.stringWidth("·") / 2,
                            after.getY() + (baseline >= 0 ? baseline : (after.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent()));
                    }
            } finally {
                g.dispose();
            }
        }

        /** The facts as laid out: one string per line, " · " between two facts on it. */
        List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (List<Component> line : placed) {
                List<String> texts = new ArrayList<>();
                for (Component fact : line) texts.add(((JLabel) fact).getText());
                lines.add(String.join(GAP, texts));
            }
            return lines;
        }

        private int gap() { return getFontMetrics(Type.caption()).stringWidth(GAP); }

        /** The visible facts line by line: a fact starts a new line when it would not fit after the previous one and a separator. */
        private List<List<Component>> breaks(int available) {
            List<List<Component>> lines = new ArrayList<>();
            List<Component> line = new ArrayList<>();
            int x = 0, gap = gap();
            for (Component fact : getComponents()) {
                if (!fact.isVisible()) continue;
                int width = fact.getPreferredSize().width;
                if (!line.isEmpty() && x + gap + width > available) { lines.add(line); line = new ArrayList<>(); x = 0; }
                x += (line.isEmpty() ? 0 : gap) + width;
                line.add(fact);
            }
            if (!line.isEmpty()) lines.add(line);
            return lines;
        }

        private int width(List<Component> line) {
            int width = 0;
            for (Component fact : line) width += (width == 0 ? 0 : gap()) + fact.getPreferredSize().width;
            return width;
        }

        private static int height(List<Component> line) {
            int height = 0;
            for (Component fact : line) height = Math.max(height, fact.getPreferredSize().height);
            return height;
        }

        /** The width inside the insets: this row's own once laid out, else its parent's; unbounded before either is known. */
        private int available() {
            Insets insets = getInsets();
            int width = getWidth();
            if (width <= 0 && getParent() != null) {
                Insets parent = getParent().getInsets();
                width = getParent().getWidth() - parent.left - parent.right;
            }
            return width > 0 ? Math.max(1, width - insets.left - insets.right) : Integer.MAX_VALUE;
        }
    }

    /**
     * The rows of one Loot or Players section, laid out on shared columns: each row's label takes the width of the section's widest
     * label, so every row's item slots start at one x; the note sits at the row's right end, or under the slots when it does not
     * fit beside them. When even the section's widest slots do not fit beside the label column, every row puts its slots under its
     * label, from the left, so the slots still line up and nothing scrolls sideways. Widths are read at layout time (they follow
     * the font).
     */
    private static final class SlotColumns {
        private final List<SlotRow> rows = new ArrayList<>();

        /** A new row of this section: {@code label}, then {@code slots}, then {@code note} (hidden: no note). */
        SlotRow row(JComponent label, JComponent slots, JComponent note) {
            SlotRow row = new SlotRow(this, label, slots, note);
            rows.add(row);
            return row;
        }

        int labelWidth() {
            int widest = 0;
            for (SlotRow row : rows) widest = Math.max(widest, row.label.getPreferredSize().width);
            return widest;
        }

        int slotsWidth() {
            int widest = 0;
            for (SlotRow row : rows) widest = Math.max(widest, row.slots.getPreferredSize().width);
            return widest;
        }
    }

    /** One row of a {@link SlotColumns} section. */
    private static final class SlotRow extends JPanel {
        private final SlotColumns columns;
        private final JComponent label, slots, note;

        SlotRow(SlotColumns columns, JComponent label, JComponent slots, JComponent note) {
            super(null);
            this.columns = columns;
            this.label = label;
            this.slots = slots;
            this.note = note;
            setOpaque(false);
            add(label);
            add(slots);
            add(note);
        }

        @Override public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (changed) SwingUtilities.invokeLater(this::revalidate);   // the row's line count depends on this width
        }

        @Override public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int available = available();
            int natural = columns.labelWidth() + Tokens.S + slots.getPreferredSize().width
                + (note.isVisible() ? Tokens.S + note.getPreferredSize().width : 0) + insets.left + insets.right;
            return new Dimension(available > 0 ? Math.min(natural, available) : natural, place(false));
        }

        /** No width floor, so the section's column never falls back to minimum sizes; the height is the wrapped one. */
        @Override public Dimension getMinimumSize() { return new Dimension(0, place(false)); }

        @Override public void doLayout() { place(true); }

        /** Places (or only measures) the row at its width; returns its height. */
        private int place(boolean apply) {
            Insets insets = getInsets();
            int available = available(), width = Math.max(1, (available > 0 ? available : Integer.MAX_VALUE / 2) - insets.left - insets.right);
            int left = insets.left, y = insets.top, gap = Tokens.S;
            int column = Math.min(width, columns.labelWidth());
            Dimension l = label.getPreferredSize(), s = slots.getPreferredSize(), n = note.isVisible() ? note.getPreferredSize() : null;
            int slotsX;
            if (column + gap + columns.slotsWidth() <= width) {
                slotsX = column + gap;   // the label column, then the slots on its line
            } else {
                if (apply) label.setBounds(left, y, width, l.height);
                y += l.height + Tokens.XS;
                slotsX = 0;              // the label on its own line; slots from the left under it
            }
            boolean noteBeside = n == null || slotsX + s.width + gap + n.width <= width;
            int line = Math.max(s.height, noteBeside && n != null ? n.height : 0);
            if (slotsX > 0) line = Math.max(line, l.height);
            if (apply) {
                if (slotsX > 0) label.setBounds(left, y + (line - l.height) / 2, column, l.height);
                slots.setBounds(left + slotsX, y + (line - s.height) / 2, Math.min(s.width, width - slotsX), s.height);
                if (n != null && noteBeside) note.setBounds(left + width - n.width, y + (line - n.height) / 2, n.width, n.height);
            }
            y += line;
            if (n != null && !noteBeside) {
                int noteX = slotsX + n.width <= width ? slotsX : 0;   // under the slots, or from the left when that is too narrow
                y += Tokens.XS;
                if (apply) note.setBounds(left + noteX, y, Math.min(n.width, width - noteX), n.height);
                y += n.height;
            }
            return y + insets.bottom;
        }

        /** This row's width once laid out, else its parent's; 0 before either is known. */
        private int available() {
            if (getWidth() > 0) return getWidth();
            Container parent = getParent();
            if (parent == null) return 0;
            Insets insets = parent.getInsets();
            return Math.max(0, parent.getWidth() - insets.left - insets.right);
        }
    }
}
