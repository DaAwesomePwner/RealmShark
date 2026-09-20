package tomato.gui.security;

import tomato.gui.TomatoGUI;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;

public class SecurityGUI extends JPanel {

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
