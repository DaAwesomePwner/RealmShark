package tomato.gui.character;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

/** The Roster tab's two cards: Enter and double-click open a row's sheet, the back link returns, and the gallery's row feed. */
public class CharacterRosterViewTest {
    private static final String ACCOUNT = CharacterJournal.accountKey("roster-view-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private CharacterJournal journal() {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.receivedAt = 1000L * id; c.supplied("class"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
        return journal;
    }
    private static String keyAt(JTable roster, int row) {
        String label = roster.getValueAt(row, 0).toString();
        return ACCOUNT + ":" + label.substring(label.lastIndexOf('#') + 1);
    }

    @Test public void enterAndDoubleClickOpenARowsSheetAndTheBackLinkReturnsToTheList() throws Exception {
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                assertEquals("character-roster-view", view.getName());
                JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
                assertEquals(3, roster.getRowCount()); assertFalse(view.showingSheet());
                assertTrue(view.listPanel().isVisible()); assertFalse(view.sheet().isVisible());
                roster.setRowSelectionInterval(1, 1);
                RosterFixtures.enter(view);
                assertTrue("Enter opens the selected row", view.showingSheet());
                assertEquals(keyAt(roster, 1), view.sheet().key());
                assertFalse(view.listPanel().isVisible()); assertTrue(view.sheet().isVisible());
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse("Without a navigator the back link switches cards in place", view.showingSheet());
                assertTrue(view.listPanel().isVisible());
                assertEquals("The list keeps its selection", 1, roster.getSelectedRow());
                Rectangle cell = roster.getCellRect(2, 0, true);
                roster.dispatchEvent(new MouseEvent(roster, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 1, false, MouseEvent.BUTTON1));
                assertFalse("A single click only selects", view.showingSheet());
                roster.dispatchEvent(new MouseEvent(roster, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 2, false, MouseEvent.BUTTON1));
                assertTrue("A double-click opens the row under the pointer", view.showingSheet());
                assertEquals(keyAt(roster, 2), view.sheet().key());
            });
        }
    }

    @Test public void theBackLinkKeepsAnUnsavedNotesDraft() throws Exception {
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
                roster.setRowSelectionInterval(0, 0); String key = keyAt(roster, 0);
                RosterFixtures.enter(view);
                JTextArea notes = RosterFixtures.named(view.sheet(), "character-notes", JTextArea.class);
                notes.setText("Typed, never saved");
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse(view.showingSheet());
                assertEquals("Back without Save keeps the note", "Typed, never saved", journal.characterCopy(key).notes);
                RosterFixtures.enter(view);
                assertEquals("…and the sheet shows it again", "Typed, never saved", notes.getText());
            });
        }
    }

    @Test public void visibleRowsFollowSearchAndSortAndNotifyListeners() throws Exception {
        try (CharacterJournal journal = journal()) {
            journal.notes(ACCOUNT + ":2", "needle");
            SwingUtilities.invokeAndWait(() -> {
                CharacterJournalGUI list = new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty);
                AtomicInteger changes = new AtomicInteger();
                list.addRowsListener(changes::incrementAndGet);
                assertEquals(3, list.visibleRows().size());
                JTextField search = RosterFixtures.named(list, "character-search", JTextField.class);
                search.setText("needle");
                assertTrue("Search notifies", changes.get() > 0);
                assertEquals(1, list.visibleRows().size());
                assertEquals(ACCOUNT + ":2", list.visibleRows().get(0).record.key);
                search.setText("");
                JTable roster = RosterFixtures.named(list, "character-roster", JTable.class);
                int before = changes.get();
                roster.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
                assertTrue("A new sort notifies", changes.get() > before);
                List<CharacterRosterQuery.Row> rows = list.visibleRows();
                assertEquals(3, rows.size());
                for (int i = 0; i < rows.size(); i++) assertEquals("Rows follow the table's order", keyAt(roster, i), rows.get(i).record.key);
            });
        }
    }
}
