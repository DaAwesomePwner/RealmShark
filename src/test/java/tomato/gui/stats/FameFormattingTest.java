package tomato.gui.stats;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.Test;
import tomato.backend.data.DungeonStatData;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSession;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;
import static tomato.gui.modern.FormattingTestSupport.*;

public class FameFormattingTest {
    @Test public void liveAndReloadedFameRenderTheSameValuesWithoutChangingTypedSorting() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                SwingUtilities.invokeAndWait(() -> {
                    FameTableBridge.getInstance().setFameTrackerGUI(null);
                    FameTablePanel live = new FameTablePanel(null);
                    live.onMapChange("Lost Halls", 1000);
                    live.updateFame(12345, 1234567, 1000, "Wizard");
                    live.updateFame(12345, 1233333, 61000, "Wizard");
                    live.refreshNow();
                    FameSession source = new FameSession("Formatting");
                    source.addCharacterData(12345, "Wizard", Arrays.asList(new Fame(1234567, 1000), new Fame(1233333, 61000)));
                    source.addCharacterData(12346, "Priest", Arrays.asList(new Fame(0, 1000), new Fame(0, 61000)));
                    source.addCharacterData(12347, "Rogue", Collections.emptyList());
                    source.addCharacterData(12348, "Knight", Arrays.asList(new Fame(1234.125, 1000), new Fame(1234.25, 61000)));
                    MapFameData visit = new MapFameData("Lost Halls", 1000, 1234567);
                    visit.endTime = 61000; visit.endFame = 1233333;
                    source.addCharacterMapData(12345, Collections.singletonList(visit));
                    Gson json = new Gson(); String before = json.toJson(source);
                    FameSession reloaded = json.fromJson(before, FameSession.class);
                    FameSessionViewer saved = new FameSessionViewer(reloaded);
                    try {
                        JTable current = named(live, "fame-characters", JTable.class);
                        JTable history = named(saved, "saved-fame-characters", JTable.class);
                        boolean german = locale.equals(Locale.GERMANY);
                        assertEquals(german ? "1.234.567" : "1,234,567", cell(current, 0, 2));
                        assertEquals(cell(current, 0, 2), cell(history, 0, 3));
                        assertEquals(cell(current, 0, 3), cell(history, 0, 4));
                        assertEquals(german ? "-1.234,0" : "-1,234.0", cell(history, 0, 5));
                        assertEquals(cell(current, 0, 4), cell(history, 0, 5));
                        assertEquals("12345", cell(history, 0, 0));
                        assertEquals("0", cell(history, 1, 3));
                        assertEquals("—", cell(history, 2, 3));
                        assertEquals(german ? "1.234,125" : "1,234.125", cell(history, 3, 3));
                        JTable maps = named(saved, "saved-fame-maps", JTable.class);
                        assertEquals("00:01:00", cell(maps, 0, 3));
                        assertEquals(german ? "-1.234,0" : "-1,234.0", cell(maps, 0, 4));
                        assertEquals(Double.class, history.getColumnClass(5));
                        history.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(5, SortOrder.ASCENDING)));
                        assertTrue(history.convertRowIndexToView(0) < history.convertRowIndexToView(1));
                        assertTrue(named(saved, "saved-fame-session-info", JTextArea.class).getText().contains("Time zone: UTC"));
                        assertEquals(before, json.toJson(reloaded));
                    } finally { saved.dispose(); }
                });
            }
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }

    @Test public void sharedStatsRenderersKeepLargeLongsAndNonfiniteValuesOutOfStringsInTheModel() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            SwingUtilities.invokeAndWait(() -> {
                DefaultTableModel model = StatsUi.model(new String[]{"Count", "Rate"}, Long.class, Double.class);
                model.addRow(new Object[]{9007199254740993L, Double.NaN});
                model.addRow(new Object[]{9L, 0.0});
                model.addRow(new Object[]{100L, Double.POSITIVE_INFINITY});
                JTable table = StatsUi.table(model, "formatting-stats");
                StatsUi.countColumns(table, 0);
                assertEquals("9.007.199.254.740.993", cell(table, 0, 0));
                assertEquals("—", cell(table, 0, 1)); assertEquals("0,0", cell(table, 1, 1));
                assertEquals("—", cell(table, 2, 1));
                table.getRowSorter().toggleSortOrder(0);
                assertEquals(9L, table.getValueAt(0, 0)); assertEquals(100L, table.getValueAt(1, 0));
                assertEquals(9007199254740993L, table.getValueAt(2, 0));
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void numericIdsStayRawUnlessAColumnExplicitlyOptsIntoCountFormatting() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            SwingUtilities.invokeAndWait(() -> {
                DefaultTableModel model = StatsUi.model(new String[]{"Item ID", "Source ID", "Count"}, Integer.class, Long.class, Long.class);
                model.addRow(new Object[]{12345, 9007199254740993L, 9007199254740993L});
                model.addRow(new Object[]{9, 9L, 9L});
                JTable table = StatsUi.table(model, "raw-ids-and-counts");
                StatsUi.countColumns(table, 2);
                String before = new Gson().toJson(model.getDataVector());
                assertEquals("12345", cell(table, 0, 0));
                assertEquals("9007199254740993", cell(table, 0, 1));
                assertEquals("9.007.199.254.740.993", cell(table, 0, 2));
                model.setColumnIdentifiers(new String[]{"Count", "Count", "Item ID"});
                // Column roles are explicit at construction, not inferred from captions.
                StatsUi.countColumns(table, 2);
                assertEquals("12345", cell(table, 0, 0));
                assertEquals("9.007.199.254.740.993", cell(table, 0, 2));
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
                assertEquals(9, table.getValueAt(0, 0)); assertEquals(12345, table.getValueAt(1, 0));
                assertEquals(Integer.class, table.getColumnClass(0)); assertEquals(Long.class, table.getColumnClass(2));
                assertEquals(before, new Gson().toJson(model.getDataVector()));
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void fameSummaryCardsAndVisitStatusFollowLocaleWithoutMutatingTrackingData() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            SwingUtilities.invokeAndWait(() -> {
                FameTableBridge.getInstance().setFameTrackerGUI(null);
                FameTablePanel fame = new FameTablePanel(null);
                FameTrackerGUI graph = new FameTrackerGUI((session, done) -> { throw new AssertionError("Formatting must not save a session"); });
                ArrayList<MapFameData> visits = new ArrayList<>();
                for (int i = 0; i < 1234; i++) {
                    fame.updateFame(12345 + i, 0, 0, "Wizard");
                    fame.updateFame(12345 + i, 2, 2000, "Wizard");
                    FameTrackerGUI.updateFame(12345, i * 2L, i * 2000L);
                    MapFameData visit = new MapFameData("Lost Halls", i * 2000L, 0);
                    visit.endTime = visit.startTime + 2000; visit.endFame = 2; visits.add(visit);
                }
                HashMap<Integer, ArrayList<MapFameData>> maps = new HashMap<>(); maps.put(12345, visits); fame.setMapFameData(maps);
                Gson json = new Gson();
                String tableBefore = json.toJson(fame.trackingSnapshot()), graphBefore = json.toJson(graph.getFameData());
                for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                    Locale.setDefault(Locale.Category.FORMAT, locale);
                    fame.refreshNow(); graph.refreshNow();
                    boolean german = locale.equals(Locale.GERMANY);
                    JLabel[] summaries = field(fame, "metrics", JLabel[].class), graphSummaries = field(graph, "metrics", JLabel[].class);
                    assertEquals(german ? "1.234" : "1,234", summaries[0].getText());
                    assertEquals(german ? "2.468" : "2,468", summaries[1].getText());
                    assertEquals("00:41:08", summaries[2].getText());
                    assertEquals(german ? "3.600,0" : "3,600.0", summaries[3].getText());
                    assertTrue(field(fame, "status", JLabel.class).getText().startsWith(german ? "1.234 of 1.234" : "1,234 of 1,234"));
                    assertTrue(field(fame, "mapStatus", JLabel.class).getText().startsWith(german ? "1.234 visits · 2.468,0 fame" : "1,234 visits · 2,468.0 fame"));
                    assertEquals(german ? "1.234" : "1,234", graphSummaries[3].getText());
                    assertEquals(german ? "3.600,0" : "3,600.0", graphSummaries[2].getText());
                    assertTrue(field(graph, "sampleStatus", JLabel.class).getText().startsWith("Character #12345 ·"));
                    JTable mapTable = named(fame, "fame-maps", JTable.class);
                    assertEquals(german ? "1.234" : "1,234", cell(mapTable, 0, 2));
                    assertEquals(Integer.class, mapTable.getColumnClass(2)); assertEquals(1234, mapTable.getValueAt(0, 2));
                    assertEquals(Double.class, mapTable.getColumnClass(4)); assertEquals(2468.0, mapTable.getValueAt(0, 4));
                    assertEquals(Long.class, mapTable.getColumnClass(3)); assertEquals(2468000L, mapTable.getValueAt(0, 3));
                    assertEquals(tableBefore, json.toJson(fame.trackingSnapshot())); assertEquals(graphBefore, json.toJson(graph.getFameData()));
                }
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }

    @Test public void dungeonCardsAndDetailCountsAgreeWithRenderersWhileObjectIdsAndJsonStayRaw() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            SwingUtilities.invokeAndWait(() -> {
                Gson json = new Gson();
                DungeonStatData data = json.fromJson("{\"data\":{\"Lost Halls\":{\"name\":\"Lost Halls\",\"enteredDungeon\":1234,\"totalTime\":1234000,\"entityDamaged\":{\"2147483000\":1234567},\"entityLoot\":{\"2147483000\":{\"lootList\":{\"2147483001\":1234}}}},\"Sprite World\":{\"name\":\"Sprite World\",\"enteredDungeon\":9,\"totalTime\":9000,\"entityDamaged\":{},\"entityLoot\":{}},\"Nexus\":{\"name\":\"Nexus\",\"enteredDungeon\":0,\"totalTime\":0,\"entityDamaged\":{},\"entityLoot\":{}}}}", DungeonStatData.class);
                String before = json.toJson(data);
                DungeonStats panel = new DungeonStats(); DungeonStats.update(data, null);
                for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                    Locale.setDefault(Locale.Category.FORMAT, locale); panel.refreshData();
                    boolean german = locale.equals(Locale.GERMANY);
                    JLabel[] metrics = field(panel, "metrics", JLabel[].class);
                    assertEquals("3", metrics[0].getText()); assertEquals(german ? "1.243" : "1,243", metrics[1].getText());
                    assertEquals("00:20:43", metrics[2].getText()); assertEquals(german ? "1.234" : "1,234", metrics[3].getText());
                    JTable dungeons = named(panel, "dungeon-table", JTable.class);
                    int large = row(dungeons, 0, "Lost Halls"), empty = row(dungeons, 0, "Nexus");
                    assertEquals(german ? "1.234" : "1,234", cell(dungeons, large, 1));
                    assertEquals("0", cell(dungeons, empty, 1)); assertEquals("—", cell(dungeons, empty, 3));
                    dungeons.setRowSelectionInterval(large, large);
                    JTable enemies = named(panel, "dungeon-enemies", JTable.class), items = named(panel, "dungeon-items", JTable.class);
                    assertEquals(german ? "1.234.567" : "1,234,567", cell(enemies, 0, 2));
                    assertEquals(german ? "1.234" : "1,234", cell(items, 0, 2));
                    assertEquals("Object #2147483001", cell(items, 0, 1));
                    assertTrue(named(panel, "dungeon-enemy-filter", JComboBox.class).getItemAt(1).toString().endsWith("(#2147483000)"));
                    assertEquals(Integer.class, items.getColumnClass(2)); assertEquals(1234, items.getValueAt(0, 2));
                    assertEquals(Long.class, dungeons.getColumnClass(4)); assertEquals(1234567L, dungeons.getValueAt(large, 4));
                    dungeons.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
                    assertEquals(0, dungeons.getValueAt(0, 1)); assertEquals(9, dungeons.getValueAt(1, 1)); assertEquals(1234, dungeons.getValueAt(2, 1));
                    assertEquals(before, json.toJson(data));
                }
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void sharedLootSummaryLabelsAndCountColumnsFollowLocaleWithoutChangingCapturedItems() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        JFrame[] frame = new JFrame[1];
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            SwingUtilities.invokeAndWait(() -> {
                Locale.setDefault(Locale.Category.FORMAT, Locale.US);
                LootDashboard first = new LootDashboard(), mirror = new LootDashboard(first);
                LootDashboard.Item potion = new LootDashboard.Item(999991, "Potion of Test", true);
                LootDashboard.Item item = new LootDashboard.Item(900000, "Item #900000", "EQUIPMENT,WEAPON,T13", ParseEnchants.summarize(""));
                LootDashboard.Item single = new LootDashboard.Item(900001, "Item #900001", "EQUIPMENT,WEAPON,T13", ParseEnchants.summarize(""));
                ArrayList<LootDashboard.Drop> drops = new ArrayList<>();
                for (int i = 0; i < 1234; i++) {
                    drops.add(new LootDashboard.Drop("White", "Dungeon #" + i, "Boss #12345", i * 1000L,
                        i == 0 ? Arrays.asList(potion, item, single) : Arrays.asList(potion, item)));
                }
                first.acceptAll(drops);
                Gson json = new Gson(); Object buckets = field(field(first, "state", Object.class), "buckets", Map.class);
                String bucketsBefore = json.toJson(buckets), recentBefore = json.toJson(first.recentDrops());
                JPanel pair = new JPanel(new java.awt.GridLayout(1, 2)); pair.add(first); pair.add(mirror);
                frame[0] = new JFrame("Formatting integration"); frame[0].setContentPane(pair); frame[0].setSize(1100, 700); frame[0].setVisible(true);
                for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                    Locale.setDefault(Locale.Category.FORMAT, locale);
                    boolean german = locale.equals(Locale.GERMANY);
                    for (LootDashboard panel : Arrays.asList(first, mirror)) {
                        JTabbedPane views = named(panel, "loot-views", JTabbedPane.class); views.setSelectedIndex(3);
                        JLabel[] metrics = field(panel, "metrics", JLabel[].class);
                        assertEquals(german ? "1.234" : "1,234", metrics[0].getText());
                        assertEquals(german ? "2.469" : "2,469", metrics[1].getText());
                        assertEquals(german ? "1.234" : "1,234", metrics[2].getText()); assertEquals(metrics[0].getText(), metrics[3].getText());
                        JTable bags = named(panel, "loot-view-3", JTable.class);
                        assertEquals(metrics[0].getText(), cell(bags, 0, 1)); assertEquals(metrics[1].getText(), cell(bags, 0, 2));
                        views.setSelectedIndex(0); JTable items = named(panel, "loot-view-0", JTable.class);
                        int potionRow = row(items, 1, "Potion of Test"), itemRow = row(items, 1, "Item #900000");
                        assertEquals(german ? "1.234" : "1,234", cell(items, potionRow, 2));
                        assertNull(items.getValueAt(potionRow, 6)); assertEquals("—", cell(items, potionRow, 6));
                        assertEquals("0", cell(items, itemRow, 6)); assertEquals("0", cell(items, itemRow, 7));
                        assertEquals("Item #900000", cell(items, itemRow, 1)); assertEquals("T13", cell(items, itemRow, 4));
                        assertEquals(Integer.class, items.getColumnClass(2)); assertEquals(Integer.class, items.getColumnClass(6));
                        assertTrue(field(panel, "results", JLabel.class).getText().startsWith("3 rows shown"));
                        String totals = named(panel, "loot-enchant-totals", JTextArea.class).getText();
                        assertTrue(totals.startsWith(german ? "2.469 drops shown" : "2,469 drops shown"));
                        assertTrue(totals.contains(german ? "Unknown: 1.234" : "Unknown: 1,234"));
                        assertTrue(totals.contains(german ? "Unenchanted (0 slots): 1.235" : "Unenchanted (0 slots): 1,235"));
                        assertTrue(field(panel, "scopeNote", JTextArea.class).getText().contains(german ? "retains 1.000 bags" : "retains 1,000 bags"));
                        items.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(2, SortOrder.ASCENDING)));
                        assertEquals(1, items.getValueAt(0, 2)); assertEquals(1234, items.getValueAt(items.getRowCount() - 1, 2));
                        views.setSelectedIndex(5);
                        assertTrue(field(panel, "results", JLabel.class).getText().startsWith(german ? "1.234 rows shown" : "1,234 rows shown"));
                        assertArrayEquals(new int[]{1234, 2469}, panel.sessionTotals());
                        assertEquals(recentBefore, json.toJson(panel.recentDrops()));
                    }
                    assertEquals(bucketsBefore, json.toJson(buckets));
                }
            });
        } finally {
            try { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
            finally { Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
        }
    }

    private static int row(JTable table, int column, Object value) {
        for (int row = 0; row < table.getRowCount(); row++) if (Objects.equals(value, table.getValueAt(row, column))) return row;
        throw new AssertionError("Missing row " + value);
    }

    @Test public void enteredTimesStayTypedAndSortChronologicallyAcrossZoneChangesAndDstFallback() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            SwingUtilities.invokeAndWait(() -> {
                FameTableBridge.getInstance().setFameTrackerGUI(null);
                FameTablePanel panel = new FameTablePanel(null); panel.updateFame(12345, 0, 0, "Wizard");
                long earlier = Instant.parse("2026-10-25T00:50:00Z").toEpochMilli(), later = Instant.parse("2026-10-25T01:10:00Z").toEpochMilli();
                ArrayList<MapFameData> visits = new ArrayList<>();
                for (long time : new long[]{later, earlier}) {
                    MapFameData visit = new MapFameData("Lost Halls", time, 0); visit.endTime = time + 60000; visit.endFame = 1; visits.add(visit);
                }
                HashMap<Integer, ArrayList<MapFameData>> history = new HashMap<>(); history.put(12345, visits); panel.setMapFameData(history);
                Gson json = new Gson(); String before = json.toJson(panel.getMapFameData());
                field(panel, "mapView", JComboBox.class).setSelectedIndex(1); panel.refreshNow();
                JTable table = named(panel, "fame-maps", JTable.class);
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(7, SortOrder.ASCENDING)));
                assertEquals(Long.class, table.getColumnClass(7)); assertEquals(earlier, table.getValueAt(0, 7));
                assertEquals("2026-10-25 00:50:00", cell(table, 0, 7));
                java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
                table.getModel().addTableModelListener(event -> changes.incrementAndGet());
                Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
                // Reuse the same table/model without refreshing or copying fame data.
                assertEquals("2026-10-25 02:50:00", cell(table, 0, 7));
                assertEquals("2026-10-25 02:10:00", cell(table, 1, 7));
                JLabel timestamp = (JLabel)table.prepareRenderer(table.getCellRenderer(0, 7), 0, 7);
                assertEquals("2026-10-25 02:50:00 (Europe/Berlin)", timestamp.getToolTipText());
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(7, SortOrder.DESCENDING)));
                assertEquals(later, table.getValueAt(0, 7)); assertEquals(earlier, table.getValueAt(1, 7));
                assertEquals(0, changes.get());
                field(panel, "mapView", JComboBox.class).setSelectedIndex(0); panel.refreshNow();
                assertEquals(Long.class, table.getColumnClass(7)); assertNull(table.getValueAt(0, 7)); assertEquals("—", cell(table, 0, 7));
                assertNull(((JLabel)table.prepareRenderer(table.getCellRenderer(0, 7), 0, 7)).getToolTipText());
                assertEquals(before, json.toJson(panel.getMapFameData()));
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }
}
