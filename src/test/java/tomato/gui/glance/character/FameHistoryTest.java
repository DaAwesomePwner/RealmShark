package tomato.gui.glance.character;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.FameFixtures.*;

/** Sheet › Fame's reader over synthetic saved history: exact account and character id only, untagged and unreadable counted, finished sessions read once. */
public class FameHistoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    /** A valid journal account key with no readings in the fixture. */
    private static final String NONE = CharacterJournal.accountKey("fame-fixture-none");

    private Path fixture() throws Exception { Path root = temp.newFolder().toPath(); FameFixtures.write(root); return root; }
    private static List<String> ids(FameHistory.Series series) { return series.sessions().stream().map(FameHistory.Session::id).collect(Collectors.toList()); }
    private static FameHistory.Point at(long time, long fame) { return new FameHistory.Point(time, fame); }

    @Test public void onlyReadingsOfExactlyThisAccountAndCharacterAreShown() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            FameHistory.Series series = new FameHistory(() -> store).read(ACCOUNT, 7);
            assertEquals("Oldest first; the corrupt and broken sessions are skipped", List.of(FIRST, SECOND), ids(series));
            assertEquals("Another account's #7, account #8 and untagged readings never join; the newer fame-latest checkpoint extends the session",
                List.of(at(T0 + MINUTE, 1_000), at(T0 + 5 * MINUTE, 1_100), at(T0 + 8 * MINUTE, 1_050), at(T0 + 13 * MINUTE, 1_250), at(T0 + 21 * MINUTE, 1_250)),
                series.sessions().get(0).points());
            assertEquals(List.of(at(T1 + MINUTE, 1_300), at(T1 + 10 * MINUTE, 1_400)), series.sessions().get(1).points());
            for (FameHistory.Session session : series.sessions()) assertFalse("Saved sessions are not the running one", session.current());
        }
    }

    @Test public void anotherAccountsSameCharacterIdIsItsOwnHistory() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            FameHistory history = new FameHistory(() -> store);
            FameHistory.Series other = history.read(OTHER, 7);
            assertEquals(List.of(FIRST), ids(other));
            assertEquals("A checkpoint no newer than the last reading is not added twice", List.of(at(T0 + 2 * MINUTE, 5_000), at(T0 + 10 * MINUTE, 5_400)),
                other.sessions().get(0).points());
            FameHistory.Series eight = history.read(ACCOUNT, 8);
            assertEquals(List.of(at(T0 + 6 * MINUTE, 300)), eight.sessions().get(0).points());
            assertEquals("Untagged readings of #7 are not #8's", 0, eight.untagged());
            assertTrue("An account with no readings has no sessions", history.read(NONE, 7).sessions().isEmpty());
        }
    }

    @Test public void untaggedReadingsOfThisCharacterIdAreCountedNeverShown() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            FameHistory history = new FameHistory(() -> store);
            assertEquals("Two dated readings in FIRST, one in SECOND; the undated one is no reading", 3, history.read(ACCOUNT, 7).untagged());
            assertEquals("The same count for another account's #7: an untagged reading may be either", 3, history.read(OTHER, 7).untagged());
        }
    }

    @Test public void unreadableSessionsAreSkippedAndCounted() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            FameHistory history = new FameHistory(() -> store);
            FameHistory.Series series = history.read(ACCOUNT, 7);
            assertEquals("Unreadable metadata and a corrupt middle line each count once", 2, series.unreadable());
            assertFalse("Readings before the corrupt line are not shown either", ids(series).contains(CORRUPT));
            assertEquals("A kept failure still counts on the next read", 2, history.read(ACCOUNT, 7).unreadable());
        }
    }

    @Test public void finishedSessionsAreReadOnceAndTheCurrentSessionEveryTime() throws Exception {
        Path root = fixture();
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            long start = store.started();
            store.append("fame", sample(ACCOUNT, 7, 2_000, start + 1_000));
            store.flush();
            FameHistory history = new FameHistory(() -> store);
            FameHistory.Series first = history.read(ACCOUNT, 7);
            assertEquals("FIRST, SECOND, CORRUPT and the current session", 4, history.reads());
            assertEquals(List.of(FIRST, SECOND, store.currentId()), ids(first));
            assertTrue("The running session is marked", first.sessions().get(2).current());
            store.append("fame", sample(ACCOUNT, 7, 2_100, start + 2_000));
            store.flush();
            FameHistory.Series second = history.read(ACCOUNT, 7);
            assertEquals("Only the current session is read again", 5, history.reads());
            assertEquals(List.of(at(start + 1_000, 2_000), at(start + 2_000, 2_100)), second.sessions().get(2).points());
            history.read(OTHER, 7);
            assertEquals("Another pair's first request reads FIRST and SECOND once more (only requested pairs are kept), with the current one",
                8, history.reads());
            history.read(OTHER, 7);
            assertEquals("Its later requests read only the current session", 9, history.reads());
            Path fame = root.resolve(SECOND).resolve("fame.jsonl");
            Files.setLastModifiedTime(fame, FileTime.fromMillis(Files.getLastModifiedTime(fame).toMillis() + 2_000));
            history.read(ACCOUNT, 7);
            assertEquals("A finished session whose file changed is read again, with the current one", 11, history.reads());
            history.read(OTHER, 7);
            assertEquals("Reading it again kept every requested pair's readings", 12, history.reads());
        }
    }

    /**
     * Memory follows what sheets asked for: a finished session keeps only the readings of the (account, character) pairs requested
     * so far, plus untagged readings counted per character id. A new pair's first request reads the finished sessions once; its
     * later requests read none of them (the current session, never kept, is read every time).
     */
    @Test public void onlyRequestedPairsAreKeptAndANewPairReadsTheSessionsOnce() throws Exception {
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            FameHistory history = new FameHistory(() -> store);
            history.read(ACCOUNT, 7);
            assertEquals("FIRST, SECOND, CORRUPT and the current session (listed though this store records nothing)", 4, history.reads());
            assertEquals("Only ACCOUNT #7's readings are kept (5 in FIRST, 2 in SECOND); OTHER #7's, #8's and untagged ones are not",
                7, history.cachedPoints());
            FameHistory.Series other = history.read(OTHER, 7);
            assertEquals("A new pair reads FIRST and SECOND once more, with the current one; CORRUPT's kept failure is not read again", 7, history.reads());
            assertEquals(List.of(FIRST), ids(other));
            assertEquals(List.of(at(T0 + 2 * MINUTE, 5_000), at(T0 + 10 * MINUTE, 5_400)), other.sessions().get(0).points());
            assertEquals(3, other.untagged());
            assertEquals(2, other.unreadable());
            assertEquals("OTHER #7's two readings join the kept ones", 9, history.cachedPoints());
            FameHistory.Series again = history.read(ACCOUNT, 7);
            history.read(OTHER, 7);
            assertEquals("Later requests of either pair read no finished session: only the current one, each time", 9, history.reads());
            assertEquals("Kept readings are still exact", List.of(FIRST, SECOND), ids(again));
            assertEquals(List.of(at(T1 + MINUTE, 1_300), at(T1 + 10 * MINUTE, 1_400)), again.sessions().get(1).points());
            assertEquals("Untagged readings stay counted from the kept per-character counts", 3, again.untagged());
            assertEquals(List.of(at(T0 + 6 * MINUTE, 300)), history.read(ACCOUNT, 8).sessions().get(0).points());
            assertEquals("#8 is a new pair: FIRST and SECOND once more, with the current one", 12, history.reads());
            assertEquals(10, history.cachedPoints());
            assertEquals("Untagged readings of #7 are not #8's", 0, history.read(ACCOUNT, 8).untagged());
            assertEquals(13, history.reads());
        }
    }

    @Test public void noStoreOrNoAccountReadsNothing() throws Exception {
        FameHistory.Series none = new FameHistory(() -> null).read(ACCOUNT, 7);
        assertTrue("History not started yet", none.sessions().isEmpty()); assertEquals(0, none.untagged()); assertEquals(0, none.unreadable());
        try (SessionStore store = new SessionStore(fixture(), false, "fixture")) {
            FameHistory history = new FameHistory(() -> store);
            FameHistory.Series untagged = history.read(null, 7);
            assertTrue("No account: nothing is exact", untagged.sessions().isEmpty());
            assertEquals("Nothing was read", 0, history.reads());
        }
    }

    @Test public void keysParseOnlyAsAJournalKey() {
        assertEquals(new FameHistory.Ref(ACCOUNT, 7), FameHistory.parse(KEY));
        assertNull("Upper-case hex is not a journal account key", FameHistory.parse(ACCOUNT.toUpperCase(java.util.Locale.ROOT) + ":7"));
        assertNull(FameHistory.parse(ACCOUNT + ":-7"));
        assertNull(FameHistory.parse(ACCOUNT.substring(1) + ":7"));
        assertNull("An id beyond int is no character", FameHistory.parse(ACCOUNT + ":99999999999"));
        assertNull(FameHistory.parse(ACCOUNT + ":7 "));
        assertNull(FameHistory.parse(null));
    }
}
