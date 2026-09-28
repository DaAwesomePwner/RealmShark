package tomato.gui.quest;

import java.awt.*;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class QuestTableKindsTest {
    /** The Board opens on cards; this test measures the table, which is the Table view. */
    @Rule public final QuestViewRule tableView = new QuestViewRule();
    @Test public void questColumnsStartFromTheirKindsAndStillFitTheirExamples() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences()), "quest-table");
            assertTrue(table.getColumnModel().getColumn(1).getPreferredWidth() >= ColumnKind.TEXT.width(table.getFont()));
            assertTrue(table.getColumnModel().getColumn(3).getPreferredWidth() >= ColumnKind.ITEM.width(table.getFont()));
            assertEquals(Math.max(table.getColumnModel().getColumn(4).getMinWidth(), ColumnKind.COUNT.width(table.getFont())),
                table.getColumnModel().getColumn(4).getPreferredWidth());
        });
    }

    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
