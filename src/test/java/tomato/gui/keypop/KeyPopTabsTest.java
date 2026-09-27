package tomato.gui.keypop;

import java.time.Instant;
import javax.swing.*;
import org.junit.*;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class KeyPopTabsTest {
    private static final String ORDER = "ui.tabs.keypops-live";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void movedViewsKeepTheirModesAndTables() throws Exception {
        PropertiesManager.setProperties(ORDER, "by-item,events,by-player|");
        SwingUtilities.invokeAndWait(() -> {
            KeyPopHistory history = new KeyPopHistory(); history.add(new KeyPopEvent(Instant.now(), "Ann", "Halls", KeyPopEvent.Kind.KEY));
            KeyPopDashboard ui = new KeyPopDashboard(history);
            assertEquals("By dungeon / item", ui.tabs.getTitleAt(0));
            ui.tabs.setSelectedIndex(1); assertEquals(KeyPopArchiveClient.Mode.EVENTS, ui.captureLiveState().query.facets().mode);
            ui.tabs.setSelectedIndex(0); assertEquals(KeyPopArchiveClient.Mode.BY_ITEM, ui.captureLiveState().query.facets().mode);
            ui.items.setRowSelectionInterval(0, 0); assertEquals("Halls", ui.selectedDungeon());
        });
    }
}
