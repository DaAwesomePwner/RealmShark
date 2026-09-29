package tomato.gui.stats;

import tomato.history.SessionStore;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.SessionPanel;
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.DisplayModeModel;

/**
 * The saved Loot and Statistics workspaces: each pairs its live view with worker-built archive projections of durable
 * sessions. Dungeon loot rates and their coverage rules live in {@link StatisticsArchiveAdapter} and {@link LootProfile}.
 */
public final class HistoricalStatistics {
    private HistoricalStatistics(){}
    /**
     * Loot › Explore (coordinator shell hook): {@code live} and saved history with one view selector each over the same views
     * ({@link LootExploreModel}: nine Simple item views, six Analyst saved-only views), sharing the chosen view. Independent
     * persisted state keys: {@code loot-live} (the live index under {@code loot-views}) and {@code loot} ({@code facets.view}).
     */
    public static ArchiveWorkspace<LootQuery.Row,LootQuery.Facets,LootQuery.Sort> lootWorkspace(
            SessionStore store,LootDashboard live,java.nio.file.Path scratch,ViewStateStore states){
        live.bindViewState(states,"loot-live");
        LootExploreModel explore=new LootExploreModel(DisplayModeModel.application());
        ArchiveWorkspace<LootQuery.Row,LootQuery.Facets,LootQuery.Sort> workspace=SessionPanel.queried(store,"loot",live,
            new LootArchiveClient(scratch,LootExploreModel.views(),LootExploreModel.initialQuery(),explore),states);
        explore.attach(live,workspace);
        return workspace;
    }
    public static ArchiveWorkspace<LootQuery.Row,LootQuery.Facets,LootQuery.Sort> statisticsWorkspace(
            SessionStore store,StatisticsGUI live,java.nio.file.Path scratch,ViewStateStore states){
        live.bindViewState(states);
        return SessionPanel.queried(store,"statistics",live,new LootArchiveClient(scratch,true),states);
    }
}
