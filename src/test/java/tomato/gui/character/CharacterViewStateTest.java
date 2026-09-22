package tomato.gui.character;

import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.*;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class CharacterViewStateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void recreatedFiltersRetainUnknownsAccountIdentitySortAndNotesOwnership() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        String a = CharacterJournal.accountKey("A"), b = CharacterJournal.accountKey("B");
        RealmCharacter first = new RealmCharacter(); first.charId = 1; first.classNum = 782; first.receivedAt = 1000; first.supplied("class");
        RealmCharacter second = new RealmCharacter(); second.charId = 1; second.classNum = 782; second.supplied("class");
        journal.mergeRoster(a, Collections.singletonList(first)); journal.mergeRoster(b, Collections.singletonList(second));
        journal.notes(a + ":1", "Account A notes"); journal.notes(b + ":1", "Account B notes");
        Memory memory = new Memory(); RosterDefinitions definitions = RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI view = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); view.bindViewState(memory.store);
            JComboBox<?> account = named(view, "character-facet-0", JComboBox.class);
            for (int i = 0; i < account.getItemCount(); i++) if (account.getItemAt(i).toString().contains(b.substring(0, 6))) account.setSelectedIndex(i);
            named(view, "character-facet-2", JComboBox.class).setSelectedIndex(3);
            named(view, "character-facet-3", JComboBox.class).setSelectedIndex(1);
            named(view, "character-facet-4", JComboBox.class).setSelectedIndex(2);
            named(view, "character-facet-7", JComboBox.class).setSelectedIndex(3);
            named(view, "character-facet-5", JSpinner.class).setValue(2); named(view, "character-facet-6", JSpinner.class).setValue(7);
            JTable table = named(view, "character-roster", JTable.class); assertEquals(1, table.getRowCount()); assertNull(table.getValueAt(0, 8));
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(8, SortOrder.DESCENDING))); table.getColumnModel().getColumn(0).setWidth(211);
            named(view, "character-detail-tabs", JTabbedPane.class).setSelectedIndex(3);
            named(view, "character-notes", JTextArea.class).setText("B unsaved draft"); view.saveViewState();
            CharacterJournalGUI reopened = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); reopened.bindViewState(memory.store);
            JTable restored = named(reopened, "character-roster", JTable.class); assertEquals(1, restored.getRowCount());
            assertTrue(restored.getValueAt(restored.getSelectedRow(), 1).toString().contains(b.substring(0, 6))); assertNull(restored.getValueAt(0, 8));
            assertEquals(3, named(reopened, "character-facet-2", JComboBox.class).getSelectedIndex());
            assertEquals(3, named(reopened, "character-facet-7", JComboBox.class).getSelectedIndex());
            assertEquals(2, named(reopened, "character-facet-5", JSpinner.class).getValue());
            assertEquals(211, restored.getColumnModel().getColumn(0).getWidth()); assertEquals(8, restored.getRowSorter().getSortKeys().get(0).getColumn());
            assertEquals(3, named(reopened, "character-detail-tabs", JTabbedPane.class).getSelectedIndex());
            assertEquals("Account B notes", named(reopened, "character-notes", JTextArea.class).getText());
            assertEquals("B unsaved draft", named(view, "character-notes", JTextArea.class).getText());
            assertFalse(memory.values.toString().contains("Account A notes")); assertFalse(memory.values.toString().contains("unsaved draft"));
            assertEquals("Account A notes", journal.characters().stream().filter(r -> r.account.equals(a)).findFirst().get().notes);
        });
    }
}
