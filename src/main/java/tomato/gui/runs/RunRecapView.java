package tomato.gui.runs;

import java.awt.*;
import java.time.Instant;
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
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;

/**
 * The run recap (spec §6.3): one exact saved run, read by {@link RunRecapBuilder} off the EDT and applied here. It replaces the
 * workbench text as the way to read a run.
 * - "‹ Runs" ({@code run-recap-back}) is always shown, also while loading and for an unavailable run.
 * - Header: portal sprite, dungeon, outcome chip, entry time, observed span, party, character, and for an in-progress run
 *   when it was read; links "Open in Runs table", "Open in Loot" and "Open in Timeline" carry the exact visit
 *   ({@link #onOpenRoute}).
 * - A tile row ({@code run-recap-tile-<id>}): your DPS and rank, damage share, deaths, fame, loot and exalt progress; unknown is
 *   "—" with its reason as the tooltip.
 * - Collapsible sections, each remembering its state ({@code ui.collapse.run-recap-<id>}): Damage ({@link RunDamagePanel}) and
 *   Loot open, Players, Resources and Timeline closed, Evidence (the workbench's text) closed and in Analyst only. A section
 *   without content shows its one-line reason instead of hiding.
 * Each section rebuilds only when its own part of the model changed. EDT only.
 */
public final class RunRecapView extends JPanel {
    public static final String LOADING = "Loading this run…";
    public static final String UNAVAILABLE_TITLE = "This run is not in saved history";
    /** Section ids; each Collapsible is named {@code run-recap-<id>} and remembers {@code ui.collapse.run-recap-<id>}. */
    public static final String DAMAGE = "damage", LOOT = "loot", PLAYERS = "players", RESOURCES = "resources", TIMELINE = "timeline",
        EVIDENCE = "evidence";
    private static final String LOADING_CARD = "loading", UNAVAILABLE_CARD = "unavailable", RECAP_CARD = "recap";
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
    private final EmptyState unavailable;
    private final JScrollPane scroll;
    // Header
    private final JLabel portal = new JLabel();
    private final KitText map = new KitText(" ", Type.title(), Tokens.Role.TEXT);
    private final Chip outcome = new Chip(" ", Tokens.Tone.NEUTRAL);
    private final KitText entered = fact("run-recap-entered"), duration = fact("run-recap-duration"), party = fact("run-recap-party"),
        character = fact("run-recap-character"), asOf = fact("run-recap-asof"), asOfDot = dot();
    private final KitButton openTable = link("Open in Runs table", "run-recap-open-table"), openLoot = link("Open in Loot", "run-recap-open-loot"),
        openTimeline = link("Open in Timeline", "run-recap-open-timeline");
    private final Map<KitButton, Route> routesByLink = new HashMap<>();
    private final Map<String, StatTile> tiles = new LinkedHashMap<>();
    private final Map<String, Collapsible> sections = new LinkedHashMap<>();
    private final RunDamagePanel damage;
    // Loot
    private final KitText lootSummary = KitText.caption(" ");
    private final JTextArea lootReason = reason("run-recap-loot-reason");
    private final JPanel lootBags = column("run-recap-loot-bags");
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
        KitButton unavailableBack = KitButton.secondary("Back to Runs");
        unavailableBack.setName("run-recap-unavailable-back");
        unavailableBack.addActionListener(e -> backAction.run());
        unavailable = new EmptyState(UNAVAILABLE_TITLE, " ", unavailableBack);
        unavailable.setName("run-recap-unavailable");

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
        playersCount.setName("run-recap-players-count");
        playersDamageLabel.setName("run-recap-players-damage-label");
        section(DAMAGE, "Damage", KitLayouts.stack(Tokens.S, damage), true);
        section(LOOT, "Loot", KitLayouts.stack(Tokens.S, lootSummary, lootReason, lootBags), true);
        section(PLAYERS, "Players", KitLayouts.stack(Tokens.S, playersCount, playersReason, playersDamageLabel, playersDamageReason, playerRows), false);
        section(RESOURCES, "Resources", KitLayouts.stack(Tokens.S, resourcesReason, resourcesHelp, resourcesScroll), false);
        section(TIMELINE, "Timeline", KitLayouts.stack(Tokens.S, timelineReason, timelineNote, eventScroll), false);
        section(EVIDENCE, "Evidence", KitLayouts.stack(Tokens.S, evidenceText), false);

        List<JComponent> rows = new ArrayList<>(List.of(header, tileGrid));
        rows.addAll(sections.values());
        Column page = new Column(KitLayouts.stack(Tokens.M, rows.toArray(new JComponent[0])));
        scroll = new JScrollPane(page);
        scroll.setName("run-recap-scroll");
        scroll.setBorder(null);
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

    /** What "‹ Runs" and the unavailable state's button do. */
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

    /** EDT: applies a model built off the EDT: the recap, or the unavailable state with the archive's wording. */
    public void show(RunRecapModel next) {
        requireEdt();
        Objects.requireNonNull(next, "model");
        model = next;
        ref = next.ref();
        loading = false;
        if (!next.available()) {
            unavailable.setBody(next.unavailable());
            cards.show(body, UNAVAILABLE_CARD);
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
        JPanel facts = ContentStyle.controls();
        facts.setOpaque(false);
        facts.setName("run-recap-facts");
        KitText[] shown = {entered, duration, party, character, asOf};
        for (int i = 0; i < shown.length; i++) {
            if (i > 0) facts.add(i == shown.length - 1 ? asOfDot : dot());
            facts.add(shown[i]);
        }
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
        set(entered, h.entered() == null ? "Entered —" : "Entered " + DisplayFormat.formatTimestamp(h.entered()),
            h.entered() == null ? "No entry time was saved for this run" : "When the run's area was entered");
        set(duration, h.durationMs() == null ? "Duration —" : "Observed " + KitFormat.duration(h.durationMs()),
            h.durationMs() == null ? "The run's observed span is unknown"
                : "Observed span from entry to last seen; not a verified clear time");
        set(party, h.partySize() == null ? "Party —" : "Party " + h.partySize(),
            h.partySize() == null ? "Party not observed" : "The observed RotMG party");
        set(character, h.character() == null ? "Character —" : h.character(),
            h.character() == null ? "No fame reading was recorded during this run, so the character used is unknown"
                : "The character of the fame readings recorded during this run");
        boolean live = h.outcome() == RunOutcome.IN_PROGRESS;
        set(asOf, live ? "Read at " + DisplayFormat.formatTimestamp(Instant.ofEpochMilli(next.capturedAt()), DisplayFormat.TimestampMode.TIME) : " ",
            live ? "An in-progress run is read from its saved checkpoint (about every 10 s); open it again for newer facts" : null);
        asOf.setVisible(live);
        asOfDot.setVisible(live);
        VisitRef exact = next.ref();
        route(openTable, ActivityRoutes.visit(Destination.RUNS, exact));
        route(openLoot, ActivityRoutes.visit(Destination.LOOT, exact));
        route(openTimeline, ActivityRoutes.visit(Destination.TIMELINE, exact));
    }

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

    /** The separator between two header facts. */
    private static KitText dot() {
        return KitText.caption("·");
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
    }

    private void title(String id, String text) {
        KitButton toggle = sections.get(id).toggle();
        if (!text.equals(toggle.getText())) toggle.setText(text);
    }

    private void applyLoot(RunRecapModel.Loot loot) {
        if (loot.equals(shownLoot)) return;
        shownLoot = loot;
        boolean any = !loot.bags().isEmpty();
        lootSummary.setText(any ? LootLine.section(loot.count(), loot.bags().size(), loot.summary()) : " ");
        lootSummary.setVisible(any);
        text(lootReason, any ? null : loot.reason());
        rebuild(lootBags, rows -> { for (RunRecapModel.Loot.Bag bag : loot.bags()) rows.add(bagRow(bag)); });
        title(LOOT, any ? "Loot · " + loot.count() + (loot.count() == 1 ? " item" : " items") : "Loot");
    }

    private static JComponent bagRow(RunRecapModel.Loot.Bag bag) {
        KitText name = KitText.body(bag.bag() == null ? "Bag" : bag.bag() + " bag");
        name.setName("run-recap-loot-bag-name");
        name.setIcon(new Dot(bag.bag()));
        name.setIconTextGap(Tokens.XS + 2);
        name.setToolTipText(bag.bag() == null ? "The bag's color was not recorded" : null);
        KitText time = KitText.caption("at " + DisplayFormat.formatTimestamp(Instant.ofEpochMilli(bag.time()), DisplayFormat.TimestampMode.TIME));
        time.setName("run-recap-loot-bag-time");
        JPanel lead = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
        lead.setOpaque(false);
        lead.add(name);
        lead.add(time);
        JPanel slots = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0));
        slots.setOpaque(false);
        List<String> names = new ArrayList<>();
        for (LootFacts.Item item : bag.items()) {
            ItemSlot slot = new ItemSlot(24);
            slot.setItem(item.id(), item.untiered() ? "UT" : item.setTiered() ? "ST" : ItemTiers.label(item.id()));
            slots.add(slot);
            names.add(Sprites.name(item.id()));
        }
        String kinds = LootLine.kinds(bag.items());
        KitText summary = KitText.caption(kinds);
        summary.setName("run-recap-loot-bag-kinds");
        summary.setVisible(!kinds.isEmpty());
        JPanel row = KitLayouts.spread(Tokens.S, lead, slots, summary);
        row.setName("run-recap-loot-bag");
        row.getAccessibleContext().setAccessibleName(name.getText() + " " + time.getText() + ": " + String.join(", ", names)
            + (kinds.isEmpty() ? "" : " (" + kinds + ")"));
        return row;
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
        rebuild(playerRows, rows -> { for (RunRecapModel.Players.Player player : players.players()) rows.add(playerRow(player, players.damageReason())); });
        title(PLAYERS, any ? "Players · " + players.players().size() : "Players");
    }

    private static JComponent playerRow(RunRecapModel.Players.Player player, String damageReason) {
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
        for (Integer item : player.equipment()) {
            ItemSlot slot = new ItemSlot(24);
            if (item == null) slot.setUnknown(); else slot.setItem(item, ItemTiers.label(item));
            slots.add(slot);
        }
        Long inspect = player.inspectDamage();
        KitText damage = KitText.caption("Inspect damage " + (inspect == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(inspect)));
        damage.setName("run-recap-player-damage");
        damage.setToolTipText(inspect == null ? damageReason : RunRecapModel.Players.INSPECT_DAMAGE);
        JPanel row = KitLayouts.spread(Tokens.S, lead, slots, damage);
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

    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Show the run recap on the EDT"); }

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

    /** A bag-colored dot beside the bag name (muted when the bag was not recorded); the name says the color too. */
    private static final class Dot implements Icon {
        private final String bag;
        Dot(String bag) { this.bag = bag; }
        @Override public int getIconWidth() { return 10; }
        @Override public int getIconHeight() { return 10; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(bag == null ? Tokens.color(Tokens.Role.TEXT_MUTED) : Tokens.bag(bag));
            g.fillOval(x + 1, y + 1, 8, 8);
            g.setColor(Tokens.color(Tokens.Role.BORDER));
            g.drawOval(x + 1, y + 1, 8, 8);
            g.dispose();
        }
    }

    /**
     * The loot wording of the recap ("1 UT · 2 potions", "3 items in 2 bags · …"), kept in one place so it can be unified with the
     * run feed's card line, which follows the same rule: untiered, then set-tiered, then potions; nothing else is counted by kind.
     */
    static final class LootLine {
        private LootLine() {}

        /** "1 UT · 2 potions" for these items; "" when none is untiered, set-tiered or a potion. */
        static String kinds(List<LootFacts.Item> items) {
            int untiered = 0, setTiered = 0, potions = 0;
            for (LootFacts.Item item : items) {
                if (item.untiered()) untiered++;
                if (item.setTiered()) setTiered++;
                if (item.potion()) potions++;
            }
            List<String> parts = new ArrayList<>();
            if (untiered > 0) parts.add(untiered + " UT");
            if (setTiered > 0) parts.add(setTiered + " ST");
            if (potions > 0) parts.add(potions + (potions == 1 ? " potion" : " potions"));
            return String.join(" · ", parts);
        }

        /** "3 items in 2 bags · 1 UT · 2 potions" ({@code kinds} as {@link #kinds} words it; "" leaves it out). */
        static String section(int items, int bags, String kinds) {
            String line = items + (items == 1 ? " item" : " items") + " in " + bags + (bags == 1 ? " bag" : " bags");
            return kinds == null || kinds.isEmpty() ? line : line + " · " + kinds;
        }
    }
}
