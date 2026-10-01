package tomato.gui.kit;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The breadcrumb: its labels in order, links for every crumb but the last, and the "›" separators. */
public class BreadcrumbTest {
    @Test public void everyCrumbButTheLastIsALink() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> opened = new ArrayList<>();
            Breadcrumb path = new Breadcrumb("path");
            path.setPath(List.of(new Breadcrumb.Crumb("Collection", () -> opened.add("collection")),
                new Breadcrumb.Crumb("Synthetic Seal", () -> opened.add("never"))));
            assertEquals(List.of("Collection", "Synthetic Seal"), path.labels());
            Component first = named(path, "path-0"), last = named(path, "path-1");
            assertTrue(first instanceof AbstractButton);
            assertFalse("The last crumb is where you are", last instanceof AbstractButton);
            ((AbstractButton) first).doClick();
            assertEquals(List.of("collection"), opened);

            path.setPath(List.of(new Breadcrumb.Crumb("Runs", null)));
            assertEquals(List.of("Runs"), path.labels());
            assertFalse(named(path, "path-0") instanceof AbstractButton);
        });
    }

    @Test public void anUnchangedPathKeepsItsComponentsAndUsesTheLatestAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> opened = new ArrayList<>();
            Breadcrumb path = new Breadcrumb("path");
            path.setPath(List.of(new Breadcrumb.Crumb("Collection", () -> opened.add("first")),
                new Breadcrumb.Crumb("Synthetic Seal", null)));
            Component first = named(path, "path-0"), last = named(path, "path-1");
            path.setPath(List.of(new Breadcrumb.Crumb("Collection", () -> opened.add("second")),
                new Breadcrumb.Crumb("Synthetic Seal", () -> opened.add("never"))));
            assertSame("An unchanged link keeps keyboard focus", first, named(path, "path-0"));
            assertSame("The last crumb stays plain regardless of its action", last, named(path, "path-1"));
            ((AbstractButton) first).doClick();
            assertEquals(List.of("second"), opened);

            path.setPath(List.of(new Breadcrumb.Crumb("Collection", null), new Breadcrumb.Crumb("Synthetic Seal", null)));
            assertFalse("A changed link pattern rebuilds the crumb", named(path, "path-0") instanceof AbstractButton);
        });
    }

    private static Component named(JComponent root, String name) {
        for (Component child : root.getComponents()) if (name.equals(child.getName())) return child;
        throw new AssertionError("No " + name);
    }
}
