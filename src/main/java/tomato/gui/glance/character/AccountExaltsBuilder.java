package tomato.gui.glance.character;

import java.util.*;
import java.util.function.IntFunction;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.LiveCharacter;
import tomato.realmshark.RealmCharacter;

/**
 * Pure, static builder of the account Exalts grid (spec §6.2). No Swing: ExaltsGrid calls it on the "character-exalts" thread with
 * the journal's deep copies. Tiers, totals and the lowest tier are SheetModelBuilder.exalts's, so a tile and its class detail (the
 * sheet's Exalts tab) always agree. The loot boost is RealmCharacter's formula over the account's saved counts, per class: 35 for a
 * fully exalted account, otherwise unknown (null), never 0, when the class's weapon group is not in the selected game assets.
 */
final class AccountExaltsBuilder {
    static final String IN_GAME = "in game", LAST_PLAYED = "last played";

    private AccountExaltsBuilder() {}

    /**
     * The grid of {@code preferredAccount} when it has saved counts, else of the first account offered (every account with at
     * least one saved class, in journal order). {@code live} is the character in game (null = nobody): its class heads the grid
     * when it plays the shown account, else the class of that account's most recently played character. {@code weaponClasses}
     * gives a class's weapon group (production: CharacterClass.weaponClasses; null = not in the assets), {@code className} its name.
     */
    static AccountExalts build(List<AccountRecord> accounts, List<CharacterRecord> records, String preferredAccount,
                               LiveCharacter.Snapshot live, IntFunction<int[]> weaponClasses, IntFunction<String> className) {
        List<AccountRecord> offered = new ArrayList<>();
        for (AccountRecord a : accounts) if (a != null && a.key != null && a.exalts != null && !a.exalts.isEmpty()) offered.add(a);
        if (offered.isEmpty()) return AccountExalts.EMPTY;
        AccountRecord shown = offered.get(0);
        for (AccountRecord a : offered) if (a.key.equals(preferredAccount)) shown = a;
        List<AccountExalts.Tile> tiles = new ArrayList<>();
        int fullyExalted = 0;
        for (int classId : new TreeSet<>(shown.exalts.keySet())) {
            SheetModel.Exalts exalts = SheetModelBuilder.exalts(classId, shown, null);
            if (!exalts.known()) continue; // the journal drops malformed counts when it loads; never a tile of made-up zeros
            tiles.add(new AccountExalts.Tile(classId, className.apply(classId), exalts.total(), exalts.lowest(), exalts.tiers(),
                boost(shown, classId, weaponClasses), exalts.seenAt()));
            if (exalts.lowest() == 5) fullyExalted++;
        }
        Integer headerClass = null;
        String basis = null;
        if (live != null && shown.key.equals(live.account()) && live.classId() > 0) { headerClass = live.classId(); basis = IN_GAME; }
        else {
            CharacterRecord last = lastPlayed(records, shown.key);
            if (last != null) { headerClass = last.classId; basis = LAST_PLAYED; }
        }
        return new AccountExalts(shown.key, choices(offered), tiles, headerClass == null ? null : boost(shown, headerClass, weaponClasses),
            headerClass == null ? null : className.apply(headerClass), basis, headerClass == null ? 0 : seenAt(shown, headerClass),
            fullyExalted, tiles.size());
    }

    /**
     * When the saved counts behind the header's boost last changed. The boost depends on every class sharing the weapon, not only
     * the header's class, so this is when any of the account's counts last changed (exaltSeen, the newest change of any class);
     * the class's own time (exaltSeenByClass) only while the account's is unknown (0); 0 = unknown.
     */
    private static long seenAt(AccountRecord account, int classId) {
        if (account.exaltSeen > 0) return account.exaltSeen;
        Long seen = account.exaltSeenByClass == null ? null : account.exaltSeenByClass.get(classId);
        return seen != null && seen > 0 ? seen : 0;
    }

    /**
     * Each saved class's full Exalts section of {@code account} (the class detail: the sheet's tab, account-scoped), by class id;
     * empty when the account is unknown. {@code dungeons} as for SheetModelBuilder.exalts (null while the mapping loads).
     */
    static Map<Integer, SheetModel.Exalts> details(List<AccountRecord> accounts, String account, IntFunction<List<String>> dungeons) {
        Map<Integer, SheetModel.Exalts> details = new TreeMap<>();
        for (AccountRecord a : accounts) if (a != null && a.key != null && a.key.equals(account) && a.exalts != null)
            for (int classId : a.exalts.keySet()) details.put(classId, SheetModelBuilder.exalts(classId, a, dungeons));
        return Collections.unmodifiableMap(details);
    }

    /**
     * Home's "current account": the character in game's, else the last one known this run, else the account of the journal's
     * most recent character ({@code CharacterJournal.mostRecentCharacter}); null when none is known.
     */
    static String currentAccount(LiveCharacter.Snapshot current, LiveCharacter.Snapshot lastKnown, CharacterRecord recent) {
        if (current != null) return current.account();
        if (lastKnown != null) return lastKnown.account();
        return recent == null ? null : recent.account;
    }

    /**
     * The class's loot boost over the account's saved counts: 35 for every class of a fully exalted account (no weapon group
     * needed), else null when the assets do not name its weapon group. A class of the group without saved counts gives 0, as the
     * game rule and the live Loot value do: a class with no completions gives no boost.
     */
    private static Integer boost(AccountRecord account, int classId, IntFunction<int[]> weaponClasses) {
        if (RealmCharacter.fullyExalted(account.exalts)) return RealmCharacter.exaltLootBonus(account.exalts, null); // 35
        int[] group = weaponClasses.apply(classId);
        return group == null ? null : RealmCharacter.exaltLootBonus(account.exalts, group);
    }

    /**
     * The account's most recently played character: never marked dead, as CharacterJournal.mostRecentCharacter chooses Home's
     * hero, and actually seen in game (a character only a character list reported was never "played"); ties by last seen.
     */
    private static CharacterRecord lastPlayed(List<CharacterRecord> records, String account) {
        CharacterRecord best = null;
        for (CharacterRecord r : records)
            if (r != null && account.equals(r.account) && !r.dead && r.classId > 0 && r.lastObservedAlive > 0 && (best == null
                || r.lastObservedAlive > best.lastObservedAlive || r.lastObservedAlive == best.lastObservedAlive && r.lastSeen > best.lastSeen))
                best = r;
        return best;
    }

    /** The saved name, else "Account" and the key's start; two accounts with one name are told apart by their keys. */
    private static List<AccountExalts.Choice> choices(List<AccountRecord> offered) {
        List<String> labels = new ArrayList<>();
        for (AccountRecord a : offered) labels.add(a.name == null || a.name.isBlank() ? "Account " + prefix(a.key) : a.name);
        List<AccountExalts.Choice> choices = new ArrayList<>();
        for (int i = 0; i < offered.size(); i++) {
            String label = labels.get(i);
            boolean shared = Collections.frequency(labels, label) > 1;
            choices.add(new AccountExalts.Choice(offered.get(i).key, shared ? label + " · " + prefix(offered.get(i).key) : label));
        }
        return choices;
    }

    private static String prefix(String key) { return key.substring(0, Math.min(6, key.length())); }
}
