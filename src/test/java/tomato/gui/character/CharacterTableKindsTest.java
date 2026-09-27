package tomato.gui.character;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class CharacterTableKindsTest {
    @Rule public final TableViewRule tableView = new TableViewRule();
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void rosterUsesColumnKindWidths() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            JTable roster = find(new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty), "character-roster");
            assertEquals(ColumnKind.PLAYER.width(roster.getFont()), roster.getColumn("Character").getPreferredWidth());
            assertEquals(ColumnKind.COUNT.width(roster.getFont()), roster.getColumn("Level").getPreferredWidth());
            assertTrue(roster.getColumn("Last snapshot update").getPreferredWidth() >= ColumnKind.DATE_TIME.width(roster.getFont()));
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
