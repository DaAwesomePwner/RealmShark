package tomato.gui.runs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DungeonStatData;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatFixtures;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;

/**
 * The Dungeons cards' saved-history source: one card per canonical dungeon over every session's saved dungeon runs, each run
 * read with the run feed's rules (outcome, observed span, exact loot and combat links), closed sessions kept by stamp, the
 * current session read every time, and one session's unreadable module degrading only that session.
 */
public class DungeonsSourceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path mixed() throws Exception { Path root = temp.newFolder("history").toPath(); RunFixtures.writeMixed(root); return root; }
    private Path large() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.writeLarge(root, LARGE_SESSIONS, LARGE_RUNS);
        CombatFixtures.writeLarge(root, LARGE_SESSIONS, LARGE_RUNS);
        return root;
    }
    private static DungeonsSource source(SessionStore store) { return new DungeonsSource(store, ZONE, () -> RunFixtures.NOW); }
    private static DungeonsModel all(DungeonsSource source) throws Exception { return source.read(DungeonsQuery.all(), new Cancellation()); }
    private static DungeonCardModel card(DungeonsModel model, String canonical) {
        return model.cards().stream().filter(c -> c.canonical().equals(canonical)).findFirst().orElseThrow(() -> new AssertionError("No card " + canonical));
    }
    private static List<String> names(DungeonsModel model) { return model.cards().stream().map(DungeonCardModel::canonical).collect(Collectors.toList()); }
    private static VisitRef large(int session, int run) { return new VisitRef(id("large-" + session), "L" + session + "-" + run); }

    @Test public void mixedHistoryMakesOneCardPerCanonicalDungeonByTheFeedsRules() throws Exception {
        assertTrue(ParseDungeon.isDungeon(RunFixtures.CRONUS_ALIAS)); assertTrue(ParseDungeon.isDungeon(RunFixtures.CRONUS));
        assertEquals(RunFixtures.CRONUS, DungeonStatData.Snapshot.canonicalName(RunFixtures.CRONUS_ALIAS));
        try (SessionStore store = new SessionStore(mixed(), false, "fixture")) {
            DungeonsModel model = all(source(store));
            assertEquals("Most visits first, then by name", List.of("Lost Halls", "Pirate Cave", RunFixtures.CRONUS, "Ice Citadel", "Snake Pit"), names(model));
            assertEquals("Every saved dungeon run (the Nexus visit is not one)", 13, model.runs());
            assertEquals(0, model.sessionsSkipped()); assertEquals(List.of(), model.issues());
            assertEquals(RunFixtures.NOW, model.capturedAt());

            DungeonCardModel halls = card(model, "Lost Halls");
            assertEquals(7, halls.visits());
            assertEquals("A1, c1, c3 and c4", 4, halls.completed()); assertEquals("c2", 1, halls.left());
            assertEquals("B's open run: B never saved its end (a crash)", 1, halls.appEnded());
            assertEquals("D's open run: an import that saved no end", 1, halls.inProgress()); assertEquals(0, halls.unknown());
            assertEquals(4.0 / 6, halls.completionRate(), 1e-9);
            assertTrue(halls.completionReason(), halls.completionReason().contains("1 in progress"));
            assertEquals("(25 + 10 + 20 + 15) min / 4", Long.valueOf(17 * MINUTE + 30_000), halls.averageDurationMs());
            assertEquals(4, halls.durationRuns()); assertNull(halls.durationReason());
            assertEquals("A1's 6 items: C saved no loot bag, so c1, c3 and c4 are excluded", 6.0, halls.lootPerCompletedRun(), 1e-9);
            assertEquals(1, halls.lootRuns()); assertEquals(3, halls.lootExcluded()); assertTrue(halls.lootPartial());
            assertTrue(halls.lootReason(), halls.lootReason().startsWith("3 of 4 completed runs") && halls.lootReason().contains("saved no loot bag"));
            assertEquals("c1's verified 50 DPS; c2's 999 is not a completed run", 50.0, halls.bestLocalDps(), 1e-9);
            assertEquals(RunFixtures.C1, halls.bestRun()); assertEquals("r-c1", halls.bestRecordingId());
            assertTrue(halls.dpsReason(), halls.dpsReason().startsWith("Best of 2 of 4 completed runs")
                && halls.dpsReason().contains("recorded no damage") && halls.dpsReason().contains("without your verified row"));
            assertEquals("B's v4 is the newest entry", at(0, 11, 30), halls.lastVisit());
            assertEquals(tomato.gui.kit.Portals.spriteId("Lost Halls"), halls.portalId());

            DungeonCardModel pirate = card(model, "Pirate Cave");
            assertEquals(2, pirate.visits()); assertEquals("A's v3: A saved its end", 1, pirate.appEnded()); assertEquals(1, pirate.completed());
            assertEquals(0.5, pirate.completionRate(), 1e-9);
            assertEquals(Long.valueOf(20 * MINUTE), pirate.averageDurationMs());
            assertEquals("d2's bag of two", 2.0, pirate.lootPerCompletedRun(), 1e-9); assertFalse(pirate.lootPartial());
            assertNull(pirate.bestLocalDps()); assertEquals(DungeonCardModel.NO_RECORDING, pirate.dpsReason());

            DungeonCardModel cronus = card(model, RunFixtures.CRONUS);
            assertEquals("The raw alias and the canonical name are one dungeon", 2, cronus.visits());
            assertEquals(RunFixtures.CRONUS, cronus.displayName());
            assertEquals(1, cronus.completed()); assertEquals(1, cronus.left()); assertEquals(0.5, cronus.completionRate(), 1e-9);
            assertNull("c5's session saved no loot bag", cronus.lootPerCompletedRun()); assertEquals(DungeonCardModel.LOOT_NOT_SAVED, cronus.lootReason());

            DungeonCardModel ice = card(model, "Ice Citadel");
            assertEquals(1, ice.completed()); assertEquals(1.0, ice.completionRate(), 1e-9);
            assertEquals("B saved a bag (tagged with A's v1): B1 has a known none", 0.0, ice.lootPerCompletedRun(), 1e-9);
            assertNull(ice.lootReason());
            assertNull("A's v1 recording saved in B's folder never joins B's v1", ice.bestLocalDps());
            assertEquals(DungeonCardModel.NO_RECORDING, ice.dpsReason());

            DungeonCardModel snake = card(model, "Snake Pit");
            assertEquals(1, snake.left()); assertEquals("A finished run without a clear: a known 0 %", 0.0, snake.completionRate(), 1e-9);
            assertNull(snake.averageDurationMs()); assertNull(snake.lootPerCompletedRun()); assertNull(snake.bestLocalDps());
            assertEquals(DungeonCardModel.NO_COMPLETED_RUN, snake.durationReason());
            assertEquals(DungeonCardModel.NO_COMPLETED_RUN, snake.lootReason()); assertEquals(DungeonCardModel.NO_COMPLETED_RUN, snake.dpsReason());
        }
    }

    @Test public void cardsAgreeWithTheRunFeedsCardsForTheSameRuns() throws Exception {
        try (SessionStore store = new SessionStore(mixed(), false, "fixture")) {
            DungeonsModel model = all(source(store));
            RunFeedSource feed = new RunFeedSource(store, ZONE, () -> RunFixtures.NOW, temp.newFolder("scratch").toPath());
            RunFeedSource.Page page = feed.first(RunFeedQuery.all(), new Cancellation());
            while (page.model().more()) { RunFeedSource.Page next = feed.more(page, new Cancellation()); page.close(); page = next; }
            Map<String, List<RunCardModel>> byDungeon = new TreeMap<>();
            for (RunCardModel run : page.model().cards())
                byDungeon.computeIfAbsent(DungeonStatData.Snapshot.canonicalName(run.map()), k -> new ArrayList<>()).add(run);
            page.close();
            assertEquals(byDungeon.keySet(), new TreeSet<>(names(model)));
            for (Map.Entry<String, List<RunCardModel>> dungeon : byDungeon.entrySet())
                assertEquals(dungeon.getKey(), DungeonCardModel.of(dungeon.getKey(), dungeon.getValue()), card(model, dungeon.getKey()));
        }
    }

    @Test public void theCurrentSessionsOpenRunsAreInProgressOrUnknownAndItIsReadAgainEveryTime() throws Exception {
        try (SessionStore store = new SessionStore(mixed(), true, "fixture")) {
            store.put("runs", "live", HomeHistoryFixture.visit("live", "Lost Halls", store.started() + 1_000, 0, false));
            store.put("runs", "nostart", HomeHistoryFixture.visit("nostart", "Lost Halls", 0, 0, false));   // no entry time: Unknown
            store.flush();
            DungeonsSource source = source(store);
            DungeonCardModel halls = card(all(source), "Lost Halls");
            assertEquals(9, halls.visits());
            assertEquals("D's import and this app run's open run", 2, halls.inProgress()); assertEquals(1, halls.unknown());
            assertEquals("Neither counts as finished", 4.0 / 6, halls.completionRate(), 1e-9);
            assertTrue(halls.completionReason(), halls.completionReason().contains("2 in progress") && halls.completionReason().contains("1 unknown"));

            ActivityJournal.Visit done = HomeHistoryFixture.visit("live", "Lost Halls", store.started() + 1_000, store.started() + 11 * MINUTE, true);
            store.put("runs", "live", done);
            store.flush();
            DungeonCardModel again = card(all(source), "Lost Halls");
            assertEquals("The current session is never kept", 5, again.completed()); assertEquals(1, again.inProgress());
            assertEquals(5.0 / 7, again.completionRate(), 1e-9);
            assertEquals("A, B, C and D are kept; the current session is not", 4, source.cachedSessions());
        }
    }

    @Test public void closedSessionsAreKeptUntilTheirFilesChange() throws Exception {
        Path root = mixed(), loot = root.resolve(RunFixtures.D).resolve("loot.jsonl");
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            DungeonsSource source = source(store);
            assertEquals(2.0, card(all(source), "Pirate Cave").lootPerCompletedRun(), 1e-9);
            assertEquals(4, source.cachedSessions());
            int reads = source.sessionReads();
            assertEquals("Four closed sessions and this app run's", 5, reads);
            all(source);
            assertEquals("Unchanged closed sessions are skipped; only the current session is read again", reads + 1, source.sessionReads());

            FileTime written = Files.getLastModifiedTime(loot);
            String text = Files.readString(loot);
            assertTrue(text.contains("\"visitId\":\"d2\""));
            Files.writeString(loot, text.replace("\"visitId\":\"d2\"", "\"visitId\":\"d9\""));   // same size: the bag leaves d2
            Files.setLastModifiedTime(loot, written);
            assertEquals("Same name, size and time: the kept partial is used", 2.0, card(all(source), "Pirate Cave").lootPerCompletedRun(), 1e-9);
            Files.setLastModifiedTime(loot, FileTime.fromMillis(written.toMillis() + 2_000));
            reads = source.sessionReads();
            DungeonCardModel pirate = card(all(source), "Pirate Cave");
            assertEquals("A changed stamp reads that session again", reads + 2, source.sessionReads());
            assertEquals("d2 now has a known none", 0.0, pirate.lootPerCompletedRun(), 1e-9);

            HomeHistoryFixture.runs(root, RunFixtures.C, HomeHistoryFixture.visit("c2", "Lost Halls", at(0, 5, 20), at(0, 5, 25), true));
            Path runs = root.resolve(RunFixtures.C).resolve("runs");
            try (var files = Files.list(runs)) { for (Path file : files.collect(Collectors.toList())) Files.setLastModifiedTime(file, FileTime.fromMillis(written.toMillis() + 5_000)); }
            assertEquals("A saved run's change is seen", 5, card(all(source), "Lost Halls").completed());
        }
    }

    @Test public void anUnreadableSessionIsSkippedCountedAndNamedWhileTheOthersShow() throws Exception {
        Path root = mixed();
        String broken = id("runs-broken");
        HomeHistoryFixture.session(root, broken, at(0, 4, 0), at(0, 4, 30));
        HomeHistoryFixture.runs(root, broken, HomeHistoryFixture.visit("x1", "Lost Halls", at(0, 4, 5), at(0, 4, 15), true));
        Files.writeString(root.resolve(broken).resolve("session.json"), "{broken");
        Path damaged = CombatFixtures.write(root, RunFixtures.C, "runs", "damaged", "{broken");   // a damaged saved run
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            DungeonsModel model = all(source(store));
            assertEquals(2, model.sessionsSkipped());
            assertEquals(model.issues().toString(), 2, model.issues().size());
            assertTrue(model.issues().toString(), model.issues().stream().anyMatch(issue -> issue.startsWith(broken + ": ")));
            assertTrue(model.issues().toString(), model.issues().stream().anyMatch(issue -> issue.startsWith(RunFixtures.C + ": runs could not be read")));
            assertTrue("No path in the issues", model.issues().stream().noneMatch(issue -> issue.contains(root.toString())));
            assertEquals("C's five runs and the broken session's run are not counted", 8, model.runs());
            DungeonCardModel halls = card(model, "Lost Halls");
            assertEquals("A1, B4 and D1 still show", 3, halls.visits());
            assertFalse("C's alias run is gone with C", model.cards().stream().anyMatch(c -> c.visits() == 2 && c.canonical().equals(RunFixtures.CRONUS)));
            Files.delete(damaged);
            assertEquals("Repaired: read again", 0, all(source(store)).issues().stream().filter(issue -> issue.startsWith(RunFixtures.C)).count());
        }
    }

    @Test public void aSessionWhoseLootOrCombatCannotBeReadShowsThoseFactsAsUnknown() throws Exception {
        Path root = mixed(), loot = root.resolve(RunFixtures.D).resolve("loot.jsonl");
        Files.writeString(loot, "{broken\n" + Files.readString(loot));   // not the final line: the journal is damaged
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            DungeonsSource source = source(store);
            source.combatReader((s, catalog, scope, sink) -> {
                if (scope.equals(RunFixtures.C)) throw new java.io.IOException("synthetic listing failure");
                CombatFacts.read(s, catalog, scope, sink);
            });
            for (int read = 0; read < 2; read++) {
                DungeonsModel model = all(source);
                assertEquals("Nothing is skipped (read " + read + ")", 0, model.sessionsSkipped());
                assertEquals(13, model.runs());
                DungeonCardModel pirate = card(model, "Pirate Cave");
                assertNull("d2's loot is unknown, never 0", pirate.lootPerCompletedRun());
                assertEquals(DungeonCardModel.LOOT_UNREADABLE, pirate.lootReason());
                DungeonCardModel halls = card(model, "Lost Halls");
                assertEquals("c1's 50 DPS is unknown; A1's 20 remains", 20.0, halls.bestLocalDps(), 1e-9);
                assertEquals(RunFixtures.A1, halls.bestRun()); assertEquals("r-v1-long", halls.bestRecordingId());
                assertTrue(halls.dpsReason(), halls.dpsReason().contains("combat records could not be read"));
                assertEquals(model.issues().toString(), 2, model.issues().size());
                assertTrue(model.issues().toString(), model.issues().contains(RunFixtures.C + ": encounters could not be read (IOException)"));
                assertTrue(model.issues().toString(), model.issues().stream().anyMatch(issue -> issue.startsWith(RunFixtures.D + ": loot could not be read")));
            }
        }
    }

    @Test public void textAndSortNarrowAndOrderTheCards() throws Exception {
        try (SessionStore store = new SessionStore(mixed(), false, "fixture")) {
            DungeonsSource source = source(store);
            assertEquals(List.of("Lost Halls"), names(source.read(new DungeonsQuery(" lost ", null), new Cancellation())));
            assertEquals(List.of(RunFixtures.CRONUS), names(source.read(new DungeonsQuery("CRONUS", DungeonsQuery.Sort.NAME), new Cancellation())));
            DungeonsModel none = source.read(new DungeonsQuery("vault", null), new Cancellation());
            assertEquals(List.of(), none.cards());
            assertEquals("No match is not an empty history: every saved run is still counted", 13, none.runs());
            assertEquals(List.of("Lost Halls", "Ice Citadel", "Pirate Cave", "Snake Pit", RunFixtures.CRONUS),
                names(source.read(new DungeonsQuery("", DungeonsQuery.Sort.RECENT), new Cancellation())));
            assertEquals(List.of("Ice Citadel", "Lost Halls", "Pirate Cave", "Snake Pit", RunFixtures.CRONUS),
                names(source.read(new DungeonsQuery(null, DungeonsQuery.Sort.NAME), new Cancellation())));
            assertEquals(DungeonsQuery.Sort.MOST_VISITS, new DungeonsQuery(null, null).sort());
            assertEquals("", new DungeonsQuery(null, null).text());
            assertEquals(DungeonsQuery.all(), new DungeonsQuery("", DungeonsQuery.Sort.MOST_VISITS));
        }
    }

    @Test public void theLargeHistoryIsReadOffTheEventDispatchThreadAndKeptPerClosedSession() throws Exception {
        Path root = large();
        assertFalse(SwingUtilities.isEventDispatchThread());
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            DungeonsSource source = new DungeonsSource(store, ZONE, () -> NOW);
            long cold = System.nanoTime();
            DungeonsModel model = source.read(DungeonsQuery.all(), new Cancellation());
            long coldMillis = (System.nanoTime() - cold) / 1_000_000;
            int reads = source.sessionReads();
            long warm = System.nanoTime();
            DungeonsModel again = source.read(DungeonsQuery.all(), new Cancellation());
            long warmMillis = (System.nanoTime() - warm) / 1_000_000;
            System.out.println("DungeonsSource.read large history (" + LARGE_SESSIONS + " sessions x " + LARGE_RUNS + " runs, with combat records): cold "
                + coldMillis + " ms, warm " + warmMillis + " ms");
            assertEquals("The stamp cache skips every unchanged closed session", reads + 1, source.sessionReads());
            assertEquals(LARGE_SESSIONS, source.cachedSessions());
            assertEquals(model.cards(), again.cards());

            assertEquals(LARGE_SESSIONS * LARGE_RUNS, model.runs());
            assertEquals(List.of("Ice Citadel", "Lost Halls", "Pirate Cave", "Snake Pit"), names(model));   // equal visits: by name
            for (DungeonCardModel card : model.cards()) assertEquals(LARGE_SESSIONS * LARGE_RUNS / 4, card.visits());
            DungeonCardModel halls = card(model, "Lost Halls"), ice = card(model, "Ice Citadel");
            assertEquals(1.0, halls.completionRate(), 1e-9); assertEquals(Long.valueOf(150_000), halls.averageDurationMs());
            assertEquals("One bag of three items per run", 3.0, halls.lootPerCompletedRun(), 1e-9);
            assertNotNull(halls.bestLocalDps()); assertNull(halls.dpsReason());
            assertEquals("Equal DPS in every run: the newest completed run", large(0, 36), halls.bestRun());
            assertEquals(CombatFixtures.largeRecordingId(id("large-0"), "L0-36"), halls.bestRecordingId());
            assertEquals("The degenerate fixture: every Ice Citadel run left", 0.0, ice.completionRate(), 1e-9);
            assertNull(ice.averageDurationMs()); assertNull(ice.lootPerCompletedRun()); assertNull(ice.bestLocalDps());
            assertTrue("Soft bound; the times are recorded from the line above (target: warm ≤ 250 ms)", coldMillis < 10_000 && warmMillis < 2_500);
        }
    }

    @Test public void readsRefuseTheEventDispatchThreadAndStopWhenCancelled() throws Exception {
        try (SessionStore store = new SessionStore(mixed(), false, "fixture")) {
            DungeonsSource source = source(store);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try { source.read(DungeonsQuery.all(), new Cancellation()); } catch (Throwable failure) { thrown.set(failure); }
            });
            assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof IllegalStateException);
            Cancellation cancelled = new Cancellation(); cancelled.cancel();
            try { source.read(DungeonsQuery.all(), cancelled); fail("A cancelled read stops"); }
            catch (java.util.concurrent.CancellationException expected) { }
            assertEquals("A cancelled read keeps nothing", 0, source.cachedSessions());
        }
    }
}
