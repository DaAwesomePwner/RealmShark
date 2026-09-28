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
 * Finished sessions are kept by session id while their fame files' stamp (name, size, modification time: HomeArchive's reuse
 * rule) is unchanged, and memory follows what sheets asked for: a kept session holds only the readings of the (account,
 * character) pairs requested so far (an empty list where it has none), plus its untagged readings counted per character id. A
 * pair's first request reads again, once, each kept session that lacks it (a kept failure is not read again), then keeps it;
 * readings of pairs no sheet asked for are never kept. The current session ({@code store.currentId()}) is still being written
 * and is read again every time, never kept. Every method runs off the EDT (SessionStore refuses the EDT), and one thread owns
 * an instance: the Fame presenter's "character-fame".
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
    /**
     * One session as read: the readings of each pair it was read for ({@code streams}; an empty list where the session has none),
     * its untagged readings counted by character id, and {@code failed} when its fame files could not be read (a failure holds
     * for every pair). {@code stamp} is null for the current session.
     */
    private record Facts(List<Stamp> stamp, Map<Stream, List<Point>> streams, Map<Integer, Integer> untagged, boolean failed) {
        static Facts failed(List<Stamp> stamp) { return new Facts(stamp, Map.of(), Map.of(), true); }
        /** Whether this answers {@code pair} without reading the session again. */
        boolean covers(Stream pair) { return failed || streams.containsKey(pair); }
    }

    private final Supplier<SessionStore> store;
    private final Map<String, Facts> kept = new HashMap<>();
    /** Every (account, character) pair a sheet has asked for; a kept session holds these pairs' readings and no others. */
    private final Set<Stream> requested = new HashSet<>();
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

    /** Readings kept for finished sessions (tests: only the requested pairs' readings are kept). */
    int cachedPoints() {
        int points = 0;
        for (Facts facts : kept.values()) for (List<Point> stream : facts.streams().values()) points += stream.size();
        return points;
    }

    /**
     * Readings of exactly ({@code account}, {@code characterId}), oldest first, grouped by session. No store (history not started)
     * or no account: an empty series. Throws only when the history folder itself cannot be listed.
     */
    Series read(String account, int characterId) throws IOException {
        SessionStore s = store.get();
        if (s == null || account == null) return Series.EMPTY;
        List<SessionStore.SessionEntry> catalog = s.catalog();   // listed once; every session read below reuses it
        prepare(s, catalog);
        Stream exact = new Stream(account, characterId);
        requested.add(exact);
        List<Session> sessions = new ArrayList<>();
        int untagged = 0, unreadable = 0;
        for (SessionStore.SessionEntry entry : catalog) {
            if (!entry.readable()) { unreadable++; continue; }
            Facts facts = facts(s, catalog, entry.id, exact);
            if (facts.failed()) { unreadable++; continue; }
            untagged += facts.untagged().getOrDefault(characterId, 0);
            List<Point> points = facts.streams().getOrDefault(exact, List.of());
            if (!points.isEmpty()) sessions.add(new Session(entry.id, entry.id.equals(s.currentId()), points));
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

    /**
     * A session's readings of {@code pair}: a finished session is kept while its stamp is unchanged and it covers the pair; else it
     * is read again for every requested pair and kept. The current session is read every time, for this pair only, never kept.
     */
    private Facts facts(SessionStore s, List<SessionStore.SessionEntry> catalog, String id, Stream pair) {
        if (id.equals(s.currentId())) return load(s, catalog, id, null, Set.of(pair));
        List<Stamp> stamp;
        try { stamp = stamp(s.directory().resolve(id)); }
        catch (IOException unlisted) { return Facts.failed(null); }   // nothing rules out unread readings: counted
        Facts facts = kept.get(id);
        if (facts != null && facts.stamp().equals(stamp) && facts.covers(pair)) return facts;
        facts = load(s, catalog, id, stamp, Set.copyOf(requested));
        kept.put(id, facts);
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
     * One session's readings of the {@code wanted} pairs (each gets a list, empty where the session has none) and its untagged
     * readings counted by character id; readings of other pairs are passed over, never held. A corrupt line (IOException) or
     * checkpoint (Gson's unchecked exception) fails the whole session: its earlier readings are not shown either, and it is
     * counted as unreadable.
     */
    private Facts load(SessionStore s, List<SessionStore.SessionEntry> catalog, String id, List<Stamp> stamp, Set<Stream> wanted) {
        reads++;
        Map<Stream, List<Point>> lines = new HashMap<>();
        for (Stream pair : wanted) lines.put(pair, new ArrayList<>());
        // Untagged readings by character id: {count, newest line time}; the time is for the checkpoint rule below.
        Map<Integer, long[]> untagged = new HashMap<>();
        List<AppHistory.FameSample> checkpoints = new ArrayList<>();
        try {
            s.read(catalog, id, "fame", AppHistory.FameSample.class, (session, sample) -> {
                if (sample.time <= 0) return;
                Stream key = stream(sample);
                if (key.account() == null) {
                    long[] tally = untagged.computeIfAbsent(key.character(), k -> new long[] {0, Long.MIN_VALUE});
                    tally[0]++; tally[1] = Math.max(tally[1], sample.time);
                } else {
                    List<Point> points = lines.get(key);
                    if (points != null) points.add(new Point(sample.time, sample.fame));
                }
            });
            s.read(catalog, id, "fame-latest", AppHistory.FameSample.class, (session, sample) -> { if (sample.time > 0) checkpoints.add(sample); });
        } catch (IOException | RuntimeException unreadable) {
            return Facts.failed(stamp);
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
            if (key.account() == null) {
                long[] tally = untagged.computeIfAbsent(key.character(), k -> new long[] {0, Long.MIN_VALUE});
                if (sample.time > tally[1]) { tally[0]++; tally[1] = sample.time; }
                continue;
            }
            List<Point> points = lines.get(key);
            if (points == null || sample.time <= newest.get(key)) continue;
            points.add(new Point(sample.time, sample.fame));
            newest.put(key, sample.time);
        }
        Map<Stream, List<Point>> streams = new HashMap<>();
        for (Map.Entry<Stream, List<Point>> e : lines.entrySet()) {
            List<Point> points = new ArrayList<>(e.getValue());
            points.sort(Comparator.comparingLong(Point::time).thenComparingLong(Point::fame));   // Home's order
            streams.put(e.getKey(), List.copyOf(points));
        }
        Map<Integer, Integer> counts = new HashMap<>();
        untagged.forEach((character, tally) -> counts.put(character, (int) Math.min(Integer.MAX_VALUE, tally[0])));
        return new Facts(stamp, Map.copyOf(streams), Map.copyOf(counts), false);
    }

    /** Gson fills fields without the sample's constructor, so an account that is not a journal key reads as not recorded here. */
    private static Stream stream(AppHistory.FameSample sample) {
        String account = sample.account != null && ACCOUNT.matcher(sample.account).matches() ? sample.account : null;
        return new Stream(account, sample.character);
    }
}
