package tomato.gui.stats;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class LootLiveFilterBarTest {
    @Test public void liveLootFacetsLiveInTheDrawerWithChips() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootDashboard dashboard = new LootDashboard();
            FilterBar bar = find(dashboard, FilterBar.class, "loot-live-filter-bar");
            assertTrue(SwingUtilities.isDescendingFrom(find(dashboard, JComboBox.class, "loot-bag-filter"), bar.drawerContent()));
            JTextField search = find(dashboard, JTextField.class, "loot-search");
            assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, bar.drawerContent()));
            LootQuery.Facets f = new LootQuery.Facets(); f.kind = LootQuery.Kind.STAT_POTION; f.tiers.add("UT"); dashboard.applyFacets(f);
            assertEquals(Arrays.asList("Stat potions", "Tier: UT"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Stat potions");
            assertEquals(Collections.singletonList("Tier: UT"), ArchiveNativeSupport.chipLabels(bar));
            find(dashboard, AbstractButton.class, "loot-live-clear-filters").doClick();
            assertEquals(0, bar.activeCount());
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
