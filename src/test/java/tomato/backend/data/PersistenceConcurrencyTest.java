package tomato.backend.data;

import com.google.gson.Gson;
import java.io.FilterReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PersistenceConcurrencyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void blockedJournalSaveAllowsReadsAndEditsAndKeepsNewRevisionDirty() throws Exception {
        Path file = temp.getRoot().toPath().resolve("journal.json");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        CharacterJournal journal = new CharacterJournal(file, (path, json) -> {
            if (writes.incrementAndGet() == 1) { entered.countDown(); await(release); }
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        });
        String account = journal.observe(CharacterJournalTest.player("concurrency", 782), 1);
        String key = account + ":1";
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> saving = threads.submit(journal::save);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            threads.submit(() -> {
                assertEquals(1, journal.characters().size());
                journal.notes(key, "Arrived during I/O");
                journal.markDead(key, true);
            }).get(2, TimeUnit.SECONDS);
            release.countDown(); saving.get(2, TimeUnit.SECONDS);
            assertTrue(journal.storageStatus().startsWith("Saving"));
            CharacterJournal first = new CharacterJournal(file);
            assertEquals("", first.characters().get(0).notes);
            assertFalse(first.characters().get(0).dead);
            journal.save();
            assertTrue(journal.storageStatus().startsWith("Saved"));
            CharacterJournal second = new CharacterJournal(file);
            assertEquals("Arrived during I/O", second.characters().get(0).notes);
            assertTrue(second.characters().get(0).dead);
            assertEquals(2, writes.get());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test public void concurrentJournalSavesAndCloseCannotOverwriteNewerObservations() throws Exception {
        Path file = temp.getRoot().toPath().resolve("ordered.json");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> savedNotes = Collections.synchronizedList(new ArrayList<>());
        CharacterJournal journal = new CharacterJournal(file, (path, json) -> {
            if (savedNotes.isEmpty()) { entered.countDown(); await(release); }
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
            savedNotes.add(new CharacterJournal(path).characters().get(0).notes);
        });
        String key = journal.observe(CharacterJournalTest.player("ordered", 782), 1) + ":1";
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = threads.submit(journal::save);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            journal.notes(key, "Latest");
            Future<?> second = threads.submit(journal::save);
            Future<?> closing = threads.submit(journal::close);
            // close() must not hold the model lock while waiting for the first writer.
            CompletableFuture<Void> read = CompletableFuture.runAsync(() -> assertEquals("Latest", journal.characters().get(0).notes));
            read.get(2, TimeUnit.SECONDS);
            release.countDown();
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS); closing.get(2, TimeUnit.SECONDS);
            assertEquals(Arrays.asList("", "Latest"), savedNotes);
            assertEquals("Latest", new CharacterJournal(file).characters().get(0).notes);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test public void journalCopiesDetachEveryMutableArrayAndPreserveUnknownValues() {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("copies.json"));
        String account = journal.observe(CharacterJournalTest.player("copy", 782), 7);
        journal.exalts(account, Collections.singletonMap(782, new int[]{1,2,3,4,5,6,7,8}));
        CharacterJournal.CharacterRecord row = journal.characters().get(0);
        row.stats[0] = 99; row.equipment[0] = 42; row.notes = "External";
        CharacterJournal.AccountRecord a = journal.accounts().get(0);
        a.exalts.get(782)[0] = 100; a.exalts.clear(); a.name = "External";
        assertNull(journal.characters().get(0).stats[0]);
        assertNull(journal.characters().get(0).equipment[0]);
        assertEquals("", journal.characters().get(0).notes);
        assertEquals(1, journal.accounts().get(0).exalts.get(782)[0]);
        assertEquals("Sample", journal.accounts().get(0).name);
    }

    @Test public void dungeonSaveDoesNotHoldModelLockAndKeepsLegacyCountersAndSynchronousContract() throws Exception {
        Path file = temp.getRoot().toPath().resolve("dungeon.stats");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        DungeonStatData data = new DungeonStatData(file, (path, json) -> {
            if (writes.incrementAndGet() == 1) { entered.countDown(); await(release); }
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        });
        Entity mob = new Entity(null, 1, 0); mob.objectType = 100;
        data.updateEntityDamage("First", mob);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> saving = threads.submit(() -> data.updateDungeon("First", 1200));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(saving.isDone());
            threads.submit(() -> {
                assertEquals(1, data.snapshot().get(0).visits);
                data.updateEntityDamage("Second", mob);
                assertEquals(2, data.snapshot().size());
            }).get(2, TimeUnit.SECONDS);
            Future<?> second = threads.submit(() -> data.updateDungeon("Second", 2400));
            release.countDown(); saving.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertFalse(json.contains("saveLock")); assertFalse(json.contains("revision"));
            DungeonStatData reopened = new Gson().fromJson(json, DungeonStatData.class);
            assertEquals(2, reopened.snapshot().size());
            assertEquals(1200, reopened.data.get("First").getTotalTime());
            assertEquals(2400, reopened.data.get("Second").getTotalTime());
            assertEquals(Integer.valueOf(1), reopened.data.get("Second").getEntityDamaged().get(100));
            assertEquals(2, writes.get());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test public void pausedHistoryReadMergesConcurrentObservationsAndRetainsActiveVisit() throws Exception {
        Path file = temp.getRoot().toPath().resolve("history.stats");
        Files.write(file, dungeonHistory().getBytes(StandardCharsets.UTF_8));
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        DungeonStatData data = new DungeonStatData(file,
            (path, json) -> Files.write(path, json.getBytes(StandardCharsets.UTF_8)),
            path -> new FilterReader(Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                private boolean paused;
                @Override public int read(char[] buffer, int offset, int length) throws java.io.IOException {
                    if (!paused) { paused = true; reading.countDown(); await(release); }
                    return super.read(buffer, offset, length);
                }
            });
        Entity mob = new Entity(null, 1, 0); mob.objectType = 100;
        Entity bag = new Entity(null, 2, 0);
        CharacterJournalTest.put(bag, packets.data.enums.StatType.INVENTORY_0_STAT, 500);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> loading = threads.submit(data::load);
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            DungeonStatData.DungeonInfo active = threads.submit(() -> {
                data.updateEntityDamage("Shared", mob);
                data.updateItems("Shared", mob, bag);
                assertEquals(1, data.snapshot().size());
                return data.info;
            }).get(2, TimeUnit.SECONDS);
            release.countDown(); loading.get(2, TimeUnit.SECONDS);
            assertSame(active, data.info);
            assertEquals(2, data.snapshot().size());
            assertEquals(Integer.valueOf(11), data.info.getEntityDamaged().get(100));
            assertEquals(Integer.valueOf(5), data.info.getLoot(100).getItems().get(500));
            data.updateEntityDamage("Shared", mob);
            data.updateDungeon("Shared", 250);
            DungeonStatData saved = new Gson().fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), DungeonStatData.class);
            assertEquals(4, saved.data.get("Shared").getEnteredDungeon());
            assertEquals(1250, saved.data.get("Shared").getTotalTime());
            assertEquals(Integer.valueOf(12), saved.data.get("Shared").getEntityDamaged().get(100));
            assertEquals(7, saved.data.get("Historical only").getEnteredDungeon());
            data.load(); data.load();
            assertEquals(4, data.data.get("Shared").getEnteredDungeon());
            assertEquals(Integer.valueOf(12), data.data.get("Shared").getEntityDamaged().get(100));
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test public void firstDungeonSaveLoadsHistoryEvenWhenExplicitStartupLoadWasMissed() throws Exception {
        Path file = temp.getRoot().toPath().resolve("first-save.stats");
        Files.write(file, dungeonHistory().getBytes(StandardCharsets.UTF_8));
        DungeonStatData data = new DungeonStatData(file, (path, json) -> Files.write(path, json.getBytes(StandardCharsets.UTF_8)));
        Entity mob = new Entity(null, 1, 0); mob.objectType=100;
        data.updateEntityDamage("Shared", mob);
        data.updateDungeon("Shared", 200);
        DungeonStatData saved = new Gson().fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), DungeonStatData.class);
        assertEquals(4, saved.data.get("Shared").getEnteredDungeon());
        assertEquals(1200, saved.data.get("Shared").getTotalTime());
        assertEquals(Integer.valueOf(11), saved.data.get("Shared").getEntityDamaged().get(100));
        assertEquals(7, saved.data.get("Historical only").getEnteredDungeon());
        data.load(); assertEquals(4, data.data.get("Shared").getEnteredDungeon());
    }

    @Test public void failedInitialHistoryReadCannotOverwriteTheExistingFile() throws Exception {
        Path file = temp.getRoot().toPath().resolve("unreadable.stats");
        Files.write(file, dungeonHistory().getBytes(StandardCharsets.UTF_8));
        AtomicInteger writes = new AtomicInteger();
        DungeonStatData data = new DungeonStatData(file, (path, json) -> writes.incrementAndGet(),
            path -> { throw new java.io.IOException("Read unavailable"); });
        Entity mob = new Entity(null, 1, 0); mob.objectType=100;
        data.updateEntityDamage("Shared", mob);
        try { data.updateDungeon("Shared", 200); fail("A failed initial read must prevent saving"); }
        catch (RuntimeException expected) { assertTrue(expected.getCause() instanceof java.io.IOException); }
        assertEquals(0, writes.get());
        assertEquals(dungeonHistory(), new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(1, data.data.get("Shared").getEnteredDungeon());
    }

    private static String dungeonHistory() {
        return "{\"data\":{\"Shared\":{\"name\":\"Shared\",\"enteredDungeon\":3,\"totalTime\":1000,"
            + "\"entityDamaged\":{\"100\":10},\"entityLoot\":{\"100\":{\"lootList\":{\"500\":4}}}},"
            + "\"Historical only\":{\"name\":\"Historical only\",\"enteredDungeon\":7,\"totalTime\":7000,"
            + "\"entityDamaged\":{},\"entityLoot\":{}}}}";
    }

    private static void await(CountDownLatch latch) throws java.io.IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Test writer was not released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
    }
}
