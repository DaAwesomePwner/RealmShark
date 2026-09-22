package tomato.gui.quest;

import java.util.prefs.Preferences;
import org.junit.Test;
import tomato.backend.data.CharacterJournal;
import util.InMemoryPreferencesFactory;
import static org.junit.Assert.*;

public class QuestPinsTest {
    @Test public void accountPlansDoNotMigrateOrDeleteLegacyGlobalInterests() {
        Preferences prefs = new InMemoryPreferencesFactory().userRoot().node("quest-pin-fixture");
        prefs.putBoolean("pin.quest", true);
        QuestPins pins = new QuestPins(prefs);
        String a = CharacterJournal.accountKey("A"), b = CharacterJournal.accountKey("B");
        assertTrue(pins.global("quest")); assertFalse(pins.pinned(a, "quest"));
        pins.set(a, "quest", true);
        assertTrue(pins.pinned(a, "quest")); assertFalse(pins.pinned(b, "quest"));
        pins.set(a, "quest", false); pins.set(null, "quest", true);
        assertTrue(pins.global("quest")); assertFalse(pins.pinned(a, "quest")); assertFalse(pins.pinned(null, "quest"));
        assertTrue(new QuestPins(prefs).global("quest"));
        pins.set(b, "quest", true); pins.removeGlobal("quest");
        assertFalse(pins.global("quest")); assertTrue(pins.pinned(b, "quest"));
    }
}
