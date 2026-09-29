package tomato.history;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Per-session stamps and the values kept by them, for readers of saved history that keep closed sessions' facts between
 * reads (Home's archive, the Dungeons cards, the run feed's sessions through {@code SessionFacts}; Loot Highlights next).
 *
 * <p>A session's stamp is the name, size and modification time of every entry of its folder and of the files in the module
 * folders a reader depends on, sorted by name, so a saved, pruned or rewritten record changes it. A folder that does not
 * exist adds nothing (a missing session folder is an empty stamp, not a failure); one that cannot be listed fails the stamp.
 * A closed (or imported) session's value is reused while its stamp is unchanged; the current session is still being written,
 * so it is loaded every time and never kept. A load that fails keeps nothing.
 *
 * <p>Off the EDT only (it lists folders). Thread-safe: the kept values are guarded by this, and a load runs outside the lock
 * (two readers may load the same session; the later one is kept).
 */
public final class SessionStamps<V> {
    /** One entry as last seen: its path inside the session folder ("loot.jsonl", "loot/…json"), size and modification time (epoch ms). */
    public record Stamp(String name, long size, long modified) {}

    /** Reads one session's value; {@code stamp} is null for the current session (never kept). */
    @FunctionalInterface public interface Loader<V> { V load(List<Stamp> stamp) throws IOException; }

    private final String[] modules;
    /** Closed sessions' values by session ID, for {@link #root}; guarded by this. */
    private final Map<String, Kept<V>> kept = new HashMap<>();
    private Path root;

    /** {@code modules}: the session's module folders whose files the kept values come from (besides the folder's own entries). */
    public SessionStamps(String... modules) { this.modules = modules.clone(); }

    /** The session folder's entries and the files of its {@code modules} folders, by name. */
    public static List<Stamp> stamp(Path folder, String... modules) throws IOException {
        List<Stamp> stamp = new ArrayList<>();
        list(stamp, folder, "");
        for (String module : modules) list(stamp, folder.resolve(module), module + "/");
        stamp.sort(Comparator.comparing(Stamp::name));
        return List.copyOf(stamp);
    }

    private static void list(List<Stamp> stamp, Path folder, String prefix) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                stamp.add(new Stamp(prefix + file.getFileName(), attributes.size(), attributes.lastModifiedTime().toMillis()));
            }
        }
    }

    /**
     * {@code session}'s value: the current session's is loaded every time and never kept; a closed session's is stamped and
     * reused while the stamp equals the kept one, else loaded with the new stamp and kept.
     *
     * @throws IOException when the session folder cannot be stamped, or from {@code load} (then nothing is kept)
     */
    public V get(SessionStore store, String session, Loader<V> load) throws IOException {
        if (session.equals(store.currentId())) return load.load(null);   // still being written: never kept
        List<Stamp> stamp = stamp(store.directory().resolve(session), modules);
        synchronized (this) {
            Kept<V> known = kept.get(session);
            if (known != null && known.stamp().equals(stamp)) return known.value();
        }
        V value = load.load(stamp);
        synchronized (this) { kept.put(session, new Kept<>(stamp, value)); }
        return value;
    }

    /** Forgets everything when the store's folder changed, and the sessions that left the catalog. */
    public synchronized void forgetGone(SessionStore store, List<SessionStore.SessionEntry> catalog) {
        if (!store.directory().equals(root)) { kept.clear(); root = store.directory(); }
        Set<String> listed = new HashSet<>();
        for (SessionStore.SessionEntry entry : catalog) listed.add(entry.id);
        kept.keySet().retainAll(listed);
    }

    /** Closed sessions whose values are kept. */
    public synchronized int size() { return kept.size(); }

    private record Kept<V>(List<Stamp> stamp, V value) {}
}
