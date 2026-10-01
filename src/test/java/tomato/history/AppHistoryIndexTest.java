package tomato.history;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.index.HistoryIndex;
import tomato.history.index.Kind;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRetention;
import tomato.history.encounter.CombatSettings;
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
    @Test public void deleteRemovesRowsPostingsPlayersAndVisitsAndSameUuidCanReturn() throws Exception {
        Assume.assumeTrue("Only Windows natives ship", System.getProperty("os.name").startsWith("Windows"));
        try (SessionStore store = new SessionStore(AppHistory.directory(), true, "test")) {
            String source = "delete-fixture", id = importedId(source);
            store.importSnapshot(source, "Originalsession", 1, "runs", "old",
                Map.of("id", "old", "map", "Originaldungeon", "playerDamage", Map.of("player:Originalplayer", 1)));
            HistoryIndex index = AppHistory.startIndex(store, false);
            try {
                assertEquals(HistoryIndex.Phase.READY, index.start().get(30, TimeUnit.SECONDS).phase());
                assertTrue(index.ready(id));
                assertEquals(1L, (long) index.rowCounts().get("runs"));
                assertFalse(index.search("Originalplayer", Set.of(Kind.PLAYER), 10).isEmpty());
                store.delete(id); index.flush().get(30, TimeUnit.SECONDS);
                for (String table : List.of("runs", "visits", "player_occurrences", "players"))
                    assertEquals(table, "0", scalar(index, "SELECT count(*) FROM " + table));
                assertEquals("0", scalar(index, "SELECT count(*) FROM sessions WHERE id='" + id + "'"));
                for (String query : List.of("Originalsession", "Originaldungeon", "Originalplayer")) {
                    assertTrue(index.search(query, Set.of(Kind.SESSION, Kind.RUN, Kind.PLAYER), 10).isEmpty());
                    for (String fts : List.of("docs", "names"))
                        assertEquals("0", scalar(index, "SELECT count(*) FROM " + fts + " WHERE " + fts + " MATCH '" + query + "'"));
                }
                // Old import markers deliberately remain; a new item can recreate the same source session UUID.
                store.importSnapshot(source, "Returned", 1, "runs", "new", Map.of("id", "new", "map", "Returneddungeon"));
                awaitReady(index, id);
                assertEquals(id, index.search("Returneddungeon", Set.of(Kind.RUN), 10).get(0).session());
            } finally { index.closeAsync().get(30, TimeUnit.SECONDS); }
        }
    }

    @Test public void renameReindexesTheClosedSessionOnceAndSearchesItsNewLabel() throws Exception {
        Assume.assumeTrue("Only Windows natives ship", System.getProperty("os.name").startsWith("Windows"));
        try (SessionStore store = new SessionStore(AppHistory.directory(), true, "test")) {
            String id = importedId("rename-fixture");
            store.importSnapshot("rename-fixture", "Oldlabel", 1, "runs", "visit", Map.of("id", "visit"));
            HistoryIndex index = AppHistory.startIndex(store, false);
            try {
                index.start().get(30, TimeUnit.SECONDS);
                long replacements = index.replacementCount();
                String stamp = scalar(index, "SELECT stamp FROM sessions WHERE id='" + id + "'");
                store.rename(id, "Searchablerenamedlabel"); awaitReady(index, id);
                assertEquals(id, index.search("Searchablerenamedlabel", Set.of(Kind.SESSION), 10).get(0).session());
                assertTrue(index.search("Oldlabel", Set.of(Kind.SESSION), 10).isEmpty());
                assertNotEquals(stamp, scalar(index, "SELECT stamp FROM sessions WHERE id='" + id + "'"));
                assertEquals(replacements + 1, index.replacementCount());
                index.flush().get(30, TimeUnit.SECONDS);
                assertEquals(replacements + 1, index.replacementCount());
            } finally { index.closeAsync().get(30, TimeUnit.SECONDS); }
        }
    }

    @Test public void legacyImportAndCombatPruningUpdateTheRunningIndex() throws Exception {
        Assume.assumeTrue("Only Windows natives ship", System.getProperty("os.name").startsWith("Windows"));
        Path legacy = temp.newFolder("legacy").toPath();
        Files.createDirectories(legacy.resolve("logs/discovery"));
        Files.writeString(legacy.resolve("logs/discovery/activity-history.json"),
            "{\"visits\":[{\"id\":\"legacyvisit\",\"map\":\"Legacydungeon\",\"started\":1,\"ended\":2}],\"entries\":[]}");
        long day = 86_400_000L, now = 1_800_000_000_000L;
        try (SessionStore store = new SessionStore(AppHistory.directory(), true, "test")) {
            String combatId = importedId("combat-fixture");
            for (int age : new int[]{400, 10}) store.importSnapshot("combat-fixture", "Combat", now - 400 * day,
                CombatFacts.RECORDS, "record" + age, Map.of("recordingId", "record" + age,
                    "enteredAt", now - age * day, "map", age == 400 ? "Pruneddungeon" : "Keptdungeon"));
            HistoryIndex index = AppHistory.startIndex(store, false);
            try {
                assertEquals(HistoryIndex.Phase.READY, index.start().get(30, TimeUnit.SECONDS).phase());
                assertEquals(2L, (long) index.rowCounts().get("combat"));
                AppHistory.importLegacy(store, legacy);
                String legacyId = importedId("legacy-activity"); awaitReady(index, legacyId);
                assertEquals(HistoryIndex.Readiness.READY, index.sessionState(legacyId).readiness());
                assertEquals(legacyId, index.search("Legacydungeon", Set.of(Kind.RUN), 10).get(0).session());
                CombatRetention.Result result = CombatRetention.prune(store,
                    new CombatSettings.Values(false, 30, 90), now, new Cancellation());
                assertEquals(1, result.files()); assertEquals(0, result.sessionsSkipped());
                awaitReady(index, combatId);
                assertEquals(1L, (long) index.rowCounts().get("combat"));
                assertTrue(index.search("Pruneddungeon", Set.of(Kind.COMBAT), 10).isEmpty());
                assertEquals(combatId, index.search("Keptdungeon", Set.of(Kind.COMBAT), 10).get(0).session());
            } finally { index.closeAsync().get(30, TimeUnit.SECONDS); }
        }
    }

    private static String importedId(String source) {
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static void awaitReady(HistoryIndex index, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            index.flush().get(30, TimeUnit.SECONDS);
            if (index.ready(id)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertTrue(index.sessionState(id).toString(), index.ready(id));
    }
    private static String scalar(HistoryIndex index, String sql) throws Exception {
        try (Connection connection = index.readConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
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
