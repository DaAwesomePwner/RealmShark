package tomato.gui.glance.home;

import java.awt.BorderLayout;
import javax.swing.JPanel;
import tomato.gui.kit.EmptyState;

/** Home (shell page 14): your character, today's progress and recent runs at a glance. Until its cards exist it invites capture. */
public final class HomePage extends JPanel {
    public HomePage() {
        super(new BorderLayout());
        setName("home-page");
        setOpaque(false);
        add(new EmptyState("Home", "Start capture and enter the game to see your character.", null), BorderLayout.CENTER);
    }
}
