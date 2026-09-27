package tomato.gui.glance.home;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;
import tomato.gui.glance.home.HomeModel.State;

/**
 * Polls {@link HomeSources} off the EDT and hands finished immutable models to the EDT. Unlike SnapshotRefresh (one
 * EDT-requested SwingWorker read at a time), Home polls continuously while shown. The 1 Hz tick runs on the daemon thread
 * "home-refresh": it polls the section tokens, rebuilds only the sections whose token moved (Now and Quests also every
 * 10 s) and keeps a section's previous object when the rebuilt one is equal. Saved-history reads run one at a time on a
 * second daemon thread, "home-archive", so the tick never waits behind a slow read. Both pause while Home is hidden, and
 * models built for an older start/stop generation are dropped on the EDT.
 */
public final class HomeRefresher implements AutoCloseable {
    public static final long LIVE_MILLIS = 1_000, ARCHIVE_MILLIS = 30_000;
    /** Now and Quests are rebuilt, and the model republished, at least this often so relative times and quest age stay current. */
    static final long REBUILD_MILLIS = 10_000;

    private final HomeSources sources;
    private final Consumer<HomeModel> applyOnEdt;
    private final LongSupplier clock;
    private final long tickMillis;
    private final ScheduledThreadPoolExecutor worker;
    private final ExecutorService archiveWorker;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean liveRequested = new AtomicBoolean(), archiveRequested = new AtomicBoolean(true), archiveBusy = new AtomicBoolean();
    private final AtomicReference<Delivery> outbox = new AtomicReference<>();
    private final Object modelLock = new Object();
    private volatile boolean running, closed;
    private volatile HomeArchive.Window window;
    private ScheduledFuture<?> ticking;   // guarded by this
    private HomeModel model;   // guarded by modelLock; each thread replaces only its own sections
    // Owned by the "home-refresh" thread.
    private long seenGeneration = -1, agedAt, archiveReadAt;
    private HomeSources.Revisions built;
    private HomeArchive.Window archiveWindow;
    // Owned by the "home-archive" thread: each window's last successful read and its time, kept (stale) when a re-read of that window fails.
    private final Map<HomeArchive.Window, LastGood> lastGood = new EnumMap<>(HomeArchive.Window.class);
    private record LastGood(HomeArchive.Result result, long readAt) {}

    public HomeRefresher(HomeSources sources, Consumer<HomeModel> applyOnEdt, LongSupplier clock, HomeArchive.Window initial) {
        this(sources, applyOnEdt, clock, initial, LIVE_MILLIS);
    }
    HomeRefresher(HomeSources sources, Consumer<HomeModel> applyOnEdt, LongSupplier clock, HomeArchive.Window initial, long tickMillis) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.applyOnEdt = Objects.requireNonNull(applyOnEdt, "applyOnEdt");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Objects.requireNonNull(initial, "initial");
        if (tickMillis <= 0) throw new IllegalArgumentException("tickMillis must be positive");
        this.tickMillis = tickMillis;
        model = HomeModel.LOADING.withToday(HomeModel.Today.placeholder(State.LOADING, initial));
        worker = new ScheduledThreadPoolExecutor(1, daemon("home-refresh"));
        worker.setRemoveOnCancelPolicy(true);
        archiveWorker = Executors.newSingleThreadExecutor(daemon("home-archive"));
    }

    private static ThreadFactory daemon(String name) {
        return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; };
    }

    public HomeArchive.Window window() { return window; }

    /** EDT. Starts or resumes polling: live sections first, then the archive for the current window. Ignored after close(). */
    public synchronized void start() {
        requireEdt();
        if (closed || running) return;
        running = true;
        long gen = generation.incrementAndGet();
        archiveRequested.set(true);
        ticking = worker.scheduleWithFixedDelay(() -> tick(gen), 0, tickMillis, TimeUnit.MILLISECONDS);
    }
    /** EDT. Pauses: no source calls while hidden; anything still in flight is dropped. */
    public synchronized void stop() {
        requireEdt();
        if (!running) return;
        running = false;
        generation.incrementAndGet();
        cancel();
    }
    /** EDT. Switches Today/This session and reads the archive at once. */
    public synchronized void setWindow(HomeArchive.Window next) {
        requireEdt();
        Objects.requireNonNull(next, "window");
        if (next == window) return;
        window = next;
        archiveRequested.set(true);
        kick();
    }
    /** Rebuilds every live section and re-reads the archive now (no-op while stopped; the next start() does both). */
    public synchronized void refreshNow() {
        liveRequested.set(true); archiveRequested.set(true);
        kick();
    }
    /** Any thread. Stops for good; an in-flight read is interrupted and its result dropped. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; running = false;
        generation.incrementAndGet();
        cancel(); worker.shutdownNow(); archiveWorker.shutdownNow();
    }

    private void kick() {   // caller holds this
        if (!running || closed) return;
        long gen = generation.get();
        worker.execute(() -> tick(gen));
    }
    private void cancel() { if (ticking != null) { ticking.cancel(false); ticking = null; } }
    private boolean current(long gen) { return running && !closed && generation.get() == gen; }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Home refresh is controlled on the EDT");
    }

    /** "home-refresh": rebuilds the live sections whose token moved, then starts an archive read when one is due. */
    private void tick(long gen) {
        if (!current(gen)) return;
        try {
            long now = clock.getAsLong();
            if (gen != seenGeneration) { seenGeneration = gen; built = null; }   // a (re)start rebuilds every section
            HomeSources.Revisions next = revisions();
            boolean all = liveRequested.getAndSet(false) || built == null || next == null;
            boolean aged = !all && now - agedAt >= REBUILD_MILLIS;
            HomeModel.Hero hero = all || next.hero() != built.hero() ? hero(now) : null;
            HomeModel.Now live = all || aged || next.now() != built.now() ? nowCard(now) : null;
            HomeModel.Quests quests = all || aged || next.quests() != built.quests() ? quests(now) : null;
            built = next;
            if (all || aged) agedAt = now;
            // Live sections never wait for the archive; the age tick republishes so relative times refresh.
            merge(gen, m -> m.withHero(keep(m.hero(), hero)).withNow(keep(m.now(), live)).withQuests(keep(m.quests(), quests)), all || aged);
            scheduleArchive(gen, now);
        } catch (Throwable unexpected) {
            liveRequested.set(true);   // keep the schedule alive (an escaping Error would cancel it); the next tick rebuilds
            log("A live refresh", unexpected);
        }
    }

    /** "home-refresh": hands one archive read to "home-archive" when requested, on a window change or every ARCHIVE_MILLIS. */
    private void scheduleArchive(long gen, long now) {
        HomeArchive.Window requested = window;
        boolean due = archiveRequested.get() || requested != archiveWindow || now - archiveReadAt >= ARCHIVE_MILLIS;
        if (!due || !current(gen) || !archiveBusy.compareAndSet(false, true)) return;   // one read at a time; a later tick retries
        archiveRequested.set(false);
        archiveReadAt = now;
        if (requested != archiveWindow) {
            archiveWindow = requested;
            merge(gen, m -> m.withToday(HomeModel.Today.placeholder(State.LOADING, requested)), false);
        }
        try { archiveWorker.execute(() -> readArchive(gen, requested, now)); }
        catch (RejectedExecutionException closing) { archiveBusy.set(false); }
    }

    /** "home-archive": one saved-history read. A failed re-read keeps this window's last good result, labeled stale. */
    private void readArchive(long gen, HomeArchive.Window requested, long now) {
        try {
            if (!current(gen)) return;
            HomeModel.Today today; HomeModel.Runs runs;
            try {
                HomeArchive.Result result = Objects.requireNonNull(sources.archive(requested, now), "archive");
                lastGood.put(requested, new LastGood(result, now));
                today = HomeModelBuilder.today(requested, result, null);
                runs = HomeModelBuilder.runs(result, null);
            } catch (Exception failure) {
                // Spec §7: this window's last good read stays, marked stale with its age and the reason; UNAVAILABLE only
                // when this window never had one.
                LastGood kept = lastGood.get(requested);
                today = kept != null ? HomeModelBuilder.staleToday(requested, kept.result(), kept.readAt(), failure, now) : HomeModelBuilder.today(requested, null, failure);
                runs = kept != null ? HomeModelBuilder.staleRuns(kept.result(), kept.readAt(), failure, now) : HomeModelBuilder.runs(null, failure);
            }
            HomeModel.Today shownToday = today;
            HomeModel.Runs shownRuns = runs;
            // A read for a window that is no longer selected is dropped; that window's own read follows.
            if (requested == window) merge(gen, m -> m.withToday(keep(m.today(), shownToday)).withRuns(keep(m.runs(), shownRuns)), false);
        } catch (Throwable unexpected) {
            log("A saved-history read", unexpected);
        } finally {
            archiveBusy.set(false);
            synchronized (this) { kick(); }   // a window chosen during this read is read right away
        }
    }

    // Each section fails on its own: an exception becomes that section's UNAVAILABLE state, never a dead thread.
    private HomeSources.Revisions revisions() { try { return sources.revisions(); } catch (RuntimeException failure) { return null; } }
    private HomeModel.Hero hero(long now) {
        try { return Objects.requireNonNull(sources.hero(now), "hero"); }
        catch (RuntimeException failure) { return HomeModel.Hero.placeholder(State.UNAVAILABLE, "Character data could not be read: " + HomeModelBuilder.oneLine(failure)); }
    }
    private HomeModel.Now nowCard(long now) { try { return Objects.requireNonNull(sources.now(now), "now"); } catch (RuntimeException f) { return HomeModel.Now.placeholder(State.UNAVAILABLE); } }
    private HomeModel.Quests quests(long now) { try { return Objects.requireNonNull(sources.quests(now), "quests"); } catch (RuntimeException f) { return HomeModel.Quests.placeholder(State.UNAVAILABLE); } }

    /** The previous section object when the rebuilt one is equal (or none was rebuilt), so HomePage can skip that card. */
    private static <T> T keep(T previous, T rebuilt) { return rebuilt == null || rebuilt.equals(previous) ? previous : rebuilt; }

    /** Swaps sections into the shared model and publishes when a section object changed, or always for the age tick. */
    private void merge(long gen, UnaryOperator<HomeModel> change, boolean always) {
        synchronized (modelLock) {
            if (!current(gen)) return;
            HomeModel next = change.apply(model);
            boolean changed = next.hero() != model.hero() || next.now() != model.now() || next.today() != model.today()
                || next.runs() != model.runs() || next.quests() != model.quests();
            if (changed) model = next;
            if (changed || always) publish(gen, model);
        }
    }

    private static void log(String what, Throwable failure) { System.err.println("[Home] " + what + " failed; it is retried: " + failure); }

    private record Delivery(long generation, HomeModel model) {}
    /** Coalesces: at most one pending EDT hand-off, always carrying the newest model. */
    private void publish(long gen, HomeModel next) {
        if (outbox.getAndSet(new Delivery(gen, next)) == null) SwingUtilities.invokeLater(this::deliver);
    }
    private void deliver() {
        Delivery delivery = outbox.getAndSet(null);
        if (delivery != null && delivery.generation() == generation.get() && running && !closed) applyOnEdt.accept(delivery.model());
    }
}
