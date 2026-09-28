package tomato.gui.dps;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.util.Objects;
import javax.swing.JPanel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.Tokens;

/**
 * Page 7 after P5b: the live meter and the recordings are tabs of Runs & DPS, so the DPS Logger page only points there
 * (the {@code BuildMovedPanel} pattern). Both destinations always exist, so both buttons stay enabled.
 */
public final class DpsMovedPanel extends JPanel {
    public DpsMovedPanel(Runnable openLiveMeter, Runnable openRecordings) {
        super(new BorderLayout());
        Objects.requireNonNull(openLiveMeter, "openLiveMeter");
        Objects.requireNonNull(openRecordings, "openRecordings");
        setName("dps-moved");
        KitButton open = KitButton.primary("Open Live meter"), recordings = KitButton.secondary("Open Recordings");
        open.setName("dps-moved-open");
        open.setToolTipText("Opens the Live meter tab of Runs & DPS");
        open.addActionListener(e -> openLiveMeter.run());
        recordings.setName("dps-moved-recordings");
        recordings.setToolTipText("Opens the Recordings tab of Runs & DPS: this app run's fights, saved summaries and imports");
        recordings.addActionListener(e -> openRecordings.run());
        EmptyState empty = new EmptyState("DPS Logger moved", "The live meter and your recordings are now tabs of Runs & DPS.", null);
        // EmptyState takes one action; both sit side by side in its action row (column 0, row 2), primary first.
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, Tokens.S, 0));
        actions.setName("dps-moved-actions");
        actions.setOpaque(false);
        actions.add(open);
        actions.add(recordings);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 2;
        empty.add(actions, c);
        add(empty, BorderLayout.CENTER);
    }
}
