package tomato.gui.quest;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.Test;
import packets.data.QuestData;
import tomato.gui.kit.KitFormat;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.quest.QuestBoardModel.Group;
import tomato.gui.quest.QuestBoardModel.GroupBy;
import tomato.gui.quest.QuestBoardModel.Summary;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestFixtures.*;

/**
 * The Board's groups keep a fixed order (tiers highest first; type labels alphabetically, then "No type label"; one "All quests"),
 * omit empty groups, and put pinned quests first only when asked, otherwise the input (the Board sort's) order. The summary counts
 * the given cards and says how long ago the list was captured with KitFormat.relative's wording against the injected clock.
 */
public class QuestBoardModelTest {
    private static final long NOW = 1_790_000_000_000L, MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    private static final LongSupplier CLOCK = () -> NOW;

    private static QuestCardModel typed(String name, int category, String label, boolean pinned) {
        return card(data(name, category, new int[] {MALUS}, STANDARD_CHEST), pinned, label);
    }
    private static QuestCardModel pinned(String name, int... rewards) { return card(data(name, 5, new int[] {MALUS}, rewards), true, ""); }
    private static QuestBoardModel build(List<QuestCardModel> cards, GroupBy by, boolean pinnedFirst) {
        return QuestBoardModel.build(cards, by, pinnedFirst, NOW - 14 * MINUTE, false);
    }
    private static List<String> keys(QuestBoardModel board) { List<String> v = new ArrayList<>(); for (Group g : board.groups()) v.add(g.key()); return v; }
    private static List<String> titles(QuestBoardModel board) { List<String> v = new ArrayList<>(); for (Group g : board.groups()) v.add(g.title()); return v; }
    private static List<String> names(Group group) { List<String> v = new ArrayList<>(); for (QuestCardModel c : group.cards()) v.add(c.name()); return v; }
    private static QuestCardModel uncaptured(String name) { QuestData d = data(name, 5, new int[] {MALUS}); d.rewards = null; return card(d, false, ""); }

    @Test public void tierGroupsKeepTheFixedOrderAndTitles() {
        List<QuestCardModel> cards = List.of(uncaptured("Unknown"), card("Tokens", FESTIVAL_TOKEN), card("Golden", GOLDEN_CHEST),
            card("Beginner", BEGINNER_CHEST), card("Standard", STANDARD_CHEST), card("Epic", CULTISH_EPIC_CHEST), card("Mighty", MIGHTY_CHEST),
            card("Royal", ROYAL_EPIC_CHEST));
        QuestBoardModel board = build(cards, GroupBy.TIER, true);
        assertEquals(List.of("mighty", "epic", "standard", "beginner", "other-chest", "no-chest", "not-captured"), keys(board));
        assertEquals(List.of("Mighty quest chests", "Epic quest chests", "Standard quest chests", "Beginner quest chests",
            "Other quest chests", "No quest chest", "Rewards not captured"), titles(board));
        assertEquals("Input order within a group", List.of("Epic", "Royal"), names(board.groups().get(1)));
        assertEquals(List.of("Golden"), names(board.groups().get(4)));
        assertEquals(List.of("Tokens"), names(board.groups().get(5)));
        assertEquals(List.of("Unknown"), names(board.groups().get(6)));
    }

    @Test public void emptyGroupsAreOmitted() {
        QuestBoardModel board = build(List.of(uncaptured("Unknown"), card("Epic", ROYAL_EPIC_CHEST)), GroupBy.TIER, true);
        assertEquals(List.of("epic", "not-captured"), keys(board));
        for (GroupBy by : GroupBy.values()) assertTrue(by + ": no cards, no groups", build(List.of(), by, true).groups().isEmpty());
        assertTrue(QuestBoardModel.build(null, GroupBy.NONE, false, 0, false).groups().isEmpty());
    }

    @Test public void typeGroupsAreLabelsAlphabeticallyThenNoTypeLabel() {
        List<QuestCardModel> cards = List.of(typed("Unlabeled 99", 99, "", false), typed("Event 8", 8, "Event", false),
            typed("Daily 5", 5, "Daily", false), typed("apex 7", 7, "apex", false), typed("Unlabeled 4", 4, "  ", false),
            typed("Event 3", 3, "Event", false));
        QuestBoardModel board = build(cards, GroupBy.TYPE, false);
        assertEquals("Ignoring case; one group per label, keyed by its lowest category",
            List.of("type-7", "type-5", "type-3", "no-type"), keys(board));
        assertEquals(List.of("apex", "Daily", "Event", "No type label"), titles(board));
        assertEquals(List.of("Event 8", "Event 3"), names(board.groups().get(2)));
        assertEquals(List.of("Unlabeled 99", "Unlabeled 4"), names(board.groups().get(3)));
        assertEquals("Chest tier is an independent axis", List.of("no-type"),
            keys(build(List.of(card("Mighty", MIGHTY_CHEST), uncaptured("Unknown")), GroupBy.TYPE, false)));
    }

    @Test public void noneIsOneAllQuestsGroup() {
        List<QuestCardModel> cards = List.of(card("A", MIGHTY_CHEST), pinned("B", FESTIVAL_TOKEN), uncaptured("C"));
        QuestBoardModel board = build(cards, GroupBy.NONE, false);
        assertEquals(List.of("all"), keys(board));
        assertEquals(List.of("All quests"), titles(board));
        assertEquals(List.of("A", "B", "C"), names(board.groups().get(0)));
        assertEquals(List.of("B", "A", "C"), names(build(cards, GroupBy.NONE, true).groups().get(0)));
    }

    @Test public void pinnedFirstWithinEachGroupOnlyWhenOn() {
        List<QuestCardModel> cards = List.of(card("a", MIGHTY_CHEST), pinned("b", MIGHTY_CHEST), card("c", MIGHTY_CHEST),
            pinned("d", MIGHTY_CHEST), card("e", STANDARD_CHEST), pinned("f", STANDARD_CHEST));
        QuestBoardModel on = build(cards, GroupBy.TIER, true), off = build(cards, GroupBy.TIER, false);
        assertEquals(List.of("b", "d", "a", "c"), names(on.groups().get(0)));
        assertEquals(List.of("f", "e"), names(on.groups().get(1)));
        assertEquals(List.of("a", "b", "c", "d"), names(off.groups().get(0)));
        assertEquals(List.of("e", "f"), names(off.groups().get(1)));
        List<QuestCardModel> typed = List.of(typed("x", 5, "Daily", false), typed("y", 5, "Daily", true));
        assertEquals(List.of("y", "x"), names(build(typed, GroupBy.TYPE, true).groups().get(0)));
    }

    @Test public void summaryCountsTheCardsAndSaysWhenTheListWasCaptured() {
        QuestData repeatDone = data("Repeat done", 5, new int[] {MALUS}, STANDARD_CHEST); repeatDone.repeatable = true; repeatDone.completed = true;
        QuestData repeat = data("Repeat", 5, new int[] {MALUS}, STANDARD_CHEST); repeat.repeatable = true;
        QuestData done = data("Done", 5, new int[] {MALUS}, STANDARD_CHEST); done.completed = true;
        List<QuestCardModel> cards = List.of(card(repeatDone, true, ""), card(repeat, true, ""), card(done, false, ""), card("Open", MIGHTY_CHEST));
        Summary summary = QuestBoardModel.build(cards, GroupBy.TIER, true, NOW - 14 * MINUTE, false).summary();
        assertEquals(new Summary(4, 2, 2, 2, NOW - 14 * MINUTE, false), summary);
        assertEquals("4 quests · 2 pinned · captured 14 min ago", summary.text(CLOCK));
        Summary stale = QuestBoardModel.build(cards, GroupBy.NONE, false, NOW - 3 * HOUR, true).summary();
        assertTrue(stale.stale());
        assertEquals("4 quests · 2 pinned · captured 3 h ago · stale", stale.text(CLOCK));
        assertEquals("1 quest · 0 pinned · captured just now",
            QuestBoardModel.build(List.of(card("Open", MIGHTY_CHEST)), GroupBy.TIER, true, NOW, false).summary().text(CLOCK));
        assertEquals("A captured empty list is a known zero", "0 quests · 0 pinned · captured 2 min ago",
            QuestBoardModel.build(List.of(), GroupBy.TIER, true, NOW - 2 * MINUTE, false).summary().text(CLOCK));
    }

    @Test public void noCapturedListSaysSoEvenWhenStale() {
        assertEquals("No quest list captured yet", QuestBoardModel.build(List.of(), GroupBy.TIER, true, 0, false).summary().text(CLOCK));
        assertEquals("No quest list captured yet", QuestBoardModel.build(List.of(), GroupBy.TIER, true, 0, true).summary().text(CLOCK));
        assertEquals(0L, QuestBoardModel.build(List.of(), GroupBy.TIER, true, 0, false).summary().capturedAt());
    }

    @Test public void capturedWordingFollowsKitFormatRelativeWithTheInjectedClock() {
        long[] ages = {-5 * MINUTE, 59_999, MINUTE, 59 * MINUTE, HOUR, 23 * HOUR + 59 * MINUTE, DAY, 2 * DAY - 1, 2 * DAY, 6 * DAY + 23 * HOUR};
        String[] texts = {"just now", "just now", "1 min ago", "59 min ago", "1 h ago", "23 h ago", "yesterday", "yesterday", "2 days ago", "6 days ago"};
        for (int i = 0; i < ages.length; i++) assertEquals("age " + ages[i], "1 quest · 0 pinned · captured " + texts[i],
            new Summary(1, 0, 0, 0, NOW - ages[i], false).text(CLOCK));
        String date = DisplayFormat.DATE.format(Instant.ofEpochMilli(NOW - 7 * DAY).atZone(ZoneId.systemDefault()));
        assertEquals("1 quest · 0 pinned · captured " + date, new Summary(1, 0, 0, 0, NOW - 7 * DAY, false).text(CLOCK));
        // Same wording as KitFormat.relative on the system clock (ages mid-minute, so a slow step cannot cross a boundary).
        for (long age : new long[] {30_000, 14 * MINUTE + 30_000, 3 * HOUR + 30_000, DAY + 30_000, 4 * DAY + 30_000, 10 * DAY}) {
            long at = System.currentTimeMillis() - age;
            assertEquals("age " + age, "1 quest · 0 pinned · captured " + KitFormat.relative(at),
                new Summary(1, 0, 0, 0, at, false).text(System::currentTimeMillis));
        }
    }

    @Test public void groupsAndCardsAreImmutable() {
        QuestBoardModel board = build(new ArrayList<>(List.of(card("A", MIGHTY_CHEST))), GroupBy.TIER, true);
        assertThrows(UnsupportedOperationException.class, () -> board.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> board.groups().get(0).cards().clear());
    }
}
