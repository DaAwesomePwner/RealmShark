package tomato.gui.stats;

import java.awt.*;
import javax.swing.*;
import tomato.backend.data.TomatoData;

public class StatisticsGUI extends JPanel {
    private final LootGUI loot;
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final FameTrackerGUI fameTracker;
    private final FameTablePanel fameTable;

    public LootDashboard getLootDashboard() { return loot.getDashboard(); }

    public StatisticsGUI(TomatoData data) {
        setLayout(new BorderLayout(0, 10));
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.setName("statistics-tabs");
        add(tabbedPane);

        fameTracker = new FameTrackerGUI();
        tabbedPane.addTab("Fame Graph", StatsUi.page(fameTracker, 510));

        fameTable = new FameTablePanel(data);
        tabbedPane.addTab("Fame Table", StatsUi.page(fameTable, 510));

        // Initialize and connect the fame table bridge
        FameTableBridge.initialize();
        FameTableBridge bridge = FameTableBridge.getInstance();
        bridge.setFameTablePanel(fameTable);
        bridge.setFameTrackerGUI(fameTracker);

        loot = new LootGUI(data);
        tabbedPane.addTab("Loot", StatsUi.page(loot, 570));
        DungeonStats dungeonStats = new DungeonStats(true);
        tabbedPane.addTab("Dungeon Stats", StatsUi.page(dungeonStats, 510));
        tabbedPane.setToolTipTextAt(0, "Character and time-range filters with interval comparison");
        tabbedPane.setToolTipTextAt(1, "Session totals and map visit breakdowns");
        tabbedPane.setToolTipTextAt(2, "Shared session loot summaries and original drop log");
        tabbedPane.setToolTipTextAt(3, "Current app session's dungeon, enemy and item counters");
    }
    public void bindViewState(tomato.gui.history.ViewStateStore store){
        new StatisticsLiveState(store,"statistics-live").attach(this).tabs(tabbedPane);
        fameTracker.bindViewState(store);fameTable.bindViewState(store);
        loot.getDashboard().bindSiblingViewState(store,"statistics-live-loot");
    }
}
