package tomato.gui.stats;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tomato.gui.kit.FilterBar;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

public class LootFacetChipsTest {
    @Test public void narrowingFacetsBecomeChipsAndRemovalResetsOnlyThatFacet() {
        LootQuery.Facets f = new LootQuery.Facets(); f.view = LootQuery.View.OCCURRENCES;
        f.bags.addAll(Arrays.asList("White", "Orange", "Blue")); f.kind = LootQuery.Kind.UT_EQUIPMENT;
        f.slots.min = 2; f.applied.unknown = LootQuery.Unknown.EXCLUDE; f.variant = "910000/2/1";
        AtomicReference<LootQuery.Facets> applied = new AtomicReference<>();
        List<FilterBar.ActiveFilter> chips = LootFacetChips.chips(() -> copy(f), applied::set);
        List<String> labels = new ArrayList<>(); for (FilterBar.ActiveFilter chip : chips) labels.add(chip.label);
        assertEquals(Arrays.asList("Bags: White, Orange +1", "UT equipment", "Slots ≥ 2", "Applied enchants any, unknown excluded", "Exact variant"), labels);
        chips.get(0).remove.run();
        assertTrue(applied.get().bags.isEmpty()); assertEquals(LootQuery.Kind.UT_EQUIPMENT, applied.get().kind);
        assertEquals("910000/2/1", applied.get().variant); assertEquals(LootQuery.View.OCCURRENCES, applied.get().view);
        chips.get(4).remove.run();
        assertNull(applied.get().variant); assertEquals(3, applied.get().bags.size());
        assertTrue(LootFacetChips.chips(LootQuery.Facets::new, applied::set).isEmpty());
    }

    private static LootQuery.Facets copy(LootQuery.Facets f) { return SessionStore.JSON.fromJson(SessionStore.JSON.toJson(f), LootQuery.Facets.class); }
}
