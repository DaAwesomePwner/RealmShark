package tomato.gui.runs;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tomato.gui.activity.ActivityRoutes;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.route.ShellNavigator;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;

/**
 * Routes into the Runs page (the Feed tab of the shell's {@code runs} page, spec §6.3): two targets over one page and one run recap, registered after the Table
 * view's targets ({@link RunsPage#tableRoutes}) so they are tried first for their shapes.
 * - {@link Destination#RUNS} without any reference brings the feed forward, on Cards or Table as chosen. Routes to rows (a visit
 *   or a query) are not accepted here: the Table view's targets open the archive on that row.
 * - {@link Destination#RUN_RECAP} with one exact saved {@link VisitRef}, optionally one recording ID, and nothing else (no query,
 *   record, object, bounds or payload) opens that run's recap on that recording (the Recordings tab's summary-only rows; an ID
 *   that is not one of the run's shows the longest, as the builder does); without a recording it shows the longest, and the
 *   picker then is the view's own state. The view is made once and put in
 *   the page's recap slot, shows "Loading this run…" at once, opens its Damage section (explicit navigation to the damage
 *   breakdown, spec S4) and is built by {@link RunRecapBuilder} on one daemon worker ("RealmShark run recap"), never on the EDT;
 *   a newer request cancels the older one and only the newest result is applied. Without saved history the route is rejected,
 *   so a caller can fall back (Home's Recent runs then opens the feed). A read that fails says so; nothing is substituted.
 * - The recap's links (Runs table, Loot, Timeline) open through the navigator. Another recording rebuilds the recap in place,
 *   without a Back entry. "‹ Runs" leads to the feed: it goes Back when the entry that led to the recap returns to this page's
 *   feed (so no stale entry is left), else it shows the feed in place (a recap opened from Home keeps "Back to Home").
 * - Back state is {@link RunsState}: the recap (its run and chosen recording) or the feed, Cards or Table, and the Table view's
 *   own state. Restore shows exactly that view, restores the workspace only when its state changed since, never opens a section,
 *   and reads the recap again only when it does not show that run and recording (or its read failed).
 * - A recap opened from another page is left when Back returns there: the page shows again what it showed before the route (the
 *   feed), so the sidebar's Runs does not land on a recap the user went Back from.
 * EDT only, except the builds.
 */
public final class RunsRouteTarget implements RouteTarget {
    /** How a recap is read: {@link RunRecapBuilder#build} over the open history store in production. Off the EDT only. */
    interface Recaps { RunRecapModel build(VisitRef ref, String recordingId, Cancellation cancel) throws IOException; }

    /** The recap's wording when saved history closed between accepting the route and reading the run. */
    static final String NO_HISTORY = "Saved history is not open in this app run, so this run cannot be read. Nothing else is shown in its place.";

    private final Destination destination;
    private final Controller runs;

    private RunsRouteTarget(Destination destination, Controller runs) { this.destination = destination; this.runs = runs; }

    /**
     * The feed target ({@code RUNS}) and the recap target ({@code RUN_RECAP}) over {@code page}, reading runs from {@code store}
     * (none open: the recap is rejected). {@code table} is the Table view's own target over the archive workspace (its detached
     * state is part of the Back state), or null when the page has no saved-history table. EDT only.
     */
    public static List<RouteTarget> of(RunsPage page, RouteTarget table, Supplier<SessionStore> store, ShellNavigator navigator) {
        return of(page, table, builders(store), RunRecapView::new, navigator);
    }

    /** As above with the recap's reader and view factory (tests). */
    static List<RouteTarget> of(RunsPage page, RouteTarget table, Supplier<Recaps> recaps, Supplier<RunRecapView> views, ShellNavigator navigator) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Wire Runs routes on the EDT");
        Controller runs = new Controller(page, table, recaps, views, navigator);
        return List.of(new RunsRouteTarget(Destination.RUNS, runs), new RunsRouteTarget(Destination.RUN_RECAP, runs));
    }

    @Override public Destination destination() { return destination; }

    @Override public boolean accepts(Route route) {
        if (route.destination != destination || route.query != null || route.record != null
            || route.localObjectId != null || route.from != null || route.until != null || route.payload != null) return false;
        if (destination == Destination.RUNS) return route.visit == null && route.recordingId == null;
        return ActivityRoutes.queryable(route.visit) && runs.recaps.get() != null;
    }

    @Override public Object captureState() { return runs.captured(); }

    @Override public void open(Route route) {
        if (!accepts(route)) throw new IllegalArgumentException("Unsupported Runs route: " + route);
        if (destination == Destination.RUNS) runs.page.showFeed();
        else runs.openRecap(route.visit, route.recordingId);
    }

    @Override public void restoreState(Object state) {
        if (!(state instanceof RunsState)) throw new IllegalArgumentException("Not a Runs page state");
        runs.restore((RunsState) state);
    }

    /** One {@link RunRecapBuilder} per open store, in the system zone and clock; null while none is open. EDT only. */
    private static Supplier<Recaps> builders(Supplier<SessionStore> stores) {
        Objects.requireNonNull(stores, "stores");
        Object[] made = new Object[2];   // {store, its reader}
        return () -> {
            SessionStore store = stores.get();
            if (store == null) return null;
            if (made[0] != store) {
                RunRecapBuilder builder = new RunRecapBuilder(store, ZoneId.systemDefault(), System::currentTimeMillis);
                made[0] = store;
                made[1] = (Recaps) builder::build;
            }
            return (Recaps) made[1];
        };
    }

    /** A recap opened from another page: the Back entry its open pushed and what the page showed before. */
    private record Away(long entry, RunsState before) {}

    /** The page, the recap and the Back bookkeeping both targets share. EDT only, except the worker's reads. */
    private static final class Controller {
        final RunsPage page;
        final RouteTarget table;
        final Supplier<Recaps> recaps;
        final Supplier<RunRecapView> views;
        final ShellNavigator navigator;
        final ThreadPoolExecutor worker;
        RunRecapView view;
        /** The recording the newest request asked for (null = the longest), that request's identity and its token. */
        String recording;
        long generation;
        Cancellation cancel = new Cancellation();
        /** The newest request is still being read; its read failed (Back to it reads again). */
        boolean building, failed, closed;
        /** The Back entry the navigation in progress will push, when this page's state was captured for it, and that state. */
        long capturedFor;
        RunsState captured;
        /** The entry "‹ Runs" goes Back through: the open's entry when it returns to this page's feed, else 0. */
        long feedEntry;
        /** Recaps opened from other pages, newest last, while their Back entries may still be on the stack. */
        final ArrayDeque<Away> away = new ArrayDeque<>();
        /** Away records left by Back, most recently popped last, until Forward or a new route. */
        final ArrayDeque<Away> left = new ArrayDeque<>();

        Controller(RunsPage page, RouteTarget table, Supplier<Recaps> recaps, Supplier<RunRecapView> views, ShellNavigator navigator) {
            this.page = Objects.requireNonNull(page, "page");
            this.table = table;
            this.recaps = Objects.requireNonNull(recaps, "recaps");
            this.views = Objects.requireNonNull(views, "views");
            this.navigator = Objects.requireNonNull(navigator, "navigator");
            worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "RealmShark run recap");
                thread.setDaemon(true);
                return thread;
            });
            worker.allowCoreThreadTimeOut(true);
            // The Table view's targets capture this page too (after a row route); their capture counts as this page's origin.
            page.onCapture(this::captured);
            page.onClose(this::close);
            navigator.addChangeListener(this::stackChanged);
        }

        /** What the page shows now, as a detached Back state. */
        RunsState state() {
            boolean recap = page.recapShown() && view != null && view.ref() != null;
            return new RunsState(recap, recap ? view.ref() : null, recap ? recording : null, page.feed().tableShown(),
                table == null ? null : table.captureState());
        }

        /** The navigator captures this page as a route's origin: remembered for that route's open. */
        RunsState captured() {
            RunsState state = state();
            captured = state;
            capturedFor = navigator.nextBackToken();
            return state;
        }

        /** Opens the recap of {@code ref} on {@code recordingId} (null = the longest; an ID that is not the run's shows the longest). */
        void openRecap(VisitRef ref, String recordingId) {
            long entry = navigator.nextBackToken();
            boolean here = entry != 0 && capturedFor == entry && captured != null;   // this page was the origin
            RunsState before = here ? captured : state();
            capturedFor = 0;
            captured = null;
            feedEntry = here && !before.recap() ? entry : 0;
            if (!here) {
                away.addLast(new Away(entry, before));
                while (away.size() > ShellNavigator.DEFAULT_CAPACITY) away.removeFirst();
            }
            ensureView();
            recording = recordingId;
            view.showLoading(ref);
            view.expandDamage();   // explicit navigation to the run's damage breakdown (S4); remembered as the user's choice
            page.showRecap();
            build(ref, recordingId);
            // The navigator shows the runs page after this returns; move keyboard focus into the recap once it shows (as the sheet does).
            SwingUtilities.invokeLater(() -> {
                if (closed || !page.recapShown() || !view.isShowing()) return;
                Component link = find(view, "run-recap-back");
                if (link != null) link.requestFocusInWindow();
            });
        }

        /** Back applies {@code saved}: exactly its view, the workspace's state when it changed since, the recap as left. */
        void restore(RunsState saved) {
            // The page's card first, then the feed's view, then the workspace: hiding a showing workspace cancels its read, while a
            // read it starts when already hidden continues.
            if (saved.recap()) showRecap(saved.ref(), saved.recordingId()); else page.showFeed();
            if (page.feed().tableShown() != saved.table()) { if (saved.table()) page.feed().showTable(); else page.feed().showCards(); }
            if (table != null && saved.workspace() != null && table.captureState() != saved.workspace()) table.restoreState(saved.workspace());
        }

        /** Shows the recap of {@code ref} with {@code recordingId}, read again only when it does not show them (or failed). */
        private void showRecap(VisitRef ref, String recordingId) {
            ensureView();
            boolean same = ref.equals(view.ref()) && Objects.equals(recordingId, recording) && !failed && (building || view.model() != null);
            if (!same) {
                recording = recordingId;
                view.showLoading(ref);
                build(ref, recordingId);
            }
            page.showRecap();
        }

        /** "‹ Runs": Back when the entry that led here returns to this page's feed, else the feed in place. */
        void backToFeed() {
            if (feedEntry != 0 && navigator.backToken() == feedEntry && navigator.back()) return;
            page.showFeed();
        }

        /** Another recording of the shown run: rebuilt in place, no loading state and no Back entry. */
        void choose(String recordingId) {
            if (closed || view.ref() == null) return;
            recording = recordingId;
            build(view.ref(), recordingId);
        }

        /** Back left one or more recaps opened from other pages: the page shows what it showed before the oldest of them. */
        void stackChanged() {
            if (!left.isEmpty() && navigator.backToken() > left.peekLast().entry()) left.clear();
            // Only Forward reuses an entry token; Back's decreasing tokens make left LIFO-consistent with Forward.
            while (!left.isEmpty() && navigator.backToken() == left.peekLast().entry()) away.addLast(left.pollLast());
            RunsState before = null;
            while (!away.isEmpty() && navigator.backToken() < away.peekLast().entry()) {
                Away popped = away.pollLast();
                before = popped.before();
                left.addLast(popped);
                while (left.size() > ShellNavigator.DEFAULT_CAPACITY) left.removeFirst();
            }
            if (before != null && !closed) restore(before);
        }

        private void ensureView() {
            if (view != null) return;
            view = Objects.requireNonNull(views.get(), "view");
            view.onBack(this::backToFeed);
            view.onRecording(this::choose);
            view.onOpenRoute(navigator::open);
            page.setRecap(view);
        }

        /** Starts the newest read on the worker; an older one is cancelled and its completion is inert. */
        private void build(VisitRef ref, String recordingId) {
            long ticket = ++generation;
            cancel.cancel();
            Cancellation token = cancel = new Cancellation();
            building = true;
            failed = false;
            Recaps source = recaps.get();
            try {
                worker.execute(() -> {
                    RunRecapModel model;
                    boolean broken = false;
                    try {
                        model = source == null ? RunRecapModel.unavailable(ref, NO_HISTORY, System.currentTimeMillis()) : source.build(ref, recordingId, token);
                    } catch (CancellationException cancelled) {
                        return;   // a newer request replaced this one
                    } catch (Exception | Error unreadable) {
                        model = RunRecapModel.unavailable(ref, unreadable(unreadable), System.currentTimeMillis());
                        broken = true;
                    }
                    RunRecapModel done = model;
                    boolean fail = broken;
                    SwingUtilities.invokeLater(() -> apply(ticket, done, fail));
                });
            } catch (RejectedExecutionException shutDown) { building = false; }
        }

        private void apply(long ticket, RunRecapModel model, boolean broken) {
            if (closed || ticket != generation) return;
            building = false;
            failed = broken;
            view.show(model);
        }

        /** A read failure in words: a damaged file is not an absence, so this never reads as "not in saved history" alone. */
        private static String unreadable(Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
            // Starts with the recap's failed-read title, which is how the recap tells a failed read from a run not in saved history.
            return RunRecapView.FAILED_TITLE + " from saved history: " + message + ". Nothing else is shown in its place; open it again to retry.";
        }

        private void close() {
            closed = true;
            away.clear(); left.clear();
            generation++;
            cancel.cancel();
            worker.shutdownNow();
        }

        private static Component find(Container root, String name) {
            for (Component child : root.getComponents()) {
                if (name.equals(child.getName())) return child;
                if (child instanceof Container) { Component found = find((Container) child, name); if (found != null) return found; }
            }
            return null;
        }
    }
}
