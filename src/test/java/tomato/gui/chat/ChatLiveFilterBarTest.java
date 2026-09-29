package tomato.gui.chat;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveNativeSupport.Memory;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class ChatLiveFilterBarTest {
    @Test public void playerStarsAndDatesMoveToTheDrawerWhileSearchChannelsAndFollowStayVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer ui = new ChatExplorer(() -> {});
            FilterBar bar = named(ui, "chat-live-filter-bar", FilterBar.class);
            assertNotNull(bar); assertFalse(bar.drawerOpen()); JComponent drawer = bar.drawerContent();
            for (String name : new String[]{"chat-player", "chat-show-ignored-players", "chat-live-sort"})
                assertTrue(name, SwingUtilities.isDescendingFrom(named(ui, name, JComponent.class), drawer));
            assertFalse(SwingUtilities.isDescendingFrom(named(ui, "chat-search", JTextField.class), drawer));
            assertTrue(SwingUtilities.isDescendingFrom(button(ui, "Follow latest"), bar));
            assertFalse(SwingUtilities.isDescendingFrom(button(ui, "Follow latest"), drawer));
            assertFalse("Channel pills stay outside the bar", SwingUtilities.isDescendingFrom(named(ui, "chat-channel-ALL", JComponent.class), bar));
            assertNull("The drawer replaces the old toggle", button(ui, "Dates / view state"));
            named(ui, "chat-player", JTextField.class).setText("Wren"); button(ui, "Starred").doClick(); ui.refresh(false);
            List<String> labels = ArchiveNativeSupport.chipLabels(bar); labels.remove("Ignored players shown");
            assertEquals(Arrays.asList("Player: Wren", "Starred"), labels);
            ArchiveNativeSupport.removeChip(bar, "Player: Wren");
            assertEquals("", named(ui, "chat-player", JTextField.class).getText());
            named(ui, "chat-live-clear-filters", AbstractButton.class).doClick();
            assertFalse(((AbstractButton) button(ui, "Starred")).isSelected());
        });
    }

    /**
     * P6b Task 12: with live view state, the drawer still holds only filters (player, stars, ignored players, sort and dates). The
     * named live views and the table's column tools are row ⋯ items, and the live-state status sits under the row, shown only while
     * it reports a problem.
     */
    @Test public void withLiveStateTheDrawerKeepsOnlyFiltersAndViewsAndColumnToolsAreRowItems() throws Exception {
        Memory memory = new Memory(); ChatExplorer[] view = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new ChatExplorer(() -> {}); ChatExplorer ui = view[0];
                ui.enableLiveState(memory.states);
                FilterBar bar = named(ui, "chat-live-filter-bar", FilterBar.class); JComponent drawer = bar.drawerContent();
                for (String name : new String[]{"chat-player", "chat-show-ignored-players", "chat-live-sort", "social-date-from", "social-date-until"})
                    assertTrue(name, SwingUtilities.isDescendingFrom(named(ui, name, JComponent.class), drawer));
                for (String text : new String[]{"Starred", "Descending", "Apply dates"})
                    assertTrue(text, SwingUtilities.isDescendingFrom(button(ui, text), drawer));
                for (String gone : new String[]{"Save live view", "Load live view", "Delete live view", "Reset saved live state",
                        "Copy selected", "Details…", "Columns…", "Reset columns"})
                    assertNull("No longer a row of buttons: " + gone, button(ui, gone));
                assertNull("No named-views combo", find(ui, JComboBox.class, box -> "Named live views".equals(box.getAccessibleContext().getAccessibleName())));
                assertNotNull("⋯ Saved views", bar.overflow().item("Saved views"));
                assertNotNull("⋯ Columns", bar.overflow().item("Columns"));
                tomato.gui.kit.Banner status = named(ui, "chat-live-state-status", tomato.gui.kit.Banner.class);
                assertNotNull("The live-state status", status);
                assertFalse("The status is not a drawer control", SwingUtilities.isDescendingFrom(status, drawer));
                assertFalse("Nothing to report", status.isVisible());
                memory.failSaves = true;
                ui.removeNotify();   // persists the live state now; the synthetic store refuses it
            });
            SwingUtilities.invokeAndWait(() -> {
                tomato.gui.kit.Banner status = named(view[0], "chat-live-state-status", tomato.gui.kit.Banner.class);
                assertTrue("A failed save shows: " + status.text(), status.isVisible());
                assertTrue(status.text(), status.text().contains("state save failed"));
            });
        } finally { memory.failSaves = false; }
    }

    private static <T extends java.awt.Component> T find(java.awt.Container root, Class<T> type, java.util.function.Predicate<T> test) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child) && test.test(type.cast(child))) return type.cast(child);
            if (child instanceof java.awt.Container) { T found = find((java.awt.Container) child, type, test); if (found != null) return found; }
        }
        return null;
    }
}
