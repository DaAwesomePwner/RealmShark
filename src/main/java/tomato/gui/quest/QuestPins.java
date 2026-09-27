package tomato.gui.quest;

import java.util.prefs.Preferences;

/** Account plans and existing global interests occupy separate preference namespaces. */
final class QuestPins {
    /** The stable pin key: the quest's server ID, or name + category when the server sent no ID. */
    static String key(String id, String name, int category) {
        String stable = id == null ? "" : id, title = name == null ? "" : name;
        return java.util.UUID.nameUUIDFromBytes((stable.isEmpty() ? title + ":" + category : stable)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private final Preferences preferences;
    QuestPins(Preferences preferences) { this.preferences = preferences; }
    boolean global(String key) { return preferences != null && preferences.getBoolean("pin." + key, false); }
    void removeGlobal(String key) { if (preferences != null) preferences.remove("pin." + key); }
    boolean pinned(String account, String key) {
        Preferences node = account(account);
        return node != null && node.getBoolean("pin." + key, false);
    }
    void set(String account, String key, boolean value) {
        Preferences node = account(account);
        if (node != null) node.putBoolean("pin." + key, value);
    }
    private Preferences account(String account) {
        return preferences == null || account == null ? null : preferences.node("accounts").node(account);
    }
}
