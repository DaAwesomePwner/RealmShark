package tomato.history.encounter;

import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.dps.CombatSummaries;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** Saved combat records round-trip through the history store; bad files, future schemas and bad links never leak. */
public class CombatFactsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long T = 1_700_000_000_000L;
    private static final String S1 = HomeHistoryFixture.id("combat-one"), S2 = HomeHistoryFixture.id("combat-two");

    private static List<CombatRecord> readAll(SessionStore store, String scope) throws Exception {
        List<CombatRecord> records = new ArrayList<>();
        CombatFacts.read(store, store.catalog(), scope, records::add);
        return records;
    }
    private static CombatRecord record(String id, String session, String visit, Double window, Long entered) {
        CombatRecord record = new CombatRecord();
        record.recordingId = id; record.visitSession = session; record.visitId = visit; record.windowSeconds = window; record.enteredAt = entered;
        return record;
    }

    @Test public void recordsAndDetailsRoundTripThroughPutAndRead() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test");
        try {
            VisitRef visit = new VisitRef(store.currentId(), "journal:1");
            CombatSummaries.Result result = CombatSummaries.build(CombatFixtures.typical(visit, T, 30, 3, 9));
            String id = result.record().recordingId;
            store.put("encounters", id, result.record()); store.put("encounter-detail", id, result.detail()); store.flush();
            List<CombatRecord> records = readAll(store, store.currentId());
            assertEquals(1, records.size());
            assertEquals(SessionStore.JSON.toJson(result.record()), SessionStore.JSON.toJson(records.get(0)));
            assertEquals(visit, records.get(0).visit()); assertEquals(1, readAll(store, SessionStore.ALL).size());
            CombatDetail detail = CombatFacts.detail(store, store.currentId(), id);
            assertEquals(SessionStore.JSON.toJson(result.detail()), SessionStore.JSON.toJson(detail));
            assertNull("absent", CombatFacts.detail(store, store.currentId(), "no-such-recording"));
            assertNull("absent session folder", CombatFacts.detail(store, S1, id));
            assertNull("not a session ID", CombatFacts.detail(store, "../elsewhere", id));
        } finally { store.close(); }
    }

    @Test public void unknownValuesStayUnknownThroughJson() throws Exception {
        Path root = temp.newFolder().toPath();
        HomeHistoryFixture.session(root, S1, T, T + 1);
        CombatRecord record = record("r-1", S1, "v-1", null, null);
        CombatRecord.PlayerLine line = new CombatRecord.PlayerLine(); line.objectId = 5; line.damage = 10; line.rank = 1;
        record.players.add(line);
        CombatRecord.Boss boss = new CombatRecord.Boss(); boss.type = 5000; boss.damage = 10; record.bosses.add(boss);
        String json = SessionStore.JSON.toJson(record);
        assertFalse(json, json.contains("windowSeconds") || json.contains("taken") || json.contains("maxHp") || json.contains("\"deaths\":null"));
        CombatFixtures.writeRecord(root, S1, record);
        SessionStore store = new SessionStore(root, false, "test");
        try {
            CombatRecord read = readAll(store, S1).get(0);
            assertNull(read.windowSeconds); assertNull(read.enteredAt); assertNull(read.localObjectId); assertNull(read.localDamage());
            assertNull(read.players.get(0).taken); assertNull(read.players.get(0).deaths); assertNull(read.players.get(0).name);
            assertNull(read.bosses.get(0).maxHp); assertNull(read.dps(read.players.get(0)));
        } finally { store.close(); }
    }

    @Test public void unknownFieldsAreToleratedWhileFutureSchemasBadFilesAndMissingIdsAreSkipped() throws Exception {
        Path root = temp.newFolder().toPath();
        HomeHistoryFixture.session(root, S1, T, T + 1); HomeHistoryFixture.session(root, S2, T + 10, T + 11);
        CombatFixtures.write(root, S1, "encounters", "future-field",
            "{\"schemaVersion\":1,\"recordingId\":\"future-field\",\"totalDamage\":42,\"newThing\":{\"a\":[1,2]},\"players\":[{\"objectId\":3,\"damage\":42,\"rank\":1,\"aura\":true}]}");
        CombatFixtures.write(root, S1, "encounters", "newer", "{\"schemaVersion\":2,\"recordingId\":\"newer\"}");
        CombatFixtures.write(root, S1, "encounters", "no-id", "{\"schemaVersion\":1,\"totalDamage\":5}");
        CombatFixtures.write(root, S1, "encounters", "broken", "{\"schemaVersion\":1,\"recordingId\":");
        CombatFixtures.write(root, S1, "encounters", "array", "[1,2,3]");
        CombatFixtures.writeRecord(root, S2, record("other-session", S2, "v-1", 3.0, T));
        CombatFixtures.write(root, S1, "encounter-detail", "newer", "{\"schemaVersion\":2,\"recordingId\":\"newer\"}");
        CombatFixtures.write(root, S1, "encounter-detail", "broken", "{\"schemaVersion\":");
        CombatFixtures.write(root, S1, "encounter-detail", "mismatch", "{\"schemaVersion\":1,\"recordingId\":\"someone-else\"}");
        SessionStore store = new SessionStore(root, false, "test");
        try {
            List<CombatRecord> s1 = readAll(store, S1);
            assertEquals(1, s1.size());
            assertEquals("future-field", s1.get(0).recordingId); assertEquals(42, s1.get(0).totalDamage);
            assertEquals(3, s1.get(0).players.get(0).objectId); assertTrue("absent lists read as empty", s1.get(0).bosses.isEmpty());
            List<CombatRecord> all = readAll(store, SessionStore.ALL);
            assertEquals(2, all.size());
            assertNull("newer detail schema", CombatFacts.detail(store, S1, "newer"));
            assertNull("a detail for another recording", CombatFacts.detail(store, S1, "mismatch"));
            try { CombatFacts.detail(store, S1, "broken"); fail("an unreadable detail is an error, not an absence"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("Unreadable")); }
        } finally { store.close(); }
    }

    @Test public void readSessionDatesEachRecordByItsFileAndCountsDamagedFiles() throws Exception {
        Path root = temp.newFolder().toPath();
        HomeHistoryFixture.session(root, S1, T, T + 1); HomeHistoryFixture.session(root, S2, T + 10, T + 11);
        Path dated = CombatFixtures.writeRecord(root, S1, record("dated", S1, "v-1", 3.0, null));
        Files.setLastModifiedTime(dated, java.nio.file.attribute.FileTime.fromMillis(T - 42_000));
        CombatFixtures.writeRecord(root, S1, record("entered", S1, "v-2", 3.0, T));
        CombatFixtures.write(root, S1, "encounters", "newer", "{\"schemaVersion\":2,\"recordingId\":\"newer\"}");
        CombatFixtures.write(root, S1, "encounters", "no-id", "{\"schemaVersion\":1,\"totalDamage\":5}");
        CombatFixtures.write(root, S1, "encounters", "broken", "{\"schemaVersion\":1,\"recordingId\":");
        CombatFixtures.write(root, S1, "encounters", "array", "[1,2,3]");
        CombatFixtures.writeRecord(root, S2, record("other-session", S2, "v-1", 3.0, T));
        SessionStore store = new SessionStore(root, false, "test");
        try {
            SessionStore.SessionEntry entry = store.catalog().stream().filter(e -> e.id.equals(S1)).findFirst().orElseThrow();
            Map<String, Long> read = new TreeMap<>();
            int damaged = CombatFacts.readSession(store, entry, (record, written) -> read.put(record.recordingId, written));
            assertEquals("The same records as read(), only this session's", Set.of("dated", "entered"), read.keySet());
            assertEquals(Long.valueOf(T - 42_000), read.get("dated"));
            assertEquals("Damaged files are counted; newer schemas and records without an ID are not damage", 2, damaged);
            List<String> ordered = new ArrayList<>(); readAll(store, S1).forEach(r -> ordered.add(r.recordingId));
            List<String> same = new ArrayList<>(); CombatFacts.readSession(store, entry, (record, written) -> same.add(record.recordingId));
            assertEquals("In read()'s file-name order", ordered, same);
            SessionStore.SessionEntry empty = store.catalog().stream().filter(e -> e.id.equals(store.currentId())).findFirst().orElseThrow();
            assertEquals("A session without records reads nothing", 0, CombatFacts.readSession(store, empty, (record, written) -> fail()));
            Files.delete(root.resolve(S2).resolve("encounters").resolve(SessionStore.checkpointName("other-session") + ".json"));
            Files.delete(root.resolve(S2).resolve("encounters")); Files.write(root.resolve(S2).resolve("encounters"), new byte[]{1});
            SessionStore.SessionEntry blocked = store.catalog().stream().filter(e -> e.id.equals(S2)).findFirst().orElseThrow();
            assertEquals("A file where the folder should be holds no records", 0, CombatFacts.readSession(store, blocked, (record, written) -> fail()));
            Throwable[] thrown = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> { try { CombatFacts.readSession(store, entry, (r, w) -> {}); } catch (Throwable t) { thrown[0] = t; } });
            assertTrue(String.valueOf(thrown[0]), thrown[0] instanceof IllegalStateException);
        } finally { store.close(); }
    }

    @Test public void readingRefusesTheEventDispatchThread() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), false, "test");
        try {
            List<SessionStore.SessionEntry> catalog = store.catalog();
            Throwable[] thrown = new Throwable[2];
            SwingUtilities.invokeAndWait(() -> {
                try { CombatFacts.read(store, catalog, SessionStore.ALL, r -> {}); } catch (Throwable t) { thrown[0] = t; }
                try { CombatFacts.detail(store, store.currentId(), "x"); } catch (Throwable t) { thrown[1] = t; }
            });
            assertTrue(String.valueOf(thrown[0]), thrown[0] instanceof IllegalStateException);
            assertTrue(String.valueOf(thrown[1]), thrown[1] instanceof IllegalStateException);
        } finally { store.close(); }
    }

    @Test public void badVisitReferencesAreNeverLinkedAndByVisitUsesExactEquality() {
        CombatRecord a = record("a", S1, "v-1", 10.0, T), b = record("b", S2, "v-1", 10.0, T), c = record("c", S1, "v-1", 5.0, T);
        CombatRecord emptySession = record("d", "", "v-1", 1.0, T), noVisit = record("e", S1, null, 1.0, T), neither = record("f", null, null, 1.0, T);
        assertNull(emptySession.visit()); assertNull(noVisit.visit()); assertNull(neither.visit());
        Map<VisitRef, List<CombatRecord>> byVisit = CombatFacts.byVisit(Arrays.asList(a, emptySession, b, noVisit, c, neither, null));
        assertEquals(2, byVisit.size());
        assertEquals("same visit ID in another session is another run", Arrays.asList(a, c), byVisit.get(new VisitRef(S1, "v-1")));
        assertEquals(Collections.singletonList(b), byVisit.get(new VisitRef(S2, "v-1")));
        assertTrue(CombatFacts.byVisit(Collections.emptyList()).isEmpty());
    }

    @Test public void longestPrefersTheLongestWindowThenTheLatestEntryThenTheRecordingId() {
        CombatRecord short1 = record("a", S1, "v", 10.0, T + 50), long1 = record("b", S1, "v", 20.0, T);
        assertSame(long1, CombatFacts.longest(Arrays.asList(short1, long1)));
        CombatRecord early = record("c", S1, "v", 20.0, T - 1), late = record("d", S1, "v", 20.0, T + 1);
        assertSame(late, CombatFacts.longest(Arrays.asList(early, late, long1)));
        CombatRecord twinA = record("e1", S1, "v", 20.0, T + 1), twinB = record("e2", S1, "v", 20.0, T + 1);
        assertSame(twinB, CombatFacts.longest(Arrays.asList(twinB, twinA)));
        assertSame(twinB, CombatFacts.longest(Arrays.asList(twinA, twinB)));
        CombatRecord unknownWindow = record("z", S1, "v", null, T + 99), unknownEntry = record("y", S1, "v", 1.0, null);
        assertSame("an unknown window is never the longest", unknownEntry, CombatFacts.longest(Arrays.asList(unknownWindow, unknownEntry)));
        assertSame(unknownWindow, CombatFacts.longest(Collections.singletonList(unknownWindow)));
        assertNull(CombatFacts.longest(Collections.emptyList())); assertNull(CombatFacts.longest(null));
    }

    @Test public void writeLargeAddsOneExactlyLinkedRecordAndDetailPerRunOfTheHomeFixture() throws Exception {
        Path root = temp.newFolder().toPath();
        HomeHistoryFixture.writeLarge(root, 2, 3);
        CombatFixtures.writeLarge(root, 2, 3);
        SessionStore store = new SessionStore(root, false, "test");
        try {
            Map<VisitRef, ActivityJournal.Visit> runs = new HashMap<>();
            store.read(SessionStore.ALL, "runs", ActivityJournal.Visit.class, (session, visit) -> runs.put(new VisitRef(session.id, visit.id), visit));
            List<CombatRecord> records = readAll(store, SessionStore.ALL);
            assertEquals(6, runs.size()); assertEquals(6, records.size());
            Map<VisitRef, List<CombatRecord>> byVisit = CombatFacts.byVisit(records);
            assertEquals(runs.keySet(), byVisit.keySet());
            for (CombatRecord record : records) {
                ActivityJournal.Visit run = runs.get(record.visit());
                assertEquals(run.map, record.map); assertEquals(Long.valueOf(run.started), record.enteredAt);
                assertTrue(record.startedAt > run.started && record.lastHitAt <= run.ended);
                assertEquals(8, record.contributors); assertEquals(60, record.enemies); assertNotNull(record.local());
                assertEquals(CombatFixtures.largeRecordingId(record.visitSession, record.visitId), record.recordingId);
                CombatDetail detail = CombatFacts.detail(store, record.visitSession, record.recordingId);
                assertEquals(record.recordingId, detail.recordingId); assertEquals((long) record.startedAt, detail.bucketOrigin);
                assertEquals(150, detail.series.get(0).values.length);
            }
        } finally { store.close(); }
    }
}
