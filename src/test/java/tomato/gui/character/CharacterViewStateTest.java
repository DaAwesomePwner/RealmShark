package tomato.gui.character;

import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.*;
import tomato.realmshark.RealmCharacter;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class CharacterViewStateTest {
    @Rule public final TableViewRule tableView = new TableViewRule();
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void explicitSelectionSupersedesAnUnresolvedRestoredCharacterAcrossRefreshAndRecreation() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("selection.json"));
        String a = CharacterJournal.accountKey("selection-A"), b = CharacterJournal.accountKey("selection-B");
        RealmCharacter character = new RealmCharacter(); character.charId = 1; character.classNum = 782; character.receivedAt = 1000; character.supplied("class");
        journal.mergeRoster(a, Collections.singletonList(character)); journal.mergeRoster(b, Collections.singletonList(character));
        journal.notes(a + ":1", "needle"); journal.notes(b + ":1", "B initial notes");
        Memory memory = new Memory(); RosterDefinitions definitions = RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI initial = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); initial.bindViewState(memory.store);
            named(initial, "character-search", JTextField.class).setText("needle"); initial.saveViewState();
            assertEquals(a + ":1", savedSelection(memory));
            journal.notes(a + ":1", "A no longer matches"); journal.notes(b + ":1", "needle");
            CharacterJournalGUI restored = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); restored.bindViewState(memory.store);
            JTable table = named(restored, "character-roster", JTable.class);
            assertEquals(1, table.getRowCount()); assertEquals(-1, table.getSelectedRow());
            // Ordinary background refresh must not discard the unresolved A restoration intent.
            journal.notes(a + ":1", "A remains hidden"); restored.refresh(); restored.saveViewState();
            assertEquals(a + ":1", savedSelection(memory)); assertEquals(-1, table.getSelectedRow());
            table.setRowSelectionInterval(0, 0); // Explicitly choose B, superseding the pending A reference.
            assertTrue(table.getValueAt(table.getSelectedRow(), 1).toString().contains(b.substring(0, 6)));
            journal.notes(a + ":1", "A still hidden after B selection"); restored.refresh();
            assertEquals(0, table.getSelectedRow());
            assertTrue(table.getValueAt(table.getSelectedRow(), 1).toString().contains(b.substring(0, 6)));
        });
        SwingUtilities.invokeAndWait(() -> {
            // The automatic save queued by B's selection must persist B, without an explicit save call.
            assertEquals(b + ":1", savedSelection(memory));
            CharacterRosterView reopenedRoster = RosterFixtures.view(journal, () -> 5000, () -> definitions);
            CharacterJournalGUI reopened = reopenedRoster.listPanel(); reopened.bindViewState(memory.store);
            JTable table = named(reopened, "character-roster", JTable.class);
            assertEquals(0, table.getSelectedRow()); assertTrue(table.getValueAt(0, 1).toString().contains(b.substring(0, 6)));
            RosterFixtures.enter(reopenedRoster);
            assertEquals("needle", named(reopenedRoster.sheet(), "character-notes", JTextArea.class).getText());
            assertEquals("A still hidden after B selection", journal.characters().stream().filter(r -> r.account.equals(a)).findFirst().get().notes);
        });
    }
    @Test public void savedViewActionsAreInTheOverflowMenuAndOnlyAFailureShows() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("menu.json"));
        Memory memory = new Memory(); RosterDefinitions definitions = RosterDefinitions.empty();
        CharacterJournalGUI[] view = new CharacterJournalGUI[1];
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.Mode before = DisplayModeModel.application().mode();
            view[0] = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); view[0].bindViewState(memory.store);
            FilterBar bar = named(view[0], "characters-filter-bar", FilterBar.class);
            try {
                for (DisplayModeModel.Mode mode : DisplayModeModel.Mode.values()) {
                    DisplayModeModel.application().set(mode);
                    assertNotNull(mode + ": Save view state is in the ⋯ menu (spec §3.2)", bar.overflow().item("Save view state"));
                    assertNotNull(mode + ": so is Reset saved view state", bar.overflow().item("Reset saved view state"));
                    assertTrue(mode + ": the ⋯ menu shows", bar.overflow().isVisible());
                }
            } finally { DisplayModeModel.application().set(before); }
            assertNull("No view-state buttons in the page", named(view[0], "characters-live-roster-save-state", JButton.class));
            assertFalse("A good state says nothing", named(view[0], "character-view-state", Banner.class).isVisible());
            memory.fail = true;
            bar.overflow().item("Save view state").doClick();
        });
        SwingUtilities.invokeAndWait(() -> { }); // the save's status arrives on the EDT
        SwingUtilities.invokeAndWait(() -> {
            Banner banner = named(view[0], "character-view-state", Banner.class);
            assertTrue("A failed save warns in the page", banner.isVisible()); assertTrue(banner.warns());
            assertTrue(banner.text(), banner.text().startsWith("View state save failed"));
            memory.fail = false;
            named(view[0], "characters-filter-bar", FilterBar.class).overflow().item("Save view state").doClick();
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> assertFalse("A later good save clears the warning", named(view[0], "character-view-state", Banner.class).isVisible()));
        journal.close();
    }

    private static String savedSelection(Memory memory) {
        return JsonParser.parseString(memory.values.get("ux.archive.characters-live-roster")).getAsJsonObject()
            .getAsJsonObject("last").getAsJsonObject("query").getAsJsonObject("facets").getAsJsonObject("values").get("selected").getAsString();
    }
    private static JsonObject savedValues(Memory memory) {
        return JsonParser.parseString(memory.values.get("ux.archive.characters-live-roster")).getAsJsonObject()
            .getAsJsonObject("last").getAsJsonObject("query").getAsJsonObject("facets").getAsJsonObject("values");
    }
    @Test public void aViewSavedBeforeTheSheetOpensTheSheetAtTheSameTab() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("legacy.json"));
        RealmCharacter character = new RealmCharacter(); character.charId = 1; character.classNum = 782; character.receivedAt = 1000; character.supplied("class");
        journal.mergeRoster(CharacterJournal.accountKey("legacy-tab"), Collections.singletonList(character));
        Memory memory = new Memory(); RosterDefinitions definitions = RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI first = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); first.bindViewState(memory.store); first.saveViewState();
            JsonObject document = JsonParser.parseString(memory.values.get("ux.archive.characters-live-roster")).getAsJsonObject();
            JsonObject values = document.getAsJsonObject("last").getAsJsonObject("query").getAsJsonObject("facets").getAsJsonObject("values");
            values.addProperty("tab", "5"); values.remove("sheetTab");
            memory.values.put("ux.archive.characters-live-roster", document.toString());
            CharacterRosterView roster = RosterFixtures.view(journal, () -> 5000, () -> definitions); roster.listPanel().bindViewState(memory.store);
            RosterFixtures.enter(roster);
            assertEquals("Index 5 was the side pane's Goals tab", "goals", roster.sheet().selectedTab());
        });
    }
    @Test public void recreatedFiltersRetainUnknownsAccountIdentitySortAndNotesOwnership() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        String a = CharacterJournal.accountKey("A"), b = CharacterJournal.accountKey("B");
        RealmCharacter first = new RealmCharacter(); first.charId = 1; first.classNum = 782; first.receivedAt = 1000; first.supplied("class");
        RealmCharacter second = new RealmCharacter(); second.charId = 1; second.classNum = 782; second.supplied("class");
        journal.mergeRoster(a, Collections.singletonList(first)); journal.mergeRoster(b, Collections.singletonList(second));
        journal.notes(a + ":1", "Account A notes"); journal.notes(b + ":1", "Account B notes");
        Memory memory = new Memory(); RosterDefinitions definitions = RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView roster = RosterFixtures.view(journal, () -> 5000, () -> definitions);
            CharacterJournalGUI view = roster.listPanel(); view.bindViewState(memory.store);
            JComboBox<?> account = named(view, "character-facet-0", JComboBox.class);
            for (int i = 0; i < account.getItemCount(); i++) if (account.getItemAt(i).toString().contains(b.substring(0, 6))) account.setSelectedIndex(i);
            named(view, "character-facet-2", JComboBox.class).setSelectedIndex(3);
            named(view, "character-facet-3", JComboBox.class).setSelectedIndex(1);
            named(view, "character-facet-4", JComboBox.class).setSelectedIndex(2);
            named(view, "character-facet-7", JComboBox.class).setSelectedIndex(3);
            named(view, "character-facet-5", JSpinner.class).setValue(2); named(view, "character-facet-6", JSpinner.class).setValue(7);
            JTable table = named(view, "character-roster", JTable.class); assertEquals(1, table.getRowCount()); assertNull(table.getValueAt(0, 8));
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(8, SortOrder.DESCENDING))); table.getColumnModel().getColumn(0).setWidth(211);
            RosterFixtures.enter(roster); roster.sheet().tabs().select("notes");
            named(roster.sheet(), "character-notes", JTextArea.class).setText("B unsaved draft"); view.saveViewState();
            JsonObject values = savedValues(memory);
            assertEquals("The saved index keeps its pre-sheet meaning (3 = Notes)", "3", values.get("tab").getAsString());
            assertEquals("notes", values.get("sheetTab").getAsString());
            CharacterRosterView reopenedRoster = RosterFixtures.view(journal, () -> 5000, () -> definitions);
            CharacterJournalGUI reopened = reopenedRoster.listPanel(); reopened.bindViewState(memory.store);
            JTable restored = named(reopened, "character-roster", JTable.class); assertEquals(1, restored.getRowCount());
            assertTrue(restored.getValueAt(restored.getSelectedRow(), 1).toString().contains(b.substring(0, 6))); assertNull(restored.getValueAt(0, 8));
            assertEquals(3, named(reopened, "character-facet-2", JComboBox.class).getSelectedIndex());
            assertEquals(3, named(reopened, "character-facet-7", JComboBox.class).getSelectedIndex());
            assertEquals(2, named(reopened, "character-facet-5", JSpinner.class).getValue());
            assertEquals(211, restored.getColumnModel().getColumn(0).getWidth()); assertEquals(8, restored.getRowSorter().getSortKeys().get(0).getColumn());
            assertNull("Restoring the list leaves the sheet closed", reopenedRoster.sheet().key());
            RosterFixtures.enter(reopenedRoster);
            assertEquals("The restored tab is selected when the sheet opens", "notes", reopenedRoster.sheet().selectedTab());
            assertEquals("Account B notes", named(reopenedRoster.sheet(), "character-notes", JTextArea.class).getText());
            assertEquals("B unsaved draft", named(roster.sheet(), "character-notes", JTextArea.class).getText());
            assertFalse(memory.values.toString().contains("Account A notes")); assertFalse(memory.values.toString().contains("unsaved draft"));
            assertEquals("Account A notes", journal.characters().stream().filter(r -> r.account.equals(a)).findFirst().get().notes);
        });
    }
}
