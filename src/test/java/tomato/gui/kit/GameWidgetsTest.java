package tomato.gui.kit;

import assets.IdToAsset;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.accessibility.AccessibleContext;
import javax.swing.SwingUtilities;
import org.junit.*;
import static org.junit.Assert.*;

public class GameWidgetsTest {
    private Locale previous;
    @Before public void fix() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void itemSlotDistinguishesEmptyUnknownAndItems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            assertEquals(ItemSlot.State.UNKNOWN, slot.state());
            assertEquals("Slot not captured", slot.getToolTipText());
            slot.setItem(0, "UT");
            assertEquals(ItemSlot.State.EMPTY, slot.state());
            slot.setItem(987_654_321, "UT");
            assertEquals(ItemSlot.State.ITEM, slot.state());
            assertEquals("Unknown item #987654321 · UT", slot.getAccessibleContext().getAccessibleName());
            assertEquals(30, slot.getPreferredSize().width);
        });
    }

    @Test public void itemSlotNamesItsItemOnceAssetsCanWithoutAnotherSetItem() throws Exception {
        int id = 987_654_322;
        Field field = IdToAsset.class.getDeclaredField("objectID"); field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer, IdToAsset> objects = (Map<Integer, IdToAsset>) field.get(null);
        ItemSlot[] slot = new ItemSlot[1];
        List<Object> announced = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            slot[0] = new ItemSlot(24);
            slot[0].getAccessibleContext().addPropertyChangeListener(event -> {
                if (AccessibleContext.ACCESSIBLE_NAME_PROPERTY.equals(event.getPropertyName())) announced.add(event.getNewValue());
            });
            slot[0].setItem(id, "T12");
            assertEquals("Unknown item #987654322 · T12", slot[0].getToolTipText());
            assertEquals("Unknown item #987654322 · T12", slot[0].getAccessibleContext().getAccessibleName());
            slot[0].setItem(id, "T12");
            assertEquals("A refill that reads the same is announced once", List.of("Unknown item #987654322 · T12"), announced);
        });
        // An asset reload publishes names after the slot was filled; nothing calls setItem again.
        IdToAsset previous = objects.put(id, new IdToAsset("", id, "Synthetic Blade", "Synthetic Blade", "", null, "", "", ""));
        try {
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Synthetic Blade · T12", slot[0].getToolTipText());
                assertEquals("The hover tooltip resolves too", "Synthetic Blade · T12",
                    slot[0].getToolTipText(new MouseEvent(slot[0], MouseEvent.MOUSE_MOVED, 0, 0, 4, 4, 0, false)));
                assertEquals("Synthetic Blade · T12", slot[0].getAccessibleContext().getAccessibleName());
                slot[0].setEmpty();
                assertEquals("Empty slot", slot[0].getToolTipText());
                assertEquals("Empty slot", slot[0].getAccessibleContext().getAccessibleName());
                assertEquals(List.of("Unknown item #987654322 · T12", "Empty slot"), announced);
            });
        } finally { if (previous == null) objects.remove(id); else objects.put(id, previous); }
    }


    @Test public void pipMeterClampsAndDescribesProgress() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PipMeter pips = new PipMeter(5);
            pips.setFilled(9);
            assertEquals(5, pips.filled());
            pips.setFilled(3);
            assertEquals("3 of 5", pips.getAccessibleContext().getAccessibleDescription());
        });
    }

    @Test public void statBarKnowsMaxedAndUnknown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatBar bar = new StatBar();
            assertFalse(bar.maxed());
            assertEquals("Not captured", bar.getToolTipText());
            bar.set(20, 25);
            assertEquals("20 of 25", bar.getToolTipText());
            bar.set(75, 75);
            assertTrue(bar.maxed());
            assertEquals("75 of 75, maxed", bar.getAccessibleContext().getAccessibleDescription());
        });
    }

    @Test public void sparklineCopiesItsValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            double[] fame = {10, 30, 20};
            Sparkline line = new Sparkline();
            line.setValues(fame);
            fame[0] = 99;
            assertEquals(10, line.values()[0], 0);
            assertEquals("From 10 to 20, low 10, high 30", line.getAccessibleContext().getAccessibleDescription());
        });
    }
}
