package tomato.gui.loot.haul;

import java.util.*;
import tomato.gui.kit.BagSprites;
import tomato.gui.kit.Tokens;
import tomato.gui.stats.LootFacts;
import tomato.realmshark.EnchantInfo;

/**
 * One run's haul, ready to draw ({@link HaulView}): the best drop, the bags grouped by type in value order, and the tally line.
 * Pure: no components, no I/O. {@code header} is null where the surface has its own (the run recap); {@code hero} is null without
 * items; {@code openByDefault} is the shelf index of the group holding the hero (0 when there is no hero), -1 without bags.
 */
public record HaulModel(Header header, Hero hero, List<Shelf> shelf, int openByDefault, String tally, int items) {
    public HaulModel { shelf = List.copyOf(shelf); Objects.requireNonNull(tally, "tally"); }

    /** The run's facts for the Full haul. {@code outcome} is its label ("Completed"); null fields are unknown and left out. */
    public record Header(String mapName, int portalId, String outcome, Tokens.Tone outcomeTone, Long entered, Long durationMs, String character) {
        public Header { Objects.requireNonNull(mapName, "mapName"); }
    }

    /** One saved bag: its name ("White", "B.White"; null when none was saved), drop time, dropper (null when unknown) and items. */
    public record Bag(String bag, long time, String dropper, List<LootFacts.Item> items) {
        public Bag { items = List.copyOf(items); }
    }

    /** The best single item and the bag it came from. */
    public record Hero(LootFacts.Item item, String bag, String dropper, long time) {}

    /** Every bag of one type ({@code bag} as saved; null groups the bags saved without a name), newest first. */
    public record Shelf(String bag, List<Bag> bags) {
        public Shelf { bags = List.copyOf(bags); }
        public int items() {
            int count = 0;
            for (Bag bag : bags) count += bag.items().size();
            return count;
        }
    }

    /** Best first: UT, ST, higher enchant rarity, higher tier, potion; ties keep the earliest drop. */
    static final Comparator<Hero> BEST = Comparator.comparing((Hero h) -> !h.item().untiered())
        .thenComparing(h -> !h.item().setTiered())
        .thenComparing(h -> -rarity(h.item()))
        .thenComparing(h -> -tier(h.item()))
        .thenComparing(h -> !h.item().potion())
        .thenComparingLong(Hero::time);

    public static HaulModel of(Header header, List<Bag> bags) {
        Map<String, List<Bag>> groups = new LinkedHashMap<>();
        List<LootFacts.Item> all = new ArrayList<>();
        Hero hero = null;
        for (Bag bag : bags) {
            groups.computeIfAbsent(bag.bag(), name -> new ArrayList<>()).add(bag);
            for (LootFacts.Item item : bag.items()) {
                all.add(item);
                Hero candidate = new Hero(item, bag.bag(), bag.dropper(), bag.time());
                if (hero == null || BEST.compare(candidate, hero) < 0) hero = candidate;
            }
        }
        List<Shelf> shelf = new ArrayList<>();
        for (Map.Entry<String, List<Bag>> group : groups.entrySet()) {
            List<Bag> newest = new ArrayList<>(group.getValue());
            newest.sort(Comparator.comparingLong(Bag::time).reversed());
            shelf.add(new Shelf(group.getKey(), newest));
        }
        shelf.sort(Comparator.comparingInt((Shelf s) -> -BagSprites.rank(s.bag())).thenComparingLong(HaulModel::earliest));
        int open = shelf.isEmpty() ? -1 : 0;
        if (hero != null) for (int i = 0; i < shelf.size(); i++) if (Objects.equals(shelf.get(i).bag(), hero.bag())) open = i;
        String tally = bags.isEmpty() ? "" : LootLine.section(all.size(), bags.size(), LootLine.kinds(all));
        return new HaulModel(header, hero, shelf, open, tally, all.size());
    }

    /** item ID/slots/applied: {@code LootQuery}'s exact variant key (unknown counts read "null"). */
    public static String variantKey(LootFacts.Item item) { return item.id() + "/" + item.slots() + "/" + item.applied(); }

    /** Rarity order 0 (Unenchanted) to 4 (Divine); -1 when not recorded, so a known rarity always wins. */
    static int rarity(LootFacts.Item item) {
        EnchantInfo.Rarity rarity = item.enchant().rarity();
        return rarity == EnchantInfo.Rarity.UNKNOWN ? -1 : rarity.ordinal();
    }

    /** The tier number of a saved "T13"-style label; -1 for none, "UT"/"ST" or anything unreadable. */
    static int tier(LootFacts.Item item) {
        String tier = item.tier();
        if (tier == null || tier.length() < 2 || Character.toUpperCase(tier.charAt(0)) != 'T') return -1;
        try { return Integer.parseInt(tier.substring(1)); } catch (NumberFormatException e) { return -1; }
    }

    private static long earliest(Shelf shelf) { return shelf.bags().get(shelf.bags().size() - 1).time(); }
}
