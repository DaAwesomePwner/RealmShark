package tomato.gui.runs;

import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.Test;
import tomato.gui.dps.EncounterLink;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.stats.LootFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;

/** The feed's day groups and headers, and the card rules (combat line, loot strip, exalt progress). Pure; synthetic data. */
public class RunFeedModelTest {
    private static final String SESSION = id("feed-model");

    private static RunCardModel card(String visit, long entered, Long duration, RunOutcome outcome) {
        return RunCardModel.of(new VisitRef(SESSION, visit), "Lost Halls", outcome, entered, duration, null, 0, List.of(), List.of(), null);
    }
    private static List<String> visits(RunFeedModel.Day day) { return day.cards().stream().map(c -> c.ref().visitId).collect(Collectors.toList()); }
    private static LootFacts.Item ut(int id) { return new LootFacts.Item(id, true, false, false, false); }
    private static LootFacts.Item st(int id) { return new LootFacts.Item(id, false, true, false, false); }
    private static LootFacts.Item high(int id) { return new LootFacts.Item(id, false, false, true, false); }
    private static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }
    private static LootFacts.Item plain(int id) { return new LootFacts.Item(id, false, false, false, false); }
    private static LootFacts.Bag bag(String name, VisitRef visit, LootFacts.Item... items) {
        return new LootFacts.Bag(SESSION, NOW, "White".equals(name), name, visit, List.of(items));
    }

    @Test public void daysGroupByTheLocalDateInTheZoneAcrossMidnight() {
        // NOW is 10:00 on DAY in New York (UTC-5). 23:55 the evening before is Yesterday there, but already DAY in UTC.
        List<RunCardModel> cards = List.of(card("t1", at(0, 0, 5), 60_000L, RunOutcome.IN_PROGRESS), card("y1", at(-1, 23, 55), 60_000L, RunOutcome.COMPLETED),
            card("y2", at(-1, 18, 0), 60_000L, RunOutcome.LEFT), card("m1", at(-2, 12, 0), 60_000L, RunOutcome.COMPLETED),
            card("old", at(-400, 12, 0), 60_000L, RunOutcome.COMPLETED));
        RunFeedModel model = RunFeedModel.of(cards, false, null, ZONE, NOW);
        assertEquals(List.of(DAY, DAY.minusDays(1), DAY.minusDays(2), DAY.minusDays(400)),
            model.days().stream().map(RunFeedModel.Day::date).collect(Collectors.toList()));
        assertEquals(List.of("Today", "Yesterday", "Monday 13 January", "Tuesday 12 December 2023"),
            model.days().stream().map(RunFeedModel.Day::title).collect(Collectors.toList()));
        assertEquals(List.of("t1"), visits(model.days().get(0)));
        assertEquals("Newest first inside a day", List.of("y1", "y2"), visits(model.days().get(1)));
        assertEquals("Every loaded card once, in order", List.of("t1", "y1", "y2", "m1", "old"),
            model.cards().stream().map(c -> c.ref().visitId).collect(Collectors.toList()));
        assertEquals(NOW, model.capturedAt());
        assertFalse(model.more());

        RunFeedModel utc = RunFeedModel.of(cards, false, null, ZoneOffset.UTC, NOW);
        assertEquals("In UTC, 23:55 New York time is already the next day", List.of("t1", "y1"), visits(utc.days().get(0)));
    }

    @Test public void headersCountTheLoadedRunsTheCompletedOnesAndTheObservedTime() {
        List<RunCardModel> today = new ArrayList<>();
        long[] minutes = {12, 20, 15, 10, 15};   // 72 minutes observed
        for (int i = 0; i < minutes.length; i++)
            today.add(card("t" + i, NOW - i * 1_000L, minutes[i] * MINUTE, i == 0 ? RunOutcome.IN_PROGRESS : RunOutcome.COMPLETED));
        today.add(card("unknown", NOW - 10_000L, null, RunOutcome.APP_ENDED));   // no observed span: adds nothing
        RunFeedModel.Day day = RunFeedModel.of(today, false, null, ZONE, NOW).days().get(0);
        assertEquals(6, day.cards().size()); assertEquals(4, day.completed()); assertEquals(72 * MINUTE, day.durationMs());
        assertEquals("Today · 6 runs · 4 completed · 1 h 12 m", day.header());

        RunFeedModel.Day one = RunFeedModel.of(List.of(card("y", at(-1, 20, 0), 20 * MINUTE, RunOutcome.LEFT)), false, null, ZONE, NOW).days().get(0);
        assertEquals("Yesterday · 1 run · 0 completed · 20 m", one.header());
        RunFeedModel.Day unknown = RunFeedModel.of(List.of(card("y", at(-1, 20, 0), null, RunOutcome.LEFT)), false, null, ZONE, NOW).days().get(0);
        assertEquals("No observed span: no time claimed", "Yesterday · 1 run · 0 completed", unknown.header());
        RunFeedModel.Day hours = RunFeedModel.of(List.of(card("y", at(-1, 20, 0), 2 * HOUR, RunOutcome.COMPLETED)), false, null, ZONE, NOW).days().get(0);
        assertEquals("Yesterday · 1 run · 1 completed · 2 h", hours.header());
        assertTrue(RunFeedModel.of(List.of(), false, null, ZONE, NOW).days().isEmpty());
    }

    @Test public void theLastLoadedDaySaysMoreBelowOnlyWhenTheNextUnloadedRunIsOnThatDay() {
        List<RunCardModel> cards = List.of(card("t", at(0, 0, 5), MINUTE, RunOutcome.COMPLETED), card("y", at(-1, 22, 0), MINUTE, RunOutcome.COMPLETED));
        RunFeedModel continues = RunFeedModel.of(cards, true, DAY.minusDays(1), ZONE, NOW);
        assertTrue(continues.more());
        assertFalse(continues.days().get(0).continues());
        assertTrue(continues.days().get(1).continues());
        assertEquals("Yesterday · 1 run · 1 completed · 1 m · more below", continues.days().get(1).header());
        RunFeedModel nextDay = RunFeedModel.of(cards, true, DAY.minusDays(2), ZONE, NOW);
        assertTrue("More runs, but on an earlier day", nextDay.more());
        assertFalse(nextDay.days().get(1).continues());
        assertFalse("Nothing left to load", RunFeedModel.of(cards, false, DAY.minusDays(1), ZONE, NOW).days().get(1).continues());
    }

    @Test public void theCombatLineUsesTheLongestRecordingAndOnlyTheVerifiedLocalRow() {
        VisitRef ref = new VisitRef(SESSION, "v1");
        CombatRecord longest = RunFixtures.record("long", ref, 2, 300, 9_000, 6_000, 3_000, 1_500, 400, 100);
        longest.players.get(1).deaths = 1;
        RunCardModel card = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, 6, 0,
            List.of(RunFixtures.record("short", ref, 2, 30, 900, 500), longest), List.of(), null);
        RunCardModel.Combat combat = card.combat();
        assertEquals("long", combat.recordingId()); assertEquals(2, combat.recordings());
        assertEquals(Long.valueOf(6_000), combat.localDamage()); assertEquals(20.0, combat.localDps(), 1e-9);
        assertEquals("#2 of 6", Integer.valueOf(2), combat.rank()); assertEquals(6, combat.contributors());
        assertEquals(30.0, combat.share(), 1e-9); assertEquals(Integer.valueOf(1), combat.localDeaths());
        assertNull(combat.localUnavailable()); assertNull(card.combatReason());
        assertEquals(Integer.valueOf(6), card.partySize());

        // Verified, but the local player recorded no damage: a real zero, not a rank.
        CombatRecord silent = RunFixtures.record("silent", ref, 9, 100, 4_000, 1_000);
        RunCardModel.Combat none = RunCardModel.of(ref, "Lost Halls", RunOutcome.LEFT, NOW, MINUTE, null, 0, List.of(silent), List.of(), null).combat();
        assertEquals(Long.valueOf(0), none.localDamage()); assertEquals(0.0, none.localDps(), 0); assertEquals(0.0, none.share(), 0);
        assertNull(none.rank()); assertNull("No row: deaths unknown", none.localDeaths()); assertNull(none.localUnavailable());
    }

    @Test public void anUnverifiedLocalRowLeavesYourFactsUnknownWithTheMetersWording() {
        VisitRef ref = new VisitRef(SESSION, "v2");
        RunCardModel card = RunCardModel.of(ref, "Snake Pit", RunOutcome.LEFT, NOW, MINUTE, null, 0,
            List.of(RunFixtures.record("r", ref, null, 120, 4_000, 3_000, 2_000, 1_000)), List.of(), null);
        RunCardModel.Combat combat = card.combat();
        assertNotNull("The recording is linked", combat);
        assertNull(combat.localDamage()); assertNull(combat.localDps()); assertNull(combat.rank()); assertNull(combat.share());
        assertNull(combat.localDeaths()); assertEquals(4, combat.contributors()); assertEquals(1, combat.recordings());
        RecordedEncounter meter = new RecordedEncounter("r", "Snake Pit", null, 60_000L, EncounterLink.live(new EncounterContext(ref, null, 1L)), 0L, 1.0);
        assertEquals("The meter's own sentence", meter.unavailableReason(), RunCardModel.UNVERIFIED_LOCAL);
        assertEquals(RunCardModel.UNVERIFIED_LOCAL, combat.localUnavailable());
        assertEquals(RunCardModel.UNVERIFIED_LOCAL, card.combatReason());
    }

    @Test public void withoutALinkedRecordingTheCardSaysSoAndNeverUsesAnotherRunsRecording() {
        VisitRef ref = new VisitRef(SESSION, "v3");
        RunCardModel card = RunCardModel.of(ref, "Pirate Cave", RunOutcome.APP_ENDED, NOW, null, null, 0,
            List.of(RunFixtures.record("other", new VisitRef(SESSION, "v1"), 1, 60, 500), RunFixtures.record("legacy", null, 1, 60, 500)),
            List.of(), null);
        assertNull(card.combat());
        assertEquals("No combat recording is linked to this run.", card.combatReason());
        assertNull(card.durationMs()); assertNull(card.fameGained()); assertNull(card.partySize()); assertNull(card.exaltProgress());
        assertEquals(List.of(), card.loot()); assertEquals(0, card.lootCount()); assertEquals("", card.lootSummary());
        assertEquals(RunOutcome.APP_ENDED, card.outcome());
        assertEquals("Pirate Cave", card.map()); assertEquals("Pirate Cave", card.mapName());
    }

    @Test public void lootKeepsHomesNotabilityOrderWithBagNamesAndSummarizesEveryExactItem() {
        VisitRef ref = new VisitRef(SESSION, "v1");
        List<LootFacts.Bag> bags = List.of(bag("White", ref, potion(1), ut(2)), bag("Orange", ref, plain(3), high(4), potion(5), st(6)),
            bag("Blue", new VisitRef(SESSION, "v2"), ut(7)), bag("Brown", null, ut(8)), bag(null, ref, plain(9), plain(10), plain(11), potion(12)));
        RunCardModel card = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, List.of(), bags, 1_240L);
        assertEquals("UT/ST, then high tier, then potions, then the rest; drop order inside a rank; at most eight",
            List.of(2, 6, 4, 1, 5, 12, 3, 9), card.loot().stream().map(RunCardModel.LootItem::id).collect(Collectors.toList()));
        assertEquals(Arrays.asList("White", "Orange", "Orange", "White", "Orange", null, "Orange", null),
            card.loot().stream().map(RunCardModel.LootItem::bag).collect(Collectors.toList()));
        assertEquals("UT", card.loot().get(0).tier()); assertEquals("ST", card.loot().get(1).tier());
        assertEquals("Every exact item counts, not only the eight shown", 10, card.lootCount());
        assertEquals("1 UT · 1 ST · 3 potions", card.lootSummary());
        assertEquals(Long.valueOf(1_240), card.fameGained());

        RunCardModel one = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, List.of(),
            List.of(bag("White", ref, ut(1), potion(2))), null);
        assertEquals("1 UT · 1 potion", one.lootSummary());
        RunCardModel plainOnly = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, List.of(),
            List.of(bag("Brown", ref, plain(1), plain(2))), null);
        assertEquals("Nothing notable: the item count", "2 items", plainOnly.lootSummary());
    }

    @Test public void unreadableLootOrCombatRecordsAreUnknownWithAReasonNeverNone() {
        VisitRef ref = new VisitRef(SESSION, "v1");
        RunCardModel unread = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, null, null, null);
        assertEquals("Loot for this session could not be read.", unread.lootReason());
        assertEquals(List.of(), unread.loot()); assertEquals(0, unread.lootCount()); assertEquals("", unread.lootSummary());
        assertNull(unread.combat()); assertEquals("Combat records for this session could not be read.", unread.combatReason());
        RunCardModel none = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, List.of(), List.of(), null);
        assertNull("Read, with no bag inside the run: a known none", none.lootReason());
        assertEquals(RunCardModel.NO_RECORDING, none.combatReason());
        RunCardModel.LootItem item = new RunCardModel.LootItem(1, "White", "UT");
        RunCardModel stated = new RunCardModel(ref, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, NOW, null, null, null,
            RunCardModel.NO_RECORDING, List.of(item), 1, "1 UT", RunCardModel.LOOT_UNREADABLE, null, null);
        assertEquals("A reason means no loot is shown", List.of(), stated.loot());
        assertEquals(0, stated.lootCount()); assertEquals("", stated.lootSummary());
    }

    @Test public void exaltProgressAppearsOnlyWhenTheVisitRecordedAnIncrease() {
        VisitRef ref = new VisitRef(SESSION, "v1");
        assertEquals(Integer.valueOf(2), RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 2, List.of(), List.of(), null).exaltProgress());
        assertNull(RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, List.of(), List.of(), null).exaltProgress());
        RunCardModel card = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, List.of(), List.of(), null);
        assertTrue("A sprite id or 0 (the kit's placeholder)", card.portalId() >= 0);
        RunCardModel blank = RunCardModel.of(ref, "", RunOutcome.UNKNOWN, NOW, MINUTE, null, 0, List.of(), List.of(), null);
        assertEquals("Unknown area", blank.mapName()); assertEquals(0, blank.portalId());
    }
}
