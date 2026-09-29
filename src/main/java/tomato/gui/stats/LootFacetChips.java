package tomato.gui.stats;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tomato.gui.history.ArchiveFilters;
import tomato.gui.kit.FilterBar;
import tomato.gui.stats.LootQuery.*;

/** Removable chips for loot facets, shared by the saved Loot drawer and the live Loot explorer. */
final class LootFacetChips {
    private LootFacetChips() {}

    /**
     * One chip per narrowing facet. current must return a fresh copy; removing a chip resets only that facet on the
     * facets current at click time, so the view, cohorts and every other facet are kept.
     */
    static List<FilterBar.ActiveFilter> chips(Supplier<Facets> current, Consumer<Facets> changed) {
        Facets f = current.get(); List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        if (!f.bags.isEmpty()) chips.add(chip("Bags: " + ArchiveFilters.summary(f.bags), current, changed, n -> n.bags = new LinkedHashSet<>()));
        if (!f.dungeons.isEmpty()) chips.add(chip("Dungeons: " + ArchiveFilters.summary(f.dungeons), current, changed, n -> n.dungeons = new LinkedHashSet<>()));
        if (f.kind != null && f.kind != Kind.ANY) chips.add(chip(LootFacetControls.kind(f.kind), current, changed, n -> n.kind = Kind.ANY));
        if (!f.rarities.isEmpty()) chips.add(chip("Rarity: " + ArchiveFilters.summary(f.rarities), current, changed, n -> n.rarities = new LinkedHashSet<>()));
        if (!f.tiers.isEmpty()) chips.add(chip("Tier: " + ArchiveFilters.summary(f.tiers), current, changed, n -> n.tiers = new LinkedHashSet<>()));
        if (narrows(f.slots)) chips.add(chip("Slots " + range(f.slots), current, changed, n -> n.slots = new Range()));
        if (narrows(f.applied)) chips.add(chip("Applied enchants " + range(f.applied), current, changed, n -> n.applied = new Range()));
        if (!f.character.isEmpty()) chips.add(chip("Character ID " + f.character, current, changed, n -> n.character = ""));
        if (!f.enemy.isEmpty()) chips.add(chip("Enemy ID " + f.enemy, current, changed, n -> n.enemy = ""));
        if (f.drilled()) chips.add(chip(f.variant != null && f.visitSession != null ? "Exact variant and run" : f.variant != null ? "Exact variant" : "Exact run",
            current, changed, n -> { n.variant = null; n.visitSession = null; n.visitId = null; }));
        return chips;
    }

    private static FilterBar.ActiveFilter chip(String label, Supplier<Facets> current, Consumer<Facets> changed, Consumer<Facets> reset) {
        return new FilterBar.ActiveFilter(label, () -> { Facets next = current.get(); reset.accept(next); changed.accept(next); });
    }

    private static boolean narrows(Range r) { return r != null && (r.min != null || r.max != null || r.unknown != Unknown.INCLUDE); }

    /** "2–4", "≥ 2", "≤ 4" or "any", plus the unknown-value policy when it is not the default. */
    static String range(Range r) {
        String bounds = r.min != null && r.max != null ? r.min + "–" + r.max : r.min != null ? "≥ " + r.min : r.max != null ? "≤ " + r.max : "any";
        return bounds + (r.unknown == Unknown.EXCLUDE ? ", unknown excluded" : r.unknown == Unknown.ONLY ? " (unknown only)" : "");
    }
}
