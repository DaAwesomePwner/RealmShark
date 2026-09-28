package tomato;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.TomatoData;
import static org.junit.Assert.*;

/** Capture stop closes the open encounter on the producer when its loop ends, for the current worker only; a stop request never does. */
public class CapturePublicationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void terminationClosesTheOpenEncounterForTheCurrentWorkerOnly() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        TomatoData data = new TomatoData() {
            @Override public void captureStopped() { calls.add("stopped"); super.captureStopped(); }
            @Override public void captureTerminated() { calls.add("closed on " + Thread.currentThread().getName()); super.captureTerminated(); }
        };
        try (DiscoveryLog log = new DiscoveryLog(temp.newFolder().toPath())) {
            log.setSaving(false);
            CapturePublication publication = new CapturePublication(data, log);
            PacketProcessor first = new PacketProcessor(), retired = new PacketProcessor(), second = new PacketProcessor();
            publication.started(first);
            publication.stopRequested(first);   // the EDT's stop request, while the producer may still dispatch
            assertEquals("A stop request never touches encounter state", List.of("stopped"), calls);
            publication.terminated(retired);
            assertEquals("Another worker's termination is ignored", List.of("stopped"), calls);
            Thread producer = new Thread(() -> publication.terminated(first), "synthetic producer");
            producer.start(); producer.join();
            assertEquals(List.of("stopped", "closed on synthetic producer"), calls);
            publication.terminated(first);
            assertEquals("Once per worker", 2, calls.size());

            publication.started(second);
            Thread failed = new Thread(() -> publication.terminated(second), "failed producer");   // an unexpected end, no stop request
            failed.start(); failed.join();
            assertEquals(List.of("stopped", "closed on synthetic producer", "stopped", "closed on failed producer"), calls);
            assertEquals("Nothing was captured, so nothing was recorded", 0, data.dpsData.size());
        }
    }
}
