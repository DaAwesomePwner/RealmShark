package tomato.gui.kit;

import java.awt.event.KeyEvent;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ContainersTest {
    @Test public void editingAFieldInsideACardDoesNotNavigate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] opens = {0}; JTextField field = new JTextField("Editable");
            Card card = new Card(new DisplayModeModel(k -> null, (k, v) -> {})).body(field).onOpen("Open", () -> opens[0]++);
            field.dispatchEvent(new java.awt.event.MouseEvent(field, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals(0, opens[0]);
        });
    }

    @Before public void noMotion() { Motion.systemOverride = false; }
    @After public void restore() { Motion.systemOverride = null; }

    @Test public void cardOpensFromMouseAndKeyboard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] opened = {0};
            Card card = new Card(new DisplayModeModel(k -> null, (k, v) -> {})).title("Recent runs").body(new JLabel("Shatters"));
            card.onOpen("Open recent runs", () -> opened[0]++);
            assertTrue(card.isFocusable());
            assertEquals("Open recent runs", card.getAccessibleContext().getAccessibleName());
            card.getActionMap().get("open-card").actionPerformed(null);
            assertEquals(1, opened[0]);
            JLabel tipped = new JLabel("3.9k DPS");
            tipped.setToolTipText("Linked recording");
            card.footer(tipped);
            tipped.dispatchEvent(new java.awt.event.MouseEvent(tipped, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals("A click on a child with a tooltip still opens the card", 2, opened[0]);
            assertNotNull(card.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)));
            assertEquals("Recent runs", card.header().title());
        });
    }

    @Test public void evidenceFollowsModeAndCanBeToggled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
            Card card = new Card(mode).title("Pet").evidence("Loaded from char/list at 12:04; family not supplied.");
            assertFalse(card.evidenceShown());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(card.evidenceShown());
            AbstractButton toggle = ControlsTest.find(card, "card-evidence");
            toggle.doClick();
            assertFalse(card.evidenceShown());
            assertEquals("Show evidence", toggle.getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void statTileShowsHonestStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatTile tile = new StatTile("Fame today");
            assertEquals("tile-fame-today", tile.getName());
            tile.setValue(DisplayValue.unknown("No fame samples yet today"), null);
            assertEquals("Fame today: —", tile.getAccessibleContext().getAccessibleName());
            tile.setValue(DisplayValue.known("+1,480", "Fame samples"), "1,050 / hour");
            assertEquals("Fame today: +1,480, 1,050 / hour", tile.getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void collapsibleRemembersItsState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            JLabel content = new JLabel("Timeline events");
            Collapsible section = new Collapsible("recap-timeline", "Timeline", content, false, store::get, store::put);
            assertFalse(section.expanded());
            assertFalse(content.isVisible());
            section.toggle().doClick();
            assertTrue(section.expanded());
            assertTrue(content.isVisible());
            assertEquals("true", store.get(Collapsible.PREFIX + "recap-timeline"));
            assertTrue(new Collapsible("recap-timeline", "Timeline", new JLabel(), false, store::get, store::put).expanded());
        });
    }

    @Test public void emptyStateOffersTheNextAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KitButton start = KitButton.primary("Start capture");
            EmptyState empty = new EmptyState("See your character here", "Start capture and enter the game.", start);
            assertEquals("empty-state", empty.getName());
            assertTrue(SwingUtilities.isDescendingFrom(start, empty));
            assertEquals("See your character here", empty.getAccessibleContext().getAccessibleName());
        });
    }
}
