package tomato.gui.stats;

import java.awt.Window;
import java.util.Arrays;
import java.util.Collections;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.SessionPanel;
import tomato.history.SessionStore;
import tomato.realmshark.ParseEnchants;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;

public class LootLayoutEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence();
    @Rule public ErrorCollector layouts = new ErrorCollector();
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private SessionStore store;
    private SessionPanel route;

    @After public void closeHistory() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (route != null) {
                Window window = SwingUtilities.getWindowAncestor(route);
                if (window != null) window.dispose();
            }
        });
        if (store != null) store.close();
    }

    @Test public void settledCompactLootRetainsTheEntireGlobalRecencyFooterAndUsefulRows() throws Exception {
        store = new SessionStore(temp.newFolder().toPath(), true, "synthetic-layout");
        LootDashboard[] panel = new LootDashboard[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new LootDashboard();
            panel[0].acceptAll(Arrays.asList(
                new LootDashboard.Drop("White", "Lost Halls", "Synthetic boss", 1000,
                    Collections.singletonList(new LootDashboard.Item(910001, "Synthetic blade", "EQUIPMENT,WEAPON,UT", ParseEnchants.summarize("")))),
                new LootDashboard.Drop("Orange", "The Shatters", "Synthetic boss", 2000,
                    Collections.singletonList(new LootDashboard.Item(910002, "Synthetic ring", "EQUIPMENT,RING,UT", ParseEnchants.summarize(null))))));
            named(panel[0], "loot-views", JTabbedPane.class).setSelectedIndex(6);
            // The live Loot workspace supplies this scrolling route; a bare dashboard omits its minimum-height contract.
            route = new SessionPanel(store, "loot", panel[0], HistoricalStatistics::loot);
        });
        for (int font : new int[]{13, 24}) for (int width : new int[]{1050, 640}) {
            SwingUtilities.invokeAndWait(() -> evidence.show(route, "Loot scope evidence", width, 700, font));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                String name = "loot-" + width + "-" + font; evidence.capture(name);
                JTextArea scope = find(panel[0], JTextArea.class, a -> a.getText().contains("Recent Drops searches"));
                assertTrue(scope.getText().contains("globally newest"));
                assertTrue(scope.getText().endsWith("the full app session."));
                assertArrayEquals(new int[]{2, 2}, panel[0].sessionTotals());
                layouts.checkSucceeds(() -> { completeText(scope); return null; });
                evidence.capture(name + "-scope-end");
                layouts.checkSucceeds(() -> {
                    completeText(named(panel[0], "loot-enchant-totals", JTextArea.class));
                    reachable(named(panel[0], "loot-search", JTextField.class));
                    completeButton(button(panel[0], "Reset filters"));
                    JTable table = named(panel[0], "loot-view-6", JTable.class);
                    assertEquals(2, table.getRowCount());
                    System.out.println("Loot viewport=" + table.getParent().getSize() + ", row height=" + table.getRowHeight());
                    assertTrue("Three usable loot rows: viewport=" + table.getParent().getSize() + ", row height=" + table.getRowHeight(),
                        table.getParent().getHeight() >= table.getRowHeight() * 3);
                    reachable(table, table.getCellRect(0, 1, true));
                    return null;
                });
            });
        }
    }
}
