package tomato.history.archive;

import com.google.gson.JsonObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;

import java.nio.file.Path;

import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** UX-04/05: export manifests state per-session recording intervals, or explicit unknown coverage. */
public class ExportCoverageManifestTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void manifestCarriesDeclaredIntervalsAndExplicitUnknownForLegacySessions() throws Exception {
        Path root = temp.newFolder().toPath();
        String legacy = session(root, 3); // Written before availability tracking.
        try (SessionStore store = new SessionStore(root, true, "synthetic")) {
            store.append("chat", new Event(10, 1, "even", "Message current"));
            store.recordInterval("chat", 5, 20, "Collection paused").toCompletableFuture().get();
            store.flush();
            try (ArchiveResult<Event> result = ArchiveResult.open(store, query(SessionStore.ALL), adapter(), temp.newFolder().toPath(), new Cancellation())) {
                JsonObject manifest = result.manifest();
                assertTrue("Older readers still find a string", manifest.get("coverage").getAsJsonPrimitive().isString());
                JsonObject coverage = manifest.getAsJsonObject("recordingCoverage");
                JsonObject unknown = coverage.getAsJsonObject(legacy).getAsJsonObject("chat");
                assertEquals("UNKNOWN", unknown.get("state").getAsString());
                assertTrue(unknown.get("reason").getAsString().contains("coverage unknown"));
                JsonObject declared = coverage.getAsJsonObject(store.currentId()).getAsJsonObject("chat");
                assertEquals("PARTIAL", declared.get("state").getAsString());
                JsonObject interval = declared.getAsJsonArray("intervals").get(0).getAsJsonObject();
                assertEquals(5, interval.get("from").getAsLong()); assertEquals(20, interval.get("until").getAsLong());
                assertEquals("Collection paused", interval.get("end").getAsString());
                assertFalse(declared.get("truncated").getAsBoolean());
                assertEquals(2, coverage.size());
            }
        }
    }
}
