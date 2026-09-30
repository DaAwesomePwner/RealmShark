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
import tomato.gui.activity.ActivityQueries;
import tomato.gui.glance.home.HomeArchive;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.stats.LootTestDrops;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatFixtures;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.item;

/** The feed's saved-history source: 50 newest runs per page, facets, and card facts joined only by exact VisitRef. */
public class RunFeedSourceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path large() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.writeLarge(root, LARGE_SESSIONS, LARGE_RUNS);
        CombatFixtures.writeLarge(root, LARGE_SESSIONS, LARGE_RUNS);
        return root;
    }
    private Path scenario() throws Exception { Path root = temp.newFolder("history").toPath(); RunFixtures.write(root); return root; }
    private RunFeedSource source(SessionStore store, long now) throws Exception {
        return new RunFeedSource(store, ZONE, () -> now);
    }
    private static List<VisitRef> refs(RunFeedModel model) { return model.cards().stream().map(RunCardModel::ref).collect(Collectors.toList()); }
    private static RunCardModel card(RunFeedModel model, VisitRef ref) {
        return model.cards().stream().filter(c -> c.ref().equals(ref)).findFirst().orElseThrow(() -> new AssertionError("No card " + ref));
    }
    private static VisitRef large(int session, int run) { return new VisitRef(id("large-" + session), "L" + session + "-" + run); }

    @Test public void theFirstPageIsTheFiftyNewestRunsReadOffTheEventDispatchThread() throws Exception {
        Path root = large();
        assertFalse(SwingUtilities.isEventDispatchThread());
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, NOW);
            long cold = System.nanoTime();
            RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation());
            long coldMillis = (System.nanoTime() - cold) / 1_000_000, warm = System.nanoTime();
            source.first(RunFeedQuery.all(), new Cancellation()).close();
            long warmMillis = (System.nanoTime() - warm) / 1_000_000;
            System.out.println("RunFeedSource.first large history (" + LARGE_SESSIONS + " sessions x " + LARGE_RUNS + " runs, with combat records): cold "
                + coldMillis + " ms, warm " + warmMillis + " ms");
            RunFeedModel model = page.model();
            List<VisitRef> expected = new ArrayList<>();
            for (int r = LARGE_RUNS - 1; r >= 0; r--) expected.add(large(0, r));
            for (int r = LARGE_RUNS - 1; expected.size() < RunFeedSource.PAGE; r--) expected.add(large(1, r));
            assertEquals("The 50 newest runs of all sessions, newest first", expected, refs(model));
            assertEquals(LARGE_SESSIONS * LARGE_RUNS, page.matches());
            assertTrue(model.more());
            assertEquals(NOW, model.capturedAt());
            // Sessions 0 and 1 began at 08:00 and 04:00 today: 50 runs of 150 s, and session 1's older runs are today's too.
            assertEquals(List.of("Today · 50 runs · 25 completed · 2 h 5 m · more below"),
                model.days().stream().map(RunFeedModel.Day::header).collect(Collectors.toList()));

            RunCardModel newest = model.cards().get(0);   // L0-39: Snake Pit, left
            assertEquals(RunOutcome.LEFT, newest.outcome()); assertEquals("Snake Pit", newest.map());
            assertEquals(Long.valueOf(150_000), newest.durationMs());
            assertEquals("High tier, potion, plain", List.of(10_039, 20_039, 30_039), newest.loot().stream().map(RunCardModel.LootItem::id).collect(Collectors.toList()));
            assertEquals("1 potion", newest.lootSummary()); assertEquals(3, newest.lootCount());
            assertEquals("Orange", newest.loot().get(0).bag());
            assertNull("No capture intervals saved: fame gained is unknown", newest.fameGained());
            assertNull("No roster observed", newest.partySize());
            RunCardModel white = card(model, large(0, 30));
            assertEquals(RunOutcome.COMPLETED, white.outcome());
            assertEquals(List.of(10_030, 20_030, 30_030), white.loot().stream().map(RunCardModel.LootItem::id).collect(Collectors.toList()));
            assertEquals("1 UT · 1 potion", white.lootSummary()); assertEquals("White", white.loot().get(0).bag());

            List<CombatRecord> saved = new ArrayList<>();
            CombatFacts.read(store, store.catalog(), id("large-0"), saved::add);
            CombatRecord record = saved.stream().filter(r -> r.recordingId.equals(CombatFixtures.largeRecordingId(id("large-0"), "L0-39"))).findFirst().orElseThrow();
            RunCardModel.Combat combat = newest.combat();
            assertEquals(record.recordingId, combat.recordingId()); assertEquals(1, combat.recordings());
            assertEquals(Integer.valueOf(record.local().rank), combat.rank()); assertEquals(8, combat.contributors());
            assertEquals(record.share(record.local()), combat.share()); assertEquals(record.dps(record.local()), combat.localDps());
            assertEquals(record.localDamage(), combat.localDamage()); assertEquals(record.local().deaths, combat.localDeaths());
            assertNull(combat.localUnavailable()); assertNull(newest.combatReason());

            // Home's Recent runs and the feed pick and order the same loot for the same runs.
            HomeArchive.Result home = HomeArchive.read(store, HomeArchive.Window.TODAY, NOW, ZONE, List.of());
            for (HomeArchive.RecentRun run : home.recent())
                assertEquals(run.lootIds(), card(model, run.visit()).loot().stream().map(RunCardModel.LootItem::id).collect(Collectors.toList()));
            page.close();
            assertTrue("Soft bound; the time is recorded from the line above", coldMillis < 10_000);
        }
    }

    @Test public void moreContinuesWithoutDuplicatesUntilTheOldestRun() throws Exception {
        Path root = large();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, NOW);
            RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation());
            int pages = 1;
            while (page.model().more()) {
                RunFeedSource.Page next = source.more(page, new Cancellation());
                List<VisitRef> before = refs(page.model()), after = refs(next.model());
                assertEquals("Earlier pages stay as they were", before, after.subList(0, before.size()));
                assertEquals(Math.min(before.size() + RunFeedSource.PAGE, LARGE_SESSIONS * LARGE_RUNS), after.size());
                page.close();   // the next page keeps what it needs
                page = next; pages++;
            }
            List<RunCardModel> all = page.model().cards();
            assertEquals(24, pages);
            assertEquals(LARGE_SESSIONS * LARGE_RUNS, all.size());
            assertEquals("No duplicates", all.size(), new HashSet<>(refs(page.model())).size());
            for (int i = 1; i < all.size(); i++) assertTrue("Newest first", all.get(i - 1).entered() >= all.get(i).entered());
            assertEquals(large(LARGE_SESSIONS - 1, 0), all.get(all.size() - 1).ref());
            assertFalse(page.model().days().get(page.model().days().size() - 1).continues());
            RunFeedSource.Page again = source.more(page, new Cancellation());
            assertEquals("Nothing more to load", refs(page.model()), refs(again.model()));
            again.close(); page.close();
            try { source.more(page, new Cancellation()); fail("A closed page cannot continue"); }
            catch (IllegalStateException expected) { }
        }
    }

    @Test public void filtersNarrowTheSavedRunsBeforePaging() throws Exception {
        Path root = large();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, NOW);
            try (RunFeedSource.Page completed = source.first(new RunFeedQuery("", Set.of(RunOutcome.COMPLETED), null), new Cancellation())) {
                assertEquals(600, completed.matches());
                assertEquals(RunFeedSource.PAGE, completed.model().cards().size());
                assertTrue(completed.model().cards().stream().allMatch(c -> c.outcome() == RunOutcome.COMPLETED));
            }
            try (RunFeedSource.Page snake = source.first(new RunFeedQuery("", Set.of(), "Snake Pit"), new Cancellation())) {
                assertEquals(300, snake.matches());
                assertTrue(snake.model().cards().stream().allMatch(c -> c.map().equals("Snake Pit")));
            }
            try (RunFeedSource.Page text = source.first(new RunFeedQuery("pirate", Set.of(RunOutcome.LEFT, RunOutcome.COMPLETED), null), new Cancellation())) {
                assertEquals("Pirate Cave runs are even, so all completed", 300, text.matches());
                assertTrue(text.model().cards().stream().allMatch(c -> c.map().equals("Pirate Cave")));
            }
            try (RunFeedSource.Page left = source.first(new RunFeedQuery("", Set.of(RunOutcome.LEFT), "Pirate Cave"), new Cancellation())) {
                assertEquals(0, left.matches());
                assertTrue(left.model().days().isEmpty()); assertFalse(left.model().more());
            }
        }
    }

    @Test public void theQueryConvertsToTheRunsArchiveFacetsOverAllSessionsNewestFirst() {
        RunFeedQuery query = new RunFeedQuery(" Lost ", EnumSet.of(RunOutcome.APP_ENDED, RunOutcome.COMPLETED), " ");
        ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> archive = query.archiveQuery();
        assertEquals(SessionStore.ALL, archive.scope());
        assertEquals(" Lost ", archive.text());
        assertEquals(1, archive.order().size());
        assertEquals(ActivityQueries.Sort.TIME, archive.order().get(0).field);
        assertEquals(ArchiveQuery.Direction.DESCENDING, archive.order().get(0).direction);
        // App ended has no archive outcome: its archive readings (left, or unfinished) stay in, and the exact rule filters after.
        assertEquals(EnumSet.of(ActivityQueries.Outcome.COMPLETED, ActivityQueries.Outcome.LEFT, ActivityQueries.Outcome.IN_PROGRESS,
            ActivityQueries.Outcome.UNKNOWN), archive.facets().outcomes);
        assertNull("A blank dungeon is all dungeons", query.map());
        assertTrue(query.matches(RunOutcome.APP_ENDED, "Snake Pit"));
        assertFalse(query.matches(RunOutcome.LEFT, "Snake Pit"));
        assertEquals(EnumSet.of(ActivityQueries.Outcome.LEFT), new RunFeedQuery(null, Set.of(RunOutcome.LEFT), null).facets().outcomes);
        assertTrue("Empty is every outcome", RunFeedQuery.all().facets().outcomes.isEmpty());
        assertEquals("", RunFeedQuery.all().archiveQuery().text());
        RunFeedQuery map = new RunFeedQuery(null, null, "Snake Pit");
        assertTrue(map.matches(RunOutcome.UNKNOWN, "Snake Pit")); assertFalse(map.matches(RunOutcome.UNKNOWN, "Snake Pit 2"));
        assertEquals(Set.of(), map.outcomes()); assertEquals("", map.text());
    }

    /**
     * P5b: the dungeon filter is a canonical dungeon (a Dungeons card's key, {@code DungeonStatData.Snapshot.canonicalName} of
     * each run's saved area name), so a raw alias and its canonical name are one dungeon and "Show runs" lists every run the card
     * counted.
     */
    @Test public void theDungeonFilterMatchesEachRunByItsCanonicalDungeonAsTheDungeonsCardsCount() throws Exception {
        assertEquals(RunFixtures.CRONUS, tomato.backend.data.DungeonStatData.Snapshot.canonicalName(RunFixtures.CRONUS_ALIAS));
        RunFeedQuery cronus = new RunFeedQuery("", Set.of(), RunFixtures.CRONUS);
        assertTrue("A run saved under the raw alias is that dungeon's", cronus.matches(RunOutcome.COMPLETED, RunFixtures.CRONUS_ALIAS));
        assertTrue(cronus.matches(RunOutcome.LEFT, RunFixtures.CRONUS));
        assertFalse(cronus.matches(RunOutcome.LEFT, "Lost Halls"));
        assertFalse("A run without an area name is no dungeon's", cronus.matches(RunOutcome.LEFT, null));
        Path root = temp.newFolder("mixed").toPath();
        RunFixtures.writeMixed(root);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            try (RunFeedSource.Page page = source.first(cronus, new Cancellation())) {
                assertEquals("c5 (saved under the alias) and d3 under one filter, newest first", List.of(RunFixtures.C5, RunFixtures.D3), refs(page.model()));
            }
            DungeonsModel cards = new DungeonsSource(store, ZONE, () -> RunFixtures.NOW).read(DungeonsQuery.all(), new Cancellation());
            assertFalse(cards.cards().isEmpty());
            for (DungeonCardModel card : cards.cards())
                try (RunFeedSource.Page page = source.first(new RunFeedQuery("", Set.of(), card.canonical()), new Cancellation())) {
                    assertEquals("The feed lists every run of the " + card.canonical() + " card", card.visits(), page.matches());
                    assertEquals(card.visits(), page.model().cards().size());
                }
        }
    }

    @Test public void lootFameAndCombatJoinOnlyByExactVisitRef() throws Exception {
        Path root = scenario();
        try (SessionStore store = new SessionStore(root, false, "fixture");
             RunFeedSource.Page page = source(store, RunFixtures.NOW).first(RunFeedQuery.all(), new Cancellation())) {
            RunFeedModel model = page.model();
            assertEquals("Dungeon runs only, newest first", List.of(RunFixtures.B4, RunFixtures.B1, RunFixtures.A3, RunFixtures.A2, RunFixtures.A1), refs(model));
            assertEquals(List.of("Today · 5 runs · 2 completed · 1 h 40 m"), model.days().stream().map(RunFeedModel.Day::header).collect(Collectors.toList()));

            RunCardModel a1 = card(model, RunFixtures.A1);
            assertEquals(RunOutcome.COMPLETED, a1.outcome()); assertEquals("Lost Halls", a1.mapName()); assertEquals(at(0, 8, 5), a1.entered());
            assertEquals(Long.valueOf(25 * MINUTE), a1.durationMs()); assertEquals(Integer.valueOf(6), a1.partySize()); assertEquals(Integer.valueOf(2), a1.exaltProgress());
            assertEquals(List.of(502, 506, 504, 501, 505, 503), a1.loot().stream().map(RunCardModel.LootItem::id).collect(Collectors.toList()));
            assertEquals(List.of("White", "Orange", "Orange", "White", "Orange", "Orange"), a1.loot().stream().map(RunCardModel.LootItem::bag).collect(Collectors.toList()));
            assertEquals(6, a1.lootCount()); assertEquals("1 UT · 1 ST · 2 potions", a1.lootSummary());
            assertEquals("10,000 → 10,100 → 10,240 inside one capture interval", Long.valueOf(240), a1.fameGained());
            RunCardModel.Combat combat = a1.combat();
            assertEquals("The longest of its two recordings", "r-v1-long", combat.recordingId()); assertEquals(2, combat.recordings());
            assertEquals(Integer.valueOf(2), combat.rank()); assertEquals(6, combat.contributors()); assertEquals(30.0, combat.share(), 1e-9);
            assertEquals(20.0, combat.localDps(), 1e-9); assertEquals(Long.valueOf(6_000), combat.localDamage()); assertEquals(Integer.valueOf(1), combat.localDeaths());
            assertNull(a1.combatReason());

            RunCardModel a2 = card(model, RunFixtures.A2);
            assertEquals(RunOutcome.LEFT, a2.outcome()); assertEquals("1 UT", a2.lootSummary());
            assertEquals("Every reading tagged with it has a predecessor: a known zero", Long.valueOf(0), a2.fameGained());
            assertNotNull(a2.combat()); assertNull(a2.combat().rank()); assertNull(a2.combat().localDps());
            assertEquals(RunCardModel.UNVERIFIED_LOCAL, a2.combatReason());
            assertNull(a2.partySize()); assertNull(a2.exaltProgress());

            RunCardModel a3 = card(model, RunFixtures.A3);
            assertEquals("Its session saved its end without closing it", RunOutcome.APP_ENDED, a3.outcome());
            assertEquals("No drop recorded inside it (the unlinked bag is not guessed)", 0, a3.lootCount());
            assertNull(a3.fameGained()); assertNull(a3.combat()); assertEquals(RunCardModel.NO_RECORDING, a3.combatReason());

            RunCardModel b1 = card(model, RunFixtures.B1);
            assertEquals(RunOutcome.COMPLETED, b1.outcome()); assertEquals("Ice Citadel", b1.map());
            assertEquals("A's v1 loot, fame and recording are never B's v1", 0, b1.lootCount());
            assertEquals("", b1.lootSummary()); assertNull(b1.fameGained()); assertNull(b1.combat());
            assertEquals(RunCardModel.NO_RECORDING, b1.combatReason());
            assertEquals("A session that never saved its end (a crash): its open run ended with the app", RunOutcome.APP_ENDED,
                card(model, RunFixtures.B4).outcome());
            assertEquals("…and A's v1 is not joined with anything B saved", "r-v1-long", a1.combat().recordingId());
        }
    }

    @Test public void anUnreadableSessionIsLeftOutAndNamedSoTheFeedCanSayItIsPartial() throws Exception {
        Path root = scenario();
        String broken = id("runs-broken");
        HomeHistoryFixture.session(root, broken, at(0, 10, 30), at(0, 10, 50));
        HomeHistoryFixture.runs(root, broken, HomeHistoryFixture.visit("x1", "Lost Halls", at(0, 10, 35), at(0, 10, 45), true));
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals(6, page.matches());
                assertEquals("Every saved session was read", List.of(), page.issues());
            }
            Files.writeString(root.resolve(broken).resolve("session.json"), "{broken");
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals("The other sessions' runs still show", List.of(RunFixtures.B4, RunFixtures.B1, RunFixtures.A3, RunFixtures.A2, RunFixtures.A1),
                    refs(page.model()));
                assertEquals(1, page.issues().size());
                assertTrue(page.issues().get(0), page.issues().get(0).startsWith(broken + ": "));
                try (RunFeedSource.Page same = source.more(page, new Cancellation())) { assertEquals(page.issues(), same.issues()); }
            }
        }
    }

    @Test public void aSessionWhoseLootCannotBeReadKeepsTheFeedAndSaysSoOnItsCards() throws Exception {
        Path root = scenario(), loot = root.resolve(RunFixtures.A).resolve("loot.jsonl");
        Files.writeString(loot, "{broken\n" + Files.readString(loot));   // not the final line: the journal is damaged
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            for (int read = 0; read < 2; read++) try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals("The page loads (read " + read + "; the second from kept facts)", 5, page.model().cards().size());
                for (VisitRef ref : List.of(RunFixtures.A1, RunFixtures.A2, RunFixtures.A3)) {
                    RunCardModel card = card(page.model(), ref);
                    assertEquals(RunCardModel.LOOT_UNREADABLE, card.lootReason());
                    assertEquals(List.of(), card.loot()); assertEquals(0, card.lootCount()); assertEquals("", card.lootSummary());
                }
                RunCardModel a1 = card(page.model(), RunFixtures.A1);
                assertEquals("The session's other facts still show", Long.valueOf(240), a1.fameGained());
                assertEquals("r-v1-long", a1.combat().recordingId());
                RunCardModel b1 = card(page.model(), RunFixtures.B1);
                assertNull("B's loot was read: no bag inside its run is a known none", b1.lootReason()); assertEquals(0, b1.lootCount());
                assertEquals(1, page.issues().size());
                assertTrue(page.issues().get(0), page.issues().get(0).startsWith(RunFixtures.A + ": loot could not be read"));
            }
        }
    }

    @Test public void runsOfASessionWithoutAnySavedLootHaveUnknownLoot() throws Exception {
        Path root = scenario();
        Files.delete(root.resolve(RunFixtures.B).resolve("loot.jsonl"));   // B saved no loot bag at all
        try (SessionStore store = new SessionStore(root, false, "fixture");
             RunFeedSource.Page page = source(store, RunFixtures.NOW).first(RunFeedQuery.all(), new Cancellation())) {
            for (VisitRef ref : List.of(RunFixtures.B1, RunFixtures.B4)) {
                RunCardModel card = card(page.model(), ref);
                assertEquals(RunCardModel.LOOT_NOT_SAVED, card.lootReason());
                assertEquals(List.of(), card.loot()); assertEquals(0, card.lootCount()); assertEquals("", card.lootSummary());
            }
            RunCardModel a3 = card(page.model(), RunFixtures.A3);
            assertNull("A saved bags (none inside v3): a known none", a3.lootReason()); assertEquals(0, a3.lootCount());
            assertEquals(6, card(page.model(), RunFixtures.A1).lootCount());
            assertEquals("Nothing failed to read", List.of(), page.issues());
        }
    }

    @Test public void aSessionWhoseFameCannotBeReadShowsFameAsUnknown() throws Exception {
        Path root = scenario(), fame = root.resolve(RunFixtures.A).resolve("fame.jsonl");
        Files.writeString(fame, "{broken\n" + Files.readString(fame));
        try (SessionStore store = new SessionStore(root, false, "fixture");
             RunFeedSource.Page page = source(store, RunFixtures.NOW).first(RunFeedQuery.all(), new Cancellation())) {
            RunCardModel a1 = card(page.model(), RunFixtures.A1), a2 = card(page.model(), RunFixtures.A2);
            assertNull("Unknown, not the +240 it would be", a1.fameGained());
            assertNull("Unknown, not the known zero it would be", a2.fameGained());
            assertEquals("Loot and combat still show", 6, a1.lootCount()); assertNull(a1.lootReason());
            assertEquals("r-v1-long", a1.combat().recordingId());
            assertEquals(1, page.issues().size());
            assertTrue(page.issues().get(0), page.issues().get(0).startsWith(RunFixtures.A + ": fame could not be read"));
        }
    }

    @Test public void aSessionWhoseCombatRecordsCannotBeReadSaysSoInsteadOfNoRecording() throws Exception {
        Path root = scenario();
        CombatFixtures.write(root, RunFixtures.A, "encounters", "damaged", "{broken");   // one damaged record: skipped, not a failure
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals("r-v1-long", card(page.model(), RunFixtures.A1).combat().recordingId());
                assertEquals(List.of(), page.issues());
            }
            RunFeedSource failing = source(store, RunFixtures.NOW);
            failing.combatReader((s, catalog, scope, sink) -> {
                if (scope.equals(RunFixtures.A)) throw new java.io.IOException("synthetic listing failure");
                CombatFacts.read(s, catalog, scope, sink);
            });
            try (RunFeedSource.Page page = failing.first(RunFeedQuery.all(), new Cancellation())) {
                for (VisitRef ref : List.of(RunFixtures.A1, RunFixtures.A2, RunFixtures.A3)) {
                    RunCardModel card = card(page.model(), ref);
                    assertNull(card.combat()); assertEquals(RunCardModel.COMBAT_UNREADABLE, card.combatReason());
                }
                RunCardModel a1 = card(page.model(), RunFixtures.A1);
                assertEquals("Loot and fame still show", 6, a1.lootCount()); assertEquals(Long.valueOf(240), a1.fameGained());
                assertEquals("B's records were read: none is linked", RunCardModel.NO_RECORDING, card(page.model(), RunFixtures.B1).combatReason());
                assertEquals(1, page.issues().size());
                assertTrue(page.issues().get(0), page.issues().get(0).startsWith(RunFixtures.A + ": encounters could not be read"));
            }
        }
    }

    @Test public void theCurrentSessionsOpenRunIsInProgressAndItsFactsAreReadAgainEveryTime() throws Exception {
        Path root = scenario();
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            VisitRef live = new VisitRef(store.currentId(), "live");
            ActivityJournal.Visit visit = HomeHistoryFixture.visit("live", "Lost Halls", store.started() + 1_000, 0, false);
            store.put("runs", visit.id, visit);
            store.append("loot", LootTestDrops.drop("White", "Lost Halls", store.started() + 2_000, live, item(901, UT)));
            store.flush();
            RunFeedSource source = source(store, store.started() + HOUR);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                RunCardModel card = card(page.model(), live);
                assertEquals(RunOutcome.IN_PROGRESS, card.outcome()); assertEquals(1, card.lootCount());
            }
            store.append("loot", LootTestDrops.drop("Orange", "Lost Halls", store.started() + 3_000, live, item(902, POTION)));
            store.flush();
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals("The current session is never kept", 2, card(page.model(), live).lootCount());
            }
        }
    }

    @Test public void closedSessionsFactsAreReusedUntilTheirFilesChange() throws Exception {
        Path root = scenario(), loot = root.resolve(RunFixtures.A).resolve("loot.jsonl");
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals(502, card(page.model(), RunFixtures.A1).loot().get(0).id());
            }
            assertEquals("Only the loaded runs' sessions are read and kept", 2, source.cachedSessions());
            FileTime written = Files.getLastModifiedTime(loot);
            String text = Files.readString(loot);
            assertTrue(text.contains("\"id\":502,"));
            Files.writeString(loot, text.replace("\"id\":502,", "\"id\":592,"));   // same size
            Files.setLastModifiedTime(loot, written);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals("Same name, size and time: the kept facts are used", 502, card(page.model(), RunFixtures.A1).loot().get(0).id());
            }
            Files.setLastModifiedTime(loot, FileTime.fromMillis(written.toMillis() + 2_000));
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals("A changed stamp reads the session again", 592, card(page.model(), RunFixtures.A1).loot().get(0).id());
            }
        }
    }

    @Test public void onlyTheSessionsOfTheLoadedRunsAreRead() throws Exception {
        Path root = large();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, NOW);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals("50 newest runs span two sessions", 2, source.cachedSessions());
                try (RunFeedSource.Page next = source.more(page, new Cancellation())) {
                    assertEquals(100, next.model().cards().size());
                    assertEquals(3, source.cachedSessions());
                }
            }
        }
    }

    @Test public void readsRefuseTheEventDispatchThreadAndClosingIsIdempotent() throws Exception {
        Path root = scenario();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = new RunFeedSource(store, ZONE, () -> RunFixtures.NOW);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try { source.first(RunFeedQuery.all(), new Cancellation()); } catch (Throwable failure) { thrown.set(failure); }
            });
            assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof IllegalStateException);
            RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation());
            page.close(); page.close();   // idempotent
            try { source.more(page, new Cancellation()); fail("Closed pages cannot be extended"); }
            catch (IllegalStateException expected) { }
            Cancellation cancelled = new Cancellation(); cancelled.cancel();
            try { source.first(RunFeedQuery.all(), cancelled); fail("A cancelled read stops"); }
            catch (java.util.concurrent.CancellationException expected) { }
        }
    }

    @Test public void closedSessionsRunsAreReusedUntilTheirFilesChange() throws Exception {
        Path root = scenario();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            source.first(RunFeedQuery.all(), new Cancellation()).close();
            int cold = source.sessionReads();
            source.first(new RunFeedQuery("Snake", Set.of(), null), new Cancellation()).close();
            assertEquals("Only the current session is re-read", cold + 1, source.sessionReads());
            HomeHistoryFixture.runs(root, RunFixtures.A, HomeHistoryFixture.visit("new", "Lost Halls", at(0, 9, 30), at(0, 9, 50), true));
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals(cold + 3, source.sessionReads());
                assertEquals(6, page.matches());
            }
            Path file = root.resolve(RunFixtures.A).resolve("runs").resolve(SessionStore.checkpointName("new") + ".json");
            FileTime written = Files.getLastModifiedTime(file);
            ActivityJournal.Visit changed = HomeHistoryFixture.visit("new", "Lost Halls", at(0, 9, 30), at(0, 9, 50), true);
            changed.rosterSize = 9;
            HomeHistoryFixture.runs(root, RunFixtures.A, changed);
            Files.setLastModifiedTime(file, FileTime.fromMillis(written.toMillis() + 2_000));
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals(cold + 5, source.sessionReads());
                assertEquals(Integer.valueOf(9), card(page.model(), new VisitRef(RunFixtures.A, "new")).partySize());
            }
        }
    }

    @Test public void pagesKeepTheirMatchingRowsWhenAClosedSessionSavesAnotherRun() throws Exception {
        Path root = large();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, NOW);
            try (RunFeedSource.Page first = source.first(RunFeedQuery.all(), new Cancellation());
                 RunFeedSource.Page before = source.more(first, new Cancellation())) {
                VisitRef added = new VisitRef(id("large-0"), "newest");
                HomeHistoryFixture.runs(root, added.sessionId, HomeHistoryFixture.visit(added.visitId, "Lost Halls", NOW - 1_000, NOW, true));
                try (RunFeedSource.Page refreshed = source.first(RunFeedQuery.all(), new Cancellation());
                     RunFeedSource.Page after = source.more(first, new Cancellation())) {
                    assertEquals(first.matches() + 1, refreshed.matches());
                    assertEquals(added, refs(refreshed.model()).get(0));
                    assertEquals(before.matches(), after.matches());
                    assertEquals(refs(before.model()), refs(after.model()));
                    assertEquals(100, new HashSet<>(refs(after.model())).size());
                    first.close();
                    try (RunFeedSource.Page next = source.more(after, new Cancellation())) { assertEquals(150, next.model().cards().size()); }
                }
            }
        }
    }

    @Test public void unfinishedJournalTailsAreExcludedEvenWhenTheyAreValidJson() throws Exception {
        Path root = scenario(), journal = root.resolve(RunFixtures.A).resolve("runs.jsonl");
        String visit = SessionStore.JSON.toJson(HomeHistoryFixture.visit("journal", "Lost Halls", at(0, 9, 30), at(0, 9, 50), true));
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            for (String tail : List.of("{unfinished", visit)) {
                Files.writeString(journal, visit + "\n" + tail);
                try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                    assertEquals(6, page.matches());
                    assertEquals(List.of(RunFixtures.A + "/runs: unfinished journal tail excluded"), page.issues());
                }
            }
            Files.writeString(journal, visit + "\n");
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals(6, page.matches()); assertTrue(page.issues().isEmpty());
            }
        }
    }

    @Test public void aDamagedCompleteJournalRecordFailsAndIsNotCached() throws Exception {
        Path root = scenario(), journal = root.resolve(RunFixtures.A).resolve("runs.jsonl");
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            for (String text : List.of("{damaged\n", "{damaged\n{}\n")) {
                Files.writeString(journal, text);
                for (int attempt = 0; attempt < 2; attempt++) {
                    int reads = source.sessionReads();
                    try { source.first(RunFeedQuery.all(), new Cancellation()); fail("Damaged complete records fail the read"); }
                    catch (java.io.IOException expected) { assertTrue(source.sessionReads() > reads); }
                }
            }
            Files.writeString(journal, "");
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) { assertEquals(5, page.matches()); }
        }
    }

    @Test public void truncatedUtf8InAnUnfinishedJournalTailIsExcluded() throws Exception {
        Path root = scenario(), journal = root.resolve(RunFixtures.A).resolve("runs.jsonl");
        String visit = SessionStore.JSON.toJson(HomeHistoryFixture.visit("journal", "Lost Halls", at(0, 9, 30), at(0, 9, 50), true));
        Files.writeString(journal, visit + "\n{\"id\":\"unfinished");
        Files.write(journal, new byte[] {(byte) 0xe2, (byte) 0x82}, java.nio.file.StandardOpenOption.APPEND);
        try (SessionStore store = new SessionStore(root, false, "fixture");
             RunFeedSource.Page page = source(store, RunFixtures.NOW).first(RunFeedQuery.all(), new Cancellation())) {
            assertEquals(6, page.matches());
            assertEquals(List.of(RunFixtures.A + "/runs: unfinished journal tail excluded"), page.issues());
        }
    }

    @Test public void anOversizedCompleteJournalLineFailsWhileAnOversizedTailIsOnlyExcluded() throws Exception {
        Path root = scenario(), journal = root.resolve(RunFixtures.A).resolve("runs.jsonl");
        String visit = SessionStore.JSON.toJson(HomeHistoryFixture.visit("journal", "Lost Halls", at(0, 9, 30), at(0, 9, 50), true));
        String huge = "x".repeat(16 * 1024 * 1024 + 1);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            Files.writeString(journal, visit + "\n" + huge);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals(6, page.matches());
                assertEquals(List.of(RunFixtures.A + "/runs: unfinished journal tail excluded"), page.issues());
            }
            Files.writeString(journal, visit + "\n" + huge + "\n");
            try { source.first(RunFeedQuery.all(), new Cancellation()); fail("An oversized record must fail the read"); }
            catch (java.io.IOException expected) { assertTrue(expected.toString(), expected.getMessage().contains("16 MiB")); }
        }
    }

    @Test public void journalGrowthDuringReadOnlyEmitsTheInitialCompletePrefix() throws Exception {
        Path root = scenario(), journal = root.resolve(RunFixtures.A).resolve("runs.jsonl");
        String visit = SessionStore.JSON.toJson(HomeHistoryFixture.visit("journal", "Lost Halls", at(0, 9, 30), at(0, 9, 50), true));
        // Exceed the reader's buffer so growth occurs before the entire prefix has been consumed.
        String prefix = (visit + "\n").repeat(120);
        Files.writeString(journal, prefix + visit);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            Cancellation mutation = duringJournal(store, source, () ->
                Files.writeString(journal, "\n" + visit + "\n{damaged\n", java.nio.file.StandardOpenOption.APPEND));
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), mutation)) {
                assertEquals("Neither the completed tail nor later records enter the result", 125, page.matches());
                assertEquals(List.of(RunFixtures.A + "/runs: unfinished journal tail excluded"), page.issues());
            }
            assertTrue(Files.size(journal) > prefix.length() + visit.length());
        }
    }

    /**
     * A journal that shrinks while it is read fails the read. A same-name replacement that only grows is not detectable on
     * Windows (no file key, and a recreated name keeps its creation time), as the archive pin could not detect it either.
     */
    @Test public void journalShrinkageDuringReadFails() throws Exception {
        Path root = scenario(), journal = root.resolve(RunFixtures.A).resolve("runs.jsonl");
        Files.writeString(journal, SessionStore.JSON.toJson(HomeHistoryFixture.visit("journal", "Lost Halls", at(0, 9, 30), at(0, 9, 50), true)) + "\n");
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            Cancellation mutation = duringJournal(store, source, () -> Files.writeString(journal, ""));
            try { source.first(RunFeedQuery.all(), mutation); fail("A shrunk journal must fail"); }
            catch (java.io.IOException expected) { assertTrue(expected.toString(), expected.getMessage().contains("changed during read")); }
        }
    }

    @FunctionalInterface private interface JournalMutation { void run() throws java.io.IOException; }

    /** The first cancellation check after this session's read counter advances is inside its journal reader. */
    private static Cancellation duringJournal(SessionStore store, RunFeedSource source, JournalMutation mutation) throws Exception {
        List<SessionStore.SessionEntry> catalog = store.catalog();
        int target = java.util.stream.IntStream.range(0, catalog.size())
            .filter(i -> catalog.get(i).id.equals(RunFixtures.A)).findFirst().orElseThrow() + 1;
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        return new Cancellation(() -> {
            if (source.sessionReads() == target && changed.compareAndSet(false, true)) {
                try { mutation.run(); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
            return false;
        });
    }

    @Test public void journalAndCheckpointVisitsRemainDuplicatesWithTheSameEndedSessionFixup() throws Exception {
        Path root = scenario();
        ActivityJournal.Visit visit = HomeHistoryFixture.visit("duplicate", "Lost Halls", at(0, 9, 30), 0, false);
        visit.lastSeen = at(0, 9, 40);
        visit.rosterSize = 99;
        HomeHistoryFixture.runs(root, RunFixtures.A, visit);
        StringBuilder journal = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            visit.rosterSize = i;
            journal.append(SessionStore.JSON.toJson(visit)).append('\n');
        }
        Files.writeString(root.resolve(RunFixtures.A).resolve("runs.jsonl"), journal);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                List<RunCardModel> duplicates = page.model().cards().stream()
                    .filter(c -> c.ref().equals(new VisitRef(RunFixtures.A, "duplicate"))).collect(Collectors.toList());
                assertEquals(18, page.matches()); assertEquals(13, duplicates.size());
                assertEquals("Checkpoint sorts before journal copies", Integer.valueOf(99), duplicates.get(0).partySize());
                for (int i = 0; i < 12; i++) assertEquals("Journal ordinals sort numerically", Integer.valueOf(i), duplicates.get(i + 1).partySize());
                for (RunCardModel card : duplicates) {
                    assertEquals(RunOutcome.APP_ENDED, card.outcome()); assertEquals(Long.valueOf(10 * 60_000), card.durationMs());
                }
            }
        }
    }

    @Test public void cancellingDuringASessionsProjectionDoesNotKeepIt() throws Exception {
        Path root = scenario();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunFeedSource source = source(store, RunFixtures.NOW);
            Cancellation cancel = new Cancellation(() -> source.sessionReads() > 0);
            try { source.first(RunFeedQuery.all(), cancel); fail("Cancellation stops the session read"); }
            catch (java.util.concurrent.CancellationException expected) { }
            assertEquals(1, source.sessionReads());
            try (RunFeedSource.Page page = source.first(RunFeedQuery.all(), new Cancellation())) {
                assertEquals(5, page.matches());
                assertEquals("Both saved sessions and the current session must still be read", 4, source.sessionReads());
            }
        }
    }
}
