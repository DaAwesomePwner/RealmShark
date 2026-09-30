package tomato.gui.glance.character;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import org.junit.Test;
import tomato.gui.modern.ThemeSwitchBordersTest;

/** P6b Polish E: a sheet tab's scroll pane keeps no box after a live theme switch (ThemeSwitchBordersTest restores the dark theme). */
public class SheetViewsTest {
    @Test public void aSheetTabsScrollPaneKeepsNoBoxAfterALiveThemeSwitch() throws Exception {
        ThemeSwitchBordersTest.assertBoxlessThroughThemeSwitch("A sheet tab", () -> SheetViews.scroll(new JPanel()), (JComponent root) -> (JScrollPane) root);
    }
}
