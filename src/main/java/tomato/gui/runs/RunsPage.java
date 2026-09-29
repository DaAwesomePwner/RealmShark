package tomato.gui.runs;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.history.SessionStore;

/**
 * The Feed tab of Runs & DPS (the shell's {@code runs} page, spec §6.3): a {@code feed} card holding the {@link RunFeedView} (the day-grouped run cards, with the
 * Runs archive workspace as its Table view) and a {@code recap} card, a slot the run recap fills ({@link #setRecap}). The page
 * keeps a direct reference to the workspace, so closing and "Browse saved history" reach it whichever view shows. Routes that
 * select rows of the archive ({@code RUNS} with a visit or a query) bring the Table view forward through
 * {@link #tableRoutes}: explicit navigation may show a hidden view, restoring saved state only selects. {@link RunsRouteTarget}
 * fills the recap slot and opens the feed and the recap (plain {@code RUNS} and {@code RUN_RECAP} routes). EDT only.
 */
public final class RunsPage extends JPanel implements AutoCloseable {
    public static final String FEED = "feed", RECAP = "recap";
    private final CardLayout layout = new CardLayout();
    private final RunFeedView feed;
    private final JPanel recapSlot = new JPanel(new BorderLayout());
    private JComponent recap;
    private boolean recapShown;
    /** What a Table view target's Back capture also tells ({@link RunsRouteTarget}'s bookkeeping); what closing also releases. */
    private Runnable captured = () -> { };
    private final List<Runnable> closers = new ArrayList<>();

    /** The production page: the feed over saved history from {@code store} with {@code workspace} (the Runs archive) as its Table view. */
    public RunsPage(JComponent workspace, Supplier<SessionStore> store) { this(new RunFeedView(workspace, store)); }

    /** The page around a built feed (tests). */
    RunsPage(RunFeedView feed) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Runs page on the EDT");
        this.feed = Objects.requireNonNull(feed, "feed");
        setLayout(layout);
        setName("runs-page");
        setOpaque(false);
        recapSlot.setName("runs-recap-slot");
        recapSlot.setOpaque(false);
        add(feed, FEED);
        add(recapSlot, RECAP);
        layout.show(this, FEED);
    }

    public RunFeedView feed() { return feed; }
    /** The Runs archive workspace (the feed's Table view), for closing and Browse saved history. */
    public JComponent workspace() { return feed.table(); }

    /** Puts {@code component} (the run recap) in the recap slot; null empties it. The shown card does not change. */
    public void setRecap(JComponent component) {
        if (recap == component) return;
        recapSlot.removeAll();
        recap = component;
        if (component != null) recapSlot.add(component, BorderLayout.CENTER);
        recapSlot.revalidate();
        recapSlot.repaint();
    }

    public JComponent recap() { return recap; }
    /** Shows the recap slot; nothing happens while it is empty. */
    public void showRecap() { if (recap == null) return; recapShown = true; layout.show(this, RECAP); }
    /** Shows the feed (its Cards or Table view, whichever was chosen). */
    public void showFeed() { recapShown = false; layout.show(this, FEED); }
    public boolean recapShown() { return recapShown; }
    /** Explicit navigation to the archive table (a row route, Browse saved history): the feed's Table view. */
    public void showTable() { showFeed(); feed.showTable(); }
    /** Explicit navigation to the cards. */
    public void showCards() { showFeed(); feed.showCards(); }

    /**
     * {@code target} (a {@code RUNS} target over the workspace) whose routes that select rows, those with a visit or a query,
     * first bring the feed's Table view forward, so the exact row shows; a plain route leaves the view as it is. Its captured
     * state also holds which view showed, and Back restores that view with the workspace's state.
     */
    public RouteTarget tableRoutes(RouteTarget target) {
        Objects.requireNonNull(target, "target");
        return new RouteTarget() {
            @Override public tomato.gui.route.Destination destination() { return target.destination(); }
            @Override public boolean accepts(Route route) { return target.accepts(route); }
            @Override public Route redirect(Route route) { return target.redirect(route); }
            @Override public Object captureState() { captured.run(); return new RouteState(feed.tableShown(), target.captureState()); }
            @Override public void open(Route route) {
                boolean table = feed.tableShown(), recapWas = recapShown;
                // The table shows before the workspace applies the route: its binding only follows a visible view.
                if (route.visit != null || route.query != null) showTable();
                try { target.open(route); }
                catch (RuntimeException failed) {   // a rejected route changes nothing: the navigator keeps the origin page
                    if (!table) feed.showCards();
                    if (recapWas) showRecap();
                    throw failed;
                }
            }
            @Override public void restoreState(Object state) {
                if (!(state instanceof RouteState)) throw new IllegalArgumentException("Not a Runs page state");
                RouteState saved = (RouteState) state;
                // Back is explicit navigation. The view switches first: hiding a showing workspace cancels its read, while a
                // read it starts when already hidden continues.
                if (saved.table()) showTable(); else showCards();
                target.restoreState(saved.inner());
            }
        };
    }

    /** Which feed view showed, and the Table view target's own state. */
    record RouteState(boolean table, Object inner) {}

    /**
     * {@code listener} runs whenever a {@link #tableRoutes} target captures this page as a route's origin, so the run recap's
     * target knows the page was the origin although another target captured it (its "‹ Runs" then goes Back to the feed).
     */
    void onCapture(Runnable listener) { captured = Objects.requireNonNull(listener, "listener"); }
    /** {@code closer} runs when the page closes (the run recap's reads). */
    void onClose(Runnable closer) { closers.add(Objects.requireNonNull(closer, "closer")); }

    /** Releases the feed's and the recap's reads and pinned results; the workspace is closed with the other archive workspaces. */
    @Override public void close() {
        feed.close();
        for (Runnable closer : closers) closer.run();
    }
}
