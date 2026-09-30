package tomato.gui.bridge;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.bridge.BridgeService;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;
import static tomato.gui.bridge.BridgeFilterBarTest.*;

/**
 * P6b Polish C: Bridge Settings names the capture menu item as the File menu shows it, and its page scroll stays borderless through a
 * live theme switch. Synthetic in-process transport only; the dark theme is installed again afterwards.
 */
public class BridgeSettingsCopyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void theIntroNamesTheFileMenusCaptureItem() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.SIMPLE)));
            List<String> texts = edt(() -> texts(panel));
            assertTrue("The intro names File › Start capture connection: " + texts, texts.stream().anyMatch(t -> t.contains("Enable capture with File › Start capture connection.")));
            assertTrue("The old menu name is gone", texts.stream().noneMatch(t -> t.contains("Start Sniffer")));
        }
    }

    /**
     * Polish B's finding: a scroll pane whose border is null gets the look and feel's outline back on a live theme switch (nested frames
     * in the light theme). The Settings page's scroll keeps an empty border through dark → light.
     */
    @Test public void theSettingsScrollStaysBorderlessThroughALiveThemeSwitch() throws Exception {
        try (BridgeService service = service(temp)) {
            edt(() -> {
                Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
                BridgeReviewGUI panel = new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.SIMPLE));
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, named(panel, "bridge-endpoint", JTextField.class));
                assertEquals("dark: no outline", new Insets(0, 0, 0, 0), scroll.getInsets());
                Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
                SwingUtilities.updateComponentTreeUI(panel);
                javax.swing.border.Border border = scroll.getBorder();
                assertNotNull("An explicit empty border, not null (which the look and feel refills)", border);
                assertFalse("Not the look and feel's border after the switch: " + border, border instanceof javax.swing.plaf.UIResource);
                assertEquals("No outline after the switch to light", new Insets(0, 0, 0, 0), border.getBorderInsets(scroll));
                return null;
            });
        } finally { edt(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false))); }
    }

    private static List<String> texts(Container root) {
        List<String> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea) found.add(((JTextArea) child).getText());
            if (child instanceof Container) found.addAll(texts((Container) child));
        }
        return found;
    }
}
