package tomato;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.incoming.NewTickPacket;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.TomatoData;
import tomato.history.SessionStore;

import static org.junit.Assert.*;

/** UX-05 review fix: a capture stop and later restart never produce one interval spanning the unobserved gap. */
public class CaptureCoverageTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void stopGapAndRestartYieldTwoIntervalsAndTheGapReadsNotRecorded() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        DiscoveryLog log = new DiscoveryLog(temp.newFolder("discovery").toPath());
        boolean closed = false;
        try {
            log.setSaving(false); log.attachHistory(store);
            CapturePublication publication = new CapturePublication(new TomatoData(), log);
            PacketProcessor first = new PacketProcessor(), second = new PacketProcessor();
            publication.started(first);
            frame(log); long firstInside = System.currentTimeMillis(); Thread.sleep(5); frame(log);
            publication.stopRequested(first); publication.terminated(first);
            Thread.sleep(20); long gap = System.currentTimeMillis(); Thread.sleep(20);
            publication.started(second); // Restarted without any connection reset reaching the collector.
            frame(log); long secondInside = System.currentTimeMillis(); Thread.sleep(5); frame(log);
            assertTrue(log.snapshot().transitions.toString(), log.snapshot().transitions.stream().anyMatch(t -> t.reason.startsWith("Capture stopped")));
            log.close(); closed = true;
            store.flush();
            SessionStore.ModuleAvailability runs = null;
            for (SessionStore.SessionEntry entry : store.catalog()) if (entry.id.equals(store.currentId())) runs = entry.availability("runs");
            assertNotNull(runs);
            assertEquals(2, runs.intervals.size());
            assertEquals("Capture stopped", runs.intervals.get(0).end);
            assertEquals(Boolean.TRUE, runs.recordedAt(firstInside));
            assertEquals("The stopped period is not recorded", Boolean.FALSE, runs.recordedAt(gap));
            assertEquals(Boolean.TRUE, runs.recordedAt(secondInside));
        } finally { if (!closed) log.close(); store.close(); }
    }

    private static void frame(DiscoveryLog log) { log.observe(PacketType.NEWTICK.getIndex(), 20, new NewTickPacket(), "decoded", 0); }
}
