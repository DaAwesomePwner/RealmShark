package tomato.gui.loot.explore;

import java.util.*;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.stats.LootFacts;
import tomato.realmshark.EnchantInfo;

/**
 * Every saved drop of one item, newest first, as Highlights' drop cards ({@link HighlightsModel.Notable}: bag, area, time, run,
 * enchantments, tier); {@code front} is the drop drawn in the header (the best enchant rarity, then the newest), null without drops.
 * Pure.
 */
public record ItemHistory(int itemId, List<HighlightsModel.Notable> drops, LootFacts.Item front) {
    public ItemHistory { drops = List.copyOf(drops); }

    public static ItemHistory of(int itemId, List<LootFacts.Bag> bags) {
        List<HighlightsModel.Notable> drops = new ArrayList<>();
        LootFacts.Item front = null;
        long frontTime = 0;
        for (LootFacts.Bag bag : bags) for (LootFacts.Item item : bag.items()) {
            if (item.id() != itemId) continue;
            drops.add(new HighlightsModel.Notable(itemId, bag.bag(), bag.dungeon(), bag.time(), bag.visit(), HighlightsModel.kind(item),
                item.enchant(), item.tier()));
            int rarity = HaulModel.rarity(item), best = front == null ? Integer.MIN_VALUE : HaulModel.rarity(front);
            if (front == null || rarity > best || rarity == best && bag.time() > frontTime) { front = item; frontTime = bag.time(); }
        }
        drops.sort(Comparator.comparingLong(HighlightsModel.Notable::time).reversed());
        return new ItemHistory(itemId, drops, front);
    }

    public int count() { return drops.size(); }

    /** "12 drops · 3 Rare · 1 Legendary": the enchanted drops by rarity, rarest last; drops without enchant data count only as drops. */
    public String summary() {
        Map<EnchantInfo.Rarity, Integer> byRarity = new EnumMap<>(EnchantInfo.Rarity.class);
        for (HighlightsModel.Notable drop : drops) if (drop.enchant().enchanted()) byRarity.merge(drop.enchant().rarity(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        parts.add(drops.size() + (drops.size() == 1 ? " drop" : " drops"));
        for (EnchantInfo.Rarity rarity : List.of(EnchantInfo.Rarity.UNCOMMON, EnchantInfo.Rarity.RARE, EnchantInfo.Rarity.LEGENDARY, EnchantInfo.Rarity.DIVINE)) {
            Integer count = byRarity.get(rarity);
            if (count != null) parts.add(count + " " + rarity.label);
        }
        return String.join(" · ", parts);
    }
}
