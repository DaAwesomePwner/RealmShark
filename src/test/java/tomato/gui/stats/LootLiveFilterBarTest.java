package tomato.gui.stats;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.LiveFilterHost;
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

    /**
     * P6b: the live dashboard hosts its workspace's Scope chip in {@code loot-live} (a {@link LiveFilterHost}), and that row is the
     * page's first, above the tiles, so Scope is in the first row live and saved (R1 D4).
     */
    @Test public void theLiveBarHostsTheScopeChipAboveTheTiles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootDashboard dashboard = new LootDashboard();
            Object host = dashboard;
            assertTrue("The live dashboard is a Scope host", host instanceof LiveFilterHost);
            FilterBar bar = find(dashboard, FilterBar.class, "loot-live-filter-bar");
            assertSame(bar, ((LiveFilterHost) host).liveFilterBar());
            assertTrue("A panel search slot, for the chip's narrow fallback and the lead", bar.searchSlot() instanceof JPanel);
            JFrame frame = new JFrame("Loot live bar fixture");
            try {
                frame.setContentPane(dashboard); frame.pack(); frame.setSize(1240, 800); frame.validate();
                JComponent tiles = find(dashboard, JComponent.class, "loot-metrics");
                int barTop = SwingUtilities.convertPoint(bar, 0, 0, dashboard).y, tilesTop = SwingUtilities.convertPoint(tiles, 0, 0, dashboard).y;
                assertTrue("The filter row is above the tiles: " + barTop + " < " + tilesTop, barTop < tilesTop);
                for (Component other : new Component[]{tiles, find(dashboard, JPanel.class, "loot-view-cards")})
                    assertTrue("The filter row is the first row", barTop <= SwingUtilities.convertPoint(other, 0, 0, dashboard).y);
                assertEquals("Nothing above the filter row", 0, barTop);
            } finally { frame.dispose(); }
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
