package tomato.gui.kit;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ScopeChip;
import static org.junit.Assert.*;

/** Shared filter-row checks (spec S6): one row per bar, and the Scope chip in the bar the page shows. EDT only. */
public final class FilterBarAssert {
    private FilterBarAssert() {}

    /**
     * Every showing control of the bar's top row (search slot, Filters, chips, Clear, scope, ⋯) starts within one control height
     * of the first, and the row is under 1.6 control heights tall, so nothing wrapped onto a second line. The control height is
     * the Filters toggle's preferred height. A failure describes the row ({@link #describeRow}).
     */
    public static void assertOneRow(FilterBar bar) {
        AbstractButton toggle = toggle(bar);
        Container leading = toggle.getParent(), row = leading.getParent();
        int control = toggle.getPreferredSize().height;
        List<Component> showing = showing(row);
        assertFalse("Showing controls in " + bar.getName(), showing.isEmpty());
        int first = y(showing.get(0), bar);
        for (Component child : showing)
            if (Math.abs(y(child, bar) - first) >= control)
                fail(label(child) + " starts on the first line of " + bar.getName() + ": y=" + y(child, bar) + ", first=" + first + ", control=" + control + "; " + describeRow(bar));
        if (row.getHeight() >= 1.6 * control) fail(bar.getName() + " is one row: " + row.getHeight() + " px < 1.6 × " + control + "; " + describeRow(bar));
    }

    /**
     * The bar's top row as text, for reports and failure messages: the bar's width and the row's height, then each showing control
     * as {@code label@x,y width×height} in bar coordinates, with the search slot's own controls in brackets.
     */
    public static String describeRow(FilterBar bar) {
        Container row = toggle(bar).getParent().getParent();
        StringBuilder text = new StringBuilder(bar.getName()).append(" width=").append(bar.getWidth()).append(" row=").append(row.getHeight()).append(':');
        for (Component child : showing(row)) {
            text.append(' ').append(box(child, bar));
            if (child == bar.searchSlot() && child instanceof Container) {
                text.append(" [");
                for (Component part : ((Container) child).getComponents()) if (part.isShowing()) text.append(' ').append(box(part, bar));
                text.append(" ]");
            }
        }
        return text.toString();
    }

    /**
     * The workspace's Scope chip is inside the workspace and in the bar the page shows: the live host's bar while live (the
     * workspace bar hidden), else the workspace's own bar. While the workspace shows, so does the chip.
     */
    public static void assertChipInVisibleBar(ArchiveWorkspace<?, ?, ?> workspace) {
        String module = workspace.getName().substring(0, workspace.getName().length() - "-session-view".length());
        ScopeChip chip = find(workspace, module + "-scope");
        assertNotNull("The Scope chip is inside the workspace: " + module, chip);
        FilterBar live = workspace.liveFilterBar(), own = workspace.filterBar();
        FilterBar visible = !workspace.state().archive && live != null ? live : own;
        assertTrue("The chip is in the visible bar " + visible.getName(), SwingUtilities.isDescendingFrom(chip, visible));
        assertEquals("The workspace bar shows exactly when it hosts the chip", visible == own, own.isVisible());
        if (workspace.isShowing()) assertTrue("The chip shows with its workspace", chip.isShowing());
    }

    private static AbstractButton toggle(FilterBar bar) {
        String name = bar.getName().substring(0, bar.getName().length() - "-filter-bar".length()) + "-filters";
        AbstractButton toggle = find(bar, name);
        assertNotNull("The Filters toggle of " + bar.getName(), toggle);
        return toggle;
    }

    private static int y(Component child, Component bar) { return SwingUtilities.convertPoint(child, 0, 0, bar).y; }

    /** The showing controls of the row's showing sides (leading, then trailing), in order. */
    private static List<Component> showing(Container row) {
        List<Component> showing = new ArrayList<>();
        for (Component side : row.getComponents())
            if (side instanceof Container && side.isShowing())
                for (Component child : ((Container) side).getComponents()) if (child.isShowing()) showing.add(child);
        return showing;
    }

    private static String box(Component child, Component bar) {
        Point at = SwingUtilities.convertPoint(child, 0, 0, bar);
        return label(child) + "@" + at.x + "," + at.y + " " + child.getWidth() + "×" + child.getHeight();
    }

    private static String label(Component child) {
        if (child.getName() != null) return child.getName();
        return child instanceof AbstractButton ? "'" + ((AbstractButton) child).getText() + "'" : child.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return (T) child;
            if (child instanceof Container) { T found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
