package tomato.gui.bridge;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.bridge.BridgeService;
import tomato.gui.kit.DisplayModeModel;
import static org.junit.Assert.*;
import static tomato.gui.bridge.BridgeFilterBarTest.*;

/** P6b Polish C: Bridge Settings names the capture menu item as the File menu shows it. Synthetic in-process transport only. */
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

    private static List<String> texts(Container root) {
        List<String> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea) found.add(((JTextArea) child).getText());
            if (child instanceof Container) found.addAll(texts((Container) child));
        }
        return found;
    }
}
