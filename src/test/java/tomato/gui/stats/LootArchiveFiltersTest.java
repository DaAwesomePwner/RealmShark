package tomato.gui.stats;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
import tomato.history.SessionStore;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/** Saved loot facets and dates live in the drawer; drill-down and cohort inputs stay in the view. */
public class LootArchiveFiltersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void lootFacetsMoveToTheDrawerAndBecomeChips() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore source = new SessionStore(root, true, "loot-filters")) {
            for (int row = 0; row < 4; row++) source.append("loot", new LootDashboard.Drop(row < 3 ? "White" : "Orange", "Lost Halls", "Synthetic boss", 2000 + row * 1000L,
                Collections.singletonList(new LootDashboard.Item(910000 + row, "Needle blade " + row, "EQUIPMENT,WEAPON,UT", ParseEnchants.summarize(""))), "visit-1"));
            source.flush();
        }
        try (SessionStore store = new SessionStore(root, true, "reader")) {
            ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> workspace = edt(() -> HistoricalStatistics.lootWorkspace(store, new LootDashboard(), scratch, memory.states));
            try {
                edt(() -> { workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 4);
                edt(() -> {
                    FilterBar bar = workspace.filterBar(); JComponent drawer = bar.drawerContent();
                    assertNotNull(drawer); assertNotNull(named(drawer, "loot-date-from", JTextField.class));
                    assertTrue(SwingUtilities.isDescendingFrom(named(workspace, "loot-apply-facets", JButton.class), drawer));
                    assertEquals("Dates are no longer repeated in the view", 1, count(workspace, "loot-date-from"));
                    LootQuery.Facets f = workspace.state().query.facets(); f.bags.add("White"); f.kind = LootQuery.Kind.UT_EQUIPMENT;
                    workspace.changeQuery(workspace.state().query.withFacets(f)); return null; });
                await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 3);
                edt(() -> { assertEquals(Arrays.asList("Bags: White", "UT equipment"), ArchiveNativeSupport.chipLabels(workspace.filterBar()));
                    ArchiveNativeSupport.removeChip(workspace.filterBar(), "Bags: White"); return null; });
                await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 4);
                edt(() -> { LootQuery.Facets f = workspace.state().query.facets(); assertTrue(f.bags.isEmpty()); assertEquals(LootQuery.Kind.UT_EQUIPMENT, f.kind);
                    assertEquals(LootQuery.View.OCCURRENCES, f.view); return null; });
            } finally { edt(() -> { workspace.close(); return null; }); }
        }
    }

    /** Components named {@code name} anywhere under {@code root} (the drawer's and any the saved view repeats). */
    private static int count(Container root, String name) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) count++;
            if (child instanceof Container) count += count((Container) child, name);
        }
        return count;
    }
}
