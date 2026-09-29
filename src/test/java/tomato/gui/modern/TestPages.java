package tomato.gui.modern;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import static org.junit.Assert.fail;

/** Test-only shell pages by destination ID, so no test addresses a shell page by number. */
public final class TestPages {
    private TestPages() { }

    /** A named placeholder panel per destination, in {@link NavEntry#defaults()} order; put real content over one. */
    public static Map<String, JComponent> placeholders() {
        Map<String, JComponent> pages = new LinkedHashMap<>();
        for (NavEntry entry : NavEntry.defaults()) {
            JPanel page = new JPanel();
            page.setName("test-page-" + entry.id());
            pages.put(entry.id(), page);
        }
        return pages;
    }

    /** A preview shell (capture disabled) over {@code pages}. */
    public static WorkspaceShell shell(Map<String, JComponent> pages) {
        return new WorkspaceShell(pages, () -> fail("Synthetic workspace must not capture"), true);
    }

    /** A preview shell with {@code content} as the page {@code id} and every other page a placeholder, showing {@code id}. */
    public static WorkspaceShell shell(String id, JComponent content) {
        Map<String, JComponent> pages = placeholders();
        if (!pages.containsKey(id)) throw new IllegalArgumentException("No destination " + id);
        pages.put(id, content);
        WorkspaceShell shell = shell(pages);
        shell.select(id);
        return shell;
    }

    /** The sidebar title of the destination {@code id}. */
    public static String title(String id) {
        NavEntry entry = NavEntry.forId(id);
        if (entry == null) throw new IllegalArgumentException("No destination " + id);
        return entry.title();
    }

    /** IDs of the visible rows in the scrolling list, top to bottom (Settings is below the list). */
    public static List<String> listedRows(WorkspaceShell shell) {
        for (int pass = 0; pass < 2; pass++) layoutTree(shell);
        List<AbstractButton> rows = new ArrayList<>();
        for (NavEntry entry : NavEntry.defaults()) {
            AbstractButton row = named(shell, "nav-" + entry.id());
            if (row.isVisible() && SwingUtilities.getAncestorOfClass(JViewport.class, row) != null) rows.add(row);
        }
        rows.sort(Comparator.comparingInt(row -> SwingUtilities.convertPoint(row, 0, 0, shell).y));
        List<String> ids = new ArrayList<>();
        for (AbstractButton row : rows) ids.add(row.getName().substring("nav-".length()));
        return ids;
    }

    /** IDs of the destinations the compact menu lists, in menu order. */
    public static List<String> menuPages(JPopupMenu popup) {
        List<String> ids = new ArrayList<>();
        for (Component item : popup.getComponents())
            if (item instanceof JMenuItem && item.isVisible()) ids.add(item.getName().substring("compact-nav-".length()));
        return ids;
    }

    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container && child.isVisible()) layoutTree((Container) child);
    }

    private static AbstractButton named(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && child instanceof AbstractButton) return (AbstractButton) child;
            if (child instanceof Container) {
                AbstractButton found = named((Container) child, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
