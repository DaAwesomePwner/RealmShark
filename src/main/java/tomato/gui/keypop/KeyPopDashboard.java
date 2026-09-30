package tomato.gui.keypop;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.chat.SocialQueryControls;
import tomato.gui.history.*;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.StatTile;
import tomato.history.archive.*;

/**
 * All three views and their metrics are derived from the same filtered history. The live row leads the page and lends its workspace
 * the Scope chip ({@link LiveFilterHost}); named live views, the shown table's column tools and the page's own actions are its ⋯ items.
 */
final class KeyPopDashboard extends JPanel implements LiveFilterHost {
    /** The row's ⋯ section for the page's own actions (exports, Log to file, Notification settings, Clear history), which KeypopGUI fills. */
    static final String PAGE_ACTIONS = "page-actions";
    private static final String ALL_ITEMS = "All dungeons / items";
    private static final String[] CAPTIONS = {"Observed pops", "Keys", "Players", "Dungeons/items"};
    /** Header → column kind for the three live tables (widths only; the Player emphasis and Type badge renderers stay). */
    private static final Map<String, ColumnKind> COLUMN_KINDS = new HashMap<>();
    static {
        for (String time : new String[]{"Time", "Last pop"}) COLUMN_KINDS.put(time, ColumnKind.DATE_TIME);
        COLUMN_KINDS.put("Player", ColumnKind.PLAYER); COLUMN_KINDS.put("Type", ColumnKind.STATUS); COLUMN_KINDS.put("Dungeon / item", ColumnKind.DUNGEON);
        for (String count : new String[]{"Pops", "Keys", "Runes", "Vials", "Incs", "Players"}) COLUMN_KINDS.put(count, ColumnKind.COUNT);
        COLUMN_KINDS.put("Share %", ColumnKind.PERCENT);
    }
    final JTextField search = new JTextField();
    private String exactPlayer;
    final JButton playerChip = new JButton();
    final JComboBox<String> type = new JComboBox<>(new String[] {"All types", "Key", "Rune", "Vial", "Inc", "Other"});
    final JComboBox<String> period = new JComboBox<>(new String[] {"All retained", "Last 15 minutes", "Last hour", "Today"});
    final JComboBox<String> item = new JComboBox<>(new String[] {ALL_ITEMS});
    final StatTile[] metrics = new StatTile[4];
    final JLabel status = new JLabel();
    private final JTextArea resolvedPeriod = ContentStyle.wrappingText("");
    final JLabel empty = new JLabel("Waiting for key-pops", SwingConstants.CENTER);
    private final KeyPopHistory history;
    private final DefaultTableModel eventsModel = model(new String[] {"Time", "Player", "Type", "Dungeon / item"}, Instant.class, String.class, String.class, String.class);
    private final DefaultTableModel playersModel = model(new String[] {"Player", "Pops", "Keys", "Runes", "Vials", "Incs", "Share %", "Last pop"}, String.class, Integer.class, Integer.class, Integer.class, Integer.class, Integer.class, Double.class, Instant.class);
    private final DefaultTableModel itemsModel = model(new String[] {"Dungeon / item", "Pops", "Players", "Share %", "Last pop"}, String.class, Integer.class, Integer.class, Double.class, Instant.class);
    final JTable events = table(eventsModel, "keypop-events");
    final JTable players = table(playersModel, "keypop-players");
    final JTable items = table(itemsModel, "keypop-items");
    private final CustomizableTabs views = new CustomizableTabs("keypops-live");
    final JTabbedPane tabs = views.component();
    /** Tab IDs in Mode order; the live view state stores the mode, never a tab position. */
    private static final String[] VIEW_IDS = {"events", "by-player", "by-item"};
    private final JScrollPane eventsScroll = ContentStyle.tableScroll(events, 3), playersScroll = ContentStyle.tableScroll(players, 3), itemsScroll = ContentStyle.tableScroll(items, 3);
    private final FilterBar filterBar = new FilterBar("keypops-live");
    private final javax.swing.Timer refreshTimer;
    private List<KeyPopEvent> filtered = Collections.emptyList();
    private long seenRevision = -1;
    private boolean updating;
    private final boolean historical;
    private ArchiveQuery.Bounds bounds = ArchiveQuery.Bounds.all();
    private final Set<String> extraKinds = new LinkedHashSet<>(), extraItems = new LinkedHashSet<>();
    private final JPanel datePanel = new JPanel(new BorderLayout());
    private final JTextArea kindsInput = new JTextArea(2, 18), itemsInput = new JTextArea(2, 18), stateStatus = ContentStyle.wrappingText("");
    private final DisplayModeModel displayMode;
    /** Each live table's column tools; the shown tab's are in the row's ⋯. */
    private final Map<JTable, HistoryTables.ColumnTools> columnTools = new LinkedHashMap<>();
    private final javax.swing.Timer remember = new javax.swing.Timer(300, e -> persistLiveState());
    private ViewStateStore stateStore;
    private boolean rebuilding;
    private long stateSave;
    static final class LiveFacets extends KeyPopArchiveClient.Facets {
        String type = "All types", item = ALL_ITEMS, period = "All retained";
        Map<String,List<SortEntry>> sorts = new LinkedHashMap<>();
    }
    static final class SortEntry { int column; String direction; SortEntry(int c,String d){column=c;direction=d;} }

    KeyPopDashboard(KeyPopHistory history) {
        this(history,false);
    }
    KeyPopDashboard(KeyPopHistory history, boolean historical) { this(history, historical, DisplayModeModel.application()); }
    /** {@code mode} decides how the time columns read: relative in Simple, absolute in Analyst (display only). */
    KeyPopDashboard(KeyPopHistory history, boolean historical, DisplayModeModel mode) {
        super(new BorderLayout(0, 8)); this.history = history;this.historical=historical;this.displayMode=Objects.requireNonNull(mode, "mode");
        remember.setRepeats(false);
        refreshTimer = new javax.swing.Timer(1000, e -> {
            if (isShowing() && history.revision() != seenRevision) refresh();
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
        });
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints(); constraints.gridx = 0; constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL; constraints.insets = new Insets(0, 0, 8, 0);
        JLabel note = new JLabel("Observed key, rune, vial and inc pops. Statistics follow your filters; callouts do not count.") {
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        ContentStyle.font(note, ContentStyle.metadata(ContentStyle.body()));
        note.setToolTipText(note.getText());
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        // The live row leads the page (the Scope chip joins it while live), then the note and the tiles it describes.
        constraints.gridy = 0; top.add(filterBar, constraints);
        constraints.gridy++; top.add(note, constraints);
        JPanel cards = ContentStyle.responsiveGrid(4, 118, 8);
        for (int i = 0; i < CAPTIONS.length; i++) {
            metrics[i] = new StatTile(CAPTIONS[i]); metrics[i].setName("keypop-metric-" + i);
            cards.add(metrics[i]);
        }
        constraints.gridy++; constraints.insets = new Insets(0, 0, 0, 0); top.add(cards, constraints);
        search.setName("keypop-search"); search.putClientProperty("JTextField.placeholderText", "Search player, dungeon or item…");
        search.getAccessibleContext().setAccessibleName("Search key-pops"); search.setColumns(22);
        search.setToolTipText("Case-insensitive search; every word must match the event.");
        playerChip.setName("keypop-exact-player"); playerChip.setVisible(false);
        playerChip.addActionListener(e -> selectPlayer(null));
        JPanel filters = ContentStyle.responsiveGrid(3, 140, 8);
        filters.add(labeled("Event type", type, "keypop-type")); filters.add(labeled("Time range", period, "keypop-period"));
        item.setPrototypeDisplayValue(ALL_ITEMS); filters.add(labeled("Dungeon / item", item, "keypop-item"));
        JPanel multis = ContentStyle.responsiveGrid(2, 200, 8);
        multis.add(SocialQueryControls.labeled("Additional types: KEY, RUNE, VIAL, INC, OTHER, UNKNOWN (one per line)", new JScrollPane(kindsInput), "keypop-live-kinds"));
        multis.add(SocialQueryControls.labeled("Additional exact dungeons/items (one per line)", new JScrollPane(itemsInput), "keypop-live-items"));
        JPanel multiPanel = new JPanel(new BorderLayout()); multiPanel.add(multis);
        JButton applyMulti = new JButton("Apply multiple choices"); multiPanel.add(applyMulti, BorderLayout.SOUTH);
        applyMulti.addActionListener(e -> {
            Set<String> selected = SocialQueryControls.lines(kindsInput.getText().toUpperCase(Locale.ROOT));
            try { for (String kind : selected) if (!kind.equals("UNKNOWN")) KeyPopEvent.Kind.valueOf(kind); }
            catch (IllegalArgumentException invalid) { stateMessage("Types not applied: use the listed type names."); return; }
            extraKinds.clear(); extraKinds.addAll(selected); extraItems.clear(); extraItems.addAll(SocialQueryControls.lines(itemsInput.getText())); refresh();
        });
        // One filter row (search + reset). The drawer holds the exact player, type/period/item, then multi-select and dates as plain
        // sections, and the live view's status line once it has one; named live views and column tools are ⋯ items.
        JPanel drawer = new JPanel(new GridBagLayout()); GridBagConstraints row = new GridBagConstraints(); row.gridx = 0; row.weightx = 1;
        row.fill = GridBagConstraints.HORIZONTAL; row.insets = new Insets(0, 0, 6, 0);
        for (JComponent part : new JComponent[]{playerChip, filters, multiPanel, datePanel, stateStatus}) { row.gridy++; drawer.add(part, row); }
        stateStatus.setName("keypop-live-state-status"); stateStatus.setVisible(false); rebuildDates();
        filterBar.search(new WrapRow(search, button("Reset filters", this::resetFilters))).drawer(drawer);
        OverflowMenu more = filterBar.overflow();
        // ⋯ in order: Saved views ▸, the page's own actions, then the shown table's column tools (as the saved workspace's ⋯ ends).
        more.section(SocialQueryControls.LIVE_VIEWS); more.section(PAGE_ACTIONS); more.section(HistoryTables.ColumnTools.SECTION);
        events.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        players.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(1, SortOrder.DESCENDING), new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        items.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(1, SortOrder.DESCENDING), new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        views.add("events", "Events", eventsScroll).add("by-player", "By player", playersScroll).add("by-item", "By dungeon / item", itemsScroll);
        JPanel body = new JPanel(new BorderLayout(0, 4)) {
            public Dimension getMinimumSize() {
                return new Dimension(0, tabs.getMinimumSize().height + (empty.isVisible() ? empty.getPreferredSize().height + 4 : 0));
            }
        };
        empty.setName("keypop-empty"); empty.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        body.add(empty, BorderLayout.NORTH); body.add(tabs);
        installDrilldown(players, true); installDrilldown(items, false);
        status.setName("keypop-status"); ContentStyle.font(status, ContentStyle.metadata(ContentStyle.body()));
        status.setForeground(ContentStyle.color("muted"));
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(status, BorderLayout.NORTH); footer.add(resolvedPeriod);
        resolvedPeriod.setName("keypop-live-resolved-period");
        // The resolved bounds and the share denominator are Analyst detail (spec §3.2); Simple's filters show as chips instead.
        displayMode.bind(resolvedPeriod, shown -> resolvedPeriod.setVisible(shown == DisplayModeModel.Mode.ANALYST));
        JScrollPane page = ContentStyle.page(top, body, footer); page.setName("keypop-page-scroll"); add(page);
        onChange(search, this::refresh);
        type.addActionListener(e -> { if (!updating) refresh(); }); period.addActionListener(e -> { if (!updating) { resolvePeriod(); refresh(); } });
        item.addActionListener(e -> { if (!updating) refresh(); }); refresh();
        tabs.addChangeListener(e -> { if (!views.isRebuilding()) rememberState(); });
        for (JTable table : new JTable[]{events, players, items}) {
            for (int i = 0; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i).setIdentifier("column-" + i);
            table.getSelectionModel().addListSelectionListener(e -> rememberState());
            table.getRowSorter().addRowSorterListener(e -> rememberState());
            scroll(table).getViewport().addChangeListener(e -> rememberState());
            // Column tools are ⋯ items: moves, resizes and tool changes join the live view state (saved once a store is enabled).
            ViewState.Table defaults = HistoryTables.columnState(table, "Default");
            HistoryTables.rememberLayout(table, layout -> rememberState());
            HistoryTables.ColumnTools tools = HistoryTables.columnTools(table, defaults, Collections.emptyMap(), layout -> rememberState());
            // Live tables have no presets, and Enter drills into the events instead of a details view.
            tools.presets().setVisible(false); tools.details().setVisible(false);
            columnTools.put(table, tools);
        }
        // Time and Last pop read "12 min ago" in Simple (the absolute time in the tooltip) and stay absolute in Analyst. Display only.
        String zone = DisplayFormat.timestampZoneLabel();
        KitTables.relativeTime(events, "column-0", displayMode, KitTables::epoch, zone);
        KitTables.relativeTime(players, "column-7", displayMode, KitTables::epoch, zone);
        KitTables.relativeTime(items, "column-4", displayMode, KitTables::epoch, zone);
        views.onSelect(id -> showTools()); showTools();
    }

    @Override public FilterBar liveFilterBar() { return filterBar; }

    /** The shown tab's column tools in the row's ⋯, replacing the previous tab's. */
    private void showTools() { columnTools.get(activeTable()).addTo(filterBar.overflow()); }

    @Override public void updateUI() {
        super.updateUI();
        if (status != null) status.setForeground(ContentStyle.color("muted"));   // null while JPanel's constructor installs the UI
    }

    @Override public void addNotify() { super.addNotify(); refreshTimer.start(); }
    @Override public void removeNotify() { refreshTimer.stop(); remember.stop(); persistLiveState(); super.removeNotify(); }

    void resetFilters() {
        exactPlayer = null; playerChip.setVisible(false);
        bounds = ArchiveQuery.Bounds.all(); extraKinds.clear(); extraItems.clear(); kindsInput.setText(""); itemsInput.setText(""); rebuildDates();
        updating = true; search.setText(""); type.setSelectedIndex(0); period.setSelectedIndex(0); item.setSelectedIndex(0);
        updating = false; refresh();
    }

    void refresh() {
        if (updating) return;
        rebuilding = true;
        KeyPopHistory.Snapshot snapshot = history.snapshot(); seenRevision = snapshot.revision;
        TreeSet<String> choices = new TreeSet<>(); for (KeyPopEvent event : snapshot.events) if (event.item != null) choices.add(event.item);
        String selected = (String)item.getSelectedItem();
        if (selected != null && !selected.equals(ALL_ITEMS)) choices.add(selected);
        List<String> existing = new ArrayList<>(); for (int i = 1; i < item.getItemCount(); i++) existing.add(item.getItemAt(i));
        if (!existing.equals(new ArrayList<>(choices))) {
            updating = true; item.removeAllItems(); item.addItem(ALL_ITEMS);
            for (String choice : choices) item.addItem(choice);
            item.setSelectedItem(selected == null ? ALL_ITEMS : selected); updating = false;
        }
        KeyPopArchiveClient.Facets facets = liveFacets();
        filtered = new ArrayList<>();
        for (KeyPopEvent event : snapshot.events) if (KeyPopArchiveClient.matches(event, facets, search.getText())
                && bounds.contains(event.time == null ? null : event.time.toEpochMilli(), null)) filtered.add(event);
        Map<String, List<KeyPopEvent>> byPlayer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<KeyPopEvent>> byItem = new TreeMap<>();
        // A saved page can hold pops whose type, player or dungeon was not recorded: they stay in Events ("Unknown" type) and in
        // the share denominator, but not in a group they cannot be named in, and the tiles that leave them out say so.
        List<Object[]> eventRows = new ArrayList<>(); int keys = 0, untyped = 0, unnamed = 0, placeless = 0;
        for (KeyPopEvent event : filtered) {
            eventRows.add(new Object[] {event.time, event.player, event.kind == null ? "Unknown" : event.kind.label, event.item});
            if (event.player != null) byPlayer.computeIfAbsent(event.player, k -> new ArrayList<>()).add(event); else unnamed++;
            if (event.item != null) byItem.computeIfAbsent(event.item, k -> new ArrayList<>()).add(event); else placeless++;
            if (event.kind == null) untyped++; else if (event.kind == KeyPopEvent.Kind.KEY) keys++;
        }
        replaceRows(events, eventRows);
        String window = snapshot.discarded <= 0 ? null : "Only the latest " + DisplayFormat.formatInteger(KeyPopHistory.CAPACITY) + " pops are retained; "
            + DisplayFormat.formatInteger(snapshot.discarded) + " older pops were dropped (saved history keeps every pop)";
        metrics[0].setValue(tile(filtered.size(), "Matching observed pop events; portal callouts excluded", 0, null, window), null);
        metrics[1].setValue(tile(keys, "Key pops among the matching events", untyped, "type", window), null);
        metrics[2].setValue(tile(byPlayer.size(), "Distinct contributors among the matching events, ignoring case", unnamed, "player", window), null);
        metrics[3].setValue(tile(byItem.size(), "Distinct dungeons or items among the matching events", placeless, "dungeon or item", window), null);
        List<Object[]> playerRows = new ArrayList<>(), itemRows = new ArrayList<>();
        byPlayer.forEach((name, pops) -> {
            int[] counts = new int[KeyPopEvent.Kind.values().length]; Instant last = null;
            for (KeyPopEvent pop : pops) { if (pop.kind != null) counts[pop.kind.ordinal()]++; last = later(last, pop.time); }
            playerRows.add(new Object[] {name, pops.size(), counts[0], counts[1], counts[2], counts[3], pops.size() * 100.0 / filtered.size(), last});
        });
        byItem.forEach((name, pops) -> {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER); Instant last = null;
            for (KeyPopEvent pop : pops) { if (pop.player != null) names.add(pop.player); last = later(last, pop.time); }
            itemRows.add(new Object[] {name, pops.size(), names.size(), pops.size() * 100.0 / filtered.size(), last});
        });
        replaceRows(players, playerRows); replaceRows(items, itemRows);
        empty.setVisible(filtered.isEmpty());
        empty.setText(snapshot.events.isEmpty() ? (historical ? "No saved pops in this page." : "Waiting for pops · Start capture and join a fresh game connection.") : "No pops match these filters. Try Reset filters.");
        empty.setToolTipText(empty.getText());
        status.setText(DisplayFormat.formatInteger(filtered.size()) + " shown / " + DisplayFormat.formatInteger(snapshot.events.size()) + " retained · " + (historical ? "Legacy loaded page" : "This app session")
            + (snapshot.discarded > 0 ? " · " + DisplayFormat.formatInteger(snapshot.discarded) + " older pops dropped" : ""));
        status.setToolTipText("Live view retains the latest " + DisplayFormat.formatInteger(KeyPopHistory.CAPACITY) + " events. Saved session history keeps every pop: choose Scope ▾ › Saved history for older pops. CSV exports filtered events.");
        resolvedPeriod.setText(SocialQueryControls.boundsLabel(bounds, false) + "\nShare denominator: " + filtered.size() + " matching retained pop events; callouts excluded.");
        updateChips(); rebuilding = false; rememberState();
    }

    /**
     * A tile's count (spec §1): a known count, 0 a real zero; partial when it leaves out {@code missing} pops without the
     * {@code field}, or when the buffer dropped older pops ({@code window}), with the reason after its {@code source} in the tooltip.
     */
    private static DisplayValue tile(long count, String source, long missing, String field, String window) {
        List<String> gaps = new ArrayList<>();
        if (missing > 0) gaps.add(DisplayFormat.formatInteger(missing) + (missing == 1 ? " matching pop has" : " matching pops have") + " no recorded " + field);
        if (window != null) gaps.add(window);
        if (gaps.isEmpty()) return DisplayValue.count(count, source + ".", null);
        return DisplayValue.partial(DisplayFormat.formatInteger(count), source + ". " + String.join(". ", gaps) + ".");
    }
    private static Instant later(Instant last, Instant time) { return time == null || last != null && !time.isAfter(last) ? last : time; }

    /** Exact player, type, period or dates, dungeon/item and multi-select choices as removable chips. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        if (exactPlayer != null) chips.add(new FilterBar.ActiveFilter("Player: " + exactPlayer, () -> selectPlayer(null)));
        if (type.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Type: " + type.getSelectedItem(), () -> type.setSelectedIndex(0)));
        if (period.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter(String.valueOf(period.getSelectedItem()), () -> period.setSelectedIndex(0)));
        else if (bounds.from != null || bounds.until != null) chips.add(new FilterBar.ActiveFilter(ArchiveFilters.dateLabel(bounds), () -> {
            bounds = new ArchiveQuery.Bounds(null, null, ZoneId.of(bounds.zone), bounds.mode, bounds.includeUnknown); rebuildDates(); refresh(); }));
        if (item.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Dungeon / item: " + item.getSelectedItem(), () -> item.setSelectedIndex(0)));
        if (!extraKinds.isEmpty()) chips.add(new FilterBar.ActiveFilter("More types: " + ArchiveFilters.summary(extraKinds), () -> { extraKinds.clear(); kindsInput.setText(""); refresh(); }));
        if (!extraItems.isEmpty()) chips.add(new FilterBar.ActiveFilter("More dungeons/items: " + ArchiveFilters.summary(extraItems), () -> { extraItems.clear(); itemsInput.setText(""); refresh(); }));
        FilterChips.update(filterBar, chips, this::resetFilters, false);
    }

    private KeyPopArchiveClient.Facets liveFacets() {
        KeyPopArchiveClient.Facets f = new KeyPopArchiveClient.Facets(); f.exactPlayer = exactPlayer == null ? "" : exactPlayer;
        f.kinds.addAll(extraKinds); if (type.getSelectedIndex() > 0) f.kinds.add(type.getSelectedItem().toString().toUpperCase(Locale.ROOT));
        f.items.addAll(extraItems); if (item.getSelectedIndex() > 0) f.items.add(item.getSelectedItem().toString()); return f;
    }
    private void resolvePeriod() {
        Instant until = Instant.now(); ZoneId zone = ZoneId.systemDefault();
        Instant from = period.getSelectedIndex() == 1 ? until.minusSeconds(900) : period.getSelectedIndex() == 2 ? until.minusSeconds(3600)
            : period.getSelectedIndex() == 3 ? until.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant() : null;
        bounds = new ArchiveQuery.Bounds(from == null ? null : from.toEpochMilli(), from == null ? null : until.toEpochMilli(), zone, ArchiveQuery.TimeMode.ENTRY, false); rebuildDates();
    }
    private void rebuildDates() { datePanel.removeAll(); datePanel.add(SocialQueryControls.dates(bounds, false, value -> { bounds = value; rebuildDates(); refresh(); })); datePanel.revalidate(); }

    void enableLiveState(ViewStateStore store) {
        if (stateStore != null) return; stateStore = store;
        ViewState<LiveFacets,KeyPopArchiveClient.Sort> defaults = captureLiveState();
        try { applyLiveState(store.load("keypops-live", defaults)); } catch (RuntimeException failure) { stateMessage("Live state not applied: " + failure.getMessage()); }
        // Named live views are a Saved views ▸ submenu in the row's ⋯ (spec §3.2), no longer a row of controls in the drawer.
        SocialQueryControls.liveViewItems(filterBar.overflow(), "keypops-live", store, this::captureLiveState, this::applyLiveState, defaults, this::stateMessage);
    }
    /** The live view's status line at the end of the drawer; it takes no space until there is something to say. */
    private void stateMessage(String text) {
        stateStatus.setText(text == null ? "" : text);
        boolean shown = !stateStatus.getText().isEmpty();
        if (stateStatus.isVisible() != shown) { stateStatus.setVisible(shown); revalidate(); }
    }
    ViewState<LiveFacets,KeyPopArchiveClient.Sort> captureLiveState() {
        LiveFacets f = new LiveFacets(); f.exactPlayer = exactPlayer == null ? "" : exactPlayer; f.kinds.addAll(extraKinds); f.items.addAll(extraItems);
        f.type = type.getSelectedItem().toString(); f.item = item.getSelectedItem().toString(); f.period = period.getSelectedItem().toString(); f.mode = mode();
        Map<String,ViewState.Table> layouts = new LinkedHashMap<>();
        for (JTable table : new JTable[]{events,players,items}) { List<SortEntry> order = new ArrayList<>();
            for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) order.add(new SortEntry(key.getColumn(), key.getSortOrder().name()));
            f.sorts.put(table.getName(), order); layouts.put(table.getName(), HistoryTables.columnState(table,"Custom"));
        }
        ArchiveQuery<LiveFacets,KeyPopArchiveClient.Sort> q = ArchiveQuery.of(ArchiveQuery.CURRENT,f,LiveFacets.class,KeyPopArchiveClient.Sort.TIME).withText(search.getText()).withBounds(bounds);
        JTable table = activeTable(); JScrollPane scroll = scroll(table); List<ArchiveRow.Ref> selection = new ArrayList<>();
        if (table.getSelectedRow() >= 0) selection.add(liveRef(table,table.getSelectedRow()));
        int row = table.rowAtPoint(scroll.getViewport().getViewPosition());
        return new ViewState<>(q,false,0,f.mode.name(),selection,row < 0 ? null : liveRef(table,row),row < 0 ? 0 : scroll.getViewport().getViewPosition().y - row * table.getRowHeight(),layouts);
    }
    void applyLiveState(ViewState<LiveFacets,KeyPopArchiveClient.Sort> state) {
        LiveFacets f = state.query.facets(); Objects.requireNonNull(f.mode); Objects.requireNonNull(f.exactPlayer); Objects.requireNonNull(f.item);
        if (!Arrays.asList("All types", "Key", "Rune", "Vial", "Inc", "Other").contains(f.type)
                || !Arrays.asList("All retained", "Last 15 minutes", "Last hour", "Today").contains(f.period)) throw new IllegalArgumentException("Unsupported live filter value");
        for (String kind : f.kinds) if (!kind.equals("UNKNOWN")) KeyPopEvent.Kind.valueOf(kind);
        for (JTable table : new JTable[]{events,players,items}) for (SortEntry key : f.sorts.getOrDefault(table.getName(),Collections.emptyList())) {
            if (key.column < 0 || key.column >= table.getModel().getColumnCount()) throw new IllegalArgumentException("Unknown saved sort column"); SortOrder.valueOf(key.direction);
        }
        updating = true;
        try { exactPlayer = f.exactPlayer.isEmpty() ? null : f.exactPlayer; search.setText(state.query.text()); type.setSelectedItem(f.type); period.setSelectedItem(f.period);
            if (!ALL_ITEMS.equals(f.item)) { boolean found=false;for(int i=0;i<item.getItemCount();i++)if(f.item.equals(item.getItemAt(i)))found=true;if(!found)item.addItem(f.item); } item.setSelectedItem(f.item);
            extraKinds.clear();extraKinds.addAll(f.kinds);extraItems.clear();extraItems.addAll(f.items);kindsInput.setText(String.join("\n",extraKinds));itemsInput.setText(String.join("\n",extraItems));
            bounds = state.query.bounds(); rebuildDates(); showView(VIEW_IDS[f.mode.ordinal()]);
            for (JTable table : new JTable[]{events,players,items}) { if (state.tables.containsKey(table.getName())) HistoryTables.applyColumns(table,state.tables.get(table.getName()));
                List<RowSorter.SortKey> keys=new ArrayList<>();for(SortEntry key:f.sorts.getOrDefault(table.getName(),Collections.emptyList()))keys.add(new RowSorter.SortKey(key.column,SortOrder.valueOf(key.direction)));table.getRowSorter().setSortKeys(keys); }
        } finally { updating = false; }
        playerChip.setVisible(exactPlayer != null);playerChip.setText(exactPlayer == null ? "" : "Player equals " + exactPlayer + " · Clear");refresh();rebuilding=true;
        JTable table=activeTable();table.clearSelection();for(int i=0;i<table.getRowCount();i++){ArchiveRow.Ref ref=liveRef(table,i);if(state.selected.contains(ref))table.addRowSelectionInterval(i,i);
            if(ref.equals(state.anchor))scroll(table).getViewport().setViewPosition(new Point(0,Math.max(0,i*table.getRowHeight()+state.anchorOffset)));}
        rebuilding=false;rememberState();
    }
    private JTable activeTable(){KeyPopArchiveClient.Mode mode=mode();return mode==KeyPopArchiveClient.Mode.EVENTS?events:mode==KeyPopArchiveClient.Mode.BY_PLAYER?players:items;}
    private KeyPopArchiveClient.Mode mode(){return KeyPopArchiveClient.Mode.values()[Math.max(0,Arrays.asList(VIEW_IDS).indexOf(views.selectedId()))];}
    private JScrollPane scroll(JTable table){return table==events?eventsScroll:table==players?playersScroll:itemsScroll;}
    private void showView(String id){views.show(id);views.select(id);}
    private ArchiveRow.Ref liveRef(JTable table,int view){int row=table.convertRowIndexToModel(view);String key=table==events?Objects.toString(filtered.get(row).id,"missing"):Objects.toString(table.getModel().getValueAt(row,0),"");return new ArchiveRow.Ref("@live","keypops",table.getName(),key);}
    private void rememberState(){if(stateStore!=null&&!updating&&!rebuilding){stateSave++;if(!"Live view changes awaiting save…".equals(stateStatus.getText()))stateMessage("Live view changes awaiting save…");remember.restart();}}
    private void persistLiveState(){if(stateStore==null||updating||rebuilding)return;long request=++stateSave;
        try{stateStore.save("keypops-live",captureLiveState()).whenComplete((result,failure)->SwingUtilities.invokeLater(()->{if(request==stateSave)stateMessage(failure==null&&result.isSuccess()?"Live view state saved.":"Live view active; its state was not saved. The next change retries.");}));}
        catch(RuntimeException failure){stateMessage(failure.getMessage());}}

    private void installDrilldown(JTable table, boolean player) {
        table.setToolTipText("Double-click or press Enter to filter the event history.");
        Runnable drilldown = () -> {
            int row = table.getSelectedRow(); if (row < 0) return;
            int modelRow = table.convertRowIndexToModel(row);
            String value = (String)table.getModel().getValueAt(modelRow, 0);
            if (player) selectPlayer(value); else item.setSelectedItem(value);
            showView("events");
        };
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "show-events");
        table.getActionMap().put("show-events", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { drilldown.run(); }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = table.rowAtPoint(e.getPoint()); if (row < 0) return;
                table.setRowSelectionInterval(row, row); drilldown.run();
            }
        });
    }

    void clearHistory() { history.clear(); refresh(); }

    void selectPlayer(String player) {
        exactPlayer = player;
        playerChip.setText(player == null ? "" : "Player equals " + player + " · Clear");
        playerChip.setToolTipText("Exact contributor name, ignoring case. Activate to remove this filter.");
        playerChip.setVisible(player != null); refresh(); revalidate();
    }

    List<KeyPopEvent> filteredEvents() { return new ArrayList<>(filtered); }

    /**
     * The dungeon/item of the selected event or By dungeon row, else the Dungeon filter; null when the
     * current tab has no such selection (By player). The value is observed text and is not resolved here.
     */
    String selectedDungeon() {
        int tab = mode().ordinal();
        if (tab == 0 && events.getSelectedRow() >= 0) {
            int row = events.convertRowIndexToModel(events.getSelectedRow());
            return row < filtered.size() ? filtered.get(row).item : null;
        }
        if (tab == 2 && items.getSelectedRow() >= 0) return (String)items.getModel().getValueAt(items.convertRowIndexToModel(items.getSelectedRow()), 0);
        if (tab != 1 && item.getSelectedIndex() > 0) return (String)item.getSelectedItem();
        return null;
    }

    void editFont(Font font) {
        for (JTable table : new JTable[] {events, players, items}) ContentStyle.tableFont(table, font, 0);
    }

    void exportCsv() {
        refresh();
        if (filtered.isEmpty()) { JOptionPane.showMessageDialog(this, "No matching events to export."); return; }
        List<KeyPopEvent> export = new ArrayList<>(filtered);
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("keypops-" + LocalDate.now() + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace " + path.getFileName() + "?", "Export CSV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        status.setText("Exporting " + DisplayFormat.formatInteger(export.size()) + " events…");
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws IOException { writeCsv(path, export); return null; }
            protected void done() {
                try { get(); status.setText("Exported " + DisplayFormat.formatInteger(export.size()) + " events to " + path.getFileName()); }
                catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    JOptionPane.showMessageDialog(KeyPopDashboard.this, "Could not export: " + cause.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    void exportCurrentTab() {
        if (mode() == KeyPopArchiveClient.Mode.EVENTS) { exportCsv(); return; }
        List<List<String>> rows = summaryRows(); List<String> headers = rows.get(0); List<List<String>> values = rows.subList(1, rows.size());
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Export current retained summary"); chooser.setSelectedFile(new java.io.File("keypop-summary.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return; Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this,"Replace " + path.getFileName() + "?","Export summary",JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws IOException { try (BufferedWriter out = Files.newBufferedWriter(path,StandardCharsets.UTF_8)) {
                out.write(csvRow(headers)); for (List<String> row : values) out.write(csvRow(row)); } return null; }
            protected void done() { try { get(); status.setText("Exported " + values.size() + " retained summary rows."); }
                catch (Exception failure) { status.setText("Summary export failed: " + failure.getMessage()); } }
        }.execute();
    }
    /**
     * The shown tab's refreshed rows as Export current tab writes them: a header row, then each row's visible model values (UTC
     * instants, unrounded shares, never the display text, so the mode never changes an export) with the share denominator.
     */
    private List<List<String>> summaryRows() {
        refresh(); JTable table = activeTable();
        List<String> headers = new ArrayList<>(); for (int c = 0; c < table.getColumnCount(); c++) headers.add(table.getColumnName(c));
        headers.add("Matching retained pop-event denominator (callouts excluded)");
        List<List<String>> rows = new ArrayList<>(); rows.add(headers);
        for (int r = 0; r < table.getRowCount(); r++) { List<String> row = new ArrayList<>();
            for (int c = 0; c < table.getColumnCount(); c++) row.add(Objects.toString(table.getValueAt(r,c),"")); row.add(Integer.toString(filtered.size())); rows.add(row); }
        return rows;
    }
    /** {@link #summaryRows} as the CSV text Export current tab writes. */
    String summaryCsv() { StringBuilder csv = new StringBuilder(); for (List<String> row : summaryRows()) csv.append(csvRow(row)); return csv.toString(); }
    private static String csvRow(List<String> values) { List<String> quoted = new ArrayList<>(); for (String value : values) quoted.add(KeyPopEvent.csv(value)); return String.join(",",quoted) + "\r\n"; }

    static void writeCsv(Path path, List<KeyPopEvent> events) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("Timestamp (UTC),Player,Type,Dungeon / item\r\n");
            for (KeyPopEvent event : events) writer.write(event.csvLine());
        }
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text); button.addActionListener(e -> action.run()); return button;
    }
    private static JPanel labeled(String text, JComponent control, String name) {
        control.setName(name); control.getAccessibleContext().setAccessibleName(text);
        JPanel panel = new JPanel(new BorderLayout(0, 4)); JLabel label = new JLabel(text); label.setLabelFor(control);
        ContentStyle.font(label, ContentStyle.metadata(ContentStyle.body()));
        panel.add(label, BorderLayout.NORTH); panel.add(control); return panel;
    }
    static void onChange(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }
    private static DefaultTableModel model(String[] names, Class<?>... types) {
        return new DefaultTableModel(names, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) { return types[column]; }
        };
    }
    private static void replaceRows(JTable table, List<Object[]> rows) {
        DefaultTableModel model = (DefaultTableModel)table.getModel();
        Object[] selected = null;
        if (table.getSelectedRow() >= 0) {
            int row = table.convertRowIndexToModel(table.getSelectedRow());
            int columns = model.getColumnClass(0) == Instant.class ? model.getColumnCount() : 1;
            selected = new Object[columns];
            for (int col = 0; col < columns; col++) selected[col] = model.getValueAt(row, col);
        }
        // One change event per refresh avoids sorting after every captured row.
        model.getDataVector().clear();
        for (Object[] row : rows) model.getDataVector().add(new Vector<>(Arrays.asList(row)));
        model.fireTableDataChanged();
        if (selected != null) {
            for (int row = 0; row < rows.size(); row++) {
                if (Arrays.equals(selected, Arrays.copyOf(rows.get(row), selected.length))) {
                    int view = table.convertRowIndexToView(row); table.setRowSelectionInterval(view, view); break;
                }
            }
        }
    }
    /** A pop type as a tone badge (Key amber, Rune violet, Vial mint, Inc rose, anything else muted): live and saved Events share it. */
    static ContentStyle.Badge typeBadge() {
        return new ContentStyle.Badge() {
            protected Color badgeColor(Object value) {
                switch (String.valueOf(value)) {
                    case "Key": return ContentStyle.color("amber");
                    case "Rune": return ContentStyle.color("violet");
                    case "Vial": return ContentStyle.color("mint");
                    case "Inc": return ContentStyle.color("rose");
                    default: return ContentStyle.color("muted");
                }
            }
        };
    }
    private static JTable table(DefaultTableModel model, String name) {
        JTable table = new JTable(model) {
            @Override public boolean getScrollableTracksViewportWidth() { return getParent() != null && getPreferredSize().width < getParent().getWidth(); }
        };
        table.setName(name); ContentStyle.table(table);
        table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        HistoryTables.kinds(table, COLUMN_KINDS);
        table.setDefaultRenderer(String.class, new ContentStyle.Cell() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                if ("Player".equals(t.getColumnName(column))) setFont(ContentStyle.emphasis(t.getFont()));
                return this;
            }
        });
        for (int i = 0; i < model.getColumnCount(); i++) if ("Type".equals(model.getColumnName(i))) table.getColumnModel().getColumn(i).setCellRenderer(typeBadge());
        table.setDefaultRenderer(Instant.class, new ContentStyle.Cell() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                setFont(ContentStyle.metadata(t.getFont()));
                if (!selected) setForeground(ContentStyle.color("muted"));
                return this;
            }
            @Override protected void setValue(Object value) {
                setText(DisplayFormat.formatTimestamp((Instant)value));
                setToolTipText(value == null ? null : getText() + " (" + DisplayFormat.timestampZoneLabel() + ")");
            }
        });
        table.setDefaultRenderer(Integer.class, new ContentStyle.Cell() {
            { setHorizontalAlignment(SwingConstants.RIGHT); }
            @Override protected void setValue(Object value) {
                setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(((Number)value).longValue()));
            }
        });
        table.setDefaultRenderer(Double.class, new ContentStyle.Cell() {
            private double percent;
            { setHorizontalAlignment(SwingConstants.RIGHT); setOpaque(false); }
            @Override protected void setValue(Object value) {
                percent = value == null ? 0 : ((Number)value).doubleValue();
                setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatPercentage(percent, 1));
                if (!Double.isFinite(percent)) percent = 0;
            }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
                Color accent = UIManager.getColor("Component.accentColor");
                if (accent == null) accent = UIManager.getColor("Table.selectionBackground");
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 65));
                g.fillRoundRect(3, 5, (int)((getWidth() - 6) * Math.min(100, percent) / 100), getHeight() - 10, 6, 6);
                super.paintComponent(g);
            }
        });
        return table;
    }
}
