package tomato.gui.glance.character;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tomato.history.AppHistory;
import tomato.history.SessionStore;

/**
 * Sheet › Fame's reader (decision "Fame history is exact by account"): one character's fame readings from saved history. A
 * reading belongs to the sheet only when its account and character id both equal the journal key's (never by name, class or
 * time); a reading of this character id with no recorded account (written before readings recorded it) is counted, never
 * shown, since it may be another account's character with the same id. Undated readings ({@code time <= 0}) are no readings.
 * It reads the {@code fame} module and the {@code fame-latest} checkpoints as HomeArchive does, over one catalog listing:
 * a checkpoint joins its stream only when newer than the stream's last line (HistoricalStatistics' rule), so it extends the
 * session instead of repeating a reading. Sessions whose metadata or payload cannot be read are skipped and counted.
 * Finished sessions are kept by session id, with every stream of every character, while their fame files' stamp (name,
 * size, modification time: HomeArchive's reuse rule) is unchanged; the current session ({@code store.currentId()}) is still
 * being written and is read again every time, never kept. Every method runs off the EDT (SessionStore refuses the EDT), and
 * one thread owns an instance: the Fame presenter's "character-fame".
 */
final class FameHistory {
    /** A journal key: the hashed account (64 lowercase hex, as CharacterJournal.accountKey writes it) ":" the character id. */
    private static final Pattern KEY = Pattern.compile("([0-9a-f]{64}):([0-9]+)");
    private static final Pattern ACCOUNT = Pattern.compile("[0-9a-f]{64}");
    private static final String[] FOLDERS = {"fame-latest"};

    /** The account and character id of a journal key. */
    record Ref(String account, int characterId) {}
    /** One reading: epoch ms (always > 0) and the sample's fame. */
    record Point(long time, long fame) {}
    /** One saved session's readings of the character, oldest first; {@code current}: the session this app is recording now. */
    record Session(String id, boolean current, List<Point> points) {
        Session { points = List.copyOf(points); }
    }
    /**
     * Sessions with at least one reading, oldest first. {@code untagged}: this character id's readings with no recorded account
     * (not shown); {@code unreadable}: saved sessions skipped because their metadata or fame files could not be read.
     */
    record Series(List<Session> sessions, int untagged, int unreadable) {
        static final Series EMPTY = new Series(List.of(), 0, 0);
        Series { sessions = List.copyOf(sessions); }
    }

    /** One reading stream inside a session: an account (null: not recorded) and a character id. */
    private record Stream(String account, int character) {}
    /** One entry of a session's fame files as last seen: its path in the session folder, size and modification time. */
    private record Stamp(String name, long size, long modified) {}
    /** One session's readings by stream; {@code failed}: its fame files could not be read; {@code stamp} null for the current session. */
    private record Facts(List<Stamp> stamp, Map<Stream, List<Point>> streams, boolean failed) {}

    private final Supplier<SessionStore> store;
    private final Map<String, Facts> kept = new HashMap<>();
    private Path root;
    /** Session payloads read from disk (tests: finished sessions are read once). */
    private int reads;

    /** {@code store}: production passes AppHistory::store, null until history starts (nothing to read yet). */
    FameHistory(Supplier<SessionStore> store) { this.store = Objects.requireNonNull(store, "store"); }

    /** The account and character id of a journal key, or null for anything that is not exactly one. */
    static Ref parse(String key) {
        if (key == null) return null;
        Matcher m = KEY.matcher(key);
        if (!m.matches()) return null;
        try { return new Ref(m.group(1), Integer.parseInt(m.group(2))); }
        catch (NumberFormatException beyondInt) { return null; }
    }

    /** Session payloads read so far (tests). */
    int reads() { return reads; }

    /**
     * Readings of exactly ({@code account}, {@code characterId}), oldest first, grouped by session. No store (history not started)
     * or no account: an empty series. Throws only when the history folder itself cannot be listed.
     */
    Series read(String account, int characterId) throws IOException {
        SessionStore s = store.get();
        if (s == null || account == null) return Series.EMPTY;
        List<SessionStore.SessionEntry> catalog = s.catalog();   // listed once; every session read below reuses it
        prepare(s, catalog);
        Stream exact = new Stream(account, characterId), untaggedStream = new Stream(null, characterId);
        List<Session> sessions = new ArrayList<>();
        int untagged = 0, unreadable = 0;
        for (SessionStore.SessionEntry entry : catalog) {
            if (!entry.readable()) { unreadable++; continue; }
            Facts facts = facts(s, catalog, entry.id);
            if (facts.failed()) { unreadable++; continue; }
            untagged += facts.streams().getOrDefault(untaggedStream, List.of()).size();
            List<Point> points = facts.streams().get(exact);
            if (points != null) sessions.add(new Session(entry.id, entry.id.equals(s.currentId()), points));
        }
        sessions.sort(Comparator.comparingLong((Session session) -> session.points().get(0).time()).thenComparing(Session::id));
        return new Series(sessions, untagged, unreadable);
    }

    /** Forgets everything when the history folder changed, and the sessions that left the catalog (HomeArchive.Cache's rule). */
    private void prepare(SessionStore s, List<SessionStore.SessionEntry> catalog) {
        if (!s.directory().equals(root)) { kept.clear(); root = s.directory(); }
        Set<String> listed = new HashSet<>();
        for (SessionStore.SessionEntry entry : catalog) listed.add(entry.id);
        kept.keySet().retainAll(listed);
    }

    /** A session's readings: kept while a finished session's stamp is unchanged; the current session is read every time. */
    private Facts facts(SessionStore s, List<SessionStore.SessionEntry> catalog, String id) {
        boolean current = id.equals(s.currentId());
        List<Stamp> stamp = null;
        if (!current) {
            try { stamp = stamp(s.directory().resolve(id)); }
            catch (IOException unlisted) { return new Facts(null, Map.of(), true); }   // nothing rules out unread readings: counted
            Facts facts = kept.get(id);
            if (facts != null && facts.stamp().equals(stamp)) return facts;
        }
        Facts facts = load(s, catalog, id, stamp);
        if (!current) kept.put(id, facts);
        return facts;
    }

    /** The fame files' stamp: fame.jsonl and every file in fame-latest, sorted by name. */
    private static List<Stamp> stamp(Path folder) throws IOException {
        List<Stamp> stamp = new ArrayList<>();
        entry(stamp, folder.resolve("fame.jsonl"), "fame.jsonl");
        for (String module : FOLDERS) {
            Path directory = folder.resolve(module);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) entry(stamp, file, module + "/" + file.getFileName());
            }
        }
        stamp.sort(Comparator.comparing(Stamp::name));
        return stamp;
    }

    private static void entry(List<Stamp> stamp, Path file, String name) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        stamp.add(new Stamp(name, attributes.size(), attributes.lastModifiedTime().toMillis()));
    }

    /**
     * One session's readings by stream. A corrupt line (IOException) or checkpoint (Gson's unchecked exception) fails the whole
     * session: its earlier readings are not shown either, and it is counted as unreadable.
     */
    private Facts load(SessionStore s, List<SessionStore.SessionEntry> catalog, String id, List<Stamp> stamp) {
        reads++;
        Map<Stream, List<Point>> lines = new HashMap<>();
        List<AppHistory.FameSample> checkpoints = new ArrayList<>();
        try {
            s.read(catalog, id, "fame", AppHistory.FameSample.class, (session, sample) -> {
                if (sample.time > 0) lines.computeIfAbsent(stream(sample), k -> new ArrayList<>()).add(new Point(sample.time, sample.fame));
            });
            s.read(catalog, id, "fame-latest", AppHistory.FameSample.class, (session, sample) -> { if (sample.time > 0) checkpoints.add(sample); });
        } catch (IOException | RuntimeException unreadable) {
            return new Facts(stamp, Map.of(), true);
        }
        Map<Stream, Long> newest = new HashMap<>();
        for (Map.Entry<Stream, List<Point>> e : lines.entrySet()) {
            long last = Long.MIN_VALUE;
            for (Point p : e.getValue()) last = Math.max(last, p.time());
            newest.put(e.getKey(), last);
        }
        // A checkpoint is the stream's latest observation: kept only when newer than its last line (then it extends the session).
        for (AppHistory.FameSample sample : checkpoints) {
            Stream key = stream(sample);
            if (sample.time <= newest.getOrDefault(key, Long.MIN_VALUE)) continue;
            lines.computeIfAbsent(key, k -> new ArrayList<>()).add(new Point(sample.time, sample.fame));
            newest.put(key, sample.time);
        }
        Map<Stream, List<Point>> streams = new HashMap<>();
        for (Map.Entry<Stream, List<Point>> e : lines.entrySet()) {
            List<Point> points = new ArrayList<>(e.getValue());
            points.sort(Comparator.comparingLong(Point::time).thenComparingLong(Point::fame));   // Home's order
            streams.put(e.getKey(), List.copyOf(points));
        }
        return new Facts(stamp, Map.copyOf(streams), false);
    }

    /** Gson fills fields without the sample's constructor, so an account that is not a journal key reads as not recorded here. */
    private static Stream stream(AppHistory.FameSample sample) {
        String account = sample.account != null && ACCOUNT.matcher(sample.account).matches() ? sample.account : null;
        return new Stream(account, sample.character);
    }
}
