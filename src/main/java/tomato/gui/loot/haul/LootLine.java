package tomato.gui.loot.haul;

import java.util.ArrayList;
import java.util.List;
import tomato.gui.stats.LootFacts;

/**
 * The loot wording shared by the run cards, the run recap and the haul ("1 UT · 2 potions", "3 items in 2 bags · …"): untiered,
 * then set-tiered, then potions; nothing else is counted by kind.
 */
public final class LootLine {
    private LootLine() {}

    /** "1 UT · 2 potions" for these items; "" when none is untiered, set-tiered or a potion. */
    public static String kinds(List<LootFacts.Item> items) {
        int untiered = 0, setTiered = 0, potions = 0;
        for (LootFacts.Item item : items) {
            if (item.untiered()) untiered++;
            if (item.setTiered()) setTiered++;
            if (item.potion()) potions++;
        }
        List<String> parts = new ArrayList<>();
        if (untiered > 0) parts.add(untiered + " UT");
        if (setTiered > 0) parts.add(setTiered + " ST");
        if (potions > 0) parts.add(potions + (potions == 1 ? " potion" : " potions"));
        return String.join(" · ", parts);
    }

    /** "3 items in 2 bags · 1 UT · 2 potions" ({@code kinds} as {@link #kinds} words it; "" leaves it out). */
    public static String section(int items, int bags, String kinds) {
        String line = items + (items == 1 ? " item" : " items") + " in " + bags + (bags == 1 ? " bag" : " bags");
        return kinds == null || kinds.isEmpty() ? line : line + " · " + kinds;
    }
}
