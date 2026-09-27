package tomato.gui.chat;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
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
}
