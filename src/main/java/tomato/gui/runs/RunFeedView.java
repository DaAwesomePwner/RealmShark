package tomato.gui.runs;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * The Runs page's feed (spec §6.3 Feed): saved runs of every session as painted cards grouped by the local day they were
 * entered, newest first, with the Runs archive workspace, unchanged (its own filter bar, scope row, ⋯ menu, saved views and
 * exports), as the Table view.
 * - Views: Cards (default) and Table, remembered in {@link #VIEW_KEY}; restoring the preference only selects. Simple offers the
 *   other view in each view's ⋯ menu ({@code run-feed-view-item} "Table view", {@code run-feed-cards-item} "Cards view"); Analyst
 *   a Cards/Table toggle ({@code run-feed-view}) above both views.
 * - Cards view: a {@code run-feed} FilterBar (search {@code run-feed-search}; a drawer with one checkbox per outcome
 *   {@code run-feed-outcome-<name>} and the dungeon {@code run-feed-map}; ⋯ Refresh), the summary line {@code run-feed-summary},
 *   a warn line {@code run-feed-issues} when saved sessions could not be read fully, then one kit SectionHeader and TileList per
 *   day ({@code run-feed-day-<yyyy-MM-dd>}), "Load more" ({@code run-feed-load-more}) while more runs match, or one empty state (saved
 *   history unavailable, loading, unreadable, no saved runs, no matches: a title and one sentence on the page's left edge) in
 *   place of the summary line.
 * - Reads run on one daemon worker ("RealmShark run feed") through {@link RunFeedSource}, never on the EDT: a newer request
 *   cancels the older one and only the newest result is applied (a generation guard). The feed reads when the Cards view first
 *   shows; afterwards showing it again, and a check every {@value #POLL_MILLIS} ms while it shows, compare the store's stamp
 *   ({@link #stamp}: the session folders and the files the feed reads) and read again only when it changed, keeping as many
 *   runs loaded as before. A query change reads its first page; ⋯ Refresh always reads. An unchanged day list fires no event.
 * - Keyboard (spec §10): arrow keys move within a day, Tab moves between days and the controls, Enter, Space or a double-click
 *   opens the run ({@link #onOpen}). Each card's accessible name states every fact it shows and each missing link.
 * EDT only, except the reads.
 */
public final class RunFeedView extends JPanel implements AutoCloseable {
    public static final String VIEW_KEY = "ui.runs.view", CARDS = "cards", TABLE = "table";
    /** How often the showing Cards view compares the store's stamp (visits are saved about every 10 s while capturing). */
    static final int POLL_MILLIS = 30_000;
    /** Typing pause before the search is applied; Enter applies it at once. */
    static final int TYPING_MILLIS = 400;
    static final String ISSUES = "Some saved sessions could not be read fully; their cards say what is missing.";
    static final String ALL_DUNGEONS = "All dungeons";

    /** How the view reads saved runs: a {@link RunFeedSource} over the history store in production. Off the EDT only. */
    interface Feed {
        /** What the feed's reads depend on; an equal stamp means a read would show the same runs. */
        Object stamp(Cancellation cancel) throws IOException;
        RunFeedSource.Page first(RunFeedQuery query, Cancellation cancel) throws IOException;
        RunFeedSource.Page more(RunFeedSource.Page previous, Cancellation cancel) throws IOException;
    }

    /** What a request does: compare the stamp first, always read, read a new query's first page, or read the next page. */
    private enum Kind { CHECK, REFRESH, QUERY, MORE }

    /** A worker's answer: a page (with the stamp read before it), no change, or a failure. */
    private record Result(Kind kind, Object stamp, RunFeedSource.Page page, boolean unchanged, Throwable failure) {}

    private final JComponent table;
    private final Supplier<Feed> feeds;
    private final ZoneId zone;
    private final BiConsumer<String, String> write;
    private final ThreadPoolExecutor worker;
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private final JPanel viewRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    private final SegmentedControl viewSwitch = new SegmentedControl("run-feed-view", "Cards", "Table");
    private final FilterBar filterBar = new FilterBar("run-feed");
    private final JTextField search = new JTextField(18);
    private final Map<RunOutcome, JCheckBox> outcomes = new EnumMap<>(RunOutcome.class);
    private final JComboBox<String> map = new JComboBox<>();
    private final SortedSet<String> maps = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final JTextArea summary = ContentStyle.wrappingText("");
    private final Banner issues = new Banner("run-feed-issues");
    private final JPanel groups = new JPanel(new GridBagLayout());
    private final JPanel emptyHolder = new JPanel(new BorderLayout());
    private final KitButton more = KitButton.secondary("Load more");
    private final JScrollPane cardsPage;
    private final JMenuItem viewItem;
    private JMenuItem cardsItem;
    private JPopupMenu.Separator cardsSeparator;
    private final javax.swing.Timer poll = new javax.swing.Timer(POLL_MILLIS, e -> check());
    private final javax.swing.Timer typing = new javax.swing.Timer(TYPING_MILLIS, e -> applyControls());
    private final Map<LocalDate, Section> sections = new HashMap<>();
    private List<LocalDate> order = List.of();
    private Consumer<VisitRef> open = ref -> { };
    private RunFeedQuery query = RunFeedQuery.all();
    private RunFeedSource.Page page;
    private Object loadedStamp;
    /** The last read's failure in words (null after a successful read), and whether it was "Load more"'s. */
    private String failure, emptyKey;
    private boolean failedMore;
    private EmptyState empty;
    private boolean tableShown, loading, loadingMore, unavailable, updating, selecting, closed;
    private long generation;
    private Cancellation cancel = new Cancellation();

    /** One day's header, counts line and card list. */
    private record Section(SectionHeader header, JTextArea counts, TileList<RunCardModel> list, JPanel panel) {}

    /**
     * The production feed over {@code table} (the Runs archive workspace, or the live runs view without saved history): saved
     * history from {@code store} (null while none is open: the Cards view says so), the system zone and clock, the application's
     * display mode, and the view preference in the app's properties.
     */
    public RunFeedView(JComponent table, Supplier<SessionStore> store) {
        this(table, sources(store), ZoneId.systemDefault(), DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    /** As above with the reader, the zone cards are dated in, the display mode and the preference store (tests). */
    RunFeedView(JComponent table, Supplier<Feed> feeds, ZoneId zone, DisplayModeModel mode, Function<String, String> read, BiConsumer<String, String> write) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the run feed on the EDT");
        this.table = Objects.requireNonNull(table, "table");
        this.feeds = Objects.requireNonNull(feeds, "feeds");
        this.zone = Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(mode, "mode");
        this.write = Objects.requireNonNull(write, "write");
        setName("run-feed");
        setOpaque(false);
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "RealmShark run feed");
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);

        // Cards view: the filter row, summary and warn line scroll with the day sections; "Load more" ends the page.
        search.setName("run-feed-search");
        search.getAccessibleContext().setAccessibleName("Search saved runs");
        search.putClientProperty("JTextField.placeholderText", "Search saved runs");
        search.setToolTipText("Searches every saved run's text as the Table view does; Enter applies it at once");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { typed(); }
            public void removeUpdate(DocumentEvent e) { typed(); }
            public void changedUpdate(DocumentEvent e) { typed(); }
        });
        search.addActionListener(e -> { typing.stop(); applyControls(); });
        typing.setRepeats(false);
        filterBar.search(new WrapRow(search)).drawer(drawer());
        OverflowMenu overflow = filterBar.overflow();
        viewItem = overflow.add("Table view", () -> show(true, true));
        viewItem.setName("run-feed-view-item");
        overflow.add("Refresh", this::refresh).setName("run-feed-refresh");
        summary.setName("run-feed-summary");
        summary.setFocusable(false);
        tint(summary);
        issues.setTone(Tokens.Tone.WARN);
        issues.setVisible(false);
        groups.setName("run-feed-days");
        groups.setOpaque(false);
        emptyHolder.setOpaque(false);
        emptyHolder.setVisible(false);
        more.setName("run-feed-load-more");   // the FilterBar's ⋯ is "run-feed-more"
        more.getAccessibleContext().setAccessibleDescription("Reads the next " + RunFeedSource.PAGE + " saved runs");
        more.addActionListener(e -> request(Kind.MORE));
        more.setVisible(false);
        JPanel body = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }   // the page scrolls the cards, never squeezes them
        };
        body.setOpaque(false);
        body.add(KitLayouts.stack(Tokens.L, groups, emptyHolder), BorderLayout.NORTH);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        footer.setOpaque(false);
        footer.add(more);
        cardsPage = ContentStyle.page(KitLayouts.stack(Tokens.S, filterBar, summary, issues), body, footer);
        cardsPage.setName("run-feed-scroll");
        cardsPage.getVerticalScrollBar().setUnitIncrement(32);
        cardsPage.getAccessibleContext().setAccessibleName("Saved runs by day; scroll for older runs");
        cardsPage.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (cardsPage.isShowing()) { poll.start(); check(); } else poll.stop();
        });
        views.setOpaque(false);
        views.add(cardsPage, CARDS);
        views.add(table, TABLE);

        // The view toggle: Analyst's above both views, Simple's in each view's ⋯ menu.
        viewSwitch.getAccessibleContext().setAccessibleName("Runs view");
        viewSwitch.onChange(index -> show(index == 1, true));
        viewRow.setName("run-feed-view-row");
        viewRow.setOpaque(false);
        viewRow.add(viewSwitch);
        FilterBar tableBar = table instanceof ArchiveWorkspace ? ((ArchiveWorkspace<?, ?, ?>) table).filterBar() : find(table, FilterBar.class);
        if (tableBar != null) {   // first in the Table view's own ⋯, above its library, saved views and exports
            cardsItem = new JMenuItem("Cards view");
            cardsItem.setName("run-feed-cards-item");
            cardsItem.addActionListener(e -> show(false, true));
            cardsSeparator = new JPopupMenu.Separator();
            JPopupMenu menu = tableBar.overflow().menu();
            menu.insert(cardsItem, 0);
            if (menu.getComponentCount() > 1) menu.insert(cardsSeparator, 1);
            tableBar.overflow().setVisible(true);
        }
        add(viewRow, BorderLayout.NORTH);
        add(views, BorderLayout.CENTER);
        show(TABLE.equals(read.apply(VIEW_KEY)), false);
        mode.bind(this, this::modeChanged);
        render();
    }

    /** The Table view's component (the Runs archive workspace). */
    public JComponent table() { return table; }
    /** Whether the Table view is the one shown. */
    public boolean tableShown() { return tableShown; }
    /** Explicit navigation (a route to a row, Browse saved history): brings the Table view forward and remembers it. EDT. */
    public void showTable() { show(true, true); }
    /** Explicit navigation to the cards: brings the Cards view forward and remembers it. EDT. */
    public void showCards() { show(false, true); }
    /** What Enter, Space or a double-click on a card runs (the run's exact reference). */
    public void onOpen(Consumer<VisitRef> action) { open = Objects.requireNonNull(action, "action"); }
    /** The query the cards show (or are loading). */
    public RunFeedQuery query() { return query; }
    /** Reads the feed again now, keeping as many runs loaded (⋯ Refresh). EDT. */
    public void refresh() { request(Kind.REFRESH); }

    /** Releases the loaded page's pinned result and stops the worker; the Table view is closed by its owner. EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        poll.stop();
        typing.stop();
        if (page != null) { page.close(); page = null; }
        worker.shutdownNow();
    }

    /** The applied model, or null before the first read (tests). */
    RunFeedModel model() { return page == null ? null : page.model(); }
    /** The day's card list, or null when that day does not show (tests). */
    TileList<RunCardModel> list(LocalDate day) { Section section = sections.get(day); return section == null ? null : section.list(); }
    /** The shown empty state, or null (tests). */
    EmptyState emptyState() { return emptyHolder.isVisible() ? empty : null; }
    /** Whether a read is in flight (tests). */
    boolean loading() { return loading || loadingMore; }
    FilterBar filterBar() { return filterBar; }

    /** Shows the Table view ({@code table}) or the Cards view; a user's or a route's choice is remembered, a restore is not. */
    private void show(boolean table, boolean remember) {
        tableShown = table;
        if (remember) write.accept(VIEW_KEY, table ? TABLE : CARDS);
        viewLayout.show(views, table ? TABLE : CARDS);
        viewSwitch.setSelected(table ? 1 : 0);
        views.revalidate();
        views.repaint();
    }

    /** Analyst: the Cards/Table toggle above both views; Simple: the other view in each view's ⋯ menu. */
    private void modeChanged(DisplayModeModel.Mode value) {
        boolean analyst = value == DisplayModeModel.Mode.ANALYST;
        viewRow.setVisible(analyst);
        viewItem.setVisible(!analyst);
        if (cardsItem != null) { cardsItem.setVisible(!analyst); cardsSeparator.setVisible(!analyst); }
        revalidate();
        repaint();
    }

    /** The drawer: one checkbox per outcome and the dungeon. A change reads the new query's first page. */
    private JComponent drawer() {
        JPanel boxes = ContentStyle.controls();
        boxes.setOpaque(false);
        for (RunOutcome outcome : RunOutcome.values()) {
            JCheckBox box = new JCheckBox(outcome.label());
            box.setName("run-feed-outcome-" + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            box.setOpaque(false);
            box.getAccessibleContext().setAccessibleName("Outcome " + outcome.label());
            box.addActionListener(e -> applyControls());
            outcomes.put(outcome, box);
            boxes.add(box);
        }
        map.setName("run-feed-map");
        map.addItem(ALL_DUNGEONS);
        map.setPrototypeDisplayValue("Lost Halls of the Shatters");
        map.setToolTipText("The dungeons among the runs read so far");
        map.addActionListener(e -> applyControls());
        JLabel outcomeLabel = KitText.caption("Outcome"), mapLabel = KitText.caption("Dungeon");
        mapLabel.setLabelFor(map);
        map.getAccessibleContext().setAccessibleName("Dungeon");
        JPanel dungeon = new WrapRow(mapLabel, map);
        return KitLayouts.stack(Tokens.XS, outcomeLabel, boxes, dungeon);
    }

    private void typed() { if (!updating) typing.restart(); }

    /** The controls' query, applied when it differs from the shown one. */
    private void applyControls() {
        if (updating || closed) return;
        Set<RunOutcome> chosen = EnumSet.noneOf(RunOutcome.class);
        for (Map.Entry<RunOutcome, JCheckBox> entry : outcomes.entrySet()) if (entry.getValue().isSelected()) chosen.add(entry.getKey());
        Object dungeon = map.getSelectedItem();
        setQuery(new RunFeedQuery(search.getText().trim(), chosen, ALL_DUNGEONS.equals(dungeon) ? null : (String) dungeon));
    }

    /** Shows {@code next}'s runs: the controls and chips follow it, and its first page is read. */
    void setQuery(RunFeedQuery next) {
        Objects.requireNonNull(next, "next");
        if (next.equals(query)) return;
        query = next;
        syncControls();
        request(Kind.QUERY);
    }

    /** The controls and the filter chips show {@link #query}, without reading. */
    private void syncControls() {
        updating = true;
        try {
            if (!search.getText().trim().equals(query.text())) search.setText(query.text());
            for (Map.Entry<RunOutcome, JCheckBox> entry : outcomes.entrySet()) entry.getValue().setSelected(query.outcomes().contains(entry.getKey()));
            if (query.map() != null && !maps.contains(query.map())) { maps.add(query.map()); rebuildMaps(); }
            map.setSelectedItem(query.map() == null ? ALL_DUNGEONS : query.map());
        } finally { updating = false; }
        List<FilterBar.ActiveFilter> active = new ArrayList<>();
        if (!query.text().isEmpty()) active.add(new FilterBar.ActiveFilter("Search active", () -> setQuery(new RunFeedQuery("", query.outcomes(), query.map()))));
        if (!query.outcomes().isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (RunOutcome outcome : query.outcomes()) labels.add(outcome.label());
            active.add(new FilterBar.ActiveFilter("Outcome: " + String.join(", ", labels), () -> setQuery(new RunFeedQuery(query.text(), Set.of(), query.map()))));
        }
        if (query.map() != null)
            active.add(new FilterBar.ActiveFilter("Dungeon: " + query.map(), () -> setQuery(new RunFeedQuery(query.text(), query.outcomes(), null))));
        filterBar.setActive(active, () -> setQuery(RunFeedQuery.all()));
    }

    /** The dungeon choices: every dungeon among the runs read so far, and the chosen one. */
    private void rebuildMaps() {
        boolean was = updating;
        updating = true;
        try {
            Object selected = map.getSelectedItem();
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            model.addElement(ALL_DUNGEONS);
            for (String name : maps) model.addElement(name);
            map.setModel(model);
            map.setSelectedItem(selected == null ? ALL_DUNGEONS : selected);
        } finally { updating = was; }
    }

    /**
     * The Cards view was shown, or its poll ticked: reads only when nothing is loaded yet or the store's stamp changed, never
     * while a read is in flight or the Table view shows. EDT.
     */
    void check() {
        if (closed || tableShown || loading || loadingMore) return;
        request(Kind.CHECK);
    }

    /** Starts a read on the worker; a newer request makes older results inert. EDT. */
    private void request(Kind kind) {
        if (closed) return;
        Feed feed = feeds.get();
        if (feed == null) {   // no saved history is open: the Cards view says so and offers the table
            generation++; cancel.cancel();
            unavailable = true; loading = loadingMore = false;
            render();
            return;
        }
        unavailable = false;
        if (kind == Kind.MORE && (page == null || loading || loadingMore || !page.model().more())) return;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        RunFeedQuery wanted = query;
        RunFeedSource.Page from = page;
        boolean same = from != null && from.query().equals(wanted);
        Object known = kind == Kind.CHECK && same ? loadedStamp : null;
        int keep = same && kind != Kind.QUERY ? from.model().cards().size() : 0;
        if (kind == Kind.MORE) loadingMore = true; else { loading = true; loadingMore = false; }
        failure = null;
        render();
        try {
            worker.execute(() -> {
                Result result;
                try {
                    result = kind == Kind.MORE ? new Result(kind, null, feed.more(from, token), false, null) : read(feed, wanted, known, keep, token, kind);
                } catch (CancellationException cancelled) {
                    return;   // a newer request replaced this one
                } catch (Exception | Error failed) {
                    result = new Result(kind, null, null, false, failed);
                }
                Result done = result;
                SwingUtilities.invokeLater(() -> apply(ticket, done));
            });
        } catch (RejectedExecutionException shutDown) { loading = loadingMore = false; }
    }

    /** On the worker: the stamp, then (unless it is {@code known}) the first page and further pages up to {@code keep} runs. */
    private static Result read(Feed feed, RunFeedQuery query, Object known, int keep, Cancellation cancel, Kind kind) throws IOException {
        Object stamp = feed.stamp(cancel);
        if (known != null && known.equals(stamp)) return new Result(kind, stamp, null, true, null);
        RunFeedSource.Page next = feed.first(query, cancel);
        try {
            while (next.model().more() && next.model().cards().size() < keep) {
                RunFeedSource.Page grown = feed.more(next, cancel);
                next.close();
                next = grown;
            }
        } catch (IOException | RuntimeException | Error failed) { next.close(); throw failed; }
        return new Result(kind, stamp, next, false, null);
    }

    /** EDT: applies the newest request's result; an older one only releases its page. */
    private void apply(long ticket, Result result) {
        if (closed || ticket != generation) { if (result.page() != null) result.page().close(); return; }
        if (result.kind() == Kind.MORE) loadingMore = false; else loading = false;
        if (result.failure() != null) {
            Throwable cause = result.failure();
            while (cause.getCause() != null) cause = cause.getCause();
            failure = cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
            failedMore = result.kind() == Kind.MORE;
            render();
            return;
        }
        if (result.unchanged()) { render(); return; }
        RunFeedSource.Page old = page;
        page = result.page();
        if (result.stamp() != null) loadedStamp = result.stamp();
        if (old != null && old != page) old.close();
        boolean added = false;
        if (page.query().map() == null) for (RunCardModel card : page.model().cards()) if (card.map() != null && !card.map().isBlank()) added |= maps.add(card.map());
        if (added) rebuildMaps();
        render();
    }

    /** EDT: the days, summary, warn line, "Load more" and empty state for the current state. */
    private void render() {
        RunFeedModel model = page != null && page.query().equals(query) ? page.model() : null;
        List<LocalDate> keys = new ArrayList<>();
        selecting = true;   // setItems keeps a selection by key: not a user's choice
        try {
            if (model != null && !unavailable) for (RunFeedModel.Day day : model.days()) {
                Section section = sections.computeIfAbsent(day.date(), this::section);
                section.header().setTitle(day.title());
                String counts = day.header().substring(day.title().length() + 3);
                if (!counts.equals(section.counts().getText())) section.counts().setText(counts);
                section.list().getAccessibleContext().setAccessibleName(day.header());
                section.list().setItems(day.cards());
                keys.add(day.date());
            }
            sections.keySet().retainAll(keys);
        } finally { selecting = false; }
        if (!keys.equals(order)) relayout(keys);
        groups.setVisible(!keys.isEmpty());
        more.setVisible(model != null && model.more() && !unavailable);
        more.setEnabled(!loadingMore && !loading);
        more.setText(loadingMore ? "Loading more…" : "Load more");
        summary.setText(summaryText(model));   // hidden below while an empty state says what there is
        List<String> warnings = new ArrayList<>();
        if (failure != null && model != null)
            warnings.add((failedMore ? "More saved runs could not be read: " : "Saved runs could not be read again: ") + failure
                + (failedMore ? ". Load more tries again." : ". The cards below are from the last successful read."));
        if (model != null && !page.issues().isEmpty()) warnings.add(ISSUES);
        issues.setText(String.join("\n", warnings));
        issues.setToolTipText(model == null || page.issues().isEmpty() ? null : String.join("\n", page.issues()));
        issues.setVisible(!warnings.isEmpty());
        showEmpty(model);
        summary.setVisible(!emptyHolder.isVisible());
        revalidate();
        repaint();
    }

    private String summaryText(RunFeedModel model) {
        if (unavailable) return "Saved runs · saved history is not open";
        if (model == null) return loading ? "Loading saved runs…" : failure != null ? "Saved runs could not be read" : "Saved runs · newest first";
        int shown = model.cards().size();
        StringBuilder text = new StringBuilder("Saved runs · newest first · ");
        text.append(page.matches() == shown ? shown + (shown == 1 ? " run" : " runs") : String.format(Locale.ENGLISH, "%,d of %,d loaded", shown, page.matches()));
        if (page.unplaced() > 0)
            text.append(" · ").append(page.unplaced()).append(page.unplaced() == 1 ? " run without" : " runs without")
                .append(" an entry time or visit ID only in the Table view");
        if (loading) text.append(" · refreshing…");
        return text.toString();
    }

    /**
     * One empty state instead of the days, or none: its title names the situation, its one body sentence says what to expect or do
     * and its action is the next step. The summary line is hidden meanwhile, so the page says it once.
     */
    private void showEmpty(RunFeedModel model) {
        String title = null, body = null;
        KitButton action = null;
        if (unavailable) {
            title = "Saved history is unavailable";
            body = "Saved history is not open in this app run; the Table view lists this app run's runs.";
            action = button("Show the table", this::showTable);
        } else if (model == null && loading) {
            title = "Loading saved runs";
            body = "Cards appear here once saved history has been read.";
        } else if (model == null && failure != null) {
            title = "Saved runs could not be read";
            body = (failure.endsWith(".") ? failure.substring(0, failure.length() - 1) : failure) + "; try again, or use the Table view.";
            action = button("Try again", this::refresh);
        } else if (model != null && model.cards().isEmpty()) {
            if (query.equals(RunFeedQuery.all())) {
                title = "No saved runs yet";
                body = "Dungeon runs appear here once they are saved (about every 10 s while capture is on).";
            } else {
                title = "No runs match";
                body = "Change the search or clear the filters to see other saved runs.";
                action = button("Clear filters", () -> setQuery(RunFeedQuery.all()));
            }
        }
        if (title == null) { emptyHolder.setVisible(false); emptyKey = null; return; }
        String key = title + "\n" + body;
        if (!key.equals(emptyKey)) {
            emptyHolder.removeAll();
            empty = new FeedEmpty(title, body, action);
            empty.setName("run-feed-empty");
            emptyHolder.add(empty, BorderLayout.CENTER);
            emptyKey = key;
        }
        emptyHolder.setVisible(true);
    }

    private static KitButton button(String text, Runnable action) {
        KitButton button = KitButton.secondary(text);
        button.setName("run-feed-empty-action");
        button.addActionListener(e -> action.run());
        return button;
    }

    private Section section(LocalDate day) {
        SectionHeader header = new SectionHeader("");
        header.setName("run-feed-header-" + day);
        JTextArea counts = ContentStyle.wrappingText("");
        counts.setName("run-feed-counts-" + day);
        counts.setFocusable(false);   // Tab moves from day to day; the counts are also the list's accessible name
        tint(counts);
        RunCardRenderer renderer = new RunCardRenderer(zone, () -> page == null ? System.currentTimeMillis() : page.model().capturedAt());
        TileList<RunCardModel> list = new TileList<>("run-feed-day-" + day, renderer, RunFeedView::key,
            card -> RunCardRenderer.accessibleName(card, zone, page == null ? System.currentTimeMillis() : page.model().capturedAt()));
        list.getAccessibleContext().setAccessibleDescription("Arrow keys move between this day's runs, Tab to the next day; Enter or Space opens the run");
        list.onOpen(card -> open.accept(card.ref()));
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || selecting || list.getSelectedValue() == null) return;
            selecting = true;   // one selected card in the whole feed
            try { for (Section other : sections.values()) if (other.list() != list) other.list().clearSelection(); } finally { selecting = false; }
        });
        list.addFocusListener(new FocusAdapter() {
            // Tab into a day selects its first run, so Enter opens something and a reader announces a card.
            @Override public void focusGained(FocusEvent e) { if (list.isSelectionEmpty() && list.getModel().getSize() > 0) list.setSelectedIndex(0); }
        });
        JPanel panel = KitLayouts.stack(Tokens.XS, header, counts, list);
        panel.setName("run-feed-section-" + day);
        return new Section(header, counts, list, panel);
    }

    /** A card's stable identity: its exact session and visit. */
    static String key(RunCardModel card) { return card.ref().sessionId + "/" + card.ref().visitId; }

    /** Stacks the day sections newest first, a section gap apart. */
    private void relayout(List<LocalDate> keys) {
        groups.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < keys.size(); i++) {
            c.gridy = i;
            c.insets = new Insets(i == 0 ? 0 : Tokens.L, 0, 0, 0);
            groups.add(sections.get(keys.get(i)).panel(), c);
        }
        order = List.copyOf(keys);
        groups.revalidate();
        groups.repaint();
    }

    /**
     * The feed's empty state on the page's left edge, as the filter bar and the day headers above it: the kit's EmptyState centers
     * its title over a body that fills the row (and so reads left-aligned), so here the title and the action start where the
     * body's text starts.
     */
    private static final class FeedEmpty extends EmptyState {
        FeedEmpty(String title, String body, KitButton action) {
            super(title, body, action);
            GridBagLayout layout = (GridBagLayout) getLayout();
            JTextArea text = null;
            for (Component child : getComponents()) if (child instanceof JTextArea) text = (JTextArea) child;
            int indent = text == null ? 0 : text.getInsets().left;
            for (Component child : getComponents()) {
                GridBagConstraints c = layout.getConstraints(child);
                c.anchor = GridBagConstraints.LINE_START;
                c.insets = new Insets(c.insets.top, child == text ? 0 : indent, c.insets.bottom, child == text ? c.insets.right : 0);
                layout.setConstraints(child, c);
            }
        }
    }

    /** Muted metadata text that follows the theme. */
    private static void tint(JTextArea area) {
        area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        area.addPropertyChangeListener("UI", e -> area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED)));
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type); if (found != null) return found; }
        }
        return null;
    }

    /** Production reads: one {@link RunFeedSource} per open store (its facts cache lives as long as the store), none without one. */
    private static Supplier<Feed> sources(Supplier<SessionStore> stores) {
        Objects.requireNonNull(stores, "stores");
        Object[] made = new Object[2];   // {store, its feed}: EDT only
        return () -> {
            SessionStore store = stores.get();
            if (store == null) return null;
            if (made[0] != store) { made[0] = store; made[1] = feed(store, new RunFeedSource(store, ZoneId.systemDefault(), System::currentTimeMillis)); }
            return (Feed) made[1];
        };
    }

    /** {@code source} over {@code store}, stamped by {@link #stamp}. */
    static Feed feed(SessionStore store, RunFeedSource source) {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(source, "source");
        return new Feed() {
            @Override public Object stamp(Cancellation cancel) throws IOException { return RunFeedView.stamp(store, cancel); }
            @Override public RunFeedSource.Page first(RunFeedQuery query, Cancellation cancel) throws IOException { return source.first(query, cancel); }
            @Override public RunFeedSource.Page more(RunFeedSource.Page previous, Cancellation cancel) throws IOException { return source.more(previous, cancel); }
        };
    }

    /** Files of a session folder the feed's cards come from: its metadata and the runs, loot, fame and combat modules. */
    private static final Set<String> READ = Set.of("session.json", "runs", "runs.jsonl", "loot", "loot.jsonl", "fame", "fame.jsonl",
        "fame-latest", "fame-latest.jsonl", CombatFacts.RECORDS, CombatFacts.RECORDS + ".jsonl");

    /**
     * The store's stamp for the feed, off the EDT: every session folder's name and, of the files the feed reads (its metadata and
     * the runs, loot, fame, fame-latest and encounters modules), each one's name, size and modification time (a module folder's
     * own time changes when a file is saved into it or removed). A new, ended, imported or deleted session, a saved visit, a bag,
     * a fame reading or a combat record changes it; the chat and timeline journals the current session keeps writing do not.
     */
    static List<String> stamp(SessionStore store, Cancellation cancel) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Stamp saved history off the EDT");
        List<String> stamp = new ArrayList<>();
        Path root = store.directory();
        if (!Files.isDirectory(root)) return stamp;
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(root)) {
            for (Path session : sessions) {
                cancel.check();
                if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) continue;
                String id = session.getFileName().toString();
                stamp.add(id);
                try (DirectoryStream<Path> files = Files.newDirectoryStream(session)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (!READ.contains(name)) continue;
                        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        stamp.add(id + "/" + name + ":" + attributes.size() + ":" + attributes.lastModifiedTime().toMillis());
                    }
                } catch (NoSuchFileException | NotDirectoryException gone) { stamp.add(id + ":gone"); }
            }
        }
        Collections.sort(stamp);
        return stamp;
    }
}
