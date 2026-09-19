package tomato.gui.character;

import org.junit.Test;
import com.formdev.flatlaf.FlatLightLaf;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.data.enums.StatType;

import javax.swing.*;
import java.awt.*;

import static org.junit.Assert.*;

public class PetFeedingFormTest {
    @Test public void invalidAndLockedLabelsFollowThemeWithoutNewCaptureData() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel original = UIManager.getLookAndFeel();
            JFrame frame = new JFrame();
            try {
                UIManager.setLookAndFeel(new VioletTheme());
                CharacterPetsGUI panel = new CharacterPetsGUI(null);
                frame.setContentPane(panel);
                frame.setSize(640, 600);
                CharacterPetsGUI.addPet(pet(stat(StatType.PET_INSTANCE_ID_STAT, 1), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30)));
                frame.setVisible(true);
                find(panel, JTextField.class).setText("0");
                JLabel invalid = label(panel, "Enter a positive whole number (1–2,147,483,647).");
                JLabel locked = label(panel, "Locked ability");
                assertNotNull(invalid);
                assertNotNull(locked);
                Color oldInvalid = invalid.getForeground(), oldLocked = locked.getForeground();
                UIManager.setLookAndFeel(new FlatLightLaf());
                SwingUtilities.updateComponentTreeUI(frame);
                assertEquals(ContentStyle.color("rose"), invalid.getForeground());
                assertEquals(ContentStyle.color("muted"), locked.getForeground());
                assertNotEquals(oldInvalid, invalid.getForeground());
                assertNotEquals(oldLocked, locked.getForeground());
                assertSame(locked, label(panel, "Locked ability"));
                assertFalse(button(panel, "Recalculate feeding costs").isEnabled());
            } catch (UnsupportedLookAndFeelException e) {
                throw new AssertionError(e);
            } finally {
                frame.dispose();
                CharacterPetsGUI.clearPets();
                try { UIManager.setLookAndFeel(original); }
                catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            }
        });
    }

    @Test public void validatesPositiveFeedPowerAndPreservesCapturedPetSnapshots() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterPetsGUI panel = new CharacterPetsGUI(null);
            JFrame frame = new JFrame();
            frame.setContentPane(panel);
            frame.setSize(640, 600);
            try {
                JTextField feed = find(panel, JTextField.class);
                JButton calculate = button(panel, "Recalculate feeding costs");
                assertNotNull(calculate);
                assertEquals("Recalculate feeding costs", calculate.getText());
                assertEquals("Feed power per item", feed.getAccessibleContext().getAccessibleName());
                for (String invalid : new String[]{"", "0", "-1", "abc", "2147483648"}) {
                    feed.setText(invalid);
                    assertFalse(calculate.isEnabled());
                    assertNotNull(label(panel, "Enter a positive whole number (1–2,147,483,647)."));
                    feed.postActionEvent();
                }
                feed.setText("500");
                assertTrue(calculate.isEnabled());

                ObjectData original = pet(stat(StatType.PET_INSTANCE_ID_STAT, 10),
                        stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                        stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1),
                        stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 0),
                        stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 407));
                CharacterPetsGUI.addPet(original);
                original.status.stats[4].statValue = 408;
                CharacterPetsGUI.addPet(pet(stat(StatType.PET_INSTANCE_ID_STAT, 10),
                        stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 100)));
                frame.setVisible(true);
                assertNotNull(label(panel, "Heal · Level 1"));
                assertNotNull(label(panel, "Feed power: 100"));
                assertNotNull(label(panel, "Items to max: 4"));
                assertNotNull(label(panel, "Fame to max: 60"));

                feed.setText("1000");
                feed.postActionEvent();
                assertNotNull(label(panel, "Items to max: 2"));
                assertNotNull(label(panel, "Fame to max: 30"));
                frame.setVisible(false);
                CharacterPetsGUI.clearPets();
                frame.setVisible(true);
                assertNotNull(label(panel, "Enter the Pet Yard to see your pets."));
            } finally { frame.dispose(); CharacterPetsGUI.clearPets(); }
        });
    }

    private static ObjectData pet(StatData... stats) {
        ObjectData object = new ObjectData();
        object.status = new ObjectStatusData();
        object.status.stats = stats;
        return object;
    }

    private static StatData stat(StatType type, int number) {
        StatData value = new StatData();
        value.statType = type; value.statTypeNum = type.get(); value.statValue = number;
        return value;
    }

    private static JLabel label(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel && text.equals(((JLabel) c).getText())) return (JLabel) c;
            if (c instanceof Container) { JLabel found = label((Container) c, text); if (found != null) return found; }
        }
        return null;
    }

    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) return (JButton) c;
            if (c instanceof Container) { JButton found = button((Container) c, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container) c, type); if (found != null) return found; }
        }
        return null;
    }
}
