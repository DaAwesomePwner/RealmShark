package tomato.gui.stats;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.TomatoData;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.realmshark.RealmCharacter;

/** Session fame presentation. Capture updates the detached model independently of the EDT. */
public class FameTablePanel extends JPanel {
    private static volatile FameTablePanel INSTANCE;
    private final TomatoData tomatoData;
    private final FameTrackingModel.Table tracking = new FameTrackingModel.Table();
    private final FameRefresh presentation;
    private volatile String sessionSaveStatus = "Sessions save automatically on map changes.";
    private final JTextField search = StatsUi.search("fame-search", "Search class or character ID", 22);
    private final JComboBox<String> activity = new JComboBox<>(new String[]{"All characters", "Current character", "With session gain"});
    private final JTextField mapSearch = StatsUi.search("fame-map-search", "Search maps", 19);
    private final JComboBox<String> mapView = new JComboBox<>(new String[]{"Group by map", "Individual visits"});
    private final JCheckBox gainedOnly = new JCheckBox("With fame gain");
    private final JLabel[] metrics = new JLabel[4];
    private final JLabel status = new JLabel();
    private final JLabel mapStatus = new JLabel();
    private final JLabel saveStatus = new JLabel("Sessions save automatically on map changes.");
    private final DefaultTableModel tableModel = StatsUi.model(
        new String[]{"Character", "Status", "Initial fame", "Current fame", "Session gain", "Observed time", "Fame / hour"},
        String.class, String.class, Double.class, Double.class, Double.class, Long.class, Double.class);
    private final DefaultTableModel mapModel = StatsUi.model(
        new String[]{"Map", "Character / scope", "Visits", "Observed time", "Fame gained", "Fame / hour", "State", "Entered"},
        String.class, String.class, Integer.class, Long.class, Double.class, Double.class, String.class, String.class);
    private final JTable fameTable = StatsUi.table(tableModel, "fame-characters");
    private final JTable mapTable = StatsUi.table(mapModel, "fame-maps");
    private final JTabbedPane views = new JTabbedPane();

    public FameTablePanel(TomatoData tomatoData) {
        this.tomatoData = tomatoData;
        setLayout(new BorderLayout(0, 8));
        JPanel filters = StatsUi.controls(); filters.add(search); filters.add(activity);
        JButton reset = new JButton("Reset filters"); filters.add(reset);
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.add(StatsUi.heading("Fame explorer", "Compare characters and inspect where their session fame was earned."), BorderLayout.CENTER);
        JButton sessions = new JButton("Sessions"); JPanel sessionControls = StatsUi.controls(); sessionControls.add(sessions);
        heading.add(sessionControls, BorderLayout.EAST);
        add(StatsUi.stack(heading,
            StatsUi.metrics(metrics, "Characters shown", "Session fame gained", "Observed time", "Fame / hour"), filters), BorderLayout.NORTH);
        StatsUi.durationColumn(fameTable, 5); StatsUi.durationColumn(mapTable, 3);
        fameTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        mapTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        fameTable.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4, SortOrder.DESCENDING)));
        mapTable.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4, SortOrder.DESCENDING)));
        views.addTab("Characters", StatsUi.tableScroll(fameTable));
        JPanel maps = new JPanel(new BorderLayout(0, 8));
        JPanel mapFilters = StatsUi.controls(); mapFilters.add(mapSearch); mapFilters.add(mapView); mapFilters.add(gainedOnly);
        maps.add(StatsUi.stack(mapFilters), BorderLayout.NORTH); maps.add(StatsUi.tableScroll(mapTable), BorderLayout.CENTER); maps.add(mapStatus, BorderLayout.SOUTH);
        views.addTab("Map breakdown", maps); add(views, BorderLayout.CENTER);
        JPopupMenu actions = new JPopupMenu();
        JMenuItem newSession = new JMenuItem("New Session"); newSession.setToolTipText("Start fresh tracking. Shift+click deletes the current session file.");
        newSession.addActionListener(e -> resetSessions((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0));
        JMenuItem saved = new JMenuItem("View Saved Sessions"); saved.addActionListener(e -> FameSessionViewer.openSessionViewer());
        JMenuItem mapButton = new JMenuItem("Show Map Fame"); mapButton.addActionListener(e -> views.setSelectedIndex(1));
        actions.add(newSession); actions.add(saved); actions.add(mapButton);
        sessions.addActionListener(e -> actions.show(sessions, 0, sessions.getHeight()));
        views.addChangeListener(e -> { status.setVisible(views.getSelectedIndex() == 0); refresh(); });
        saveStatus.setName("fame-table-save-status");
        for (JLabel label : new JLabel[]{status, mapStatus, saveStatus}) label.setFont(ContentStyle.metadata(ContentStyle.body()));
        add(StatsUi.stack(status, saveStatus, StatsUi.note("Session scope · Rates use observed sample time, excluding other characters. Map filters affect the breakdown only. Open visits stop at the latest sample.")), BorderLayout.SOUTH);
        StatsUi.onSearch(search, this::refresh); StatsUi.onSearch(mapSearch, this::refreshMaps);
        activity.addActionListener(e -> refresh()); mapView.addActionListener(e -> refreshMaps()); gainedOnly.addActionListener(e -> refreshMaps());
        reset.addActionListener(e -> { search.setText(""); activity.setSelectedIndex(0); resetDungeonFilters(); });
        presentation = new FameRefresh(this, () -> render(false));
        INSTANCE = this;
        refresh();
    }

    public static FameTablePanel getInstance() { return INSTANCE; }
    void setSessionSaveStatus(String text) { sessionSaveStatus = text; refresh(); }
    public static void updateRealmChars() {
        FameTablePanel panel = INSTANCE;
        if (panel != null) panel.populateFromCharacterData();
    }
    public static void handleMapChange(String name) {
        FameTablePanel panel = INSTANCE;
        if (panel != null) panel.onMapChange(name);
    }

    public void updateFame(int charId, long fame, long time, String className) {
        synchronized (FameTableBridge.getInstance()) {
            tracking.sample(charId, fame, time, className);
            FameTableBridge.getInstance().tableChanged(this);
        }
        refresh();
    }

    public void onMapChange(String name) { onMapChange(name, System.currentTimeMillis()); }
    void onMapChange(String name, long time) {
        if (name == null) return;
        synchronized (FameTableBridge.getInstance()) {
            tracking.mapChanged(name, time);
            FameTableBridge.getInstance().triggerMapChangeAutoSave();
        }
        refresh();
    }
    public void checkForCharacterChange() {
        if (tomatoData == null) return;
        synchronized (FameTableBridge.getInstance()) { tracking.characterChanged(tomatoData.getCharId()); }
        refresh();
    }

    private void populateFromCharacterData() {
        if (tomatoData == null || tomatoData.chars == null) return;
        synchronized (FameTableBridge.getInstance()) {
            for (RealmCharacter character : tomatoData.chars) {
                if (character.charId == 0 || character.classString == null) continue;
                // Account inventory is a display baseline, not an observed live sample.
                tracking.characterBaseline(character.charId, character.classString, character.fame);
            }
        }
        refresh();
    }
    private boolean include(FameTrackingModel.TableSnapshot data, int id) {
        return StatsUi.matches(data.displayName(id), search.getText())
            && (activity.getSelectedIndex() != 1 || id == data.currentId)
            && (activity.getSelectedIndex() != 2 || data.gain(id) > 0);
    }
    private void refresh() { if (presentation != null) presentation.request(); }
    private void refreshMaps() { refresh(); }

    /** Explicitly rebuild both tables on the EDT, including detached/hidden test fixtures. */
    void refreshNow() { presentation.refreshNow(() -> render(true)); }
    FameTrackingModel.TableSnapshot trackingSnapshot() { return tracking.snapshot(true); }
    FameTrackingModel.TableCounts trackingCounts() { return tracking.counts(); }
    FameRefresh.Counts refreshCounts() { return presentation.counts(); }
    HashMap<Integer, String> classNamesSnapshot() { return tracking.classNames(); }

    private void render(boolean allViews) {
        boolean characters = allViews || views.getSelectedIndex() == 0;
        boolean maps = allViews || views.getSelectedIndex() == 1;
        FameTrackingModel.TableSnapshot data = tracking.snapshot(maps);
        saveStatus.setText(sessionSaveStatus);
        if (characters) tableModel.setRowCount(0);
        double totalGain = 0; long totalTime = 0; int shown = 0;
        for (int id : new TreeSet<>(data.initial.keySet())) {
            if (!include(data, id)) continue;
            Fame last = data.last.get(id); double initial = data.initial.get(id);
            long elapsed = data.observed.getOrDefault(id, 0L); double gained = data.gain(id);
            if (characters) tableModel.addRow(new Object[]{data.displayName(id), id == data.currentId ? "Current" : last == null ? "Not observed" : "Inactive",
                initial, last == null ? initial : last.getFame(), gained, elapsed, elapsed > 0 ? gained * 3600000.0 / elapsed : null});
            shown++; totalGain += gained; totalTime += elapsed;
        }
        metrics[0].setText(Integer.toString(shown)); metrics[1].setText(Formatters.formatNumber(totalGain, 0));
        metrics[2].setText(Formatters.formatDurationHMS(totalTime)); metrics[3].setText(totalTime > 0 ? Formatters.formatNumber(totalGain * 3600000.0 / totalTime, 1) : "—");
        status.setText(data.initial.isEmpty() ? "Waiting for character samples. Enter the Realm with capture running."
            : shown + " of " + data.initial.size() + " characters shown · Click column headings to sort");
        if (maps) renderMaps(data);
    }
    private void renderMaps(FameTrackingModel.TableSnapshot data) {
        mapModel.setRowCount(0);
        Map<String, double[]> grouped = new TreeMap<>();
        int count = 0; double gain = 0; long duration = 0;
        HashMap<Integer, ArrayList<MapFameData>> all = data.maps;
        for (int id : new TreeSet<>(all.keySet())) {
            if (!include(data, id)) continue;
            for (MapFameData map : all.get(id)) {
                if (!StatsUi.matches(map.mapName, mapSearch.getText()) || (gainedOnly.isSelected() && map.getFameGained() <= 0)) continue;
                long elapsed = Math.max(0, map.getTimeSpent()); double gained = map.getFameGained();
                boolean open = map == data.open.get(id);
                count++; gain += gained; duration += elapsed;
                if (mapView.getSelectedIndex() == 1) {
                    mapModel.addRow(new Object[]{map.mapName, data.displayName(id), 1, elapsed, gained,
                        elapsed > 0 ? gained * 3600000.0 / elapsed : null, open ? "Open · latest sample" : "Closed", Formatters.formatTimestamp(map.startTime)});
                } else {
                    double[] sum = grouped.computeIfAbsent(map.mapName, key -> new double[4]);
                    sum[0]++; sum[1] += elapsed; sum[2] += gained; if (open) sum[3]++;
                }
            }
        }
        grouped.forEach((name, sum) -> mapModel.addRow(new Object[]{name, "Matching characters", (int)sum[0], (long)sum[1], sum[2],
            sum[1] > 0 ? sum[2] * 3600000.0 / sum[1] : null, sum[3] > 0 ? (int)sum[3] + " open" : "Closed", "—"}));
        mapStatus.setText(count == 0 ? "No matching map visits. Map tracking begins with the first fame sample in an instance."
            : count + " visits · " + Formatters.formatNumber(gain, 1) + " fame · " + Formatters.formatDurationHMS(duration) + " observed");
    }

    void resetSessions(boolean deleteFile) {
        synchronized (FameTableBridge.getInstance()) {
            if (!deleteFile) FameTableBridge.getInstance().triggerMapChangeAutoSave();
            tracking.reset();
            if (deleteFile) FameTableBridge.getInstance().clearCurrentSessionFile();
            else FameTableBridge.getInstance().startNewSessionFile();
        }
        refresh();
    }
    public Double getCurrentFame(int id) { return tracking.currentFame(id); }
    public String getClassNameForCharacterId(int id) { return tracking.className(id); }
    /** Includes each current visit exactly once, so autosave and the explorer agree. */
    public HashMap<Integer, ArrayList<MapFameData>> getMapFameData() {
        return tracking.maps();
    }
    public void setMapFameData(HashMap<Integer, ArrayList<MapFameData>> data) {
        synchronized (FameTableBridge.getInstance()) { tracking.setMaps(data); }
        refresh();
    }
    public HashMap<Integer, MapFameData> getCurrentMapData() { return tracking.currentMaps(); }
    public void setCurrentMapData(HashMap<Integer, MapFameData> data) {
        synchronized (FameTableBridge.getInstance()) { tracking.setCurrentMaps(data); }
        refresh();
    }
    public void resetDungeonFilters() { mapSearch.setText(""); gainedOnly.setSelected(false); mapView.setSelectedIndex(0); refreshMaps(); }
}
