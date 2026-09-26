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
    private static final java.util.Map<Integer, Long> fameValues = new java.util.HashMap<>();
    // Replaced only by tests; production reads the collector's exact active visit.
    static volatile java.util.function.Supplier<packets.packetcapture.logger.DiscoveryLog.CurrentVisit> currentVisit =
        packets.packetcapture.logger.DiscoveryLog.INSTANCE::currentVisit;
    /** Records a captured fame observation with the exact visit active at producer time, if any. */
    public static void fame(int character, long fame, long time, String className) {
        // Read the collector before taking this class's lock; the two monitors are never nested the other way.
        packets.packetcapture.logger.DiscoveryLog.CurrentVisit active = currentVisit.get();
        record(character, fame, time, className, active == null ? null : active.visit, active == null ? null : active.map);
    }
    private static synchronized void record(int character, long fame, long time, String className, VisitRef visit, String map) {
        SessionStore s = store; if (s == null || !s.writable()) return;
        // A visit from another history session cannot be resolved from this one's samples.
        if (visit != null && !visit.sessionId.equals(s.currentId())) { visit = null; map = null; }
        FameSample sample = new FameSample(character, fame, time, className, visit, map);
        Long previous = fameValues.put(character, fame);
        if (previous == null || previous != fame) s.append("fame", sample);
        s.put("fame-latest", Integer.toString(character), sample);
    }
    public static final class FameSample {
        public final int character; public final long fame, time; public final String className;
        /** Optional exact visit active when the sample was produced; null (absent in JSON) means Not recorded. */
        public final String visitSession, visitId, map;
        public FameSample(int character, long fame, long time, String className) { this(character, fame, time, className, null, null); }
        public FameSample(int character, long fame, long time, String className, VisitRef visit, String map) {
            this.character=character;this.fame=fame;this.time=time;this.className=className;
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
