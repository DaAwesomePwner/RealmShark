package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.planning.PlanningStore;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class CharacterSheetTest {
    private static final String ORDER = "ui.tabs.character";
    private static final String ACCOUNT = CharacterJournal.accountKey("sheet-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;

    @Before public void remember() throws Exception {
        savedOrder = PropertiesManager.getProperty(ORDER); PropertiesManager.setProperties(ORDER, "");
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
    }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    private CharacterJournal journal(String file, int... ids) { return journal(new CharacterJournal(temp.getRoot().toPath().resolve(file)), ids); }
    private static CharacterJournal journal(CharacterJournal journal, int... ids) {
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id : ids) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.level = 20; c.receivedAt = 1000;
            c.supplied("class"); c.supplied("level"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
        return journal;
    }
    private static CharacterSheet sheet(CharacterJournal journal) {
        return new CharacterSheet(new SheetContext(new TomatoData(), journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> 5000, PlanningStore.shared()));
    }
    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    @Test public void tabsKeepTheirIdsAndOrderSnapshotEvidenceIsAnalystOnlyAndSlotsAreReplaceable() throws Exception {
        try (CharacterJournal journal = journal("tabs.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                CharacterSheet sheet = sheet(journal);
                assertEquals("character-sheet", sheet.getName());
                List<String> order = Arrays.asList("overview", "gear", "exalts", "goals", "notes", "evidence", "death");
                assertEquals(order, sheet.tabs().order());
                JTabbedPane tabs = sheet.tabs().component();
                assertEquals("character-tabs", tabs.getName());
                assertEquals("Death annotation shows only for a character marked dead", Arrays.asList("Overview", "Gear", "Exalts", "Goals", "Notes", "Snapshot evidence"), titles(tabs));
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                assertEquals(5, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
                sheet.open(ACCOUNT + ":1", "notes"); assertEquals("notes", sheet.selectedTab());
                sheet.open(ACCOUNT + ":1", "goals"); assertEquals("An explicit tab is selected", "goals", sheet.selectedTab());
                JPanel replacement = new JPanel();
                sheet.setTab("overview", replacement);
                assertEquals("A replaced slot keeps its id and place", order, sheet.tabs().order());
                assertTrue(SwingUtilities.isDescendingFrom(replacement, tabs.getComponentAt(0)));
                try { sheet.setTab("notes", new JPanel()); fail("Only overview, gear and exalts are slots"); } catch (IllegalArgumentException expected) { }
            });
        }
    }

    @Test public void headerHasTheBackLinkIdentityAndMarkDeadShowsTheDeathTab() throws Exception {
        try (CharacterJournal journal = journal("header.json", 7)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                AtomicInteger backs = new AtomicInteger();
                sheet.onBack(backs::incrementAndGet);
                sheet.open(ACCOUNT + ":7", null);
                assertEquals(ACCOUNT + ":7", sheet.key()); assertTrue(sheet.ready());
                AbstractButton back = named(sheet, "character-sheet-back", AbstractButton.class);
                assertEquals("‹ Characters", back.getText());
                back.doClick(); assertEquals(1, backs.get());
                JTextArea title = named(sheet, "character-sheet-title", JTextArea.class);
                assertTrue(title.getText(), title.getText().contains("#7") && title.getText().contains("Level 20"));
                AbstractButton death = named(sheet, "character-sheet-death", AbstractButton.class), restore = named(sheet, "character-sheet-restore", AbstractButton.class);
                assertEquals("Mark dead", death.getText()); assertTrue(death.isVisible()); assertFalse(restore.isVisible());
                assertFalse("An alive character has no Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                death.doClick();
                assertTrue(journal.characterCopy(ACCOUNT + ":7").dead);
                assertFalse(death.isVisible()); assertTrue(restore.isVisible()); assertEquals("Restore alive", restore.getText());
                assertTrue(title.getText().contains("Marked dead manually"));
                assertTrue("Marking dead shows the Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                assertEquals("…without rewriting the saved order", "", PropertiesManager.getProperty(ORDER));
                restore.doClick();
                assertFalse(journal.characterCopy(ACCOUNT + ":7").dead);
                assertTrue(death.isVisible()); assertEquals("Mark dead", death.getText());
                assertFalse(sheet.tabs().visibleIds().contains("death"));
            });
        }
    }

    @Test public void anUnknownKeyShowsTheUnavailableStateAndAKnownOneTheTabs() throws Exception {
        try (CharacterJournal journal = journal("unknown.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":404", null);
                EmptyState missing = named(sheet, "character-sheet-unavailable", EmptyState.class);
                assertTrue(missing.isVisible());
                assertEquals(CharacterSheet.UNAVAILABLE, missing.getAccessibleContext().getAccessibleName());
                assertFalse(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                assertEquals(" ", named(sheet, "character-snapshot-evidence", JTextArea.class).getText());
                sheet.open(ACCOUNT + ":1", null);
                assertFalse(missing.isVisible());
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void aNotesDraftSurvivesRefreshAndIsSavedWhenAnotherCharacterOpens() throws Exception {
        try (CharacterJournal journal = journal("notes.json", 1, 2)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":1", "notes");
                JTextArea notes = named(sheet, "character-notes", JTextArea.class);
                notes.setText("Draft for one"); sheet.refresh();
                assertEquals("A refresh keeps the draft", "Draft for one", notes.getText());
                assertEquals("", journal.characterCopy(ACCOUNT + ":1").notes);
                sheet.open(ACCOUNT + ":2", null);
                assertEquals("Opening another character saves the draft", "Draft for one", journal.characterCopy(ACCOUNT + ":1").notes);
                assertEquals("", notes.getText());
                notes.setText("Saved for two"); named(sheet, "character-notes-save", AbstractButton.class).doClick();
                assertEquals("Saved for two", journal.characterCopy(ACCOUNT + ":2").notes);
            });
        }
    }

    @Test public void hidingTheSheetSavesItsNotesDraft() throws Exception {
        try (CharacterJournal journal = journal("hide.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                JFrame frame = new JFrame("Sheet hide"); frame.setContentPane(sheet); frame.setSize(900, 600); frame.setVisible(true);
                try {
                    sheet.open(ACCOUNT + ":1", "notes");
                    named(sheet, "character-notes", JTextArea.class).setText("Kept when the sheet hides");
                    sheet.setVisible(false); // what another card, Back or another Characters tab does
                    assertEquals("Kept when the sheet hides", journal.characterCopy(ACCOUNT + ":1").notes);
                } finally { frame.dispose(); }
            });
        }
    }

    @Test public void snapshotEvidenceAndTheTabHintAreAnalystOnly() throws Exception {
        try (CharacterJournal journal = journal("provenance.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":1", null);
                JTextArea evidence = named(sheet, "character-snapshot-evidence", JTextArea.class), hint = named(sheet, "character-sheet-hint", JTextArea.class);
                assertFalse("Simple hides provenance (spec §3.2)", evidence.isVisible()); assertFalse(hint.isVisible());
                assertTrue("The text is still kept current", evidence.getText().contains("Snapshot update age"));
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertTrue(evidence.isVisible()); assertTrue(hint.isVisible());
            });
        }
    }

    @Test public void anUnreadableJournalAndAFailedNotesSaveShowAWarnBanner() throws Exception {
        Path broken = temp.getRoot().toPath().resolve("broken.json");
        Files.writeString(broken, "{broken");
        Path blocker = temp.newFile("blocker").toPath(); // a file where the journal's folder should be: every save fails
        CharacterSheet[] shown = new CharacterSheet[1];
        try (CharacterJournal unreadable = new CharacterJournal(broken); CharacterJournal failing = journal(new CharacterJournal(blocker.resolve("journal.json")), 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(unreadable);
                sheet.open(ACCOUNT + ":1", null);
                Banner storage = named(sheet, "character-sheet-storage", Banner.class);
                assertTrue(storage.isVisible()); assertTrue(storage.warns()); assertTrue(storage.text(), storage.text().startsWith("Cannot read"));
                shown[0] = sheet(failing);
                shown[0].open(ACCOUNT + ":1", "notes");
                assertFalse("Nothing has failed yet", named(shown[0], "character-sheet-storage", Banner.class).isVisible());
                named(shown[0], "character-notes", JTextArea.class).setText("Never reaches the disk");
                named(shown[0], "character-notes-save", AbstractButton.class).doClick();
            });
            failing.save(); // the saver thread's write fails
            SwingUtilities.invokeAndWait(() -> {
                shown[0].refresh();
                Banner storage = named(shown[0], "character-sheet-storage", Banner.class);
                assertTrue("A failed save warns inside the sheet", storage.isVisible()); assertTrue(storage.warns());
                assertTrue(storage.text(), storage.text().startsWith("Save failed"));
            });
        }
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
