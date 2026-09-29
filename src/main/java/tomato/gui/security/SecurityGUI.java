package tomato.gui.security;

import tomato.gui.modern.ContentStyle;
import tomato.gui.history.LiveFilterHost;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;
import tomato.gui.kit.Banner;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.Tokens;

import javax.swing.*;
import java.awt.*;

/**
 * Party: Current Area, Runs and (Analyst) Ability Use. Each tab's own filter row lends its workspace the Scope chip while live
 * ({@link LiveFilterHost}): the roster's on Current Area, the runs table's on Runs and the evidence row's on Ability Use.
 */
public class SecurityGUI extends JPanel implements LiveFilterHost {
    public static JComponent workspace(SecurityGUI live) {
        tomato.history.SessionStore store=tomato.history.AppHistory.store();
        return store==null?live:workspace(store,live,java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),"realmshark-inspect-archive"),tomato.gui.history.ViewStateStore.application());
    }
    public static tomato.gui.history.ArchiveWorkspace<tomato.gui.activity.ActivityQueries.Row,tomato.gui.activity.ActivityQueries.Filters,tomato.gui.activity.ActivityQueries.Sort> workspace(
            tomato.history.SessionStore store,JComponent live,java.nio.file.Path scratch,tomato.gui.history.ViewStateStore states) {
        if(live instanceof SecurityGUI)((SecurityGUI)live).bindViewState(states);
        return tomato.gui.history.SessionPanel.queried(store,"inspect",live,InspectRunsPanel.archiveClient(scratch),states);
    }
    public static tomato.gui.history.SessionPanel.Loaded history(tomato.history.SessionStore store, String scope, int page, String query) throws java.io.IOException {
        packets.packetcapture.logger.ActivityJournal.State state = new packets.packetcapture.logger.ActivityJournal.State();
        tomato.gui.history.HistoryPage<packets.packetcapture.logger.ActivityJournal.Visit> visits = tomato.gui.history.HistoryPage.read(store,scope,"runs",packets.packetcapture.logger.ActivityJournal.Visit.class,page,100,query,
                visit -> visit.map + " " + String.join(" ",visit.inspectedPlayers.keySet()),visit -> tomato.realmshark.ParseDungeon.isDungeon(visit.map));
        for (packets.packetcapture.logger.ActivityJournal.Visit visit : visits.values) {
            visit.normalizePlayers(); state.visits.add(visit);
        }
        state.visits.sort(java.util.Comparator.comparingLong(v -> v.started));
        return new tomato.gui.history.SessionPanel.Loaded(() -> {
            ParsePanelGUI roster = new ParsePanelGUI(false);
            InspectRunsPanel view = new InspectRunsPanel(packets.packetcapture.logger.DiscoveryLog.historyView(state), roster);
            view.readOnly();view.showRoster();return view;
        }, visits.more(), visits.description());
    }

    private final CustomizableTabs tabs=new CustomizableTabs("inspect");
    private final JTabbedPane tabbedPane=tabs.component();
    private static final String[] TAB_IDS={"area","runs","ability"};
    private boolean tabSyncPending;
    private final ParsePanelGUI parsePanel;
    private final InspectRunsPanel runs;
    private final AbilityEvidencePanel abilityUse;
    private final JPanel currentArea = new JPanel(new BorderLayout());
    /** The filter row of the finally selected tab: the Scope chip's host while live. */
    private FilterBar shownBar;
    private RosterViewState liveState;
    private final JPanel stateHost=new JPanel(new BorderLayout());
    private final Banner stateBanner=new Banner("inspect-live-container-view-state");

    public SecurityGUI() {
        this(packets.packetcapture.logger.DiscoveryLog.INSTANCE);
        bindViewState(ViewStateStore.application());
    }

    SecurityGUI(packets.packetcapture.logger.DiscoveryLog log) {
        setLayout(new BorderLayout(8, 8));

        tabbedPane.setName("inspect-tabs");
        parsePanel = new ParsePanelGUI();
        currentArea.add(parsePanel);
        runs = new InspectRunsPanel(log, parsePanel);

        abilityUse = new AbilityEvidencePanel(tomato.ability.AbilityObservationStore.application());
        // Ability Use is diagnostic evidence: Analyst mode only (spec §3.2, §4.2).
        tabs.add("area", "Current Area", currentArea).add("runs", "Runs", runs).addAnalyst("ability", "Ability Use", abilityUse);
        Runnable tabChanged = () -> {
            if (tabbedPane.getSelectedComponent() != runs) runs.releaseRoster();
            if (tabbedPane.getSelectedComponent() == currentArea) {
                parsePanel.showCurrentArea();
                currentArea.add(parsePanel);
                currentArea.revalidate();
            } else if (tabbedPane.getSelectedComponent() == runs) runs.showRoster();
            if(liveState!=null)liveState.changed();
            showBar();
        };
        tabbedPane.addChangeListener(e -> {
            // A rebuild (mode switch, reorder, hide) passes through transient selections; act once on the final one.
            if (!tabs.isRebuilding()) { tabChanged.run(); return; }
            if (tabSyncPending) return;
            tabSyncPending = true; SwingUtilities.invokeLater(() -> { tabSyncPending = false; tabChanged.run(); });
        });
        // The container's saved state is in the tabs' ⋯ (bindViewState). Its status line, only while it is a failure, shares the
        // page's scroll fallback instead of taking a fixed slice from the roster viewport in a compact workspace.
        stateBanner.setTone(Tokens.Tone.WARN); stateBanner.setVisible(false); stateHost.add(stateBanner); stateHost.setVisible(false);
        add(ContentStyle.page(null, tabbedPane, stateHost));
        shownBar = barOf(tabbedPane.getSelectedComponent());

        ContentStyle.refreshFonts(this);
    }

    @Override public FilterBar liveFilterBar() { return shownBar; }
    private FilterBar barOf(Component tab) { return tab == runs ? runs.filterBar() : tab == abilityUse ? abilityUse.filterBar() : parsePanel.filterBar(); }
    /** Fires {@link #BAR} when the final selection shows another row; tabChanged never runs for a rebuild's transient selections. */
    private void showBar() {
        FilterBar next = barOf(tabbedPane.getSelectedComponent()), old = shownBar;
        if (next == old) return;
        shownBar = next;
        firePropertyChange(BAR, old, next);
    }

    /** The container's tab/run-list state is independent of ParsePanelGUI's roster facets and notes. */
    public void bindViewState(ViewStateStore store){
        if(liveState!=null)return;runs.bindViewState(store);
        liveState=new RosterViewState(store,"inspect-live-container",()->{
            java.util.Map<String,String> values=new java.util.LinkedHashMap<>();values.put("tab",tabs.selectedId()==null?"area":tabs.selectedId());return values;
        },values->{int tab=RosterViewState.option(values,"tab",0,"area","runs","ability");return ()->{if(values.containsKey("tab"))tabs.select(TAB_IDS[tab]);};});
        liveState.onStatus(this::viewStateChanged); viewStateChanged();
        // The three state rows are ⋯ items now (spec §3.2). Each tab's ⋯ saves and resets that tab's view with the page's tab:
        // Current Area's roster items also cover the tab, Runs' cover the runs and the tab, and Ability Use's the tab.
        parsePanel.alsoOnViewState(() -> liveState.save(), liveState::resetSaved);
        if (runs.viewStateBound()) {
            OverflowMenu more = runs.filterBar().overflow(); more.addSeparator();
            more.add("Save view state", this::saveViewState).setName("inspect-live-runs-save-state");
            more.add("Reset saved view state", () -> { runs.resetViewState(); liveState.resetSaved(); }).setName("inspect-live-runs-reset-state");
        }
        JPopupMenu ability = abilityUse.filterBar().overflow().menu();
        JMenuItem save = new JMenuItem("Save view state"), reset = new JMenuItem("Reset saved view state");
        save.setName("inspect-live-container-save-state"); save.addActionListener(e -> liveState.save());
        reset.setName("inspect-live-container-reset-state"); reset.addActionListener(e -> liveState.resetSaved());
        ability.insert(save, 0); ability.insert(reset, 1); ability.insert(new JPopupMenu.Separator(), 2);
    }
    /** The container state's status as a warning line while it is a failure. */
    private void viewStateChanged() {
        boolean problem = liveState.statusProblem();
        stateBanner.setText(problem ? liveState.statusText() : ""); stateBanner.setVisible(problem);
        if (stateHost.isVisible() != problem) { stateHost.setVisible(problem); revalidate(); }
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState(){
        if(liveState==null)throw new IllegalStateException("Inspect container state is not bound");
        return runs.saveViewState().thenCombine(liveState.save(),(run,container)->run.isSuccess()?container:run);
    }
    @Override public void removeNotify(){if(liveState!=null)saveViewState();super.removeNotify();}

    public static void updateAbilityUsage(String s) {
        // Legacy callers lack structured provenance; never reverse-parse UI strings.
        tomato.ability.AbilityObservationStore.application().omit();
    }

}
