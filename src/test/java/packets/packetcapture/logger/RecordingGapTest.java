package packets.packetcapture.logger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.incoming.NewTickPacket;
import tomato.history.SessionStore;

import static org.junit.Assert.*;

/** Defence in depth: a silence longer than the split threshold ends the interval even without any hook. */
public class RecordingGapTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void aLongSilenceSplitsTheIntervalAndTransportHooksCloseIt() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        DiscoveryLog log = new DiscoveryLog(temp.newFolder("discovery").toPath());
        boolean closed = false;
        try {
            // A wide margin (1.4 s silence vs a 1 s threshold) and count-independent assertions keep this
            // stable under GC pauses or a loaded runner, where an extra split may occur but never a merge.
            log.setSaving(false); log.attachHistory(store); log.intervalGapMillis(1000);
            frame(log); Thread.sleep(5); frame(log);
            Thread.sleep(700); long silent = System.currentTimeMillis(); Thread.sleep(700);
            frame(log); Thread.sleep(5); frame(log);
            log.captureInterrupted();
            Thread.sleep(5); frame(log);
            log.close(); closed = true;
            store.flush();
            SessionStore.ModuleAvailability timeline = null;
            for (SessionStore.SessionEntry entry : store.catalog()) if (entry.id.equals(store.currentId())) timeline = entry.availability("timeline");
            assertNotNull(timeline);
            assertTrue("Silence and the transport hook each end an interval", timeline.intervals.size() >= 3);
            assertEquals("No frames observed for over 1000 ms", timeline.intervals.get(0).end);
            boolean interrupted = false;
            for (SessionStore.Interval interval : timeline.intervals) interrupted |= "Capture interrupted or reopened".equals(interval.end);
            assertTrue("The transport hook closes the interval it interrupts", interrupted);
            assertEquals(Boolean.FALSE, timeline.recordedAt(silent));
        } finally { if (!closed) log.close(); store.close(); }
    }

    private static void frame(DiscoveryLog log) { log.observe(PacketType.NEWTICK.getIndex(), 20, new NewTickPacket(), "decoded", 0); }
}
