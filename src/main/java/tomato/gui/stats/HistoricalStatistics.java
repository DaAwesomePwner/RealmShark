package tomato.gui.stats;

import tomato.history.SessionStore;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.SessionPanel;
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.DisplayModeModel;

/**
 * The saved Loot workspace (Loot › Explore): the live loot view beside worker-built archive projections of durable sessions.
 * Dungeon loot rates and their coverage rules live in {@link StatisticsArchiveAdapter} and {@link LootProfile}. The Statistics
 * workspace this class also built left with the Statistics page (P6a); its saved view states are orphaned, never read.
 */
public final class HistoricalStatistics {
    private HistoricalStatistics(){}
    /**
     * Loot › Explore (coordinator shell hook): {@code live} and saved history behind one view selector, the live dashboard's
     * ({@link LootExploreModel}: nine Simple item views, six Analyst saved-only views), which leads the filter row the page shows;
     * {@code live}'s row hosts the Scope chip while live. Independent persisted state keys: {@code loot-live} (the live index under
     * {@code loot-views}) and {@code loot} ({@code facets.view}).
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
}
