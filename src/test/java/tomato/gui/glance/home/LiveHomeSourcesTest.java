package tomato.gui.glance.home;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.QuestData;
import tomato.backend.data.*;
import tomato.gui.dps.RecordedEncounter;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

/** Production sources on an isolated TomatoData: no capture, a temporary journal and history, stand-in pin and recording lookups. */
public class LiveHomeSourcesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final List<CharacterJournal> journals = new ArrayList<>();

    @After public void closeJournals() { journals.forEach(CharacterJournal::close); }

    private TomatoData isolated() throws IOException {
        CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("Characters/journal.json"));
        journals.add(journal);
        return new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
    }
    private static QuestData quest(String id) {
        QuestData quest = new QuestData(); quest.id = id; quest.name = "Quest " + id; quest.rewards = new int[]{1};
        return quest;
    }

    @Test public void archiveWithoutSavedHistoryIsAnExplicitFailure() throws Exception {
        LiveHomeSources sources = new LiveHomeSources(isolated(), () -> null);
        IOException failure = assertThrows(IOException.class, () -> sources.archive(HomeArchive.Window.TODAY, 0));
        assertTrue(failure.getMessage(), failure.getMessage().contains("not available"));
    }

    @Test public void eachTokenMovesOnlyWhenItsOwnInputsChange() throws Exception {
        TomatoData data = isolated();
        LiveHomeSources sources = new LiveHomeSources(data, () -> null);
        HomeSources.Revisions first = sources.revisions();
        assertEquals("Nothing changed", first, sources.revisions());
        data.progression().reset("account-A", "identified");
        HomeSources.Revisions progressed = sources.revisions();
        assertTrue("A progression change moves the quests token", progressed.quests() > first.quests());
        assertEquals(first.hero(), progressed.hero()); assertEquals(first.now(), progressed.now());
        data.characterJournal().accountLive(CharacterJournal.accountKey("live-home-sources"), 782, 70, null, null, null, 1_000);
        HomeSources.Revisions journaled = sources.revisions();
        assertTrue("A journal change moves the hero token", journaled.hero() > progressed.hero());
        assertEquals(progressed.quests(), journaled.quests()); assertEquals(progressed.now(), journaled.now());
    }

    @Test public void pinsAreReadOnlyWhenTheQuestsTokenMoves() throws Exception {
        TomatoData data = isolated();
        AtomicInteger reads = new AtomicInteger();
        LiveHomeSources sources = new LiveHomeSources(data, () -> null,
            (account, quest) -> { reads.incrementAndGet(); return "q1".equals(quest.id); }, () -> "", List::of);
        ProgressionData progression = data.progression();
        progression.reset("account-A", "identified");
        assertTrue(progression.quests(progression.scope(), new QuestData[]{quest("q1"), quest("q2")}, 1_000));
        sources.revisions();
        HomeModel.Quests quests = sources.quests(2_000);
        assertEquals(1, quests.pinned()); assertEquals("One Preferences read per quest", 2, reads.get());
        sources.revisions(); sources.quests(12_000);
        assertEquals("The 10 s age rebuild reuses the pins it read", 2, reads.get());
        assertTrue(progression.quests(progression.scope(), new QuestData[]{quest("q1"), quest("q2")}, 20_000));
        sources.revisions(); sources.quests(21_000);
        assertEquals("A new quest list is read again", 4, reads.get());
    }

    @Test public void recordingsAreProjectedOncePerRecordingsRevision() throws Exception {
        AtomicInteger projections = new AtomicInteger();
        String[] revision = {"catalog#1"};
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), false, "fixture")) {
            LiveHomeSources sources = new LiveHomeSources(isolated(), () -> store, (account, quest) -> false, () -> revision[0],
                () -> { projections.incrementAndGet(); return List.<RecordedEncounter>of(); });
            sources.archive(HomeArchive.Window.TODAY, 1_000);
            sources.archive(HomeArchive.Window.SESSION, 2_000);
            assertEquals("Recorded meters are recomputed only when the recordings change", 1, projections.get());
            revision[0] = "catalog#2";
            sources.archive(HomeArchive.Window.TODAY, 3_000);
            assertEquals(2, projections.get());
        }
    }


    @Test public void reprojectionsGetEarlierProjectionsAndArchiveReadsKeepTheirCache() throws Exception {
        List<Map<String, RecordedEncounter>> maps = new ArrayList<>();
        String[] revision = {"catalog#1"};
        java.nio.file.Path root = temp.newFolder().toPath(); HomeHistoryFixture.write(root);
        HomeArchive.Cache cache = new HomeArchive.Cache();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            LiveHomeSources sources = new LiveHomeSources(isolated(), () -> store, (account, quest) -> false, () -> revision[0],
                known -> { maps.add(known); return List.of(); }, cache);
            sources.archive(HomeArchive.Window.TODAY, HomeHistoryFixture.NOW);
            revision[0] = "catalog#2";
            sources.archive(HomeArchive.Window.TODAY, HomeHistoryFixture.NOW);
            assertEquals(2, maps.size()); assertSame("Each re-projection gets the projections kept so far", maps.get(0), maps.get(1));
            assertTrue("The archive read through the sources' cache", cache.size() > 0);
        }
    }

    @Test public void unreadableJournalIsUnavailableUntilALiveCharacterCanBeShown() throws Exception {
        java.nio.file.Path path = temp.newFile("broken-journal.json").toPath();
        java.nio.file.Files.writeString(path, "{broken");
        CharacterJournal journal = new CharacterJournal(path);
        journals.add(journal);
        TomatoData data = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
        LiveHomeSources sources = new LiveHomeSources(data, () -> null);
        HomeModel.Hero unavailable = sources.hero(1000);
        assertEquals(HomeModel.State.UNAVAILABLE, unavailable.state());
        assertTrue(unavailable.evidence().contains("Cannot read Characters/journal.json"));
        data.liveCharacter.publish(new LiveCharacter.Snapshot("account", 7, 782, "Sample", null, 20, null,
            null, null, null, null, null, null, null, null, 1000));
        assertEquals("Captured data remains useful despite a failed saved journal", HomeModel.State.LIVE, sources.hero(1000).state());
    }

    @Test public void onlyAMapChangeKeepsTheLastCharacterLiveForTheGrace() throws Exception {
        TomatoData data = isolated();
        LiveHomeSources sources = new LiveHomeSources(data, () -> null);
        LiveCharacter live = data.liveCharacter;
        LiveCharacter.Snapshot sample = new LiveCharacter.Snapshot(CharacterJournal.accountKey("live-home-sources"), 7, 782, "Sample", null, 20,
            null, null, null, null, null, null, null, null, null, 1000);
        live.publish(sample);
        data.clear();   // map change
        long left = live.lastSeenAt();
        assertEquals("A map change: still live within the grace", HomeModel.State.LIVE, sources.hero(left + 4_000).state());
        assertEquals("and stale after it", HomeModel.State.STALE, sources.hero(left + HomeModelBuilder.MAP_CHANGE_GRACE_MILLIS + 1).state());
        long token = sources.revisions().hero();
        data.setUserId(1, 8, "AAAAAA==");   // CREATE for another character
        assertTrue("An identity change moves the hero token", sources.revisions().hero() > token);
        assertEquals("Another character: stale at once, even within the grace", HomeModel.State.STALE, sources.hero(left + 1).state());
        live.publish(sample);
        data.setUserId(1, 9, "AAAAAA==");   // another character without a map change first
        assertEquals(HomeModel.State.STALE, sources.hero(live.lastSeenAt()).state());
        live.publish(sample);
        data.captureStopped();
        assertEquals("Capture stopped: stale at once", HomeModel.State.STALE, sources.hero(live.lastSeenAt()).state());
    }

    @Test public void everySectionIsEmptyWithoutCaptureHistoryOrQuests() throws Exception {
        LiveHomeSources sources = new LiveHomeSources(isolated(), () -> null);
        sources.revisions();
        assertEquals("An empty journal and no live character", HomeModel.State.EMPTY, sources.hero(1_000).state());
        assertEquals(HomeModel.State.EMPTY, sources.quests(1_000).state());
        assertEquals("Tests never start capture", HomeModel.State.EMPTY, sources.now(1_000).state());
    }
}
