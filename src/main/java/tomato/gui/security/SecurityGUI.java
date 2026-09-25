package tomato.gui.security;

import tomato.gui.TomatoGUI;
import tomato.gui.modern.ContentStyle;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;

import javax.swing.*;
import java.awt.*;

public class SecurityGUI extends JPanel {
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

    private static volatile SecurityGUI INSTANCE;

    private JTextArea text;
    private final StringBuilder pending = new StringBuilder();
    private boolean appendScheduled;
    private final JTabbedPane tabbedPane=new JTabbedPane();
    private InspectRunsPanel runs;
    private RosterViewState liveState;
    private final JPanel stateHost=new JPanel(new BorderLayout());

    public SecurityGUI() {
        this(packets.packetcapture.logger.DiscoveryLog.INSTANCE);
        bindViewState(ViewStateStore.application());
    }

    SecurityGUI(packets.packetcapture.logger.DiscoveryLog log) {
        setLayout(new BorderLayout(8, 8));

        tabbedPane.setName("inspect-tabs");
        ParsePanelGUI parsePanel = new ParsePanelGUI();
        JPanel currentArea = new JPanel(new BorderLayout());
        currentArea.add(parsePanel);
        runs = new InspectRunsPanel(log, parsePanel);

        JPanel abilityUse = new JPanel();
        tabbedPane.addTab("Current Area", currentArea);
        tabbedPane.addTab("Runs", runs);
        tabbedPane.addTab("Ability Use", abilityUse);
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedComponent() != runs) runs.releaseRoster();
            if (tabbedPane.getSelectedComponent() == currentArea) {
                parsePanel.showCurrentArea();
                currentArea.add(parsePanel);
                currentArea.revalidate();
            } else if (tabbedPane.getSelectedComponent() == runs) runs.showRoster();
            if(liveState!=null)liveState.changed();
        });
        // Container-state actions share the page's scroll fallback instead of taking
        // a fixed slice from the roster viewport in a compact workspace.
        add(ContentStyle.page(null, tabbedPane, stateHost));

        abilityUse.setLayout(new BorderLayout());
        text = new tomato.gui.modern.EmptyLogArea("No ability activity yet", "Ability usage will appear here during capture.");
        text.setFont(ContentStyle.body());
        text.getAccessibleContext().setAccessibleName("Ability usage log");
        JButton button = new JButton("Clear ability log");
        button.addActionListener(e -> {
            synchronized (pending) { pending.setLength(0); }
            text.setText("");
        });
        abilityUse.add(TomatoGUI.createTextArea(text, true), BorderLayout.CENTER);
        JPanel controls = ContentStyle.controls();
        controls.add(button);
        abilityUse.add(controls, BorderLayout.SOUTH);
        ContentStyle.refreshFonts(this);
        INSTANCE = this;
    }

    /** The container's tab/run-list state is independent of ParsePanelGUI's roster facets and notes. */
    public void bindViewState(ViewStateStore store){
        if(liveState!=null)return;runs.bindViewState(store);
        liveState=new RosterViewState(store,"inspect-live-container",()->{
            java.util.Map<String,String> values=new java.util.LinkedHashMap<>();values.put("tab",new String[]{"area","runs","ability"}[tabbedPane.getSelectedIndex()]);return values;
        },values->{int tab=RosterViewState.option(values,"tab",0,"area","runs","ability");return ()->tabbedPane.setSelectedIndex(tab);});
        stateHost.add(liveState.controls());
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState(){
        if(liveState==null)throw new IllegalStateException("Inspect container state is not bound");
        return runs.saveViewState().thenCombine(liveState.save(),(run,container)->run.isSuccess()?container:run);
    }
    @Override public void removeNotify(){if(liveState!=null)saveViewState();super.removeNotify();}

    public static void updateAbilityUsage(String s) {
        SecurityGUI panel = INSTANCE;
        if (panel != null) panel.appendText(s);
    }

    private void appendText(String s) {
        synchronized (pending) {
            pending.append(s).append('\n');
            if (appendScheduled) return;
            appendScheduled = true;
        }
        SwingUtilities.invokeLater(() -> {
            String batch;
            synchronized (pending) {
                batch = pending.toString();
                pending.setLength(0);
                appendScheduled = false;
            }
            text.append(batch);
        });
    }
}
