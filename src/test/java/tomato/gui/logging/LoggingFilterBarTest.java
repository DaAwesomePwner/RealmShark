package tomato.gui.logging;

import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.TestPages;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.modern.FormattingTestSupport.cell;
import static tomato.gui.modern.FormattingTestSupport.field;

/** P6b Task 5: Logging's one FilterBar row, its drawer and chips, and the view states and actions in ⋯. Synthetic data only. */
public class LoggingFilterBarTest {
    private static final String DRAWER = "ui.filters.logging.open";
    private static final String[] TABS = {"discovery", "reentry", "packets", "stats", "events", "fields"};
    @Rule public VisualEvidence evidence = new VisualEvidence("p6b-logging");
    private String drawerBefore, modeBefore;
    private DisplayModeModel.Mode applicationMode;

    @Before public void isolate() throws Exception {
        drawerBefore = PropertiesManager.getProperty(DRAWER); modeBefore = PropertiesManager.getProperty(DisplayModeModel.KEY);
        SwingUtilities.invokeAndWait(() -> applicationMode = DisplayModeModel.application().mode());
        PropertiesManager.setProperties(DRAWER, "false");
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(applicationMode));
        PropertiesManager.setProperties(DRAWER, drawerBefore == null ? "" : drawerBefore);
        PropertiesManager.setProperties(DisplayModeModel.KEY, modeBefore == null ? "" : modeBefore);
    }

    @Test public void everyTabHasOneFilterRowWithCollectionAndPauseShowing() throws Exception {
        try (DiscoveryLog log = LoggingQueryTest.fixture()) {
            LoggingGUI view = view(log, LoggingStateTestSupport.memoryStore());
            JComponent root = edt(() -> TestPages.shell("logging", view));
            try {
                edt(() -> { evidence.show(root, "Logging filter row", 1240, 800, 13); return null; });
                for (String tab : TABS) {
                    edt(() -> { tabGroup(view).select(tab); return null; });
                    evidence.settle();
                    edt(() -> {
                        evidence.capture("logging-filter-row-" + tab);
                        FilterBar bar = bar(view);
                        assertFalse(tab + ": the drawer starts closed", bar.drawerOpen());
                        JCheckBox collection = field(view, "enabled", JCheckBox.class), pause = field(view, "freeze", JCheckBox.class);
                        assertEquals("Pause this view", pause.getText());
                        assertTrue(tab + ": collection shows", collection.isShowing()); assertTrue(tab + ": Pause shows", pause.isShowing());
                        assertTrue(SwingUtilities.isDescendingFrom(collection, bar)); assertTrue(SwingUtilities.isDescendingFrom(pause, bar));
                        List<Component> row = new ArrayList<>(Arrays.asList(search(view), reset(view), collection, pause, more(view)));
                        if (!"discovery".equals(tab)) row.add(VisualEvidence.named(bar, "logging-filters", AbstractButton.class));
                        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE, shortest = Integer.MAX_VALUE;
                        for (Component part : row) {
                            assertTrue(tab + ": " + part.getName() + " shows", part.isShowing());
                            Point at = SwingUtilities.convertPoint(part, 0, 0, bar);
                            top = Math.min(top, at.y); bottom = Math.max(bottom, at.y + part.getHeight()); shortest = Math.min(shortest, part.getHeight());
                        }
                        int tallest = 0; for (Component part : row) tallest = Math.max(tallest, part.getHeight());
                        assertTrue(tab + ": the filter controls share one row (span " + (bottom - top) + ", tallest " + tallest + ")", bottom - top < tallest + shortest / 2);
                        assertTrue(tab + ": the closed bar is one row high (" + bar.getHeight() + ")", bar.getHeight() < tallest + shortest);
                        // Nothing else interactive sits between the bar and the tab strip, except the coverage link by the summary.
                        JTabbedPane tabs = field(view, "tabs", JTabbedPane.class);
                        int barBottom = SwingUtilities.convertPoint(bar, 0, bar.getHeight(), view).y, tabsTop = SwingUtilities.convertPoint(tabs, 0, 0, view).y;
                        List<String> extra = new ArrayList<>();
                        for (JComponent control : controls(view)) {
                            if (!control.isShowing() || SwingUtilities.isDescendingFrom(control, bar) || SwingUtilities.isDescendingFrom(control, tabs)) continue;
                            int y = SwingUtilities.convertPoint(control, 0, 0, view).y;
                            if (y >= barBottom && y < tabsTop) extra.add(control.getClass().getSimpleName() + ":" + text(control));
                        }
                        assertEquals(tab + ": no control rows between the filter row and the table", Collections.singletonList("KitButton:Diagnostic coverage"), extra);
                        return null;
                    });
                }
                // One short chip (and Clear) still fits beside the search slot.
                edt(() -> { tabGroup(view).select("events"); search(view).setText("800"); return null; });
                evidence.settle();
                edt(() -> {
                    evidence.capture("logging-filter-row-events-one-chip");
                    FilterBar bar = bar(view);
                    assertEquals(Collections.singletonList("Search: 800"), chips(bar));
                    AbstractButton clear = VisualEvidence.named(bar, "logging-clear-filters", AbstractButton.class);
                    int searchY = SwingUtilities.convertPoint(search(view), 0, 0, bar).y, clearY = SwingUtilities.convertPoint(clear, 0, 0, bar).y;
                    assertTrue("a single chip and Clear stay on the search row", Math.abs(clearY - searchY) < search(view).getHeight());
                    search(view).setText(""); return null;
                });
                edt(() -> {
                    KitButton coverage = coverage(view);
                    assertEquals(KitButton.Variant.GHOST, coverage.variant());
                    assertEquals("Diagnostic coverage", coverage.getAccessibleContext().getAccessibleName());
                    JLabel summary = field(view, "summary", JLabel.class);
                    int linkY = SwingUtilities.convertPoint(coverage, 0, 0, view).y, summaryY = SwingUtilities.convertPoint(summary, 0, 0, view).y;
                    assertTrue("the coverage link sits by the summary", linkY < summaryY + summary.getHeight() && summaryY < linkY + coverage.getHeight());
                    assertTrue("the export row below the table stays", VisualEvidence.button(view, "Export report").isShowing());
                    assertFalse("no view-state status without a failure", field(state(view), "status", JLabel.class).isVisible());
                    return null;
                });
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void facetsLiveInTheDrawerAndChipsClearThemLikeReset() throws Exception {
        try (DiscoveryLog log = LoggingQueryTest.fixture()) {
            LoggingGUI view = view(log, LoggingStateTestSupport.memoryStore());
            JButton reset = edt(() -> reset(view));
            edt(() -> {
                FilterBar bar = bar(view);
                tabGroup(view).select("discovery");
                assertNull("Discovery has no facets, so no Filters toggle", bar.drawerContent());
                tabGroup(view).select("events");
                JComponent drawer = bar.drawerContent(); assertNotNull(drawer);
                for (String facet : new String[]{"packet", "stat", "object", "area", "outcome"})
                    assertTrue(facet + " is in the drawer", SwingUtilities.isDescendingFrom(VisualEvidence.named(bar, "logging-facet-" + facet, JComboBox.class), drawer));
                for (String check : new String[]{"observedOnly", "issuesOnly", "changedOnly"})
                    assertTrue(check + " is in the drawer", SwingUtilities.isDescendingFrom(field(view, check, JCheckBox.class), drawer));
                assertFalse(SwingUtilities.isDescendingFrom(search(view), drawer)); assertTrue(SwingUtilities.isDescendingFrom(search(view), bar));
                assertTrue(SwingUtilities.isDescendingFrom(reset, bar)); assertFalse(SwingUtilities.isDescendingFrom(reset, drawer));
                assertEquals(0, bar.activeCount());

                JTable table = table(view); assertEquals(2, table.getRowCount());
                facet(view, "object").setSelectedItem("777");
                assertTrue(chips(bar).toString(), chips(bar).contains("Object: 777")); assertEquals(chips(bar).size(), bar.activeCount());
                assertEquals(Integer.valueOf(777), view.captureViewState().tabs.get("events").query.object);
                removeChip(bar, "Object: 777");
                assertEquals("Any", facet(view, "object").getSelectedItem()); assertEquals(2, table(view).getRowCount());
                assertNull(view.captureViewState().tabs.get("events").query.object);
                assertEquals("the object's capture scope goes with it", "", view.captureViewState().tabs.get("events").query.captureRun);
                assertFalse(chips(bar).contains("Object: 777"));

                // Clear (the bar's) and Reset filters leave the same state.
                facet(view, "packet").setSelectedItem("NEWTICK"); field(view, "changedOnly", JCheckBox.class).doClick(); search(view).setText("HP_STAT");
                assertEquals(Arrays.asList("Search: HP_STAT", "Packet: NEWTICK", "Changed values"), chips(bar));
                VisualEvidence.named(bar, "logging-clear-filters", AbstractButton.class).doClick();
                Map<String, Object> cleared = view.captureViewState().tabs.get("events").query.metadata();
                assertEquals(new LoggingQuery().metadata(), cleared); assertEquals("", search(view).getText());
                assertEquals(0, bar.activeCount()); assertFalse(field(view, "changedOnly", JCheckBox.class).isSelected());
                facet(view, "packet").setSelectedItem("NEWTICK"); search(view).setText("HP_STAT");
                reset.doClick();
                assertEquals(cleared, view.captureViewState().tabs.get("events").query.metadata()); assertEquals("", search(view).getText());
                assertEquals(0, bar.activeCount()); assertEquals("Any", facet(view, "packet").getSelectedItem());

                // Packets: the observed/issues checks are drawer facets with chips too.
                tabGroup(view).select("packets");
                field(view, "observedOnly", JCheckBox.class).doClick();
                assertEquals(Collections.singletonList("Observed packets"), chips(bar));
                removeChip(bar, "Observed packets"); assertFalse(field(view, "observedOnly", JCheckBox.class).isSelected());
                assertEquals(0, bar.activeCount());
                return null;
            });
            LoggingQueryTest.tick(log, 700, 80); edt(() -> { view.refresh(); return null; });
            await(() -> field(view, "snapshot", DiscoveryLog.Snapshot.class).events.size() == 3);
            edt(() -> {
                tabGroup(view).select("events"); tabGroup(view).select("stats");
                assertSame("logging-reset is one persistent instance", reset, reset(view));
                return null;
            });
        }
    }

    @Test public void overflowItemsRunTheViewStateMethodsAndDiagnosticActions() throws Exception {
        LoggingStateTestSupport.Memory memory = new LoggingStateTestSupport.Memory();
        try (DiscoveryLog log = LoggingQueryTest.fixture()) {
            LoggingGUI view = view(log, memory.store);
            Answers answers = new Answers();
            edt(() -> {
                LoggingViewState state = state(view); state.prompts = answers;
                OverflowMenu more = more(view); assertTrue(more.isVisible());
                JMenu saved = (JMenu) more.item("Saved views"); assertNotNull(saved);
                assertEquals(Arrays.asList("Save current view…", "Load", "Delete view…", "Reset saved state", "Retry save"), texts(saved.getMenuComponents()));
                JMenu load = (JMenu) more.item("Load");
                assertFalse("nothing to load yet", load.isEnabled()); assertFalse(more.item("Delete view…").isEnabled());

                tabGroup(view).select("events"); search(view).setText("HP_STAT");
                answers.name = "HP evidence"; more.item("Save current view…").doClick();
                assertEquals("Save current view… calls saveNamed", Collections.singletonList("HP evidence"), state.names());
                assertEquals(Collections.singletonList("HP evidence"), texts(load.getMenuComponents())); assertTrue(load.isEnabled());
                answers.name = null; more.item("Save current view…").doClick();
                assertEquals("a cancelled prompt saves nothing", Collections.singletonList("HP evidence"), state.names());

                tabGroup(view).select("packets"); search(view).setText("other");
                ((JMenuItem) load.getMenuComponent(0)).doClick();
                assertEquals("Load ▸ name calls loadNamed", "events", tabGroup(view).selectedId()); assertEquals("HP_STAT", search(view).getText());

                answers.name = "HP evidence"; more.item("Delete view…").doClick();
                assertEquals(Collections.singletonList("HP evidence"), answers.choices);
                assertTrue("Delete view… calls deleteNamed", state.names().isEmpty()); assertFalse(load.isEnabled());

                search(view).setText("draft to reset"); more.item("Reset saved state").doClick();
                assertEquals("Reset saved state calls reset", "", search(view).getText());
                assertSame("Retry save is the view state's retry item", state.retry, more.item("Retry save"));
                search(view).setText("retried"); state.retry.setEnabled(true); state.retry.doClick();
                assertTrue("Retry save calls retry", memory.values.get("ux.archive.logging-live").contains("retried"));

                JCheckBoxMenuItem samples = (JCheckBoxMenuItem) more.item("Save diagnostic samples");
                assertFalse(samples.isSelected()); samples.doClick(); assertTrue(log.isSaving());
                samples.doClick(); assertFalse(log.isSaving());
                log.setSaving(true); view.refresh(); assertTrue("the check item mirrors the log", samples.isSelected()); log.setSaving(false); view.refresh();

                JMenu sampling = (JMenu) more.item("Sampling");
                assertEquals(Arrays.asList("Sampled", "Detailed"), texts(sampling.getMenuComponents()));
                assertTrue(((JRadioButtonMenuItem) more.item("Detailed")).isSelected());
                more.item("Sampled").doClick(); assertEquals(1000, log.snapshot().sampleMillis);
                more.item("Detailed").doClick(); assertEquals(0, log.snapshot().sampleMillis);

                JMenuItem clear = more.item("Clear data…");
                assertEquals("Danger", Tokens.color(Tokens.Role.BAD), clear.getForeground());
                answers.confirm = false; clear.doClick(); assertEquals("declined: nothing cleared", 2, log.snapshot().events.size());
                field(view, "freeze", JCheckBox.class).setSelected(true);
                answers.confirm = true; clear.doClick(); assertTrue(answers.confirmed);
                assertTrue(log.snapshot().events.isEmpty()); assertFalse(field(view, "freeze", JCheckBox.class).isSelected());
                return null;
            });
        }
    }

    @Test public void failureStatusShowsBelowTheBarOnlyOnFailure() throws Exception {
        LoggingStateTestSupport.Memory memory = new LoggingStateTestSupport.Memory();
        memory.values.put("ux.archive.logging-live", "{\"version\":99}");
        try (DiscoveryLog log = LoggingQueryTest.fixture()) {
            LoggingGUI view = view(log, memory.store);
            edt(() -> {
                JLabel status = field(state(view), "status", JLabel.class);
                assertTrue(status.isVisible()); assertTrue(status.getText().contains("Reset saved state"));
                assertTrue("the status line is in the page header", SwingUtilities.isDescendingFrom(status, view));
                more(view).item("Reset saved state").doClick();
                assertFalse("a successful reset hides it", status.isVisible());
                return null;
            });
        }
    }

    @Test public void timesStayAbsoluteInSimpleAndAnalyst() throws Exception {
        try (DiscoveryLog log = LoggingQueryTest.fixture()) {
            LoggingGUI view = view(log, LoggingStateTestSupport.memoryStore());
            for (DisplayModeModel.Mode mode : DisplayModeModel.Mode.values()) {
                edt(() -> {
                    DisplayModeModel.application().set(mode);
                    for (String tab : new String[]{"events", "reentry"}) {
                        tabGroup(view).select(tab); JTable table = table(view);
                        for (int row = 0; row < table.getRowCount(); row++) {
                            Instant time = (Instant) table.getValueAt(row, 0);
                            assertEquals(mode + " " + tab + ": absolute (fresh fixture time, never \"… ago\")", DisplayFormat.formatTimestamp(time), cell(table, row, 0));
                        }
                    }
                    return null;
                });
            }
        }
    }

    /** Answers the ⋯ prompts without a modal dialog. */
    private static final class Answers implements LoggingViewState.Prompts {
        String name; boolean confirm, confirmed; List<String> choices;
        public String ask(String title, String message, List<String> offered) { choices = offered; return name; }
        public boolean confirm(String title, String message) { confirmed = true; return confirm; }
    }

    private static LoggingGUI view(DiscoveryLog log, tomato.gui.history.ViewStateStore store) throws Exception {
        LoggingGUI view = edt(() -> { LoggingGUI created = new LoggingGUI(log, store); created.refresh(); return created; });
        await(() -> field(view, "snapshot", DiscoveryLog.Snapshot.class) != null); return view;
    }
    private static <T> T edt(java.util.concurrent.Callable<T> body) throws Exception {
        Object[] result = new Object[1]; Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> { try { result[0] = body.call(); } catch (Exception e) { failure[0] = e; } });
        if (failure[0] != null) throw failure[0];
        @SuppressWarnings("unchecked") T value = (T) result[0]; return value;
    }
    private static FilterBar bar(LoggingGUI view) { return VisualEvidence.named(view, "logging-filter-bar", FilterBar.class); }
    private static OverflowMenu more(LoggingGUI view) { return VisualEvidence.named(view, "logging-more", OverflowMenu.class); }
    private static JButton reset(LoggingGUI view) { return VisualEvidence.named(view, "logging-reset", JButton.class); }
    private static JTextField search(LoggingGUI view) { return VisualEvidence.named(view, "logging-search", JTextField.class); }
    private static KitButton coverage(LoggingGUI view) { return VisualEvidence.find(view, KitButton.class, b -> "Diagnostic coverage".equals(b.getText())); }
    private static tomato.gui.kit.CustomizableTabs tabGroup(LoggingGUI view) { return field(view, "tabGroup", tomato.gui.kit.CustomizableTabs.class); }
    private static LoggingViewState state(LoggingGUI view) { return field(view, "viewState", LoggingViewState.class); }
    private static JComboBox<?> facet(LoggingGUI view, String name) { return field(view, name + "Facet", JComboBox.class); }
    private static JTable table(LoggingGUI view) {
        return VisualEvidence.find((Container) field(view, "tabs", JTabbedPane.class).getSelectedComponent(), JTable.class, t -> true);
    }
    private static List<JComponent> controls(Container root) {
        List<JComponent> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton || child instanceof JComboBox || child instanceof JTextField) found.add((JComponent) child);
            else if (child instanceof Container) found.addAll(controls((Container) child));
        }
        return found;
    }
    private static String text(JComponent control) { return control instanceof AbstractButton ? ((AbstractButton) control).getText() : control.getName(); }
    private static List<String> texts(Component[] items) {
        List<String> found = new ArrayList<>();
        for (Component item : items) if (item instanceof JMenuItem) found.add(((JMenuItem) item).getText());
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
