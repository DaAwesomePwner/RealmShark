package tomato.gui.loot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.glance.home.HomeArchive;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.LootDashboard;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;
import static tomato.gui.loot.HighlightsFixture.*;
import static tomato.gui.loot.HighlightsModel.Window.SESSION;
import static tomato.gui.loot.HighlightsModel.Window.TODAY;

/**
 * Loot Highlights' reader: saved sessions of the window through per-session stamps (closed sessions kept, the current one read
 * every time), the live feed labeled "This app run · not saved" when no history is open, one unreadable session degrading
 * alone, and tiles that agree with Home's for the same saved history and window. Synthetic history only.
 */
public class HighlightsSourceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long EVENING = at(0, 23, 0);

    private Path fixture() throws Exception { Path root = temp.newFolder("history").toPath(); HighlightsFixture.write(root); return root; }
    private static HighlightsSource source(SessionStore store, long now) { return new HighlightsSource(() -> store, new LootDashboard.Feed(), ZONE, () -> now); }
    private static HighlightsModel read(HighlightsSource source, HighlightsModel.Window window) { return source.read(window, new Cancellation()); }
    private static List<Integer> ids(HighlightsModel model) { return model.notable().stream().map(HighlightsModel.Notable::itemId).collect(Collectors.toList()); }

    /** The tiles show exactly Home's counts for the same history and window, and are unknown exactly when Home's are. */
    private static void assertHomeParity(String what, HomeArchive.Totals home, HighlightsModel model) {
        if (!home.lootRecorded()) {
            for (DisplayValue tile : List.of(model.ut(), model.st(), model.potions(), model.whites()))
                assertEquals(what + ": unknown on Home, unknown here", DisplayValue.State.UNKNOWN, tile.state);
            return;
        }
        assertEquals(what + ": UT", DisplayFormat.formatInteger(home.untiered()), model.ut().text());
        assertEquals(what + ": ST", DisplayFormat.formatInteger(home.setTiered()), model.st().text());
        assertEquals(what + ": potions", DisplayFormat.formatInteger(home.potions()), model.potions().text());
        assertEquals(what + ": white bags", DisplayFormat.formatInteger(home.whiteBags()), model.whites().text());
    }

    @Test public void todayCountsTheDaysSavedSessionsAndListsNotableDropsNewestFirst() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            HighlightsModel model = read(source(store, EVENING), TODAY);
            assertNull(model.unavailable());
            assertEquals(TODAY, model.window()); assertEquals(HighlightsModel.Source.SAVED, model.source());
            assertEquals(EVENING, model.capturedAt());
            assertEquals("Yesterday's session is not today's", "2", model.ut().text());
            assertEquals("2", model.st().text()); assertEquals("5", model.potions().text()); assertEquals("3", model.whites().text());
            assertEquals(6, model.bags()); assertEquals(1, model.unnamedBags()); assertEquals("Item 104", 1, model.enchantUnknown());
            Map<String, Integer> stats = new LinkedHashMap<>();
            stats.put("Life", 3); stats.put("Defense", 1); stats.put(HighlightsModel.OTHER_POTIONS, 1);
            assertEquals(stats, model.potionsByStat());
            assertEquals(List.of(202, LIFE, 201, 105, DEFENSE, 102, 101, LIFE, GREATER_LIFE), ids(model));
            HighlightsModel.Notable newest = model.notable().get(0);
            assertEquals(ref(LATE, "l1"), newest.visit()); assertNull("Unrecognized area: Unknown area", newest.dungeon());
            assertEquals(HighlightsModel.Kind.UT, newest.kind()); assertEquals(at(0, 20, 20), newest.time());
            HighlightsModel.Notable legacy = model.notable().get(2);
            assertNull("No bag name saved", legacy.bag()); assertNull("No visit recorded", legacy.visit());
            assertEquals(HighlightsModel.Kind.ENCHANTED, model.notable().get(5).kind());
            assertEquals(List.of(new HighlightsModel.DungeonCell("Lost Halls", model.dungeons().get(0).portalId(), 3, 1, 1, 2),
                new HighlightsModel.DungeonCell("Pirate Cave", model.dungeons().get(1).portalId(), 1, 0, 1, 1),
                new HighlightsModel.DungeonCell(null, 0, 2, 1, 0, 2)), model.dungeons());
            assertEquals(0, model.sessionsSkipped()); assertFalse(model.capped());
        }
    }

    @Test public void thisSessionReadsOnlyTheCurrentSessionAndReadsItEveryTime() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), true, "fixture")) {
            HighlightsSource source = source(store, store.started() + HOUR);
            HighlightsModel empty = read(source, SESSION);
            assertEquals("Nothing saved this session yet: unknown, never 0", DisplayValue.State.UNKNOWN, empty.ut().state);
            assertEquals(HighlightsModel.NO_LOOT, empty.ut().detail);
            store.append("loot", drop("White", "Lost Halls", store.started() + 1_000, ref(store.currentId(), "c1"), ut(701, 2)));
            store.flush();
            int reads = source.sessionReads();
            HighlightsModel one = read(source, SESSION);
            assertEquals("1", one.ut().text()); assertEquals("1", one.whites().text());
            assertEquals("The current session is read again", reads + 1, source.sessionReads());
            assertEquals(ref(store.currentId(), "c1"), one.notable().get(0).visit());
            store.append("loot", drop("Orange", "Lost Halls", store.started() + 2_000, null, ut(702, null)));
            store.flush();
            HighlightsModel two = read(source, SESSION);
            assertEquals("2", two.ut().text()); assertEquals("1", two.whites().text());
            assertEquals(reads + 2, source.sessionReads());
            assertEquals("The current session is never kept", 0, source.cachedSessions());
            assertEquals("Saved history · This session", two.sourceLabel());
        }
    }

    @Test public void closedSessionsAreKeptUntilTheirFilesChange() throws Exception {
        Path root = fixture();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            HighlightsSource source = source(store, EVENING);
            HighlightsModel first = read(source, TODAY);
            assertEquals("Today's two sessions", 2, source.sessionReads());
            assertEquals(2, source.cachedSessions());
            assertEquals(first.notable(), read(source, TODAY).notable());
            assertEquals("Unchanged closed sessions are not read again", 2, source.sessionReads());
            Path loot = root.resolve(LATE).resolve("loot.jsonl");
            FileTime written = Files.getLastModifiedTime(loot);
            HomeHistoryFixture.loot(root, LATE, drop("Red", "Lost Halls", at(0, 20, 40), ref(LATE, "l2"), ut(203, 0)));
            Files.setLastModifiedTime(loot, FileTime.fromMillis(written.toMillis() + 2_000));
            HighlightsModel changed = read(source, TODAY);
            assertEquals("Only the changed session is read again", 3, source.sessionReads());
            assertEquals("3", changed.ut().text());
            assertEquals(203, changed.notable().get(0).itemId());
        }
    }

    @Test public void theTilesEqualHomesCountsForTheSameHistoryAndWindow() throws Exception {
        Path home = temp.newFolder("home").toPath(), highlights = fixture(), large = temp.newFolder("large").toPath();
        HomeHistoryFixture.write(home);
        HighlightsFixture.writeLarge(large, BIG_SESSIONS, BIG_BAGS);
        Object[][] cases = {{"Home's fixture", home, NOW}, {"Highlights' fixture", highlights, EVENING}, {"The large fixture", large, at(0, 23, 50)}};
        for (Object[] c : cases) {
            try (SessionStore store = new SessionStore((Path) c[1], false, "fixture")) {
                long now = (Long) c[2];
                HighlightsSource source = source(store, now);
                assertHomeParity(c[0] + ", Today", HomeArchive.read(store, HomeArchive.Window.TODAY, now, ZONE, List.of()).totals(), read(source, TODAY));
                assertHomeParity(c[0] + ", This session (nothing saved)", HomeArchive.read(store, HomeArchive.Window.SESSION, now, ZONE, List.of()).totals(),
                    read(source, SESSION));
            }
        }
        try (SessionStore store = new SessionStore(highlights, true, "fixture")) {
            long now = store.started() + HOUR;
            store.append("loot", drop("White", "Lost Halls", store.started() + 1_000, null, ut(801, 0), potion(LIFE), potion(OTHER_POTION)));
            store.append("loot", drop("B.White", "Snake Pit", store.started() + 2_000, null, st(802, 3)));
            store.append("loot", drop(null, "Snake Pit", store.started() + 3_000, null, st(803, null)));
            store.flush();
            HighlightsModel session = read(source(store, now), SESSION);
            assertEquals("2", session.whites().text());
            assertHomeParity("This session with saved bags", HomeArchive.read(store, HomeArchive.Window.SESSION, now, ZONE, List.of()).totals(), session);
        }
    }

    @Test public void withoutSavedHistoryTheLiveFeedIsShownLabeledNotSavedAndCapped() throws Exception {
        List<LootFacts.Bag> bags = new ArrayList<>(List.of(
            bag("White", "Lost Halls", at(-1, 23, 0), null, item(1, true, false, false, 0)),
            bag("Orange", "Lost Halls", at(0, 9, 0), null, item(2, true, false, false, 2)),
            bag("White", "Pirate Cave", at(0, 10, 0), null, item(LIFE, false, false, true, null))));
        boolean[] capped = {false};
        long[] revision = {3};
        HighlightsSource.Live live = new HighlightsSource.Live() {
            @Override public long revision() { return revision[0]; }
            @Override public List<LootFacts.Bag> snapshot(String session) { return List.copyOf(bags); }
            @Override public boolean capped() { return capped[0]; }
        };
        HighlightsSource source = new HighlightsSource(() -> null, live, ZONE, () -> EVENING);
        assertEquals("The nudge is the feed's revision", 3, source.revision());
        HighlightsModel today = read(source, TODAY);
        assertEquals(HighlightsModel.Source.LIVE_UNSAVED, today.source());
        assertEquals("This app run · not saved", today.sourceLabel());
        assertEquals("Today's two bags of this app run", "1", today.ut().text());
        assertEquals(DisplayValue.State.KNOWN, today.ut().state);
        assertEquals(2, today.bags());
        assertEquals("This session: every retained bag of this app run", 3, read(source, SESSION).bags());
        capped[0] = true;
        HighlightsModel full = read(source, SESSION);
        assertEquals("This app run · not saved · latest 1,000 bags", full.sourceLabel());
        assertEquals(DisplayValue.State.PARTIAL, full.ut().state);
        assertTrue(full.capped());

        HighlightsSource none = new HighlightsSource(() -> null, new LootDashboard.Feed(), ZONE, () -> EVENING);
        HighlightsModel nothing = read(none, SESSION);
        assertEquals(0, none.revision());
        assertEquals(HighlightsModel.Source.LIVE_UNSAVED, nothing.source());
        assertEquals(DisplayValue.State.UNKNOWN, nothing.ut().state);
        assertEquals(HighlightsModel.NO_LIVE_LOOT, nothing.ut().detail);
        assertEquals(ZONE, none.zone());
    }

    @Test public void oneUnreadableSessionDegradesAloneAndIsCounted() throws Exception {
        Path root = fixture(), loot = root.resolve(EARLY).resolve("loot.jsonl");
        Files.writeString(loot, "{broken\n" + Files.readString(loot));   // not the final line: the journal is damaged
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            HighlightsModel model = read(source(store, EVENING), TODAY);
            assertEquals(1, model.sessionsSkipped());
            assertEquals("The late session still counts", "1", model.ut().text());
            assertEquals(DisplayValue.State.PARTIAL, model.ut().state);
            assertTrue(model.ut().detail, model.ut().detail.startsWith("1 saved session could not be read"));
            assertEquals("1", model.whites().text()); assertEquals("2", model.potions().text());
        }
        Files.writeString(root.resolve(LATE).resolve("session.json"), "{broken");
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            assertEquals("Unreadable metadata changed today counts too (Home's rule)", 2, read(source(store, EVENING), TODAY).sessionsSkipped());
            try (Stream<Path> files = Files.walk(root.resolve(LATE))) {
                for (Path file : files.collect(Collectors.toList())) Files.setLastModifiedTime(file, FileTime.fromMillis(at(0, 0, 0) - HOUR));
            }
            assertEquals("Unchanged since before today: it cannot hold today's loot", 1, read(source(store, EVENING), TODAY).sessionsSkipped());
        }
    }

    @Test public void aHistoryFolderThatCannotBeListedIsUnavailableWithoutAPath() throws Exception {
        Path file = temp.newFile("not-a-folder").toPath();
        try (SessionStore store = new SessionStore(file, false, "fixture")) {
            HighlightsModel model = read(source(store, EVENING), TODAY);
            assertNotNull(model.unavailable());
            assertTrue(model.unavailable(), model.unavailable().startsWith("Saved history could not be read"));
            assertFalse("No path is shown", model.unavailable().contains(temp.getRoot().getName()) || model.unavailable().contains("/"));
            assertEquals(DisplayValue.State.UNKNOWN, model.ut().state);
        }
    }

    @Test public void readsRefuseTheEventDispatchThreadAndStopWhenCancelled() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            HighlightsSource source = source(store, EVENING);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try { read(source, TODAY); } catch (Throwable failure) { thrown.set(failure); }
            });
            assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof IllegalStateException);
            Cancellation cancelled = new Cancellation();
            cancelled.cancel();
            assertThrows(CancellationException.class, () -> source.read(TODAY, cancelled));
        }
    }

    @Test public void theLargeHistoryIsReadColdAndWarmOffTheEventDispatchThread() throws Exception {
        Path root = temp.newFolder("large").toPath();
        HighlightsFixture.writeLarge(root, BIG_SESSIONS, BIG_BAGS);
        assertFalse(SwingUtilities.isEventDispatchThread());
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            HighlightsSource source = source(store, at(0, 23, 50));
            long cold = System.nanoTime();
            HighlightsModel model = read(source, TODAY);
            long coldMillis = (System.nanoTime() - cold) / 1_000_000;
            int reads = source.sessionReads();
            long warm = System.nanoTime();
            HighlightsModel again = read(source, TODAY);
            long warmMillis = (System.nanoTime() - warm) / 1_000_000;
            System.out.println("HighlightsSource.read large history (" + BIG_SESSIONS + " sessions x " + BIG_BAGS + " bags, Today): cold "
                + coldMillis + " ms, warm " + warmMillis + " ms");
            assertEquals("Every closed session was read once", BIG_SESSIONS, reads);
            assertEquals("The stamp cache skips every unchanged closed session", reads, source.sessionReads());
            assertEquals(model.notable(), again.notable());
            int bags = BIG_SESSIONS * BIG_BAGS;
            assertEquals(bags, model.bags());
            assertEquals(DisplayFormat.formatInteger(BIG_SESSIONS * 4), model.ut().text());
            assertEquals(DisplayFormat.formatInteger(bags), model.st().text());
            assertEquals(DisplayFormat.formatInteger(bags), model.potions().text());
            // Each bag name keeps its newest 200 (PR #27 review), so the grid always finds the newest 200 visible drops.
            assertEquals(HighlightsModel.NOTABLE_LIMIT, model.shown(bag -> true).items().size());
            assertTrue(model.notable().size() <= model.notableByBag().size() * HighlightsModel.NOTABLE_LIMIT);
            assertEquals(4, model.dungeons().size());
            assertTrue("Soft bound; the times are recorded from the line above (target: warm ≤ 250 ms)", coldMillis < 10_000 && warmMillis < 2_500);
        }
    }
}
