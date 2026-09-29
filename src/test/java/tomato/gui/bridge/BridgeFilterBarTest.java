package tomato.gui.bridge;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.bridge.*;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.FormattingTestSupport;
import tomato.gui.modern.TestPages;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P6b Task 8: Bridge Review's one filter row per tab (Review, Logs), the drawer and its chips, Reset and Clear, the ⋯ actions and
 * the button hierarchy. A synthetic in-process transport only: no endpoint is contacted and nothing is delivered.
 */
public class BridgeFilterBarTest {
    static final String[] KEYS = {"ui.filters.bridge-review.open", "ui.filters.bridge-logs.open", CustomizableTabs.PREFIX + "bridge", DisplayModeModel.KEY};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("p6b-bridge");
    private final Map<String, String> before = new HashMap<>();
    private DisplayModeModel.Mode applicationMode;

    @Before public void isolate() throws Exception {
        for (String key : KEYS) before.put(key, PropertiesManager.getProperty(key));
        SwingUtilities.invokeAndWait(() -> applicationMode = DisplayModeModel.application().mode());
        for (String key : KEYS) if (!DisplayModeModel.KEY.equals(key)) PropertiesManager.setProperties(key, "");
    }
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(applicationMode));
        for (String key : KEYS) PropertiesManager.setProperties(key, before.get(key) == null ? "" : before.get(key));
    }

    @Test public void reviewAndLogsEachKeepOneFilterRowInTheShell() throws Exception {
        // The application's mode, so the shell's Simple/Analyst toggle in the captures matches the tables.
        DisplayModeModel mode = DisplayModeModel.application();
        edt(() -> { mode.set(DisplayModeModel.Mode.SIMPLE); return null; });
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode));
            JComponent root = edt(() -> TestPages.shell("bridge-review", panel));
            try {
                edt(() -> { evidence.show(root, "Bridge review filter row", 1240, 800, 13); return null; });
                evidence.settle();
                edt(() -> {
                    evidence.capture("bridge-review-simple");
                    FilterBar bar = bar(panel, "bridge-review");
                    assertTrue("The Review bar shows", bar.isShowing());
                    assertFalse("The drawer starts closed", bar.drawerOpen());
                    System.out.println("Bridge review filter bar width=" + bar.getWidth());
                    assertTrue("Measured at the shell's content width (" + bar.getWidth() + ")", bar.getWidth() > 900 && bar.getWidth() < 1100);
                    FilterBarAssert.assertOneRow(bar);
                    JTextField search = named(panel, "bridge-search", JTextField.class);
                    AbstractButton reset = named(panel, "bridge-reset", AbstractButton.class);
                    assertSame("[Search][Reset filters] fill the search slot", bar.searchSlot(), search.getParent());
                    assertSame(bar.searchSlot(), reset.getParent());
                    assertEquals("Reset filters", reset.getText());
                    assertTrue(search.isShowing()); assertTrue(reset.isShowing());
                    assertTrue("The Filters toggle shows", named(bar, "bridge-review-filters", AbstractButton.class).isShowing());
                    assertTrue("⋯ shows", more(panel, "bridge-review").isShowing());
                    for (String facet : FACETS) {
                        JComboBox<?> combo = named(panel, facet, JComboBox.class);
                        assertTrue(facet + " lives in the drawer", SwingUtilities.isDescendingFrom(combo, bar.drawerContent()));
                        assertFalse(facet + " is hidden while the drawer is closed", combo.isShowing());
                    }
                    // Nothing interactive between the filter row and the table; the alert action sits above the details pane.
                    JTable table = named(panel, "bridge-review-table", JTable.class);
                    JScrollPane tableScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
                    int barBottom = y(bar, panel) + bar.getHeight(), tableTop = y(tableScroll, panel);
                    assertTrue("The table follows the filter row (" + barBottom + " ≤ " + tableTop + ")", barBottom <= tableTop);
                    for (JComponent control : controls(panel))
                        if (control.isShowing() && !SwingUtilities.isDescendingFrom(control, bar) && !SwingUtilities.isDescendingFrom(control, tableScroll)) {
                            int at = y(control, panel);
                            assertFalse("No control between the row and the table: " + describe(control), at >= barBottom && at < tableTop);
                        }
                    JButton alert = named(panel, "bridge-alert-draft", JButton.class);
                    JScrollPane details = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, named(panel, "bridge-details", JTextArea.class));
                    assertTrue("The alert action shows", alert.isShowing());
                    assertTrue("…below the table", y(alert, panel) >= y(tableScroll, panel) + tableScroll.getHeight());
                    assertTrue("…right above the details pane", y(alert, panel) + alert.getHeight() <= y(details, panel)
                        && y(details, panel) - (y(alert, panel) + alert.getHeight()) < 3 * Tokens.S);
                    // Every column shows at 1240×800 without a sideways scroll, and no Outcome label is cut.
                    assertTrue("The review columns fit the page (" + table.getWidth() + " ≤ " + tableScroll.getViewport().getWidth() + ")",
                        table.getWidth() <= tableScroll.getViewport().getWidth());
                    for (int row = 0; row < table.getRowCount(); row++) {
                        JLabel outcome = (JLabel) table.prepareRenderer(table.getCellRenderer(row, 6), row, 6);
                        assertTrue(outcome.getText() + " fits its column", outcome.getPreferredSize().width <= table.getColumnModel().getColumn(6).getWidth());
                    }
                    for (int column = 0; column < table.getColumnCount(); column++) {
                        javax.swing.table.TableColumn shown = table.getColumnModel().getColumn(column);
                        assertTrue(shown.getHeaderValue() + "'s header stays whole", shown.getWidth() >= shown.getMinWidth());
                        assertTrue(shown.getHeaderValue() + "'s minimum is its header text", shown.getMinWidth() >= table.getTableHeader().getDefaultRenderer()
                            .getTableCellRendererComponent(table, shown.getHeaderValue(), false, false, -1, column).getPreferredSize().width);
                    }
                    FormattingTestSupport.field(panel, "views", CustomizableTabs.class).select("logs");
                    return null;
                });
                evidence.settle();
                edt(() -> {
                    evidence.capture("bridge-logs-simple");
                    FilterBar bar = bar(panel, "bridge-logs");
                    assertTrue("The Logs bar shows", bar.isShowing());
                    FilterBarAssert.assertOneRow(bar);
                    JTextField logSearch = named(panel, "bridge-log-search", JTextField.class);
                    JComboBox<?> level = named(panel, "bridge-log-level", JComboBox.class);
                    assertSame("Search and level share the search slot", logSearch.getParent(), level.getParent());
                    assertTrue(logSearch.isShowing()); assertTrue(level.isShowing());
                    assertSame("Search stays shared with Review", named(panel, "bridge-search", JTextField.class).getDocument(), logSearch.getDocument());
                    assertNull("Logs has no drawer", bar.drawerContent());
                    assertFalse("…so no Filters toggle", named(bar, "bridge-logs-filters", AbstractButton.class).isVisible());
                    assertTrue("⋯ shows", more(panel, "bridge-logs").isShowing());
                    JTable logs = named(panel, "bridge-log-table", JTable.class);
                    for (int column = 0; column < 2; column++)
                        assertEquals("Time and Level keep their kind widths", logs.getColumnModel().getColumn(column).getPreferredWidth(), logs.getColumnModel().getColumn(column).getWidth());
                    assertTrue("The message takes the room left", logs.getColumnModel().getColumn(2).getWidth() > logs.getWidth() / 2);
                    mode.set(DisplayModeModel.Mode.ANALYST);
                    return null;
                });
                evidence.settle();
                edt(() -> { evidence.capture("bridge-logs-analyst"); FilterBarAssert.assertOneRow(bar(panel, "bridge-logs"));
                    FormattingTestSupport.field(panel, "views", CustomizableTabs.class).select("review"); return null; });
                evidence.settle();
                edt(() -> {
                    evidence.capture("bridge-review-analyst"); FilterBarAssert.assertOneRow(bar(panel, "bridge-review"));
                    JTable table = named(panel, "bridge-review-table", JTable.class);
                    for (int row = 0; row < table.getRowCount(); row++) {
                        JLabel time = (JLabel) table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                        assertTrue("Analyst's absolute time " + time.getText() + " is whole", time.getPreferredSize().width <= table.getColumnModel().getColumn(0).getWidth());
                    }
                    return null;
                });
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void compactReviewScrollsSidewaysAtItsKindWidthsInsteadOfCuttingLabels() throws Exception {
        try (BridgeService service = service(temp)) {
            edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); return null; });
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, DisplayModeModel.application()));
            JComponent root = edt(() -> TestPages.shell("bridge-review", panel));
            try {
                edt(() -> { evidence.show(root, "Bridge review compact", 680, 520, 18); return null; });
                evidence.settle();
                edt(() -> {
                    evidence.capture("bridge-review-compact-analyst");
                    JTable table = named(panel, "bridge-review-table", JTable.class);
                    JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
                    int minimum = 0;
                    for (int column = 0; column < table.getColumnCount(); column++) minimum += table.getColumnModel().getColumn(column).getMinWidth();
                    System.out.println("Bridge compact review viewport=" + scroll.getViewport().getWidth() + " minimum=" + minimum + " table=" + table.getWidth());
                    assertTrue("The page is narrower than the columns' minimums", scroll.getViewport().getWidth() < minimum);
                    assertTrue("…so the table scrolls sideways", table.getWidth() > scroll.getViewport().getWidth() && scroll.getHorizontalScrollBar().isShowing());
                    for (int column = 0; column < table.getColumnCount(); column++) {
                        javax.swing.table.TableColumn shown = table.getColumnModel().getColumn(column);
                        assertEquals(shown.getHeaderValue() + " keeps its kind width", shown.getPreferredWidth(), shown.getWidth());
                    }
                    for (int row = 0; row < table.getRowCount(); row++) {
                        JLabel outcome = (JLabel) table.prepareRenderer(table.getCellRenderer(row, 6), row, 6);
                        assertTrue(outcome.getText() + " fits its column", outcome.getPreferredSize().width <= table.getColumnModel().getColumn(6).getWidth());
                    }
                    return null;
                });
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void drawerFiltersShowAsChipsResetClearsEverythingAndClearKeepsTheSearch() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.SIMPLE)));
            JComponent root = edt(() -> TestPages.shell("bridge-review", panel));
            try {
                edt(() -> { evidence.show(root, "Bridge review drawer", 1240, 800, 13); return null; });
                edt(() -> {
                    FilterBar bar = bar(panel, "bridge-review"); JTable table = named(panel, "bridge-review-table", JTable.class);
                    AbstractButton toggle = named(bar, "bridge-review-filters", AbstractButton.class);
                    assertEquals(4, table.getRowCount()); assertEquals(0, bar.activeCount()); assertEquals("Filters", toggle.getText());
                    assertFalse("No Clear at the defaults", named(bar, "bridge-review-clear-filters", AbstractButton.class).isVisible());
                    bar.setDrawerOpen(true);
                    combo(panel, "bridge-outcome-filter").setSelectedItem(BridgeService.Outcome.LOGGED);
                    assertEquals(1, table.getRowCount());
                    assertEquals(Collections.singletonList("Outcome: Confirmed logged"), chips(bar));
                    combo(panel, "bridge-outcome-filter").setSelectedIndex(0);
                    combo(panel, "bridge-status-filter").setSelectedItem("Not in CSV");
                    combo(panel, "bridge-character-filter").setSelectedItem("Example #7");
                    combo(panel, "bridge-dungeon-filter").setSelectedItem("Lost Halls");
                    combo(panel, "bridge-enchant-filter").setSelectedIndex(2);
                    combo(panel, "bridge-outcome-filter").setSelectedItem(BridgeService.Outcome.LOCAL);
                    assertEquals(1, table.getRowCount()); assertEquals("Unlisted ST", table.getValueAt(0, 1));
                    assertEquals(Arrays.asList("Outcome: Local/excluded", "Status: Not in CSV", "Character: Example #7", "Dungeon: Lost Halls", "Enchants: No applied enchants"), chips(bar));
                    assertEquals("Filters · 5", toggle.getText());
                    assertTrue(named(panel, "bridge-totals", JTextArea.class).getText().contains("Shown: 1 / 4 retained items"));
                    return null;
                });
                evidence.settle();
                edt(() -> {
                    evidence.capture("bridge-review-drawer-chips");
                    FilterBar bar = bar(panel, "bridge-review"); JTable table = named(panel, "bridge-review-table", JTable.class);
                    removeChip(bar, "Status: Not in CSV");
                    assertEquals("A chip's × resets its control", 0, combo(panel, "bridge-status-filter").getSelectedIndex());
                    assertEquals(4, bar.activeCount());
                    removeChip(bar, "Dungeon: Lost Halls");
                    assertEquals(0, combo(panel, "bridge-dungeon-filter").getSelectedIndex());
                    assertEquals(Arrays.asList("Outcome: Local/excluded", "Character: Example #7", "Enchants: No applied enchants"), chips(bar));
                    JTextField search = named(panel, "bridge-search", JTextField.class);
                    search.setText("Unlisted");
                    assertEquals("Search is not a chip; the field shows it", 3, bar.activeCount());
                    AbstractButton clear = named(bar, "bridge-review-clear-filters", AbstractButton.class);
                    assertTrue("Clear shows while chips do", clear.isShowing());
                    clear.doClick();
                    for (String facet : FACETS) assertEquals(facet + " is cleared", 0, combo(panel, facet).getSelectedIndex());
                    assertEquals("Clear removes the chips and keeps the search", "Unlisted", search.getText());
                    assertEquals(0, bar.activeCount()); assertFalse(clear.isVisible());
                    assertEquals(1, table.getRowCount());
                    combo(panel, "bridge-dungeon-filter").setSelectedItem("Ice Citadel");
                    named(panel, "bridge-reset", AbstractButton.class).doClick();
                    assertEquals("Reset filters clears the search", "", search.getText());
                    for (String facet : FACETS) assertEquals(facet + " is reset", 0, combo(panel, facet).getSelectedIndex());
                    assertEquals(0, bar.activeCount()); assertEquals(4, table.getRowCount());
                    assertTrue("Resetting leaves the drawer as it was", bar.drawerOpen());
                    return null;
                });
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void overflowExportsTheShownRowsAndClearLogsAsksFirst() throws Exception {
        try (BridgeService service = service(temp)) {
            List<String[]> exported = new ArrayList<>(); List<String[]> asked = new ArrayList<>(); boolean[] answer = {false};
            BridgeReviewGUI panel = edt(() -> {
                BridgeReviewGUI created = new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.SIMPLE));
                created.useExporter((name, content) -> exported.add(new String[]{name, content}));
                created.useConfirm((title, message) -> { asked.add(new String[]{title, message}); return answer[0]; });
                return created;
            });
            edt(() -> {
                for (String gone : new String[]{"Export review CSV", "Export logs", "Clear logs"})
                    assertNull("'" + gone + "' is a ⋯ item now, not a button", search(panel, AbstractButton.class, b -> gone.equals(b.getText())));
                OverflowMenu review = more(panel, "bridge-review");
                assertEquals(Collections.singletonList("Export review CSV…"), texts(review.menu().getComponents()));
                named(panel, "bridge-search", JTextField.class).setText("Sword");
                List<BridgeService.Review> shown = new ArrayList<>();
                for (BridgeService.Review r : service.snapshot().reviews) if (r.matches("Sword")) shown.add(r);
                Collections.reverse(shown);
                JMenuItem export = review.item("Export review CSV…");
                assertEquals("bridge-export-review", export.getName());
                export.doClick();
                assertEquals("bridge-review.csv", exported.get(0)[0]);
                assertEquals("The export holds the shown rows", BridgeReviewGUI.reviewCsv(shown), exported.get(0)[1]);
                named(panel, "bridge-search", JTextField.class).setText("");

                OverflowMenu logs = more(panel, "bridge-logs");
                assertEquals(Arrays.asList("Export logs…", null, "Clear logs…"), texts(logs.menu().getComponents()));
                JTable table = named(panel, "bridge-log-table", JTable.class);
                StringBuilder expected = new StringBuilder();
                for (int i = 0; i < table.getRowCount(); i++)
                    expected.append(table.getValueAt(i, 0)).append(" [").append(table.getValueAt(i, 1)).append("] ").append(table.getValueAt(i, 2)).append('\n');
                logs.item("Export logs…").doClick();
                assertEquals("bridge-diagnostics.log", exported.get(1)[0]);
                assertEquals(expected.toString(), exported.get(1)[1]);
                JMenuItem clear = logs.item("Clear logs…");
                assertEquals("bridge-clear-logs", clear.getName());
                assertEquals("Clear is a Danger item", Tokens.color(Tokens.Role.BAD), clear.getForeground());
                int before = service.snapshot().logs.size();
                assertTrue(before > 0);
                clear.doClick();
                assertEquals("Clear logs asks first", 1, asked.size());
                assertEquals("Clear Bridge logs", asked.get(0)[0]);
                assertEquals("Declining keeps every entry", before, service.snapshot().logs.size());
                assertEquals(before, table.getRowCount());
                answer[0] = true; clear.doClick();
                assertEquals(0, service.snapshot().logs.size());
                assertEquals("The table follows at once", 0, table.getRowCount());
                assertEquals("Review rows are untouched", 4, named(panel, "bridge-review-table", JTable.class).getRowCount());
                return null;
            });
        }
    }

    @Test public void otherTabsFollowTheButtonHierarchy() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.SIMPLE)));
            edt(() -> {
                assertEquals(KitButton.Variant.PRIMARY, variant(panel, "bridge-saved-open-configured"));
                assertEquals(KitButton.Variant.SECONDARY, variant(panel, "bridge-saved-open-file"));
                assertEquals(KitButton.Variant.SECONDARY, variant(panel, "bridge-saved-export"));
                assertEquals(KitButton.Variant.PRIMARY, variant(panel, "bridge-save"));
                assertEquals(KitButton.Variant.SECONDARY, variant(panel, "bridge-revert"));
                KitButton included = VisualEvidence.find(panel, KitButton.class, b -> "Use included CSV".equals(b.getText()));
                assertNotNull(included); assertEquals(KitButton.Variant.GHOST, included.variant());
                assertEquals(KitButton.Variant.SECONDARY, variant(panel, "bridge-alert-draft"));
                assertEquals("Item alert from this drop…", named(panel, "bridge-alert-draft", JButton.class).getText());
                assertEquals(KitButton.Variant.GHOST, variant(panel, "bridge-reset"));
                return null;
            });
        }
    }

    // ---- fixture: a synthetic service whose in-process transport answers per item; nothing leaves the process ----

    static final String[] FACETS = {"bridge-outcome-filter", "bridge-status-filter", "bridge-character-filter", "bridge-dungeon-filter", "bridge-enchant-filter"};

    /** Four retained drops (Confirmed logged, Local/excluded, Received—unconfirmed, Not logged) and debug log entries. */
    static BridgeService service(TemporaryFolder temp) throws Exception {
        Path csv = temp.newFile("bridge-items.csv").toPath();
        Files.write(csv, "Item Name\nTest Sword\nCrystal Wand\nMystic Blade\n".getBytes(StandardCharsets.UTF_8));
        Properties p = new Properties(); String x = BridgeConfig.PREFIX;
        p.setProperty(x + "enabled", "true"); p.setProperty(x + "send", "true"); p.setProperty(x + "endpoint", "https://example.invalid/ingest");
        p.setProperty(x + "guild_id", "123456789012345678"); p.setProperty(x + "link_token", "bridge-fixture-token"); p.setProperty(x + "csv_path", csv.toString());
        p.setProperty(x + "debug", "true");
        BridgeService service = new BridgeService(temp.getRoot().toPath().resolve("bridge-fixture.properties"), false, (url, json) ->
            new BridgeService.Response(200, json.contains("Crystal Wand") ? "{\"ok\":true}"
                : json.contains("Mystic Blade") ? "{\"result\":{\"logged\":false,\"reason\":\"unmapped_character\"}}" : "{\"ok\":true,\"result\":{\"logged\":true}}"), 20);
        service.configure(new BridgeConfig(p), false, false);
        service.receive(Arrays.asList(drop(42, "Test Sword", 7, "Example", "The Shatters", "Damage Boost(1)"), drop(43, "Unlisted ST", 7, "Example", "Lost Halls", ""),
            drop(44, "Crystal Wand", 8, "Fixture", "Ice Citadel", ""), drop(45, "Mystic Blade", 8, "Fixture", "Lost Halls", "")));
        service.awaitIdle(3000);
        return service;
    }
    static BridgePayload.Drop drop(int id, String name, int character, String who, String dungeon, String enchants) {
        return new BridgePayload.Drop(new BridgePayload.Item(id, name, "EQUIPMENT", "UT", enchants, false), character, who, "Wizard", dungeon, false, false, 9, 0);
    }
    static DisplayModeModel mode(DisplayModeModel.Mode start) {
        return new DisplayModeModel(key -> start == DisplayModeModel.Mode.ANALYST ? "analyst" : "simple", (key, value) -> { });
    }
    static <T> T edt(Callable<T> body) throws Exception {
        Object[] result = new Object[1]; Exception[] failure = new Exception[1]; Error[] error = new Error[1];
        SwingUtilities.invokeAndWait(() -> { try { result[0] = body.call(); } catch (Exception e) { failure[0] = e; } catch (Error e) { error[0] = e; } });
        if (error[0] != null) throw error[0];
        if (failure[0] != null) throw failure[0];
        @SuppressWarnings("unchecked") T value = (T) result[0]; return value;
    }
    static <T extends Component> T named(Container root, String name, Class<T> type) {
        T found = VisualEvidence.named(root, name, type); assertNotNull("No " + name, found); return found;
    }
    private static FilterBar bar(BridgeReviewGUI panel, String name) { return named(panel, name + "-filter-bar", FilterBar.class); }
    private static OverflowMenu more(BridgeReviewGUI panel, String name) { return named(panel, name + "-more", OverflowMenu.class); }
    private static JComboBox<?> combo(BridgeReviewGUI panel, String name) { return named(panel, name, JComboBox.class); }
    private static KitButton.Variant variant(BridgeReviewGUI panel, String name) {
        JButton button = named(panel, name, JButton.class);
        assertTrue(name + " is a KitButton", button instanceof KitButton);
        return ((KitButton) button).variant();
    }
    private static <T extends Component> T search(Container root, Class<T> type, java.util.function.Predicate<T> predicate) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && predicate.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container) { T found = search((Container) child, type, predicate); if (found != null) return found; }
        }
        return null;
    }
    private static int y(Component child, Component root) { return SwingUtilities.convertPoint(child, 0, 0, root).y; }
    private static List<JComponent> controls(Container root) {
        List<JComponent> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton || child instanceof JComboBox || child instanceof JTextField) found.add((JComponent) child);
            else if (child instanceof Container) found.addAll(controls((Container) child));
        }
        return found;
    }
    private static String describe(JComponent control) {
        return control.getName() != null ? control.getName() : control instanceof AbstractButton ? ((AbstractButton) control).getText() : control.getClass().getSimpleName();
    }
    private static List<String> texts(Component[] items) {
        List<String> found = new ArrayList<>();
        for (Component item : items) found.add(item instanceof JMenuItem ? ((JMenuItem) item).getText() : null);
        return found;
    }
    private static List<String> chips(FilterBar bar) {
        List<String> labels = new ArrayList<>();
        for (AbstractButton close : removers(bar)) labels.add(close.getAccessibleContext().getAccessibleName().substring("Remove filter: ".length()));
        return labels;
    }
    private static void removeChip(FilterBar bar, String label) {
        for (AbstractButton close : removers(bar)) if (("Remove filter: " + label).equals(close.getAccessibleContext().getAccessibleName())) { close.doClick(); return; }
        fail("No chip " + label);
    }
    private static List<AbstractButton> removers(Container root) {
        List<AbstractButton> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && "remove-filter".equals(child.getName())) found.add((AbstractButton) child);
            else if (child instanceof Container) found.addAll(removers((Container) child));
        }
        return found;
    }
}
