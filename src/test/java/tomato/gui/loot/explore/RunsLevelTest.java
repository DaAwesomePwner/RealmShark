package tomato.gui.loot.explore;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.kit.Tokens;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.runs.RunCardModel;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import tomato.gui.runs.RunOutcome;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** The Runs level over the synthetic Runs fixture and a fake haul reader: newest first, picking, routes, stale reads, failures, loot outside runs. */
public class RunsLevelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final List<RunsLevel> levels = new ArrayList<>();
    private SessionStore store;

    /** A haul reader the test fills; unknown runs read as unavailable. */
    static final class FakeLoader implements RunHauls.Loader {
        final Map<VisitRef, RunHauls.RunHaul> runs = new HashMap<>();
        final List<VisitRef> reads = new CopyOnWriteArrayList<>();
        volatile List<RunHauls.UnlinkedSession> unlinked = List.of();
        volatile IOException fail;
        @Override public RunHauls.RunHaul run(VisitRef ref, Cancellation cancel) throws IOException {
            reads.add(ref);
            if (fail != null) throw fail;
            RunHauls.RunHaul run = runs.get(ref);
            return run != null ? run : new RunHauls.RunHaul(ref, HaulModel.of(null, List.of()), null, "Linked visit unavailable");
        }
        @Override public List<RunHauls.UnlinkedSession> unlinked(Cancellation cancel) throws IOException {
            if (fail != null) throw fail;
            return unlinked;
        }
    }

    static RunHauls.RunHaul haul(VisitRef ref, String map) {
        HaulModel model = HaulModel.of(new HaulModel.Header(map, 0, "Completed", Tokens.Tone.GOOD, 1L, 60_000L, null),
            List.of(new HaulModel.Bag("White", 1L, "Synthetic boss", List.of(new LootFacts.Item(101, true, false, false, false)))));
        return new RunHauls.RunHaul(ref, model, null, null);
    }

    @Before public void write() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        store = new SessionStore(root, false, "fixture");
    }

    @After public void release() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (RunsLevel level : levels) level.close(); });
        store.close();
    }

    private RunsLevel level(RunFeedView feed, RunHauls.Loader loader, java.util.concurrent.Executor worker) throws Exception {
        RunsLevel level = edt(() -> new RunsLevel(feed, loader, worker));
        levels.add(level);
        return level;
    }

    @Test public void theNewestRunOpensFirstAndPickingACardOpensItsHaul() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.B4, haul(RunFixtures.B4, "Synthetic B4"));
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "Synthetic A1"));
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> store)), loader, Runnable::run);
        edt(() -> { level.feed().refresh(); return null; });
        await("the newest run's haul", () -> RunFixtures.B4.equals(level.selectedRun()) && level.detailShown() == level.haul());
        assertEquals(List.of(RunFixtures.B4), loader.reads);
        assertEquals("Synthetic B4", edt(() -> level.haul().model().header().mapName()));
        edt(() -> { assertTrue(level.feed().select(RunFixtures.A1)); return null; });
        edt(() -> null);   // the read's result applies on a later EDT turn
        assertEquals(RunFixtures.A1, edt(level::selectedRun));
        assertEquals("Synthetic A1", edt(() -> level.haul().model().header().mapName()));
        assertEquals(List.of(RunFixtures.B4, RunFixtures.A1), loader.reads);
    }

    @Test public void reloadsDoNotReselectACardThatIsAlreadySelected() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.B4, haul(RunFixtures.B4, "Synthetic B4"));
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "Synthetic A1"));
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> store)), loader, Runnable::run);
        edt(() -> { level.feed().refresh(); return null; });
        await("the newest run's haul", () -> RunFixtures.B4.equals(level.selectedRun()) && level.detailShown() == level.haul());
        edt(() -> { level.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        RunCardModel completed = new RunCardModel(RunFixtures.A1, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, 1L,
            60_000L, null, null, "No combat recording", List.of(), 0, "", null, null, null);
        edt(() -> {
            assertFalse(level.pendingSelect());
            level.loaded(List.of(completed));
            assertFalse(level.pendingSelect());
            assertEquals(List.of(RunFixtures.B4, RunFixtures.A1), loader.reads);
            level.openRun(new VisitRef(RunFixtures.A, "not-loaded"));
            assertTrue("A missing card still needs selection when it loads", level.pendingSelect());
            level.openUnlinked();
            assertFalse("Loot outside runs clears the pending selection", level.pendingSelect());
            return null;
        });

        // An exact route before the feed loads is selected once its card arrives, without another haul read.
        FakeLoader pendingLoader = new FakeLoader();
        pendingLoader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "Synthetic A1"));
        RunsLevel pending = level(edt(() -> RunFeedView.picker(() -> store)), pendingLoader, Runnable::run);
        edt(() -> {
            pending.openRun(RunFixtures.A1);
            assertTrue(pending.pendingSelect());
            pending.feed().refresh();
            return null;
        });
        await("the routed card's selection", () -> !pending.pendingSelect());
        assertEquals(List.of(RunFixtures.A1), pendingLoader.reads);
    }

    @Test public void aClearedSelectionIsRestoredOnTheNextLoadWithoutReadingTheHaulAgain() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.B4, haul(RunFixtures.B4, "Synthetic B4"));
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "Synthetic A1"));
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> store)), loader, Runnable::run);
        edt(() -> { level.feed().refresh(); return null; });
        await("the newest run's haul", () -> RunFixtures.B4.equals(level.selectedRun()) && level.detailShown() == level.haul());
        edt(() -> { level.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        RunCardModel completed = new RunCardModel(RunFixtures.A1, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, 1L,
            60_000L, null, null, "No combat recording", List.of(), 0, "", null, null, null);
        edt(() -> {
            assertTrue(level.feed().isSelected(RunFixtures.A1));
            assertFalse(level.pendingSelect());
            level.feed().clearSelection();   // a search or filter rebuilt the list without a selected card
            assertFalse(level.feed().isSelected(RunFixtures.A1));
            level.loaded(List.of(completed));
            assertTrue(level.feed().isSelected(RunFixtures.A1));
            assertFalse(level.pendingSelect());
            assertEquals(RunFixtures.A1, level.selectedRun());
            assertSame(level.haul(), level.detailShown());
            assertEquals(List.of(RunFixtures.B4, RunFixtures.A1), loader.reads);
            return null;
        });
    }

    @Test public void aRouteToARunTheFeedHasNotLoadedStillShowsWhatIsKnown() throws Exception {
        FakeLoader loader = new FakeLoader();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> store)), loader, Runnable::run);
        VisitRef gone = new VisitRef(RunFixtures.A, "gone");
        edt(() -> { level.openRun(gone); return null; });
        edt(() -> null);
        assertEquals(gone, edt(level::selectedRun));
        assertSame(edt(level::status), edt(level::detailShown));
        assertEquals("Linked visit unavailable", edt(() -> level.status().getText()));
    }

    @Test public void onlyTheNewestRequestsHaulApplies() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "First"));
        loader.runs.put(RunFixtures.A2, haul(RunFixtures.A2, "Second"));
        List<Runnable> queued = new ArrayList<>();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, queued::add);
        edt(() -> {
            level.openRun(RunFixtures.A1);
            level.openRun(RunFixtures.A2);
            assertEquals(RunsLevel.LOADING, level.status().getText());
            for (Runnable task : List.copyOf(queued)) task.run();   // the older read finishes too, in order
            return null;
        });
        edt(() -> null);
        assertEquals("Second", edt(() -> level.haul().model().header().mapName()));
    }

    @Test public void aFailedReadSaysWhy() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.fail = new IOException("synthetic read failure");
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        edt(() -> { level.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        assertSame(edt(level::status), edt(level::detailShown));
        assertEquals("This run's loot could not be read: synthetic read failure", edt(() -> level.status().getText()));
    }

    @Test public void aFailedRunCanBeRetried() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "Synthetic A1"));
        loader.fail = new IOException("synthetic read failure");
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        edt(() -> { level.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        assertSame(edt(level::status), edt(level::detailShown));
        edt(() -> { loader.fail = null; level.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        assertEquals(List.of(RunFixtures.A1, RunFixtures.A1), loader.reads);
        assertSame(edt(level::haul), edt(level::detailShown));
    }

    @Test public void anUnavailableRunCanBeRetried() throws Exception {
        FakeLoader loader = new FakeLoader();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        edt(() -> { level.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        assertEquals("Linked visit unavailable", edt(() -> level.status().getText()));
        edt(() -> {
            loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "Synthetic A1"));
            level.openRun(RunFixtures.A1);
            return null;
        });
        edt(() -> null);
        assertEquals(List.of(RunFixtures.A1, RunFixtures.A1), loader.reads);
        assertSame(edt(level::haul), edt(level::detailShown));
    }

    @Test public void returningToThePreviousRunWhileAnotherLoadsReadsItAgain() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "First"));
        loader.runs.put(RunFixtures.A2, haul(RunFixtures.A2, "Second"));
        List<Runnable> queued = new ArrayList<>();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, queued::add);
        edt(() -> { level.openRun(RunFixtures.A1); queued.remove(0).run(); return null; });
        edt(() -> null);
        edt(() -> {
            level.openRun(RunFixtures.A2);
            level.openRun(RunFixtures.A1);
            level.openRun(RunFixtures.A1);
            assertEquals("The in-flight repeat adds no read", 2, queued.size());
            for (Runnable task : queued) task.run();
            return null;
        });
        edt(() -> null);
        assertEquals("First", edt(() -> level.haul().model().header().mapName()));
        assertSame(edt(level::haul), edt(level::detailShown));
        assertEquals(List.of(RunFixtures.A1, RunFixtures.A2, RunFixtures.A1), loader.reads);
    }

    @Test public void aRunInProgressIsReadAgainWhenNewRunsLoad() throws Exception {
        FakeLoader loader = new FakeLoader();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        RunCardModel live = new RunCardModel(RunFixtures.B4, "Lost Halls", "Lost Halls", 0, RunOutcome.IN_PROGRESS, 1L, null, null, null,
            "No combat recording", List.of(), 0, "", null, null, null);
        edt(() -> { level.openRun(RunFixtures.B4); level.loaded(List.of(live)); return null; });
        edt(() -> null);
        assertEquals("Opened, then read again", List.of(RunFixtures.B4, RunFixtures.B4), loader.reads);
    }

    @Test public void lootOutsideRunsIsShownBySession() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.unlinked = List.of(new RunHauls.UnlinkedSession(RunFixtures.A, RunFixtures.NOW - 3_600_000L,
            List.of(new HaulModel.Bag("Brown", RunFixtures.NOW - 60_000L, null, List.of(new LootFacts.Item(701, false, false, false, false))))));
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        edt(() -> { named(level, "loot-runs-unlinked", AbstractButton.class).doClick(); return null; });
        edt(() -> null);
        assertTrue(edt(level::showingUnlinked));
        assertNull(edt(level::selectedRun));
        List<JLabel> sessions = edt(() -> all(level, "loot-runs-unlinked-session", JLabel.class));
        assertEquals(1, sessions.size());
        assertTrue(sessions.get(0).getText().startsWith("Session started "));
        assertTrue(sessions.get(0).getText().endsWith(" · 1 item in 1 bag"));

        loader.unlinked = List.of();
        edt(() -> { level.openUnlinked(); return null; });
        edt(() -> null);
        assertEquals(RunsLevel.NO_UNLINKED, edt(() -> level.status().getText()));
    }

    // ---- helpers ----

    @FunctionalInterface interface Checked<T> { T get() throws Exception; }

    static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    static void await(String what, BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for " + what);
    }

    static <T extends Component> List<T> all(Container root, String name, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) found.addAll(all(container, name, type));
        }
        return found;
    }

    static <T extends Component> T named(Container root, String name, Class<T> type) {
        List<T> found = all(root, name, type);
        assertFalse("No " + name, found.isEmpty());
        return found.get(0);
    }
}
