package tomato.gui.stats;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.TomatoData;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.realmshark.RealmCharacter;

/** Session fame and map visits. All mutable tracking state belongs to the EDT. */
public class FameTablePanel extends JPanel {
    private static FameTablePanel INSTANCE;
    private final TomatoData tomatoData;
    private final HashMap<Integer, ArrayList<Fame>> fameData = new HashMap<>();
    private final HashMap<Integer, Fame> lastFameEntries = new HashMap<>();
    private final HashMap<Integer, Double> sessionStartFame = new HashMap<>();
    private final HashMap<Integer, Long> observedTime = new HashMap<>();
    private final HashMap<Integer, String> characterClassNames = new HashMap<>();
    private final HashMap<Integer, ArrayList<MapFameData>> mapFameData = new HashMap<>();
    private final HashMap<Integer, MapFameData> currentMapData = new HashMap<>();
    private int currentCharacterId = -1;
    private String currentMapName = "";
    private int transitionCharacterId = -1;
    private long transitionTime;
    private double transitionFame;
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
        INSTANCE = this; this.tomatoData = tomatoData;
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
        views.addChangeListener(e -> status.setVisible(views.getSelectedIndex() == 0));
        saveStatus.setName("fame-table-save-status");
        for (JLabel label : new JLabel[]{status, mapStatus, saveStatus}) label.setFont(ContentStyle.metadata(ContentStyle.body()));
        add(StatsUi.stack(status, saveStatus, StatsUi.note("Session scope · Rates use observed sample time, excluding other characters. Map filters affect the breakdown only. Open visits stop at the latest sample.")), BorderLayout.SOUTH);
        StatsUi.onSearch(search, this::refresh); StatsUi.onSearch(mapSearch, this::refreshMaps);
        activity.addActionListener(e -> refresh()); mapView.addActionListener(e -> refreshMaps()); gainedOnly.addActionListener(e -> refreshMaps());
        reset.addActionListener(e -> { search.setText(""); activity.setSelectedIndex(0); resetDungeonFilters(); });
        refresh();
    }

    public static FameTablePanel getInstance() { return INSTANCE; }
    void setSessionSaveStatus(String text) { saveStatus.setText(text); }
    private static void onEdt(Runnable action) { if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action); }
    public static void updateRealmChars() { if (INSTANCE != null) onEdt(INSTANCE::populateFromCharacterData); }
    public static void handleMapChange(String name) { if (INSTANCE != null) INSTANCE.onMapChange(name); }

    public void updateFame(int charId, long fame, long time, String className) {
        onEdt(() -> {
            if (className != null && !className.isEmpty()) characterClassNames.put(charId, className);
            Fame previous = lastFameEntries.get(charId);
            if (previous != null && time < previous.getTime()) return;
            boolean sameCharacter = currentCharacterId == charId;
            if (!sameCharacter) {
                // A previous character's last sample is the last point we can attribute to it.
                finishMap(currentCharacterId);
                currentCharacterId = charId;
            }
            if (!sessionStartFame.containsKey(charId)) sessionStartFame.put(charId, (double)fame);
            if (sameCharacter && previous != null) observedTime.merge(charId, Math.max(0, time - previous.getTime()), Long::sum);
            ArrayList<Fame> samples = fameData.computeIfAbsent(charId, id -> new ArrayList<>());
            if (previous == null || fame != previous.getFame() || time != previous.getTime()) samples.add(new Fame(fame, time));
            lastFameEntries.put(charId, new Fame(fame, time));
            MapFameData map = currentMapData.get(charId);
            if (map == null && !currentMapName.isEmpty()) {
                boolean knownBaseline = transitionCharacterId == charId && transitionTime <= time;
                map = new MapFameData(currentMapName, knownBaseline ? transitionTime : time, knownBaseline ? transitionFame : fame);
                currentMapData.put(charId, map); transitionCharacterId = -1;
            }
            if (map != null) { map.endTime = Math.max(map.startTime, time); map.endFame = fame; }
            refresh();
        });
    }

    public void onMapChange(String name) { onMapChange(name, System.currentTimeMillis()); }
    void onMapChange(String name, long time) {
        if (name == null) return;
        onEdt(() -> {
            MapFameData current = currentMapData.get(currentCharacterId);
            if (current != null) current.endTime = Math.max(current.endTime, time);
            finishMap(currentCharacterId);
            currentMapName = name;
            Fame baseline = lastFameEntries.get(currentCharacterId);
            transitionCharacterId = baseline == null ? -1 : currentCharacterId;
            transitionTime = time; transitionFame = baseline == null ? 0 : baseline.getFame();
            // Wait for the first character sample; a map change can accompany a character switch.
            refreshMaps();
            FameTableBridge.getInstance().triggerMapChangeAutoSave();
        });
    }
    private void finishMap(int id) {
        MapFameData previous = currentMapData.remove(id);
        if (previous != null) mapFameData.computeIfAbsent(id, key -> new ArrayList<>()).add(previous);
    }
    public void checkForCharacterChange() {
        onEdt(() -> {
            if (tomatoData != null && tomatoData.getCharId() != -1 && currentCharacterId != tomatoData.getCharId()) {
                finishMap(currentCharacterId); currentCharacterId = -1; refresh();
            }
        });
    }

    private void populateFromCharacterData() {
        if (tomatoData == null || tomatoData.chars == null) return;
        for (RealmCharacter character : tomatoData.chars) {
            if (character.charId == 0 || character.classString == null) continue;
            characterClassNames.put(character.charId, character.classString);
            // Account inventory is a display baseline, not an observed live sample.
            sessionStartFame.putIfAbsent(character.charId, (double)character.fame);
        }
        refresh();
    }
    private String displayName(int id) { return getClassNameForCharacterId(id) + " (#" + id + ")"; }
    private double gain(int id) {
        Fame last = lastFameEntries.get(id);
        return last == null ? 0 : last.getFame() - sessionStartFame.getOrDefault(id, last.getFame());
    }
    private boolean include(int id) {
        return StatsUi.matches(displayName(id), search.getText())
            && (activity.getSelectedIndex() != 1 || id == currentCharacterId)
            && (activity.getSelectedIndex() != 2 || gain(id) > 0);
    }
    private void refresh() {
        tableModel.setRowCount(0); double totalGain = 0; long totalTime = 0;
        for (int id : new TreeSet<>(sessionStartFame.keySet())) {
            if (!include(id)) continue;
            Fame last = lastFameEntries.get(id); double initial = sessionStartFame.get(id);
            long elapsed = observedTime.getOrDefault(id, 0L); double gained = gain(id);
            tableModel.addRow(new Object[]{displayName(id), id == currentCharacterId ? "Current" : last == null ? "Not observed" : "Inactive",
                initial, last == null ? initial : last.getFame(), gained, elapsed, elapsed > 0 ? gained * 3600000.0 / elapsed : null});
            totalGain += gained; totalTime += elapsed;
        }
        metrics[0].setText(Integer.toString(tableModel.getRowCount())); metrics[1].setText(Formatters.formatNumber(totalGain, 0));
        metrics[2].setText(Formatters.formatDurationHMS(totalTime)); metrics[3].setText(totalTime > 0 ? Formatters.formatNumber(totalGain * 3600000.0 / totalTime, 1) : "—");
        status.setText(sessionStartFame.isEmpty() ? "Waiting for character samples. Enter the Realm with capture running."
            : tableModel.getRowCount() + " of " + sessionStartFame.size() + " characters shown · Click column headings to sort");
        refreshMaps();
    }
    private void refreshMaps() {
        mapModel.setRowCount(0);
        Map<String, double[]> grouped = new TreeMap<>();
        int count = 0; double gain = 0; long duration = 0;
        HashMap<Integer, ArrayList<MapFameData>> all = getMapFameData();
        for (int id : new TreeSet<>(all.keySet())) {
            if (!include(id)) continue;
            for (MapFameData map : all.get(id)) {
                if (!StatsUi.matches(map.mapName, mapSearch.getText()) || (gainedOnly.isSelected() && map.getFameGained() <= 0)) continue;
                long elapsed = Math.max(0, map.getTimeSpent()); double gained = map.getFameGained();
                boolean open = currentMapData.containsKey(id) && map == currentMapData.get(id);
                count++; gain += gained; duration += elapsed;
                if (mapView.getSelectedIndex() == 1) {
                    mapModel.addRow(new Object[]{map.mapName, displayName(id), 1, elapsed, gained,
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

    private void resetSessions(boolean deleteFile) {
        if (!deleteFile) FameTableBridge.getInstance().triggerMapChangeAutoSave();
        lastFameEntries.forEach((id, last) -> sessionStartFame.put(id, last.getFame()));
        observedTime.clear(); fameData.clear(); mapFameData.clear(); currentMapData.clear();
        // The first sample in the new session establishes the time baseline.
        lastFameEntries.clear(); currentCharacterId = -1; transitionCharacterId = -1; refresh();
        if (deleteFile) FameTableBridge.getInstance().clearCurrentSessionFile();
        else FameTableBridge.getInstance().startNewSessionFile();
    }
    public ArrayList<Fame> getFameData(int id) { return fameData.get(id); }
    public Double getCurrentFame(int id) { Fame last = lastFameEntries.get(id); return last == null ? null : last.getFame(); }
    public String getClassNameForCharacterId(int id) { return characterClassNames.getOrDefault(id, "Char"); }
    /** Includes each current visit exactly once, so autosave and the explorer agree. */
    public HashMap<Integer, ArrayList<MapFameData>> getMapFameData() {
        HashMap<Integer, ArrayList<MapFameData>> result = new HashMap<>();
        mapFameData.forEach((id, rows) -> result.put(id, new ArrayList<>(rows)));
        currentMapData.forEach((id, row) -> result.computeIfAbsent(id, key -> new ArrayList<>()).add(row));
        return result;
    }
    public void setMapFameData(HashMap<Integer, ArrayList<MapFameData>> data) { mapFameData.clear(); mapFameData.putAll(data); refreshMaps(); }
    public HashMap<Integer, MapFameData> getCurrentMapData() { return currentMapData; }
    public void setCurrentMapData(HashMap<Integer, MapFameData> data) { currentMapData.clear(); currentMapData.putAll(data); refreshMaps(); }
    public void resetDungeonFilters() { mapSearch.setText(""); gainedOnly.setSelected(false); mapView.setSelectedIndex(0); refreshMaps(); }
}
