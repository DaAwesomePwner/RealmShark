package tomato.gui.quest;

import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;
import packets.data.QuestData;

/** Read-only pin lookups for other pages (Home), using the Quests page's own keys and preference namespaces. */
public final class QuestPinning {
    private static final AtomicLong REVISION = new AtomicLong();

    private QuestPinning() {}

    /** Moves whenever the Quests page pins or unpins a quest in this app run, so readers re-read Preferences only then. */
    public static long revision() { return REVISION.get(); }

    static void changed() { REVISION.incrementAndGet(); }

    /**
     * True when the Quests page shows this quest as pinned: the account's plan pin when {@code account} (the hashed
     * account key) is known, otherwise the legacy global pin. Legacy global interests do not count as account pins.
     * Any thread; call it off the EDT (it reads Preferences).
     */
    public static boolean pinned(String account, QuestData quest) {
        return pinned(Preferences.userNodeForPackage(QuestGUI.class), account, quest);
    }

    static boolean pinned(Preferences preferences, String account, QuestData quest) {
        if (quest == null) return false;
        String key = QuestPins.key(quest.id, quest.name, quest.category);
        QuestPins pins = new QuestPins(preferences);
        return account == null ? pins.global(key) : pins.pinned(account, key);
    }
}
