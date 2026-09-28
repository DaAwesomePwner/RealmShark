package tomato.gui.quest;

import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Chest tier of a quest from its reward names, for the Board's grouping (P4 decision): the highest tier among the rewards, highest
 * first. The name rules are QuestGUI.matchesReward's, unchanged: a reward is a quest chest when its lower-cased name contains
 * "quest chest"; its tier is "mighty", "epic", "standard" (or the bare name "quest chest") or "beginner" in that name. A quest
 * chest naming none of the four (matchesReward's "Any quest chest" only, e.g. "Golden Quest Chest") is OTHER_CHEST, below the four;
 * NO_CHEST is only for captured rewards with no quest chest at all. Names come from the page's lookup with the "Unknown item #id"
 * fallback, so a missing asset is never a chest: a tier is never guessed from ids, repeatability, expiration or the user's type
 * label (docs/DAILY-QUESTS.md), and chest tier and type label stay independent axes. Labels are the Board's group titles; the four
 * tiered labels equal the Reward filter's tier options.
 */
enum QuestTier {
    MIGHTY("mighty", "Mighty quest chests"), EPIC("epic", "Epic quest chests"), STANDARD("standard", "Standard quest chests"),
    BEGINNER("beginner", "Beginner quest chests"), OTHER_CHEST("other-chest", "Other quest chests"), NO_CHEST("no-chest", "No quest chest"),
    NOT_CAPTURED("not-captured", "Rewards not captured");

    /** The Board's group key (component name "quest-cards-" + key). */
    final String key;
    final String label;

    QuestTier(String key, String label) { this.key = key; this.label = label; }

    /** The highest tier among the rewards' names; NO_CHEST when none is a quest chest; NOT_CAPTURED when rewards are unknown. */
    static QuestTier of(QuestGUI.Quest quest, IntFunction<String> names) {
        if (!quest.rewardsKnown) return NOT_CAPTURED;
        QuestTier best = NO_CHEST;
        for (int id : quest.rewards) {
            QuestTier tier = ofName(QuestCardModel.itemName(names, id));
            if (tier.ordinal() < best.ordinal()) best = tier;
        }
        return best;
    }

    /**
     * One reward name's tier, highest first when a name carries several tier words; NO_CHEST when it is not a quest chest, and
     * OTHER_CHEST for a quest chest that names none of the four tiers (no tier filter finds it, only "Any quest chest").
     */
    static QuestTier ofName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.contains("quest chest")) return NO_CHEST;
        if (lower.contains("mighty")) return MIGHTY;
        if (lower.contains("epic")) return EPIC;
        if (lower.contains("standard") || lower.equals("quest chest")) return STANDARD;
        if (lower.contains("beginner")) return BEGINNER;
        return OTHER_CHEST;
    }
}
