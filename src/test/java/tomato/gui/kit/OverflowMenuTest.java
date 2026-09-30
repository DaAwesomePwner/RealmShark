package tomato.gui.kit;

import java.awt.Component;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.junit.Test;
import static org.junit.Assert.*;

/** OverflowMenu's named, replaceable sections: hidden while empty, placed where created, separators never doubled. */
public class OverflowMenuTest {
    @Test public void aSectionAloneKeepsTheMenuHiddenWhileEmpty() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OverflowMenu more = new OverflowMenu("tools-more");
            OverflowMenu.Section section = more.section("tools");
            assertSame("One section per ID", section, more.section("tools"));
            assertEquals("tools", section.id());
            assertFalse("An empty section keeps ⋯ hidden", more.isVisible());
            JMenuItem a = new JMenuItem("A"), b = new JMenuItem("B");
            section.replace(a, b);
            assertTrue(more.isVisible());
            assertEquals(List.of(a, b), section.items());
            assertEquals(List.of("A", "B"), shown(more));
            section.clear();
            assertFalse("Emptied again, ⋯ hides", more.isVisible());
            assertTrue(section.items().isEmpty());
            assertEquals(List.of(), shown(more));
        });
    }

    @Test public void aSectionIsReplacedInPlaceWithSingleSeparators() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OverflowMenu more = new OverflowMenu("page-more");
            JMenuItem refresh = more.add("Refresh", () -> {});
            OverflowMenu.Section section = more.section("tools");
            more.add("Export page…", () -> {});
            assertEquals("No separator around an empty section", List.of("Refresh", "Export page…"), shown(more));
            section.replace(new JMenuItem("Columns"), new JMenuItem("Reset columns"));
            assertEquals(List.of("Refresh", "—", "Columns", "Reset columns", "—", "Export page…"), shown(more));
            section.replace(Collections.singletonList(new JMenuItem("Columns")));
            assertEquals("Replaced, not appended", List.of("Refresh", "—", "Columns", "—", "Export page…"), shown(more));
            assertEquals(1, count(more, "Columns"));
            // Visibility changes elsewhere are settled when the menu opens.
            refresh.setVisible(false);
            open(more);
            assertEquals("Nothing before the section: no leading separator", List.of("Columns", "—", "Export page…"), shown(more));
            section.clear();
            refresh.setVisible(true);
            open(more);
            assertEquals(List.of("Refresh", "Export page…"), shown(more));
        });
    }

    @Test public void separatorsNeverDoubleUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OverflowMenu more = new OverflowMenu("page-more");
            more.add("Refresh", () -> {});
            more.addSeparator();
            OverflowMenu.Section first = more.section("first");
            OverflowMenu.Section second = more.section("second");
            more.addSeparator();
            more.add("Export page…", () -> {});
            first.replace(new JMenuItem("A"));
            second.replace(new JMenuItem("B"));
            open(more);
            assertEquals(List.of("Refresh", "—", "A", "—", "B", "—", "Export page…"), shown(more));
            first.clear();
            open(more);
            assertEquals(List.of("Refresh", "—", "B", "—", "Export page…"), shown(more));
        });
    }

    /** Visible entries in order; a visible separator reads "—". */
    private static List<String> shown(OverflowMenu more) {
        List<String> shown = new ArrayList<>();
        for (Component child : more.menu().getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JSeparator) shown.add("—");
            else if (child instanceof JMenuItem) shown.add(((JMenuItem) child).getText());
        }
        return shown;
    }

    private static int count(OverflowMenu more, String label) {
        int found = 0;
        for (Component child : more.menu().getComponents()) if (child instanceof JMenuItem && label.equals(((JMenuItem) child).getText())) found++;
        return found;
    }

    /** What showing the popup runs first, without a native window. */
    private static void open(OverflowMenu more) {
        PopupMenuEvent event = new PopupMenuEvent(more.menu());
        for (PopupMenuListener listener : more.menu().getPopupMenuListeners()) listener.popupMenuWillBecomeVisible(event);
    }
}
