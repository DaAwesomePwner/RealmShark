package tomato.gui.kit;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

public class FilterBarTest {
    @Test public void rebuildingDuringLoadKeepsNewChipsAndFacetsDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FilterBar bar = new FilterBar("loading", k -> null, (k, v) -> {});
            JButton old = new JButton("Old"); old.setEnabled(false);
            bar.drawer(old); bar.setDrawerEnabled(false);
            JButton replacement = new JButton("New");
            bar.drawer(replacement);
            int[] edits = {0};
            bar.setActive(Collections.singletonList(new FilterBar.ActiveFilter("New filter", () -> edits[0]++)), () -> edits[0]++);
            assertFalse(replacement.isEnabled());
            AbstractButton remove = ControlsTest.find(bar, "remove-filter");
            assertFalse(remove.isEnabled()); remove.doClick();
            assertEquals(0, edits[0]);
            assertFalse("Detached control keeps its original disabled state", old.isEnabled());
            bar.setDrawerEnabled(true);
            assertTrue(replacement.isEnabled()); assertTrue(remove.isEnabled());
        });
    }

    @Before public void noMotion() { Motion.systemOverride = false; }
    @After public void restore() { Motion.systemOverride = null; }

    @Test public void activeFiltersBecomeRemovableChipsWithACount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            FilterBar bar = new FilterBar("runs", store::get, store::put);
            bar.search(new JTextField(12)).drawer(new JLabel("Outcome, evidence, dates"));
            List<String> removed = new ArrayList<>(); int[] cleared = {0};
            bar.setActive(Arrays.asList(new FilterBar.ActiveFilter("Completed", () -> removed.add("outcome")),
                new FilterBar.ActiveFilter("Last 7 days", () -> removed.add("dates"))), () -> cleared[0]++);
            AbstractButton filters = ControlsTest.find(bar, "runs-filters");
            assertEquals("Filters · 2", filters.getText());
            assertEquals(2, bar.activeCount());
            ControlsTest.find(bar, "remove-filter").doClick();
            assertEquals(Collections.singletonList("outcome"), removed);
            AbstractButton clear = ControlsTest.find(bar, "runs-clear-filters");
            assertTrue(clear.isVisible());
            clear.doClick();
            assertEquals(1, cleared[0]);
            bar.setActive(Collections.emptyList(), null);
            assertEquals("Filters", filters.getText());
            assertFalse(clear.isVisible());
        });
    }

    @Test public void drawerIsClosedByDefaultAndRemembered() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            JLabel facets = new JLabel("Facets");
            FilterBar bar = new FilterBar("loot", store::get, store::put).drawer(facets);
            assertFalse(bar.drawerOpen());
            assertFalse("The drawer panel is hidden while closed", facets.getParent().isVisible());
            ControlsTest.find(bar, "loot-filters").doClick();
            assertTrue(bar.drawerOpen());
            assertEquals("true", store.get("ui.filters.loot.open"));
            assertTrue(new FilterBar("loot", store::get, store::put).drawer(new JLabel()).drawerOpen());
        });
    }

    @Test public void withoutADrawerTheFiltersToggleIsHidden() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FilterBar bar = new FilterBar("chat", k -> null, (k, v) -> {});
            assertFalse(ControlsTest.find(bar, "chat-filters").isVisible());
            bar.setDrawerOpen(true);
            assertFalse("Cannot open an empty drawer", bar.drawerOpen());
        });
    }

    @Test public void drawerContentCanBeDisabledWhileAQueryLoads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel facets = new JPanel(); JButton apply = new JButton("Apply"); facets.add(apply);
            FilterBar bar = new FilterBar("keypops", k -> null, (k, v) -> {}).drawer(facets);
            bar.setDrawerEnabled(false);
            assertFalse(apply.isEnabled());
            bar.setDrawerEnabled(true);
            assertTrue(apply.isEnabled());
        });
    }

    // ---- Esc closes an open drawer (P6b) ----

    @Test public void escClosesAnOpenDrawerPersistsItAndFocusesTheToggle() throws Exception {
        Map<String, String> store = new HashMap<>();
        JFrame[] frame = new JFrame[1];
        JTextField[] field = new JTextField[1];
        FilterBar[] bar = new FilterBar[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                field[0] = new JTextField(12);
                JPanel facets = new JPanel(); facets.add(new JCheckBox("Completed"));
                bar[0] = new FilterBar("esc", store::get, store::put).search(field[0]).drawer(facets);
                bar[0].setDrawerOpen(true);
                frame[0] = new JFrame("Esc"); frame[0].setContentPane(bar[0]); frame[0].setSize(600, 300); frame[0].setVisible(true);
                field[0].requestFocusInWindow();
            });
            await(() -> field[0].isFocusOwner());
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(bar[0].drawerOpen()); assertEquals("true", store.get("ui.filters.esc.open"));
                KeyEvent esc = escape(field[0]);
                field[0].dispatchEvent(esc);
                assertTrue("Esc is consumed while the drawer is open", esc.isConsumed());
                assertFalse("Esc closes the drawer as the toggle does", bar[0].drawerOpen());
                assertEquals("The closed state persists", "false", store.get("ui.filters.esc.open"));
            });
            AbstractButton toggle = ControlsTest.find(bar[0], "esc-filters");
            await(toggle::isFocusOwner);
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
        }
    }

    @Test public void escWithTheDrawerClosedFallsThroughToAParentBinding() throws Exception {
        Map<String, String> store = new HashMap<>();
        JTextField field = new JTextField(12);
        int[] parentEsc = {0};
        showing(() -> {
            FilterBar bar = new FilterBar("fall", store::get, store::put).search(field).drawer(new JLabel("Facets"));
            JPanel parent = new JPanel(new BorderLayout()); parent.add(bar);
            parent.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "parent-esc");
            parent.getActionMap().put("parent-esc", new AbstractAction() { public void actionPerformed(ActionEvent e) { parentEsc[0]++; } });
            return parent;
        }, root -> {
            FilterBar bar = (FilterBar) ((Container) root).getComponent(0);
            assertFalse(bar.drawerOpen());
            KeyEvent closed = escape(field);
            field.dispatchEvent(closed);
            assertEquals("A closed drawer does not take Esc", 1, parentEsc[0]);
            assertNull("Nothing was written", store.get("ui.filters.fall.open"));
            bar.setDrawerOpen(true);
            KeyEvent open = escape(field);
            field.dispatchEvent(open);
            assertTrue(open.isConsumed());
            assertEquals("An open drawer takes Esc before the parent", 1, parentEsc[0]);
            assertFalse(bar.drawerOpen());
        });
    }

    @Test public void aFocusedFieldsOwnEscBindingWins() throws Exception {
        Map<String, String> store = new HashMap<>();
        JTextField field = new JTextField(12);
        int[] own = {0};
        showing(() -> {
            field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "own-esc");
            field.getActionMap().put("own-esc", new AbstractAction() { public void actionPerformed(ActionEvent e) { own[0]++; } });
            FilterBar bar = new FilterBar("own", store::get, store::put).search(field).drawer(new JLabel("Facets"));
            bar.setDrawerOpen(true);
            return bar;
        }, root -> {
            field.dispatchEvent(escape(field));
            assertEquals(1, own[0]);
            assertTrue("The field's own Esc keeps precedence; the drawer stays open", ((FilterBar) root).drawerOpen());
        });
    }

    // ---- The Filters toggle shows the drawer's state (P6b polish) ----

    @Test public void theFiltersToggleIsSelectedWhileTheDrawerIsOpenAndSaysSo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            FilterBar bar = new FilterBar("state", store::get, store::put).drawer(new JLabel("Facets"));
            AbstractButton toggle = ControlsTest.find(bar, "state-filters");
            assertToggle(toggle, false);
            toggle.doClick();
            assertTrue(bar.drawerOpen());
            assertToggle(toggle, true);
            bar.setActive(Collections.singletonList(new FilterBar.ActiveFilter("Completed", () -> {})), () -> {});
            assertEquals("Filters · 1", toggle.getText());
            assertToggle(toggle, true);
            bar.setDrawerOpen(false);
            assertToggle(toggle, false);
            bar.setDrawerOpen(true);
            bar.drawer(null);
            assertToggle(toggle, false);   // no drawer: nothing is shown
            FilterBar reopened = new FilterBar("state", store::get, store::put).drawer(new JLabel("Facets"));
            assertTrue("The remembered open state", reopened.drawerOpen());
            assertToggle(ControlsTest.find(reopened, "state-filters"), true);
        });
    }

    @Test public void anOpenDrawersToggleLooksPressedInBothThemes() throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (Themes.Variant variant : Themes.Variant.values())
                    for (boolean contrast : new boolean[]{false, true}) {
                        Themes.install(new Themes.Choice(variant, contrast));
                        String theme = variant + (contrast ? " + contrast" : "");
                        FilterBar bar = new FilterBar("look", k -> null, (k, v) -> {}).drawer(new JLabel("Facets"));
                        AbstractButton toggle = ControlsTest.find(bar, "look-filters");
                        BufferedImage closed = paint(toggle);
                        bar.setDrawerOpen(true);
                        BufferedImage open = paint(toggle);
                        int w = closed.getWidth(), y = closed.getHeight() / 2, edge = w - 1 - UIManager.getInt("Component.focusWidth");
                        // Inside the right padding (no text), and the 1 px edge just inside the focus ring (wider under contrast).
                        Color fillClosed = new Color(closed.getRGB(edge - 3, y)), fillOpen = new Color(open.getRGB(edge - 3, y));
                        Color edgeClosed = new Color(closed.getRGB(edge, y)), edgeOpen = new Color(open.getRGB(edge, y));
                        assertEquals(theme + ": the open fill is the selection wash", Tokens.color(Tokens.Role.SELECTION), fillOpen);
                        assertTrue(theme + ": the fill changes clearly " + fillClosed + " → " + fillOpen, distance(fillClosed, fillOpen) >= 12);
                        assertEquals(theme + ": the open edge is the accent", UIManager.getColor("Component.accentColor"), edgeOpen);
                        assertNotEquals(theme + ": the edge changes", edgeClosed, edgeOpen);
                        Color ink = UIManager.getColor("ToggleButton.selectedForeground");   // the ink of a selected segment
                        assertTrue(theme + ": readable text on the wash " + contrast(ink, fillOpen), contrast(ink, fillOpen) >= 4.5);
                    }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
        }
    }

    private static void assertToggle(AbstractButton toggle, boolean open) {
        assertEquals("Selected while the drawer is open", open, toggle.isSelected());
        assertEquals(open ? "Filters shown" : "Filters hidden", toggle.getAccessibleContext().getAccessibleDescription());
        assertEquals("Assistive technology hears the state", open,
            toggle.getAccessibleContext().getAccessibleStateSet().contains(javax.accessibility.AccessibleState.CHECKED));
    }

    private static BufferedImage paint(AbstractButton button) {
        button.setSize(button.getPreferredSize());
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(UIManager.getColor("Panel.background"));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            button.paint(g);
        } finally { g.dispose(); }
        return image;
    }

    private static int distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue());
    }

    /** WCAG 2 contrast ratio. */
    static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Key events reach only showing components (the focus manager drops the rest), so these checks run in a shown frame. */
    private static void showing(java.util.function.Supplier<JComponent> content, java.util.function.Consumer<JComponent> check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Esc");
            try {
                JComponent root = content.get();
                frame.setContentPane(root); frame.setSize(600, 300); frame.setVisible(true);
                check.accept(root);
            } finally { frame.dispose(); }
        });
    }

    private static KeyEvent escape(Component target) {
        return new KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] done = {false};
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for EDT state");
    }
}
