package tomato.gui.loot.explore;

import java.util.*;
import java.util.function.IntFunction;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.stats.LootFacts;

/**
 * Explore's Collection, the trophy cabinet: every item ever looted, one entry per item ID with its drop count, on shelves by kind
 * (UTs, STs, Tiered, Potions, Other), most-dropped first. An entry's {@code front} is the drop drawn for it: the best enchant rarity,
 * then the newest. Pure.
 */
public record CollectionModel(List<Shelf> shelves, int drops, int kinds) {
    public CollectionModel { shelves = List.copyOf(shelves); }

    /** The shelves, in this order. */
    public enum Kind {
        UT("UTs"), ST("STs"), TIERED("Tiered"), POTIONS("Potions"), OTHER("Other");
        public final String label;
        Kind(String label) { this.label = label; }
    }

    /** One item: {@code count} drops, {@code front} the one drawn, {@code last} its newest drop's time. */
    public record Entry(int itemId, int count, LootFacts.Item front, long last) {}

    /** One shelf's entries, most-dropped first. */
    public record Shelf(Kind kind, List<Entry> entries) {
        public Shelf { entries = List.copyOf(entries); }
    }

    /** UT, then ST, then tiered (T13+), then stat potion; everything else is Other. */
    public static Kind kind(LootFacts.Item item) {
        if (item.untiered()) return Kind.UT;
        if (item.setTiered()) return Kind.ST;
        if (item.highTier()) return Kind.TIERED;
        if (item.potion()) return Kind.POTIONS;
        return Kind.OTHER;
    }

    /** The cabinet of {@code bags}, keeping only items whose name ({@code names}) contains {@code search}, ignoring case. */
    public static CollectionModel of(List<LootFacts.Bag> bags, String search, IntFunction<String> names) {
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        final class Tally { int count; LootFacts.Item front; long frontTime, last; }
        Map<Integer, Tally> byId = new LinkedHashMap<>();
        Map<Integer, Boolean> matches = new HashMap<>();
        int drops = 0;
        for (LootFacts.Bag bag : bags) for (LootFacts.Item item : bag.items()) {
            if (!query.isEmpty() && !matches.computeIfAbsent(item.id(),
                id -> Objects.toString(names.apply(id), "").toLowerCase(Locale.ROOT).contains(query))) continue;
            Tally tally = byId.computeIfAbsent(item.id(), id -> new Tally());
            tally.count++;
            drops++;
            tally.last = Math.max(tally.last, bag.time());
            if (tally.front == null || better(item, bag.time(), tally.front, tally.frontTime)) { tally.front = item; tally.frontTime = bag.time(); }
        }
        Map<Kind, List<Entry>> shelves = new EnumMap<>(Kind.class);
        for (Map.Entry<Integer, Tally> entry : byId.entrySet()) {
            Tally tally = entry.getValue();
            shelves.computeIfAbsent(kind(tally.front), kind -> new ArrayList<>()).add(new Entry(entry.getKey(), tally.count, tally.front, tally.last));
        }
        List<Shelf> result = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            List<Entry> entries = shelves.get(kind);
            if (entries == null) continue;
            entries.sort(Comparator.comparingInt(Entry::count).reversed().thenComparing(Comparator.comparingLong(Entry::last).reversed())
                .thenComparingInt(Entry::itemId));
            result.add(new Shelf(kind, entries));
        }
        return new CollectionModel(result, drops, byId.size());
    }

    /** A higher enchant rarity wins; at equal rarity, the newer drop. */
    private static boolean better(LootFacts.Item item, long time, LootFacts.Item front, long frontTime) {
        int a = HaulModel.rarity(item), b = HaulModel.rarity(front);
        return a != b ? a > b : time > frontTime;
    }
}
