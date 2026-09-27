package tomato.gui.myinfo;

import java.awt.BorderLayout;
import java.awt.event.HierarchyEvent;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;

/** Page 6 after P3a: Build is a tab on the character sheet. "Open Build" follows the Build route once a character exists. */
public final class BuildMovedPanel extends JPanel {
    private final KitButton open = KitButton.primary("Open Build");
    private final BooleanSupplier available;

    /** {@code available}: whether some character exists to open (BuildRoute.key != null). */
    public BuildMovedPanel(Runnable openBuild, BooleanSupplier available) {
        super(new BorderLayout());
        this.available = Objects.requireNonNull(available, "available");
        setName("build-moved");
        open.setName("build-moved-open");
        open.addActionListener(e -> openBuild.run());
        add(new EmptyState("Build moved", "Build is now a tab on the character sheet.", open), BorderLayout.CENTER);
        addHierarchyListener(e -> { if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh(); });
        refresh();
    }

    /** EDT: Open Build is enabled only while a character exists, so it never routes back to this page. */
    public void refresh() {
        boolean any = available.getAsBoolean();
        open.setEnabled(any);
        open.setToolTipText(any ? "Opens the Build tab of the character you're playing, or of your most recent character"
            : "Enter the game with capture on to add a character");
    }
}
