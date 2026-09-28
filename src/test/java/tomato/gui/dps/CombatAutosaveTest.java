package tomato.gui.dps;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.Packet;
import packets.incoming.MapInfoPacket;
import packets.incoming.TextPacket;
import tomato.backend.data.*;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.encounter.*;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** The combat history worker: closed recordings saved off the producer as a record and a detail, optional full detail, pruning. */
public class CombatAutosaveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final CombatSettings.Values SUMMARIES = new CombatSettings.Values(false, 30, null), FULL = new CombatSettings.Values(true, 30, null);
    private static final String CHAT = "synthetic chat line that must never be saved";
    private final List<AutoCloseable> closing = new ArrayList<>();

    @After public void close() throws Exception { for (int i = closing.size() - 1; i >= 0; i--) closing.get(i).close(); }

    private SessionStore store(boolean writable) throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), writable, "synthetic"); closing.add(store); return store;
    }
    private CombatAutosave autosave(SessionStore store, Supplier<CombatSettings.Values> settings) {
        CombatAutosave autosave = new CombatAutosave(store, settings, System::currentTimeMillis); closing.add(autosave); return autosave;
    }
    private static DpsData fight(SessionStore store, String visit) {
        return CombatFixtures.typical(new VisitRef(store.currentId(), visit), 1_700_000_000_000L, 20, 3, 6);
    }
    /**
     * A fight of plain entities, as capture records them, so it can be serialized as full detail (CombatFixtures' enemies are
     * anonymous subclasses that hold their builder): one verified local player hitting three enemies for 10 s.
     */
    private static DpsData plainFight(SessionStore store, String visit) {
        long start = 1_700_000_000_000L;
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Lost Halls";
        Entity player = new Entity(null, 1, 0); player.objectType = 782; player.markPlayerIdentity(); player.setUser(7);
        HashMap<Integer, Entity> hits = new HashMap<>();
        for (int e = 0; e < 3; e++) {
            Entity enemy = new Entity(null, 100 + e, 0); enemy.objectType = 5000 + e; hits.put(enemy.id, enemy);
            for (int s = 0; s < 10; s++) {
                Projectile projectile = new Projectile(100 + s); projectile.setSource(DamageSource.WEAPON, 10_000);
                enemy.genericDamageHit(player, projectile, start + s * 1000L); enemy.updateDamageTaken(start + s * 1000L);
            }
        }
        return new DpsData(map, hits, new ArrayList<>(), 10_000, start, null, player,
            new EncounterContext(new VisitRef(store.currentId(), visit), 1, start - 5_000));
    }
    private static List<CombatRecord> records(SessionStore store) throws Exception {
        store.flush();
        List<CombatRecord> read = new ArrayList<>();
        CombatFacts.read(store, store.catalog(), store.currentId(), read::add);
        return read;
    }
    private static CombatRecord record(SessionStore store, String id) throws Exception {
        for (CombatRecord record : records(store)) if (record.recordingId.equals(id)) return record;
        return null;
    }

    private static void awaitQuietly(CountDownLatch latch, long millis) {
        try { latch.await(millis, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    /** Every file the session holds for {@code id}: its record, its detail and its full detail. */
    private static List<Path> savedFiles(Path session, String id) {
        String name = SessionStore.checkpointName(id);
        List<Path> found = new ArrayList<>();
        for (Path file : List.of(session.resolve(CombatFacts.RECORDS).resolve(name + ".json"),
                session.resolve(CombatFacts.DETAILS).resolve(name + ".json"), CombatAutosave.fullDetailFile(session, id)))
            if (Files.exists(file)) found.add(file);
        return found;
    }

    /** Shutdown: closing waits for the queued fights, so they are saved before the history store closes. */
    @Test public void closeWaitsForQueuedSavesBeforeTheStoreCloses() throws Exception {
        SessionStore store = store(true);
        CountDownLatch release = new CountDownLatch(1);
        CombatAutosave autosave = new CombatAutosave(store, () -> { awaitQuietly(release, 5_000); return SUMMARIES; },
            System::currentTimeMillis, 10_000);
        closing.add(autosave);
        DpsData first = fight(store, "v1"), second = fight(store, "v2");
        autosave.submit(first); autosave.submit(second);
        Thread slow = new Thread(() -> { try { Thread.sleep(400); } catch (InterruptedException e) { return; } release.countDown(); });
        slow.start();
        long started = System.nanoTime();
        autosave.close();
        assertTrue("close waited for the slow save", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 300);
        assertNotNull("queued fights are saved before close returns", record(store, first.getRecordingId()));
        assertNotNull(record(store, second.getRecordingId()));
    }

    /** Closing gives up after its drain: the fight being saved and the queued one leave no record, detail or full detail. */
    @Test public void fightsNotSavedWhenClosingGivesUpLeaveNoPartialFiles() throws Exception {
        SessionStore store = store(true);
        Path session = store.currentDirectory().orElseThrow();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        CombatAutosave autosave = new CombatAutosave(store, () -> { blocked.countDown(); awaitQuietly(release, 10_000); return FULL; },
            System::currentTimeMillis, 100);
        closing.add(autosave);
        DpsData first = plainFight(store, "v1"), second = plainFight(store, "v2");
        autosave.submit(first); autosave.submit(second);
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        autosave.close();   // the drain passes while the first save is blocked: both are cancelled
        release.countDown();
        store.flush();
        assertEquals("The fight being saved commits nothing", List.of(), savedFiles(session, first.getRecordingId()));
        assertEquals("The queued fight is dropped", List.of(), savedFiles(session, second.getRecordingId()));
    }

    /** A fight cancelled after its full detail was written, before it committed: the file goes too. */
    @Test public void aFullDetailFileOfAFightCancelledBeforeItsCommitIsDeleted() throws Exception {
        SessionStore store = store(true);
        Path session = store.currentDirectory().orElseThrow();
        CombatAutosave autosave = autosave(store, () -> FULL);
        DpsData fight = plainFight(store, "v1");
        Path file = CombatAutosave.fullDetailFile(session, fight.getRecordingId());
        java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean();
        autosave.beforeCommit = () -> { written.set(Files.exists(file)); autosave.cancelSaves(); };
        autosave.submit(fight);
        assertTrue(autosave.flush(10_000));
        store.flush();
        assertTrue("the full detail was written before the commit", written.get());
        assertEquals(List.of(), savedFiles(session, fight.getRecordingId()));
    }

    /** A fight that reaches its commit while the history store is closing (its puts would be dropped) commits nothing. */
    @Test public void aFightThatFindsTheStoreClosingCommitsNothing() throws Exception {
        SessionStore store = store(true);
        Path session = store.currentDirectory().orElseThrow();
        CombatAutosave autosave = autosave(store, () -> FULL);
        DpsData fight = plainFight(store, "v1");
        autosave.beforeCommit = store::close;
        autosave.submit(fight);
        assertTrue(autosave.flush(10_000));
        assertEquals(List.of(), savedFiles(session, fight.getRecordingId()));
    }

    @Test public void closedRecordingsAreSavedOnTheCombatWorkerWithoutBlockingTheProducer() throws Exception {
        SessionStore store = store(true);
        CountDownLatch release = new CountDownLatch(1);
        List<String> threads = new CopyOnWriteArrayList<>();
        CombatAutosave autosave = autosave(store, () -> {
            threads.add(Thread.currentThread().getName());
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return SUMMARIES;
        });
        DpsData first = fight(store, "1:1"), second = fight(store, "1:2");
        long started = System.nanoTime();
        autosave.submit(first); autosave.submit(second);
        assertTrue("submit only queues, even while the worker is busy", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
        release.countDown();
        assertTrue(autosave.flush(10_000));
        assertEquals(List.of(CombatAutosave.THREAD, CombatAutosave.THREAD), threads);
        CombatRecord saved = record(store, first.getRecordingId());
        assertNotNull(saved);
        assertEquals("The builder's record, as built from the closed recording",
            SessionStore.JSON.toJson(CombatSummaries.build(first).record()), SessionStore.JSON.toJson(saved));
        assertEquals(new VisitRef(store.currentId(), "1:1"), saved.visit());
        assertFalse(saved.fullDetail);
        CombatDetail detail = CombatFacts.detail(store, store.currentId(), second.getRecordingId());
        assertNotNull("The detail is saved beside the record", detail);
        assertEquals(SessionStore.JSON.toJson(CombatSummaries.build(second).detail()), SessionStore.JSON.toJson(detail));
        assertEquals(2, records(store).size());
        assertFalse("Summaries only: no full detail", Files.exists(store.currentDirectory().get().resolve(CombatRetention.FULL_DETAIL)));
    }

    @Test public void previewSavesNothing() throws Exception {
        SessionStore preview = store(false);
        List<String> asked = new CopyOnWriteArrayList<>();
        CombatAutosave autosave = autosave(preview, () -> { asked.add("settings"); return FULL; });
        autosave.submit(fight(preview, "1:1"));
        autosave.prune();
        assertTrue(autosave.flush(10_000));
        assertEquals("Nothing is built or written for a read-only history", List.of(), asked);
        try (Stream<Path> files = Files.walk(preview.directory())) {
            assertEquals(List.of(), files.filter(Files::isRegularFile).collect(Collectors.toList()));
        }
        assertEquals(Optional.empty(), preview.currentDirectory());
    }

    @Test public void fullDetailKeepsTheRecordingWithoutItsDebugPackets() throws Exception {
        SessionStore store = store(true);
        CombatAutosave autosave = autosave(store, () -> FULL);
        DpsData data = plainFight(store, "1:1");
        TextPacket chat = new TextPacket(); chat.name = "Synthetic"; chat.text = CHAT;
        data.debugPackets = new ArrayList<Packet>(List.of(chat));
        autosave.submit(data);
        assertTrue(autosave.flush(10_000));
        CombatRecord saved = record(store, data.getRecordingId());
        assertTrue("The record says full detail was kept", saved.fullDetail);
        Path file = CombatAutosave.fullDetailFile(store.currentDirectory().get(), data.getRecordingId());
        assertEquals(store.currentDirectory().get().resolve(CombatRetention.FULL_DETAIL)
            .resolve(SessionStore.checkpointName(data.getRecordingId()) + ".dps"), file);
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals("Written atomically: no staging file is left", List.of(file), files.collect(Collectors.toList()));
        }
        byte[] bytes = Files.readAllBytes(file);
        assertFalse("The debug packet log (chat) is never saved", new String(bytes, StandardCharsets.ISO_8859_1).contains(CHAT));
        EncounterImport reopened = EncounterImport.read(file);
        assertEquals(data.getRecordingId(), reopened.data.getRecordingId());
        assertNull(reopened.data.debugPackets);
        assertEquals(data.hitList.size(), reopened.data.hitList.size());
        assertEquals(data.getEncounterContext().visit, reopened.data.getEncounterContext().visit);
        assertEquals("The in-memory recording keeps its own log", 1, data.debugPackets.size());
    }

    @Test public void fullDetailOffKeepsOnlyTheSummaries() throws Exception {
        SessionStore store = store(true);
        CombatAutosave autosave = autosave(store, () -> SUMMARIES);
        DpsData data = fight(store, "1:1");
        autosave.submit(data);
        assertTrue(autosave.flush(10_000));
        assertFalse(record(store, data.getRecordingId()).fullDetail);
        assertNotNull(CombatFacts.detail(store, store.currentId(), data.getRecordingId()));
        assertFalse(Files.exists(store.currentDirectory().get().resolve(CombatRetention.FULL_DETAIL)));
    }

    @Test public void aFailedSaveNeverStopsLaterSaves() throws Exception {
        SessionStore store = store(true);
        CombatAutosave autosave = autosave(store, () -> FULL);
        CombatFixtures.Fight broken = CombatFixtures.fight("Lost Halls");
        DpsData unreadable = broken.context(null, null, 1L).build();
        unreadable.hitList.put(99, new Entity(null, 99, 0) {
            @Override public ArrayList<Damage> getDamageList() { throw new IllegalStateException("synthetic failure"); }
        });
        autosave.submit(unreadable);
        // Full detail cannot be written (a file blocks its folder): the summaries are still saved, marked without it.
        Path session = Files.createDirectories(store.currentDirectory().get());
        Files.write(session.resolve(CombatRetention.FULL_DETAIL), new byte[]{1});
        DpsData blocked = fight(store, "1:1");
        autosave.submit(blocked);
        assertTrue(autosave.flush(10_000));
        assertEquals(2, autosave.failures());
        CombatRecord saved = record(store, blocked.getRecordingId());
        assertNotNull("A later save still runs", saved);
        assertFalse("It says no full detail was kept", saved.fullDetail);
        Files.delete(session.resolve(CombatRetention.FULL_DETAIL));
        DpsData later = plainFight(store, "1:2");
        autosave.submit(later);
        assertTrue(autosave.flush(10_000));
        assertTrue(record(store, later.getRecordingId()).fullDetail);
        assertNull("The failed recording saved nothing", record(store, unreadable.getRecordingId()));
    }

    @Test public void startPrunesNowAndAfterEverySettingsChangeUntilClosed() throws Exception {
        SessionStore store = store(true);
        String closed = HomeHistoryFixture.id("combat-autosave-closed");
        long now = System.currentTimeMillis();
        HomeHistoryFixture.session(store.directory(), closed, now - 100 * 86_400_000L, now - 99 * 86_400_000L);
        Path folder = Files.createDirectories(store.directory().resolve(closed).resolve(CombatRetention.FULL_DETAIL));
        Path first = oldFile(folder, "first", now);
        CombatAutosave autosave = CombatAutosave.start(store);   // default settings: full detail is kept 30 days
        try {
            assertTrue(autosave.flush(10_000));
            assertFalse("Pruned at start", Files.exists(first));
            assertEquals(1, autosave.lastPrune().files());
            Path second = oldFile(folder, "second", now);
            CombatSettings.changed();
            assertTrue(autosave.flush(10_000));
            assertFalse("Pruned again after a Combat history change", Files.exists(second));
            DpsData data = fight(store, "1:1");
            CombatAutosave.closed(data);
            assertTrue(autosave.flush(10_000));
            assertNotNull("The capture's hand-off reaches the started autosave", record(store, data.getRecordingId()));
        } finally { autosave.close(); }
        Path third = oldFile(folder, "third", now);
        CombatSettings.changed();
        DpsData after = fight(store, "1:2");
        CombatAutosave.closed(after);
        Thread.sleep(200);
        assertTrue("A closed autosave no longer listens", Files.exists(third));
        assertNull("…nor saves", record(store, after.getRecordingId()));
    }

    @Test public void clearDpsLogsSaysSavedCombatHistoryIsNotDeleted() {
        assertTrue(DpsGUI.CLEAR_LOGS_HELP, DpsGUI.CLEAR_LOGS_HELP.contains("this app run's encounter list"));
        assertTrue(DpsGUI.CLEAR_LOGS_HELP, DpsGUI.CLEAR_LOGS_HELP.contains("Saved combat history") && DpsGUI.CLEAR_LOGS_HELP.contains("is not deleted"));
    }

    private static Path oldFile(Path folder, String id, long now) throws Exception {
        Path file = Files.write(folder.resolve(SessionStore.checkpointName(id) + ".dps"), new byte[10]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - 40 * 86_400_000L));
        return file;
    }
}
