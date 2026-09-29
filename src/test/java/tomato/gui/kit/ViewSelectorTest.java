package tomato.gui.kit;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import javax.accessibility.Accessible;
import javax.swing.*;
import javax.swing.plaf.basic.ComboPopup;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

/**
 * The kit's view selector (spec §6.4 Explore): Simple views, then an "Analyst" header and the Analyst views, then a "Current view"
 * header and a restored view no list has. Headers are shown but never selected by the mouse, the keys or select(); only the user's
 * choices notify; each item has its tooltip; the accessible name is "View"; it follows the theme and the font.
 */
public class ViewSelectorTest {
    enum View {
        ITEMS("All Items"), POTIONS("Stat Potions"), UTS("UTs"), OCCURRENCES("Item occurrences"), COHORTS("A/B cohorts"), SOURCES("Loot by source");
        final String title;
        View(String title) { this.title = title; }
    }

    private static final List<View> SIMPLE = List.of(View.ITEMS, View.POTIONS, View.UTS), ANALYST = List.of(View.OCCURRENCES, View.COHORTS);
    private Font previous;

    @Before public void remember() throws Exception { SwingUtilities.invokeAndWait(() -> previous = ContentStyle.body()); }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.setBodyFont(previous);
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
        });
    }

    @Test public void analystViewsFollowAHeaderThatIsNeverSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewSelector<View> selector = selector();
            List<View> changes = new ArrayList<>();
            selector.onChange(changes::add);
            selector.setItems(SIMPLE, ANALYST, null);
            assertEquals(List.of("All Items", "Stat Potions", "UTs", "[Analyst]", "Item occurrences", "A/B cohorts"), rows(selector));
            assertEquals("The first view is selected", View.ITEMS, selector.selected());
            box(selector).setSelectedIndex(3); // what a click on the header row in the popup does
            assertEquals("A header is never selected", View.ITEMS, selector.selected());
            selector.select(null);
            selector.select(View.SOURCES);
            assertEquals("A null or unlisted view leaves the selection as it is", View.ITEMS, selector.selected());
            assertTrue(changes.isEmpty());
            selector.setItems(SIMPLE, List.of(), null);
            assertEquals("No Analyst views, no header", List.of("All Items", "Stat Potions", "UTs"), rows(selector));
        });
    }

    @Test public void aRestoredViewNoListHasStaysShownAsTheCurrentView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewSelector<View> selector = selector();
            List<View> changes = new ArrayList<>();
            selector.onChange(changes::add);
            selector.setItems(SIMPLE, List.of(), View.COHORTS);
            assertEquals(List.of("All Items", "Stat Potions", "UTs", "[Current view]", "A/B cohorts"), rows(selector));
            selector.select(View.COHORTS);
            assertEquals(View.COHORTS, selector.selected());
            selector.setItems(SIMPLE, ANALYST, View.COHORTS);
            assertEquals("A listed view is not repeated as the current view",
                List.of("All Items", "Stat Potions", "UTs", "[Analyst]", "Item occurrences", "A/B cohorts"), rows(selector));
            assertEquals("A rebuild keeps a view it still lists", View.COHORTS, selector.selected());
            selector.setItems(List.of(), List.of(), View.UTS);
            assertEquals(List.of("[Current view]", "UTs"), rows(selector));
            assertEquals("A view no longer listed falls back to the first listed one", View.UTS, selector.selected());
            selector.setItems(SIMPLE, ANALYST, null);
            assertEquals(View.UTS, selector.selected());
            assertTrue("Rebuilding never notifies", changes.isEmpty());
        });
    }

    @Test public void onlyTheUsersChoicesNotify() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewSelector<View> selector = selector();
            List<View> changes = new ArrayList<>();
            selector.onChange(changes::add);
            selector.setItems(SIMPLE, ANALYST, null);
            JComboBox<?> box = box(selector);
            selector.select(View.UTS);
            assertEquals(View.UTS, selector.selected());
            assertTrue("select() is silent", changes.isEmpty());
            box.setSelectedItem(View.OCCURRENCES); // a pick from the popup
            assertEquals(List.of(View.OCCURRENCES), changes);
            box.setSelectedItem(View.OCCURRENCES);
            box.setSelectedIndex(3);
            assertEquals("Picking the same view or a header changes nothing", List.of(View.OCCURRENCES), changes);
            selector.select(View.UTS);
            assertTrue("Typing 'a' finds a title", box.selectWithKeyChar('a'));
            assertEquals("Typing matches titles and skips the Analyst header", View.COHORTS, selector.selected());
            assertEquals(List.of(View.OCCURRENCES, View.COHORTS), changes);
        });
    }

    @Test public void itemsCarryTheirTooltipsAndTheSelectorIsNamedView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewSelector<View> selector = selector();
            selector.setItems(SIMPLE, ANALYST, null);
            JComboBox<?> box = box(selector);
            assertSame(box, selector.component());
            assertEquals("loot-views", box.getName());
            assertEquals("View", box.getAccessibleContext().getAccessibleName());
            assertEquals("Shows stat potions", ((JComponent) render(box, new JList<>(), 1)).getToolTipText());
            assertNull("No tooltip text, no tooltip", ((JComponent) render(box, new JList<>(), 2)).getToolTipText());
            assertEquals("The closed selector shows the chosen view's tooltip", "Shows all items", box.getToolTipText());
            selector.select(View.UTS);
            assertNull(box.getToolTipText());
            selector.select(View.COHORTS);
            assertEquals("Shows a/b cohorts", box.getToolTipText());
        });
    }

    @Test public void navigationKeysStepOverHeadersAndStayPutAtEitherEnd() throws Exception {
        JFrame[] frame = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                ViewSelector<View> selector = selector();
                List<View> changes = new ArrayList<>();
                selector.onChange(changes::add);
                // [Analyst] Item occurrences, A/B cohorts, [Current view] Loot by source: a header at either end of a step.
                selector.setItems(List.of(), ANALYST, View.SOURCES);
                JComboBox<?> box = box(selector);
                frame[0] = new JFrame("view-selector");
                frame[0].add(box);
                frame[0].pack();
                frame[0].setVisible(true);
                assertEquals(View.OCCURRENCES, selector.selected());
                box.setPopupVisible(true);
                JList<?> list = popupList(box);
                Object[][] steps = {{KeyEvent.VK_UP, View.OCCURRENCES}, {KeyEvent.VK_DOWN, View.COHORTS}, {KeyEvent.VK_DOWN, View.SOURCES},
                    {KeyEvent.VK_DOWN, View.SOURCES}, {KeyEvent.VK_UP, View.COHORTS}, {KeyEvent.VK_HOME, View.OCCURRENCES},
                    {KeyEvent.VK_END, View.SOURCES}, {KeyEvent.VK_PAGE_UP, View.OCCURRENCES}, {KeyEvent.VK_PAGE_DOWN, View.SOURCES}};
                for (Object[] step : steps) {
                    String key = KeyEvent.getKeyText((Integer) step[0]);
                    press(box, (Integer) step[0]);
                    assertEquals(key, step[1], selector.selected());
                    assertEquals(key + ": the popup list never rests on a header", box.getSelectedIndex(), list.getSelectedIndex());
                }
                assertEquals(List.of(View.COHORTS, View.SOURCES, View.COHORTS, View.OCCURRENCES, View.SOURCES, View.OCCURRENCES, View.SOURCES), changes);
                // The mouse: hovering the Current view header selects it in the list, and a click there changes nothing.
                list.setSelectedIndex(3);
                Rectangle cell = list.getCellBounds(3, 3);
                for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED})
                    list.dispatchEvent(new MouseEvent(list, id, System.currentTimeMillis(), 0, cell.x + 4, cell.y + cell.height / 2, 1, false, MouseEvent.BUTTON1));
                assertEquals(View.SOURCES, selector.selected());
                assertEquals(7, changes.size());
                box.setPopupVisible(false);
                // Closed, the bindings some platforms use for the arrows step over headers too.
                for (View expected : new View[] {View.COHORTS, View.OCCURRENCES, View.OCCURRENCES}) {
                    act(box, "selectPrevious2");
                    assertEquals(expected, selector.selected());
                }
                act(box, "selectNext2");
                act(box, "selectNext2");
                assertEquals(View.SOURCES, selector.selected());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
        }
    }

    @Test public void headersAndItemsFollowTheThemeAndTheFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewSelector<View> selector = selector();
            JComboBox<?> box = box(selector);
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                SwingUtilities.updateComponentTreeUI(box);
                int smallHeight = 0;
                for (int size : new int[] {13, 18}) {
                    ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size));
                    ContentStyle.refreshFonts(box);
                    selector.setItems(List.of(), ANALYST, View.SOURCES);
                    String at = variant + " at font " + size;
                    assertEquals(at, List.of("[Analyst]", "Item occurrences", "A/B cohorts", "[Current view]", "Loot by source"), rows(selector));
                    assertEquals(at, size, box.getFont().getSize2D(), 0.01f);
                    JList<?> list = popupList(box);
                    assertEquals(at + ": items use the selector's font", size, render(box, list, 1).getFont().getSize2D(), 0.01f);
                    Component header = render(box, list, 3);
                    assertEquals(at + ": headers are captions", size * 12f / 13f, header.getFont().getSize2D(), 0.01f);
                    assertTrue(at, header.getFont().isBold());
                    assertEquals(at + ": headers are muted in this theme", Tokens.color(Tokens.Role.TEXT_MUTED), header.getForeground());
                    int height = box.getPreferredSize().height;
                    if (smallHeight == 0) smallHeight = height;
                    else assertTrue(at + ": the selector grows with the font", height > smallHeight);
                    // The key handling survives the theme change: Home lands on the Analyst header and steps over it.
                    selector.select(View.COHORTS);
                    press(box, KeyEvent.VK_HOME);
                    assertEquals(at, View.OCCURRENCES, selector.selected());
                }
            }
        });
    }

    private static ViewSelector<View> selector() {
        return new ViewSelector<>("loot-views", view -> view.title,
            view -> view == View.UTS ? null : "Shows " + view.title.toLowerCase(Locale.ROOT));
    }

    private static JComboBox<?> box(ViewSelector<View> selector) { return (JComboBox<?>) selector.component(); }

    @SuppressWarnings("unchecked")
    private static Component render(JComboBox<?> box, JList<?> list, int row) {
        ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) box.getRenderer();
        return renderer.getListCellRendererComponent((JList<Object>) list, box.getItemAt(row), row, false, false);
    }

    /** Each row as the popup paints it: an item's title, or "[title]" for a header. */
    private static List<String> rows(ViewSelector<View> selector) {
        JComboBox<?> box = box(selector);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < box.getItemCount(); i++) {
            String text = ((JLabel) render(box, new JList<>(), i)).getText();
            rows.add(box.getItemAt(i) instanceof View ? text : "[" + text + "]");
        }
        return rows;
    }

    private static JList<?> popupList(JComboBox<?> box) {
        Accessible popup = box.getUI().getAccessibleChild(box, 0);
        assertTrue("The look and feel's popup", popup instanceof ComboPopup);
        return ((ComboPopup) popup).getList();
    }

    /** A key press as Swing dispatches it: the look and feel's binding, then the action it names. */
    private static void press(JComboBox<?> box, int keyCode) {
        KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, 0);
        Object key = box.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke);
        if (key == null) key = box.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        assertNotNull("A binding for " + KeyEvent.getKeyText(keyCode), key);
        act(box, key.toString());
    }

    private static void act(JComboBox<?> box, String name) {
        Action action = box.getActionMap().get(name);
        assertNotNull(name, action);
        action.actionPerformed(new ActionEvent(box, ActionEvent.ACTION_PERFORMED, name));
    }
}
