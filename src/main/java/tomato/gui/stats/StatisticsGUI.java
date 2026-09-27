package tomato.gui.stats;

import java.awt.*;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.CustomizableTabs;

public class StatisticsGUI extends JPanel {
    private final LootGUI loot;
    private final CustomizableTabs tabs = new CustomizableTabs("statistics");
    private final JTabbedPane tabbedPane = tabs.component();
    private final FameTrackerGUI fameTracker;
    private final FameTablePanel fameTable;

    public LootDashboard getLootDashboard() { return loot.getDashboard(); }

    public StatisticsGUI(TomatoData data) {
        setLayout(new BorderLayout(0, 10));
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.setName("statistics-tabs");
        add(tabbedPane);

        fameTracker = new FameTrackerGUI();
        JComponent fameGraph = StatsUi.page(fameTracker, 510);

        fameTable = new FameTablePanel(data);
        JComponent famePage = StatsUi.page(fameTable, 510);

        // Initialize and connect the fame table bridge
        FameTableBridge.initialize();
        FameTableBridge bridge = FameTableBridge.getInstance();
        bridge.setFameTablePanel(fameTable);
        bridge.setFameTrackerGUI(fameTracker);

        loot = new LootGUI(data);
        JComponent lootPage = StatsUi.page(loot, 570);
        DungeonStats dungeonStats = new DungeonStats(true);
        JComponent dungeonPage = StatsUi.page(dungeonStats, 510);
        tabs.add("fame-graph", "Fame Graph", fameGraph).add("fame-table", "Fame Table", famePage)
            .add("loot", "Loot", lootPage).add("dungeon-stats", "Dungeon Stats", dungeonPage);
        Map<Component, String> tips = new HashMap<>();
        tips.put(fameGraph, "Character and time-range filters with interval comparison");
        tips.put(famePage, "Session totals and map visit breakdowns");
        tips.put(lootPage, "Shared session loot summaries and original drop log");
        tips.put(dungeonPage, "Current app session's dungeon, enemy and item counters");
        tabTips(tabbedPane, tips);
    }
    public void bindViewState(tomato.gui.history.ViewStateStore store){
        new StatisticsLiveState(store,"statistics-live").attach(this).tabs(tabs,"fame-graph","fame-table","loot","dungeon-stats");
        fameTracker.bindViewState(store);fameTable.bindViewState(store);
        loot.getDashboard().bindSiblingViewState(store,"statistics-live-loot");
    }

    /** CustomizableTabs re-adds pages when they move, hide or the display mode changes; each tooltip follows its page. */
    private static void tabTips(JTabbedPane pane, Map<Component, String> tips) {
        Runnable apply = () -> {
            for (int i = 0; i < pane.getTabCount(); i++) { String tip = tips.get(pane.getComponentAt(i)); if (tip != null) pane.setToolTipTextAt(i, tip); }
        };
        pane.addContainerListener(new ContainerAdapter() { @Override public void componentAdded(ContainerEvent e) { apply.run(); } });
        apply.run();
    }
}
