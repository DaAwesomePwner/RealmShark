package tomato.history;

import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.index.HistoryIndex;
import tomato.history.index.Kind;
import static org.junit.Assert.*;

public class AppHistoryIndexTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String historyDir, nativeDir;
    @Before public void setup() throws Exception {
        historyDir = System.getProperty("realmshark.historyDir");
        nativeDir = System.getProperty("realmshark.indexNativeDir");
        System.setProperty("realmshark.historyDir", temp.newFolder("history").toString());
        System.setProperty("realmshark.indexNativeDir", temp.newFolder("native").toString());
    }
    @After public void restore() {
        restore("realmshark.historyDir", historyDir);
        restore("realmshark.indexNativeDir", nativeDir);
    }
    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
    @Test public void startupWiringIndexesLiveRecordsAndRecoversFinalFlushOnRestart() throws Exception {
        Assume.assumeTrue("Only Windows natives ship", System.getProperty("os.name").startsWith("Windows"));
        SessionStore store = new SessionStore(AppHistory.directory(), true, "test");
        HistoryIndex index = AppHistory.startIndex(store, false);
        try {
            assertEquals(HistoryIndex.Phase.READY, index.start().get(30, TimeUnit.SECONDS).phase());
            store.append("timeline", Map.of("kind", "Boss", "detail", "Synthetic first"));
            store.put("runs", "visit", Map.of("id", "visit", "map", "Synthetic dungeon"));
            store.flush(); index.flush().get(30, TimeUnit.SECONDS);
            assertTrue(index.ready(store.currentId()));
            assertEquals(1L, (long) index.rowCounts().get("timeline"));
            assertEquals(store.currentId(), index.search("Synthetic", Set.of(Kind.RUN), 10).get(0).session());
            store.collect("final", () -> {
                if (store.closing()) store.append("timeline", Map.of("kind", "Boss", "detail", "Synthetic final"));
            });
            store.close();
            long before = System.nanoTime();
            AppHistory.closeIndex(index);
            assertTrue("Index shutdown stays bounded", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
            index.closeAsync().get(30, TimeUnit.SECONDS);
            try (SessionStore next = new SessionStore(AppHistory.directory(), false, "test")) {
                HistoryIndex reopened = new HistoryIndex(next, true);
                try {
                    assertEquals(HistoryIndex.Phase.READY, reopened.start().get(30, TimeUnit.SECONDS).phase());
                    assertEquals(2L, (long) reopened.rowCounts().get("timeline"));
                } finally { reopened.closeAsync().get(30, TimeUnit.SECONDS); }
            }
        } finally { store.close(); index.closeAsync().get(30, TimeUnit.SECONDS); }
    }
    @Test public void previewDoesNotCreateAnIndex() throws Exception {
        try (SessionStore store = new SessionStore(AppHistory.directory(), false, "test")) {
            assertNull(AppHistory.startIndex(store, true));
            assertFalse(Files.exists(AppHistory.directory().resolve("index")));
        }
        AppHistory.closeIndex(null);
    }
    @Test public void blockedIndexCloseNeverUsesAnUnboundedWait() {
        CompletableFuture<Void> blocked = new CompletableFuture<>();
        long before = System.nanoTime();
        AppHistory.awaitIndexClose(blocked);
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
        assertFalse("Timeout leaves the daemon writer free to finish", blocked.isDone());
    }
    @Test public void interruptedShutdownPreservesInterrupt() {
        Thread.currentThread().interrupt();
        try {
            AppHistory.awaitIndexClose(new CompletableFuture<>());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
