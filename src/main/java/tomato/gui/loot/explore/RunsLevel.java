package tomato.gui.loot.explore;

import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.loot.haul.HaulView;
import tomato.gui.loot.haul.LootLine;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.runs.RunCardModel;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunOutcome;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;

/**
 * Loot › Explore's Runs level: saved runs in a horizontal strip above the chosen run's full-width haul and dungeon summary.
 * The newest run opens when the first runs load; picking a card, Enter on
 * one, or {@link #openRun} (a route) opens another, including a run the feed has not loaded. A run still in progress is read again
 * whenever the feed reads new runs (about every 30 s while it shows). Reads run on one worker; only the newest request's result
 * applies. EDT only, except the reads.
 */
public final class RunsLevel extends JPanel implements AutoCloseable {
    static final String CHOOSE = "Choose a run to see what it dropped.", LOADING = "Loading this run's loot…",
        UNLINKED_LOADING = "Loading loot outside runs…", NO_UNLINKED = "Every saved bag was recorded inside a run.";

    private final RunFeedView feed;
    private final RunHauls.Loader loader;
    private final Executor worker;
    private final DungeonPanel dungeon;
    private final HaulView haul = new HaulView(HaulView.Mode.FULL);
    private final JTextArea status = ContentStyle.wrappingText(CHOOSE);
    private final JPanel unlinked = new JPanel();
    private final JPanel detail = new Detail();
    private final KitButton outside = KitButton.ghost("Loot outside runs");
    private Consumer<String> openItem = key -> { };
    private Runnable shownListener = () -> { };
    /** The run asked for (shown or loading); null while none, or while "Loot outside runs" shows. */
    private VisitRef selected;
    /** The run whose haul is drawn now; null while the status or "Loot outside runs" shows. */
    private VisitRef shown;
    private boolean pendingSelect;
    private boolean loading;
    private boolean showingUnlinked, closed;
    private long generation;
    private Cancellation cancel = new Cancellation();

    /** The production level over saved history from {@code store}, reading on its own daemon worker. */
    public static RunsLevel production(Supplier<SessionStore> store) {
        return production(store, LootCatalog.over(store));
    }

    public static RunsLevel production(Supplier<SessionStore> store, LootCatalog.Reader catalog) {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "RealmShark loot explore");
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);
        return new RunsLevel(RunFeedView.strip(store), RunHauls.over(store), worker, DungeonPanel.production(catalog));
    }

    /** Test fixture without a saved-loot catalog. Production supplies a dungeon panel through the four-argument constructor. */
    RunsLevel(RunFeedView feed, RunHauls.Loader loader, Executor worker) {
        this(feed, loader, worker, new DungeonPanel(cancel -> List.of(), Runnable::run));
    }

    RunsLevel(RunFeedView feed, RunHauls.Loader loader, Executor worker, DungeonPanel dungeon) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Runs level on the EDT");
        this.feed = Objects.requireNonNull(feed, "feed");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.dungeon = Objects.requireNonNull(dungeon, "dungeon");
        setName("loot-runs");
        setOpaque(false);
        status.setName("loot-runs-status");
        status.setFocusable(false);
        unlinked.setName("loot-runs-unlinked-list");
        unlinked.setOpaque(false);
        unlinked.setLayout(new BoxLayout(unlinked, BoxLayout.Y_AXIS));
        detail.setName("loot-runs-detail");
        detail.setOpaque(false);
        detail.setBorder(BorderFactory.createEmptyBorder(Tokens.S, 0, 0, 0));
        showDetail(status);

        outside.setName("loot-runs-unlinked");
        outside.setToolTipText("Bags that recorded no run, by session");
        outside.addActionListener(e -> openUnlinked());
        feed.setPickerAccessory(outside);
        JScrollPane right = new JScrollPane(detail);
        right.setName("loot-runs-detail-scroll");
        right.setBorder(BorderFactory.createEmptyBorder());
        right.setOpaque(false);
        right.getViewport().setOpaque(false);
        right.getVerticalScrollBar().setUnitIncrement(32);
        add(feed, BorderLayout.NORTH);
        add(right, BorderLayout.CENTER);

        feed.onSelect(card -> openRun(card.ref()));
        feed.onOpen(this::openRun);
        feed.onLoaded(this::loaded);
    }

    public RunFeedView feed() { return feed; }
    public HaulView haul() { return haul; }
    public DungeonPanel dungeon() { return dungeon; }
    /** The run whose haul shows or is loading; null while none does, or while "Loot outside runs" shows. */
    public VisitRef selectedRun() { return showingUnlinked ? null : selected; }
    public boolean showingUnlinked() { return showingUnlinked; }
    /** What runs whenever what the level shows changes: a run asked for or drawn, or "Loot outside runs". */
    public void onShown(Runnable listener) { shownListener = Objects.requireNonNull(listener, "listener"); }
    /** The run whose haul is drawn now; null while the status or "Loot outside runs" shows. */
    public VisitRef shownRun() { return shown; }
    /** What the haul's "Open run" runs (the run's recap in production). */
    public void onOpenRun(Consumer<VisitRef> action) { haul.onOpenRun(action); }
    /** What clicking an item runs, with its exact variant key, in the run's haul and under "Loot outside runs". */
    public void onOpenItem(Consumer<String> action) {
        openItem = Objects.requireNonNull(action, "action");
        haul.onOpenItem(action);
    }

    /** Shows {@code ref}'s haul and selects its card when it is loaded; the haul shows even when it is not. Asking again for the shown run does nothing. */
    public void openRun(VisitRef ref) {
        Objects.requireNonNull(ref, "ref");
        if (closed) return;
        boolean same = !showingUnlinked && (ref.equals(shown) || (ref.equals(selected) && loading));
        selected = ref;
        showingUnlinked = false;
        // Start the read before selecting: onSelect can re-enter openRun and must see the read already in flight.
        if (!same) load(ref);
        pendingSelect = !feed.select(ref);
        shownListener.run();
    }

    /** Forgets the current haul without reading; the next feed page opens its newest run. */
    public void clearRun() {
        generation++;
        cancel.cancel();
        selected = shown = null;
        pendingSelect = loading = showingUnlinked = false;
        feed.clearSelection();
        dungeon.showDungeon(null);
        haul.setSide(null);
        status.setText(CHOOSE);
        showDetail(status);
    }

    /** Shows the loot saved outside any run, by session; the run cards lose their selection. */
    public void openUnlinked() {
        if (closed) return;
        showingUnlinked = true;
        dungeon.showDungeon(null);
        haul.setSide(null);
        pendingSelect = false;
        loading = false;
        selected = null;
        shown = null;
        feed.clearSelection();
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        status.setText(UNLINKED_LOADING);
        showDetail(status);
        submit(() -> {
            List<RunHauls.UnlinkedSession> sessions = null;
            String failure = null;
            try { sessions = loader.unlinked(token); }
            catch (CancellationException cancelled) { return; }
            catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
            List<RunHauls.UnlinkedSession> done = sessions;
            String why = failure;
            SwingUtilities.invokeLater(() -> applyUnlinked(ticket, done, why));
        });
        shownListener.run();
    }

    /** The feed applied new runs: opens the newest when nothing is chosen, and reads the chosen run again while it is in progress. */
    void loaded(List<RunCardModel> cards) {
        if (closed) return;
        if (!showingUnlinked && feed.query().map() != null && selected != null
            && cards.stream().noneMatch(card -> card.ref().equals(selected))) clearRun();
        shownListener.run();
        if (showingUnlinked) return;
        if (selected == null) {
            if (!cards.isEmpty()) openRun(cards.get(0).ref());
            else if (feed.query().map() != null) {
                // Loot can know a dungeon with no saved visit; its collection must still be reachable.
                status.setText(feed.query().text().isBlank() && feed.query().outcomes().isEmpty()
                    ? "No saved runs in this dungeon." : "No runs match. Change the search or clear the filters to see other saved runs.");
                dungeon.showDungeon(feed.query().map());
                showDetail(KitLayouts.stack(Tokens.S, status, dungeon));
            } else {
                dungeon.showDungeon(null);
                status.setText(CHOOSE);
                showDetail(status);
            }
            return;
        }
        // Select a routed run once its card loads, or again after a search or filter rebuilt the list; an already selected card is
        // left alone, so Load more and polls never scroll the feed.
        if (pendingSelect || !feed.isSelected(selected)) pendingSelect = !feed.select(selected);
        for (RunCardModel card : cards)
            if (card.ref().equals(selected) && card.outcome() == RunOutcome.IN_PROGRESS) { load(selected); return; }
    }

    JTextArea status() { return status; }
    boolean pendingSelect() { return pendingSelect; }
    /** What shows below the strip: the status, the haul or the "Loot outside runs" list. */
    JComponent detailShown() { return detail.getComponentCount() == 0 ? null : (JComponent) detail.getComponent(0); }

    private void load(VisitRef ref) {
        loading = true;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        if (!ref.equals(shown)) {   // a refresh of the drawn run keeps it in place
            dungeon.showDungeon(null);
            haul.setSide(null);
            shown = null;
            status.setText(LOADING);
            showDetail(status);
        }
        submit(() -> {
            RunHauls.RunHaul run = null;
            String failure = null;
            try { run = loader.run(ref, token); }
            catch (CancellationException cancelled) { return; }
            catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
            RunHauls.RunHaul done = run;
            String why = failure;
            SwingUtilities.invokeLater(() -> applyRun(ticket, ref, done, why));
        });
    }

    private void applyRun(long ticket, VisitRef ref, RunHauls.RunHaul run, String failure) {
        if (closed || ticket != generation) return;
        loading = false;
        if (failure != null || run.unavailable() != null) {
            dungeon.showDungeon(null);
            haul.setSide(null);
            status.setText(failure != null ? "This run's loot could not be read: " + failure : run.unavailable());
            shown = null;
            showDetail(status);
            shownListener.run();
            return;
        }
        haul.show(run.haul(), ref, run.emptyReason());
        dungeon.showDungeon(run.dungeon());
        haul.setSide(dungeon.dungeon() == null ? null : dungeon);
        shown = ref;
        showDetail(haul);
        shownListener.run();
    }

    private void applyUnlinked(long ticket, List<RunHauls.UnlinkedSession> sessions, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null || sessions.isEmpty()) {
            status.setText(failure != null ? "Loot outside runs could not be read: " + failure : NO_UNLINKED);
            showDetail(status);
            shownListener.run();
            return;
        }
        unlinked.removeAll();
        KitText title = KitText.emphasis("Loot outside runs");
        title.setName("loot-runs-unlinked-title");
        KitText note = KitText.caption("Bags that recorded no run, by session, newest first"
            + (sessions.size() == RunHauls.UNLINKED_SESSIONS ? " (the newest " + RunHauls.UNLINKED_SESSIONS + " sessions)" : ""));
        note.setName("loot-runs-unlinked-note");
        stack(unlinked, title);
        stack(unlinked, note);
        for (RunHauls.UnlinkedSession session : sessions) {
            KitText header = KitText.body("Session started "
                + DisplayFormat.formatTimestamp(Instant.ofEpochMilli(session.started()), DisplayFormat.TimestampMode.FULL)
                + " · " + LootLine.section(session.items(), session.bags().size(), ""));
            header.setName("loot-runs-unlinked-session");
            HaulView bags = new HaulView(HaulView.Mode.COMPACT);
            bags.onOpenItem(key -> openItem.accept(key));
            bags.show(HaulModel.of(null, session.bags()), null);
            header.setBorder(BorderFactory.createEmptyBorder(Tokens.M, 0, Tokens.XS, 0));
            stack(unlinked, header);
            stack(unlinked, bags);
        }
        showDetail(unlinked);
        shownListener.run();
    }

    private static void stack(JPanel column, JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        column.add(part);
    }

    private void showDetail(JComponent part) {
        if (detail.getComponentCount() == 1 && detail.getComponent(0) == part) return;
        detail.removeAll();
        detail.add(part, BorderLayout.NORTH);
        detail.revalidate();
        detail.repaint();
    }

    private void submit(Runnable task) {
        try { worker.execute(task); } catch (RejectedExecutionException shutDown) { /* closed: nothing applies */ }
    }

    /** Stops the reads and the feed's worker. EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        feed.close();
        dungeon.close();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }

    /** Full-width content in the vertical viewport, including at the responsive breakpoint. */
    private static final class Detail extends JPanel implements Scrollable {
        Detail() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 32; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(32, visible.height - 32); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
