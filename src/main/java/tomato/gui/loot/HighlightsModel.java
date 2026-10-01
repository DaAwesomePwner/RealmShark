package tomato.gui.loot;

import java.util.*;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.Portals;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;

/**
 * What Loot › Highlights shows for one window (spec §6.4; P6a decisions), built off the EDT by {@link HighlightsSource} and
 * applied on the EDT as is (immutable).
 * - Tiles use Home's rules so the two agree to the number for the same window: UT and ST by the saved item classification,
 *   potions by the item's potion flag, white bags by the saved bag name, enchanted by rare-or-better slots regardless of kind.
 *   When no bag was saved in the window's sessions the five
 *   tiles are unknown ({@link #NO_LOOT}), never 0; a window whose sessions saved loot but none in the period is a real zero.
 *   Sessions that could not be read make the counts partial (◐), as does a live list capped at its latest 1,000 bags.
 * - {@code potionsByStat}: stat → potions (small, greater and soulbound together) in stat order, {@link #OTHER_POTIONS} last;
 *   it sums to the potions tile.
 * - {@code enchantedByRarity}: counts in rarity order across all item kinds; it sums to the enchanted tile. {@link Focus}
 *   narrows the notable list after bag visibility, without changing any tile's window-wide count.
 * - {@code notable}: the window's notable drops, newest bag first and each bag's items in notability order (UT, ST, stat potion,
 *   enchanted), each item once under its first kind. Each bag name (null: none saved) keeps its newest {@value #NOTABLE_LIMIT}, so
 *   whichever bag colors Filter Loot hides, {@link #shown} still finds the newest {@value #NOTABLE_LIMIT} visible drops;
 *   {@code notableTotal} says how many there were, and {@code notableByBag} how many under each bag name. "Enchanted" is rare or
 *   better (2 or more recorded enchant slots); {@code enchantUnknown} counts the other items without recorded slots, which are
 *   never listed as enchanted nor counted as not enchanted.
 * - {@code dungeons}: one cell per area, most bags first (then by name), "Unknown area" (dungeon null: no map recorded, or an area
 *   the catalog does not know) last and never dropped.
 * - {@code bags} and {@code unnamedBags}: the window's bags and those without a saved bag name (legacy saves; not counted as white).
 * - {@code unavailable}: why saved history could not be read at all (then everything is unknown), else null.
 * Counts are observed drops, not pickups ({@link #OBSERVED}, in every tile's tooltip).
 */
public record HighlightsModel(Window window, Source source, DisplayValue ut, DisplayValue st, DisplayValue potions, DisplayValue whites,
                              DisplayValue enchanted, Map<EnchantInfo.Rarity, Integer> enchantedByRarity,
                              Map<String, Integer> potionsByStat, List<Notable> notable, List<DungeonCell> dungeons, int sessionsSkipped,
                              boolean capped, long capturedAt, String unavailable, int bags, int unnamedBags, int notableTotal,
                              int enchantUnknown, Map<String, Integer> notableByBag) {
    public static final String NO_LOOT = "No loot was saved for this period", NO_LIVE_LOOT = "No loot was observed in this app run yet",
        OBSERVED = "Observed drops, not pickups", LIVE = "This app run · not saved", CAPPED = "latest 1,000 bags",
        UNKNOWN_AREA = LootFacts.UNKNOWN_AREA, OTHER_POTIONS = "Other potions";
    /** What capture saves for an area the dungeon catalog does not know ({@link LootFacts#UNRECOGNIZED}): Unknown area here. */
    static final String UNRECOGNIZED = LootFacts.UNRECOGNIZED;
    public static final int NOTABLE_LIMIT = 200;
    /** The stat potions' stats in {@code StatPotion} order, with the sub-line's short labels. */
    private static final String[] STATS = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"},
        SHORT = {"Life", "Mana", "Att", "Def", "Spd", "Dex", "Vit", "Wis"};
    /** The potions sub-line lists at most this many stats; the tooltip lists them all. */
    private static final int SUBLINE_STATS = 4;

    /** Today = the local calendar day; This session = since RealmShark started (Home's windows). */
    public enum Window {
        TODAY("today", "Today"), SESSION("session", "This session");
        private final String key, label;
        Window(String key, String label) { this.key = key; this.label = label; }
        /** The value kept in {@code ui.loot.highlights}. */
        public String key() { return key; }
        public String label() { return label; }
        /** A saved value; anything else (none, unknown) is Today. */
        public static Window of(String key) { return SESSION.key.equals(key) ? SESSION : TODAY; }
    }

    /** Saved history, or (no history store open) this app run's live capture, which is not saved. */
    public enum Source { SAVED, LIVE_UNSAVED }

    /** Temporary notable-list focus; enchantment and white bags can overlap any item kind. */
    public enum Focus {
        ALL("notable drops"), UT("UT drops"), ST("ST drops"), POTIONS("stat potion drops"),
        WHITES("white-bag drops"), ENCHANTED("enchanted drops");
        private final String label;
        Focus(String label) { this.label = label; }
        public String label() { return label; }
        public boolean test(Notable drop) {
            return switch (this) {
                case ALL -> true;
                case UT -> drop.kind() == Kind.UT;
                case ST -> drop.kind() == Kind.ST;
                case POTIONS -> drop.kind() == Kind.POTION;
                case WHITES -> LootFacts.whiteBag(drop.bag());
                case ENCHANTED -> drop.enchant().enchanted() && drop.enchant().rarity().ordinal() >= EnchantInfo.Rarity.RARE.ordinal();
            };
        }
    }

    /** Notability order: an item that is several kinds is listed once, under the first. */
    public enum Kind {
        UT("UT"), ST("ST"), POTION("Potion"), ENCHANTED("Enchanted");
        private final String label;
        Kind(String label) { this.label = label; }
        /** The chip's text. */
        public String label() { return label; }
    }

    /**
     * One notable drop: {@code bag} as saved (null = no bag name saved), {@code dungeon} null = Unknown area, {@code visit} the exact
     * run recorded at drop time (null = not linked to a run: none is inferred).
     * {@code enchant} gives the rarity pips and glow, the rarity words and the hover enchant lines (not recorded = none).
     * {@code tier} is the tier label saved with the drop (null: none saved; the renderer then asks the current definitions).
     */
    public record Notable(int itemId, String bag, String dungeon, long time, VisitRef visit, Kind kind, EnchantInfo enchant, String tier) {
        public Notable {
            if (enchant == null) enchant = EnchantInfo.notRecorded();
        }
        public Notable(int itemId, String bag, String dungeon, long time, VisitRef visit, Kind kind, EnchantInfo enchant) {
            this(itemId, bag, dungeon, time, visit, kind, enchant, null);
        }
        public Notable(int itemId, String bag, String dungeon, long time, VisitRef visit, Kind kind) {
            this(itemId, bag, dungeon, time, visit, kind, null);
        }
        /** A stable identity for the painted list (equal drops of one bag share it, which only affects the kept selection). */
        public String key() { return time + "/" + itemId + "/" + bag + "/" + visit + "/" + kind; }
    }

    /** The notable drops Filter Loot leaves visible: at most {@value #NOTABLE_LIMIT}, newest first; the visible and hidden totals. */
    public record Shown(List<Notable> items, int total, int hidden) {
        public Shown { items = List.copyOf(items); }
    }

    /**
     * The notable drops whose bag name {@code showsBag} accepts (Filter Loot; null = no bag name saved): the newest
     * {@value #NOTABLE_LIMIT} of them, exact because every bag name keeps its own newest {@value #NOTABLE_LIMIT}; {@code total} counts
     * every visible notable drop of the window and {@code hidden} the rest.
     */
    public Shown shown(java.util.function.Predicate<String> showsBag) {
        return shown(showsBag, Focus.ALL);
    }

    /**
     * Bag visibility and focus together. ALL totals include the whole window; focused totals and hidden counts cover matching
     * retained drops and can undercount only beyond NOTABLE_LIMIT per bag name. The displayed list remains capped at that limit.
     */
    public Shown shown(java.util.function.Predicate<String> showsBag, Focus focus) {
        List<Notable> visible = new ArrayList<>();
        int matching = 0, hidden = 0;
        for (Notable drop : notable) {
            if (!focus.test(drop)) continue;
            if (showsBag.test(drop.bag())) {
                matching++;
                if (visible.size() < NOTABLE_LIMIT) visible.add(drop);
            } else hidden++;
        }
        if (focus != Focus.ALL) return new Shown(visible, matching, hidden);
        int total = 0;
        for (Map.Entry<String, Integer> entry : notableByBag.entrySet()) if (showsBag.test(entry.getKey())) total += entry.getValue();
        return new Shown(visible, total, notableTotal - total);
    }

    /** One area's bags in the window: {@code dungeon} null = Unknown area; {@code portalId} 0 = the kit's placeholder glyph. */
    public record DungeonCell(String dungeon, int portalId, int bags, int ut, int st, int potions) {
        /** The area's name, or {@link #UNKNOWN_AREA} ({@link LootFacts#areaLabel}). */
        public String name() { return LootFacts.areaLabel(dungeon); }
    }

    public HighlightsModel {
        Objects.requireNonNull(window, "window"); Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ut, "ut"); Objects.requireNonNull(st, "st"); Objects.requireNonNull(potions, "potions"); Objects.requireNonNull(whites, "whites");
        Objects.requireNonNull(enchanted, "enchanted");
        Map<EnchantInfo.Rarity, Integer> rarities = new EnumMap<>(EnchantInfo.Rarity.class);
        if (enchantedByRarity != null) rarities.putAll(enchantedByRarity);
        enchantedByRarity = Collections.unmodifiableMap(rarities);
        potionsByStat = Collections.unmodifiableMap(new LinkedHashMap<>(potionsByStat == null ? Map.of() : potionsByStat));   // keeps the order
        notable = notable == null ? List.of() : List.copyOf(notable);
        // A HashMap: a bag without a saved name counts under the null key.
        notableByBag = Collections.unmodifiableMap(new HashMap<>(notableByBag == null ? Map.of() : notableByBag));
        dungeons = dungeons == null ? List.of() : List.copyOf(dungeons);
        if (sessionsSkipped < 0 || bags < 0 || unnamedBags < 0 || notableTotal < 0 || enchantUnknown < 0)
            throw new IllegalArgumentException("Counts must not be negative");
    }

    /**
     * The model of {@code bags}, the window's bags (already kept to its period). {@code lootRecorded}: a bag was saved in the
     * window's sessions (Home's rule; false makes the tiles unknown); {@code sessionsSkipped}: sessions of the window that could
     * not be read; {@code capped}: the live list holds only the latest 1,000 bags.
     */
    static HighlightsModel of(Window window, Source source, List<LootFacts.Bag> bags, boolean lootRecorded, int sessionsSkipped,
                              boolean capped, long capturedAt) {
        List<LootFacts.Bag> newest = new ArrayList<>(bags);
        // Newest bag first; equal times keep the later-listed (later-saved) bag first.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < newest.size(); i++) order.add(i);
        order.sort(Comparator.comparingLong((Integer i) -> newest.get(i).time()).reversed().thenComparing(Comparator.reverseOrder()));
        int ut = 0, st = 0, potions = 0, whites = 0, enchanted = 0, unnamed = 0, enchantUnknown = 0;
        Map<EnchantInfo.Rarity, Integer> byRarity = new EnumMap<>(EnchantInfo.Rarity.class);
        int[] stats = new int[STATS.length + 1];   // the last slot: other potions
        List<Notable> notable = new ArrayList<>();
        Map<String, Integer> notableByBag = new HashMap<>();   // bag name (null: none saved) → notable drops
        int total = 0;
        Map<String, int[]> cells = new HashMap<>();   // known area → bags, UT, ST, potions
        int[] unknown = null;                          // Unknown area's, once a bag has none
        for (int index : order) {
            LootFacts.Bag bag = newest.get(index);
            if (bag.bag() == null) unnamed++;
            if (bag.white()) whites++;
            String area = LootFacts.area(bag.dungeon());   // null: Unknown area (none recorded, or the catalog did not know it)
            int[] cell = area != null ? cells.computeIfAbsent(area, key -> new int[4]) : unknown != null ? unknown : (unknown = new int[4]);
            cell[0]++;
            List<Notable> listed = new ArrayList<>();
            for (LootFacts.Item item : bag.items()) {
                if (item.enchanted()) { enchanted++; byRarity.merge(item.enchant().rarity(), 1, Integer::sum); }
                if (item.untiered()) { ut++; cell[1]++; }
                if (item.setTiered()) { st++; cell[2]++; }
                if (item.potion()) { potions++; cell[3]++; stats[stat(item.id())]++; }
                Kind kind = kind(item);
                if (kind != null) listed.add(new Notable(item.id(), bag.bag(), area, bag.time(), bag.visit(), kind, item.enchant(), item.tier()));
                else if (!item.potion() && !item.enchantKnown()) enchantUnknown++;   // could be enchanted: never counted either way
            }
            listed.sort(Comparator.comparing(Notable::kind));   // stable: drop order within a kind
            for (Notable drop : listed) {
                // Each bag name keeps its newest NOTABLE_LIMIT (bags come newest first), so a hidden color never crowds out another.
                int seen = notableByBag.merge(drop.bag(), 1, Integer::sum);
                if (seen <= NOTABLE_LIMIT) notable.add(drop);
                total++;
            }
        }
        Map<String, Integer> byStat = new LinkedHashMap<>();
        for (int i = 0; i < STATS.length; i++) if (stats[i] > 0) byStat.put(STATS[i], stats[i]);
        if (stats[STATS.length] > 0) byStat.put(OTHER_POTIONS, stats[STATS.length]);
        List<DungeonCell> dungeons = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : cells.entrySet()) {
            int[] c = entry.getValue();
            dungeons.add(new DungeonCell(entry.getKey(), Portals.spriteId(entry.getKey()), c[0], c[1], c[2], c[3]));
        }
        dungeons.sort(Comparator.comparing(DungeonCell::bags, Comparator.reverseOrder()).thenComparing(c -> c.name().toLowerCase(Locale.ROOT)));
        if (unknown != null) dungeons.add(new DungeonCell(null, 0, unknown[0], unknown[1], unknown[2], unknown[3]));   // last, never dropped

        String scope = source == Source.LIVE_UNSAVED ? "this app run (not saved)" : window.label() + " from saved history";
        String missing = sessionsSkipped > 0 ? unreadable(sessionsSkipped)
            : capped ? "Counts cover only the latest 1,000 bags of this app run (" + CAPPED + ")" : null;
        String none = source == Source.LIVE_UNSAVED ? NO_LIVE_LOOT : NO_LOOT;
        String whiteNote = unnamed == 0 ? "" : "; " + unnamed + (unnamed == 1 ? " bag without a saved bag name is not counted" : " bags without a saved bag name are not counted");
        return new HighlightsModel(window, source,
            tile(ut, "UT drops, " + scope, lootRecorded, none, missing),
            tile(st, "ST drops, " + scope, lootRecorded, none, missing),
            tile(potions, "Potion drops" + (byStat.isEmpty() ? "" : " (" + potionWords(byStat) + ")") + ", " + scope, lootRecorded, none, missing),
            tile(whites, "White bags by their saved bag name, " + scope + whiteNote, lootRecorded, none, missing),
            tile(enchanted, "Enchanted drops (rare or better: 2+ enchant slots), " + scope
                + (byRarity.isEmpty() ? "" : " (" + enchantLine(byRarity) + ")"), lootRecorded, none, missing), byRarity,
            byStat, notable, dungeons, sessionsSkipped, capped, capturedAt, null, newest.size(), unnamed, total, enchantUnknown, notableByBag);
    }

    /** Saved history could not be read at all: every value unknown with {@code reason}. */
    static HighlightsModel unavailable(Window window, Source source, String reason, long capturedAt) {
        DisplayValue unknown = DisplayValue.unknown(reason);
        return new HighlightsModel(window, source, unknown, unknown, unknown, unknown, unknown, Map.of(), Map.of(), List.of(), List.of(), 0, false, capturedAt,
            Objects.requireNonNull(reason, "reason"), 0, 0, 0, 0, Map.of());
    }

    /** The item's notable kind, or null: UT, ST, a stat potion (by id), or enchanted (2+ recorded slots; unknown is never). (public: Explore reuses it) */
    public static Kind kind(LootFacts.Item item) {
        if (item.untiered()) return Kind.UT;
        if (item.setTiered()) return Kind.ST;
        if (LootFacts.potionStat(item.id()) != null) return Kind.POTION;
        if (item.enchanted()) return Kind.ENCHANTED;
        return null;
    }

    /** "2 Life · 1 Mana · 3 Def · 1 other" (at most four stats, then "+N more"); empty without potions. */
    static String potionLine(Map<String, Integer> byStat) {
        List<String> parts = new ArrayList<>();
        int shown = 0, more = 0;
        for (Map.Entry<String, Integer> entry : byStat.entrySet()) {
            if (shown == SUBLINE_STATS) { more++; continue; }
            parts.add(entry.getValue() + " " + shortLabel(entry.getKey()));
            shown++;
        }
        if (more > 0) parts.add("+" + more + " more");
        return String.join(" · ", parts);
    }

    /** Every recorded rare-or-better rarity, in slot-count order, regardless of the item's notable kind. */
    static String enchantLine(Map<EnchantInfo.Rarity, Integer> byRarity) {
        List<String> parts = new ArrayList<>();
        for (EnchantInfo.Rarity rarity : EnchantInfo.Rarity.values())
            if (byRarity.containsKey(rarity)) parts.add(byRarity.get(rarity) + " " + rarity.label);
        return String.join(" · ", parts);
    }

    /** "Saved history · Today", "This app run · not saved", "This app run · not saved · latest 1,000 bags". */
    public String sourceLabel() {
        if (source == Source.LIVE_UNSAVED) return capped ? LIVE + " · " + CAPPED : LIVE;
        return "Saved history · " + window.label();
    }

    /** "1 saved session could not be read", "3 saved sessions could not be read" (Home's words). */
    static String unreadable(int sessions) { return sessions + (sessions == 1 ? " saved session" : " saved sessions") + " could not be read"; }

    /** Unknown when nothing was saved; partial (◐) when part of the window could not be read; else the count (0 is a real zero). */
    private static DisplayValue tile(int count, String detail, boolean recorded, String none, String missing) {
        if (!recorded) return DisplayValue.unknown(none);
        String described = detail + " · " + OBSERVED;
        if (missing != null) return DisplayValue.partial(DisplayFormat.formatInteger(count), missing + " · " + described);
        return DisplayValue.count((long) count, described, null);
    }

    /** "2 Life, 1 Mana, 3 Defense, 1 other potion": the tooltip's full names. */
    private static String potionWords(Map<String, Integer> byStat) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : byStat.entrySet())
            parts.add(entry.getValue() + " " + (OTHER_POTIONS.equals(entry.getKey()) ? (entry.getValue() == 1 ? "other potion" : "other potions") : entry.getKey()));
        return String.join(", ", parts);
    }

    private static String shortLabel(String stat) {
        if (OTHER_POTIONS.equals(stat)) return "other";
        for (int i = 0; i < STATS.length; i++) if (STATS[i].equals(stat)) return SHORT[i];
        return stat;
    }

    /** The stat slot of a potion id; STATS.length for other potions. */
    private static int stat(int id) {
        String stat = LootFacts.potionStat(id);
        for (int i = 0; stat != null && i < STATS.length; i++) if (STATS[i].equals(stat)) return i;
        return STATS.length;
    }
}
