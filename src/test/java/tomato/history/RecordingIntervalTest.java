package tomato.history;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.incoming.NewTickPacket;
import packets.packetcapture.logger.DiscoveryLog;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** UX-05: producer collection intervals persist as coverage, so "not recorded" differs from "recorded, empty". */
public class RecordingIntervalTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void collectionIntervalsDistinguishRecordedFromNotRecordedAndSurviveSummaries() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        DiscoveryLog log = new DiscoveryLog(temp.newFolder("discovery").toPath());
        boolean closed = false;
        try {
            log.setSaving(false); log.attachHistory(store);
            long beforeAny = System.currentTimeMillis(); Thread.sleep(15);
            frame(log); long firstInside = System.currentTimeMillis(); Thread.sleep(15); frame(log);
            log.setEnabled(false);
            Thread.sleep(15); long paused = System.currentTimeMillis(); Thread.sleep(15);
            log.setEnabled(true);
            Thread.sleep(15); long resumedBeforeFrames = System.currentTimeMillis(); Thread.sleep(15);
            frame(log); long secondInside = System.currentTimeMillis(); frame(log);
            log.boundary();
            log.close(); closed = true;
            store.flush();
            SessionStore.ModuleAvailability runs = entry(store).availability("runs");
            assertEquals(SessionStore.ModuleAvailability.State.PARTIAL, runs.state);
            assertEquals(2, runs.intervals.size());
            assertEquals(Arrays.asList("Collection paused", "Connection boundary"),
                Arrays.asList(runs.intervals.get(0).end, runs.intervals.get(1).end));
            assertEquals(Boolean.TRUE, runs.recordedAt(firstInside));
            assertEquals(Boolean.TRUE, runs.recordedAt(secondInside));
            assertEquals("Paused: not recorded, not empty", Boolean.FALSE, runs.recordedAt(paused));
            assertEquals("Enabled but nothing observed is not claimed as recorded", Boolean.FALSE, runs.recordedAt(resumedBeforeFrames));
            assertEquals(Boolean.FALSE, runs.recordedAt(beforeAny));
            assertEquals(2, entry(store).availability("timeline").intervals.size());

            // A later summary without intervals keeps the richer interval evidence.
            store.availability("runs", new SessionStore.ModuleAvailability(SessionStore.ModuleAvailability.State.PARTIAL, "Summary", 1L, 2L))
                .toCompletableFuture().get();
            SessionStore.ModuleAvailability kept = entry(store).availability("runs");
            assertEquals("Summary", kept.reason); assertEquals(2, kept.intervals.size());
            assertNull("Modules without evidence stay unknown", entry(store).availability("chat").recordedAt(firstInside));
        } finally { if (!closed) log.close(); store.close(); }
    }

    @Test public void intervalsMergeBySameStartAndTruncationMakesOlderTimesUnknown() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder("bounded").toPath(), true, "synthetic")) {
            store.recordInterval("runs", 100, 200, "Open; collection continuing").toCompletableFuture().get();
            store.recordInterval("runs", 100, 300, "Collection paused").toCompletableFuture().get();
            SessionStore.ModuleAvailability merged = entry(store).availability("runs");
            assertEquals(1, merged.intervals.size()); assertEquals(300, merged.intervals.get(0).until);
            for (int i = 1; i <= SessionStore.ModuleAvailability.INTERVAL_LIMIT; i++)
                store.recordInterval("runs", 1000L * i, 1000L * i + 10, "Connection boundary").toCompletableFuture().get();
            SessionStore.ModuleAvailability bounded = entry(store).availability("runs");
            assertEquals(SessionStore.ModuleAvailability.INTERVAL_LIMIT, bounded.intervals.size());
            assertTrue(bounded.truncated);
            assertNull("Dropped history is unknown, not unrecorded", bounded.recordedAt(150));
            assertEquals(Boolean.FALSE, bounded.recordedAt(1500));
        }
    }

    @Test public void legacyAvailabilityWithoutIntervalsReadsAsUnknownCoverage() throws Exception {
        SessionStore.ModuleAvailability legacy = SessionStore.JSON.fromJson(
            "{\"schemaVersion\":1,\"state\":\"PARTIAL\",\"reason\":\"Observed interval; gaps unknown\",\"from\":1,\"until\":2}",
            SessionStore.ModuleAvailability.class);
        assertNull(legacy.intervals); assertFalse(legacy.truncated);
        assertNull(legacy.recordedAt(1));
    }

    private static void frame(DiscoveryLog log) { log.observe(PacketType.NEWTICK.getIndex(), 20, new NewTickPacket(), "decoded", 0); }
    private static SessionStore.SessionEntry entry(SessionStore store) throws Exception {
        List<SessionStore.SessionEntry> entries = store.catalog();
        for (SessionStore.SessionEntry entry : entries) if (entry.id.equals(store.currentId())) return entry;
        throw new AssertionError("Current session missing from catalog");
    }
}
