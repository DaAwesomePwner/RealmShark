package tomato.gui.character;

import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

/** Headless Swing state checks: no frames, rendering, native focus or sleeping timers. */
public class CharacterJournalFreshnessRefreshTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void unchangedJournalRefreshAdvancesAgeWithoutTouchingTablesSelectionOrDraft() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        String account = CharacterJournal.accountKey("age-fixture");
        journal.mergeRoster(account, Arrays.asList(character(7, 100_000), character(8, 90_000)));
        journal.notes(account + ":7", "Saved notes");
        AtomicLong clock = new AtomicLong(100_000);
        tomato.backend.data.RosterDefinitions definitions = tomato.backend.data.RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI panel = new CharacterJournalGUI(journal, clock::get, () -> definitions);
            JTable roster = named(panel, "character-roster", JTable.class);
            JTextArea notes = named(panel, "character-notes", JTextArea.class);
            JTextArea evidence = named(panel, "character-snapshot-evidence", JTextArea.class);
            JTabbedPane tabs = named(panel, "character-detail-tabs", JTabbedPane.class);
            roster.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
            tabs.setSelectedIndex(3); notes.setText("Unsaved draft remains editable"); notes.setCaretPosition(5);
            assertTrue(roster.getValueAt(roster.getSelectedRow(), 0).toString().endsWith("#7"));
            assertTrue(evidence.getText().contains("Snapshot update age: 0s"));
            int selected = roster.getSelectedRow(); long revision = journal.revision();
            List<JTable> tables = new ArrayList<>(); collectTables(panel, tables);
            AtomicInteger modelChanges = new AtomicInteger(), selectionChanges = new AtomicInteger(), noteChanges = new AtomicInteger();
            for (JTable table : tables) table.getModel().addTableModelListener(e -> modelChanges.incrementAndGet());
            roster.getSelectionModel().addListSelectionListener(e -> selectionChanges.incrementAndGet());
            notes.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { noteChanges.incrementAndGet(); }
                public void removeUpdate(DocumentEvent e) { noteChanges.incrementAndGet(); }
                public void changedUpdate(DocumentEvent e) { noteChanges.incrementAndGet(); }
            });
            clock.set(105_000); panel.refresh();
            assertTrue(evidence.getText(), evidence.getText().contains("Snapshot update age: 5s"));
            clock.set(160_000); panel.refresh();
            assertTrue(evidence.getText(), evidence.getText().contains("Snapshot update age: 60s"));
            assertEquals(revision, journal.revision());
            assertEquals(0, modelChanges.get()); assertEquals(0, selectionChanges.get()); assertEquals(0, noteChanges.get());
            assertEquals(selected, roster.getSelectedRow()); assertEquals(3, tabs.getSelectedIndex());
            assertEquals("Unsaved draft remains editable", notes.getText()); assertEquals(5, notes.getCaretPosition());
            assertEquals("Saved notes", journal.characters().stream().filter(r -> r.characterId == 7).findFirst().get().notes);
        });
    }

    @Test public void ageRefreshRetainsUnknownAndEmptyStatesAndClampsBackwardClock() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("unknown.json"));
        String account = CharacterJournal.accountKey("unknown-age-fixture");
        journal.mergeRoster(account, Arrays.asList(character(7, 100_000), character(8, 0)));
        journal.notes(account + ":8", "unknown-timestamp-only");
        AtomicLong clock = new AtomicLong(120_000);
        tomato.backend.data.RosterDefinitions definitions = tomato.backend.data.RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI panel = new CharacterJournalGUI(journal, clock::get, () -> definitions);
            JTextArea evidence = named(panel, "character-snapshot-evidence", JTextArea.class);
            assertTrue(evidence.getText().contains("Snapshot update age: 20s"));
            clock.set(90_000); panel.refresh();
            assertTrue(evidence.getText().contains("Snapshot update age: 0s"));
            JTextField search = named(panel, "character-search", JTextField.class);
            search.setText("unknown-timestamp-only");
            assertTrue(evidence.getText().contains("Snapshot update age: Unknown"));
            clock.set(200_000); panel.refresh();
            assertTrue(evidence.getText().contains("Snapshot update age: Unknown"));
            search.setText("no matching character"); panel.refresh();
            assertEquals(" ", evidence.getText());
        });
    }

    private static RealmCharacter character(int id, long at) {
        RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 1; c.classString = "Fixture";
        c.receivedAt = at; c.supplied("class", at, "Synthetic roster"); return c;
    }
    private static void collectTables(Container root, List<JTable> tables) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable) tables.add((JTable) child);
            if (child instanceof Container) collectTables((Container) child, tables);
        }
    }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
