package tomato.gui.quest;

import java.util.List;
import java.util.function.IntFunction;
import org.junit.Test;
import packets.data.QuestData;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestFixtures.*;

/**
 * A quest's chest tier is the highest tier among its reward names under QuestGUI.matchesReward's name rules, never inferred from
 * ids; a quest with rewards but no tiered quest chest is NO_CHEST, and uncaptured rewards are NOT_CAPTURED, never NO_CHEST.
 */
public class QuestTierTest {
    private static QuestTier tier(int... rewards) { return QuestTier.of(quest("Q", 5, new int[] {MALUS}, rewards), ITEM_NAMES); }
    /** One reward named {@code name} (id 20). */
    private static QuestTier named(String name) {
        IntFunction<String> names = id -> id == 20 ? name : NAMES.get(id);
        return QuestTier.of(quest("Q", 5, new int[0], 20), names);
    }

    @Test public void tiersKeepTheirOrderLabelsAndGroupKeys() {
        assertEquals(List.of(QuestTier.MIGHTY, QuestTier.EPIC, QuestTier.STANDARD, QuestTier.BEGINNER, QuestTier.NO_CHEST, QuestTier.NOT_CAPTURED),
            List.of(QuestTier.values()));
        // The four chest labels are the Reward filter's tier options verbatim.
        assertEquals("Mighty quest chests", QuestTier.MIGHTY.label);
        assertEquals("Epic quest chests", QuestTier.EPIC.label);
        assertEquals("Standard quest chests", QuestTier.STANDARD.label);
        assertEquals("Beginner quest chests", QuestTier.BEGINNER.label);
        assertEquals("No quest chest", QuestTier.NO_CHEST.label);
        assertEquals("Rewards not captured", QuestTier.NOT_CAPTURED.label);
        assertEquals("mighty epic standard beginner no-chest not-captured",
            String.join(" ", QuestTier.MIGHTY.key, QuestTier.EPIC.key, QuestTier.STANDARD.key, QuestTier.BEGINNER.key,
                QuestTier.NO_CHEST.key, QuestTier.NOT_CAPTURED.key));
    }

    @Test public void eachTierComesFromTheRewardName() {
        assertEquals(QuestTier.MIGHTY, tier(MIGHTY_CHEST));
        assertEquals(QuestTier.EPIC, tier(ROYAL_EPIC_CHEST));
        assertEquals(QuestTier.EPIC, tier(CULTISH_EPIC_CHEST));
        assertEquals(QuestTier.STANDARD, tier(STANDARD_CHEST));
        assertEquals(QuestTier.BEGINNER, tier(BEGINNER_CHEST));
        assertEquals("Case-insensitive, as matchesReward", QuestTier.MIGHTY, named("MIGHTY QUEST CHEST"));
        assertEquals(QuestTier.EPIC, named("Epic quest chest key"));
    }

    @Test public void aQuestWithSeveralChestsTakesTheHighestTier() {
        QuestData choice = data("Festival exchange", 8, new int[] {FESTIVAL_TOKEN}, ROYAL_EPIC_CHEST, MIGHTY_CHEST);
        choice.itemOfChoice = true;
        assertEquals(QuestTier.MIGHTY, QuestTier.of(quest(choice), ITEM_NAMES));
        assertEquals("Reward order does not matter", QuestTier.MIGHTY, tier(MIGHTY_CHEST, ROYAL_EPIC_CHEST));
        assertEquals(QuestTier.STANDARD, tier(BEGINNER_CHEST, FESTIVAL_TOKEN, STANDARD_CHEST));
        assertEquals(QuestTier.EPIC, tier(BEGINNER_CHEST, CULTISH_EPIC_CHEST, STANDARD_CHEST));
        assertEquals("A name naming two tiers counts as the higher", QuestTier.MIGHTY, named("Epic Mighty Quest Chest"));
    }

    @Test public void aPlainQuestChestIsStandard() {
        assertEquals(QuestTier.STANDARD, named("Quest Chest"));
        assertEquals(QuestTier.STANDARD, named("quest chest"));
    }

    @Test public void rewardsWithoutATieredQuestChestAreNoChest() {
        assertEquals(QuestTier.NO_CHEST, tier(FORGOTTEN_KING, FESTIVAL_TOKEN));
        assertEquals("Captured and empty is no chest, not unknown", QuestTier.NO_CHEST, tier());
        assertEquals("A tier word outside a quest chest name does not count", QuestTier.NO_CHEST, named("Mighty Sword"));
        assertEquals(QuestTier.NO_CHEST, named("Standard Token"));
        // A quest chest no tier filter matches ("Any quest chest" only) is in none of the four tiers.
        assertEquals(QuestTier.NO_CHEST, named("Golden Quest Chest"));
        assertEquals("Only an exact bare name is standard", QuestTier.NO_CHEST, named(" Quest Chest "));
    }

    @Test public void uncapturedRewardsAreNotCapturedNeverNoChest() {
        QuestData unknown = data("Unknown rewards", 5, new int[] {MALUS}); unknown.rewards = null;
        assertEquals(QuestTier.NOT_CAPTURED, QuestTier.of(quest(unknown), ITEM_NAMES));
        unknown.requirements = null;
        assertEquals(QuestTier.NOT_CAPTURED, QuestTier.of(quest(unknown), ITEM_NAMES));
    }

    @Test public void missingAssetNamesAreNoChestNeverGuessedFromIds() {
        assertEquals("Unknown item #9999", QuestCardModel.itemName(ITEM_NAMES, UNKNOWN_ITEM));
        assertEquals(QuestTier.NO_CHEST, tier(UNKNOWN_ITEM));
        IntFunction<String> none = id -> null, empty = id -> "", failing = id -> { throw new IllegalStateException("assets not loaded"); };
        for (IntFunction<String> names : List.of(none, empty, failing)) {
            assertEquals("Unknown item #10", QuestCardModel.itemName(names, MIGHTY_CHEST));
            assertEquals("The chest id alone is not a tier", QuestTier.NO_CHEST,
                QuestTier.of(quest("Q", 5, new int[0], MIGHTY_CHEST, ROYAL_EPIC_CHEST), names));
        }
    }
}
