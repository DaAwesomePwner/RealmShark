package tomato.gui.loot.explore;

import java.util.*;
import tomato.gui.kit.Portals;
import tomato.gui.runs.DungeonCardModel;
import tomato.gui.stats.LootFacts;

/**
 * Explore's Dungeons wall: one tile per dungeon that a saved run (Runs › Dungeons) or a saved bag knows, by canonical name. A
 * tile has the dungeon's total runs (null when only loot knows it), its runs with loot (distinct drop-time runs among its bags),
 * white bags and UTs with their rates per run with loot (null without one), its top 3 drops ignoring rarity, and its latest
 * activity. Unknown and unrecognized areas are never tiles. Pure.
 */
public record AtlasModel(List<Tile> tiles) {
    public AtlasModel { tiles = List.copyOf(tiles); }

    public enum Sort {
        RUNS("Most runs"), WHITES("Whites per run"), UTS("UTs per run"), RECENT("Recent");
        public final String label;
        Sort(String label) { this.label = label; }
    }

    public record Tile(String dungeon, int portalId, Integer runs, int lootRuns, int whites, int uts, List<DungeonStats.Item> top, long last) {
        public Tile { top = List.copyOf(top); }
        public Double whitesPerRun() { return lootRuns == 0 ? null : (double) whites / lootRuns; }
        public Double utsPerRun() { return lootRuns == 0 ? null : (double) uts / lootRuns; }
    }

    public static AtlasModel of(List<DungeonCardModel> cards, List<LootFacts.Bag> bags, Sort sort, String search) {
        Map<String, DungeonCardModel> byName = new LinkedHashMap<>();
        for (DungeonCardModel card : cards) if (known(card.canonical())) byName.put(card.canonical(), card);
        Map<String, List<LootFacts.Bag>> loot = new LinkedHashMap<>();
        for (LootFacts.Bag bag : bags) if (known(bag.dungeon())) loot.computeIfAbsent(bag.dungeon(), name -> new ArrayList<>()).add(bag);
        Set<String> dungeons = new LinkedHashSet<>(byName.keySet());
        dungeons.addAll(loot.keySet());
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Tile> tiles = new ArrayList<>();
        for (String dungeon : dungeons) {
            if (!query.isEmpty() && !dungeon.toLowerCase(Locale.ROOT).contains(query)) continue;
            List<LootFacts.Bag> own = loot.getOrDefault(dungeon, List.of());
            DungeonStats stats = DungeonStats.of(own, dungeon);
            DungeonCardModel card = byName.get(dungeon);
            long last = card == null ? 0 : card.lastVisit();
            for (LootFacts.Bag bag : own) last = Math.max(last, bag.time());
            List<DungeonStats.Item> top = stats.mostDropped().size() > 3 ? stats.mostDropped().subList(0, 3) : stats.mostDropped();
            tiles.add(new Tile(dungeon, card != null ? card.portalId() : Portals.spriteId(dungeon), card == null ? null : card.visits(),
                stats.runs(), stats.whites(), stats.uts(), top, last));
        }
        Comparator<Tile> byDungeon = Comparator.comparing(tile -> tile.dungeon().toLowerCase(Locale.ROOT));
        Comparator<Tile> order = switch (sort) {
            case RUNS -> Comparator.comparingInt((Tile tile) -> tile.runs() == null ? -1 : tile.runs()).reversed()
                .thenComparing(Comparator.comparingInt(Tile::lootRuns).reversed());
            case WHITES -> Comparator.comparing(Tile::whitesPerRun, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingInt(Tile::lootRuns).reversed());
            case UTS -> Comparator.comparing(Tile::utsPerRun, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingInt(Tile::lootRuns).reversed());
            case RECENT -> Comparator.comparingLong(Tile::last).reversed();
        };
        tiles.sort(order.thenComparing(byDungeon));
        return new AtlasModel(tiles);
    }

    private static boolean known(String dungeon) {
        return dungeon != null && !dungeon.isBlank() && !LootFacts.UNKNOWN_AREA.equals(dungeon) && !LootFacts.UNRECOGNIZED.equals(dungeon)
            && !LootFacts.UNKNOWN.equals(dungeon);
    }
}
