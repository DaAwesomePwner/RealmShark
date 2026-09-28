package tomato.gui.quest;

import java.util.Map;
import java.util.function.IntFunction;
import packets.data.QuestData;

/**
 * Synthetic quests for the Board tests: item names as QuestGuiTest's, categories 5 ("Daily") and 8 ("Event") by convention. No
 * capture and no personal data. {@code null} reward or requirement arrays are "not captured"; set them on the {@link QuestData}.
 */
public final class QuestFixtures {
    public static final int FORGOTTEN_KING = 1, MALUS = 2, FESTIVAL_TOKEN = 3;
    public static final int MIGHTY_CHEST = 10, STANDARD_CHEST = 11, ROYAL_EPIC_CHEST = 12, CULTISH_EPIC_CHEST = 13, BEGINNER_CHEST = 14;
    /** A quest chest naming none of the four tiers: QuestTier.OTHER_CHEST. */
    public static final int GOLDEN_CHEST = 15;
    /** An id no name lookup knows: its name is "Unknown item #9999". */
    public static final int UNKNOWN_ITEM = 9999;
    public static final Map<Integer, String> NAMES = Map.of(
        FORGOTTEN_KING, "Mark of the Forgotten King", MALUS, "Mark of Malus", FESTIVAL_TOKEN, "Festival Token",
        MIGHTY_CHEST, "Mighty Quest Chest", STANDARD_CHEST, "Standard Quest Chest", ROYAL_EPIC_CHEST, "Royal Epic Quest Chest",
        CULTISH_EPIC_CHEST, "Cultish Epic Quest Chest", BEGINNER_CHEST, "Beginner Quest Chest", GOLDEN_CHEST, "Golden Quest Chest");
    /** The name lookup the Board gets: null for unknown ids, as the asset lookup without assets. */
    public static final IntFunction<String> ITEM_NAMES = NAMES::get;

    private QuestFixtures() {}

    /** A quest as the server sends it: id = name, a fixed description, no expiration, one-time, not done, not a choice. */
    public static QuestData data(String name, int category, int[] requirements, int... rewards) {
        QuestData q = new QuestData();
        q.id = name; q.name = name; q.category = category; q.requirements = requirements; q.rewards = rewards;
        q.description = "Bring the listed items to the Tinkerer to claim your reward.";
        return q;
    }

    /** The page's detached copy of {@code data}. */
    static QuestGUI.Quest quest(QuestData data) { return new QuestGUI.Quest(data); }

    static QuestGUI.Quest quest(String name, int category, int[] requirements, int... rewards) {
        return quest(data(name, category, requirements, rewards));
    }

    /** A card over {@link #ITEM_NAMES}. */
    static QuestCardModel card(QuestData data, boolean pinned, String typeLabel) {
        return QuestCardModel.of(quest(data), pinned, typeLabel, ITEM_NAMES);
    }

    /** An unpinned, unlabeled card needing one Mark of Malus and rewarding {@code rewards}. */
    static QuestCardModel card(String name, int... rewards) { return card(data(name, 5, new int[] {MALUS}, rewards), false, ""); }
}
