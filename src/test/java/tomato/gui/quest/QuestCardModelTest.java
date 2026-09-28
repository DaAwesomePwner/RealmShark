package tomato.gui.quest;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import packets.data.QuestData;
import tomato.gui.quest.QuestCardModel.Item;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestFixtures.*;

/**
 * One quest card: repeated ids become counts in first-seen order, "Pick 1 of N" counts distinct options, badges are the quest's own
 * flags, an uncaptured list is "not captured" (never an empty "none"), and the server's expiration is kept verbatim, never parsed.
 */
public class QuestCardModelTest {
    @Test public void repeatedIdsAggregateIntoCountsInFirstSeenOrder() {
        QuestData d = data("Royal tribute", 5, new int[] {MALUS, FORGOTTEN_KING, MALUS, FORGOTTEN_KING, FORGOTTEN_KING, UNKNOWN_ITEM},
            ROYAL_EPIC_CHEST, MIGHTY_CHEST, ROYAL_EPIC_CHEST);
        QuestCardModel card = card(d, true, "Daily");
        assertEquals(List.of(new Item(MALUS, 2, "Mark of Malus"), new Item(FORGOTTEN_KING, 3, "Mark of the Forgotten King"),
            new Item(UNKNOWN_ITEM, 1, "Unknown item #9999")), card.requirements());
        assertEquals(List.of(new Item(ROYAL_EPIC_CHEST, 2, "Royal Epic Quest Chest"), new Item(MIGHTY_CHEST, 1, "Mighty Quest Chest")),
            card.rewards());
        assertTrue(card.rewardsKnown()); assertTrue(card.requirementsKnown());
        assertEquals("Royal tribute", card.id()); assertEquals("Royal tribute", card.name());
        assertEquals(5, card.category()); assertEquals("Daily", card.typeLabel()); assertTrue(card.pinned());
        assertEquals(QuestTier.MIGHTY, card.tier());
        assertEquals("Bring the listed items to the Tinkerer to claim your reward.", card.description());
        assertEquals("You get", card.rewardsTitle());
    }

    @Test public void aChoiceQuestPicksOneOfItsDistinctRewards() {
        QuestData d = data("Festival exchange", 8, new int[] {FESTIVAL_TOKEN}, MIGHTY_CHEST, ROYAL_EPIC_CHEST, CULTISH_EPIC_CHEST, ROYAL_EPIC_CHEST);
        d.itemOfChoice = true;
        QuestCardModel card = card(d, false, "Event");
        assertTrue(card.choice());
        assertEquals("Pick 1 of 3", card.rewardsTitle());
        assertEquals(3, card.rewards().size());
        d.rewards = null;
        assertEquals("An uncaptured choice names no count", "Pick 1", card(d, false, "Event").rewardsTitle());
        d.itemOfChoice = false;
        assertEquals("You get", card(d, false, "Event").rewardsTitle());
    }

    @Test public void badgesForEachRepeatableAndCompletedCombination() {
        QuestData d = data("Q", 5, new int[] {MALUS}, STANDARD_CHEST);
        d.repeatable = true; d.completed = false;
        assertEquals(List.of("↻ Repeatable"), card(d, false, "").badges());
        d.repeatable = true; d.completed = true;
        assertEquals(List.of("↻ Repeatable", "✓ Done"), card(d, false, "").badges());
        d.repeatable = false; d.completed = false;
        assertEquals(List.of("One-time"), card(d, false, "").badges());
        d.repeatable = false; d.completed = true;
        QuestCardModel done = card(d, false, "");
        assertEquals(List.of("One-time", "✓ Done"), done.badges());
        assertTrue(done.completed()); assertFalse(done.repeatable());
    }

    @Test public void uncapturedListsAreNotCapturedNeverEmptyNone() {
        QuestData d = data("Unknown", 5, null); d.rewards = null;
        QuestCardModel unknown = card(d, false, "");
        assertFalse(unknown.rewardsKnown()); assertTrue(unknown.rewards().isEmpty());
        assertFalse(unknown.requirementsKnown()); assertTrue(unknown.requirements().isEmpty());
        assertEquals(QuestTier.NOT_CAPTURED, unknown.tier());
        QuestCardModel empty = card(data("Empty", 5, new int[0]), false, "");
        assertTrue("Captured and empty stays known", empty.rewardsKnown()); assertTrue(empty.rewards().isEmpty());
        assertTrue(empty.requirementsKnown()); assertTrue(empty.requirements().isEmpty());
        assertEquals(QuestTier.NO_CHEST, empty.tier());
    }

    @Test public void rawExpirationIsKeptVerbatimAndEmptyWhenNotSupplied() {
        QuestData d = data("Q", 5, new int[] {MALUS}, STANDARD_CHEST);
        d.expiration = "  raw-unparsed 1759104000 ";
        assertEquals("  raw-unparsed 1759104000 ", card(d, false, "").rawExpiration());
        d.expiration = null;
        assertEquals("", card(d, false, "").rawExpiration());
        d.expiration = "";
        assertEquals("", card(d, false, "").rawExpiration());
    }

    @Test public void typeLabelIsTheUsersOwnTrimmedAndEmptyWhenUnlabeled() {
        QuestData d = data("Q", 5, new int[] {MALUS}, STANDARD_CHEST);
        assertEquals("Daily", card(d, false, "  Daily ").typeLabel());
        assertEquals("", card(d, false, "   ").typeLabel());
        assertEquals("", card(d, false, null).typeLabel());
    }

    @Test public void listsAreImmutableCopies() {
        QuestCardModel card = card(data("Q", 5, new int[] {MALUS}, STANDARD_CHEST), false, "");
        assertThrows(UnsupportedOperationException.class, () -> card.rewards().add(new Item(1, 1, "x")));
        assertThrows(UnsupportedOperationException.class, () -> card.requirements().clear());
        assertThrows(UnsupportedOperationException.class, () -> card.badges().clear());
        List<Item> source = new ArrayList<>(List.of(new Item(MALUS, 1, "Mark of Malus")));
        QuestCardModel built = new QuestCardModel("id", "name", false, false, false, false, 5, "", QuestTier.NO_CHEST,
            source, true, source, true, "", "");
        source.clear();
        assertEquals(1, built.rewards().size()); assertEquals(1, built.requirements().size());
    }
}
