package tomato.gui.character;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class CharacterFilterBarTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void rosterFiltersLiveInTheDrawerWithChips() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty);
            FilterBar bar = find(panel, FilterBar.class, "characters-filter-bar"); JComponent drawer = bar.drawerContent();
            JComboBox<?> need = find(panel, JComboBox.class, "character-facet-2");
            assertTrue(SwingUtilities.isDescendingFrom(need, drawer));
            JTextField search = find(panel, JTextField.class, "character-search");
            assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, drawer));
            need.setSelectedIndex(1); find(panel, JComboBox.class, "character-facet-7").setSelectedIndex(3);
            assertEquals(Arrays.asList("Needs Life", "Unknown snapshot age"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Needs Life"); assertEquals(0, need.getSelectedIndex());
            find(panel, AbstractButton.class, "characters-clear-filters").doClick(); assertEquals(0, bar.activeCount());
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
