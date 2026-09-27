package tomato.gui.glance.character;

import java.util.List;
import java.util.Objects;
import tomato.gui.kit.DisplayValue;

/**
 * Immutable character sheet view model (spec §6.2), built off the EDT by SheetModelBuilder and applied on the EDT. Per-stat
 * lists have 8 entries in canonical order (life, mana, atk, def, spd, dex, vit, wis) with -1 for unknown. Lists are immutable
 * and compare by content, so the presenter and tabs can skip a section that did not change.
 */
public record SheetModel(String key, Identity identity, Stats stats, Gear gear, Exalts exalts, Death death, Live live) {
    public SheetModel {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(gear, "gear"); Objects.requireNonNull(exalts, "exalts");
    }

    /**
     * {@code maxed} 0-8 or -1 unknown; {@code lastSeen} epoch ms of the last capture (0 unknown, the build time while playing);
     * {@code lastPlayed} the last time in game (the journal's lastObservedAlive, 0 never, the build time while playing);
     * {@code playing}: in game now.
     */
    public record Identity(String name, int classId, String className, Integer skin, Integer level, DisplayValue fame,
                           Boolean seasonal, boolean dead, long lastSeen, long lastPlayed, boolean playing, int maxed) {}

    /**
     * {@code boosts} gear and effect bonuses (total - base), 0 unless playing; {@code needed} potions to max per stat; {@code vault}
     * stored potions per stat, null while unknown or for a seasonal character (only the regular vault is recorded), counted at
     * {@code vaultObservedAt} (0 unknown or no count); {@code needs} one line per stat that needs potions; {@code unknown} stats
     * whose base or cap is not captured; {@code evidence} the stat table's Field evidence column.
     */
    public record Stats(List<Integer> base, List<Integer> caps, List<Integer> boosts, List<Integer> needed, List<Integer> vault,
                        long vaultObservedAt, List<String> needs, int unknown, int maxed, List<String> evidence) {}

    /**
     * 28 slots (0-3 equipped, 4-11 inventory, 12-27 backpack): item id > 0, 0 empty, -1 not captured. {@code hasBackpack} null
     * unknown. {@code enchants}: unlocked enchant slots of the 4 equipped items (0 Common … 4 Divine), -1 unknown; null unless playing.
     */
    public record Gear(List<Integer> slots, Boolean hasBackpack, List<Integer> enchants) {}

    /**
     * This class's exaltations: {@code completions}, {@code tiers} (0-5) and {@code toNext} (0 once maxed) per stat, -1 unknown;
     * {@code total} and {@code lowest} -1 unknown; {@code liveBonus} the saved live stat bonus for this class (null = never observed)
     * from {@code liveObservedAt}; {@code seenAt} when this class's counts were last observed (0 unknown); {@code earnIn} dungeon
     * names per stat, null while the mapping loads or is unavailable ("" = not mapped); {@code summary} top 3 stats plus "+N more".
     */
    public record Exalts(List<Integer> completions, List<Integer> tiers, List<Integer> toNext, int total, int lowest,
                         List<Integer> liveBonus, long liveObservedAt, long seenAt, List<String> earnIn, String summary) {
        public boolean known() { return total >= 0; }
    }

    /** Manual death annotation; {@code occurredAt} null unless the user entered it. */
    public record Death(long markedAt, Long occurredAt, String notes) {}

    /**
     * The character in game now (possibly another one): its journal key, class and character id. The game's name stat is the
     * account name, shared by every character on it, so the Build pointer is worded by class (and character id), never by name.
     */
    public record Live(String key, String className, int characterId) {}
}
