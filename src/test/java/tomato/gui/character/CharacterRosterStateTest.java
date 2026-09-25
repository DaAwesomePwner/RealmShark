package tomato.gui.character;

import java.awt.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.*;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

public class CharacterRosterStateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test @SuppressWarnings({"rawtypes", "unchecked"}) public void capturedMarkupLabelsStayLiteralInBoundedChoiceTooltips() throws Exception {
        String name = "<html><img src='https://invalid.example/image'>Account & <b>name</b>";
        try (CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("markup.json"))) {
            Entity player = CharacterJournalTest.player("markup-account", 782);
            player.stat.get(packets.data.enums.StatType.NAME_STAT).stringStatValue = name;
            journal.observe(player, 1);
            SwingUtilities.invokeAndWait(() -> {
                CharacterJournalGUI view = new CharacterJournalGUI(journal);
                JComboBox box = named(view, "character-facet-0", JComboBox.class); box.setSelectedIndex(1);
                String label = box.getSelectedItem().toString(); assertTrue(label.startsWith(name));
                assertEquals(label, box.getAccessibleContext().getAccessibleDescription());
                assertNotNull(box.getPrototypeDisplayValue());
                assertEquals("Full label: " + label, box.getToolTipText());
                assertFalse(javax.swing.plaf.basic.BasicHTML.isHTMLString(box.getToolTipText()));
                JLabel rendered = (JLabel)box.getRenderer().getListCellRendererComponent(new JList(), box.getSelectedItem(), 1, false, false);
                assertEquals(label, rendered.getText()); assertEquals(Boolean.TRUE, rendered.getClientProperty("html.disable"));
                assertEquals("Full label: " + label, rendered.getToolTipText());
                assertFalse(javax.swing.plaf.basic.BasicHTML.isHTMLString(rendered.getToolTipText()));
            });
        }
    }
    @Test public void composedControlsUseAccountIdentityAndNumericNullablePotionColumn() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        String a = CharacterJournal.accountKey("A"), b = CharacterJournal.accountKey("B");
        journal.mergeRoster(a, Collections.singletonList(character(1, 650, 1000)));
        journal.mergeRoster(b, Collections.singletonList(character(1, 620, 2000)));
        RosterDefinitions definitions = CharacterRosterQueryTest.definitions();
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI view = new CharacterJournalGUI(journal, () -> 3000, () -> definitions);
            JTable table = named(view, "character-roster", JTable.class); assertEquals(Long.class, table.getModel().getColumnClass(8));
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(8, SortOrder.ASCENDING)));
            assertEquals(4L, table.getValueAt(0, 8)); assertEquals(10L, table.getValueAt(1, 8));
            JComboBox<?> accounts = named(view, "character-facet-0", JComboBox.class);
            for (int i = 0; i < accounts.getItemCount(); i++) if (accounts.getItemAt(i).toString().contains(a.substring(0,6))) accounts.setSelectedIndex(i);
            named(view, "character-facet-2", JComboBox.class).setSelectedIndex(1);
            assertEquals(1, table.getRowCount()); assertTrue(table.getValueAt(0,1).toString().contains(a.substring(0,6)));
            named(view, "character-search", JTextField.class).setText("12345"); assertEquals(1, table.getRowCount());
            named(view, "character-search", JTextField.class).setText("no match"); assertEquals(0, table.getRowCount());
            view.refresh(); assertTrue(allText(view).contains("No matching characters"));
        });
    }
    @Test public void ageBoundaryChangesMembershipWithoutWritingDraftToAnotherCharacter() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("age.json")); String account = CharacterJournal.accountKey("A");
        journal.mergeRoster(account, Arrays.asList(character(1, 650, 1000), character(2, 650, 2000)));
        AtomicLong clock = new AtomicLong(2000); RosterDefinitions definitions = CharacterRosterQueryTest.definitions();
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI view = new CharacterJournalGUI(journal, clock::get, () -> definitions);
            JTable table = named(view, "character-roster", JTable.class);
            for (int i = 0; i < table.getRowCount(); i++) if (table.getValueAt(i,0).toString().endsWith("#1")) table.setRowSelectionInterval(i,i);
            named(view, "character-notes", JTextArea.class).setText("Draft for character one");
            named(view, "character-facet-8", JSpinner.class).setValue(1); named(view, "character-facet-7", JComboBox.class).setSelectedIndex(1);
            clock.set(3601000); view.refresh(); assertEquals(1, table.getRowCount()); assertTrue(table.getValueAt(0,0).toString().endsWith("#2"));
            assertEquals("Draft for character one", journal.characters().stream().filter(r -> r.characterId == 1).findFirst().get().notes);
            assertEquals("", journal.characters().stream().filter(r -> r.characterId == 2).findFirst().get().notes);
        });
    }
    private static RealmCharacter character(int id, int hp, long at) {
        RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.receivedAt = at; c.seasonal = true;
        c.supplied("class", at, "fixture"); c.supplied("seasonal", at, "fixture"); c.hp=hp; c.mp=385; c.atk=75; c.def=25; c.spd=50; c.dex=75; c.vit=40; c.wis=60;
        c.capturedStatMask = 255; c.equipment = new int[]{12345}; return c;
    }
    private static String allText(Container root) { StringBuilder text = new StringBuilder(); for (Component c : root.getComponents()) { if (c instanceof JTextArea) text.append(((JTextArea)c).getText()); if (c instanceof Container) text.append(allText((Container)c)); } return text.toString(); }
    private static <T> T named(Container root, String name, Class<T> type) { for (Component c : root.getComponents()) { if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c); if (c instanceof Container) { T child = named((Container)c,name,type); if (child != null) return child; } } return null; }
}
