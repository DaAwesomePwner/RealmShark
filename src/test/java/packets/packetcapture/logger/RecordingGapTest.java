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
            log.setSaving(false); log.attachHistory(store); log.intervalGapMillis(60);
            frame(log); Thread.sleep(5); frame(log);
            Thread.sleep(80); long silent = System.currentTimeMillis(); Thread.sleep(80);
            frame(log); Thread.sleep(5); frame(log);
            log.captureInterrupted();
            Thread.sleep(5); frame(log);
            log.close(); closed = true;
            store.flush();
            SessionStore.ModuleAvailability timeline = null;
            for (SessionStore.SessionEntry entry : store.catalog()) if (entry.id.equals(store.currentId())) timeline = entry.availability("timeline");
            assertNotNull(timeline);
            assertEquals(3, timeline.intervals.size());
            assertEquals("No frames observed for over 60 ms", timeline.intervals.get(0).end);
            assertEquals("Capture interrupted or reopened", timeline.intervals.get(1).end);
            assertEquals(Boolean.FALSE, timeline.recordedAt(silent));
        } finally { if (!closed) log.close(); store.close(); }
    }

    private static void frame(DiscoveryLog log) { log.observe(PacketType.NEWTICK.getIndex(), 20, new NewTickPacket(), "decoded", 0); }
}
