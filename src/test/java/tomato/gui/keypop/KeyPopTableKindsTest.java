package tomato.gui.keypop;

import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class KeyPopTableKindsTest {
    @Test public void liveKeyPopTablesUseColumnKindWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyPopDashboard ui = new KeyPopDashboard(new KeyPopHistory());
            assertEquals(ColumnKind.DATE_TIME.width(ui.events.getFont()), ui.events.getColumnModel().getColumn(0).getPreferredWidth());
            assertEquals(ColumnKind.PLAYER.width(ui.events.getFont()), ui.events.getColumnModel().getColumn(1).getPreferredWidth());
            assertEquals(ColumnKind.COUNT.width(ui.players.getFont()), ui.players.getColumnModel().getColumn(1).getPreferredWidth());
        });
    }
}
