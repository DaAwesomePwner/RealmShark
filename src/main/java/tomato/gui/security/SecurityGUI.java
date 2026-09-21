package tomato.gui.security;

import tomato.gui.TomatoGUI;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;

public class SecurityGUI extends JPanel {
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

    public SecurityGUI() {
        this(packets.packetcapture.logger.DiscoveryLog.INSTANCE);
    }

    SecurityGUI(packets.packetcapture.logger.DiscoveryLog log) {
        setLayout(new BorderLayout(8, 8));

        JTabbedPane tabbedPane = new JTabbedPane();
        ParsePanelGUI parsePanel = new ParsePanelGUI();
        JPanel currentArea = new JPanel(new BorderLayout());
        currentArea.add(parsePanel);
        InspectRunsPanel runs = new InspectRunsPanel(log, parsePanel);

        JPanel abilityUse = new JPanel();
        tabbedPane.addTab("Current Area", currentArea);
        tabbedPane.addTab("Runs", runs);
        tabbedPane.addTab("Ability Use", abilityUse);
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedComponent() == currentArea) {
                parsePanel.showCurrentArea();
                currentArea.add(parsePanel);
                currentArea.revalidate();
            } else if (tabbedPane.getSelectedComponent() == runs) runs.showRoster();
        });
        add(tabbedPane);

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
