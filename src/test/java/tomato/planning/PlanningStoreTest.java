package tomato.planning;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class PlanningStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static PlanData.AccountPlan stock(long n) {
        PlanData.AccountPlan p = new PlanData.AccountPlan(); p.held.put(1, new PlanData.ManualHeld(n, 100, "manual")); return p;
    }
    @Test public void durableSnapshotsAreDetachedAndAccountsIndependent() throws Exception {
        Path file = temp.getRoot().toPath().resolve("plans.json");
        try (PlanningStore store = new PlanningStore(file)) {
            PlanData.AccountPlan p = stock(4); assertTrue(store.update("A", 0, p).get().saved); p.held.get(1).quantity = 99;
            assertEquals(4, store.snapshot("A").plan().held.get(1).quantity);
            PlanData.AccountPlan copy = store.snapshot("A").plan(); copy.held.clear();
            assertEquals(4, store.snapshot("A").plan().held.get(1).quantity);
            assertTrue(store.update("B", 0, stock(8)).get().saved);
            assertFalse(store.update("A", 0, stock(10)).get().saved);
        }
        try (PlanningStore store = new PlanningStore(file)) {
            // A queued no-op stale write is also a deterministic load barrier.
            assertFalse(store.update("A", 0, stock(11)).get().saved);
            assertEquals(4, store.snapshot("A").plan().held.get(1).quantity);
            assertEquals(8, store.snapshot("B").plan().held.get(1).quantity);
        }
    }
    @Test public void failedWriteRetainsDurableRevisionAndCanRetryDraft() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false); Path path = temp.getRoot().toPath().resolve("plans.json");
        try (PlanningStore store = new PlanningStore(path, (p, json) -> {
            if (fail.get()) throw new java.io.IOException("fixture"); Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        })) {
            assertTrue(store.update("A", 0, stock(4)).get().saved);
            byte[] durable = Files.readAllBytes(path); fail.set(true);
            PlanData.AccountPlan draft = stock(7);
            assertFalse(store.update("A", 1, draft).get().saved);
            assertEquals(1, store.snapshot("A").revision); assertArrayEquals(durable, Files.readAllBytes(path));
            fail.set(false); assertTrue(store.update("A", 1, draft).get().saved);
            assertEquals(7, store.snapshot("A").plan().held.get(1).quantity);
        }
    }
    @Test public void oversizedUtf8DraftPreservesFileRevisionAndCanRetry() throws Exception {
        Path file = temp.getRoot().toPath().resolve("plans.json");
        try (PlanningStore store = new PlanningStore(file)) {
            assertTrue(store.update("A", 0, stock(4)).get().saved);
            byte[] prior = Files.readAllBytes(file);
            char[] chars = new char[6 * 1024 * 1024]; java.util.Arrays.fill(chars, '\u20ac');
            PlanData.AccountPlan draft = stock(7);
            draft.held.put(1, new PlanData.ManualHeld(7, 100, new String(chars)));
            PlanningStore.SaveResult rejected = store.update("A", 1, draft).get();
            assertFalse(rejected.saved); assertTrue(rejected.message.contains("16 MiB"));
            assertEquals(1, store.snapshot("A").revision);
            assertArrayEquals(prior, Files.readAllBytes(file));
            assertTrue(store.update("A", 1, stock(7)).get().saved);
        }
        try (PlanningStore store = new PlanningStore(file)) {
            assertFalse(store.update("A", 0, stock(9)).get().saved);
            assertFalse(store.snapshot("A").readOnly);
            assertEquals(7, store.snapshot("A").plan().held.get(1).quantity);
        }
    }
    @Test public void unsupportedAndMalformedFilesArePreserved() throws Exception {
        for (String json : new String[]{"{broken", "{\"version\":2,\"accounts\":{}}", "{\"accounts\":{}}"}) {
            Path path = temp.newFile().toPath(); byte[] bytes = json.getBytes(StandardCharsets.UTF_8); Files.write(path, bytes);
            try (PlanningStore store = new PlanningStore(path)) {
                assertFalse(store.update("A", 0, stock(4)).get().saved);
                assertTrue(store.snapshot("A").readOnly); assertArrayEquals(bytes, Files.readAllBytes(path));
            }
        }
    }
    @Test public void doubleAllocationAndReducedDemandRejectAtomically() throws Exception {
        try (PlanningStore store = PlanningStore.memory()) {
            PlanData.AccountPlan p = stock(4);
            for (String id : new String[]{"a", "b"}) {
                PlanData.QuestPlanEntry q = new PlanData.QuestPlanEntry(); q.entryId = id; q.stableQuestId = id;
                q.requirementsKnown = true; q.repeatable = true; q.requirements.put(1, 2L); p.quests.put(id, q);
                p.reservations.put(id, new java.util.LinkedHashMap<>()); p.reservations.get(id).put(1, 2L);
            }
            assertTrue(store.update("A", 0, p).get().saved);
            p.held.get(1).quantity = 3; assertFalse(store.update("A", 1, p).get().saved);
            p.held.get(1).quantity = 4; p.quests.get("a").requirements.put(1, 1L);
            assertFalse(store.update("A", 1, p).get().saved);
            assertEquals(2L, (long)store.snapshot("A").plan().quests.get("a").requirements.get(1));
            p.reservations.get("a").put(1, 1L); assertTrue(store.update("A", 1, p).get().saved);
        }
    }
    @Test public void staleQueuedWritesCannotOverwriteAndCloseDrains() throws Exception {
        PlanningStore store = PlanningStore.memory();
        CompletableFuture<PlanningStore.SaveResult> a = store.update("A", 0, stock(1));
        CompletableFuture<PlanningStore.SaveResult> b = store.update("A", 0, stock(2)); store.close();
        assertTrue(a.get().saved); assertFalse(b.get().saved); assertEquals(1, store.snapshot("A").plan().held.get(1).quantity);
        assertFalse(store.update("A", 1, stock(3)).get().saved);
    }
}
