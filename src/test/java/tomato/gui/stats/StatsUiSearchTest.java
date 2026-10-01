package tomato.gui.stats;

import com.formdev.flatlaf.icons.FlatSearchIcon;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StatsUiSearchTest {
    @Test public void searchHasAnIconAndClearButton() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = StatsUi.search("loot-search", "Search loot", 18);
            assertTrue(field.getClientProperty("JTextField.leadingIcon") instanceof FlatSearchIcon);
            assertEquals(true, field.getClientProperty("JTextField.showClearButton"));
            assertEquals("Search loot", field.getAccessibleContext().getAccessibleName());
            assertEquals(18, field.getColumns());
        });
    }
}
