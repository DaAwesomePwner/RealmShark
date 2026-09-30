package tomato.gui.character;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.modern.DisplayFormat;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

/**
 * P6b Polish C: the roster's Table view reads an unknown level, maxed count, fame, snapshot time or potions total as "—" (with its
 * reason in the tooltip), as the gallery does, never "Unknown" or a blank; textual states (State, Season) keep their words. The
 * model keeps null, so sorting and filters still tell unknown from zero. Synthetic journal only.
 */
public class RosterTableUnknownTest {
    @Rule public final TableViewRule tableView = new TableViewRule();
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void unknownNumbersAndTimesReadAsADashWithTheirReason() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        RosterDefinitions definitions = CharacterRosterQueryTest.definitions();
        String account = journal.observe(CharacterJournalTest.player("synthetic-account", 782), 101);
        List<RealmCharacter> roster = new ArrayList<>();
        RealmCharacter known = new RealmCharacter(); known.classNum = 782; known.charId = 101; known.level = 20; known.seasonal = false; known.fame = 1234;
        for (String field : new String[]{"class", "level", "seasonal", "fame"}) known.supplied(field);
        known.receivedAt = System.currentTimeMillis();
        known.hp = 670; known.mp = 385; known.atk = 75; known.def = 25; known.spd = 50; known.dex = 75; known.vit = 40; known.wis = 50; known.capturedStatMask = 255;
        roster.add(known);
        // Only the class is known: no level, fame, season, stats (so no maxed count or potions total) or snapshot time.
        RealmCharacter unknown = new RealmCharacter(); unknown.classNum = 782; unknown.charId = 102; unknown.supplied("class");
        roster.add(unknown);
        journal.mergeRoster(account, roster);
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(new CharacterJournalGUI(journal, System::currentTimeMillis, () -> definitions), "character-roster");
            int blank = row(table, "#102"), full = row(table, "#101");
            String[] columns = {"Level", "Maxed", "Fame", "Last snapshot update", "Potions remaining"};
            for (String name : columns) {
                int column = table.getColumnModel().getColumnIndex(name);
                JLabel cell = render(table, blank, column);
                assertEquals(name + " reads as unknown", DisplayFormat.UNAVAILABLE, cell.getText());
                assertNotNull(name + " says why in its tooltip", cell.getToolTipText());
                assertFalse(name + "'s reason is not just the dash", cell.getToolTipText().trim().equals(DisplayFormat.UNAVAILABLE));
                if (!"Last snapshot update".equals(name)) assertNull(name + " stays null in the model, never 0", table.getValueAt(blank, column));
            }
            assertEquals("Season is a textual state and keeps its word", "Unknown", render(table, blank, table.getColumnModel().getColumnIndex("Season")).getText());
            assertEquals("20", render(table, full, table.getColumnModel().getColumnIndex("Level")).getText());
            assertEquals("7/8", render(table, full, table.getColumnModel().getColumnIndex("Maxed")).getText());
            assertEquals("1,234", render(table, full, table.getColumnModel().getColumnIndex("Fame")).getText());
            assertEquals("10", render(table, full, table.getColumnModel().getColumnIndex("Potions remaining")).getText());
            assertNotEquals(DisplayFormat.UNAVAILABLE, render(table, full, table.getColumnModel().getColumnIndex("Last snapshot update")).getText());
            String header = table.getTableHeader().getToolTipText();
            assertFalse("The header no longer speaks of \"Unknown\" totals: " + header, header.contains("Unknown totals"));
        });
    }

    private static int row(JTable table, String suffix) {
        for (int row = 0; row < table.getRowCount(); row++) if (String.valueOf(table.getValueAt(row, 0)).endsWith(suffix)) return row;
        throw new AssertionError("No row " + suffix);
    }
    private static JLabel render(JTable table, int row, int column) { return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column); }
    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
