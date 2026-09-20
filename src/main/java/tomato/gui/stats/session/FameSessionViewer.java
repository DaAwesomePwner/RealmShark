package tomato.gui.stats.session;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import tomato.gui.stats.Fame;
import tomato.gui.stats.FameTablePanel;
import tomato.gui.stats.Formatters;
import tomato.gui.stats.GraphPanel;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.modern.DisplayFormat;

/**
 * Viewer for saved fame session files.
 * Displays character fame data, map fame data, and session information in a tabbed interface.
 */
public class FameSessionViewer extends JFrame {

    private final FameSession session;
    private JTabbedPane tabbedPane;
    private JTable characterFameTable;
    private JTable mapFameTable;
    private JTextArea sessionInfoArea;
    private JComboBox<CharacterChoice> characterSelector;
    private JComboBox<String> dungeonFilter;
    private final JCheckBox gainedOnly = new JCheckBox("With fame gain");
    private final JLabel characterStatus = new JLabel();
    private final JLabel mapStatus = new JLabel();
    private final JLabel graphStatus = new JLabel();
    private GraphPanel graphPanel;
    private boolean updatingFilters;

    public FameSessionViewer(FameSession session) {
        realmshark.branding.AppIdentity.apply(this);
        this.session = session;
        initializeUI();
        populateData();
        setVisible(true);
    }

    private void initializeUI() {
        setTitle("Fame Session Viewer - " + session.getSessionName());
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Character selector panel at the top
        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selectorPanel.add(new JLabel("Select Character (Class & ID): "));
        characterSelector = new JComboBox<>();
        characterSelector.setName("saved-fame-character");
        characterSelector.addActionListener(e -> { if (!updatingFilters) updateCharacterData(); });
        selectorPanel.add(characterSelector);
        add(selectorPanel, BorderLayout.NORTH);

        // Create tabbed pane
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Character Fame", createCharacterFamePanel());

        // Use shared GraphPanel (minimal mode - no time range dropdown)
        graphPanel = GraphPanel.createMinimal();
        graphPanel.setName("saved-fame-graph");
        graphPanel.setPreferredSize(new Dimension(800, 400));
        JPanel graph = new JPanel(new BorderLayout());
        graph.add(graphPanel, BorderLayout.CENTER);
        graph.add(graphStatus, BorderLayout.SOUTH);
        graphStatus.setName("saved-fame-graph-status");
        tabbedPane.addTab("Fame Graph", graph);

        tabbedPane.addTab("Map Fame", createMapFamePanel());
        tabbedPane.addTab("Session Info", createSessionInfoPanel());
        add(tabbedPane, BorderLayout.CENTER);

        // Close button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        buttonPanel.add(closeButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel createCharacterFamePanel() {
        JPanel panel = new JPanel(new BorderLayout());

        String[] columnNames = {
            "Character ID",
            "Class Name",
            "Fame Entries",
            "Start Fame",
            "End Fame",
            "Fame Gained",
        };
        DefaultTableModel model = model(columnNames,
            Integer.class, String.class, Integer.class, Double.class, Double.class, Double.class);
        characterFameTable = new JTable(model);
        characterFameTable.setName("saved-fame-characters");
        characterFameTable.setAutoCreateRowSorter(true);
        numberColumn(characterFameTable, 2, -1);
        numberColumn(characterFameTable, 3, -1);
        numberColumn(characterFameTable, 4, -1);
        numberColumn(characterFameTable, 5, 1);

        panel.add(new JScrollPane(characterFameTable), BorderLayout.CENTER);
        characterStatus.setName("saved-fame-character-status");
        panel.add(characterStatus, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createMapFamePanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // Filter panel
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filterPanel.add(new JLabel("Filter Dungeon: "));
        dungeonFilter = new JComboBox<>();
        dungeonFilter.setName("saved-fame-dungeon");
        dungeonFilter.addItem("All Dungeons");
        dungeonFilter.addActionListener(e -> { if (!updatingFilters) updateMapFameData(); });
        filterPanel.add(dungeonFilter);
        gainedOnly.setName("saved-fame-gained-only");
        gainedOnly.setToolTipText("Show only visits with positive fame gain. Character rows and graph samples are unaffected.");
        gainedOnly.addActionListener(e -> updateMapFameData());
        filterPanel.add(gainedOnly);
        panel.add(filterPanel, BorderLayout.NORTH);

        // Table
        String[] columnNames = {
            "Class Name",
            "Map Name",
            "Fame Gained",
            "Time Spent",
            "Fame/Minute",
        };
        DefaultTableModel model = model(columnNames,
            String.class, String.class, Double.class, Long.class, Double.class);
        mapFameTable = new JTable(model);
        mapFameTable.setName("saved-fame-maps");
        mapFameTable.setAutoCreateRowSorter(true);
        numberColumn(mapFameTable, 2, 1);
        numberColumn(mapFameTable, 4, 1);
        mapFameTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                setText(value == null ? "—" : Formatters.formatDurationHMS(((Number)value).longValue()));
            }
        });

        panel.add(new JScrollPane(mapFameTable), BorderLayout.CENTER);
        mapStatus.setName("saved-fame-map-status");
        panel.add(mapStatus, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createSessionInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        sessionInfoArea = new JTextArea();
        sessionInfoArea.setName("saved-fame-session-info");
        sessionInfoArea.setEditable(false);
        sessionInfoArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        panel.add(new JScrollPane(sessionInfoArea), BorderLayout.CENTER);
        return panel;
    }

    private void populateData() {
        populateCharacterFameData();
        updateCharacterData();
    }

    private void populateCharacterFameData() {
        DefaultTableModel model =
            (DefaultTableModel) characterFameTable.getModel();
        model.setRowCount(0);
        Integer selectedId = getSelectedCharacterId();
        updatingFilters = true;
        try {
            characterSelector.removeAllItems();
            for (Integer charId : characterIds()) {
                List<Fame> entries = fameSamples(charId);
                String className = getClassNameForCharacter(charId);
                Double startFame = entries.isEmpty() ? null : entries.get(0).getFame();
                Double endFame = entries.isEmpty() ? null : entries.get(entries.size() - 1).getFame();
                model.addRow(new Object[]{charId, className, entries.size(), startFame, endFame,
                    entries.isEmpty() ? null : endFame - startFame});
                CharacterChoice choice = new CharacterChoice(charId, className);
                characterSelector.addItem(choice);
                if (Integer.valueOf(charId).equals(selectedId)) characterSelector.setSelectedItem(choice);
            }
        } finally {
            updatingFilters = false;
        }
        characterStatus.setText(model.getRowCount() == 0 ? "No saved character or map records."
            : DisplayFormat.formatInteger(model.getRowCount()) + " saved characters · All session records · — means no saved fame samples");
    }

    private void populateDungeonFilter() {
        String selectedDungeon = (String)dungeonFilter.getSelectedItem();
        TreeSet<String> maps = new TreeSet<>();
        for (MapFameData visit : mapVisits(getSelectedCharacterId())) maps.add(visit.mapName);
        updatingFilters = true;
        try {
            dungeonFilter.removeAllItems();
            dungeonFilter.addItem("All Dungeons");
            for (String map : maps) dungeonFilter.addItem(map);
            if (maps.contains(selectedDungeon)) dungeonFilter.setSelectedItem(selectedDungeon);
        } finally {
            updatingFilters = false;
        }
    }

    private void updateMapFameData() {
        DefaultTableModel model = (DefaultTableModel) mapFameTable.getModel();
        model.setRowCount(0);

        Integer selectedCharId = getSelectedCharacterId();
        String selectedDungeon = (String) dungeonFilter.getSelectedItem();
        boolean showAllDungeons = "All Dungeons".equals(selectedDungeon);

        List<MapFameData> visits = mapVisits(selectedCharId);
        for (MapFameData visit : visits) {
            if (!showAllDungeons && !visit.mapName.equals(selectedDungeon)) continue;
            if (gainedOnly.isSelected() && visit.getFameGained() <= 0) continue;
            long timeSpent = Math.max(0, visit.getTimeSpent());
            model.addRow(new Object[]{getClassNameForCharacter(selectedCharId), visit.mapName,
                visit.getFameGained(), timeSpent,
                timeSpent > 0 ? visit.getFameGained() * 60000.0 / timeSpent : null});
        }
        mapStatus.setText(DisplayFormat.formatInteger(model.getRowCount()) + " of " + DisplayFormat.formatInteger(visits.size())
            + " saved visits shown · Selected character · Dungeon and gain filters affect visits only");
        populateSessionInfo();
    }

    private void populateSessionInfo() {
        StringBuilder info = new StringBuilder();
        info
            .append("Session Name: ")
            .append(session.getSessionName())
            .append("\n\n");
        info.append("Time zone: ").append(DisplayFormat.timestampZoneLabel()).append("\n");
        info
            .append("Created: ")
            .append(formatTimestamp(session.getCreatedTimestamp()))
            .append("\n");
        info
            .append("Last Modified: ")
            .append(formatTimestamp(session.getLastModifiedTimestamp()))
            .append("\n\n");
        info
            .append("Entire saved session (unfiltered)\n")
            .append("Characters Tracked: ")
            .append(DisplayFormat.formatInteger(characterIds().size()))
            .append("\n");
        info
            .append("Total Fame Entries: ")
            .append(DisplayFormat.formatInteger(getTotalFameEntries()))
            .append("\n");
        info
            .append("Total Map Fame Entries: ")
            .append(DisplayFormat.formatInteger(getTotalMapFameEntries()))
            .append("\n\n");
        info.append("Current view\n")
            .append("Character Rows: ").append(DisplayFormat.formatInteger(characterFameTable.getRowCount())).append("\n")
            .append("Selected Character: ").append(characterSelector.getSelectedItem() == null
                ? "None" : characterSelector.getSelectedItem()).append("\n")
            .append("Graph Samples (all for selected character): ").append(DisplayFormat.formatInteger(graphPanel.getScores().size())).append("\n")
            .append("Map Visits Shown: ").append(DisplayFormat.formatInteger(mapFameTable.getRowCount())).append(" of ")
            .append(DisplayFormat.formatInteger(mapVisits(getSelectedCharacterId()).size())).append(" for selected character\n")
            .append("Dungeon: ").append(dungeonFilter.getSelectedItem()).append("\n")
            .append("With fame gain (map visits only): ").append(gainedOnly.isSelected()).append("\n\n");
        info
            .append("Description:\n")
            .append(session.getDescription())
            .append("\n\n");
        info.append("Read Only: ").append(session.isReadOnly());

        sessionInfoArea.setText(info.toString());
    }

    private void updateCharacterData() {
        Integer selectedCharId = getSelectedCharacterId();
        ArrayList<Fame> samples = fameSamples(selectedCharId);
        graphPanel.setScores(samples);
        graphStatus.setText(selectedCharId == null ? "No saved character selected."
            : samples.isEmpty() ? "No saved fame samples for this character. Map visits are available separately."
            : samples.size() == 1 ? "1 saved sample for selected character · Another timestamp is needed to draw a graph."
            : DisplayFormat.formatInteger(samples.size()) + " saved samples · Selected character, entire session · Map filters do not affect the graph");
        populateDungeonFilter();
        updateMapFameData();
    }

    private Integer getSelectedCharacterId() {
        CharacterChoice choice = (CharacterChoice)characterSelector.getSelectedItem();
        return choice == null ? null : choice.id;
    }

    private TreeSet<Integer> characterIds() {
        TreeSet<Integer> ids = new TreeSet<>(session.getCharacterFameData().keySet());
        ids.addAll(session.getCharacterMapFameData().keySet());
        return ids;
    }

    private ArrayList<Fame> fameSamples(Integer id) {
        List<Fame> saved = session.getCharacterFameData().get(id);
        ArrayList<Fame> samples = saved == null ? new ArrayList<>() : new ArrayList<>(saved);
        samples.sort(Comparator.comparingLong(Fame::getTime));
        return samples;
    }

    private List<MapFameData> mapVisits(Integer id) {
        List<MapFameData> visits = session.getCharacterMapFameData().get(id);
        return visits == null ? Collections.emptyList() : visits;
    }

    private static final class CharacterChoice {
        final int id;
        final String className;
        CharacterChoice(int id, String className) { this.id = id; this.className = className; }
        @Override public String toString() { return className + " (ID: " + id + ")"; }
    }

    private String getClassNameForCharacter(int charId) {
        String storedClassName = session.getCharacterClassNames().get(charId);
        if (storedClassName != null) {
            return storedClassName;
        }
        FameTablePanel instance = FameTablePanel.getInstance();
        return instance != null
            ? instance.getClassNameForCharacterId(charId)
            : "Char " + charId;
    }

    private long getTotalFameEntries() {
        long total = 0;
        for (List<Fame> entries : session.getCharacterFameData().values()) {
            total += entries.size();
        }
        return total;
    }

    private long getTotalMapFameEntries() {
        long total = 0;
        for (List<MapFameData> entries : session
            .getCharacterMapFameData()
            .values()) {
            total += entries.size();
        }
        return total;
    }

    private String formatTimestamp(long timestamp) {
        return Formatters.formatTimestamp(timestamp);
    }

    private static DefaultTableModel model(String[] names, Class<?>... types) {
        return new DefaultTableModel(names, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) { return types[column]; }
        };
    }

    private static void numberColumn(JTable table, int column, int decimals) {
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                setText(value == null ? DisplayFormat.UNAVAILABLE : decimals < 0
                    ? DisplayFormat.formatExact((Number)value) : Formatters.formatNumber(((Number)value).doubleValue(), decimals));
            }
        };
        renderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(column).setCellRenderer(renderer);
    }

    /**
     * Opens a file chooser to select and view a saved session.
     */
    public static void openSessionViewer() {
        FameSessionManager.loadSessionAsync(null, FameSessionViewer::new);
    }
}
