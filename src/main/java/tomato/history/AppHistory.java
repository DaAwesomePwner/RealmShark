package tomato.history;

import packets.packetcapture.logger.ActivityJournal;
import tomato.history.link.VisitRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** The shared user profile is independent of the folder/version of the portable application. */
public final class AppHistory {
    private static volatile SessionStore store;
    private AppHistory() { }
    public static SessionStore store() { return store; }
    public static Path directory() {
        String override = System.getProperty("realmshark.historyDir");
        if (override != null) return Paths.get(override);
        String local = System.getenv("LOCALAPPDATA");
        return (local == null ? Paths.get(System.getProperty("user.home"), ".realmshark") : Paths.get(local, "RealmShark")).resolve("history");
    }
    public static synchronized void start(boolean preview) {
        if (store != null) return;
        store = new SessionStore(directory(), !preview, realmshark.version.Version.VERSION);
        packets.packetcapture.logger.DiscoveryLog.INSTANCE.attachHistory(store);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            packets.packetcapture.logger.DiscoveryLog.INSTANCE.close();
            store.close();
        }, "Session history shutdown"));
        if (!preview) {
            Thread migration = new Thread(() -> {
                try { importLegacy(store, Paths.get(".")); }
                catch (Exception e) { store.importError("Some existing history could not be imported. Originals are kept; use Import old folder to retry."); }
            }, "Import legacy history"); migration.setDaemon(true); migration.start();
        }
    }
    public static void append(String module, Object snapshot) { SessionStore s=store; if(s!=null)s.append(module,snapshot); }
    public static void run(ActivityJournal.Visit visit) { SessionStore s=store; if(s!=null)s.put("runs",visit.id,visit); }
    /** Last recorded fame per stream: account + ":" + character, or "?:" + character while the account is not known. */
    private static final java.util.Map<String, Long> fameValues = new java.util.HashMap<>();
    // Replaced only by tests; production reads the collector's exact active visit.
    static volatile java.util.function.Supplier<packets.packetcapture.logger.DiscoveryLog.CurrentVisit> currentVisit =
        packets.packetcapture.logger.DiscoveryLog.INSTANCE::currentVisit;
    /** Records a captured fame observation with the exact visit active at producer time, if any; the account is not recorded. */
    public static void fame(int character, long fame, long time, String className) { fame(character, null, fame, time, className); }
    /**
     * As above, stamped with the player's journal account key ({@link FameSample#account}; null while the account is not known),
     * so a character's fame history can be read exactly by account and character id.
     */
    public static void fame(int character, String account, long fame, long time, String className) {
        // Read the collector before taking this class's lock; the two monitors are never nested the other way.
        packets.packetcapture.logger.DiscoveryLog.CurrentVisit active = currentVisit.get();
        record(character, account, fame, time, className, active == null ? null : active.visit, active == null ? null : active.map);
    }
    private static synchronized void record(int character, String account, long fame, long time, String className, VisitRef visit, String map) {
        SessionStore s = store; if (s == null || !s.writable()) return;
        // A visit from another history session cannot be resolved from this one's samples.
        if (visit != null && !visit.sessionId.equals(s.currentId())) { visit = null; map = null; }
        FameSample sample = new FameSample(character, account, fame, time, className, visit, map);
        // Character ids are per account, so two accounts' character #7 are two streams and two checkpoints. Without an account
        // the checkpoint keeps its legacy key (the bare character id); the sample's validated account decides both keys.
        Long previous = fameValues.put((sample.account == null ? "?" : sample.account) + ":" + character, fame);
        if (previous == null || previous != fame) s.append("fame", sample);
        s.put("fame-latest", sample.account == null ? Integer.toString(character) : sample.account + ":" + character, sample);
    }
    private static final java.util.regex.Pattern ACCOUNT_KEY = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    public static final class FameSample {
        public final int character; public final long fame, time; public final String className;
        /**
         * The journal's hashed account key of the player (64 lowercase hex, the key {@code Characters/journal.json} uses; never a
         * raw account id). Null (absent in JSON) means Not recorded: legacy samples and samples taken before the account was known.
         * The constructors record nothing else (any other value becomes null); readers match it exactly against a journal key.
         */
        public final String account;
        /** Optional exact visit active when the sample was produced; null (absent in JSON) means Not recorded. */
        public final String visitSession, visitId, map;
        public FameSample(int character, long fame, long time, String className) { this(character, null, fame, time, className, null, null); }
        public FameSample(int character, long fame, long time, String className, VisitRef visit, String map) {
            this(character, null, fame, time, className, visit, map);
        }
        public FameSample(int character, String account, long fame, long time, String className) { this(character, account, fame, time, className, null, null); }
        public FameSample(int character, String account, long fame, long time, String className, VisitRef visit, String map) {
            this.character=character;this.fame=fame;this.time=time;this.className=className;
            this.account = account != null && ACCOUNT_KEY.matcher(account).matches() ? account : null;
            visitSession = visit == null ? null : visit.sessionId; visitId = visit == null ? null : visit.visitId;
            this.map = visit == null ? null : map;
        }
        /** The recorded visit, or null for legacy samples and samples taken without an exact active visit. */
        public VisitRef visit() {
            return visitSession == null || visitId == null || visitSession.isEmpty() || visitId.isEmpty() ? null : new VisitRef(visitSession, visitId);
        }
    }
    public static void importLegacy(SessionStore target, Path directory) throws java.io.IOException {
        Path old = directory.resolve("logs/discovery/activity-history.json");
            if (Files.isRegularFile(old)) {
                ActivityJournal.State state = SessionStore.JSON.fromJson(new String(Files.readAllBytes(old), StandardCharsets.UTF_8), ActivityJournal.State.class);
                if (state != null && state.visits != null) for (ActivityJournal.Visit visit : state.visits) {
                    visit.normalizePlayers();
                    target.importSnapshot("legacy-activity", "Imported run history", visit.started, "runs", visit.id, visit);
                }
                if (state != null && state.entries != null) for (ActivityJournal.Entry entry : state.entries)
                    target.importSnapshot("legacy-activity", "Imported run history", entry.time, "timeline", entry.id, entry);
            }
            Path fame = directory.resolve("FameSessions");
            if (Files.isDirectory(fame)) try (DirectoryStream<Path> files = Files.newDirectoryStream(fame, "*.fame")) {
                for (Path path : files) {
                    tomato.gui.stats.session.FameSession session = SessionStore.JSON.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), tomato.gui.stats.session.FameSession.class);
                    target.importSnapshot("legacy-fame:" + session.getSessionName() + ":" + session.getCreatedTimestamp(),
                            session.getSessionName(), session.getCreatedTimestamp(), "fame-snapshots", "fame", session);
                }
            }
        target.importError("");
    }
}
