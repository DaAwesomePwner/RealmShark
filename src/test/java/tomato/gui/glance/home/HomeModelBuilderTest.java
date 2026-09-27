package tomato.gui.glance.home;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.Test;
import packets.data.QuestData;
import tomato.backend.data.*;
import tomato.gui.dps.MeterSummary;
import tomato.gui.glance.home.HomeModel.State;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.myinfo.BuildEstimates;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeArchive.Window.SESSION;
import static tomato.gui.glance.home.HomeArchive.Window.TODAY;

/** Home section rules on synthetic inputs: honest states, unknown never zero, estimates marked "≈". */
public class HomeModelBuilderTest {
    private static final int CLASS = 782;
    private static final int[] CAPS = {720, 252, 75, 25, 50, 75, 40, 60}, UNKNOWN8 = {-1, -1, -1, -1, -1, -1, -1, -1};
    private static final IntFunction<int[]> CAPS_OF = id -> id == CLASS ? CAPS.clone() : null;
    private static final IntFunction<String> NAMES = id -> id == CLASS ? "Wizard" : null;
    private static final long NOW = 1_700_000_000_000L;

    private static LiveCharacter.Snapshot live(int[] base, Long fame) { return live(base, fame, NOW - 500); }
    private static LiveCharacter.Snapshot live(int[] base, Long fame, long observedAt) {
        return new LiveCharacter.Snapshot("account-A", 7, CLASS, "Tester", 900, 20, fame, new int[]{800, 300, 90, 30, 60, 80, 50, 70},
            base, new int[]{1001, 1002, 0, 1004}, 12_345, 1_200, 70, null, null, observedAt);
    }
    private static BuildEstimates.Estimates estimates(Double dps, Double mp) { return new BuildEstimates.Estimates(dps, mp); }
    /** A current character (lastSeenAt 0), a journal record or nothing; a cleared one gives its boundary with the overload below. */
    private static HomeModel.Hero hero(LiveCharacter.Snapshot live, BuildEstimates.Estimates estimates, CharacterJournal.CharacterRecord last,
                                      CharacterJournal.AccountRecord account, long lastSeenAt) {
        return hero(live, estimates, last, account, lastSeenAt, null);
    }
    private static HomeModel.Hero hero(LiveCharacter.Snapshot live, BuildEstimates.Estimates estimates, CharacterJournal.CharacterRecord last,
                                      CharacterJournal.AccountRecord account, long lastSeenAt, LiveCharacter.Boundary boundary) {
        return HomeModelBuilder.hero(live, estimates, last, account, lastSeenAt, boundary, NOW, CAPS_OF, NAMES);
    }
    private static CharacterJournal.AccountRecord account(int... exalts) {
        CharacterJournal.AccountRecord account = new CharacterJournal.AccountRecord(); account.key = "account-A";
        if (exalts.length == 8) account.exalts.put(CLASS, exalts);
        return account;
    }
    private static MeterSummary.Row row(String name, long damage, boolean local) { return new MeterSummary.Row(name, CLASS, "Wizard", damage, damage / 60.0, local); }
    private static QuestData quest(String id, String name, boolean repeatable, boolean completed, int reward) {
        QuestData quest = new QuestData(); quest.id = id; quest.name = name; quest.repeatable = repeatable; quest.completed = completed; quest.rewards = new int[]{reward};
        return quest;
    }

    @Test public void liveHeroShowsMaxedPotionsExaltsAndEstimates() {
        // Exalt counts are in RealmCharacter order (dex, spd, vit, wis, def, atk, mana, life): tiers 5+4+3+2+1+0+0+5.
        HomeModel.Hero h = hero(live(new int[]{720, 252, 75, 20, 50, 75, 37, 48}, 4_321L), estimates(1_234.5, 12.25), null, account(75, 50, 30, 15, 5, 0, 4, 80), 0);
        assertEquals(State.LIVE, h.state()); assertEquals("Tester", h.name()); assertEquals("Wizard", h.className());
        assertEquals(5, h.maxed());
        assertArrayEquals(new int[]{0, 0, 0, 5, 0, 0, 3, 12}, h.potionsNeeded());
        assertEquals("Needs DEF 5 · VIT 3 · WIS 12 potions", h.needsLine());
        assertArrayEquals(CAPS, h.caps()); assertEquals(20, h.exaltTiers());
        assertArrayEquals(new int[]{1001, 1002, 0, 1004}, h.equipment());
        assertEquals(DisplayValue.State.KNOWN, h.fame().state); assertEquals(DisplayFormat.formatInteger(4_321), h.fame().text());
        assertEquals(DisplayValue.State.ESTIMATE, h.weaponDps().state);
        assertTrue(h.weaponDps().text(), h.weaponDps().text().matches("[≈~] " + Pattern.quote(DisplayFormat.formatNumber(1_234.5, 0))));
        assertEquals(DisplayValue.State.ESTIMATE, h.mpPerSecond().state);
        assertTrue(h.mpPerSecond().text().endsWith(DisplayFormat.formatNumber(12.25, 1)));
        assertEquals(DisplayFormat.formatInteger(70) + " stars · " + DisplayFormat.formatInteger(12_345) + " account fame · "
            + DisplayFormat.formatInteger(1_200) + " gold", h.accountLine());
        assertEquals("A live hero has no last-seen time", 0, h.lastSeenAt());
    }

    @Test public void anUnchangedLiveCharacterBuildsAnEqualHeroSoHomeDoesNotRedrawIt() {
        HomeModel.Hero first = hero(live(CAPS.clone(), 100L, NOW - 900), estimates(10.0, 1.0), null, account(), 0);
        HomeModel.Hero later = hero(live(CAPS.clone(), 100L, NOW - 100), estimates(10.0, 1.0), null, account(), 0);
        assertNotSame(first, later);
        assertEquals("A later packet with the same content builds an equal hero", first, later);
        assertEquals(first.hashCode(), later.hashCode());
        assertNotEquals("Arrays compare by value", first,
            hero(live(new int[]{720, 252, 75, 25, 50, 75, 40, 59}, 100L, NOW - 100), estimates(10.0, 1.0), null, account(), 0));
        assertNotEquals(first, hero(live(CAPS.clone(), 100L, NOW - 100), estimates(11.0, 1.0), null, account(), 0));
    }

    @Test public void theHeroCarriesTheJournalKeyOfTheSheetItOpens() {
        String account = CharacterJournal.accountKey("sample-account");
        LiveCharacter.Snapshot keyed = new LiveCharacter.Snapshot(account, 7, CLASS, "Tester", 900, 20, 100L, new int[]{800, 300, 90, 30, 60, 80, 50, 70},
            CAPS.clone(), new int[]{1001, 1002, 0, 1004}, null, null, null, null, null, NOW - 500);
        HomeModel.Hero inGame = hero(keyed, null, null, account(), 0);
        assertEquals("A live hero opens its own sheet", account + ":7", inGame.key());
        assertNull("An account that is not a journal key opens the Characters list", hero(live(CAPS.clone(), 100L), null, null, account(), 0).key());
        CharacterJournal.CharacterRecord saved = new CharacterJournal.CharacterRecord();
        saved.key = account + ":9"; saved.account = account; saved.characterId = 9; saved.classId = CLASS; saved.lastSeen = NOW - 60_000L;
        assertEquals("A saved hero opens that record's sheet", account + ":9", hero(null, null, saved, account(), 0).key());
        saved.key = "account-A:9";
        assertNull("A malformed saved key is never routed", hero(null, null, saved, account(), 0).key());
        assertNull("No character, no key", hero(null, null, null, null, 0).key());
        assertNotEquals("The key is part of the hero's content", inGame, HomeModels.withKey(inGame, null));
    }

    @Test public void needsLineShowsThreeStatsThenACount() {
        HomeModel.Hero h = hero(live(new int[]{710, 247, 72, 24, 45, 75, 40, 60}, 1L), null, null, null, 0);
        assertEquals(3, h.maxed());
        assertEquals("Needs LIFE 2 · MANA 1 · ATT 3 potions · +2 more", h.needsLine());
        HomeModel.Hero maxed = hero(live(CAPS.clone(), 1L), null, null, null, 0);
        assertEquals(8, maxed.maxed()); assertEquals("", maxed.needsLine());
    }

    @Test public void unknownIsNeverZero() {
        HomeModel.Hero h = hero(live(new int[]{720, 252, 75, -1, 50, 75, 40, 60}, null), null, null, account(), 0);
        assertEquals(-1, h.maxed()); assertEquals(-1, h.potionsNeeded()[3]); assertEquals(0, h.potionsNeeded()[0]); assertEquals("", h.needsLine());
        assertEquals("No exalts saved for this class", -1, h.exaltTiers());
        assertEquals(DisplayValue.State.UNKNOWN, h.fame().state); assertEquals(DisplayFormat.UNAVAILABLE, h.fame().text());
        assertEquals("No estimates: unknown, never 0", DisplayValue.State.UNKNOWN, h.weaponDps().state);
        assertEquals(DisplayValue.State.UNKNOWN, h.mpPerSecond().state);
        HomeModel.Hero bare = HomeModelBuilder.hero(new LiveCharacter.Snapshot("account-A", 7, 999, null, null, null, 0L, null, null, null,
            null, null, null, null, null, NOW), estimates(null, null), null, null, 0, null, NOW, CAPS_OF, NAMES);
        assertEquals("Class #999", bare.className());
        assertEquals("Class definitions missing: caps and potions unknown", -1, bare.maxed());
        assertArrayEquals(UNKNOWN8, bare.caps()); assertArrayEquals(UNKNOWN8, bare.potionsNeeded()); assertArrayEquals(UNKNOWN8, bare.totals());
        assertArrayEquals(new int[]{-1, -1, -1, -1}, bare.equipment());
        assertEquals("", bare.accountLine());
        assertEquals(DisplayValue.State.UNKNOWN, bare.weaponDps().state);
        assertEquals("A captured zero is a real zero", DisplayValue.State.ZERO, bare.fame().state);
    }

    @Test public void mapChangeGraceKeepsTheHeroLiveForFiveSeconds() {
        LiveCharacter.Snapshot known = live(CAPS.clone(), 100L);
        BuildEstimates.Estimates some = estimates(10.0, 1.0);
        LiveCharacter.Boundary mapChange = LiveCharacter.Boundary.TRANSIENT;
        assertEquals(State.LIVE, hero(known, some, null, null, NOW - 4_999, mapChange).state());
        assertEquals(State.LIVE, hero(known, some, null, null, NOW - 5_000, mapChange).state());
        HomeModel.Hero stale = hero(known, some, null, null, NOW - 5_001, mapChange);
        assertEquals(State.STALE, stale.state()); assertEquals(NOW - 5_001, stale.lastSeenAt());
        assertEquals(DisplayValue.State.STALE, stale.fame().state); assertEquals(DisplayFormat.formatInteger(100), stale.fame().text());
        assertEquals("Estimates stay marked as estimates", DisplayValue.State.ESTIMATE, stale.weaponDps().state);
        assertTrue(stale.evidence().startsWith("Not in game."));
    }

    @Test public void aLiveHeroShowsOnlyLiveAccountValuesAndOnlyAStaleHeroFillsGapsFromTheSavedRecord() {
        CharacterJournal.AccountRecord saved = account(); saved.rankStars = 50; saved.accountFame = 999L; saved.gold = 77L;
        LiveCharacter.Snapshot goldOnly = new LiveCharacter.Snapshot("account-A", 7, CLASS, "Tester", 900, 20, 100L, null, CAPS.clone(), null,
            null, 1_200, null, null, null, NOW - 500);
        HomeModel.Hero live = hero(goldOnly, null, null, saved, 0);
        assertEquals(State.LIVE, live.state());
        assertEquals("A live hero shows what the live snapshot has, never unlabeled saved values", DisplayFormat.formatInteger(1_200) + " gold", live.accountLine());
        HomeModel.Hero stale = hero(goldOnly, null, null, saved, NOW - 60_000);
        assertEquals(State.STALE, stale.state());
        assertEquals("A stale hero is labeled, so saved values may fill the gaps", DisplayFormat.formatInteger(50) + " stars · "
            + DisplayFormat.formatInteger(999) + " account fame · " + DisplayFormat.formatInteger(1_200) + " gold", stale.accountLine());
    }

    @Test public void captureStopAndIdentityChangesGetNoGrace() {
        LiveCharacter.Snapshot known = live(CAPS.clone(), 100L);
        assertEquals(State.LIVE, hero(known, null, null, null, NOW - 1, LiveCharacter.Boundary.TRANSIENT).state());
        assertEquals("Capture stopped: stale at once", State.STALE, hero(known, null, null, null, NOW - 1, LiveCharacter.Boundary.STOPPED).state());
        assertEquals("Another account or character: stale at once", State.STALE, hero(known, null, null, null, NOW - 1, LiveCharacter.Boundary.IDENTITY).state());
        assertEquals("No known reason: never live", State.STALE, hero(known, null, null, null, NOW - 1, null).state());
        assertTrue(HomeModelBuilder.stillCurrent(0, null, NOW));
        assertFalse(HomeModelBuilder.stillCurrent(NOW - 1, LiveCharacter.Boundary.STOPPED, NOW));
    }

    @Test public void journalRecordIsStaleAndNothingIsEmpty() {
        CharacterJournal.CharacterRecord last = new CharacterJournal.CharacterRecord();
        last.name = "Saved"; last.classId = CLASS; last.className = "Wizard"; last.characterId = 3; last.fame = 900L; last.lastSeen = NOW - 7_200_000;
        last.stats = new Integer[]{720, 252, 75, 25, 50, 75, 40, null}; last.equipment[0] = 2001; last.equipment[1] = -1;
        CharacterJournal.AccountRecord saved = account(); saved.rankStars = 50; saved.accountFame = 999L;
        HomeModel.Hero h = hero(null, null, last, saved, 0);
        assertEquals(State.STALE, h.state()); assertEquals("Saved", h.name()); assertEquals(NOW - 7_200_000, h.lastSeenAt());
        assertEquals(DisplayValue.State.STALE, h.fame().state);
        assertEquals(-1, h.maxed()); assertEquals(-1, h.potionsNeeded()[7]); assertEquals(0, h.potionsNeeded()[0]);
        assertArrayEquals("The journal has no live totals", UNKNOWN8, h.totals());
        assertArrayEquals(new int[]{2001, 0, -1, -1}, h.equipment());
        assertEquals(DisplayValue.State.UNKNOWN, h.weaponDps().state);
        assertEquals(DisplayFormat.formatInteger(50) + " stars · " + DisplayFormat.formatInteger(999) + " account fame", h.accountLine());
        assertEquals(State.EMPTY, hero(null, null, null, null, 0).state());
    }

    @Test public void nowShowsAreaAndMeterOnlyInDungeons() {
        KeypopGUI.LastPop pop = new KeypopGUI.LastPop("Ann", "Lost Halls", Instant.ofEpochMilli(NOW - 60_000));
        HomeModel.Now off = HomeModelBuilder.now(false, null, null, null, pop);
        assertEquals(State.EMPTY, off.state()); assertFalse(off.capturing()); assertSame(pop, off.lastPop());
        HomeModel.Now idle = HomeModelBuilder.now(true, null, null, null, null);
        assertEquals(State.LIVE, idle.state()); assertTrue(idle.capturing()); assertNull(idle.area()); assertTrue(idle.top().isEmpty());
        List<MeterSummary.Row> rows = List.of(row("A", 900, false), row("B", 800, false), row("C", 700, false), row("Me", 100, true));
        HomeModel.Now nexus = HomeModelBuilder.now(true, "Nexus", NOW - 120_000, rows, 4, 8, pop);
        assertEquals("Nexus", nexus.area()); assertNull(nexus.startedAt());
        assertTrue("Outside dungeons: area and capture state only", nexus.top().isEmpty()); assertEquals(0, nexus.localRank());
        HomeModel.Now halls = HomeModelBuilder.now(true, "Lost Halls", NOW - 120_000, rows, 4, 8, pop);
        assertEquals(Long.valueOf(NOW - 120_000), halls.startedAt());
        assertEquals(3, halls.top().size()); assertEquals("A", halls.top().get(0).name());
        assertEquals(4, halls.localRank()); assertEquals(8, halls.players());
        assertEquals("Equal inputs build an equal section", halls, HomeModelBuilder.now(true, "Lost Halls", NOW - 120_000, rows, 4, 8, pop));
    }

    @Test public void meterRowsNeedTheExactlyLinkedVisit() {
        VisitRef here = new VisitRef("session-A", "visit-1");
        assertTrue(HomeModelBuilder.linked(here, new EncounterContext(new VisitRef("session-A", "visit-1"), 7, 1L)));
        assertFalse("Another visit of the same session", HomeModelBuilder.linked(here, new EncounterContext(new VisitRef("session-A", "visit-2"), 7, 1L)));
        assertFalse("An encounter entered without a visit", HomeModelBuilder.linked(here, new EncounterContext(null, 7, 1L)));
        assertFalse(HomeModelBuilder.linked(here, null));
        assertFalse(HomeModelBuilder.linked(null, new EncounterContext(here, 7, 1L)));
        HomeModel.Now unlinked = HomeModelBuilder.now(true, null, new EncounterContext(here, 7, 1L),
            MeterSummary.of(null, 3), null);
        assertTrue("No current visit: no meter rows", unlinked.top().isEmpty());
    }

    @Test public void todayAndRunsStates() {
        HomeArchive.Totals none = new HomeArchive.Totals(TODAY, 0, 1, 0, 0, false, null, null, null, 0, 0, 0, 0, false);
        HomeArchive.Totals some = new HomeArchive.Totals(TODAY, 0, 1, 1, 2, true, 0L, null, new double[12], 0, 0, 0, 0, true);
        assertEquals(State.LOADING, HomeModelBuilder.today(TODAY, null, null).state());
        HomeModel.Today empty = HomeModelBuilder.today(TODAY, new HomeArchive.Result(none, List.of()), null);
        assertEquals(State.EMPTY, empty.state()); assertFalse(empty.reason().isEmpty());
        assertEquals(State.LIVE, HomeModelBuilder.today(TODAY, new HomeArchive.Result(some, List.of()), null).state());
        HomeModel.Today failed = HomeModelBuilder.today(SESSION, null, new IOException("Unreadable session abc\n at line 2"));
        assertEquals(State.UNAVAILABLE, failed.state()); assertEquals(SESSION, failed.window()); assertNull(failed.totals());
        assertEquals("Saved history could not be read: Unreadable session abc", failed.reason());
        assertEquals("Saved history could not be read: IllegalStateException", HomeModelBuilder.today(TODAY, null, new IllegalStateException()).reason());
        assertEquals(State.LOADING, HomeModelBuilder.runs(null, null).state());
        assertEquals(State.EMPTY, HomeModelBuilder.runs(new HomeArchive.Result(none, List.of()), null).state());
        HomeArchive.RecentRun run = new HomeArchive.RecentRun(new VisitRef("s", "v"), "Lost Halls", "Completed", NOW, null, List.of(1), null);
        HomeModel.Runs live = HomeModelBuilder.runs(new HomeArchive.Result(some, List.of(run)), null);
        assertEquals(State.LIVE, live.state()); assertEquals(List.of(run), live.rows());
        HomeModel.Runs failedRuns = HomeModelBuilder.runs(null, new IOException("x".repeat(400)));
        assertEquals(State.UNAVAILABLE, failedRuns.state());
        assertTrue("One short line", failedRuns.reason().length() <= "Saved runs could not be read: ".length() + 160);
        HomeArchive.Result good = new HomeArchive.Result(some, List.of(run));
        HomeModel.Today staleToday = HomeModelBuilder.staleToday(TODAY, good, NOW - 5 * 60_000, new IOException("disk full\nat line 2"), NOW);
        assertEquals("A failed re-read keeps the last good totals", State.STALE, staleToday.state()); assertEquals(some, staleToday.totals());
        assertEquals("Last updated 5 min ago · disk full", staleToday.reason());
        HomeModel.Runs staleRuns = HomeModelBuilder.staleRuns(good, NOW - 5 * 60_000, new IOException("disk full"), NOW);
        assertEquals(State.STALE, staleRuns.state()); assertEquals(List.of(run), staleRuns.rows());
        assertEquals("Last updated 5 min ago · disk full", staleRuns.reason());
        assertEquals("Totals compare by content", some, new HomeArchive.Totals(TODAY, 0, 1, 1, 2, true, 0L, null, new double[12], 0, 0, 0, 0, true));
        assertNotEquals("Whether runs were saved is content", some, new HomeArchive.Totals(TODAY, 0, 1, 1, 2, false, 0L, null, new double[12], 0, 0, 0, 0, true));
    }

    @Test public void aPeriodWithUnreadableSessionsIsNeverEmpty() {
        HomeArchive.Totals nothing = new HomeArchive.Totals(TODAY, 0, 1, 0, 0, false, null, null, null, 0, 0, 0, 0, false, 1);
        assertEquals("Its runs may be in the unreadable session", State.LIVE, HomeModelBuilder.today(TODAY, new HomeArchive.Result(nothing, List.of()), null).state());
        assertEquals("The P2 constructor: every session readable", 0, new HomeArchive.Totals(TODAY, 0, 1, 0, 0, false, null, null, null, 0, 0, 0, 0, false).unreadableSessions());
        assertNotEquals(nothing, new HomeArchive.Totals(TODAY, 0, 1, 0, 0, false, null, null, null, 0, 0, 0, 0, false, 2));
        HomeModel.Runs skipped = HomeModelBuilder.runs(new HomeArchive.Result(nothing, List.of(), 2), null);
        assertEquals("No readable run, but two sessions may hold newer ones: not empty", State.LIVE, skipped.state());
        assertEquals("2 saved sessions could not be read", skipped.reason());
        assertEquals("The P2 constructor: nothing skipped", State.EMPTY, HomeModelBuilder.runs(new HomeArchive.Result(nothing, List.of()), null).state());
    }

    @Test public void questsShowPinnedQuestsOnlyWithCountsAndStaleness() {
        ProgressionData progression = new ProgressionData(); progression.reset("account-A", "identified");
        assertTrue(progression.quests(progression.scope(), new QuestData[]{quest("q1", "Open repeatable", true, false, 11), quest("q2", "Pinned done", false, true, 12),
            quest("q3", "Pinned open", false, false, 13), quest("q4", "Done", false, true, 14), quest("q5", "Other", true, false, 15)}, NOW - 60_000));
        Predicate<QuestData> pins = q -> q.id.equals("q2") || q.id.equals("q3");
        HomeModel.Quests quests = HomeModelBuilder.quests(progression.snapshot(), pins, NOW);
        assertEquals(State.LIVE, quests.state()); assertFalse(quests.stale());
        assertEquals(2, quests.pinned()); assertEquals(2, quests.repeatable()); assertEquals(2, quests.done());
        assertEquals("Pinned open, then pinned done; unpinned quests are not listed",
            List.of("Pinned open", "Pinned done"), quests.top().stream().map(HomeModel.QuestLine::name).collect(Collectors.toList()));
        assertArrayEquals(new int[]{13}, quests.top().get(0).rewardIds());
        assertTrue(quests.top().get(1).done());
        assertEquals(NOW - 60_000, quests.capturedAt());
        assertEquals("Equal lists build an equal section", quests, HomeModelBuilder.quests(progression.snapshot(), pins, NOW));
        HomeModel.Quests unpinned = HomeModelBuilder.quests(progression.snapshot(), q -> false, NOW);
        assertTrue("Nothing pinned: no quest lines", unpinned.top().isEmpty());
        assertEquals("Counts stay", 0, unpinned.pinned()); assertEquals(2, unpinned.repeatable()); assertEquals(2, unpinned.done());
        assertTrue("A day-old list is stale", HomeModelBuilder.quests(progression.snapshot(), pins, NOW - 60_000 + 24L * 3_600_000).stale());
        progression.captureStopped();
        HomeModel.Quests stopped = HomeModelBuilder.quests(progression.snapshot(), pins, NOW);
        assertEquals(State.STALE, stopped.state()); assertTrue(stopped.stale()); assertEquals(2, stopped.pinned());
        assertEquals(State.EMPTY, HomeModelBuilder.quests(new ProgressionData().snapshot(), pins, NOW).state());
    }
}
