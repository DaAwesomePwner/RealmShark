package tomato.gui.dps;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.*;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

/** Non-focus Swing behavior; no windows, capture, network or rendering. */
public class EncounterLibraryStateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void sortedFirstRowCanBeCheckedAndHiddenChecksAndSelectionSurviveRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData(); DpsData first = EncounterCatalogTest.encounter("Same"), second = EncounterCatalogTest.encounter("Same");
            second.dungeonStartTime = 2000; data.dpsData.add(first); data.dpsData.add(second);
            DpsGUI dps = new DpsGUI(data); DungeonListGUI view = new DungeonListGUI(dps, data); JTable table = find(view, JTable.class, null);
            await(() -> table.getRowCount() == 3);
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
            table.setRowSelectionInterval(0, 0); String id = dps.currentEncounterId(); assertNotNull(id);
            table.getActionMap().get("toggle-export").actionPerformed(new ActionEvent(table, 0, "SPACE"));
            assertTrue(dps.encounters().checked(id)); assertSame(second, dps.encounters().find(id).data);
            JTextField search = find(view, JTextField.class, "encounter-search"); search.setText("no match");
            assertEquals(1, table.getRowCount()); assertEquals("Live", table.getValueAt(0, 2)); assertEquals(id, dps.currentEncounterId());
            assertEquals(1, dps.encounters().checkedEntries().size());
            data.dpsData.add(EncounterCatalogTest.encounter("Third")); DpsGUI.updateMapPacket(data); view.refreshEncounters();
            await(() -> table.getModel().getRowCount() == 4); search.setText("");
            assertEquals(id, dps.currentEncounterId()); assertEquals(Boolean.TRUE, table.getValueAt(table.getSelectedRow(), 0));
            DungeonListGUI reopened = new DungeonListGUI(dps, data); JTable again = find(reopened, JTable.class, null); await(() -> again.getRowCount() == 4);
            assertEquals(Boolean.TRUE, again.getValueAt(again.getSelectedRow(), 0));
        });
    }
    @Test public void importedEncounterOpensByIdentityAndDoesNotMutateCaptureHistory() throws Exception {
        Path file = temp.getRoot().toPath().resolve("fixture.dps"); EncounterCatalogTest.write(file, EncounterCatalogTest.encounter("Imported"));
        DpsGUI[] dps = new DpsGUI[1]; DungeonListGUI[] view = new DungeonListGUI[1]; TomatoData data = new TomatoData();
        SwingWorker<?, ?>[] job = new SwingWorker<?, ?>[1];
        SwingUtilities.invokeAndWait(() -> { dps[0] = new DpsGUI(data); view[0] = new DungeonListGUI(dps[0], data); job[0] = view[0].importFile(file.toFile()); });
        job[0].get(5, TimeUnit.SECONDS); await(() -> dps[0].encounters().entries().size() == 1);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(data.dpsData.isEmpty());
            AbstractButton open = findButton(view[0], "View imported encounter"); assertTrue(open.isEnabled()); open.doClick();
            assertEquals(dps[0].encounters().entries().get(0).id, dps[0].currentEncounterId());
            job[0] = view[0].importFile(file.toFile());
        });
        job[0].get(5, TimeUnit.SECONDS); await(() -> find(view[0], JTextArea.class, "encounter-status").getText().startsWith("Already loaded"));
        assertEquals(1, dps[0].encounters().entries().size());
    }
    private static AbstractButton findButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && text.equals(((AbstractButton)child).getText())) return (AbstractButton)child;
            if (child instanceof Container) { AbstractButton b = findButton((Container)child, text); if (b != null) return b; }
        }
        return null;
    }
    private static <T> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && (name == null || name.equals(child.getName()))) return type.cast(child);
            if (child instanceof Container) { T value = find((Container)child, type, name); if (value != null) return value; }
        }
        return null;
    }
}
