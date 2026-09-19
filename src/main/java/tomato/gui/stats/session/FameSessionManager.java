package tomato.gui.stats.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import tomato.gui.stats.Fame;
import tomato.gui.stats.data.MapFameData;

/**
 * Manages saving and loading of fame tracking sessions.
 * Handles file I/O and data format conversion for session persistence.
 */
public class FameSessionManager {

    private static final String SESSIONS_DIRECTORY = "FameSessions";
    private static final String FILE_EXTENSION = ".fame";
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private static final SessionWriter WRITER = new SessionWriter(FameSessionManager::writeFile);

    static { Runtime.getRuntime().addShutdownHook(new Thread(WRITER::close, "fame-session-exit")); }

    // --- Save Operations ---

    /**
     * Save a fame session to its default file location.
     */
    public static boolean saveSession(FameSession session) {
        return writeSession(snapshot(session), sessionFile(session.getSessionName()));
    }

    public static File sessionFile(String name) {
        return new File(SESSIONS_DIRECTORY, sanitizeFilename(name) + FILE_EXTENSION);
    }

    /** Call on the model's owning thread. Only the detached snapshot reaches the writer. */
    public static void saveSessionAsync(FameSession session, Consumer<Boolean> completion) {
        WRITER.save(snapshot(session), sessionFile(session.getSessionName()), completion);
    }

    public static void deleteSessionAsync(String name, Consumer<Boolean> completion) {
        WRITER.delete(sessionFile(name), completion);
    }

    /**
     * Choose a destination and schedule a save. Returns whether a save was requested.
     */
    public static boolean saveSessionAs(FameSession session) {
        JFileChooser fileChooser = createFileChooser("Save Fame Session");

        if (fileChooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().endsWith(FILE_EXTENSION)) {
                file = new File(file.getAbsolutePath() + FILE_EXTENSION);
            }
            SessionSnapshot snapshot = snapshot(session);
            File destination = file;
            JDialog progress = progress(null, "Saving fame session…");
            WRITER.save(snapshot, destination, success -> {
                progress.dispose();
                if (!success) showError("Could not save the fame session. Check the destination and try again.", "Save Error");
            });
            return true;
        }
        return false;
    }

    private static boolean writeSession(SessionSnapshot session, File file) {
        try {
            boolean success = WRITER.save(session, file, null).get();
            if (!success) showError("Could not save the fame session. Check the destination and try again.", "Save Error");
            return success;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            showError("Could not save the fame session.", "Save Error");
            return false;
        }
    }

    // --- Load Operations ---

    /**
     * Load a fame session with a file chooser dialog.
     */
    public static FameSession loadSession() {
        JFileChooser fileChooser = createFileChooser(
            "Load Fame Session (Read-Only)"
        );

        if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            FameSession session = loadSession(fileChooser.getSelectedFile());
            if (session != null) {
                session.setReadOnly(true);
            }
            return session;
        }
        return null;
    }

    /**
     * Load a session from a specific file.
     */
    public static FameSession loadSession(File file) {
        try {
            return readSession(file);
        } catch (IOException | com.google.gson.JsonParseException e) {
            showError("Error loading session: " + e.getMessage(), "Load Error");
            return null;
        }
    }

    /**
     * Get list of all saved session files.
     */
    public static List<File> getSavedSessions() {
        List<File> sessions = new ArrayList<>();
        File dir = new File(SESSIONS_DIRECTORY);

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) ->
                name.endsWith(FILE_EXTENSION)
            );
            if (files != null) {
                for (File file : files) {
                    sessions.add(file);
                }
            }
        }
        return sessions;
    }

    /**
     * Delete a saved session file.
     */
    public static boolean deleteSession(File sessionFile) {
        return sessionFile.exists() && sessionFile.delete();
    }

    /** Chooser stays on the EDT; file reading and parsing run on a worker. */
    public static void loadSessionAsync(Component parent, Consumer<FameSession> completion) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> loadSessionAsync(parent, completion));
            return;
        }
        JFileChooser chooser = createFileChooser("Load Fame Session (Read-Only)");
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        JDialog progress = progress(parent, "Loading fame session…");
        new SwingWorker<FameSession, Void>() {
            @Override protected FameSession doInBackground() throws IOException {
                FameSession session = readSession(file);
                session.setReadOnly(true);
                return session;
            }
            @Override protected void done() {
                progress.dispose();
                try { completion.accept(get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) { showError("Could not load this fame session. Check that it is a valid .fame file.", "Load Error"); }
            }
        }.execute();
    }

    private static FameSession readSession(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            FameSession session = GSON.fromJson(reader, FameSession.class);
            if (session == null || session.getCharacterFameData() == null
                || session.getCharacterMapFameData() == null || session.getCharacterClassNames() == null)
                throw new IOException("Invalid session data");
            for (List<Fame> rows : session.getCharacterFameData().values())
                if (rows == null || rows.contains(null)) throw new IOException("Invalid fame samples");
            for (List<MapFameData> rows : session.getCharacterMapFameData().values()) {
                if (rows == null) throw new IOException("Invalid map visits");
                for (MapFameData row : rows)
                    if (row == null || row.mapName == null) throw new IOException("Invalid map visit");
            }
            return session;
        }
    }

    // --- Session Creation ---

    /**
     * Create a session from raw fame and map data.
     */
    public static FameSession createSessionFromData(
        HashMap<Integer, ArrayList<Fame>> fameData,
        HashMap<Integer, ArrayList<MapFameData>> mapFameData,
        String sessionName
    ) {
        FameSession session = new FameSession(sessionName);
        session.setCharacterFameData(convertToSessionFormat(fameData));
        session.setCharacterMapFameData(
            convertMapDataToSessionFormat(mapFameData)
        );
        return session;
    }

    // --- Data Conversion (Generic) ---

    /**
     * Generic conversion from HashMap<Integer, ArrayList<T>> to HashMap<Integer, List<T>>.
     * Used for converting between runtime and session storage formats.
     */
    private static <T> HashMap<Integer, List<T>> convertMapToList(
        HashMap<Integer, ArrayList<T>> source
    ) {
        HashMap<Integer, List<T>> result = new HashMap<>();
        if (source != null) {
            for (Map.Entry<Integer, ArrayList<T>> entry : source.entrySet()) {
                result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Generic conversion from HashMap<Integer, List<T>> to HashMap<Integer, ArrayList<T>>.
     * Used for extracting data from sessions back to runtime format.
     */
    private static <T> HashMap<Integer, ArrayList<T>> convertListToMap(
        HashMap<Integer, List<T>> source
    ) {
        HashMap<Integer, ArrayList<T>> result = new HashMap<>();
        if (source != null) {
            for (Map.Entry<Integer, List<T>> entry : source.entrySet()) {
                result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Convert fame data from runtime format to session format.
     */
    public static HashMap<Integer, List<Fame>> convertToSessionFormat(
        HashMap<Integer, ArrayList<Fame>> fameData
    ) {
        return convertMapToList(fameData);
    }

    /**
     * Convert map fame data from runtime format to session format.
     */
    public static HashMap<
        Integer,
        List<MapFameData>
    > convertMapDataToSessionFormat(
        HashMap<Integer, ArrayList<MapFameData>> mapFameData
    ) {
        HashMap<Integer, List<MapFameData>> result = new HashMap<>();
        if (mapFameData != null) mapFameData.forEach((id, rows) -> result.put(id, copyMaps(rows)));
        return result;
    }

    /**
     * Extract fame data from a session to runtime format.
     */
    public static HashMap<Integer, ArrayList<Fame>> extractFameData(
        FameSession session
    ) {
        return convertListToMap(session.getCharacterFameData());
    }

    /**
     * Extract map fame data from a session to runtime format.
     */
    public static HashMap<Integer, ArrayList<MapFameData>> extractMapFameData(
        FameSession session
    ) {
        return convertListToMap(session.getCharacterMapFameData());
    }

    // --- Export ---

    /**
     * Export session data to CSV format.
     */
    public static boolean exportSessionToCsv(
        FameSession session,
        File outputFile
    ) {
        try {
            StringBuilder csv = new StringBuilder();
            csv.append("CharacterID,Timestamp,Fame\n");

            for (Map.Entry<Integer, List<Fame>> entry : session
                .getCharacterFameData()
                .entrySet()) {
                int charId = entry.getKey();
                for (Fame fame : entry.getValue()) {
                    csv
                        .append(charId)
                        .append(",")
                        .append(fame.getTime())
                        .append(",")
                        .append(fame.getFame())
                        .append("\n");
                }
            }

            Files.write(outputFile.toPath(), csv.toString().getBytes());
            return true;
        } catch (IOException e) {
            showError(
                "Error exporting session: " + e.getMessage(),
                "Export Error"
            );
            return false;
        }
    }

    // --- Utilities ---

    private static JFileChooser createFileChooser(String title) {
        JFileChooser fileChooser = new JFileChooser(SESSIONS_DIRECTORY);
        fileChooser.setDialogTitle(title);
        fileChooser.setFileFilter(
            new FileNameExtensionFilter(
                "Fame Session Files (*" + FILE_EXTENSION + ")",
                FILE_EXTENSION.substring(1)
            )
        );
        return fileChooser;
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\- ]", "_");
    }

    private static void showError(String message, String title) {
        System.err.println(title + ": " + message);
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            JDialog dialog = new JOptionPane(message, JOptionPane.ERROR_MESSAGE).createDialog(title);
            realmshark.branding.AppIdentity.apply(dialog);
            dialog.setModal(false);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setVisible(true);
        });
    }

    private static JDialog progress(Component parent, String message) {
        JDialog dialog = new JDialog();
        realmshark.branding.AppIdentity.apply(dialog);
        dialog.setTitle(message); dialog.setModal(false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JProgressBar bar = new JProgressBar(); bar.setIndeterminate(true);
        JPanel panel = new JPanel(new java.awt.BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        panel.add(new JLabel(message), java.awt.BorderLayout.NORTH); panel.add(bar, java.awt.BorderLayout.CENTER);
        dialog.setContentPane(panel); dialog.pack(); dialog.setLocationRelativeTo(parent);
        // Quick operations should not flash a progress window.
        Timer timer = new Timer(250, e -> { if (dialog.isDisplayable()) dialog.setVisible(true); });
        timer.setRepeats(false); timer.start();
        return dialog;
    }

    private static List<MapFameData> copyMaps(List<MapFameData> source) {
        List<MapFameData> rows = new ArrayList<>();
        for (MapFameData map : source) {
            MapFameData copy = new MapFameData(map.mapName, map.startTime, map.startFame);
            copy.endTime = map.endTime; copy.endFame = map.endFame; rows.add(copy);
        }
        return rows;
    }

    /** Field names deliberately match the existing .fame schema, including timestamps. */
    static final class SessionSnapshot {
        String sessionName, description;
        long createdTimestamp, lastModifiedTimestamp;
        boolean readOnly;
        HashMap<Integer, List<Fame>> characterFameData = new HashMap<>();
        HashMap<Integer, List<MapFameData>> characterMapFameData = new HashMap<>();
        HashMap<Integer, String> characterClassNames;
    }

    static SessionSnapshot snapshot(FameSession session) {
        SessionSnapshot copy = new SessionSnapshot();
        copy.sessionName = session.getSessionName(); copy.description = session.getDescription();
        copy.createdTimestamp = session.getCreatedTimestamp(); copy.lastModifiedTimestamp = session.getLastModifiedTimestamp();
        copy.readOnly = session.isReadOnly();
        session.getCharacterFameData().forEach((id, rows) -> copy.characterFameData.put(id, new ArrayList<>(rows)));
        session.getCharacterMapFameData().forEach((id, rows) -> copy.characterMapFameData.put(id, copyMaps(rows)));
        copy.characterClassNames = new HashMap<>(session.getCharacterClassNames());
        return copy;
    }

    private static void writeFile(File file, String json) throws IOException {
        Path path = file.toPath().toAbsolutePath();
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".fame-", ".tmp");
        try {
            Files.write(temporary, json.getBytes(Charset.defaultCharset()));
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    @FunctionalInterface
    interface Store { void write(File file, String json) throws IOException; }

    /** One ordered writer; only pending saves to the same file coalesce. Deletes are barriers. */
    static final class SessionWriter implements AutoCloseable {
        private final Store store;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fame-session-save"); t.setDaemon(true); return t;
        });
        private final ArrayDeque<Job> queue = new ArrayDeque<>();
        private final Map<File, Job> pending = new LinkedHashMap<>();
        private boolean running;

        SessionWriter(Store store) { this.store = store; }

        synchronized CompletableFuture<Boolean> save(SessionSnapshot snapshot, File file, Consumer<Boolean> completion) {
            file = file.getAbsoluteFile();
            Job job = pending.get(file);
            if (job == null) {
                job = new Job(file); pending.put(file, job); queue.add(job);
            }
            job.snapshot = snapshot;
            if (completion != null) job.completions.add(completion);
            start();
            return job.result;
        }

        synchronized void delete(File file, Consumer<Boolean> completion) {
            file = file.getAbsoluteFile();
            pending.remove(file);
            Job job = new Job(file);
            if (completion != null) job.completions.add(completion);
            queue.add(job); start();
        }

        private void start() {
            if (!running) { running = true; executor.execute(this::drain); }
        }

        private void drain() {
            while (true) {
                Job job;
                synchronized (this) {
                    job = queue.poll();
                    if (job == null) { running = false; return; }
                    pending.remove(job.file, job);
                }
                boolean success;
                try {
                    if (job.snapshot == null) Files.deleteIfExists(job.file.toPath());
                    else store.write(job.file, GSON.toJson(job.snapshot));
                    success = true;
                } catch (IOException | RuntimeException e) {
                    System.err.println("Fame session persistence failed: " + e.getClass().getSimpleName());
                    success = false;
                }
                final boolean result = success;
                job.result.complete(result);
                SwingUtilities.invokeLater(() -> job.completions.forEach(callback -> callback.accept(result)));
            }
        }

        @Override public void close() {
            executor.shutdown();
            try { executor.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        private static final class Job {
            final File file;
            SessionSnapshot snapshot;
            final List<Consumer<Boolean>> completions = new ArrayList<>();
            final CompletableFuture<Boolean> result = new CompletableFuture<>();
            Job(File file) { this.file = file; }
        }
    }
}
