package tomato.gui.kit;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.LineIcon;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

public class ControlsTest {
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void variantsSurviveThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            KitButton primary = KitButton.primary("Start capture"), ghost = KitButton.ghost("Clear");
            assertEquals(KitButton.Variant.PRIMARY, primary.variant());
            assertNotNull(primary.getClientProperty("FlatLaf.style"));
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(primary); SwingUtilities.updateComponentTreeUI(ghost);
            assertNotNull("Style reapplied after updateUI", primary.getClientProperty("FlatLaf.style"));
            assertEquals("borderless", ghost.getClientProperty("JButton.buttonType"));
        });
    }

    @Test public void iconButtonsAreNamedForAssistiveTechnology() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KitButton icon = KitButton.icon(new LineIcon(LineIcon.GEAR, 16), "Settings");
            assertEquals("Settings", icon.getToolTipText());
            assertEquals("Settings", icon.getAccessibleContext().getAccessibleName());
            assertTrue(icon.getText() == null || icon.getText().isEmpty());
        });
    }

    @Test public void removableChipRunsItsRemoveAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] removed = {0};
            JComponent chip = Chip.removable("Completed", () -> removed[0]++);
            AbstractButton remove = find(chip, "remove-filter");
            assertEquals("Remove filter: Completed", remove.getAccessibleContext().getAccessibleName());
            remove.doClick();
            assertEquals(1, removed[0]);
            Chip status = new Chip("Left", Tokens.Tone.NEUTRAL);
            assertEquals(Tokens.tone(Tokens.Tone.NEUTRAL), status.getForeground());
            status.setTone(Tokens.Tone.GOOD);
            assertEquals(Tokens.tone(Tokens.Tone.GOOD), status.getForeground());
        });
    }

    @Test public void segmentedControlReportsUserChangesOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SegmentedControl mode = new SegmentedControl("display-mode", "Simple", "Analyst");
            java.util.List<Integer> changes = new ArrayList<>();
            mode.onChange(changes::add);
            mode.setSelected(1);
            assertTrue("Programmatic selection is silent", changes.isEmpty());
            assertEquals(1, mode.selected());
            ((AbstractButton) find(mode, "display-mode-0")).doClick();
            assertEquals(Collections.singletonList(0), changes);
        });
    }

    @Test public void overflowMenuAppearsOnceItHasItems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OverflowMenu more = new OverflowMenu("runs-more");
            assertFalse(more.isVisible());
            int[] ran = {0};
            more.add("Export page…", () -> ran[0]++);
            JMenu views = more.submenu("Saved views");
            JMenuItem nested = new JMenuItem("Reset saved state"); views.add(nested);
            assertTrue(more.isVisible());
            assertEquals("More actions", more.getAccessibleContext().getAccessibleName());
            more.item("Export page…").doClick();
            assertEquals(1, ran[0]);
            assertSame(nested, more.item("Reset saved state"));
            assertNull(more.item("Missing"));
        });
    }

    static AbstractButton find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && name.equals(child.getName())) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
