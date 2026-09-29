package tomato.gui.runs;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.DungeonAnalysis;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * The Runs &amp; DPS page's Dungeons tab (spec §6.3 Dungeons; user decisions 2026-09-28): one painted card per dungeon over every
 * saved session ({@link DungeonsSource}: completion over finished runs, labeled observed; average duration, loot per completed
 * run and best personal DPS from completed runs only; each unknown "—" with its reason) and, in Analyst mode, the Analysis view
 * (session comparison, A/B cohorts, dungeon statistics and the other saved dungeon analyses, saved history only).
 * - Views: Cards (default) and Analysis, remembered in {@link #VIEW_KEY}; restoring the preference only selects. Analyst shows a
 *   Cards · Analysis switch ({@code dungeons-view-mode}); Simple hides it, and switching to Simple while Analysis shows returns
 *   to the cards (the preference is kept: the mode's switch is not the user's choice).
 * - Cards: a {@code dungeons} FilterBar (search {@code dungeons-search} over the dungeon's name; a drawer with the sort
 *   {@code dungeons-sort}: Most visits, Most recent, Name; ⋯ Refresh), the summary line {@code dungeons-summary}, a warn line
 *   {@code dungeons-issues} when saved sessions could not be read fully, the TileList {@code dungeons-cards}, or one empty state
 *   (saved history unavailable, loading, unreadable, no saved dungeon runs, no match) in place of the summary line. The search
 *   and sort apply to the loaded cards on the EDT (a few dozen immutable cards), never a new read.
 * - Reads run on one daemon worker ("RealmShark dungeons") through {@link DungeonsSource}, never on the EDT: a newer request
 *   cancels the older one and only the newest result is applied (a generation guard). The cards read when they first show;
 *   afterwards showing them again, and a check every {@value #POLL_MILLIS} ms while they show, compare the store's stamp (the
 *   run feed's: the session folders and the files the cards come from) and read again only when it changed. ⋯ Refresh always
 *   reads. An unchanged card list fires no event.
 * - Card actions: Enter, Space or a double-click shows the dungeon's runs ({@link #onOpenRuns}); a click on a card's painted
 *   action, or its menu (Shift+F10, the context-menu key or a right click), runs Show runs, Open best run (the exact run and
 *   recording, {@link #onOpenRecap}; only when known) or Analyze (Analyst: the Analysis view with that dungeon as its facet).
 * - Analysis: built by the caller's factory on its first show (a restored preference selects it but builds nothing until it
 *   shows; the Dungeons analysis workspace reads saved history from its constructor). {@link #close} closes the worker and the
 *   built view.
 * EDT only, except the reads.
 */
public final class DungeonsView extends JPanel implements AutoCloseable {
    public static final String VIEW_KEY = "ui.dungeons.view", CARDS = "cards", ANALYSIS = "analysis";
    /** How often the showing cards compare the store's stamp (visits are saved about every 10 s while capturing). */
    static final int POLL_MILLIS = 30_000;
    static final String ISSUES = "Some saved sessions could not be read fully; the cards say what is missing.";

    /** How the view reads the cards: a {@link DungeonsSource} over the history store in production. Off the EDT only. */
    interface Cards {
        /** What the cards depend on; an equal stamp means a read would show the same cards. */
        Object stamp(Cancellation cancel) throws IOException;
        /** Every dungeon's card, most visited first (the view filters and orders them). */
        DungeonsModel read(Cancellation cancel) throws IOException;
    }

    /** What a request does: compare the stamp first, or always read. */
    private enum Kind { CHECK, REFRESH }

    /** A worker's answer: a model (with the stamp read before it), no change, or a failure. */
    private record Result(Kind kind, Object stamp, DungeonsModel model, boolean unchanged, Throwable failure) {}

    private final Supplier<Cards> cards;
    private final Supplier<JComponent> factory;
    private final BiConsumer<String, String> write;
    private final ThreadPoolExecutor worker;
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private final JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    private final SegmentedControl viewSwitch = new SegmentedControl("dungeons-view-mode", "Cards", "Analysis");
    private final FilterBar filterBar = new FilterBar("dungeons");
    private final JTextField search = new JTextField(18);
    private final JComboBox<DungeonsQuery.Sort> sort = new JComboBox<>(DungeonsQuery.Sort.values());
    private final JTextArea summary = ContentStyle.wrappingText("");
    private final Banner issues = new Banner("dungeons-issues");
    private final DungeonCardRenderer renderer;
    private final TileList<DungeonCardModel> list;
    private final JPanel emptyHolder = new JPanel(new BorderLayout());
    private final JScrollPane cardsPage;
    private final JPanel analysisHolder = new JPanel(new BorderLayout(0, Tokens.S));
    private final javax.swing.Timer poll = new javax.swing.Timer(POLL_MILLIS, e -> check());
    private Consumer<String> openRuns = canonical -> { };
    private BiConsumer<VisitRef, String> openRecap = (ref, recording) -> { };
    private DungeonsQuery query = DungeonsQuery.all();
    private DungeonsModel model;
    private List<DungeonCardModel> shown = List.of();
    private Object loadedStamp;
    /** The last read's failure in words (null after a successful read) and the shown empty state's title and body. */
    private String failure, emptyKey;
    private EmptyState empty;
    private JComponent analysis;
    private boolean analysisShown, analyst, loading, unavailable, updating, closed;
    private long generation;
    private Cancellation cancel = new Cancellation();

    /**
     * The production tab: cards from {@code store}'s saved history (null while none is open: the cards say so) read in
     * {@code zone} with {@code clock}, the Analysis view from {@code analysis} (built on its first show; Task 12 passes
     * {@code DungeonAnalysis.workspace(...)}), the application's display mode and the view preference in the app's properties.
     */
    public DungeonsView(Supplier<SessionStore> store, ZoneId zone, LongSupplier clock, Supplier<JComponent> analysis) {
        this(sources(store, zone, clock), analysis, DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    /** As above with the reader, the display mode and the preference store (tests). */
    DungeonsView(Supplier<Cards> cards, Supplier<JComponent> analysis, DisplayModeModel mode, Function<String, String> read, BiConsumer<String, String> write) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Dungeons tab on the EDT");
        this.cards = Objects.requireNonNull(cards, "cards");
        this.factory = Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(mode, "mode");
        this.write = Objects.requireNonNull(write, "write");
        setName("dungeons-view");
        setOpaque(false);
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "RealmShark dungeons");
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);

        // Cards: the filter row, summary and warn line scroll with the cards.
        search.setName("dungeons-search");
        search.getAccessibleContext().setAccessibleName("Search dungeons");
        search.putClientProperty("JTextField.placeholderText", "Search dungeons");
        search.setToolTipText("Shows the dungeons whose name contains these words");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyControls(); }
            public void removeUpdate(DocumentEvent e) { applyControls(); }
            public void changedUpdate(DocumentEvent e) { applyControls(); }
        });
        filterBar.search(new WrapRow(search)).drawer(drawer());
        filterBar.overflow().add("Refresh", this::refresh).setName("dungeons-refresh");
        summary.setName("dungeons-summary");
        summary.setFocusable(false);
        tint(summary);
        issues.setTone(Tokens.Tone.WARN);
        issues.setVisible(false);
        renderer = new DungeonCardRenderer(() -> this.analyst);
        list = new TileList<>("dungeons-cards", renderer, DungeonCardModel::canonical, card -> DungeonCardRenderer.accessibleName(card, this.analyst));
        list.getAccessibleContext().setAccessibleName("Dungeons, one card each");
        list.getAccessibleContext().setAccessibleDescription("Arrow keys move between dungeons; Enter or Space shows the dungeon's runs; Shift+F10 opens its other actions");
        list.onOpen(this::openFromList);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 1 || !SwingUtilities.isLeftMouseButton(e)) return;
                int index = index(e.getPoint());
                DungeonCardRenderer.Action action = actionAt(e.getPoint());
                if (index >= 0 && action != null) run(action, list.getModel().getElementAt(index));
            }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        for (KeyStroke key : new KeyStroke[] {KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0)})
            list.getInputMap(JComponent.WHEN_FOCUSED).put(key, "dungeons-card-menu");
        list.getActionMap().put("dungeons-card-menu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int index = list.getSelectedIndex();
                Rectangle cell = index < 0 ? null : list.getCellBounds(index, index);
                if (cell != null) cardMenu(list.getModel().getElementAt(index)).show(list, cell.x + Tokens.M, cell.y + Tokens.M);
            }
        });
        list.addFocusListener(new FocusAdapter() {
            // Tab into the cards selects the first, so Enter opens something and a reader announces a card.
            @Override public void focusGained(FocusEvent e) { if (list.isSelectionEmpty() && list.getModel().getSize() > 0) list.setSelectedIndex(0); }
        });
        emptyHolder.setOpaque(false);
        emptyHolder.setVisible(false);
        JPanel body = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }   // the page scrolls the cards, never squeezes them
        };
        body.setOpaque(false);
        body.add(KitLayouts.stack(Tokens.L, list, emptyHolder), BorderLayout.NORTH);
        cardsPage = ContentStyle.page(KitLayouts.stack(Tokens.S, filterBar, summary, issues), body, null);
        cardsPage.setName("dungeons-scroll");
        cardsPage.getVerticalScrollBar().setUnitIncrement(32);
        cardsPage.getAccessibleContext().setAccessibleName("Dungeons; scroll for more");
        cardsPage.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (cardsPage.isShowing()) { poll.start(); check(); } else poll.stop();
        });

        // Analysis: the analysis view, built on its first show.
        analysisHolder.setName("dungeons-analysis");
        analysisHolder.setOpaque(false);
        analysisHolder.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && analysisHolder.isShowing() && analysisShown) ensureAnalysis();
        });
        views.setOpaque(false);
        views.add(cardsPage, CARDS);
        views.add(analysisHolder, ANALYSIS);

        viewSwitch.getAccessibleContext().setAccessibleName("Dungeons view");
        viewSwitch.onChange(index -> select(index == 1, true));
        modeRow.setName("dungeons-view-mode-row");
        modeRow.setOpaque(false);
        modeRow.add(viewSwitch);
        add(modeRow, BorderLayout.NORTH);
        add(views, BorderLayout.CENTER);
        select(ANALYSIS.equals(read.apply(VIEW_KEY)), false);
        mode.bind(this, this::modeChanged);
        syncControls();
        render();
    }

    /** What Show runs, Enter, Space or a double-click runs: the card's canonical dungeon name. */
    public void onOpenRuns(Consumer<String> action) { openRuns = Objects.requireNonNull(action, "action"); }
    /** What Open best run runs: the best run's exact reference and its recording ID (null when the record has none). */
    public void onOpenRecap(BiConsumer<VisitRef, String> action) { openRecap = Objects.requireNonNull(action, "action"); }
    /** Explicit navigation to the cards: brings them forward and remembers it. EDT. */
    public void showCards() { select(false, true); }
    /** Whether the Analysis view is the one shown (or selected while the tab is hidden). */
    public boolean analysisShown() { return analysisShown; }
    /**
     * Analyze: shows the Analysis view (building it on first use) with {@code canonical} as its dungeon. Analyst only; in Simple
     * nothing happens. EDT.
     */
    public void analyze(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        if (closed || !analyst) return;
        select(true, true);
        DungeonAnalysis.analyze(analysis, canonical);
    }
    /** Reads the cards again now (⋯ Refresh). EDT. */
    public void refresh() { request(Kind.REFRESH); }

    /** Stops the worker and closes the Analysis view if it was built. Idempotent. EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        poll.stop();
        worker.shutdownNow();
        if (analysis instanceof AutoCloseable) {
            try { ((AutoCloseable) analysis).close(); }
            catch (RuntimeException failed) { throw failed; }
            catch (Exception failed) { throw new IllegalStateException(failed); }
        }
    }

    /** The applied model, or null before the first read (tests). */
    DungeonsModel model() { return model; }
    /** The cards shown, filtered and in order (tests). */
    List<DungeonCardModel> shownCards() { return shown; }
    /** The shown empty state, or null (tests). */
    EmptyState emptyState() { return emptyHolder.isVisible() ? empty : null; }
    /** Whether a read is in flight (tests). */
    boolean loading() { return loading; }
    FilterBar filterBar() { return filterBar; }
    TileList<DungeonCardModel> cardList() { return list; }
    DungeonsQuery query() { return query; }
    /** The built Analysis view, or null (tests). */
    JComponent analysis() { return analysis; }

    /** Shows the Analysis view or the cards; a user's or a route's choice is remembered, a restore or the mode's is not. */
    private void select(boolean toAnalysis, boolean explicit) {
        if (closed || toAnalysis && explicit && !analyst) return;   // Analysis is Analyst-only
        analysisShown = toAnalysis;
        if (explicit) write.accept(VIEW_KEY, toAnalysis ? ANALYSIS : CARDS);
        viewLayout.show(views, toAnalysis ? ANALYSIS : CARDS);
        viewSwitch.setSelected(toAnalysis ? 1 : 0);
        if (toAnalysis && (explicit || analysisHolder.isShowing())) ensureAnalysis();
        views.revalidate();
        views.repaint();
    }

    /** Builds the Analysis view once, on its first show or an explicit Analyze. */
    private void ensureAnalysis() {
        if (analysis != null || closed) return;
        JComponent built = Objects.requireNonNull(factory.get(), "analysis view");
        analysis = built;
        analysisHolder.add(built, BorderLayout.CENTER);
        analysisHolder.revalidate();
        analysisHolder.repaint();
    }

    /** Analyst: the Cards · Analysis switch and Analyze; Simple hides both and returns to the cards. */
    private void modeChanged(DisplayModeModel.Mode value) {
        analyst = value == DisplayModeModel.Mode.ANALYST;
        modeRow.setVisible(analyst);
        if (!analyst && analysisShown) select(false, false);
        list.repaint();
        revalidate();
        repaint();
    }

    /** The drawer: the cards' order. */
    private JComponent drawer() {
        sort.setName("dungeons-sort");
        sort.getAccessibleContext().setAccessibleName("Sort dungeons");
        sort.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof DungeonsQuery.Sort ? ((DungeonsQuery.Sort) value).label() : value, index, selected, focus);
            }
        });
        sort.addActionListener(e -> applyControls());
        JLabel label = KitText.caption("Sort");
        label.setLabelFor(sort);
        return new WrapRow(label, sort);
    }

    /** The controls' query, applied when it differs from the shown one. */
    private void applyControls() {
        if (updating || closed) return;
        setQuery(new DungeonsQuery(search.getText(), (DungeonsQuery.Sort) sort.getSelectedItem()));
    }

    /** Shows {@code next}'s cards from those loaded: the controls and chips follow it; nothing is read. EDT. */
    void setQuery(DungeonsQuery next) {
        Objects.requireNonNull(next, "next");
        if (next.equals(query)) return;
        query = next;
        syncControls();
        render();
    }

    /** The controls and the filter chip show {@link #query}, without reading. Sorting is not a filter: it has no chip. */
    private void syncControls() {
        updating = true;
        try {
            if (!search.getText().trim().equals(query.text())) search.setText(query.text());
            sort.setSelectedItem(query.sort());
        } finally { updating = false; }
        List<FilterBar.ActiveFilter> active = new ArrayList<>();
        if (!query.text().isEmpty()) active.add(new FilterBar.ActiveFilter("Search active", this::clearSearch));
        filterBar.setActive(active, this::clearSearch);
    }

    private void clearSearch() { setQuery(new DungeonsQuery("", query.sort())); }

    /** The cards were shown, or their poll ticked: reads only when nothing is loaded yet or the store's stamp changed. EDT. */
    void check() {
        if (closed || analysisShown || loading) return;
        request(Kind.CHECK);
    }

    /** Starts a read on the worker; a newer request makes older results inert. EDT. */
    private void request(Kind kind) {
        if (closed) return;
        Cards source = cards.get();
        if (source == null) {   // no saved history is open: the tab says so
            generation++; cancel.cancel();
            unavailable = true; loading = false;
            render();
            return;
        }
        unavailable = false;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        Object known = kind == Kind.CHECK && model != null ? loadedStamp : null;
        loading = true;
        failure = null;
        render();
        try {
            worker.execute(() -> {
                Result result;
                try {
                    Object stamp = source.stamp(token);
                    result = known != null && known.equals(stamp) ? new Result(kind, stamp, null, true, null)
                        : new Result(kind, stamp, source.read(token), false, null);
                } catch (CancellationException cancelled) {
                    return;   // a newer request replaced this one
                } catch (Exception | Error failed) {
                    result = new Result(kind, null, null, false, failed);
                }
                Result done = result;
                SwingUtilities.invokeLater(() -> apply(ticket, done));
            });
        } catch (RejectedExecutionException shutDown) { loading = false; }
    }

    /** EDT: applies the newest request's result. */
    private void apply(long ticket, Result result) {
        if (closed || ticket != generation) return;
        loading = false;
        if (result.failure() != null) {
            failure = safe(result.failure());
        } else if (!result.unchanged()) {
            model = result.model();
            loadedStamp = result.stamp();
        }
        render();
    }

    /**
     * A read failure in words without a path: the root cause's message unless it names a file path (NIO failures such as
     * {@code AccessDeniedException} carry the absolute history path), else its kind, as the Recordings view words them.
     */
    static String safe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() || message.contains("/") || message.contains("\\") ? cause.getClass().getSimpleName() : message;
    }

    /** EDT: the cards, summary, warn line and empty state for the current state. */
    private void render() {
        DungeonsModel current = unavailable ? null : model;
        List<DungeonCardModel> next = new ArrayList<>();
        if (current != null) for (DungeonCardModel card : current.cards()) if (query.matches(card)) next.add(card);
        next.sort(query.order());
        shown = List.copyOf(next);
        list.setItems(shown);   // an equal list fires nothing
        list.setVisible(!shown.isEmpty());
        summary.setText(summaryText(current));
        List<String> warnings = new ArrayList<>();
        if (failure != null && current != null)
            warnings.add("Saved runs could not be read again: " + failure + ". The cards below are from the last successful read.");
        boolean partial = current != null && (current.sessionsSkipped() > 0 || !current.issues().isEmpty());
        if (partial) warnings.add(ISSUES);
        issues.setText(String.join("\n", warnings));
        issues.setToolTipText(partial ? String.join("\n", current.issues()) : null);
        issues.setVisible(!warnings.isEmpty());
        showEmpty(current);
        summary.setVisible(!emptyHolder.isVisible());
        revalidate();
        repaint();
    }

    private String summaryText(DungeonsModel current) {
        if (unavailable) return "Saved runs · saved history is not open";
        if (current == null) return loading ? "Loading saved runs…" : failure != null ? "Saved runs could not be read" : "Saved runs · all sessions";
        int total = current.cards().size();
        StringBuilder text = new StringBuilder("Saved runs · all sessions · ");
        text.append(shown.size() == total ? count(total, "dungeon") : shown.size() + " of " + count(total, "dungeon"));
        text.append(" · ").append(count(current.runs(), "run")).append(" · ").append(switch (query.sort()) {
            case MOST_VISITS -> "most visits first";
            case RECENT -> "most recent first";
            case NAME -> "by name";
        });
        if (loading) text.append(" · refreshing…");
        return text.toString();
    }

    private static String count(int value, String noun) { return String.format(Locale.ENGLISH, "%,d", value) + " " + noun + (value == 1 ? "" : "s"); }

    /**
     * One empty state instead of the cards, or none: its title names the situation, its body says what to expect or do and its
     * action is the next step. The summary line is hidden meanwhile, so the tab says it once.
     */
    private void showEmpty(DungeonsModel current) {
        String title = null, body = null;
        KitButton action = null;
        if (unavailable) {
            title = "Saved history is unavailable";
            body = "Saved history is not open in this app run; dungeon cards are built from saved runs.";
        } else if (current == null && loading) {
            title = "Loading saved runs";
            body = "Dungeon cards appear here once saved history has been read.";
        } else if (current == null && failure != null) {
            title = "Saved runs could not be read";
            body = (failure.endsWith(".") ? failure.substring(0, failure.length() - 1) : failure) + "; try again.";
            action = button("Try again", this::refresh);
        } else if (current != null && current.cards().isEmpty()) {
            title = "No saved dungeon runs yet";
            body = "A card per dungeon appears here once dungeon runs are saved (about every 10 s while capture is on).";
        } else if (current != null && shown.isEmpty()) {
            title = "No dungeons match";
            body = "Change the search, or clear it to see every dungeon.";
            action = button("Clear filters", this::clearSearch);
        }
        if (title == null) { emptyHolder.setVisible(false); emptyKey = null; return; }
        String key = title + "\n" + body;
        if (!key.equals(emptyKey)) {
            emptyHolder.removeAll();
            empty = new EmptyState(title, body, action);
            empty.setName("dungeons-empty");
            emptyHolder.add(empty, BorderLayout.CENTER);
            emptyKey = key;
        }
        emptyHolder.setVisible(true);
    }

    private static KitButton button(String text, Runnable action) {
        KitButton button = KitButton.secondary(text);
        button.setName("dungeons-empty-action");
        button.addActionListener(e -> action.run());
        return button;
    }

    /** Runs a card's action: Show runs, Open best run (only when known) or Analyze (Analyst only). EDT. */
    void run(DungeonCardRenderer.Action action, DungeonCardModel card) {
        if (closed || card == null) return;
        switch (action) {
            case RUNS -> openRuns.accept(card.canonical());
            case BEST -> { if (card.bestRun() != null) openRecap.accept(card.bestRun(), card.bestRecordingId()); }
            case ANALYZE -> analyze(card.canonical());
        }
    }

    /** Enter, Space or a double-click: Show runs, except for a double-click on a painted action, which runs only that action. */
    private void openFromList(DungeonCardModel card) {
        AWTEvent event = EventQueue.getCurrentEvent();
        if (event instanceof MouseEvent && ((MouseEvent) event).getComponent() == list && actionAt(((MouseEvent) event).getPoint()) != null) return;
        run(DungeonCardRenderer.Action.RUNS, card);
    }

    /** The card's menu: its three actions (Open best run disabled without a known best run, Analyze only in Analyst). */
    JPopupMenu cardMenu(DungeonCardModel card) {
        JPopupMenu menu = new JPopupMenu(card.displayName());
        menu.setName("dungeons-card-menu");
        menu.add(item(menu, "dungeons-card-runs", DungeonCardRenderer.Action.RUNS, card));
        JMenuItem best = item(menu, "dungeons-card-best", DungeonCardRenderer.Action.BEST, card);
        best.setEnabled(card.bestRun() != null);
        if (card.bestRun() == null) best.setToolTipText(card.dpsReason());
        menu.add(best);
        JMenuItem analyze = item(menu, "dungeons-card-analyze", DungeonCardRenderer.Action.ANALYZE, card);
        analyze.setVisible(analyst);
        menu.add(analyze);
        return menu;
    }

    private JMenuItem item(JPopupMenu menu, String name, DungeonCardRenderer.Action action, DungeonCardModel card) {
        JMenuItem item = new JMenuItem(action.label);
        item.setName(name);
        item.getAccessibleContext().setAccessibleDescription(action.description);
        item.addActionListener(e -> run(action, card));
        return item;
    }

    /** A right click (the platform's popup trigger) selects the card under it and opens its menu. */
    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int index = index(e.getPoint());
        if (index < 0) return;
        list.setSelectedIndex(index);
        cardMenu(list.getModel().getElementAt(index)).show(list, e.getX(), e.getY());
    }

    /** The index of the card under {@code point} (list coordinates), or -1 outside every card. */
    private int index(Point point) {
        int index = list.locationToIndex(point);
        Rectangle cell = index < 0 ? null : list.getCellBounds(index, index);
        return cell != null && cell.contains(point) ? index : -1;
    }

    /** The painted action under {@code point} (list coordinates), or null. */
    private DungeonCardRenderer.Action actionAt(Point point) {
        int index = index(point);
        if (index < 0) return null;
        Rectangle cell = list.getCellBounds(index, index);
        return renderer.actionAt(list.getModel().getElementAt(index), point.x - cell.x, point.y - cell.y, cell.width, cell.height);
    }

    /** Muted metadata text that follows the theme. */
    private static void tint(JTextArea area) {
        area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        area.addPropertyChangeListener("UI", e -> area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED)));
    }

    /** Production reads: one {@link DungeonsSource} per open store (its per-session cache lives as long as the store), none without one. */
    private static Supplier<Cards> sources(Supplier<SessionStore> stores, ZoneId zone, LongSupplier clock) {
        Objects.requireNonNull(stores, "store"); Objects.requireNonNull(zone, "zone"); Objects.requireNonNull(clock, "clock");
        Object[] made = new Object[2];   // {store, its cards}: EDT only
        return () -> {
            SessionStore store = stores.get();
            if (store == null) return null;
            if (made[0] != store) { made[0] = store; made[1] = cards(store, new DungeonsSource(store, zone, clock)); }
            return (Cards) made[1];
        };
    }

    /** {@code source} over {@code store}, stamped as the run feed stamps it (the session folders and the files the cards read). */
    static Cards cards(SessionStore store, DungeonsSource source) {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(source, "source");
        return new Cards() {
            @Override public Object stamp(Cancellation cancel) throws IOException { return RunFeedView.stamp(store, cancel); }
            @Override public DungeonsModel read(Cancellation cancel) throws IOException { return source.read(DungeonsQuery.all(), cancel); }
        };
    }
}
