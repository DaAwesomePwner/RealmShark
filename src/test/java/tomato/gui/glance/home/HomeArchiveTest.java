package tomato.gui.glance.home;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.dps.*;
import tomato.gui.stats.LootTestDrops;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeArchive.Window.SESSION;
import static tomato.gui.glance.home.HomeArchive.Window.TODAY;
import static tomato.gui.glance.home.HomeHistoryFixture.*;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.item;

/** Home totals and recent runs from synthetic saved history; exact VisitRef joins only. */
public class HomeArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path fixture() throws Exception { Path root = temp.newFolder().toPath(); HomeHistoryFixture.write(root); return root; }
    private static HomeArchive.Result read(Path root, HomeArchive.Window window, long now, List<RecordedEncounter> recordings) throws Exception {
        try (SessionStore store = new SessionStore(root, false, "fixture")) { return HomeArchive.read(store, window, now, ZONE, recordings); }
    }
    /** EncounterLink.live(context) is the public factory; a context with a visit is LINKED. */
    private static RecordedEncounter recording(String id, String map, VisitRef visit, Integer local, long damage, double seconds) {
        return new RecordedEncounter(id, map, null, 60_000L, EncounterLink.live(new EncounterContext(visit, local, 1L)), damage, seconds);
    }


    @Test public void unreadableSessionMetadataNeverBecomesCompleteTotals() throws Exception {
        Path root = fixture();
        Files.writeString(root.resolve(MORNING).resolve("session.json"), "{broken");
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            assertTrue(store.catalog().stream().anyMatch(entry -> !entry.readable()));
            for (HomeArchive.Window window : HomeArchive.Window.values()) {
                try {
                    HomeArchive.read(store, window, NOW, ZONE, List.of());
                    fail("Unreadable historical metadata must mark the combined archive result incomplete");
                } catch (java.io.IOException expected) {
                    assertTrue(expected.getMessage().contains("Unreadable session " + MORNING));
                }
            }
        }
    }

    @Test public void todayCountsTheLocalCalendarDayAcrossSessions() throws Exception {
        HomeArchive.Totals t = read(fixture(), TODAY, NOW, List.of()).totals();
        assertEquals(TODAY, t.window()); assertEquals(MIDNIGHT, t.from()); assertEquals(at(1, 0, 0), t.until());
        assertEquals("After-midnight Lost Halls plus three morning runs; hubs and the 23:30 Snake Pit excluded", 4, t.runsEntered());
        assertEquals("Only runs with completion evidence", 2, t.runsCompleted());
        assertEquals(1, t.untiered()); assertEquals(1, t.setTiered()); assertEquals(2, t.whiteBags()); assertEquals(2, t.potions());
        assertTrue("Loot was saved in today's sessions, so these counts are real", t.lootRecorded());
        assertTrue("Visits were saved in today's sessions, so the run counts are real", t.runsRecorded());
    }

    @Test public void fameGainIgnoresDecreasesAndNewCharacterBaselines() throws Exception {
        HomeArchive.Totals t = read(fixture(), TODAY, NOW, List.of()).totals();
        assertEquals(Long.valueOf(310), t.fameGained());
        assertEquals("Hours with readings: 00:20-01:50 and 08:00-09:20; the gap between the sessions does not count",
            310 * 60.0 / 170, t.famePerHour(), 1e-9);
        double[] series = t.fameSeries();
        assertEquals(12, series.length);
        assertEquals(0, series[1], 0); assertEquals(100, series[2], 0); assertEquals(100, series[9], 0);
        assertEquals(250, series[10], 0); assertEquals(310, series[11], 0);
        for (int i = 1; i < series.length; i++) assertTrue("Cumulative", series[i] >= series[i - 1]);
    }

    @Test public void aDayWithoutRecordsHasNoFameReadingNotZeroFame() throws Exception {
        HomeArchive.Result result = read(fixture(), TODAY, at(2, 10, 0), List.of());
        HomeArchive.Totals t = result.totals();
        assertEquals(0, t.runsEntered()); assertEquals(0, t.runsCompleted());
        assertNull("No readings: unknown, not zero", t.fameGained()); assertNull(t.famePerHour()); assertEquals(0, t.fameSeries().length);
        assertEquals(0, t.untiered() + t.setTiered() + t.whiteBags() + t.potions());
        assertFalse("No loot was saved that day: the loot counts are unknown, not zero", t.lootRecorded());
        assertFalse("No visit was saved that day: the run counts are unknown, not zero", t.runsRecorded());
        assertEquals("Recent runs are not limited to the window", 5, result.recent().size());
    }

    @Test public void famePerHourNeedsTenMinutesOfReadings() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            long start = store.started();
            store.append("fame", new AppHistory.FameSample(3, 100, start + 1_000, "Rogue"));
            store.append("fame", new AppHistory.FameSample(3, 140, start + 1_000 + 9 * MINUTE, "Rogue"));
            store.flush();
            HomeArchive.Totals t = HomeArchive.read(store, SESSION, start + 15 * MINUTE, ZONE, List.of()).totals();
            assertEquals(Long.valueOf(40), t.fameGained()); assertNull("Under ten minutes between readings", t.famePerHour());
            assertFalse("Only fame was saved", t.lootRecorded()); assertFalse("Only fame was saved", t.runsRecorded());
            store.append("fame", new AppHistory.FameSample(3, 160, start + 1_000 + 10 * MINUTE, "Rogue"));
            store.flush();
            t = HomeArchive.read(store, SESSION, start + 15 * MINUTE, ZONE, List.of()).totals();
            assertEquals(Long.valueOf(60), t.fameGained()); assertEquals(360.0, t.famePerHour(), 1e-9);
        }
    }

    @Test public void sessionWindowCoversOnlyTheCurrentSession() throws Exception {
        Path root = fixture();
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            long start = store.started(); String id = store.currentId();
            store.put("runs", "s1", visit("s1", "Lost Halls", start + MINUTE, start + 10 * MINUTE, true));
            store.put("runs", "s2", visit("s2", "Nexus", start + 11 * MINUTE, start + 12 * MINUTE, false));
            store.append("loot", LootTestDrops.drop("White", "Lost Halls", start + 5 * MINUTE, new VisitRef(id, "s1"), item(8, POTION), item(9, UT)));
            store.append("fame", new AppHistory.FameSample(4, 500, start + 1_000, "Knight"));
            store.append("fame", new AppHistory.FameSample(4, 650, start + 15 * MINUTE, "Knight"));
            store.flush();
            long now = start + 20 * MINUTE;
            HomeArchive.Result result = HomeArchive.read(store, SESSION, now, ZONE, List.of());
            HomeArchive.Totals t = result.totals();
            assertEquals(SESSION, t.window()); assertEquals(start, t.from()); assertEquals(now, t.until());
            assertEquals("Earlier sessions are excluded", 1, t.runsEntered()); assertEquals(1, t.runsCompleted());
            assertEquals(Long.valueOf(150), t.fameGained());
            assertEquals(1, t.untiered()); assertEquals(0, t.setTiered()); assertEquals(1, t.whiteBags()); assertEquals(1, t.potions());
            assertTrue(t.lootRecorded());
            assertEquals("Recent runs span sessions; the current one is newest", new VisitRef(id, "s1"), result.recent().get(0).visit());
            assertEquals("Notable first", List.of(9, 8), result.recent().get(0).lootIds());
        }
    }

    @Test public void hubVisitsAloneMakeZeroRunsARealZero() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            long start = store.started();
            store.put("runs", "n1", visit("n1", "Nexus", start + MINUTE, start + 2 * MINUTE, false));
            store.flush();
            HomeArchive.Totals t = HomeArchive.read(store, SESSION, start + 5 * MINUTE, ZONE, List.of()).totals();
            assertEquals("A hub visit is not a dungeon run", 0, t.runsEntered());
            assertTrue("A visit was saved, so no runs is a real zero", t.runsRecorded());
        }
    }

    @Test public void recentRunsAreTheNewestDungeonVisitsWithExactLinksOnly() throws Exception {
        List<RecordedEncounter> recordings = List.of(
            recording("r1", "Ice Citadel", ref(MORNING, "c1"), 7, 120_000, 60.0),
            recording("r2", "Lost Halls", ref(ACROSS, "b2"), null, 50_000, 25.0),    // local row not verified: no DPS
            recording("r3", "Lost Halls", null, 7, 90_000, 30.0),                     // unlinked: never joined by map or time
            recording("r4", "Lost Halls", ref(YESTERDAY, "c2"), 7, 80_000, 40.0),     // same visit ID, another session
            recording("r5", "Snake Pit", ref(ACROSS, "b1"), 7, 70_000, 0.0));         // linked, but no damage window
        List<HomeArchive.RecentRun> recent = read(fixture(), TODAY, NOW, recordings).recent();
        assertEquals("Newest first, at most five, dungeons only", List.of(ref(MORNING, "c3"), ref(MORNING, "c2"), ref(MORNING, "c1"), ref(ACROSS, "b2"), ref(ACROSS, "b1")),
            recent.stream().map(HomeArchive.RecentRun::visit).collect(Collectors.toList()));
        HomeArchive.RecentRun c3 = recent.get(0), c2 = recent.get(1), c1 = recent.get(2), b2 = recent.get(3);
        assertEquals("Pirate Cave", c3.map()); assertEquals("Left · completion unconfirmed", c3.outcome());
        assertEquals("Closed when its session ended", Long.valueOf(at(0, 9, 25)), c3.ended());
        assertEquals("Completed", c1.outcome()); assertEquals(at(0, 8, 10), c1.started());
        assertEquals("Only the exactly linked bag counts; notable items first", List.of(301, 302, 303), c2.lootIds());
        assertEquals(List.of(202, 203), b2.lootIds());
        assertEquals(List.of(), c1.lootIds());
        assertEquals(2_000.0, c1.localDps(), 1e-9);
        assertNull(c2.localDps()); assertNull(b2.localDps()); assertNull(c3.localDps());
        assertNull("A zero-length window has no DPS", recent.get(4).localDps());
    }

    @Test public void aSessionThatNeverSavedItsEndEndsAtItsLastWriteAndItsRunsAreClosed() throws Exception {
        Path root = temp.newFolder().toPath();
        String crashed = id("crashed");
        session(root, crashed, at(-2, 20, 0), 0);   // the app crashed: no end was saved
        runs(root, crashed, visit("x1", "Snake Pit", at(-2, 20, 10), 0, false));
        // Readings timed after the last write cannot happen; here they only show whether today reads this session.
        fame(root, crashed, sample(9, 100, at(0, 5, 0)), sample(9, 900, at(0, 6, 0)));
        FileTime lastWrite = FileTime.fromMillis(at(-2, 20, 40));
        try (Stream<Path> files = Files.walk(root.resolve(crashed))) {
            for (Path file : (Iterable<Path>) files::iterator) Files.setLastModifiedTime(file, lastWrite);
        }
        HomeArchive.Result result = read(root, TODAY, NOW, List.of());
        assertNull("It ended two days ago, so today does not read it again", result.totals().fameGained());
        HomeArchive.RecentRun run = result.recent().get(0);
        assertEquals(ref(crashed, "x1"), run.visit());
        assertEquals("Never in progress once its session is over", "Left · completion unconfirmed", run.outcome());
        assertEquals("Closed at its last reading", Long.valueOf(at(-2, 20, 30)), run.ended());
    }

    @Test public void readsRefuseTheEventDispatchThread() throws Exception {
        Path root = fixture();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try { HomeArchive.read(store, TODAY, NOW, ZONE, List.of()); } catch (Throwable t) { failure.set(t); }
            });
            assertTrue(String.valueOf(failure.get()), failure.get() instanceof IllegalStateException);
        }
    }

    @Test public void largeHistoryReadsOffTheEventDispatchThreadQuickly() throws Exception {
        Path root = temp.newFolder().toPath();
        HomeHistoryFixture.writeLarge(root, LARGE_SESSIONS, LARGE_RUNS);
        assertFalse(SwingUtilities.isEventDispatchThread());
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            long cold = System.nanoTime();
            HomeArchive.Result result = HomeArchive.read(store, TODAY, NOW, ZONE, List.of());
            long coldMillis = (System.nanoTime() - cold) / 1_000_000, warm = System.nanoTime();
            HomeArchive.read(store, TODAY, NOW, ZONE, List.of());
            long warmMillis = (System.nanoTime() - warm) / 1_000_000;
            System.out.println("HomeArchive large history (" + LARGE_SESSIONS + " sessions x " + LARGE_RUNS + " runs): cold "
                + coldMillis + " ms, warm " + warmMillis + " ms (target < 2000 ms)");
            HomeArchive.Totals t = result.totals();
            assertEquals("Three sessions fall on the day", 120, t.runsEntered()); assertEquals(60, t.runsCompleted());
            assertEquals(12, t.untiered()); assertEquals(12, t.whiteBags()); assertEquals(120, t.potions());
            assertEquals(Long.valueOf(3 * 975), t.fameGained());
            assertEquals(5, result.recent().size());
            assertEquals(new VisitRef(id("large-0"), "L0-39"), result.recent().get(0).visit());
            assertTrue("Soft bound; the < 2 s target is recorded from the line above", coldMillis < 10_000);
        }
    }
}
