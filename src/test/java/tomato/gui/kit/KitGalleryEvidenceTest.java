package tomato.gui.kit;

import java.awt.*;
import java.util.Locale;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.Themes;
import ui.VisualEvidence;
import static org.junit.Assert.*;

public class KitGalleryEvidenceTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-kit");

    @Before public void noMotion() { Motion.systemOverride = false; }
    @After public void restore() { Motion.systemOverride = null; }

    @Test public void galleryRendersInBothVariantsAtWideAndCompactSizes() throws Exception {
        for (Themes.Variant variant : Themes.Variant.values())
            for (int[] size : new int[][]{{1240, 800}, {680, 520}})
                for (int font : new int[]{13, 18}) {
                    JComponent[] gallery = new JComponent[1];
                    SwingUtilities.invokeAndWait(() -> {
                        Themes.install(new Themes.Choice(variant, false));
                        gallery[0] = KitGallery.build();
                        evidence.show(gallery[0], "Kit gallery", size[0], size[1], font);
                    });
                    evidence.settle();
                    SwingUtilities.invokeAndWait(() -> {
                        evidence.capture("kit-" + variant.name().toLowerCase(Locale.ROOT) + "-" + size[0] + "-" + font);
                        assertIconButtonsAreNamed(gallery[0]);
                        JScrollPane scroll = (JScrollPane) gallery[0];
                        if (scroll.getVerticalScrollBar().isVisible()) {
                            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                            evidence.capture("kit-" + variant.name().toLowerCase(Locale.ROOT) + "-" + size[0] + "-" + font + "-lower");
                        }
                    });
                }
    }

    /** Kit buttons only: Swing's own scroll-bar and combo arrow buttons are unnamed by design. */
    private static void assertIconButtonsAreNamed(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof KitButton) {
                KitButton button = (KitButton) child;
                String name = button.getAccessibleContext().getAccessibleName();
                if (button.getText() == null || button.getText().isEmpty())
                    assertFalse("Icon-only button needs an accessible name: " + button.getName(), name == null || name.isEmpty());
            }
            if (child instanceof Container) assertIconButtonsAreNamed((Container) child);
        }
    }
}
