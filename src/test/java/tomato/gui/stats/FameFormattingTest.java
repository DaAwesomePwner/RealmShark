package tomato.gui.stats;

import com.google.gson.Gson;
import java.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.Test;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSession;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;
import static tomato.gui.modern.FormattingTestSupport.*;

public class FameFormattingTest {
    /** The saved fame viewer's formatting (P6a removed the live Fame Table it was compared with; its values are asserted directly). */
    @Test public void reloadedFameRendersLocaleValuesWithoutChangingTypedSorting() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                SwingUtilities.invokeAndWait(() -> {
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
                        JTable history = named(saved, "saved-fame-characters", JTable.class);
                        boolean german = locale.equals(Locale.GERMANY);
                        assertEquals(german ? "1.234.567" : "1,234,567", cell(history, 0, 3));
                        assertEquals(german ? "1.233.333" : "1,233,333", cell(history, 0, 4));
                        assertEquals(german ? "-1.234,0" : "-1,234.0", cell(history, 0, 5));
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
                        JComboBox<?> views = named(panel, "loot-views", JComboBox.class); views.setSelectedItem(LootExploreModel.liveView(3));
                        tomato.gui.kit.StatTile[] metrics = field(panel, "metrics", tomato.gui.kit.StatTile[].class);
                        assertEquals(german ? "1.234" : "1,234", metrics[0].valueText());
                        assertEquals(german ? "2.469" : "2,469", metrics[1].valueText());
                        assertEquals(german ? "1.234" : "1,234", metrics[2].valueText()); assertEquals(metrics[0].valueText(), metrics[3].valueText());
                        JTable bags = named(panel, "loot-view-3", JTable.class);
                        assertEquals(metrics[0].valueText(), cell(bags, 0, 1)); assertEquals(metrics[1].valueText(), cell(bags, 0, 2));
                        views.setSelectedItem(LootExploreModel.liveView(0)); JTable items = named(panel, "loot-view-0", JTable.class);
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
                        String scope = field(panel, "scopeNote", JTextArea.class).getText();
                        assertTrue(scope.contains(german ? "globally newest 1.000 bags by timestamp" : "globally newest 1,000 bags by timestamp"));
                        assertTrue(scope.contains("ties: session and record order"));
                        assertTrue(scope.contains("item summaries retain the full app session"));
                        items.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(2, SortOrder.ASCENDING)));
                        assertEquals(1, items.getValueAt(0, 2)); assertEquals(1234, items.getValueAt(items.getRowCount() - 1, 2));
                        views.setSelectedItem(LootExploreModel.liveView(5));
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
}
