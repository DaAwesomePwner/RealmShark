package tomato.gui.stats;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSession;
import tomato.gui.stats.session.FameSessionManager;

/**
 * GUI panel for displaying fame graph and managing live session tracking.
 */
public class FameTrackerGUI extends JPanel {

    private static FameTrackerGUI INSTANCE;

    private final HashMap<Integer, ArrayList<Fame>> fameList = new HashMap<>();
    private final BiConsumer<FameSession, Consumer<Boolean>> sessionSaver;
    private final GraphPanel graphPanel;
    private FameSession currentLiveSession;
    private boolean fameGainedSinceLastSave = false;
    private long revision, lastSessionTimestamp, saveRequest;
    private final JLabel saveStatus = new JLabel("Sessions save automatically on map changes.");
    private final JComboBox<String> character = new JComboBox<>(new String[]{"Follow current character"});
    private final JComboBox<String> range = new JComboBox<>(new String[]{"All samples", "1 min", "5 min", "15 min", "30 min", "60 min"});
    private final JComboBox<String> measure = new JComboBox<>(new String[]{"Total fame", "Gain in range"});
    private final JLabel[] metrics = new JLabel[4];
    private final JLabel sampleStatus = new JLabel("Waiting for captured fame samples.");
    private final java.util.LinkedHashMap<String, Integer> characterIds = new java.util.LinkedHashMap<>();
    private int currentCharacterId = -1;

    public FameTrackerGUI() {
        this(FameSessionManager::saveSessionAsync);
    }

    FameTrackerGUI(BiConsumer<FameSession, Consumer<Boolean>> sessionSaver) {
        this.sessionSaver = sessionSaver;
        INSTANCE = this;
        setLayout(new BorderLayout(0, 8));

        graphPanel = new GraphPanel(new ArrayList<>(), false);
        graphPanel.setMinimumSize(new Dimension(0, 240));
        add(graphPanel, BorderLayout.CENTER);
        character.setName("fame-graph-character"); range.setName("fame-graph-range"); measure.setName("fame-graph-measure");
        character.setPrototypeDisplayValue("Follow current character");
        character.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                Integer id = characterIds.get(value); FameTablePanel table = FameTablePanel.getInstance();
                if (id != null && table != null) setText(table.getClassNameForCharacterId(id) + " (#" + id + ")");
                putClientProperty("html.disable", true); return this;
            }
        });
        JPanel controls = StatsUi.controls(); controls.add(character); controls.add(range); controls.add(measure);
        add(StatsUi.stack(StatsUi.heading("Fame over time", "Explore a character's captured progression. Ranges end at that character's latest sample."),
            StatsUi.metrics(metrics, "Fame gained", "Sample span", "Fame / hour", "Samples shown"), controls), BorderLayout.NORTH);
        saveStatus.setName("fame-save-status");
        saveStatus.setFont(ContentStyle.metadata(ContentStyle.body()));
        sampleStatus.setFont(ContentStyle.metadata(ContentStyle.body()));
        add(StatsUi.stack(sampleStatus, saveStatus, StatsUi.note("Rates use the first and last actual samples in the selected range. Character history may include time spent elsewhere; this is not combat uptime.")), BorderLayout.SOUTH);
        character.addActionListener(e -> refreshGraph()); range.addActionListener(e -> refreshGraph()); measure.addActionListener(e -> refreshGraph());

        resetSession();
    }

    // --- Public API ---

    public static void updateFame(int charId, long fame, long time) {
        if (INSTANCE != null) {
            FameTrackerGUI panel = INSTANCE;
            if (SwingUtilities.isEventDispatchThread()) panel.update(charId, fame, time);
            else SwingUtilities.invokeLater(() -> panel.update(charId, fame, time));
        }
    }

    public void triggerAutoSave() {
        onEdt(() -> {
            if (!fameList.isEmpty() && fameGainedSinceLastSave) saveCurrentLiveSession(null);
        });
    }

    public void triggerMapChangeAutoSave() {
        onEdt(() -> {
            revision++; // A closed/zero-gain visit is also a persistence change.
            if (!fameList.isEmpty()) { fameGainedSinceLastSave = true; saveCurrentLiveSession(null); }
        });
    }

    public boolean hasFameGainedSinceLastSave() {
        return fameGainedSinceLastSave;
    }

    public HashMap<Integer, ArrayList<Fame>> getFameData() {
        return fameList;
    }

    public void saveCurrentSession(String sessionName) {
        if (sessionName == null || sessionName.trim().isEmpty()) return;
        onEdt(() -> {
            currentLiveSession.setSessionName(sessionName.trim()); revision++;
            FameSession saving = currentLiveSession; long savingRevision = revision;
            saveCurrentLiveSession(success -> {
                if (success && currentLiveSession == saving && revision == savingRevision) resetSession();
            });
        });
    }

    public void clearCurrentSessionFile() {
        onEdt(() -> {
            String name = currentLiveSession.getSessionName();
            clearTracking(); resetSession();
            long request = ++saveRequest;
            setSaveStatus("Clearing previous session file…");
            FameSessionManager.deleteSessionAsync(name, success -> {
                if (saveRequest == request) setSaveStatus(success ? "Session cleared. Tracking a new session."
                    : "Could not delete the previous session file. Check FameSessions folder access.");
            });
        });
    }

    public void startNewSessionFile() {
        onEdt(() -> { clearTracking(); resetSession(); saveCurrentLiveSession(null); });
    }

    // --- Private Methods ---

    private void update(int charId, long fame, long time) {
        fameGainedSinceLastSave = true;
        revision++;

        fameList
            .computeIfAbsent(charId, k -> new ArrayList<>())
            .add(new Fame(fame, time));

        currentCharacterId = charId;
        String label = "Character #" + charId;
        if (!characterIds.containsKey(label)) { characterIds.put(label, charId); character.addItem(label); }
        refreshGraph();
    }

    private void clearTracking() {
        fameList.clear(); currentCharacterId = -1; characterIds.clear();
        character.removeAllItems(); character.addItem("Follow current character");
        graphPanel.clearData(); refreshGraph();
    }

    private void refreshGraph() {
        if (graphPanel == null) return;
        int id = characterIds.getOrDefault(character.getSelectedItem(), currentCharacterId);
        long[] minutes = {0, 1, 5, 15, 30, 60};
        ArrayList<Fame> samples = GraphPanel.window(fameList.get(id), minutes[range.getSelectedIndex()] * 60000);
        double gain = samples.size() < 2 ? 0 : samples.get(samples.size() - 1).getFame() - samples.get(0).getFame();
        long duration = samples.size() < 2 ? 0 : samples.get(samples.size() - 1).getTime() - samples.get(0).getTime();
        metrics[0].setText(samples.isEmpty() ? "—" : Formatters.formatNumber(gain, 0));
        metrics[1].setText(Formatters.formatDurationHMS(duration));
        metrics[2].setText(duration > 0 ? Formatters.formatNumber(gain * 3600000.0 / duration, 1) : "—");
        metrics[3].setText(Integer.toString(samples.size()));
        sampleStatus.setText(samples.isEmpty() ? "No samples in this range. Choose another character or a wider range."
            : "Character #" + id + " · Latest sample " + Formatters.formatTimestamp(samples.get(samples.size() - 1).getTime()));
        if (measure.getSelectedIndex() == 1 && !samples.isEmpty()) {
            double baseline = samples.get(0).getFame(); ArrayList<Fame> relative = new ArrayList<>();
            for (Fame sample : samples) relative.add(new Fame(sample.getFame() - baseline, sample.getTime()));
            samples = relative;
        }
        graphPanel.setScores(samples);
    }

    private void updateLiveSessionData() {
        currentLiveSession.setCharacterFameData(
            FameSessionManager.convertToSessionFormat(fameList)
        );

        FameTablePanel tablePanel = FameTablePanel.getInstance();
        if (tablePanel == null) return;

        // Update class names
        HashMap<Integer, String> classNames = new HashMap<>();
        for (Integer charId : fameList.keySet()) {
            classNames.put(
                charId,
                tablePanel.getClassNameForCharacterId(charId)
            );
        }
        currentLiveSession.setCharacterClassNames(classNames);

        // Update map fame data
        try {
            HashMap<Integer, ArrayList<MapFameData>> mapData =
                tablePanel.getMapFameData();
            currentLiveSession.setCharacterMapFameData(
                FameSessionManager.convertMapDataToSessionFormat(mapData)
            );
        } catch (Exception e) {
            System.err.println(
                "Error updating live session map data: " + e.getMessage()
            );
        }
    }

    private void saveCurrentLiveSession(Consumer<Boolean> completion) {
        updateLiveSessionData();
        FameSession saving = currentLiveSession;
        long savingRevision = revision, request = ++saveRequest;
        setSaveStatus("Saving fame session…");
        sessionSaver.accept(saving, success -> {
            if (currentLiveSession == saving) {
                if (success && revision == savingRevision) fameGainedSinceLastSave = false;
                if (request == saveRequest) setSaveStatus(success
                    ? (revision == savingRevision ? "Fame session saved locally." : "Fame session saved; newer samples await the next save.")
                    : "Fame save failed. Check FameSessions folder access; the next autosave will retry.");
            } else if (!success) {
                setSaveStatus("A previous fame session could not be saved. Check FameSessions folder access.");
            }
            if (completion != null) completion.accept(success);
        });
    }

    private void resetSession() {
        lastSessionTimestamp = Math.max(System.currentTimeMillis(), lastSessionTimestamp + 1);
        currentLiveSession = new FameSession("Live_" + lastSessionTimestamp);
        revision++;
        fameGainedSinceLastSave = false;
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    private void setSaveStatus(String text) {
        saveStatus.setText(text);
        FameTablePanel table = FameTablePanel.getInstance();
        if (table != null) table.setSessionSaveStatus(text);
    }
}
