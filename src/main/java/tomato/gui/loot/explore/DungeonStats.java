package tomato.gui.loot.explore;

import java.util.*;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;

/** Every saved bag in one canonical dungeon. Item counts combine all enchant variants; no I/O or components. */
public record DungeonStats(String dungeon, int bags, int runs, int whites, int uts, int sts, int potions, List<Item> mostDropped) {
    public DungeonStats { mostDropped = List.copyOf(mostDropped); }
    public record Item(int id, int count, long newest) {}

    public static DungeonStats of(List<LootFacts.Bag> saved, String canonical) {
        String dungeon = LootFacts.UNKNOWN_AREA.equals(canonical) ? null : LootFacts.area(canonical);
        Set<VisitRef> runs = new HashSet<>();
        Map<Integer, Item> items = new HashMap<>();
        int bags = 0, whites = 0, uts = 0, sts = 0, potions = 0;
        if (dungeon != null) for (LootFacts.Bag bag : saved) {
            if (!dungeon.equals(bag.dungeon())) continue;
            bags++;
            if (bag.visit() != null) runs.add(bag.visit());
            if (bag.white()) whites++;
            for (LootFacts.Item item : bag.items()) {
                if (item.untiered()) uts++;
                if (item.setTiered()) sts++;
                if (item.potion() && LootFacts.potionStat(item.id()) != null) potions++;
                if (item.untiered() || item.setTiered() || item.highTier()) {
                    Item old = items.get(item.id());
                    items.put(item.id(), new Item(item.id(), old == null ? 1 : old.count() + 1,
                        old == null ? bag.time() : Math.max(old.newest(), bag.time())));
                }
            }
        }
        List<Item> top = items.values().stream().sorted(Comparator.comparingInt(Item::count).reversed()
            .thenComparing(Comparator.comparingLong(Item::newest).reversed()).thenComparingInt(Item::id)).limit(6).toList();
        return new DungeonStats(dungeon, bags, runs.size(), whites, uts, sts, potions, top);
    }
}
