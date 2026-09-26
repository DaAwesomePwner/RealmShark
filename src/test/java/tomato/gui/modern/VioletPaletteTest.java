package tomato.gui.modern;

import java.awt.Color;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;

public class VioletPaletteTest {
    private LookAndFeel previous;

    @Before public void remember() throws Exception { SwingUtilities.invokeAndWait(() -> previous = UIManager.getLookAndFeel()); }
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            if (previous != null && !(previous instanceof VioletTheme)) {
                try { UIManager.setLookAndFeel(previous); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            }
        });
    }

    @Test public void darkVariantKeepsTheExistingPalette() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertTrue(UIManager.getLookAndFeel() instanceof VioletTheme);
            assertEquals(new Color(0x181627), UIManager.getColor("Table.background"));
            assertEquals(new Color(0x38334F), UIManager.getColor("Component.borderColor"));
            assertEquals(new Color(0xAAA7BD), ContentStyle.color("muted"));
            assertEquals(new Color(0xE9E6F7), ContentStyle.color("text"));
        });
    }

    @Test public void lightVariantUsesTheLightRoles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            assertTrue(UIManager.getLookAndFeel() instanceof VioletLightTheme);
            assertEquals(new Color(0xFFFFFF), UIManager.getColor("Table.background"));
            assertEquals(new Color(0xF5F4F9), ContentStyle.color("background"));
            assertEquals(new Color(0x24222E), ContentStyle.color("text"));
            assertEquals(new Color(0x6241AA), ContentStyle.color("violet"));
            assertEquals(new Color(0x626071), ContentStyle.color("muted"));
            // Plain menu items (for example Find settings, Ctrl+K) must not retain
            // FlatLightLaf's white accelerator text on our pale selection surface.
            assertEquals(new Color(0x302048), UIManager.getColor("MenuItem.acceleratorSelectionForeground"));
        });
    }

    @Test public void increaseContrastStrengthensBordersAndMutedTextInBothVariants() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, true));
            assertTrue(Themes.increaseContrast());
            assertEquals(new Color(0x5A5378), UIManager.getColor("Component.borderColor"));
            assertEquals(new Color(0xCFCBE0), ContentStyle.color("muted"));
            assertEquals(2, UIManager.getInt("Component.focusWidth"));

            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, true));
            assertEquals(new Color(0x8C86A3), UIManager.getColor("Component.borderColor"));
            assertEquals(new Color(0x4A4757), ContentStyle.color("muted"));

            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertFalse(Themes.increaseContrast());
            assertEquals(new Color(0x38334F), UIManager.getColor("Component.borderColor"));
            assertEquals(1, UIManager.getInt("Component.focusWidth"));
        });
    }
}
