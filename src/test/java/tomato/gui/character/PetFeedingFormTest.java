package tomato.gui.character;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.formdev.flatlaf.FlatLightLaf;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.TileList;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import util.PropertiesManager;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.data.enums.StatType;

import javax.swing.*;
import java.awt.*;

import static org.junit.Assert.*;

public class PetFeedingFormTest {
    /** The feeding calculator's drawer remembers whether it is open; every test starts from the default and restores the user's value. */
    private static final String FEEDING = Collapsible.PREFIX + "pets-feeding";
    private String feeding;

    @Before public void defaultFeedingDrawer() { feeding = PropertiesManager.getProperty(FEEDING); PropertiesManager.setProperties(FEEDING, ""); }
    @After public void restoreFeedingDrawer() { PropertiesManager.setProperties(FEEDING, feeding == null ? "" : feeding); }

    @Test public void invalidAndLockedLabelsFollowThemeWithoutNewCaptureData() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel original = UIManager.getLookAndFeel();
            JFrame frame = new JFrame();
            try {
                UIManager.setLookAndFeel(new VioletTheme());
                CharacterPetsGUI panel = new CharacterPetsGUI(null);
                expandFeeding(panel);
                frame.setContentPane(panel);
                frame.setSize(640, 600);
                CharacterPetsGUI.addPet(pet(stat(StatType.PET_INSTANCE_ID_STAT, 1), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30)));
                frame.setVisible(true);
                feedField(panel).setText("0");
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
            expandFeeding(panel);
            JFrame frame = new JFrame();
            frame.setContentPane(panel);
            frame.setSize(640, 600);
            try {
                JTextField feed = feedField(panel);
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
                assertTrue(text(panel).contains("Captured points: 100"));
                assertTrue("Spec §1: estimates show ≈", text(panel).contains("Items to max: ≈ 4"));
                assertTrue("Spec §1: estimates show ≈", text(panel).contains("Fame to max: ≈ 60"));
                assertTrue(text(panel).contains("Next level items:"));
                assertTrue("The next level's items and fame are estimates too", text(panel).contains("Next level items: ≈ ") && text(panel).contains(" · Fame: ≈ "));
                assertFalse("A captured input is never an estimate", text(panel).contains("Captured points: ≈"));
                assertFalse("Unknown is never an estimate", text(panel).contains("≈ Not captured"));

                feed.setText("1000");
                feed.postActionEvent();
                assertTrue("Spec §1: estimates show ≈", text(panel).contains("Items to max: ≈ 2"));
                assertTrue("Spec §1: estimates show ≈", text(panel).contains("Fame to max: ≈ 30"));
                frame.setVisible(false);
                CharacterPetsGUI.clearPets();
                frame.setVisible(true);
                assertNotNull(label(panel, "Enter the Pet Yard to see your pets."));
            } finally { frame.dispose(); CharacterPetsGUI.clearPets(); }
        });
    }

    @Test public void theFeedingDrawerStartsCollapsedRemembersItsStateAndComputesForTheSelectedCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterPetsGUI panel = new CharacterPetsGUI(null);
            JFrame frame = new JFrame();
            frame.setContentPane(panel);
            frame.setSize(900, 700);
            try {
                Collapsible drawer = named(panel, "pets-feeding", Collapsible.class);
                AbstractButton toggle = named(panel, "collapsible-pets-feeding", AbstractButton.class);
                assertNotNull(drawer); assertNotNull(toggle);
                assertFalse("The feeding calculator starts collapsed", drawer.expanded());
                assertEquals("Feeding calculator", toggle.getText());
                frame.setVisible(true);
                assertTrue("No pet yet: the invitation shows", named(panel, "pet-empty", EmptyState.class).isVisible());
                assertEquals("No pets yet", named(panel, "pet-empty", EmptyState.class).getAccessibleContext().getAccessibleName());
                assertFalse(named(panel, "pet-cards", TileList.class).isVisible());

                CharacterPetsGUI.addPet(pet(101, stat(StatType.PET_INSTANCE_ID_STAT, 10), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                    stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 100), stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 407)));
                CharacterPetsGUI.addPet(pet(102, stat(StatType.PET_INSTANCE_ID_STAT, 20), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                    stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1), stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 408)));
                feedField(panel).postActionEvent(); // recalculates from fresh observations now instead of at the next 500 ms poll
                TileList<?> cards = named(panel, "pet-cards", TileList.class);
                assertTrue(cards.isVisible());
                assertFalse(named(panel, "pet-empty", EmptyState.class).isVisible());
                assertEquals(2, cards.items().size());
                assertEquals("Pets", cards.getAccessibleContext().getAccessibleName());
                assertTrue("Nothing selected: the calculator uses the first card's pet", text(panel).contains("Captured points: 100"));
                assertNotNull(label(panel, "Heal · Level 1"));

                cards.selectKey("pet:20", true);
                assertTrue("It follows the selected card", text(panel).contains("Ability points not captured"));
                assertNotNull(label(panel, "Magic heal · Level 1"));
                assertFalse(text(panel).contains("Captured points: 100"));

                toggle.doClick();
                assertTrue(drawer.expanded());
                assertEquals("The drawer remembers it is open", "true", PropertiesManager.getProperty(FEEDING));
                toggle.doClick();
                assertFalse(drawer.expanded());
                cards.getActionMap().get(TileList.OPEN).actionPerformed(null);
                assertTrue("Enter on a pet card opens its feeding calculator", drawer.expanded());
            } finally { frame.dispose(); CharacterPetsGUI.clearPets(); }
        });
    }

    /** A fully fed ability (level at the captured maximum) needs nothing: its zeros are exact, never "≈ 0". */
    @Test public void aFullyFedAbilityShowsExactZeros() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterPetsGUI panel = new CharacterPetsGUI(null);
            expandFeeding(panel);
            JFrame frame = new JFrame();
            frame.setContentPane(panel);
            frame.setSize(640, 600);
            try {
                CharacterPetsGUI.addPet(pet(stat(StatType.PET_INSTANCE_ID_STAT, 30), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                    stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 30), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 2_100), stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 407)));
                feedField(panel).postActionEvent();
                frame.setVisible(true);
                String text = text(panel);
                assertTrue(text, text.contains("Fully fed at captured maximum level."));
                assertTrue(text, text.contains("Next level items: 0 · Fame: 0"));
                assertTrue(text, text.contains("Items to max: 0 · Fame to max: 0"));
            } finally { frame.dispose(); CharacterPetsGUI.clearPets(); }
        });
    }

    /** The feeding calculator is in a drawer that starts collapsed: open it as the user does before reading its estimates. */
    private static void expandFeeding(Container panel) {
        Collapsible drawer = named(panel, "pets-feeding", Collapsible.class);
        assertNotNull("The feeding calculator drawer", drawer);
        if (!drawer.expanded()) named(panel, "collapsible-pets-feeding", AbstractButton.class).doClick();
        assertTrue(drawer.expanded());
    }

    private static ObjectData pet(StatData... stats) { return pet(0, stats); }
    private static ObjectData pet(int objectId, StatData... stats) {
        ObjectData object = new ObjectData();
        object.status = new ObjectStatusData();
        object.status.objectId = objectId;
        object.status.stats = stats;
        return object;
    }
    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T found = named((Container) c, name, type); if (found != null) return found; }
        }
        return null;
    }
    private static String text(Container root) {
        StringBuilder result = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea) result.append(((JTextArea)child).getText());
            if (child instanceof Container) result.append(text((Container)child));
        }
        return result.toString();
    }
    private static JTextField feedField(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField && "pet-feed-power".equals(child.getName())) return (JTextField)child;
            if (child instanceof Container) { JTextField result = feedField((Container)child); if (result != null) return result; }
        }
        return null;
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
