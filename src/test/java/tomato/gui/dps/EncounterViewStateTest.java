package tomato.gui.dps;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.*;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class EncounterViewStateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void filtersAndCheckedSelectionRestoreByExactBytesAcrossNewCatalogAndRename() throws Exception {
        Path first = temp.getRoot().toPath().resolve("first.dps"), variant = temp.getRoot().toPath().resolve("variant.dps"), renamed = temp.getRoot().toPath().resolve("renamed.dps");
        DpsData data = EncounterCatalogTest.encounter("Run"); EncounterCatalogTest.write(first, data);
        DpsData other = data.getSaveFile(false); other.totalDungeonPcTime = 9000; EncounterCatalogTest.write(variant, other); Files.copy(variant, renamed);
        EncounterImport a = EncounterImport.read(first), b = EncounterImport.read(variant), alias = EncounterImport.read(renamed);
        Memory memory = new Memory();
        SwingUtilities.invokeAndWait(() -> {
            TomatoData source = new TomatoData(); DpsGUI dps = new DpsGUI(source); dps.encounters().add(a); EncounterCatalog.Entry chosen = dps.encounters().add(b).entry;
            DungeonListGUI view = new DungeonListGUI(dps, source, memory.store); JTable table = named(view, "saved-encounters", JTable.class); await(() -> table.getRowCount() == 3);
            for (int row = 0; row < table.getRowCount(); row++) if ("variant.dps".equals(table.getValueAt(row, 7))) { table.setRowSelectionInterval(row, row); table.setValueAt(true, row, 0); }
            named(view, "encounter-search", JTextField.class).setText("Run");
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4, SortOrder.DESCENDING))); table.getColumnModel().getColumn(2).setWidth(307); view.saveViewState();
            TomatoData secondSource = new TomatoData(); DpsGUI secondDps = new DpsGUI(secondSource); EncounterCatalog.Entry restoredEntry = secondDps.encounters().add(alias).entry; secondDps.encounters().add(a);
            assertNotEquals(chosen.id, restoredEntry.id);
            DungeonListGUI reopened = new DungeonListGUI(secondDps, secondSource, memory.store); JTable restored = named(reopened, "saved-encounters", JTable.class); await(() -> restored.getRowCount() == 3);
            assertEquals("Run", named(reopened, "encounter-search", JTextField.class).getText());
            assertEquals("renamed.dps", restored.getValueAt(restored.getSelectedRow(), 7)); assertEquals(Boolean.TRUE, restored.getValueAt(restored.getSelectedRow(), 0));
            assertTrue(secondDps.encounters().checked(restoredEntry.id)); assertEquals(1, secondDps.encounters().checkedEntries().size());
            assertEquals(307, restored.getColumnModel().getColumn(2).getWidth());
            secondDps.setIndex(-1); // Explicit meter navigation wins over an older dialog selection.
            DungeonListGUI live = new DungeonListGUI(secondDps, secondSource, memory.store); JTable liveTable = named(live, "saved-encounters", JTable.class);
            await(() -> liveTable.getRowCount() == 3); assertEquals("Live", liveTable.getValueAt(liveTable.getSelectedRow(), 2));
            assertTrue(secondDps.encounters().checked(restoredEntry.id));
            DpsGUI.clearDpsLogs(); reopened.saveViewState(); secondDps.encounters().add(alias);
            DungeonListGUI cleared = new DungeonListGUI(secondDps, secondSource, memory.store); await(() -> named(cleared, "saved-encounters", JTable.class).getRowCount() == 2);
            assertTrue(secondDps.encounters().checkedEntries().isEmpty());
        });
    }
}
