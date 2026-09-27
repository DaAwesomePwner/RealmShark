package tomato.gui.glance.home;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.*;
import tomato.gui.glance.home.HomeModel.State;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeArchive.Window.SESSION;
import static tomato.gui.glance.home.HomeArchive.Window.TODAY;

/** The refresh threads: per-section rebuilds, pause and close, dropped late results, stale archive failures, no source call on the EDT. */
public class HomeRefresherTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final List<HomeRefresher> refreshers = new ArrayList<>();

    @After public void closeAndCheckThreads() {
        refreshers.forEach(HomeRefresher::close);
        assertEquals("Live sources ran only on home-refresh, archive reads only on home-archive, models only on the EDT", List.of(), violations);
    }

    private HomeRefresher refresher(Fake sources, List<HomeModel> models) {
        HomeRefresher refresher = new HomeRefresher(sources, model -> {
            if (!SwingUtilities.isEventDispatchThread()) violations.add("model applied off the EDT");
            models.add(model);
        }, clock::get, TODAY, 5);
        refreshers.add(refresher);
        return refresher;
    }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Timed out: " + what); Thread.sleep(5); }
        edt(() -> { });
    }
    private static void settle() throws Exception { Thread.sleep(80); edt(() -> { }); }
    private static HomeModel last(List<HomeModel> models) { return models.get(models.size() - 1); }

    @Test public void eachLiveSectionRebuildsOnlyWhenItsTokenMovesAndNowAndQuestsAgeEveryTenSeconds() throws Exception {
        Fake sources = new Fake(); List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        edt(refresher::start);
        await(() -> sources.heroCalls.get() == 1 && sources.archiveCalls.get() == 1 && !models.isEmpty()
            && last(models).today().state() == State.LIVE, "first build");
        settle();
        assertEquals("Polled, not rebuilt", 1, sources.heroCalls.get()); assertEquals(1, sources.nowCalls.get()); assertEquals(1, sources.questCalls.get());
        assertTrue(sources.revisionCalls.get() > 3);
        int delivered = models.size();
        sources.heroRevision.incrementAndGet();
        await(() -> sources.heroCalls.get() == 2, "a hero token change rebuilds the hero");
        settle();
        assertEquals("Only the hero is rebuilt", 1, sources.nowCalls.get()); assertEquals(1, sources.questCalls.get());
        assertEquals("An equal hero is dropped: nothing new is handed to the EDT", delivered, models.size());
        sources.questRevision.incrementAndGet();
        await(() -> sources.questCalls.get() == 2, "a quests token change rebuilds the quests");
        assertEquals(2, sources.heroCalls.get()); assertEquals(1, sources.nowCalls.get());
        clock.addAndGet(HomeRefresher.REBUILD_MILLIS - 1); settle();
        assertEquals(1, sources.nowCalls.get());
        clock.addAndGet(1);
        await(() -> sources.nowCalls.get() == 2 && sources.questCalls.get() == 3, "now and quests rebuild every 10 s for their age rules");
        assertEquals("The hero has no age rule", 2, sources.heroCalls.get());
        await(() -> models.size() > delivered, "the age tick republishes so relative times refresh");
    }

    @Test public void archiveReadsOnStartWindowChangeAndInterval() throws Exception {
        Fake sources = new Fake(); List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        edt(refresher::start);
        await(() -> sources.archiveCalls.get() == 1 && !models.isEmpty() && last(models).today().state() == State.LIVE, "archive on start");
        assertEquals(TODAY, sources.windows.get(0));
        edt(() -> refresher.setWindow(SESSION));
        await(() -> !models.isEmpty() && last(models).today().window() == SESSION && last(models).today().state() == State.LIVE, "archive on window change");
        assertEquals(List.of(TODAY, SESSION), sources.windows);
        edt(() -> refresher.setWindow(SESSION)); settle();
        assertEquals("Same window: no re-read", 2, sources.archiveCalls.get());
        clock.addAndGet(HomeRefresher.ARCHIVE_MILLIS);
        await(() -> sources.archiveCalls.get() == 3, "archive interval");
        settle(); assertEquals(3, sources.archiveCalls.get());
    }

    @Test public void aSlowArchiveReadNeverDelaysTheLiveTick() throws Exception {
        Fake sources = new Fake(); sources.archiveGate = new CountDownLatch(1);
        List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        edt(refresher::start);
        assertTrue(sources.archiveEntered.await(5, TimeUnit.SECONDS));
        int polls = sources.revisionCalls.get();
        sources.heroRevision.incrementAndGet();
        await(() -> sources.heroCalls.get() == 2 && sources.revisionCalls.get() > polls + 3, "the tick keeps polling while the read is blocked");
        sources.archiveGate.countDown();
        await(() -> last(models).today().state() == State.LIVE, "the read still arrives");
    }

    @Test public void failuresBecomeUnavailableAndTheThreadSurvives() throws Exception {
        Fake sources = new Fake(); List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        sources.archiveFailure = new IOException("disk full\nsecond line"); sources.heroFailure = new IllegalStateException("journal busy");
        edt(refresher::start);
        await(() -> !models.isEmpty() && last(models).today().state() == State.UNAVAILABLE, "failure shown");
        HomeModel failed = last(models);
        assertEquals(State.UNAVAILABLE, failed.hero().state()); assertTrue(failed.hero().evidence().contains("journal busy"));
        assertEquals("Never a good read: unavailable, not stale", State.UNAVAILABLE, failed.runs().state());
        assertEquals("Saved history could not be read: disk full", failed.today().reason());
        sources.archiveFailure = null; sources.heroFailure = null;
        edt(refresher::refreshNow);
        await(() -> last(models).hero().state() == State.EMPTY && last(models).today().state() == State.LIVE, "recovers on the same thread");
    }

    @Test public void aFailedReReadKeepsTheLastGoodReadAsStale() throws Exception {
        Fake sources = new Fake(); List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        edt(refresher::start);
        await(() -> !models.isEmpty() && last(models).today().state() == State.LIVE, "a good read");
        HomeArchive.Totals good = last(models).today().totals();
        sources.archiveFailure = new IOException("disk full");
        clock.addAndGet(5 * 60_000L);
        edt(refresher::refreshNow);
        await(() -> last(models).today().state() == State.STALE, "the failed re-read");
        HomeModel stale = last(models);
        assertEquals("The last good totals stay", good, stale.today().totals());
        assertEquals("Last updated 5 min ago · disk full", stale.today().reason());
        assertEquals(State.STALE, stale.runs().state()); assertEquals("Last updated 5 min ago · disk full", stale.runs().reason());
        sources.archiveFailure = null;
        edt(refresher::refreshNow);
        await(() -> last(models).today().state() == State.LIVE, "recovers");
    }

    @Test public void anErrorInASourceDoesNotStopTheSchedule() throws Exception {
        Fake sources = new Fake(); List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        sources.heroError = new Error("synthetic failure");
        edt(refresher::start);
        await(() -> sources.heroCalls.get() >= 3, "the tick keeps running after an Error");
        sources.heroError = null;
        await(() -> !models.isEmpty() && last(models).hero().state() == State.EMPTY, "the hero arrives once the Error stops");
    }

    @Test public void nothingRunsWhileStopped() throws Exception {
        Fake sources = new Fake(); List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        edt(refresher::start);
        await(() -> sources.archiveCalls.get() == 1 && !models.isEmpty(), "running");
        edt(refresher::stop); settle();
        int calls = sources.calls(), delivered = models.size();
        sources.heroRevision.incrementAndGet(); clock.addAndGet(60_000);
        edt(refresher::refreshNow); edt(() -> refresher.setWindow(SESSION));
        settle(); settle();
        assertEquals("No source calls while hidden", calls, sources.calls()); assertEquals(delivered, models.size());
        edt(refresher::start);
        await(() -> sources.archiveCalls.get() == 2, "restart reads the archive again");
        assertEquals(SESSION, sources.windows.get(1));
    }

    @Test public void resultsFinishedAfterStopOrCloseAreDropped() throws Exception {
        Fake sources = new Fake(); sources.archiveGate = new CountDownLatch(1);
        List<HomeModel> models = new CopyOnWriteArrayList<>(); HomeRefresher refresher = refresher(sources, models);
        edt(refresher::start);
        assertTrue(sources.archiveEntered.await(5, TimeUnit.SECONDS));
        await(() -> !models.isEmpty(), "live sections arrive before the archive read finishes");
        edt(refresher::stop);
        int delivered = models.size();
        sources.archiveGate.countDown(); settle();
        assertEquals("Nothing is applied after stop()", delivered, models.size());
        assertTrue(models.stream().allMatch(m -> m.today().state() == State.LOADING));

        Fake second = new Fake(); second.archiveGate = new CountDownLatch(1);
        List<HomeModel> secondModels = new CopyOnWriteArrayList<>(); HomeRefresher closing = refresher(second, secondModels);
        edt(closing::start);
        assertTrue(second.archiveEntered.await(5, TimeUnit.SECONDS));
        closing.close();
        second.archiveGate.countDown(); settle();
        assertTrue(secondModels.stream().allMatch(m -> m.today().state() == State.LOADING));
        int calls = second.calls();
        edt(closing::start); settle();
        assertEquals("start() after close() is a no-op", calls, second.calls());
    }

    private final class Fake implements HomeSources {
        final AtomicLong heroRevision = new AtomicLong(1), nowRevision = new AtomicLong(1), questRevision = new AtomicLong(1);
        final AtomicInteger revisionCalls = new AtomicInteger(), heroCalls = new AtomicInteger(), nowCalls = new AtomicInteger(),
            questCalls = new AtomicInteger(), archiveCalls = new AtomicInteger();
        final List<HomeArchive.Window> windows = new CopyOnWriteArrayList<>();
        final CountDownLatch archiveEntered = new CountDownLatch(1);
        volatile CountDownLatch archiveGate;
        volatile RuntimeException heroFailure;
        volatile Error heroError;
        volatile Exception archiveFailure;

        int calls() { return revisionCalls.get() + heroCalls.get() + nowCalls.get() + questCalls.get() + archiveCalls.get(); }
        private void check(String method, String expected) {
            Thread thread = Thread.currentThread();
            if (SwingUtilities.isEventDispatchThread() || !expected.equals(thread.getName()) || !thread.isDaemon())
                violations.add(method + " ran on " + thread.getName());
        }
        @Override public Revisions revisions() {
            check("revisions", "home-refresh"); revisionCalls.incrementAndGet();
            return new Revisions(heroRevision.get(), nowRevision.get(), questRevision.get());
        }
        @Override public HomeModel.Hero hero(long now) {
            check("hero", "home-refresh"); heroCalls.incrementAndGet();
            Error error = heroError; if (error != null) throw error;
            RuntimeException failure = heroFailure; if (failure != null) throw failure;
            return HomeModel.Hero.placeholder(State.EMPTY, "fake");
        }
        @Override public HomeModel.Now now(long now) { check("now", "home-refresh"); nowCalls.incrementAndGet(); return HomeModel.Now.placeholder(State.EMPTY); }
        @Override public HomeModel.Quests quests(long now) { check("quests", "home-refresh"); questCalls.incrementAndGet(); return HomeModel.Quests.placeholder(State.EMPTY); }
        @Override public HomeArchive.Result archive(HomeArchive.Window window, long now) throws Exception {
            check("archive", "home-archive"); windows.add(window); archiveCalls.incrementAndGet(); archiveEntered.countDown();
            CountDownLatch gate = archiveGate; if (gate != null) gate.await(5, TimeUnit.SECONDS);
            Exception failure = archiveFailure; if (failure != null) throw failure;
            return new HomeArchive.Result(new HomeArchive.Totals(window, 0, now, 1, 2, 10L, null, new double[12], 0, 0, 0, 0, true), List.of());
        }
    }
}
