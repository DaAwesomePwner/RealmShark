package tomato.gui.stats;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.DungeonStatData;
import tomato.backend.data.DungeonStatData.Snapshot;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** Cumulative dungeon counters, with a selected-dungeon enemy and loot breakdown. */
public class DungeonStats extends JPanel {
    private static DungeonStats INSTANCE;
    private volatile DungeonStatData source;
    private volatile boolean dirty;
    private List<Snapshot> snapshots = Collections.emptyList();
    private final JTextField search = StatsUi.search("dungeon-search", "Search dungeons", 22);
    private final JTextField detailSearch = StatsUi.search("dungeon-detail-search", "Search enemies or items", 20);
    private final JComboBox<String> activity = new JComboBox<>(new String[]{"All activity", "With loot", "With activity-recorded exits"});
    private final JComboBox<String> enemy = new JComboBox<>();
    private final Map<String, Integer> enemyIds = new LinkedHashMap<>();
    private final JLabel[] metrics = new JLabel[4];
    private final JLabel detailTitle = new JLabel("Select a dungeon to explore its enemies and loot");
    private final JLabel status = new JLabel("No dungeon history yet. Start capture and change instance.");
    private final DefaultTableModel dungeons = StatsUi.model(
        new String[]{"Dungeon", "Activity-recorded exits", "Finalized time", "Avg / exit", "Hit events", "Items", "Ongoing contribution"},
        String.class, Integer.class, Long.class, Long.class, Long.class, Long.class, String.class);
    private final DefaultTableModel enemies = StatsUi.model(new String[]{"Icon", "Enemy", "Hit events", "Items"},
        Icon.class, String.class, Integer.class, Long.class);
    private final DefaultTableModel items = StatsUi.model(new String[]{"Icon", "Item", "Count", "Dropper"},
        Icon.class, String.class, Integer.class, String.class);
    private final JTable dungeonTable = StatsUi.table(dungeons, "dungeon-table");
    private final JTable enemyTable = StatsUi.table(enemies, "dungeon-enemies");
    private final JTable itemTable = StatsUi.table(items, "dungeon-items");
    private final javax.swing.Timer refreshTimer = new javax.swing.Timer(750, e -> { if (isShowing() && dirty) refreshData(); });
    private boolean rebuilding;
    private final boolean currentSession;

    public DungeonStats() {
        this(false, true);
    }
    public DungeonStats(boolean currentSession) { this(currentSession, true); }
    private DungeonStats(boolean currentSession, boolean owner) {
        this.currentSession = currentSession;
        if (owner) INSTANCE = this; setLayout(new BorderLayout(0, 8));
        JPanel filters = StatsUi.controls(); filters.add(search); filters.add(activity);
        activity.getAccessibleContext().setAccessibleName("Dungeon activity filter");
        JButton reset = new JButton("Reset filters"); filters.add(reset);
        add(StatsUi.stack(StatsUi.heading(currentSession ? "Current session dungeons" : "Dungeon history", "Select a row for recorded enemy and item details in this scope."),
            StatsUi.metrics(metrics, "Dungeons shown", "Activity-recorded exits", "Finalized time", "Observed items"), filters), BorderLayout.NORTH);
        StatsUi.durationColumn(dungeonTable, 2); StatsUi.durationColumn(dungeonTable, 3);
        StatsUi.countColumns(dungeonTable, 1, 4, 5);
        StatsUi.countColumns(enemyTable, 2, 3); StatsUi.countColumns(itemTable, 2);
        dungeonTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        dungeonTable.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
        enemyTable.getColumnModel().getColumn(0).setMaxWidth(40); itemTable.getColumnModel().getColumn(0).setMaxWidth(40);
        JPanel detailFilters = StatsUi.controls(); detailFilters.add(detailSearch);
        JPanel dropper = new JPanel(new BorderLayout(6, 0)); JLabel dropperLabel = new JLabel("Dropper"); dropperLabel.setLabelFor(enemy);
        dropper.add(dropperLabel, BorderLayout.WEST); dropper.add(enemy); detailFilters.add(dropper);
        enemy.getAccessibleContext().setAccessibleName("Dungeon dropper filter");
        enemy.setPrototypeDisplayValue("All enemies / unknown"); enemy.setName("dungeon-enemy-filter");
        detailTitle.setFont(ContentStyle.emphasis(ContentStyle.body())); detailTitle.putClientProperty("html.disable", true);
        JPanel detailToolbar = StatsUi.stack(detailTitle, detailFilters); detailToolbar.setVisible(false);
        JTabbedPane tabs = new JTabbedPane(); tabs.setName("dungeon-views");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Dungeons", StatsUi.tableScroll(dungeonTable));
        tabs.addTab("Enemies", StatsUi.tableScroll(enemyTable)); tabs.addTab("Loot by source", StatsUi.tableScroll(itemTable));
        tabs.addChangeListener(e -> { detailToolbar.setVisible(tabs.getSelectedIndex() > 0); revalidate(); });
        dungeonTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && selected() != null) tabs.setSelectedIndex(1);
            }
        });
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(detailToolbar, BorderLayout.NORTH); content.add(tabs, BorderLayout.CENTER); add(content, BorderLayout.CENTER);
        JTextArea note = StatsUi.note("Hit events are not kills; items are observed drops. Exits/time finalize only for areas with tracked activity. Runs counts observed visits, including zero-activity and ongoing visits, so totals can differ. Ongoing hits/items are included here before exit time is finalized. Legacy counters have no date bounds.");
        note.setToolTipText("Maps with no tracked activity may be absent. Hit events do not confirm soulbound credit. Historical dates and drop rates are not recorded.");
        status.setFont(ContentStyle.metadata(ContentStyle.body()));
        add(StatsUi.stack(status, note), BorderLayout.SOUTH);
        StatsUi.onSearch(search, this::refreshRows); activity.addActionListener(e -> refreshRows());
        StatsUi.onSearch(detailSearch, this::refreshDetails); enemy.addActionListener(e -> { if (!rebuilding) refreshDetails(); });
        dungeonTable.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !rebuilding) selectDungeon(); });
        reset.addActionListener(e -> { search.setText(""); activity.setSelectedIndex(0); detailSearch.setText(""); if (enemy.getItemCount() > 0) enemy.setSelectedIndex(0); });
        refreshRows();
    }

    @Override public void addNotify() { super.addNotify(); refreshTimer.start(); if (dirty) refreshData(); }
    @Override public void removeNotify() { refreshTimer.stop(); super.removeNotify(); }

    void refreshData() {
        dirty = false;
        if (source != null) snapshots = Snapshot.canonicalize(currentSession ? source.sessionSnapshot() : source.snapshot());
        refreshRows();
    }
    static JComponent history(java.util.List<Snapshot> snapshots) {
        DungeonStats panel = new DungeonStats(false, false);panel.snapshots=Snapshot.canonicalize(snapshots);panel.refreshRows();return panel;
    }

    private String selectedName() {
        int row = dungeonTable.getSelectedRow(); return row < 0 ? null : (String)dungeonTable.getValueAt(row, 0);
    }
    private Snapshot selected() {
        String name = selectedName();
        for (Snapshot row : snapshots) if (Objects.equals(row.name, name)) return row;
        return null;
    }

    private void refreshRows() {
        String selection = selectedName(); rebuilding = true; dungeons.setRowCount(0);
        long visits = 0, time = 0, loot = 0;
        for (Snapshot row : snapshots) {
            if (!StatsUi.matches(row.name, search.getText())) continue;
            if (activity.getSelectedIndex() == 1 && row.itemCount() == 0) continue;
            if (activity.getSelectedIndex() == 2 && row.visits == 0) continue;
            dungeons.addRow(new Object[]{row.name, row.visits, row.time, row.visits == 0 ? null : row.time / row.visits, row.hitCount(), row.itemCount(),row.ongoingActivity==null?"Not captured":row.ongoingActivity?"Included; time not finalized":"None at snapshot"});
            visits += row.visits; time += row.time; loot += row.itemCount();
        }
        metrics[0].setText(DisplayFormat.formatInteger(dungeons.getRowCount())); metrics[1].setText(DisplayFormat.formatInteger(visits));
        metrics[2].setText(Formatters.formatDurationHMS(time)); metrics[3].setText(DisplayFormat.formatInteger(loot));
        int choose = dungeonTable.getRowCount() > 0 ? 0 : -1;
        for (int r = 0; r < dungeonTable.getRowCount(); r++) if (Objects.equals(selection, dungeonTable.getValueAt(r, 0))) choose = r;
        if (choose >= 0) dungeonTable.setRowSelectionInterval(choose, choose);
        rebuilding = false; selectDungeon();
        status.setText(snapshots.isEmpty() ? "No activity counters in this scope; observed visits are available in Runs."
            : DisplayFormat.formatInteger(dungeons.getRowCount()) + " of " + DisplayFormat.formatInteger(snapshots.size()) + " dungeons shown · Click column headings to sort");
    }

    private void selectDungeon() {
        Object previous = enemy.getSelectedItem(); rebuilding = true;
        enemy.removeAllItems(); enemyIds.clear(); enemy.addItem("All enemies");
        Snapshot row = selected();
        if (row != null) {
            Set<Integer> ids = new TreeSet<>(row.hits.keySet()); ids.addAll(row.loot.keySet());
            for (int id : ids) { String label = name(id) + " (#" + id + ")"; enemyIds.put(label, id); enemy.addItem(label); }
        }
        if (enemyIds.containsKey(previous)) enemy.setSelectedItem(previous);
        rebuilding = false; refreshDetails();
    }

    private void refreshDetails() {
        enemies.setRowCount(0); items.setRowCount(0); Snapshot row = selected();
        detailTitle.setText(row == null ? "No matching dungeon selected" : row.name + " · Enemy and loot breakdown");
        if (row == null) return;
        Integer selectedEnemy = enemyIds.get(enemy.getSelectedItem());
        Set<Integer> ids = new TreeSet<>(row.hits.keySet()); ids.addAll(row.loot.keySet());
        for (int id : ids) {
            if (selectedEnemy != null && selectedEnemy != id) continue;
            Map<Integer, Integer> drops = row.loot.getOrDefault(id, Collections.emptyMap());
            String mob = name(id); boolean mobMatch = StatsUi.matches(mob, detailSearch.getText());
            if (mobMatch) enemies.addRow(new Object[]{icon(id), mob, row.hits.getOrDefault(id, 0), drops.values().stream().mapToLong(Integer::longValue).sum()});
            for (Map.Entry<Integer, Integer> item : drops.entrySet()) {
                if (mobMatch || StatsUi.matches(name(item.getKey()), detailSearch.getText()))
                    items.addRow(new Object[]{icon(item.getKey()), name(item.getKey()), item.getValue(), mob});
            }
        }
    }

    private static String name(int id) {
        if (id == 0) return "Unknown source";
        String name = IdToAsset.objectName(id); return name == null || name.isEmpty() ? "Object #" + id : name;
    }
    private static Icon icon(int id) { return id == 0 ? null : ImageBuffer.getOutlinedIcon(id, 24); }

    public static void update(DungeonStatData data, String dungeon) {
        DungeonStats panel = INSTANCE;
        if (panel != null) { panel.source = data; panel.dirty = true; }
    }
    public static void editFont(Font font) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> editFont(font)); return; }
        if (INSTANCE != null) for (JTable table : new JTable[]{INSTANCE.dungeonTable, INSTANCE.enemyTable, INSTANCE.itemTable})
            ContentStyle.tableFont(table, font, 0);
    }
}
