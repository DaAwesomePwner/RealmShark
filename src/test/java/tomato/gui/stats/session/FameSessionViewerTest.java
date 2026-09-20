package tomato.gui.stats.session;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.stats.Fame;
import tomato.gui.stats.GraphPanel;
import tomato.gui.stats.data.MapFameData;
import static org.junit.Assert.*;

public class FameSessionViewerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void reloadedZeroGainHistoryIsVisibleAndGainFilterOnlyHidesVisits() throws Exception {
        FameSession session = new FameSession("Flat history");
        session.addCharacterData(1, "Wizard", Arrays.asList(new Fame(100, 1000), new Fame(100, 61000)));
        session.addCharacterMapData(1, Collections.singletonList(visit("Nexus", 100, 100, 60000)));
        withViewer(reload(session), view -> {
            JTable characters = named(view, "saved-fame-characters", JTable.class);
            JTable maps = named(view, "saved-fame-maps", JTable.class);
            JComboBox<?> choices = named(view, "saved-fame-character", JComboBox.class);
            GraphPanel graph = named(view, "saved-fame-graph", GraphPanel.class);
            JCheckBox gain = named(view, "saved-fame-gained-only", JCheckBox.class);
            assertFalse(gain.isSelected());
            assertEquals(1, characters.getRowCount()); assertEquals(1, choices.getItemCount());
            assertEquals(2, characters.getValueAt(0, 2)); assertEquals(0.0, characters.getValueAt(0, 5));
            assertEquals(1, maps.getRowCount()); assertEquals(0.0, maps.getValueAt(0, 2));
            assertEquals(60000L, maps.getValueAt(0, 3)); assertEquals(0.0, maps.getValueAt(0, 4));
            assertEquals(Arrays.asList(new Fame(100, 1000), new Fame(100, 61000)), graph.getScores());
            assertInfo(view, "Characters Tracked: 1", "Total Fame Entries: 2", "Total Map Fame Entries: 1",
                "Graph Samples (all for selected character): 2", "Map Visits Shown: 1 of 1");
            Object selected = choices.getSelectedItem();
            gain.doClick();
            assertEquals(0, maps.getRowCount()); assertEquals(1, characters.getRowCount());
            assertSame(selected, choices.getSelectedItem()); assertEquals(2, graph.getScores().size());
            assertInfo(view, "Total Map Fame Entries: 1", "Map Visits Shown: 0 of 1",
                "With fame gain (map visits only): true");
            gain.doClick(); assertEquals(1, maps.getRowCount());
        });
    }

    @Test public void reloadedUnionIncludesNegativeMapOnlySingleAndEmptyCharactersWithoutStaleGraphs() throws Exception {
        withViewer(reload(mixedSession()), view -> {
            JTable characters = named(view, "saved-fame-characters", JTable.class);
            JTable maps = named(view, "saved-fame-maps", JTable.class);
            JComboBox<?> choices = named(view, "saved-fame-character", JComboBox.class);
            GraphPanel graph = named(view, "saved-fame-graph", GraphPanel.class);
            assertEquals(6, choices.getItemCount()); assertEquals(6, characters.getRowCount());
            for (int row = 0; row < 6; row++) assertEquals((row + 1) * 10, characters.getValueAt(row, 0));
            assertEquals(-10.0, characters.getValueAt(1, 5));
            assertEquals(15.0, characters.getValueAt(2, 5));
            assertEquals(0, characters.getValueAt(3, 2));
            for (int column = 3; column <= 5; column++) assertNull(characters.getValueAt(3, column));
            assertEquals(1, characters.getValueAt(4, 2)); assertEquals(0.0, characters.getValueAt(4, 5));
            assertEquals(0, characters.getValueAt(5, 2)); assertNull(characters.getValueAt(5, 5));
            assertInfo(view, "Characters Tracked: 6", "Total Fame Entries: 7", "Total Map Fame Entries: 7");

            choices.setSelectedIndex(1);
            assertEquals(Arrays.asList(new Fame(200, 1000), new Fame(190, 61000)), graph.getScores());
            assertEquals(-10.0, maps.getValueAt(0, 2)); assertEquals(-10.0, maps.getValueAt(0, 4));
            choices.setSelectedIndex(3);
            assertTrue(graph.getScores().isEmpty()); assertEquals(2, maps.getRowCount());
            assertTrue(named(view, "saved-fame-graph-status", JLabel.class).getText().contains("No saved fame samples"));
            assertInfo(view, "Graph Samples (all for selected character): 0", "Map Visits Shown: 2 of 2");
            JCheckBox gain = named(view, "saved-fame-gained-only", JCheckBox.class);
            gain.doClick();
            assertEquals(1, maps.getRowCount()); assertEquals(5.0, maps.getValueAt(0, 2));
            assertEquals(6, choices.getItemCount()); assertTrue(graph.getScores().isEmpty());
            gain.doClick(); assertEquals(2, maps.getRowCount());
            choices.setSelectedIndex(4);
            assertEquals(Collections.singletonList(new Fame(123, 1000)), graph.getScores());
            assertEquals(0, maps.getRowCount());
            assertTrue(named(view, "saved-fame-graph-status", JLabel.class).getText().startsWith("1 saved sample"));
            choices.setSelectedIndex(5);
            assertTrue(graph.getScores().isEmpty()); assertEquals(0, maps.getRowCount());
            assertEquals(1, named(view, "saved-fame-dungeon", JComboBox.class).getItemCount());
        });
    }

    @Test public void visitFiltersKeepSelectionsAndTypedNumbersWhileSessionAndGraphScopesStayExplicit() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            withViewer(reload(mixedSession()), view -> {
                JTable characters = named(view, "saved-fame-characters", JTable.class);
                JTable maps = named(view, "saved-fame-maps", JTable.class);
                JComboBox<?> choices = named(view, "saved-fame-character", JComboBox.class);
                JComboBox<?> dungeon = named(view, "saved-fame-dungeon", JComboBox.class);
                JCheckBox gain = named(view, "saved-fame-gained-only", JCheckBox.class);
                GraphPanel graph = named(view, "saved-fame-graph", GraphPanel.class);
                choices.setSelectedIndex(2);
                Object selected = choices.getSelectedItem();
                assertEquals(3, maps.getRowCount()); assertNull(maps.getValueAt(2, 4));
                assertEquals(Integer.class, characters.getColumnClass(0));
                assertEquals(Integer.class, characters.getColumnClass(2));
                assertEquals(Double.class, characters.getColumnClass(5));
                assertEquals(Double.class, maps.getColumnClass(2));
                assertEquals(Long.class, maps.getColumnClass(3));
                assertEquals(Double.class, maps.getColumnClass(4));
                assertEquals("15,0", ((JLabel)characters.prepareRenderer(characters.getCellRenderer(2, 5), 2, 5)).getText());
                gain.doClick();
                assertEquals(1, maps.getRowCount()); assertEquals(15.0, maps.getValueAt(0, 2));
                assertEquals(15.0, maps.getValueAt(0, 4)); assertSame(selected, choices.getSelectedItem());
                gain.doClick(); assertEquals(3, maps.getRowCount());
                dungeon.setSelectedItem("Nexus");
                gain.doClick();
                assertEquals(0, maps.getRowCount()); assertSame(selected, choices.getSelectedItem());
                assertEquals("Nexus", dungeon.getSelectedItem()); assertEquals(3, dungeon.getItemCount());
                assertEquals(2, graph.getScores().size());
                assertInfo(view, "Characters Tracked: 6", "Character Rows: 6", "Total Map Fame Entries: 7",
                    "Map Visits Shown: 0 of 3", "Dungeon: Nexus");
                gain.doClick(); assertEquals(1, maps.getRowCount());
                choices.setSelectedIndex(3); // Nexus remains a valid selection for the map-only character.
                assertEquals("Nexus", dungeon.getSelectedItem()); assertEquals(1, maps.getRowCount());
                choices.setSelectedIndex(1); // No Nexus visits: fall back to all dungeons, not a stale filter.
                assertEquals("All Dungeons", dungeon.getSelectedItem()); assertEquals(1, maps.getRowCount());
                gain.doClick(); assertEquals(0, maps.getRowCount()); assertEquals(2, graph.getScores().size());
                gain.doClick(); assertEquals(1, maps.getRowCount());
                characters.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(5, SortOrder.ASCENDING)));
                int negativeRow = characters.convertRowIndexToView(1);
                int zeroRow = characters.convertRowIndexToView(0);
                int positiveRow = characters.convertRowIndexToView(2);
                assertTrue(negativeRow < zeroRow); assertTrue(zeroRow < positiveRow);
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void reloadedEmptySessionShowsEmptyModelsAndZeroCounts() throws Exception {
        withViewer(reload(new FameSession("Empty")), view -> {
            assertEquals(0, named(view, "saved-fame-characters", JTable.class).getRowCount());
            assertEquals(0, named(view, "saved-fame-maps", JTable.class).getRowCount());
            assertEquals(0, named(view, "saved-fame-character", JComboBox.class).getItemCount());
            assertTrue(named(view, "saved-fame-graph", GraphPanel.class).getScores().isEmpty());
            assertInfo(view, "Characters Tracked: 0", "Total Fame Entries: 0", "Total Map Fame Entries: 0",
                "Selected Character: None", "Map Visits Shown: 0 of 0");
            named(view, "saved-fame-gained-only", JCheckBox.class).doClick();
            assertEquals(0, named(view, "saved-fame-maps", JTable.class).getRowCount());
        });
    }

    private FameSession reload(FameSession session) throws Exception {
        File file = new File(temp.getRoot(), "history.fame");
        try (FameSessionManager.SessionWriter writer = new FameSessionManager.SessionWriter(
                (destination, json) -> Files.write(destination.toPath(), json.getBytes(Charset.defaultCharset())))) {
            assertTrue(writer.save(FameSessionManager.snapshot(session), file, null).get(3, TimeUnit.SECONDS));
        }
        FameSession loaded = FameSessionManager.loadSession(file);
        assertNotNull(loaded); loaded.setReadOnly(true);
        return loaded;
    }

    private static void withViewer(FameSession session, Consumer<FameSessionViewer> checks) throws Exception {
        String saved = new Gson().toJson(session);
        SwingUtilities.invokeAndWait(() -> {
            FameSessionViewer viewer = new FameSessionViewer(session);
            try { checks.accept(viewer); }
            finally { viewer.dispose(); }
        });
        assertEquals("Viewing/filtering must not mutate saved samples, visits or metadata", saved, new Gson().toJson(session));
    }

    private static FameSession mixedSession() {
        FameSession session = new FameSession("Mixed history");
        session.addCharacterData(10, "Wizard", Arrays.asList(new Fame(100, 1000), new Fame(100, 61000)));
        session.addCharacterMapData(10, Collections.singletonList(visit("Nexus", 100, 100, 60000)));
        // Deliberately persisted out of order: table endpoints and graph must use the same chronology.
        session.addCharacterData(20, "Knight", Arrays.asList(new Fame(190, 61000), new Fame(200, 1000)));
        session.addCharacterMapData(20, Collections.singletonList(visit("Lost Halls", 200, 190, 60000)));
        session.addCharacterData(30, "Priest", Arrays.asList(new Fame(50, 1000), new Fame(65, 61000)));
        session.addCharacterMapData(30, Arrays.asList(visit("Nexus", 50, 50, 60000),
            visit("Lost Halls", 50, 65, 60000), visit("Lost Halls", 65, 65, 0)));
        session.addCharacterMapData(40, "Rogue", Arrays.asList(visit("Nexus", 300, 300, 60000),
            visit("Lost Halls", 300, 305, 60000)));
        session.addCharacterData(50, "Archer", Collections.singletonList(new Fame(123, 1000)));
        session.addCharacterData(60, Collections.emptyList());
        session.getCharacterClassNames().put(99, "Metadata without records");
        return session;
    }

    private static MapFameData visit(String map, double start, double end, long duration) {
        MapFameData visit = new MapFameData(map, 1000, start);
        visit.endTime += duration; visit.endFame = end;
        return visit;
    }

    private static void assertInfo(FameSessionViewer view, String... fragments) {
        String info = named(view, "saved-fame-session-info", JTextArea.class).getText();
        for (String fragment : fragments) assertTrue(fragment + "\n" + info, info.contains(fragment));
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T found = named((Container)child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
