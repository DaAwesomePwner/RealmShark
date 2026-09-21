package tomato.history;

import packets.packetcapture.logger.ActivityJournal;
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
    public static synchronized void fame(int character, long fame, long time, String className) {
        SessionStore s = store; if (s == null || !s.writable()) return;
        FameSample sample = new FameSample(character, fame, time, className);
        Long previous = fameValues.put(character, fame);
        if (previous == null || previous != fame) s.append("fame", sample);
        s.put("fame-latest", Integer.toString(character), sample);
    }
    public static final class FameSample {
        public final int character; public final long fame, time; public final String className;
        public FameSample(int character, long fame, long time, String className) { this.character=character;this.fame=fame;this.time=time;this.className=className; }
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
