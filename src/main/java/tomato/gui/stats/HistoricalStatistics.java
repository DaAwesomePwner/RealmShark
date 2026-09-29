package tomato.gui.stats;

import tomato.history.SessionStore;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.SessionPanel;
import tomato.gui.history.ViewStateStore;

/**
 * The saved Loot and Statistics workspaces: each pairs its live view with worker-built archive projections of durable
 * sessions. Dungeon loot rates and their coverage rules live in {@link StatisticsArchiveAdapter} and {@link LootProfile}.
 */
public final class HistoricalStatistics {
    private HistoricalStatistics(){}
    /** Coordinator shell hook: independent persisted Loot and Statistics scope/state keys. */
    public static ArchiveWorkspace<LootQuery.Row,LootQuery.Facets,LootQuery.Sort> lootWorkspace(
            SessionStore store,LootDashboard live,java.nio.file.Path scratch,ViewStateStore states){
        live.bindViewState(states,"loot-live");
        return SessionPanel.queried(store,"loot",live,new LootArchiveClient(scratch,false),states);
    }
    public static ArchiveWorkspace<LootQuery.Row,LootQuery.Facets,LootQuery.Sort> statisticsWorkspace(
            SessionStore store,StatisticsGUI live,java.nio.file.Path scratch,ViewStateStore states){
        live.bindViewState(states);
        return SessionPanel.queried(store,"statistics",live,new LootArchiveClient(scratch,true),states);
    }
}
