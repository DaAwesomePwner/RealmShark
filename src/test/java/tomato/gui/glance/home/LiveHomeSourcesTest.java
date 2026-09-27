package tomato.gui.glance.home;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    @Test public void everySectionIsEmptyWithoutCaptureHistoryOrQuests() throws Exception {
        LiveHomeSources sources = new LiveHomeSources(isolated(), () -> null);
        sources.revisions();
        assertEquals("An empty journal and no live character", HomeModel.State.EMPTY, sources.hero(1_000).state());
        assertEquals(HomeModel.State.EMPTY, sources.quests(1_000).state());
        assertEquals("Tests never start capture", HomeModel.State.EMPTY, sources.now(1_000).state());
    }
}
