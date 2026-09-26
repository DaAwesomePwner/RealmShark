package tomato.gui.logging;

import org.junit.Test;
import packets.PacketType;
import packets.incoming.NewTickPacket;
import packets.packetcapture.logger.DiscoveryCatalog;
import packets.packetcapture.logger.DiscoveryLog;
import packets.reader.BufferReader;
import tomato.gui.route.*;

import javax.swing.SwingUtilities;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** LOG-2: pause, decode failure, retention loss, cache loss and disk loss are separate evidence. */
public class CoverageExplanationTest {
    @Test public void pauseDecodeFailureRetentionAndCacheEvictionHaveDistinctCountersAndExplanations() {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            log.setSaving(false); log.setSampleMillis(0);
            int tick = PacketType.NEWTICK.getIndex();
            for (int i = 0; i < DiscoveryLog.EVENT_LIMIT + 5; i++) log.observe(tick, 20, new NewTickPacket(), "decoded", 0);
            log.decodeFailure(PacketType.UPDATE.getIndex(), 30, new BufferReader(ByteBuffer.wrap(new byte[]{1, 2})), new IllegalArgumentException("synthetic"));
            log.setEnabled(false);
            DiscoveryLog.Snapshot snapshot = log.snapshot();
            assertEquals("The decode-error sample also evicted one", 6, snapshot.retentionEvictions);
            assertEquals("Cache eviction is a different counter", 0, snapshot.cacheEvictions);
            assertEquals(DiscoveryLog.EVENT_LIMIT, snapshot.events.size());
            assertNotNull(snapshot.retainedFirst); assertTrue(snapshot.retainedLast >= snapshot.retainedFirst);
            assertNull("Paused collection has no open observed interval", snapshot.observedSince);
            assertEquals(1, snapshot.transitions.size());
            assertFalse(snapshot.transitions.get(0).collecting);
            assertEquals("Collection paused", snapshot.transitions.get(0).reason);

            String text = DiagnosticCoverage.describe(snapshot);
            assertTrue(text.contains("Retention evictions: 6 (older samples were dropped"));
            assertTrue(text.contains("Delta-cache evictions: 0 (comparison baseline lost; not an event-retention count"));
            assertTrue(text.contains("Decode failures: 1 (packet could not be decoded"));
            assertTrue(text.contains("Collection: paused; frames are not observed while paused, which is not zero activity"));
            assertTrue(text.contains("not collecting · Collection paused"));
            assertTrue(text.contains("Views affected by decode issues: Resources & buffs (1 frames); Inspect (1 frames); Loot (1 frames)"));
            assertTrue(text.contains("Diagnostic disk drops: 0 (writer lifetime"));
            assertTrue(text.contains("Sampled-out events: 0"));
            assertTrue(text.contains("Omitted stat deltas: 0"));
            assertFalse("No chat, credential or raw payload wording is invented", text.toLowerCase().contains("token"));

            log.setEnabled(true);
            log.observe(tick, 20, new NewTickPacket(), "decoded", 0);
            DiscoveryLog.Snapshot resumed = log.snapshot();
            assertNotNull(resumed.observedSince);
            assertTrue(resumed.transitions.get(1).collecting);
            log.clearDiagnostics();
            assertEquals(0, log.snapshot().retentionEvictions);
        }
    }

    @Test public void transitionsAreBoundedAndTheViewAllowlistNamesRealPackets() {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            log.setSaving(false);
            for (int i = 0; i < DiscoveryLog.TRANSITION_LIMIT + 10; i++) log.setEnabled(i % 2 != 0);
            assertEquals(DiscoveryLog.TRANSITION_LIMIT, log.snapshot().transitions.size());
        }
        for (String[] view : DiscoveryCatalog.AFFECTED_VIEWS) {
            Destination.valueOf(view[0]);
            for (String packet : view[2].split(", ")) assertNotNull(packet + " is a known packet", PacketType.valueOf(packet));
        }
        assertTrue(DiscoveryCatalog.affectedViews("TEXT").isEmpty());
        assertTrue(DiscoveryCatalog.packetsFor("CHAT").isEmpty());
    }

    @Test public void errorRouteFocusesAllowlistedPacketIssuesAndBackRestoresLogging() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            log.setSaving(false);
            edt(() -> {
                LoggingGUI logging = new LoggingGUI(log, LoggingStateTestSupport.memoryStore());
                LoggingRouteTarget target = new LoggingRouteTarget(logging);
                int[] page = {10};
                ShellNavigator navigator = new ShellNavigator(() -> page[0], value -> page[0] = value, d -> d == Destination.LOGGING ? 9 : 10, 5);
                navigator.register(target);
                assertFalse(navigator.canOpen(Route.to(Destination.LOGGING).withPayload(LoggingRouteTarget.packetFor(Destination.RUNS, "TEXT"))));
                assertFalse(navigator.canOpen(Route.to(Destination.LOGGING).withPayload(LoggingRouteTarget.issuesFor(Destination.NOTIFICATIONS))));
                assertFalse(navigator.canOpen(Route.to(Destination.LOGGING).withPayload("MAPINFO")));
                assertTrue(navigator.open(Route.to(Destination.LOGGING).withPayload(LoggingRouteTarget.issuesFor(Destination.RUNS))));
                assertEquals(9, page[0]);
                assertEquals("packets", logging.captureViewState().tab);
                assertTrue(logging.captureViewState().tabs.get("packets").query.issues);
                assertTrue(navigator.open(Route.to(Destination.LOGGING).withPayload(LoggingRouteTarget.packetFor(Destination.RUNS, "MAPINFO"))));
                assertEquals("MAPINFO", logging.captureViewState().tabs.get("packets").query.packet);
                assertTrue(navigator.back());
                assertTrue(logging.captureViewState().tabs.get("packets").query.issues);
                assertTrue(navigator.back());
                assertEquals(10, page[0]);
                return null;
            });
        }
    }
    private interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(body.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
