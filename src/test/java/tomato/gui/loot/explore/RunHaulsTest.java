package tomato.gui.loot.explore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.runs.RunFixtures;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** Explore's reads over the synthetic Runs fixture: one run's haul through the recap's reader, and loot outside runs. */
public class RunHaulsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private SessionStore store;

    @Before public void write() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        store = new SessionStore(root, false, "fixture");
    }

    @After public void close() throws Exception { store.close(); }

    @Test public void aRunsHaulIsItsRecapsHeaderAndBags() throws Exception {
        RunHauls.RunHaul run = RunHauls.over(() -> store).run(RunFixtures.A1, new Cancellation());
        assertNull(run.unavailable());
        assertEquals(RunFixtures.A1, run.ref());
        assertEquals("Lost Halls", run.haul().header().mapName());
        assertEquals("Lost Halls", run.dungeon());
        assertEquals("Completed", run.haul().header().outcome());
        assertEquals("6 items in 2 bags · 1 UT · 1 ST · 2 potions", run.haul().tally());
        assertEquals(List.of("White", "Orange"), run.haul().shelf().stream().map(HaulModel.Shelf::bag).toList());
        assertEquals(502, run.haul().hero().item().id());
        assertEquals("Synthetic boss", run.haul().hero().dropper());
    }

    @Test public void aRunNotInSavedHistoryIsUnavailable() throws Exception {
        RunHauls.RunHaul run = RunHauls.over(() -> store).run(new VisitRef(RunFixtures.A, "missing"), new Cancellation());
        assertNotNull(run.unavailable());
        assertNull(run.dungeon());
        assertTrue(run.haul().shelf().isEmpty());
    }

    @Test public void lootOutsideRunsIsGroupedBySessionNewestFirst() throws Exception {
        List<RunHauls.UnlinkedSession> sessions = RunHauls.over(() -> store).unlinked(new Cancellation());
        assertEquals("Only session A has a bag without a recorded run", 1, sessions.size());
        assertEquals(RunFixtures.A, sessions.get(0).sessionId());
        assertEquals(List.of("Brown"), sessions.get(0).bags().stream().map(HaulModel.Bag::bag).toList());
        assertEquals(1, sessions.get(0).items());
    }

    @Test public void withoutSavedHistoryEveryReadSaysSo() throws Exception {
        RunHauls.Loader loader = RunHauls.over(() -> null);
        try { loader.run(RunFixtures.A1, new Cancellation()); fail("A run read needs saved history"); }
        catch (IOException expected) { assertEquals("Saved history is not open in this app run", expected.getMessage()); }
        try { loader.unlinked(new Cancellation()); fail("An unlinked read needs saved history"); }
        catch (IOException expected) { assertEquals("Saved history is not open in this app run", expected.getMessage()); }
    }
}
