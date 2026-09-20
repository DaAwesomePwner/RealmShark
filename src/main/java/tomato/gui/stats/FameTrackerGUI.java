package tomato.gui.stats;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.session.FameSession;
import tomato.gui.stats.session.FameSessionManager;

/**
 * GUI panel for displaying fame graph and managing live session tracking.
 */
public class FameTrackerGUI extends JPanel {

    private static volatile FameTrackerGUI INSTANCE;

    private final FameTrackingModel.History tracking = new FameTrackingModel.History();
    private final FameRefresh presentation;
    // The saver must synchronously detach and enqueue, as saveSessionAsync does; it
    // must not perform I/O or retain the mutable transfer object after returning.
    private final BiConsumer<FameSession, Consumer<Boolean>> sessionSaver;
    private final GraphPanel graphPanel;
    private final JLabel saveStatus = new JLabel("Sessions save automatically on map changes.");
    private final JComboBox<String> character = new JComboBox<>(new String[]{"Follow current character"});
    private final JComboBox<String> range = new JComboBox<>(new String[]{"All samples", "1 min", "5 min", "15 min", "30 min", "60 min"});
    private final JComboBox<String> measure = new JComboBox<>(new String[]{"Total fame", "Gain in range"});
    private final JLabel[] metrics = new JLabel[4];
    private final JLabel sampleStatus = new JLabel("Waiting for captured fame samples.");
    private final java.util.LinkedHashMap<String, Integer> characterIds = new java.util.LinkedHashMap<>();
    private boolean updatingCharacters;
    private long renderedGeneration = -1;

    public FameTrackerGUI() {
        this(FameSessionManager::saveSessionAsync);
    }

    FameTrackerGUI(BiConsumer<FameSession, Consumer<Boolean>> sessionSaver) {
        this.sessionSaver = sessionSaver;
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

        presentation = new FameRefresh(this, this::renderGraph);
        INSTANCE = this;
        refreshGraph();
    }

    // --- Public API ---

    public static void updateFame(int charId, long fame, long time) {
        FameTrackerGUI panel = INSTANCE;
        if (panel != null) {
            synchronized (FameTableBridge.getInstance()) { panel.tracking.sample(charId, fame, time); }
            panel.refreshGraph();
        }
    }

    /** Graph-only compatibility path; Entity observations go through FameTableBridge. */
    public static void trackFame(int charId, long fame, long time) {
        FameTrackerGUI panel = INSTANCE;
        if (panel != null) panel.trackCapturedFame(charId, fame, time);
    }

    /** Retain fame changes and character switches in the bridge's registered history. */
    void trackCapturedFame(int charId, long fame, long time) {
        boolean changed;
        synchronized (FameTableBridge.getInstance()) { changed = tracking.sampleIfChanged(charId, fame, time); }
        if (changed) refreshGraph();
    }

    public void triggerAutoSave() {
        synchronized (FameTableBridge.getInstance()) {
            if (tracking.hasSamples() && tracking.dirty()) saveCurrentLiveSession(false);
        }
    }

    public void triggerMapChangeAutoSave() {
        synchronized (FameTableBridge.getInstance()) {
            tracking.visitsChanged(); // A closed/zero-gain visit is also a persistence change.
            if (tracking.hasSamples()) saveCurrentLiveSession(false);
        }
    }

    void tableChanged() { tracking.visitsChanged(); }

    public boolean hasFameGainedSinceLastSave() {
        return tracking.dirty();
    }

    /** Detached graph/session history; callers cannot mutate live tracking through it. */
    public HashMap<Integer, ArrayList<Fame>> getFameData() {
        return tracking.samples();
    }

    public void saveCurrentSession(String sessionName) {
        if (sessionName == null || sessionName.trim().isEmpty()) return;
        synchronized (FameTableBridge.getInstance()) {
            tracking.rename(sessionName.trim()); saveCurrentLiveSession(true);
        }
    }

    public void clearCurrentSessionFile() {
        synchronized (FameTableBridge.getInstance()) {
            String name = tracking.sessionName();
            tracking.startNew();
            long request = tracking.deleting();
            publishSaveStatus();
            FameSessionManager.deleteSessionAsync(name, success -> {
                synchronized (FameTableBridge.getInstance()) {
                    tracking.deleted(request, success); publishSaveStatus();
                }
            });
        }
    }

    public void startNewSessionFile() {
        synchronized (FameTableBridge.getInstance()) { tracking.startNew(); saveCurrentLiveSession(false); }
    }

    // --- Private Methods ---

    private void refreshGraph() {
        if (presentation != null && (!SwingUtilities.isEventDispatchThread() || !updatingCharacters)) presentation.request();
    }

    void refreshNow() { presentation.refreshNow(this::renderGraph); }
    FameTrackingModel.GraphSnapshot trackingSnapshot(Integer selectedId, long duration) { return tracking.graph(selectedId, duration); }
    FameTrackingModel.HistoryCounts trackingCounts() { return tracking.counts(); }
    FameRefresh.Counts refreshCounts() { return presentation.counts(); }

    private void renderGraph() {
        Integer selectedId = characterIds.get(character.getSelectedItem());
        long[] minutes = {0, 1, 5, 15, 30, 60};
        FameTrackingModel.GraphSnapshot snapshot = tracking.graph(selectedId, minutes[range.getSelectedIndex()] * 60000);
        boolean newHistory = renderedGeneration != snapshot.generation;
        if (selectedId != null && (newHistory || !snapshot.characterIds.contains(selectedId))) {
            selectedId = null; snapshot = tracking.graph(null, minutes[range.getSelectedIndex()] * 60000);
        }
        updatingCharacters = true;
        try {
            if (newHistory || !new ArrayList<>(characterIds.values()).equals(snapshot.characterIds)) {
                characterIds.clear(); character.removeAllItems(); character.addItem("Follow current character");
                for (int id : snapshot.characterIds) {
                    String label = "Character #" + id; characterIds.put(label, id); character.addItem(label);
                }
                character.setSelectedItem(selectedId == null ? "Follow current character" : "Character #" + selectedId);
            }
        } finally { updatingCharacters = false; }
        renderedGeneration = snapshot.generation;
        int id = snapshot.selectedId;
        ArrayList<Fame> samples = snapshot.samples;
        samples.sort(java.util.Comparator.comparingLong(Fame::getTime));
        saveStatus.setText(tracking.status());
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

    private void saveCurrentLiveSession(boolean resetOnSuccess) {
        FameTablePanel table = FameTablePanel.getInstance();
        FameTrackingModel.Save saving = tracking.prepareSave(table == null ? new HashMap<>() : table.getMapFameData(),
            table == null ? new HashMap<>() : table.classNamesSnapshot());
        publishSaveStatus();
        sessionSaver.accept(saving.session, success -> {
            synchronized (FameTableBridge.getInstance()) {
                tracking.saved(saving, success, resetOnSuccess); publishSaveStatus();
            }
        });
    }

    private void publishSaveStatus() {
        refreshGraph();
        FameTablePanel table = FameTablePanel.getInstance();
        if (table != null) table.setSessionSaveStatus(tracking.status());
    }
}
