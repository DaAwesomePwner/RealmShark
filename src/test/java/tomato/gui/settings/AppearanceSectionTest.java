package tomato.gui.settings;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Motion;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;
import static tomato.gui.settings.SettingsPageTest.named;

public class AppearanceSectionTest {
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
    private final List<Themes.Choice> selected = new ArrayList<>();
    private Themes.Choice installed = new Themes.Choice(Themes.Variant.DARK, false);
    private int refreshed;

    private AppearanceSection section() {
        return new AppearanceSection(selected::add, () -> installed, store::get, store::put, mode, () -> refreshed++);
    }

    @Test public void themeAndContrastApplyThroughThemesAndRefreshTheShell() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AppearanceSection section = section();
            assertEquals("settings-appearance", section.getName());
            named(section, "settings-theme-1", AbstractButton.class).doClick();
            assertEquals(new Themes.Choice(Themes.Variant.LIGHT, false), selected.get(0));
            named(section, "settings-increase-contrast", AbstractButton.class).doClick();
            assertEquals(new Themes.Choice(Themes.Variant.LIGHT, true), selected.get(1));
            assertEquals(2, refreshed);
        });
    }

    @Test public void controlsFollowTheInstalledThemeAfterAnyLookAndFeelChange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AppearanceSection section = section();
            assertEquals(0, named(section, "settings-theme", SegmentedControl.class).selected());
            assertFalse(named(section, "settings-increase-contrast", JCheckBox.class).isSelected());
            installed = new Themes.Choice(Themes.Variant.LIGHT, true);
            SwingUtilities.updateComponentTreeUI(section);
            assertEquals(1, named(section, "settings-theme", SegmentedControl.class).selected());
            assertTrue(named(section, "settings-increase-contrast", JCheckBox.class).isSelected());
            assertTrue("Following the Theme menu never re-selects a theme", selected.isEmpty());
        });
    }

    @Test public void reduceMotionAndDisplayModeSaveImmediately() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AppearanceSection section = section();
            JCheckBox motion = named(section, "settings-reduce-motion", JCheckBox.class);
            assertFalse(motion.isSelected());
            motion.doClick();
            assertEquals("true", store.get(Motion.REDUCE_KEY));
            motion.doClick();
            assertEquals("false", store.get(Motion.REDUCE_KEY));
            named(section, "settings-display-mode-1", AbstractButton.class).doClick();
            assertEquals("analyst", store.get(DisplayModeModel.KEY));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals("The header switch and this control share one mode", 0,
                named(section, "settings-display-mode", SegmentedControl.class).selected());
        });
    }
}
