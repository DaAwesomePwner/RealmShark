package tomato.history;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * The shared per-session stamp and cache (Home's archive, the run feed's sessions, the Dungeons cards, Loot Highlights): a
 * closed session's value is reused while its stamp is unchanged and read again when it changes; the current session is read
 * every time and never kept. Synthetic session folders in a temporary history folder only.
 */
public class SessionStampsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** A session an earlier launch saved and closed: a loot checkpoint (loot/…json) and a chat journal (chat.jsonl). */
    private static String closedSession(Path root) throws Exception {
        SessionStore earlier = new SessionStore(root, true, "fixture");
        String id = earlier.currentId();
        try {
            earlier.put("loot", "bag-1", Map.of("time", 1));
            earlier.append("chat", Map.of("text", "hello"));
            earlier.flush();
        } finally { earlier.close(); }
        return id;
    }

    private static Path checkpoint(Path root, String session) { return root.resolve(session).resolve("loot").resolve(SessionStore.checkpointName("bag-1") + ".json"); }

    /** Counts its loads and remembers the stamp each one was given (null = the current session). */
    private static final class Loads implements SessionStamps.Loader<String> {
        final List<List<SessionStamps.Stamp>> seen = new ArrayList<>();
        @Override public String load(List<SessionStamps.Stamp> stamp) { seen.add(stamp); return "load " + seen.size(); }
    }

    @Test public void aStampIsTheSessionFoldersEntriesAndItsModuleFoldersFilesByName() throws Exception {
        Path root = temp.newFolder().toPath();
        String id = closedSession(root);
        Path folder = root.resolve(id);
        Files.createDirectories(folder.resolve("fame"));
        Files.writeString(folder.resolve("fame").resolve("other.json"), "{}");
        List<SessionStamps.Stamp> stamp = SessionStamps.stamp(folder, "loot");
        List<String> names = stamp.stream().map(SessionStamps.Stamp::name).collect(Collectors.toList());
        assertTrue(names.toString(), names.containsAll(List.of("session.json", "chat.jsonl", "loot", "fame")));
        assertTrue("A listed module folder's files, by their path inside the session folder",
            names.contains("loot/" + SessionStore.checkpointName("bag-1") + ".json"));
        assertFalse("Files of module folders not listed are not stamped", names.contains("fame/other.json"));
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        assertEquals("Sorted by name", sorted, names);
        SessionStamps.Stamp chat = stamp.get(names.indexOf("chat.jsonl"));
        assertEquals(Files.size(folder.resolve("chat.jsonl")), chat.size());
        assertEquals(Files.getLastModifiedTime(folder.resolve("chat.jsonl"), LinkOption.NOFOLLOW_LINKS).toMillis(), chat.modified());
        assertEquals("Listing again gives an equal stamp", stamp, SessionStamps.stamp(folder, "loot"));
    }

    @Test public void aClosedSessionIsReusedWhileItsStampIsUnchangedAndReadAgainWhenItChanges() throws Exception {
        Path root = temp.newFolder().toPath();
        String id = closedSession(root);
        SessionStamps<String> stamps = new SessionStamps<>("loot");
        Loads loads = new Loads();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            assertEquals("load 1", stamps.get(store, id, loads));
            assertEquals("A closed session's loader is given its stamp", SessionStamps.stamp(root.resolve(id), "loot"), loads.seen.get(0));
            assertEquals("Unchanged: the kept value, not read again", "load 1", stamps.get(store, id, loads));
            assertEquals(1, loads.seen.size());
            assertEquals(1, stamps.size());

            Path saved = checkpoint(root, id);
            Files.setLastModifiedTime(saved, FileTime.fromMillis(Files.getLastModifiedTime(saved).toMillis() + 2_000));
            assertEquals("A module file's new time changes the stamp: read again", "load 2", stamps.get(store, id, loads));
            assertEquals("…and the new value is kept", "load 2", stamps.get(store, id, loads));
            Files.writeString(root.resolve(id).resolve("chat.jsonl"), "{\"text\":\"more\"}\n", StandardOpenOption.APPEND);
            assertEquals("An entry of the session folder that grew changes it too", "load 3", stamps.get(store, id, loads));
            assertEquals(3, loads.seen.size());
            assertEquals(1, stamps.size());
        }
    }

    @Test public void theCurrentSessionIsReadEveryTimeAndNeverKept() throws Exception {
        Path root = temp.newFolder().toPath();
        SessionStamps<String> stamps = new SessionStamps<>("loot");
        Loads loads = new Loads();
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            store.put("loot", "bag-1", Map.of("time", 1));
            store.flush();
            assertEquals("load 1", stamps.get(store, store.currentId(), loads));
            assertEquals("Unchanged files, still read again: it is being written", "load 2", stamps.get(store, store.currentId(), loads));
            assertNull("The current session is not stamped", loads.seen.get(0));
            assertNull(loads.seen.get(1));
            assertEquals("Never kept", 0, stamps.size());
        }
    }

    @Test public void aMissingFolderIsAnEmptyStampNotACrash() throws Exception {
        Path root = temp.newFolder().toPath();
        assertEquals(List.of(), SessionStamps.stamp(root.resolve("absent"), "loot"));
        String gone = UUID.randomUUID().toString();
        SessionStamps<String> stamps = new SessionStamps<>("loot");
        Loads loads = new Loads();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            assertEquals("load 1", stamps.get(store, gone, loads));
            assertEquals("An empty stamp is a stamp", List.of(), loads.seen.get(0));
            assertEquals("…kept like any other", "load 1", stamps.get(store, gone, loads));
            Files.createDirectories(root.resolve(gone).resolve("loot"));
            Files.writeString(root.resolve(gone).resolve("loot").resolve("bag.json"), "{}");
            assertEquals("Files appearing change it", "load 2", stamps.get(store, gone, loads));
        }
    }

    @Test public void aFailedLoadKeepsNothing() throws Exception {
        Path root = temp.newFolder().toPath();
        String id = closedSession(root);
        SessionStamps<String> stamps = new SessionStamps<>("loot");
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            IOException failure = assertThrows(IOException.class, () -> stamps.get(store, id, stamp -> { throw new IOException("unreadable"); }));
            assertEquals("The loader's failure reaches the caller", "unreadable", failure.getMessage());
            assertEquals(0, stamps.size());
            Loads loads = new Loads();
            assertEquals("The next read loads again", "load 1", stamps.get(store, id, loads));
            assertEquals(1, stamps.size());
        }
    }

    @Test public void sessionsThatLeftTheCatalogAndAnotherHistoryFolderAreForgotten() throws Exception {
        Path root = temp.newFolder().toPath();
        String first = closedSession(root), second = closedSession(root);
        SessionStamps<String> stamps = new SessionStamps<>("loot");
        Loads loads = new Loads();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            List<SessionStore.SessionEntry> catalog = store.catalog();
            stamps.forgetGone(store, catalog);
            stamps.get(store, first, loads);
            stamps.get(store, second, loads);
            assertEquals(2, stamps.size());
            stamps.forgetGone(store, catalog);
            assertEquals("Both still listed: both kept", 2, stamps.size());
            stamps.forgetGone(store, catalog.stream().filter(entry -> !entry.id.equals(second)).collect(Collectors.toList()));
            assertEquals("A session no longer listed is forgotten", 1, stamps.size());
            assertEquals("The listed one is still reused", "load 1", stamps.get(store, first, loads));
        }
        Path other = temp.newFolder().toPath();
        try (Stream<Path> files = Files.walk(root.resolve(first))) {   // the same session copied into another history folder
            for (Path file : files.collect(Collectors.toList()))
                Files.copy(file, other.resolve(first).resolve(root.resolve(first).relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
        }
        try (SessionStore elsewhere = new SessionStore(other, false, "fixture")) {
            assertTrue(elsewhere.catalog().stream().anyMatch(entry -> entry.id.equals(first)));
            stamps.forgetGone(elsewhere, elsewhere.catalog());
            assertEquals("Another history folder: nothing kept from the old one", 0, stamps.size());
            assertEquals("load 3", stamps.get(elsewhere, first, loads));
        }
    }
}
