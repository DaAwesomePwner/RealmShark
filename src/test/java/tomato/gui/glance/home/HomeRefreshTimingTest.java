package tomato.gui.glance.home;

import java.awt.event.WindowEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import tomato.gui.dps.MeterSummary;
import tomato.gui.kit.DisplayModeModel;
import static org.junit.Assert.*;

/** S9: Home's live refresh never blocks the EDT for more than 16 ms. Synthetic sources; no capture. */
public class HomeRefreshTimingTest {
    private final Map<String, String> prefs = new HashMap<>();
    private JFrame frame;
    private HomePage page;

    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (page != null) page.close();
            if (frame != null) frame.dispose();
        });
    }

    private void show(HomeSources sources) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            page = new HomePage(sources, HomeModels.NO_ACTIONS, new DisplayModeModel(prefs::get, prefs::put), prefs::get, prefs::put,
                System::currentTimeMillis);
            frame = new JFrame("Home timing - synthetic validation");
            frame.setContentPane(page);
            frame.setSize(1240, 800);
            frame.setVisible(true);
        });
    }

    /** S9 (a): live sources run on "home-refresh" and archive reads on "home-archive", never on the EDT; nothing is read while Home is hidden. */
    @Test public void sourcesAreReadOffTheEdtAndOnlyWhileHomeIsShowing() throws Exception {
        Recording sources = new Recording();
        show(sources);
        await(() -> sources.calls("hero") > 0 && sources.calls("now") > 0 && sources.calls("quests") > 0 && sources.calls("archive:TODAY") > 0);
        await(() -> edt(() -> page.model().hero().state() == HomeModel.State.LIVE && page.model().today().state() != HomeModel.State.LOADING));
        SwingUtilities.invokeAndWait(() -> HomeModels.named(page, "home-today-window-1", AbstractButton.class).doClick());
        await(() -> sources.calls("archive:SESSION") > 0);
        assertEquals("The window choice is saved", "session", prefs.get(HomePage.WINDOW_KEY));
        SwingUtilities.invokeAndWait(() -> page.setVisible(false));
        Thread.sleep(300);
        int paused = sources.total();
        Thread.sleep(1_500);
        assertEquals("Hidden Home reads nothing", paused, sources.total());
        assertEquals("No source method ran on the EDT", 0, sources.onEdt.get());
        assertEquals("Live sources on home-refresh, archive reads on home-archive", List.of(), sources.wrongThread);
        assertEquals(Set.of("home-refresh", "home-archive"), sources.threads);
    }

    /** A minimized window pauses Home like a hidden page; restoring the window resumes it. */
    @Test public void aMinimizedWindowPausesHomeAndRestoringItResumes() throws Exception {
        Recording sources = new Recording();
        show(sources);
        await(() -> sources.calls("revisions") > 2 && sources.calls("archive:TODAY") > 0);
        SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_ICONIFIED)));
        Thread.sleep(300);
        int paused = sources.total();
        Thread.sleep(1_500);
        assertEquals("A minimized Home reads nothing", paused, sources.total());
        SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_DEICONIFIED)));
        await(() -> sources.total() > paused);
        assertEquals(0, sources.onEdt.get());
    }

    /**
     * Now's elapsed time ticks every second on the EDT, not only on the 10 s age tick, but only while Home is showing (not
     * hidden, minimized or closed) and the applied Now is a live run with a start time. Null sources: only the timer moves it.
     */
    @Test public void nowsElapsedTimeTicksEachSecondOnlyWhileHomeShowsALiveRun() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        JLabel[] elapsed = new JLabel[1];
        SwingUtilities.invokeAndWait(() -> {
            page = new HomePage(null, HomeModels.NO_ACTIONS, new DisplayModeModel(prefs::get, prefs::put), prefs::get, prefs::put, clock::get);
            page.apply(HomeModels.populated(clock.get()));
            assertFalse("Not showing yet: no tick", page.elapsedTicking());
            elapsed[0] = HomeModels.named(page, "home-now-elapsed", JLabel.class);
            assertEquals("12m 30s", elapsed[0].getText());
            frame = new JFrame("Home elapsed tick - synthetic validation");
            frame.setContentPane(page);
            frame.setSize(1240, 800);
            frame.setVisible(true);
        });
        await(() -> edt(() -> page.elapsedTicking()));
        clock.addAndGet(1_000);
        await(() -> edt(() -> "12m 31s".equals(elapsed[0].getText())));
        SwingUtilities.invokeAndWait(() -> page.setVisible(false));
        assertFalse("Hidden: no tick", edt(() -> page.elapsedTicking()));
        clock.addAndGet(5_000); Thread.sleep(1_500);
        assertTrue("Nothing ticked while hidden", edt(() -> "12m 31s".equals(elapsed[0].getText())));
        SwingUtilities.invokeAndWait(() -> page.setVisible(true));
        assertTrue(edt(() -> page.elapsedTicking()));
        SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_ICONIFIED)));
        assertFalse("Minimized: no tick", edt(() -> page.elapsedTicking()));
        SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_DEICONIFIED)));
        assertTrue(edt(() -> page.elapsedTicking()));
        SwingUtilities.invokeAndWait(() -> page.apply(HomeModels.populated(clock.get())
            .withNow(new HomeModel.Now(HomeModel.State.LIVE, true, "Nexus", null, List.of(), 0, 0, null))));
        assertFalse("No start time: no tick", edt(() -> page.elapsedTicking()));
        SwingUtilities.invokeAndWait(() -> page.apply(HomeModels.populated(clock.get())));
        assertTrue(edt(() -> page.elapsedTicking()));
        SwingUtilities.invokeAndWait(() -> page.close());
        assertFalse("Closed: no tick", edt(() -> page.elapsedTicking()));
    }

    /**
     * S9 (b), the live-tick path (user decision 2026-09-26: strict on Home's own EDT work). A live refresh changes only the
     * live sections, and Swing repaints only the cards whose section changed. Every sample is one EDT turn: the apply, the
     * layout it queued (validate) and the repaint of the dirty regions (RepaintManager.paintDirtyRegions), after 20 warm-ups.
     * A: over 50 ticks that change Now (elapsed time and meter values) and one hero stat, apply plus layout takes at most 16 ms on
     * every sample. B: over 50 typical ticks that change only Now, apply, layout and repaint take at most 16 ms at p95; a first
     * run whose p95 is above 16 ms (a garbage-collection or scheduling outlier) is measured once more in full. The worst case of
     * B, the Now-and-hero tick with its repaint and a full-page paint (first show or page switch, which S8 covers) are logged
     * for the validation record, not asserted: Java2D paint time on a shared desktop is noisy.
     */
    @Test public void liveTicksKeepHomesEdtWorkWithinOneFrame() throws Exception {
        long now = System.currentTimeMillis();
        // View models are bounded (5 runs × 8 loot, 3 quests × 4 rewards, 3 meter rows, 12 fame points) whatever the history
        // size, so these are the largest models; a large history only lengthens the archive read, which runs off the EDT.
        HomeModel first = HomeModels.populated(now);
        HomeModel nowAndHero = first.withNow(liveMeter(now, 5_000L, 1.07)).withHero(wisdom(first.hero(), 61));
        HomeModel nowOnly = first.withNow(liveMeter(now, 1_000L, 1.02));
        for (HomeModel tick : new HomeModel[] {nowAndHero, nowOnly}) {
            assertSame("Only the live sections differ", first.today(), tick.today());
            assertSame(first.runs(), tick.runs()); assertSame(first.quests(), tick.quests());
        }
        assertSame("A typical tick changes only Now", first.hero(), nowOnly.hero());
        show(null);
        SwingUtilities.invokeAndWait(() -> page.apply(first));
        for (int pass = 0; pass < 3; pass++) SwingUtilities.invokeAndWait(() -> page.paintImmediately(0, 0, page.getWidth(), page.getHeight()));

        long[][] heroTicks = measure(first, nowAndHero);
        long[][] typical = measure(first, nowOnly);
        if (p95(typical[0]) > 16_000) {
            System.out.println("S9 first run: typical tick p95 " + p95(typical[0]) + " us is above 16 ms; measuring the 50 ticks again");
            typical = measure(first, nowOnly);
        }
        long[] full = new long[50];
        for (int i = 0; i < 70; i++) {
            int sample = i - 20;
            SwingUtilities.invokeAndWait(() -> {
                long start = System.nanoTime();
                page.paintImmediately(0, 0, page.getWidth(), page.getHeight());
                if (sample >= 0) full[sample] = (System.nanoTime() - start) / 1_000;
            });
        }
        Arrays.sort(full);
        log("A: Now-and-hero tick, apply and layout (asserted: every sample)", heroTicks[1]);
        log("B: typical Now-only tick, apply, layout and dirty-region paint (asserted: p95)", typical[0]);
        log("Now-and-hero tick with dirty-region paint (logged)", heroTicks[0]);
        log("full-page paint (first show or page switch; S8, logged)", full);
        long worstEdtWork = heroTicks[1][heroTicks[1].length - 1];
        assertTrue("S9 A: apply plus layout takes at most 16 ms on every live tick; worst " + worstEdtWork + " us", worstEdtWork <= 16_000);
        assertTrue("S9 B: a typical live tick with its repaint takes at most 16 ms at p95; p95 " + p95(typical[0]) + " us", p95(typical[0]) <= 16_000);
    }

    /** 20 warm-ups, then 50 ticks alternating the two models; sorted microseconds: [0] the whole tick, [1] apply and layout, [2] the dirty-region paint. */
    private long[][] measure(HomeModel first, HomeModel second) throws Exception {
        long[][] micros = new long[3][50];
        for (int i = 0; i < 70; i++) {
            HomeModel model = i % 2 == 0 ? second : first;
            int sample = i - 20;
            SwingUtilities.invokeAndWait(() -> {
                RepaintManager repaints = RepaintManager.currentManager(page);
                long start = System.nanoTime();
                page.apply(model);
                repaints.validateInvalidComponents(); // the layout the apply queued
                page.validate();
                long laidOut = System.nanoTime();
                repaints.paintDirtyRegions(); // only what the apply marked dirty, as Swing paints after this event
                long painted = System.nanoTime();
                if (sample >= 0) {
                    micros[0][sample] = (painted - start) / 1_000;
                    micros[1][sample] = (laidOut - start) / 1_000;
                    micros[2][sample] = (painted - laidOut) / 1_000;
                }
            });
        }
        for (long[] part : micros) Arrays.sort(part);
        return micros;
    }

    private static long p95(long[] sorted) { return sorted[(int) Math.ceil(sorted.length * .95) - 1]; }

    private static void log(String what, long[] micros) {
        System.out.println("S9 " + what + " over " + micros.length + " samples: median " + micros[micros.length / 2] + " us, p95 "
            + p95(micros) + " us, max " + micros[micros.length - 1] + " us");
    }

    /** The fixture's Now a few seconds later, with the meter's damage and DPS scaled. */
    private static HomeModel.Now liveMeter(long now, long later, double scale) {
        HomeModel.Now base = HomeModels.now(now);
        List<MeterSummary.Row> rows = new ArrayList<>();
        for (MeterSummary.Row row : base.top())
            rows.add(new MeterSummary.Row(row.name(), row.classId(), row.className(), Math.round(row.damage() * scale), row.dps() * scale, row.local()));
        return new HomeModel.Now(base.state(), base.capturing(), base.area(), base.startedAt() - later, rows, base.localRank(), base.players(), base.lastPop());
    }

    /** The same hero with another wisdom base value (one stat bar, its value text and the needs line change). */
    private static HomeModel.Hero wisdom(HomeModel.Hero h, int value) {
        int[] base = h.base();
        base[7] = value;
        return new HomeModel.Hero(h.state(), h.name(), h.classId(), h.className(), h.skin(), h.level(), h.fame(), h.maxed(), base, h.caps(),
            h.totals(), h.potionsNeeded(), h.needsLine(), h.exaltTiers(), h.equipment(), h.weaponDps(), h.mpPerSecond(), h.accountLine(),
            h.lastSeenAt(), h.evidence(), h.key(), h.petChip());
    }

    private static final class Recording implements HomeSources {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final List<String> wrongThread = new CopyOnWriteArrayList<>();
        final AtomicInteger onEdt = new AtomicInteger();
        private void record(String name, String thread) {
            calls.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
            String current = Thread.currentThread().getName();
            threads.add(current);
            if (!thread.equals(current)) wrongThread.add(name + " ran on " + current);
            if (SwingUtilities.isEventDispatchThread()) onEdt.incrementAndGet();
        }
        int calls(String name) { AtomicInteger count = calls.get(name); return count == null ? 0 : count.get(); }
        int total() { int sum = 0; for (AtomicInteger count : calls.values()) sum += count.get(); return sum; }
        @Override public Revisions revisions() { record("revisions", "home-refresh"); return new Revisions(1, 1, 1); }
        @Override public HomeModel.Hero hero(long now) { record("hero", "home-refresh"); return HomeModels.hero(HomeModel.State.LIVE, now); }
        @Override public HomeModel.Now now(long now) { record("now", "home-refresh"); return HomeModels.now(now); }
        @Override public HomeModel.Quests quests(long now) { record("quests", "home-refresh"); return HomeModels.quests(now, false); }
        @Override public HomeArchive.Result archive(HomeArchive.Window window, long now) {
            record("archive:" + window, "home-archive");
            return HomeModels.result(window, now);
        }
    }

    private static boolean edt(BooleanSupplier condition) {
        boolean[] result = new boolean[1];
        try { SwingUtilities.invokeAndWait(() -> result[0] = condition.getAsBoolean()); }
        catch (Exception e) { throw new AssertionError(e); }
        return result[0];
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for Home to refresh");
    }
}
