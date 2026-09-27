package tomato.gui.kit;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.Test;
import static org.junit.Assert.*;

/** The shared painted tile list: width-driven wrapping, in-place updates by key, Enter/Space/double-click open, per-tile names. */
public class TileListTest {
    private static final int WIDE = 100, TALL = 40;

    /** A tile keyed by {@code key}; {@code label} is what changes between two updates of the same tile. */
    private record Item(String key, String label) {}

    /** Paints nothing; one component for every cell, like the card renderers. Its preferred size is the cell. */
    private static final class Renderer extends JComponent implements ListCellRenderer<Item> {
        int width = WIDE, height = TALL;
        @Override public Dimension getPreferredSize() { return new Dimension(width, height); }
        @Override public Component getListCellRendererComponent(JList<? extends Item> list, Item value, int index, boolean selected, boolean focused) {
            return this;
        }
    }

    private static List<Item> items(int count, String label) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add(new Item("k" + i, label + " " + i));
        return items;
    }
    private static TileList<Item> list(Renderer renderer) { return new TileList<>("tiles", renderer, Item::key, item -> "Tile " + item.label()); }

    @Test public void wrapsToThreeTwoAndOneColumnsAsTheWidthShrinks() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Renderer renderer = new Renderer();
            TileList<Item> list = list(renderer);
            list.setItems(items(5, "Item"));
            assertEquals("tiles", list.getName());
            assertEquals("A wrapping grid", JList.HORIZONTAL_WRAP, list.getLayoutOrientation());
            assertEquals("Fixed cells from the renderer's preferred size", new Dimension(WIDE, TALL),
                new Dimension(list.getFixedCellWidth(), list.getFixedCellHeight()));
            JPanel host = new JPanel(new BorderLayout());
            host.add(list);
            host.setSize(250, 1_000);
            assertEquals("Before its first layout the list wraps to its nearest sized ancestor", new Dimension(2 * WIDE, 3 * TALL), list.getPreferredSize());
            for (int[] expected : new int[][] {{350, 3}, {250, 2}, {150, 1}, {60, 1}}) {
                host.setSize(expected[0], 1_000);
                host.doLayout();
                int columns = expected[1], rows = (5 + columns - 1) / columns;
                assertEquals(expected[0] + " px wide: " + columns + " columns, the height follows", new Dimension(columns * WIDE, rows * TALL),
                    list.getPreferredSize());
                assertEquals("The page scrolls, so the list never asks for less than it needs", list.getPreferredSize(), list.getMinimumSize());
                paint(list); // JList notices a new width when it paints
                Rectangle last = list.getCellBounds(4, 4);
                assertEquals("The fifth tile sits in row " + rows + " at " + expected[0] + " px", (rows - 1) * TALL, last.y);
                assertEquals((4 % columns) * WIDE, last.x);
            }
            renderer.width = 120; renderer.height = 50;
            list.setFont(list.getFont().deriveFont(18f)); // a font change re-measures the renderer
            assertEquals(new Dimension(120, 50), new Dimension(list.getFixedCellWidth(), list.getFixedCellHeight()));
            list.setFixedCellHeight(22); // ContentStyle.refreshFonts sizes text lists by their line height
            assertEquals("A tile keeps the height its renderer needs", 50, list.getFixedCellHeight());
        });
    }

    @Test public void anUpdateWithEqualKeysChangesInPlaceKeepingSelectionAndScroll() throws Exception {
        JFrame frame = new JFrame("Tile list update - synthetic validation");
        try {
            TileList<Item>[] list = listArray();
            JScrollPane[] scroll = new JScrollPane[1];
            SwingUtilities.invokeAndWait(() -> {
                list[0] = list(new Renderer());
                list[0].setItems(items(30, "Before"));
                JPanel page = new JPanel(new BorderLayout());
                page.add(list[0], BorderLayout.NORTH);
                scroll[0] = new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                frame.setContentPane(scroll[0]);
                frame.setSize(360, 200);
                frame.setVisible(true);
            });
            SwingUtilities.invokeAndWait(() -> {
                ui.UiTestLayout.settle(frame);
                assertTrue("Sanity: the tiles overflow the viewport, so the page scrolls", page(scroll[0]).getHeight() > scroll[0].getViewport().getHeight());
                list[0].setSelectedIndex(25);
                scroll[0].getViewport().setViewPosition(new Point(0, 200));
                int[] contents = {0}, structure = {0};
                list[0].getModel().addListDataListener(new ListDataListener() {
                    @Override public void intervalAdded(ListDataEvent e) { structure[0]++; }
                    @Override public void intervalRemoved(ListDataEvent e) { structure[0]++; }
                    @Override public void contentsChanged(ListDataEvent e) { contents[0]++; }
                });
                list[0].setItems(items(30, "After"));
                ui.UiTestLayout.settle(frame);
                assertEquals("Equal keys: one change event", 1, contents[0]);
                assertEquals("…and no removal or insertion", 0, structure[0]);
                assertEquals("The selection stays on its tile", 25, list[0].getSelectedIndex());
                assertEquals("…which now shows the new values", "After 25", list[0].getSelectedValue().label());
                assertEquals("No scroll jump", new Point(0, 200), scroll[0].getViewport().getViewPosition());
                assertEquals(items(30, "After"), list[0].items());
                list[0].setItems(new ArrayList<>(items(30, "After")));
                assertEquals("An equal list changes nothing", 1, contents[0]);
                List<Item> reordered = new ArrayList<>(items(30, "After"));
                reordered.add(0, reordered.remove(29));
                list[0].setItems(reordered);
                assertEquals("New keys: one removal and one insertion", 2, structure[0]);
                assertEquals("The selection follows its tile's key, not the index", "k25", list[0].getSelectedValue().key());
                list[0].selectKey("k3", false);
                assertEquals("k3", list[0].getSelectedValue().key());
                list[0].selectKey("missing", false);
                assertTrue("An unknown key clears the selection", list[0].isSelectionEmpty());
            });
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test public void enterSpaceAndDoubleClickOpenAndASingleClickOnlySelects() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TileList<Item> list = list(new Renderer());
            List<Item> items = items(5, "Item");
            list.setItems(items);
            list.setSize(350, 200);
            paint(list);
            List<Item> opened = new ArrayList<>();
            list.onOpen(opened::add);
            for (String key : new String[] {"ENTER", "SPACE"})
                assertEquals(key + " opens the selected tile", TileList.OPEN, list.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key)));
            list.getActionMap().get(TileList.OPEN).actionPerformed(null);
            assertTrue("Nothing selected, nothing opens", opened.isEmpty());
            list.setSelectedIndex(1);
            list.getActionMap().get(TileList.OPEN).actionPerformed(null);
            assertEquals(List.of(items.get(1)), opened);
            Rectangle cell = list.getCellBounds(3, 3);
            for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED})
                list.dispatchEvent(mouse(list, id, cell.x + cell.width / 2, cell.y + cell.height / 2, 1));
            assertEquals("A single click selects", 3, list.getSelectedIndex());
            assertEquals("…and only selects", 1, opened.size());
            list.dispatchEvent(mouse(list, MouseEvent.MOUSE_CLICKED, cell.x + cell.width / 2, cell.y + cell.height / 2, 2));
            assertEquals("A double-click opens the tile under the pointer", List.of(items.get(1), items.get(3)), opened);
            list.dispatchEvent(mouse(list, MouseEvent.MOUSE_CLICKED, 340, 190, 2));
            assertEquals("A double-click below the last tile opens nothing", 2, opened.size());
            list.onOpen("open-character", opened::add);
            assertEquals("A caller may keep its own action name", "open-character", list.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER")));
            assertNull(list.getActionMap().get(TileList.OPEN));
            list.getActionMap().get("open-character").actionPerformed(null);
            assertEquals(items.get(3), opened.get(2));
        });
    }

    @Test public void eachTileHasItsOwnAccessibleName() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TileList<Item> list = list(new Renderer());
            list.getAccessibleContext().setAccessibleName("Tiles");
            list.setItems(items(3, "Item"));
            assertEquals("The list's own name is the caller's", "Tiles", list.getAccessibleContext().getAccessibleName());
            assertEquals(3, list.getAccessibleContext().getAccessibleChildrenCount());
            for (int i = 0; i < 3; i++) assertEquals("Tile Item " + i, child(list, i));
            list.setItems(items(3, "Updated"));
            assertEquals("Names follow an in-place update", "Tile Updated 2", child(list, 2));
        });
    }

    @SuppressWarnings("unchecked")
    private static TileList<Item>[] listArray() { return (TileList<Item>[]) new TileList<?>[1]; }
    private static Component page(JScrollPane scroll) { return scroll.getViewport().getView(); }
    private static void paint(JComponent component) {
        BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
    }
    private static String child(JList<?> list, int index) {
        return list.getAccessibleContext().getAccessibleChild(index).getAccessibleContext().getAccessibleName();
    }
    private static MouseEvent mouse(JList<?> list, int id, int x, int y, int count) {
        return new MouseEvent(list, id, System.currentTimeMillis(), id == MouseEvent.MOUSE_PRESSED ? MouseEvent.BUTTON1_DOWN_MASK : 0, x, y, count, false, MouseEvent.BUTTON1);
    }
}
