package tomato.gui.chat;

import java.util.*;
import packets.incoming.AccountListPacket;
import packets.incoming.TextPacket;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;

/** Passive, connection-local account-list observation; never requests or saves account IDs. */
final class ObservedIgnores {
    private final Set<String> accounts = new HashSet<>();
    private final Map<String, String> knownNames = new LinkedHashMap<>();
    private boolean snapshot;
    synchronized void reset() { accounts.clear(); knownNames.clear(); snapshot = false; }
    synchronized void accept(AccountListPacket packet) {
        // ACCOUNTLIST: 1=Ignore; action -1=snapshot, 0=remove, 1=add. See docs/CHAT.md.
        if (packet.accountListId != 1 || packet.accountIds == null) return;
        if (packet.lockAction == -1) { accounts.clear(); snapshot = true; }
        else if (packet.lockAction != 0 && packet.lockAction != 1) return;
        for (String account : packet.accountIds) if (account != null && !account.isEmpty()) {
            if (packet.lockAction == 0) accounts.remove(account); else accounts.add(account);
        }
    }
    synchronized boolean matches(TextPacket packet, TomatoData data) {
        String name = ChatFilters.playerKey(packet.name);
        if (name.isEmpty() || name.startsWith("#")) return false;
        Entity entity = data == null ? null : data.entityList.get(packet.objectId);
        if (entity != null && name.equals(ChatFilters.playerKey(entity.getStatName()))) {
            StatData account = entity.stat.get(StatType.ACCOUNT_ID_STAT);
            if (account != null && account.stringStatValue != null && !account.stringStatValue.isEmpty()) {
                knownNames.put(name, account.stringStatValue);
                if (knownNames.size() > ChatExplorer.HISTORY_LIMIT) knownNames.remove(knownNames.keySet().iterator().next());
            }
        }
        return accounts.contains(knownNames.get(name));
    }
    synchronized String status() {
        return (snapshot ? "Observed ignore list: " : "No full ignore list captured; observed entries: ") + accounts.size()
                + ". Remote whisper identities may be unavailable.";
    }
}
