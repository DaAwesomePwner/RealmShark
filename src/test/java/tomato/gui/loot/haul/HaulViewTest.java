package tomato.gui.loot.haul;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.Tokens;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** The haul over synthetic bags (synthetic names only): Compact and Full composition, opening groups, item and run callbacks. */
public class HaulViewTest {
    private static final long T0 = 1_700_000_000_000L;
    static final VisitRef RUN = new VisitRef("00000000-0000-4000-8000-000000000001", "v1");
    private Locale previous;

    @Before public void usLocale() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    static LootFacts.Item ut(int id) { return new LootFacts.Item(id, true, false, false, false, 2, 0); }
    static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }

    /** White (one UT, from a named boss), then two Purple bags of potions. */
    static HaulModel model(HaulModel.Header header) {
        return HaulModel.of(header, List.of(
            new HaulModel.Bag("White", T0 + 300_000, "Synthetic Colossus", List.of(ut(101))),
            new HaulModel.Bag("Purple", T0 + 400_000, null, List.of(potion(102), potion(103))),
            new HaulModel.Bag("Purple", T0 + 500_000, null, List.of(potion(104)))));
    }

    @Test public void compactShowsTheShelfAndTheOpenBagOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            view.show(model(null), null);
            assertFalse(shows(view, "loot-haul-header"));
            assertFalse(shows(view, "loot-haul-hero"));
            assertFalse(shows(view, "loot-haul-empty"));
            assertEquals(List.of("White bag: 1 bag, 1 item", "Purple bag: 2 bags, 3 items"), tooltips(view, "loot-haul-bag"));
            assertEquals("×1", all(view, "loot-haul-bag", JToggleButton.class).get(0).getText());
            assertEquals("The best drop's bag opens first", 0, view.openGroup());
            assertEquals(List.of(List.of(101)), grids(view));
            assertEquals("A bag is 8 slots", 8, all(view, "loot-haul-grid", JPanel.class).get(0).getComponentCount());
            assertTrue(text(view, "loot-haul-grid-caption").startsWith("White bag · Synthetic Colossus · "));
        });
    }

    @Test public void openingAGroupShowsOneGridPerBagNewestFirst() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            view.show(model(null), null);
            List<JToggleButton> tiles = all(view, "loot-haul-bag", JToggleButton.class);
            tiles.get(1).doClick();
            assertEquals(1, view.openGroup());
            assertTrue(tiles.get(1).isSelected());
            assertFalse(tiles.get(0).isSelected());
            assertEquals(List.of(List.of(104), List.of(102, 103)), grids(view));
            assertEquals("Purple bag", text(view, "loot-haul-grid-caption").substring(0, "Purple bag".length()));
            press(tiles.get(0), "ENTER");
            assertEquals("Enter opens a bag", 0, view.openGroup());
        });
    }

    @Test public void itemsReportTheirExactVariantOnClickEnterAndSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            List<String> opened = new ArrayList<>();
            view.onOpenItem(opened::add);
            view.show(model(null), null);
            ItemSlot slot = (ItemSlot) all(view, "loot-haul-grid", JPanel.class).get(0).getComponent(0);
            assertTrue(slot.isFocusable());
            slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
            press(slot, "ENTER");
            press(slot, "SPACE");
            assertEquals(List.of("101/2/0", "101/2/0", "101/2/0"), opened);
        });
    }

    @Test public void anEmptyHaulSaysSoAndBigOrEmptyBagsKeepWholeRowsOfEight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            view.show(HaulModel.of(null, List.of()), null);
            assertTrue(shows(view, "loot-haul-empty"));
            assertEquals(HaulView.EMPTY, text(view, "loot-haul-empty"));
            assertFalse(shows(view, "loot-haul-shelf"));
            assertEquals(-1, view.openGroup());

            List<LootFacts.Item> nine = new ArrayList<>();
            for (int i = 0; i < 9; i++) nine.add(potion(200 + i));
            view.show(HaulModel.of(null, List.of(new HaulModel.Bag(null, T0, null, nine), new HaulModel.Bag("Mystery", T0 + 1, null, List.of()))), null);
            assertFalse(shows(view, "loot-haul-empty"));
            assertEquals(List.of("Bag: 1 bag, 9 items", "Mystery bag: 1 bag, 0 items"), tooltips(view, "loot-haul-bag"));
            assertEquals("Nine items wrap to a second row of 8", 16, all(view, "loot-haul-grid", JPanel.class).get(0).getComponentCount());
            view.openGroup(1);
            assertEquals("An empty bag is 8 empty slots", 8, all(view, "loot-haul-grid", JPanel.class).get(0).getComponentCount());
            assertEquals(List.of(List.of()), grids(view));
        });
    }

    @Test public void fullAddsTheHeaderTheBestDropAndOpenRun() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.FULL);
            List<VisitRef> runs = new ArrayList<>();
            view.onOpenRun(runs::add);
            view.show(model(new HaulModel.Header("Synthetic Halls", 0, "Completed", Tokens.Tone.GOOD, T0, 840_000L, "Wizard #3")), RUN);
            assertTrue(shows(view, "loot-haul-header"));
            assertEquals("Synthetic Halls", text(view, "loot-haul-title"));
            assertTrue(text(view, "loot-haul-facts").startsWith("Wizard #3 · "));
            assertEquals("Completed", text(view, "loot-haul-outcome"));
            assertEquals("4 items in 3 bags · 1 UT · 3 potions", text(view, "loot-haul-tally"));
            assertTrue(shows(view, "loot-haul-hero"));
            assertEquals(101, named(view, "loot-haul-hero-slot", ItemSlot.class).itemId());
            assertEquals("White bag · Synthetic Colossus", text(view, "loot-haul-hero-from"));
            named(view, "loot-haul-open-run", AbstractButton.class).doClick();
            assertEquals(List.of(RUN), runs);

            view.show(model(new HaulModel.Header("Synthetic Halls", 0, null, null, null, null, null)), null);
            assertFalse("No run: no Open run", named(view, "loot-haul-open-run", JComponent.class).isVisible());
            assertFalse("No outcome: no chip", has(view, "loot-haul-outcome"));
            assertFalse("Unknown facts are left out", named(view, "loot-haul-facts", JComponent.class).isVisible());

            view.show(model(null), RUN);
            assertFalse("No header facts: no header", shows(view, "loot-haul-header"));
            assertTrue("The best drop still shows", shows(view, "loot-haul-hero"));
        });
    }

    // ---- helpers ----

    static <T extends Component> List<T> all(Container root, String name, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) found.addAll(all(container, name, type));
        }
        return found;
    }

    static <T extends Component> T named(Container root, String name, Class<T> type) {
        List<T> found = all(root, name, type);
        assertFalse("No " + name, found.isEmpty());
        return found.get(0);
    }

    static boolean has(Container root, String name) { return !all(root, name, Component.class).isEmpty(); }
    static boolean shows(Container root, String name) { return named(root, name, Component.class).isVisible(); }
    static String text(Container root, String name) { return named(root, name, JLabel.class).getText(); }

    static List<String> tooltips(Container root, String name) {
        List<String> tips = new ArrayList<>();
        for (JComponent component : all(root, name, JComponent.class)) tips.add(component.getToolTipText());
        return tips;
    }

    /** The item IDs of each shown grid, in order (empty slots left out). */
    static List<List<Integer>> grids(Container root) {
        List<List<Integer>> result = new ArrayList<>();
        for (JPanel grid : all(root, "loot-haul-grid", JPanel.class)) {
            List<Integer> ids = new ArrayList<>();
            for (Component c : grid.getComponents()) if (c instanceof ItemSlot slot && slot.state() == ItemSlot.State.ITEM) ids.add(slot.itemId());
            result.add(ids);
        }
        return result;
    }

    static void press(JComponent target, String key) {
        Object action = target.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
        assertNotNull("Bound on the focused component: " + key, action);
        target.getActionMap().get(action).actionPerformed(new ActionEvent(target, ActionEvent.ACTION_PERFORMED, null));
    }
}
