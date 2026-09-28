package tomato.gui.quest;

import java.awt.Component;
import java.awt.Container;
import java.util.*;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import packets.data.QuestData;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.TomatoData;
import static org.junit.Assert.*;

/** Home's pin lookups agree with what the Quests page stores when a quest is pinned or unpinned. */
public class QuestPinningTest {
    /** The Board opens on cards; these tests select a quest in the table, which is the Table view. */
    @Rule public final QuestViewRule tableView = new QuestViewRule();
    private static final Preferences NODE = Preferences.userNodeForPackage(QuestGUI.class);
    private static final String ACCOUNT = CharacterJournal.accountKey("quest-pinning-fixture");
    private final List<String> keys = new ArrayList<>();

    @After public void restorePreferences() throws Exception {
        for (String key : keys) {
            NODE.remove("pin." + key);
            if (NODE.nodeExists("accounts/" + ACCOUNT)) NODE.node("accounts/" + ACCOUNT).remove("pin." + key);
        }
    }

    private QuestData quest(String id, String name) {
        QuestData quest = new QuestData(); quest.id = id; quest.name = name; quest.category = 5;
        quest.requirements = new int[]{1}; quest.rewards = new int[]{10}; quest.description = "Synthetic quest";
        keys.add(QuestPins.key(quest.id, quest.name, quest.category));
        return quest;
    }

    @Test public void globalPinsAgreeWithTheUnboundQuestPage() throws Exception {
        QuestData quest = quest(null, "QuestPinning global fixture"); // No server ID: keyed by name and category.
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(id -> "Item " + id, id -> null, NODE);
            ui.update(new QuestData[]{quest});
            find(ui, JTable.class).setRowSelectionInterval(0, 0);
            AbstractButton pin = pin(ui);
            assertFalse(QuestPinning.pinned(null, quest));
            long revision = QuestPinning.revision();
            assertEquals("Pin quest", pin.getText()); pin.doClick();
            assertTrue(QuestPinning.pinned(null, quest));
            assertTrue("Pinning tells Home to re-read pins", QuestPinning.revision() > revision);
            assertFalse("A global pin is not an account pin", QuestPinning.pinned(ACCOUNT, quest));
            revision = QuestPinning.revision();
            assertEquals("Unpin quest", pin.getText()); pin.doClick();
            assertFalse(QuestPinning.pinned(null, quest));
            assertTrue(QuestPinning.revision() > revision);
        });
    }

    @Test public void accountPinsAgreeWithTheBoundQuestPage() throws Exception {
        TomatoData data = new TomatoData(); ProgressionData source = data.progression();
        source.reset(ACCOUNT, "identified");
        QuestData quest = quest("quest-pinning-account-fixture", "QuestPinning account fixture");
        assertTrue(source.quests(source.scope(), new QuestData[]{quest}, 100));
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(data, id -> "Item " + id, id -> null, NODE);
            try {
                find(ui, JTable.class).setRowSelectionInterval(0, 0);
                AbstractButton pin = pin(ui);
                assertFalse(QuestPinning.pinned(ACCOUNT, quest));
                long revision = QuestPinning.revision();
                assertEquals("Pin for account", pin.getText()); pin.doClick();
                assertTrue("Account pins move the revision too", QuestPinning.revision() > revision);
                assertTrue(QuestPinning.pinned(ACCOUNT, quest));
                assertFalse("An account pin is not a global pin", QuestPinning.pinned(null, quest));
                assertFalse(QuestPinning.pinned(CharacterJournal.accountKey("another account"), quest));
                assertEquals("Unpin quest", pin.getText()); pin.doClick();
                assertFalse(QuestPinning.pinned(ACCOUNT, quest));
            } finally { ui.removeNotify(); }
        });
    }

    private static AbstractButton pin(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && "quest-pin".equals(c.getName())) return (AbstractButton) c;
            if (c instanceof Container) { AbstractButton found = pin((Container) c); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container) c, type); if (found != null) return found; }
        }
        return null;
    }
}
