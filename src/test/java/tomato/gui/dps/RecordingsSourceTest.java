package tomato.gui.dps;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.*;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/**
 * The Recordings tab's reader: one row per recording across this app run's memory, saved history (records and full-detail
 * files) and imports; pruned full detail; the 30-day scope; link states from each item's own fields; unknown values stay
 * null; unreadable sessions are counted; closed sessions are kept by stamp; the large history reads off the EDT. Synthetic
 * names and isolated history folders only.
 */
public class RecordingsSourceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long NOW = HomeHistoryFixture.NOW, HOUR = HomeHistoryFixture.HOUR, DAY = 24 * HOUR;
    private static final String S1 = HomeHistoryFixture.id("recordings-one"), S2 = HomeHistoryFixture.id("recordings-two"),
        RECENT = HomeHistoryFixture.id("recordings-recent"), OLD = HomeHistoryFixture.id("recordings-old"), BAD = HomeHistoryFixture.id("recordings-bad");
    private final EncounterCatalog catalog = new EncounterCatalog();
    private final List<SessionStore> stores = new ArrayList<>();

    @After public void close() { for (SessionStore store : stores) store.close(); }

    private SessionStore store(Path root, boolean writable) { SessionStore store = new SessionStore(root, writable, "test"); stores.add(store); return store; }
    private RecordingsSource source(SessionStore store) { return new RecordingsSource(() -> store, catalog::entries, () -> NOW); }
    private static RecordingsQuery all() { return new RecordingsQuery("", RecordingsQuery.Scope.ALL, Set.of(), null); }
    private static List<String> keys(RecordingsSource.Result result) { return result.items().stream().map(RecordingItem::key).collect(Collectors.toList()); }
    private static RecordingItem row(RecordingsSource.Result result, String key) {
        return result.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow(() -> new AssertionError("No row " + key + " in " + keys(result)));
    }

    @Test public void oneRowPerRecordingAcrossMemorySavedHistoryAndImports() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, S1, NOW - 6 * HOUR, NOW - HOUR / 2);
        HomeHistoryFixture.session(root, S2, NOW - 30 * HOUR, NOW - 25 * HOUR);
        DpsData a = plain("Lost Halls", new VisitRef(S1, "v-a"), NOW - HOUR, 1, 300);
        DpsData b = plain("Ice Citadel", null, NOW - 2 * HOUR, 1, 200);
        DpsData c = plain("Pirate Cave", null, NOW - 3 * HOUR, null, 100);
        DpsData f = plain("Abyss of Demons", null, NOW - 5 * HOUR, 1, 90);
        DpsData d = plain("Snake Pit", new VisitRef(S2, "v-d"), NOW - 26 * HOUR, 1, 150);
        CombatRecord aRecord = record(a); aRecord.fullDetail = true;
        CombatFixtures.writeRecord(root, S1, aRecord);
        Path aFull = writeDps(CombatAutosave.fullDetailFile(root.resolve(S1), a.getRecordingId()), a.getSaveFile(false));
        CombatFixtures.writeRecord(root, S1, record(c));
        CombatRecord dRecord = record(d); dRecord.fullDetail = true;
        CombatFixtures.writeRecord(root, S2, dRecord);
        Path dFull = writeDps(CombatAutosave.fullDetailFile(root.resolve(S2), d.getRecordingId()), d.getSaveFile(false));
        catalog.captured(new DpsData[]{a, b});
        EncounterCatalog.Entry aEntry = catalog.entries().get(0), bEntry = catalog.entries().get(1);
        EncounterCatalog.Entry e = catalog.add(EncounterImport.read(writeDps(temp.getRoot().toPath().resolve("a-copy.dps"), a.getSaveFile(false)))).entry;
        EncounterCatalog.Entry fEntry = catalog.add(EncounterImport.read(writeDps(temp.getRoot().toPath().resolve("f.dps"), f.getSaveFile(false)))).entry;
        Path legacyFile = temp.getRoot().toPath().resolve("legacy.dps"); Files.write(legacyFile, EncounterImportFilterTest.baseline());
        EncounterCatalog.Entry g = catalog.add(EncounterImport.read(legacyFile)).entry;
        DpsData variant = f.getSaveFile(false); variant.totalDungeonPcTime = 9_000;
        EncounterCatalog.Entry h = catalog.add(EncounterImport.read(writeDps(temp.getRoot().toPath().resolve("f-variant.dps"), variant))).entry;
        EncounterCatalog.Entry dEntry = catalog.addSaved(EncounterImport.readSaved(dFull), x -> false);

        RecordingsSource source = source(store(root, false));
        RecordingsSource.Result result = source.read(RecordingsQuery.defaults(), new Cancellation());
        String eKey = EncounterCatalog.reference(e), fKey = EncounterCatalog.reference(fEntry), gKey = EncounterCatalog.reference(g), hKey = EncounterCatalog.reference(h);
        List<String> keys = keys(result);
        assertEquals("Eight recordings, one row each: " + keys, 8, new HashSet<>(keys).size()); assertEquals(8, keys.size());
        assertEquals("Newest first; a tie puts this app run before its imported copy", List.of(a.getRecordingId(), eKey, b.getRecordingId(), c.getRecordingId()), keys.subList(0, 4));
        assertEquals(Set.of(fKey, hKey), new HashSet<>(keys.subList(4, 6)));
        assertEquals(List.of(d.getRecordingId(), gKey), keys.subList(6, 8));
        assertEquals(NOW, result.capturedAt()); assertEquals(0, result.sessionsSkipped()); assertEquals(List.of(), result.issues());

        RecordingItem aRow = row(result, a.getRecordingId());   // in memory + saved record + full detail: one row, the memory copy opens
        assertEquals(RecordingItem.Kind.THIS_RUN, aRow.kind()); assertEquals(aEntry.id, aRow.memoryEntryId());
        assertTrue(aRow.summarySaved()); assertEquals(S1, aRow.session());
        assertEquals(RecordingItem.FullDetail.PRESENT, aRow.fullDetail()); assertEquals(Long.valueOf(Files.size(aFull)), aRow.fullDetailBytes());
        assertEquals(RecordingItem.Link.LINKED, aRow.link()); assertEquals(new VisitRef(S1, "v-a"), aRow.visit());
        assertEquals("Lost Halls", aRow.mapName()); assertEquals(Long.valueOf(NOW - HOUR), aRow.enteredAt());
        assertEquals(aRecord.dps(aRecord.local()), aRow.localDps()); assertNull(aRow.localUnavailable());
        assertEquals(2, aRow.contributors()); assertEquals(Long.valueOf(aRecord.totalDamage), aRow.totalDamage());
        assertNull(aRow.fileName()); assertNull(aRow.sameRecordingAs());

        RecordingItem bRow = row(result, b.getRecordingId());   // in memory, not saved (yet): facts from the recording itself
        assertEquals(RecordingItem.Kind.THIS_RUN, bRow.kind()); assertEquals(bEntry.id, bRow.memoryEntryId());
        assertFalse(bRow.summarySaved()); assertNull(bRow.session());
        assertEquals(RecordingItem.FullDetail.NONE, bRow.fullDetail()); assertNull(bRow.fullDetailBytes());
        assertEquals(RecordingItem.Link.UNLINKED, bRow.link()); assertNull(bRow.visit());
        CombatRecord bFacts = record(b);
        assertEquals(bFacts.windowSeconds, bRow.windowSeconds()); assertEquals(Long.valueOf(bFacts.totalDamage), bRow.totalDamage());
        assertEquals(bFacts.dps(bFacts.local()), bRow.localDps());

        RecordingItem cRow = row(result, c.getRecordingId());   // saved only
        assertEquals(RecordingItem.Kind.SAVED, cRow.kind()); assertNull(cRow.memoryEntryId()); assertTrue(cRow.summarySaved());
        assertEquals(S1, cRow.session()); assertEquals(RecordingItem.Link.UNLINKED, cRow.link());
        assertNull(cRow.localDps()); assertEquals(RecordingItem.UNVERIFIED_LOCAL, cRow.localUnavailable());

        RecordingItem dRow = row(result, d.getRecordingId());   // saved full detail loaded into memory: its record's row
        assertEquals(RecordingItem.Kind.SAVED, dRow.kind()); assertEquals(dEntry.id, dRow.memoryEntryId()); assertTrue(dRow.inMemory());
        assertEquals(S2, dRow.session()); assertEquals(RecordingItem.FullDetail.PRESENT, dRow.fullDetail());
        assertEquals(RecordingItem.Link.LINKED, dRow.link());

        RecordingItem eRow = row(result, eKey);   // an imported copy of a recording already listed: its own row, and says so
        assertEquals(RecordingItem.Kind.IMPORTED, eRow.kind()); assertEquals(a.getRecordingId(), eRow.recordingId());
        assertEquals(a.getRecordingId(), eRow.sameRecordingAs()); assertEquals("a-copy.dps", eRow.fileName());
        assertEquals(e.id, eRow.memoryEntryId()); assertFalse(eRow.summarySaved()); assertNull(eRow.session());
        assertEquals(RecordingItem.FullDetail.NONE, eRow.fullDetail()); assertEquals(RecordingItem.Link.LINKED, eRow.link());
        assertNull(row(result, fKey).sameRecordingAs());
        assertEquals("A second variant of the same imported recording", fKey, row(result, hKey).sameRecordingAs());

        RecordingItem gRow = row(result, gKey);   // a legacy import: no recording ID, keyed by its bytes
        assertEquals(RecordingItem.Kind.LEGACY_IMPORT, gRow.kind()); assertNull(gRow.recordingId()); assertTrue(gKey.startsWith("file:"));
        assertEquals(RecordingItem.Link.LEGACY, gRow.link()); assertNull(gRow.enteredAt());
        assertNull(gRow.localDps()); assertEquals(RecordingItem.LEGACY_LOCAL, gRow.localUnavailable()); assertEquals("legacy.dps", gRow.fileName());

        for (RecordingItem item : result.items()) assertFalse("No path in " + item, item.toString().contains(root.toString()) || item.toString().contains(temp.getRoot().toString()));

        RecordingsSource.Result again = source.read(RecordingsQuery.defaults(), new Cancellation());
        assertEquals(result.items(), again.items());
        assertEquals("Each in-memory recording without a saved record is summarized once (graphs are frozen)", 5, source.summarized());

        assertEquals(List.of(d.getRecordingId()), keys(source.read(new RecordingsQuery("SNAKE", null, null, null), new Cancellation())));
        assertEquals(List.of(eKey), keys(source.read(new RecordingsQuery(" a-copy ", null, null, null), new Cancellation())));
        assertEquals(Set.of(eKey, fKey, gKey, hKey), new HashSet<>(keys(source.read(new RecordingsQuery("", null,
            EnumSet.of(RecordingItem.Kind.IMPORTED, RecordingItem.Kind.LEGACY_IMPORT), null), new Cancellation()))));
        assertEquals(List.of(a.getRecordingId(), eKey, d.getRecordingId()),
            keys(source.read(new RecordingsQuery("", null, Set.of(), RecordingItem.Link.LINKED), new Cancellation())));
        assertEquals(List.of(gKey), keys(source.read(new RecordingsQuery("", null, Set.of(), RecordingItem.Link.LEGACY), new Cancellation())));
    }

    @Test public void fullDetailIsPresentWhileItsFileExistsAndPrunedWhenOnlyTheRecordSaysSo() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, S1, NOW - 6 * HOUR, NOW - HOUR);
        CombatRecord kept = saved("kept", NOW - HOUR, true), pruned = saved("pruned", NOW - 2 * HOUR, true), none = saved("none", NOW - 3 * HOUR, false),
            orphan = saved("orphan", NOW - 4 * HOUR, false);
        for (CombatRecord record : List.of(kept, pruned, none, orphan)) CombatFixtures.writeRecord(root, S1, record);
        Files.write(Files.createDirectories(root.resolve(S1).resolve(CombatRetention.FULL_DETAIL)).resolve(SessionStore.checkpointName("kept") + ".dps"), new byte[1234]);
        Files.write(root.resolve(S1).resolve(CombatRetention.FULL_DETAIL).resolve(SessionStore.checkpointName("orphan") + ".dps"), new byte[10]);
        RecordingsSource.Result result = source(store(root, false)).read(all(), new Cancellation());
        assertEquals(List.of("kept", "pruned", "none", "orphan"), keys(result));
        assertEquals(RecordingItem.FullDetail.PRESENT, row(result, "kept").fullDetail()); assertEquals(Long.valueOf(1234), row(result, "kept").fullDetailBytes());
        assertEquals("Retention deleted the file and never rewrites the record", RecordingItem.FullDetail.PRUNED, row(result, "pruned").fullDetail());
        assertNull(row(result, "pruned").fullDetailBytes());
        assertEquals(RecordingItem.FullDetail.NONE, row(result, "none").fullDetail());
        assertEquals("The file is the evidence", RecordingItem.FullDetail.PRESENT, row(result, "orphan").fullDetail());
    }

    @Test public void theLast30DaysUseEntryTimeElseFileTimeWhileMemoryRowsAlwaysShow() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, RECENT, NOW - 40 * DAY, NOW - DAY);
        HomeHistoryFixture.session(root, OLD, NOW - 50 * DAY, NOW - 40 * DAY);
        CombatFixtures.writeRecord(root, RECENT, saved("entered-2d", NOW - 2 * DAY, false));
        CombatFixtures.writeRecord(root, RECENT, saved("entered-31d", NOW - 31 * DAY, false));
        Files.setLastModifiedTime(CombatFixtures.writeRecord(root, RECENT, saved("file-1d", null, false)), FileTime.fromMillis(NOW - DAY));
        Files.setLastModifiedTime(CombatFixtures.writeRecord(root, RECENT, saved("file-35d", null, false)), FileTime.fromMillis(NOW - 35 * DAY));
        CombatFixtures.writeRecord(root, OLD, saved("old-45d", NOW - 45 * DAY, false));
        DpsData old = plain("Tomb of the Ancients", new VisitRef(OLD, "v-old"), NOW - 44 * DAY, 1, 120);
        CombatRecord oldRecord = record(old); oldRecord.fullDetail = true; CombatFixtures.writeRecord(root, OLD, oldRecord);
        Path oldFull = writeDps(CombatAutosave.fullDetailFile(root.resolve(OLD), old.getRecordingId()), old.getSaveFile(false));
        DpsData ancient = plain("Parasite Chambers", null, NOW - 400 * DAY, 1, 80);
        String importKey = EncounterCatalog.reference(catalog.add(EncounterImport.read(writeDps(temp.getRoot().toPath().resolve("ancient.dps"), ancient))).entry);

        RecordingsSource source = source(store(root, false));
        RecordingsSource.Result recent = source.read(RecordingsQuery.defaults(), new Cancellation());
        assertEquals("Imports are listed whatever their age", List.of("file-1d", "entered-2d", importKey), keys(recent));
        assertFalse("A session that ended before the scope is not read", source.reads(OLD) > 0);
        RecordingsSource.Result everything = source.read(all(), new Cancellation());
        assertEquals(List.of("file-1d", "entered-2d", "entered-31d", "file-35d", old.getRecordingId(), "old-45d", importKey), keys(everything));
        assertTrue(source.reads(OLD) > 0);

        EncounterCatalog.Entry loaded = catalog.addSaved(EncounterImport.readSaved(oldFull), x -> false);
        RecordingsSource.Result withLoaded = source.read(RecordingsQuery.defaults(), new Cancellation());
        assertEquals("Saved full detail in memory shows with its record, older records of its session do not",
            List.of("file-1d", "entered-2d", old.getRecordingId(), importKey), keys(withLoaded));
        RecordingItem oldRow = row(withLoaded, old.getRecordingId());
        assertEquals(RecordingItem.Kind.SAVED, oldRow.kind()); assertEquals(loaded.id, oldRow.memoryEntryId());
        assertTrue(oldRow.summarySaved()); assertEquals(OLD, oldRow.session()); assertEquals(RecordingItem.FullDetail.PRESENT, oldRow.fullDetail());
    }

    @Test public void unknownValuesStayNullAndARealZeroStaysZero() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, S1, NOW - 6 * HOUR, NOW - HOUR);
        CombatRecord bare = saved("bare", NOW - HOUR, false);
        CombatRecord silent = saved("silent", NOW - 2 * HOUR, false); silent.localObjectId = 1; silent.windowSeconds = 10.0; silent.totalDamage = 500; silent.contributors = 1;
        CombatRecord.PlayerLine other = new CombatRecord.PlayerLine(); other.objectId = 2; other.damage = 500; other.rank = 1; silent.players.add(other);
        CombatRecord instant = saved("instant", NOW - 3 * HOUR, false); instant.localObjectId = 1; instant.totalDamage = 50; instant.contributors = 1;
        CombatRecord.PlayerLine local = new CombatRecord.PlayerLine(); local.objectId = 1; local.local = true; local.damage = 50; local.rank = 1; instant.players.add(local);
        CombatRecord legacy = saved("legacy", null, false);
        Files.setLastModifiedTime(CombatFixtures.writeRecord(root, S1, legacy), FileTime.fromMillis(NOW - 5 * HOUR));
        CombatRecord linked = saved("linked", NOW - 4 * HOUR, false); linked.visitSession = S1; linked.visitId = "v-5";
        CombatRecord half = saved("half", NOW - 4 * HOUR - 1, false); half.visitId = "v-6";
        for (CombatRecord record : List.of(bare, silent, instant, linked, half)) CombatFixtures.writeRecord(root, S1, record);
        RecordingsSource.Result result = source(store(root, false)).read(all(), new Cancellation());
        assertEquals(List.of("bare", "silent", "instant", "linked", "half", "legacy"), keys(result));

        RecordingItem bareRow = row(result, "bare");
        assertNull(bareRow.windowSeconds()); assertNull(bareRow.startedAt()); assertNull(bareRow.elapsedMs()); assertNull(bareRow.map());
        assertNull(bareRow.localDps()); assertEquals(RecordingItem.UNVERIFIED_LOCAL, bareRow.localUnavailable());
        assertEquals("A saved zero is a real zero", Long.valueOf(0), bareRow.totalDamage()); assertEquals(0, bareRow.contributors());
        assertEquals(RecordingItem.Link.UNLINKED, bareRow.link()); assertNull(bareRow.visit());
        assertEquals("Verified and no recorded damage over a known window: 0 DPS", Double.valueOf(0), row(result, "silent").localDps());
        assertNull(row(result, "silent").localUnavailable());
        assertNull("No window: DPS unknown", row(result, "instant").localDps()); assertEquals(RecordingItem.NO_WINDOW, row(result, "instant").localUnavailable());
        assertEquals(RecordingItem.Link.LEGACY, row(result, "legacy").link()); assertNull(row(result, "legacy").enteredAt());
        assertEquals(RecordingItem.LEGACY_LOCAL, row(result, "legacy").localUnavailable());
        assertEquals(RecordingItem.Link.LINKED, row(result, "linked").link()); assertEquals(new VisitRef(S1, "v-5"), row(result, "linked").visit());
        assertEquals("Half a visit is no link", RecordingItem.Link.UNLINKED, row(result, "half").link()); assertNull(row(result, "half").visit());
    }

    @Test public void unreadableSessionsAndDamagedRecordsAreCountedNotHidden() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, S1, NOW - 6 * HOUR, NOW - HOUR);
        HomeHistoryFixture.session(root, S2, NOW - 9 * HOUR, NOW - 8 * HOUR);
        Files.write(Files.createDirectories(root.resolve(BAD)).resolve("session.json"), "not metadata".getBytes("UTF-8"));
        CombatFixtures.writeRecord(root, S1, saved("good", NOW - HOUR, false));
        CombatFixtures.write(root, S1, CombatFacts.RECORDS, "damaged", "{\"schemaVersion\":1,\"recordingId\":");
        CombatFixtures.writeRecord(root, S2, saved("unlistable", NOW - 8 * HOUR, false));
        DpsData live = plain("Lost Halls", null, NOW - HOUR / 2, 1, 100); catalog.captured(new DpsData[]{live});
        RecordingsSource source = source(store(root, false));
        source.recordReader((store, entry, sink) -> {
            if (entry.id.equals(S2)) throw new IOException("synthetic unlistable folder");
            return CombatFacts.readSession(store, entry, sink);
        });
        RecordingsSource.Result result = source.read(all(), new Cancellation());
        assertEquals(List.of(live.getRecordingId(), "good"), keys(result));
        assertEquals("Unreadable metadata and an unlistable records folder", 2, result.sessionsSkipped());
        assertTrue(result.issues().toString(), result.issues().contains(BAD + ": session metadata could not be read"));
        assertTrue(result.issues().toString(), result.issues().contains(S2 + ": encounters could not be read (IOException)"));
        assertTrue(result.issues().toString(), result.issues().contains(S1 + ": 1 combat record could not be read"));
        for (String issue : result.issues()) assertFalse(issue, issue.contains(root.toString()) || issue.contains("synthetic"));

        RecordingsSource.Result noHistory = new RecordingsSource(() -> null, catalog::entries, () -> NOW).read(all(), new Cancellation());
        assertEquals("This app run still lists without saved history", List.of(live.getRecordingId()), keys(noHistory));
        assertEquals(List.of(RecordingsSource.NO_HISTORY), noHistory.issues()); assertEquals(0, noHistory.sessionsSkipped());
    }

    @Test public void closedSessionsAreKeptByStampAndTheCurrentSessionIsReadAgain() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, S1, NOW - 6 * HOUR, NOW - HOUR);
        CombatFixtures.writeRecord(root, S1, saved("first", NOW - 2 * HOUR, true));
        SessionStore store = store(root, true);
        RecordingsSource source = source(store);
        assertEquals(List.of("first"), keys(source.read(all(), new Cancellation())));
        assertEquals(RecordingItem.FullDetail.PRUNED, row(source.read(all(), new Cancellation()), "first").fullDetail());
        assertEquals("The closed session was read once", 1, source.reads(S1));
        assertEquals("The current session every time", 2, source.reads(store.currentId()));

        Files.write(Files.createDirectories(root.resolve(S1).resolve(CombatRetention.FULL_DETAIL)).resolve(SessionStore.checkpointName("first") + ".dps"), new byte[7]);
        CombatFixtures.writeRecord(root, S1, saved("second", NOW - 3 * HOUR, false));
        CombatRecord current = saved("current", NOW - HOUR / 4, false);
        store.put(CombatFacts.RECORDS, current.recordingId, current); store.flush();
        RecordingsSource.Result changed = source.read(all(), new Cancellation());
        assertEquals(List.of("current", "first", "second"), keys(changed));
        assertEquals(2, source.reads(S1));
        assertEquals(RecordingItem.FullDetail.PRESENT, row(changed, "first").fullDetail());
        assertEquals(store.currentId(), row(changed, "current").session());
    }

    @Test public void theLargeHistoryReadsOffTheEventDispatchThread() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.writeLarge(root, HomeHistoryFixture.LARGE_SESSIONS, HomeHistoryFixture.LARGE_RUNS);
        CombatFixtures.writeLarge(root, HomeHistoryFixture.LARGE_SESSIONS, HomeHistoryFixture.LARGE_RUNS);
        int runs = HomeHistoryFixture.LARGE_SESSIONS * HomeHistoryFixture.LARGE_RUNS;
        RecordingsSource source = source(store(root, false));
        AtomicReference<Throwable> onEdt = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { source.read(all(), new Cancellation()); } catch (Throwable t) { onEdt.set(t); } });
        assertTrue(String.valueOf(onEdt.get()), onEdt.get() instanceof IllegalStateException);
        assertFalse(SwingUtilities.isEventDispatchThread());
        long cold = System.nanoTime();
        RecordingsSource.Result first = source.read(RecordingsQuery.defaults(), new Cancellation());
        long coldMillis = (System.nanoTime() - cold) / 1_000_000, warm = System.nanoTime();
        RecordingsSource.Result second = source.read(RecordingsQuery.defaults(), new Cancellation());
        long warmMillis = (System.nanoTime() - warm) / 1_000_000;
        System.out.println("RecordingsSource.read large history (" + HomeHistoryFixture.LARGE_SESSIONS + " sessions x " + HomeHistoryFixture.LARGE_RUNS
            + " runs, " + runs + " records): cold " + coldMillis + " ms, warm " + warmMillis + " ms (soft bound 250 ms warm)");
        assertEquals(runs, first.items().size()); assertEquals(first.items(), second.items());
        for (RecordingItem item : first.items()) {
            assertEquals(RecordingItem.Kind.SAVED, item.kind()); assertEquals(RecordingItem.Link.LINKED, item.link());
            assertTrue(item.summarySaved()); assertEquals(RecordingItem.FullDetail.NONE, item.fullDetail()); assertNotNull(item.localDps());
        }
        RecordingItem newest = first.items().get(0);
        assertEquals(CombatFixtures.largeRecordingId(HomeHistoryFixture.id("large-0"), "L0-39"), newest.recordingId());
        assertTrue("Soft bounds; the times are recorded from the line above", coldMillis < 10_000 && warmMillis < 2_000);
    }

    // ---- fixtures ----

    /** A small recording of plain entities: the user (object 1) and another player hit two enemies for 10 s; {@code entered} null is legacy. */
    static DpsData plain(String map, VisitRef visit, Long entered, Integer localId, int damage) {
        MapInfoPacket info = new MapInfoPacket(); info.name = info.displayName = map;
        long start = entered == null ? NOW - DAY : entered + 5_000;
        Entity self = new Entity(null, 1, start); self.objectType = 782; self.markPlayerIdentity(); self.setUser(7);
        Entity other = new Entity(null, 2, start); other.objectType = 775; other.markPlayerIdentity();
        HashMap<Integer, Entity> hits = new HashMap<>();
        for (int e = 0; e < 2; e++) {
            Entity enemy = new Entity(null, 100 + e, start); enemy.objectType = 5000 + e; hits.put(enemy.id, enemy);
            for (int s = 0; s < 10; s++) {
                enemy.genericDamageHit(self, new Projectile(damage), start + s * 1000L);
                enemy.genericDamageHit(other, new Projectile(damage / 2), start + s * 1000L + 500);
                enemy.updateDamageTaken(start + s * 1000L + 500);
            }
        }
        return entered == null ? new DpsData(info, hits, new ArrayList<>(), 10_000, start, null)
            : new DpsData(info, hits, new ArrayList<>(), 10_000, start, null, self, new EncounterContext(visit, localId, entered));
    }
    private static CombatRecord record(DpsData data) { return CombatSummaries.build(data).record(); }
    /** A bare saved record: only its ID, entry time and full-detail flag (everything else unknown or zero as saved). */
    private static CombatRecord saved(String id, Long entered, boolean fullDetail) {
        CombatRecord record = new CombatRecord(); record.recordingId = id; record.enteredAt = entered; record.fullDetail = fullDetail; return record;
    }
    private static Path writeDps(Path file, DpsData data) throws IOException {
        Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(data); }
        return file;
    }
}
