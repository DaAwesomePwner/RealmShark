package tomato.gui.character;

import java.awt.*;
import java.util.Collections;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.kit.DisplayModeModel;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class CharacterTabsTest {
    private static final String ORDER = "ui.tabs.character";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;
    @Before public void remember() throws Exception { savedOrder = PropertiesManager.getProperty(ORDER); SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode()); }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    private static String seed(CharacterJournal journal) {
        String account = CharacterJournal.accountKey("tabs-fixture");
        RealmCharacter c = new RealmCharacter(); c.charId = 1; c.classNum = 782; c.receivedAt = 1000; c.supplied("class");
        journal.mergeRoster(account, Collections.singletonList(c));
        return account + ":1";
    }

    @Test public void snapshotEvidenceIsAnalystOnlyAndTabsKeepTheirSavedOrder() throws Exception {
        PropertiesManager.setProperties(ORDER, "goals,overview,gear,exalts,notes,evidence,death|");
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        String key = seed(journal);
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            CharacterSheet sheet = RosterFixtures.sheet(journal, () -> 5000, RosterDefinitions::empty);
            JTabbedPane tabs = find(sheet, JTabbedPane.class, "character-tabs");
            assertEquals("Goals", tabs.getTitleAt(0)); assertEquals(6, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertEquals(7, tabs.getTabCount()); assertEquals(5, tabs.indexOfTab("Snapshot evidence"));
            sheet.open(key, null); tabs.setSelectedIndex(tabs.indexOfTab("Notes")); sheet.open(key, "goals");
            assertEquals("Goals", tabs.getTitleAt(tabs.getSelectedIndex()));
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }

    /** Tab hiding persists (spec §12 P1 exit): restoring the saved view must not un-hide the last selected tab. */
    @Test public void savedViewKeepsAHiddenNotesTabHidden() throws Exception {
        tomato.gui.history.ArchiveNativeSupport.Memory memory = new tomato.gui.history.ArchiveNativeSupport.Memory();
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("tabs.json"));
        seed(journal);
        try {
            SwingUtilities.invokeAndWait(() -> {
                PropertiesManager.setProperties(ORDER, "");
                CharacterRosterView first = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                first.listPanel().bindViewState(memory.states);
                RosterFixtures.enter(first); first.sheet().tabs().select("notes"); first.listPanel().saveViewState();
                PropertiesManager.setProperties(ORDER, "overview,gear,exalts,goals,notes,evidence,death|notes");
                CharacterRosterView restored = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                restored.listPanel().bindViewState(memory.states);
                assertNull("Restoring the list does not open the sheet", restored.sheet().key());
                RosterFixtures.enter(restored);
                JTabbedPane shown = find(restored.sheet(), JTabbedPane.class, "character-tabs");
                assertEquals("The hidden Notes tab stays hidden", -1, shown.indexOfTab("Notes"));
                assertEquals("The first visible tab stays selected", "Overview", shown.getTitleAt(shown.getSelectedIndex()));
                assertEquals("Restoring does not rewrite the hidden set", "overview,gear,exalts,goals,notes,evidence,death|notes",
                    PropertiesManager.getProperty(ORDER));
            });
            SwingUtilities.invokeAndWait(() -> {});
        } finally { journal.close(); }
    }
}
