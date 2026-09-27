package tomato.gui.glance.character;

import java.util.List;
import java.util.Objects;

/**
 * The Characters › Exalts grid for one account (spec §6.2), built off the EDT by {@link AccountExaltsBuilder}. {@code account} is
 * the shown account's journal key (null: no account has saved exalt counts, the empty model); {@code accounts} the accounts the
 * selector offers; {@code tiles} one per observed class, by class id. The header: {@code headerClass} (null = no class in game
 * or played on this account) with {@code headerBasis} "in game" or "last played", and {@code headerBoost} its loot boost (null =
 * unknown). {@code fullyExalted} counts the tiles whose lowest tier is 5; {@code observed} is the number of tiles.
 */
record AccountExalts(String account, List<Choice> accounts, List<Tile> tiles, Integer headerBoost, String headerClass,
                     String headerBasis, int fullyExalted, int observed) {
    /** No account with saved exalt counts. */
    static final AccountExalts EMPTY = new AccountExalts(null, List.of(), List.of(), null, null, null, 0, 0);

    AccountExalts {
        accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        tiles = List.copyOf(Objects.requireNonNull(tiles, "tiles"));
    }

    /** An account the selector offers: key and label (the account's saved name, else "Account " + the first 6 key characters). */
    record Choice(String key, String label) {}

    /**
     * One observed class: {@code tiers} per stat in canonical order (0–5), {@code total} completions and the {@code lowest} tier,
     * as the sheet's Exalts tab counts them; {@code lootBoost} percent, null = unknown; {@code seenAt} when this class's counts
     * last changed (0 = unknown).
     */
    record Tile(int classId, String className, int total, int lowest, List<Integer> tiers, Integer lootBoost, long seenAt) {
        Tile { tiers = List.copyOf(tiers); }
    }
}
