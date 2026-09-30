package tomato.gui.keypop;

import com.google.gson.JsonArray;
import java.awt.Component;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.LiveFilterHost;
import tomato.gui.history.ScopeChip;
import tomato.gui.history.SessionPanel;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.TestPages;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveExport;
import tomato.history.archive.ExportSelection;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/**
 * P6b Task 13: Key-pops lends its live row to the Scope chip, shows its metrics as StatTiles, keeps page actions, live views and
 * column tools in ⋯, shows its saved modes as customizable tabs, and reads times relatively in Simple. Synthetic history only.
 */
public class KeyPopRestyleTest {
    /** Drawer-open keys of both bars, both tab groups, the mode and the Log to file choice; the live buffer is restored too. */
    private static final String[] KEYS = {"ui.filters.keypops-live.open", "ui.filters.keypops.open", "ui.tabs.keypops-live",
        "ui.tabs.keypops-saved", DisplayModeModel.KEY, "keypopLogging"};
    private static final String[] VIEWS = {"events", "by-player", "by-item"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("p6b-keypops");
    private final Map<String, String> saved = new HashMap<>();
    private DisplayModeModel.Mode mode;
    private KeyPopHistory.Snapshot buffer;

    @Before public void isolate() throws Exception {
        for (String key : KEYS) saved.put(key, PropertiesManager.getProperty(key));
        for (String key : KEYS) if (key.startsWith("ui.filters.")) PropertiesManager.setProperties(key, "false");
        for (String key : KEYS) if (key.startsWith("ui.tabs.")) PropertiesManager.setProperties(key, "");
        mode = edt(() -> DisplayModeModel.application().mode());
        buffer = live().snapshot();
    }
    @After public void restore() throws Exception {
        edt(() -> { DisplayModeModel.application().set(mode); return null; });
        for (String key : KEYS) PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key));
        KeyPopHistory history = live(); history.clear(); for (KeyPopEvent event : buffer.events) history.add(event);
    }

    @Test public void theLiveRowLeadsThePageAboveTheTilesAndHostsTheChipInBothModes() throws Exception {
        try (Fixture f = new Fixture()) {
            seedLiveBuffer();
            JComponent shell = edt(() -> TestPages.shell("key-pops", f.workspace));
            try {
                for (DisplayModeModel.Mode m : DisplayModeModel.Mode.values()) {
                    edt(() -> { DisplayModeModel.application().set(m); evidence.show(shell, "Key-pops live", 1240, 800, 13); return null; });
                    evidence.settle();
                    edt(() -> {
                        evidence.capture("keypops-live-" + m.name().toLowerCase(Locale.ROOT));
                        FilterBar bar = f.workspace.liveFilterBar();
                        assertNotNull("Key-pops lends a live row", bar);
                        assertEquals("keypops-live-filter-bar", bar.getName());
                        assertSame("The page lends its own dashboard's row", f.gui.live.liveFilterBar(), bar);
                        StatTile first = named(f.gui, "keypop-metric-0", StatTile.class);
                        assertTrue("The live row is above the tiles: row " + y(bar, f.gui) + "+" + bar.getHeight() + ", tiles " + y(first, f.gui),
                            y(bar, f.gui) + bar.getHeight() <= y(first, f.gui));
                        assertTrue("The row has the shell's content width (" + bar.getWidth() + ")", bar.getWidth() > 850 && bar.getWidth() < 1100);
                        FilterBarAssert.assertOneRow(bar);
                        FilterBarAssert.assertChipInVisibleBar(f.workspace);
                        ScopeChip chip = ArchiveNativeSupport.scope(f.workspace);
                        assertTrue(chip.isShowing());
                        assertFalse("At this width the chip keeps the scope slot", SwingUtilities.isDescendingFrom(chip, bar.searchSlot()));
                        assertTrue("Dungeon alert… stays visible", named(f.gui, "keypop-notify-dungeon", AbstractButton.class).isShowing());
                        if (m == DisplayModeModel.Mode.SIMPLE) bar.overflow().doClick();   // evidence of the ⋯ items
                        return null;
                    });
                    if (m == DisplayModeModel.Mode.SIMPLE) {
                        evidence.settle();
                        edt(() -> {
                            JPopupMenu menu = f.gui.liveFilterBar().overflow().menu();
                            assertTrue("⋯ opens", menu.isShowing());
                            evidence.capture(SwingUtilities.getWindowAncestor(menu), "keypops-live-more-simple");   // the popup's own window near the edge
                            menu.setVisible(false); return null;
                        });
                    }
                }
                edt(() -> { f.workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ArchiveNativeSupport.ready(f.workspace) && f.workspace.displayedPage().matches == 1120);
                for (DisplayModeModel.Mode m : DisplayModeModel.Mode.values()) {
                    edt(() -> { DisplayModeModel.application().set(m); return null; });
                    evidence.settle();
                    edt(() -> {
                        evidence.capture("keypops-saved-" + m.name().toLowerCase(Locale.ROOT));
                        FilterBarAssert.assertOneRow(f.workspace.filterBar());
                        FilterBarAssert.assertChipInVisibleBar(f.workspace);
                        return null;
                    });
                }
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void eachPageLendsItsOwnDashboardsRowNeverTheLastBuiltOne() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                KeypopGUI later = new KeypopGUI();   // the static dashboard now points at this one
                assertTrue(f.gui instanceof LiveFilterHost);
                assertTrue(SwingUtilities.isDescendingFrom(f.gui.liveFilterBar(), f.gui));
                assertTrue(SwingUtilities.isDescendingFrom(later.liveFilterBar(), later));
                assertSame(f.gui.liveFilterBar(), f.workspace.liveFilterBar());
                assertNotNull("The workspace enabled this page's live views", f.gui.liveFilterBar().overflow().item("Saved views"));
                assertNull("…not the later page's", later.liveFilterBar().overflow().item("Saved views"));
                return null;
            });
        }
    }

    @Test public void tilesAreStatTilesAndUnknownIsNeverShownAsZero() throws Exception {
        edt(() -> {
            String[] captions = {"Observed pops", "Keys", "Players", "Dungeons/items"};
            KeyPopHistory history = new KeyPopHistory(); Instant now = Instant.now();
            KeyPopDashboard ui = new KeyPopDashboard(history, false, model(DisplayModeModel.Mode.ANALYST));
            for (int i = 0; i < captions.length; i++) {
                StatTile tile = named(ui, "keypop-metric-" + i, StatTile.class);
                assertSame(ui.metrics[i], tile);
                assertEquals("An empty buffer is a real zero", "0", tile.valueText());
                assertEquals(DisplayValue.State.ZERO, tile.value().state);
                assertEquals(captions[i] + ": 0", tile.getAccessibleContext().getAccessibleName());
            }
            history.add(new KeyPopEvent(now, "Ann", "Lost Halls", KeyPopEvent.Kind.KEY));
            history.add(new KeyPopEvent(now, "ann", "Vial", KeyPopEvent.Kind.VIAL));
            history.add(new KeyPopEvent(now, "Bo", "The Shatters", KeyPopEvent.Kind.KEY));
            ui.refresh();
            assertEquals(Arrays.asList("3", "2", "2", "3"), values(ui));
            for (StatTile tile : ui.metrics) { assertEquals(DisplayValue.State.KNOWN, tile.value().state); assertNotNull("The tile says what it counts", tile.value().tooltip()); }
            // A saved page can hold a pop whose type, player or dungeon was not recorded: counted where known, marked partial.
            KeyPopHistory page = new KeyPopHistory();
            page.add(new KeyPopEvent(now, "Ann", "Lost Halls", KeyPopEvent.Kind.KEY)); page.add(new KeyPopEvent(null, null, null, null));
            KeyPopDashboard loaded = new KeyPopDashboard(page, true, model(DisplayModeModel.Mode.ANALYST));
            assertEquals("2", loaded.metrics[0].valueText());
            assertEquals(DisplayValue.State.KNOWN, loaded.metrics[0].value().state);
            String[] missing = {null, "type", "player", "dungeon or item"};
            for (int i = 1; i < 4; i++) {
                assertEquals(captions[i], "1 (partial)", loaded.metrics[i].valueText());
                assertTrue(captions[i] + ": " + loaded.metrics[i].value().detail, loaded.metrics[i].value().detail.contains("1 matching pop has no recorded " + missing[i]));
            }
            int unrecorded = -1; for (int row = 0; row < loaded.events.getModel().getRowCount(); row++) if (loaded.events.getModel().getValueAt(row, 1) == null) unrecorded = row;
            assertEquals("Unknown", loaded.events.getModel().getValueAt(unrecorded, 2));
            assertEquals(1, loaded.players.getRowCount()); assertEquals(1, loaded.items.getRowCount());
            // Once the buffer drops older pops every tile counts only the retained window, and says so.
            KeyPopHistory full = new KeyPopHistory();
            for (int i = 0; i < KeyPopHistory.CAPACITY + 5; i++) full.add(new KeyPopEvent(now.minusSeconds(i), "P" + (i % 3), "Lost Halls", KeyPopEvent.Kind.KEY));
            KeyPopDashboard capped = new KeyPopDashboard(full, false, model(DisplayModeModel.Mode.ANALYST));
            assertEquals(DisplayFormat.formatInteger(KeyPopHistory.CAPACITY) + " (partial)", capped.metrics[0].valueText());
            for (StatTile tile : capped.metrics) {
                assertEquals(DisplayValue.State.PARTIAL, tile.value().state);
                assertTrue(tile.value().detail, tile.value().detail.contains("5 older pops"));
            }
            return null;
        });
    }

    @Test public void pageActionsLiveInTheRowsMoreMenuAndClearHistoryConfirms() throws Exception {
        edt(() -> {
            KeypopGUI gui = new KeypopGUI();
            KeyPopHistory history = live(); history.clear();
            history.add(new KeyPopEvent(Instant.now(), "Ann", "Lost Halls", KeyPopEvent.Kind.KEY)); gui.live.refresh();
            OverflowMenu more = gui.liveFilterBar().overflow();
            assertTrue("⋯ shows", more.isVisible());
            String[][] items = {{"Export events…", "keypop-export-events"}, {"Export current tab…", "keypop-export-tab"},
                {"Log to file", "keypop-log-to-file"}, {"Notification settings…", "keypop-notification-settings"}, {"Clear history…", "keypop-clear-history"}};
            for (String[] item : items) {
                JMenuItem found = more.item(item[0]);
                assertNotNull("⋯ " + item[0], found); assertEquals(item[1], found.getName()); assertTrue(found.isVisible() && found.isEnabled());
            }
            JCheckBoxMenuItem log = (JCheckBoxMenuItem) more.item("Log to file");
            boolean was = log.isSelected();
            assertEquals("The check item shows the saved choice", "true".equals(PropertiesManager.getProperty("keypopLogging")), was);
            try {
                log.doClick(); assertEquals(String.valueOf(!was), PropertiesManager.getProperty("keypopLogging"));
            } finally { log.doClick(); }
            assertEquals(String.valueOf(was), PropertiesManager.getProperty("keypopLogging"));
            JMenuItem clear = more.item("Clear history…");
            assertEquals("Clear is a Danger item", Tokens.color(Tokens.Role.BAD), clear.getForeground());
            List<String> asked = new ArrayList<>();
            gui.confirm = message -> { asked.add(message); return false; };
            clear.doClick();
            assertEquals(1, asked.size()); assertTrue(asked.get(0), asked.get(0).contains("Saved session history is kept"));
            assertEquals("Declined: nothing is cleared", 1, history.snapshot().events.size()); assertEquals("1", gui.live.metrics[0].valueText());
            gui.confirm = message -> true;
            clear.doClick();
            assertEquals(0, history.snapshot().events.size()); assertEquals("0", gui.live.metrics[0].valueText());
            // The old footer row is gone; Dungeon alert… stays a visible selection action beside its status line.
            for (String old : new String[]{"Notifications", "Log to file", "Export events (retained CSV)", "Export current tab (retained CSV)", "Clear history"})
                assertNull("No footer button " + old, button(gui, old));
            AbstractButton alert = named(gui, "keypop-notify-dungeon", AbstractButton.class);
            assertNotNull(alert); assertTrue(alert.isVisible()); assertEquals("Dungeon alert…", alert.getText());
            assertNull(more.item("Dungeon alert…"));
            assertNotNull(named(gui, "keypop-handoff-status", JTextArea.class));
            return null;
        });
    }

    @Test public void liveViewsAndColumnToolsAreMoreItemsAndTheDrawerKeepsOnlyFilters() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                KeyPopDashboard ui = f.gui.live; OverflowMenu more = ui.liveFilterBar().overflow();
                assertEquals("keypops-live-saved-views", more.item("Saved views").getName());
                assertEquals("keypop-events-columns", more.item("Columns").getName());
                assertFalse("Live tables have no row details", more.item("Row details…").isVisible());
                assertTrue(more.item("Copy selected rows").isVisible());
                List<String> order = new ArrayList<>();
                for (Component item : more.menu().getComponents()) if (item instanceof JMenuItem && item.isVisible()) order.add(((JMenuItem) item).getText());
                assertTrue("Saved views, then the page actions, then the column tools: " + order,
                    order.indexOf("Saved views") < order.indexOf("Export events…") && order.indexOf("Clear history…") < order.indexOf("Columns"));
                ui.tabs.setSelectedIndex(1);
                assertEquals("The tools follow the tab", "keypop-players-columns", more.item("Columns").getName());
                ui.tabs.setSelectedIndex(2);
                assertEquals("keypop-items-columns", more.item("Columns").getName());
                ui.tabs.setSelectedIndex(0);
                // A column tool applies and becomes part of the live view state.
                JMenu columns = (JMenu) more.item("Columns");
                ((tomato.gui.history.HistoryTables.ColumnTools) columns.getClientProperty(tomato.gui.history.HistoryTables.ColumnTools.class)).refresh();   // as opening the submenu does
                JMenuItem type = null; for (Component item : columns.getMenuComponents()) if ("keypop-events-column-column-2".equals(item.getName())) type = (JMenuItem) item;
                assertNotNull(type); type.doClick();
                assertEquals(3, ui.events.getColumnCount());
                boolean hidden = false;
                for (tomato.gui.history.ViewState.Column column : ui.captureLiveState().tables.get("keypop-events").columns) if (column.id.equals("column-2")) hidden = !column.visible;
                assertTrue("The hidden Type column is in the live view state", hidden);
                // The drawer: plain multi-select and date sections, no toggle, no column rows.
                JComponent drawer = ui.liveFilterBar().drawerContent();
                assertNull(button(f.gui, "Multi-select / absolute dates / view state"));
                for (String name : new String[]{"keypop-live-kinds", "keypop-live-items", "social-date-from"}) {
                    Component part = named(drawer, name, Component.class);
                    assertNotNull(name, part);
                    for (Component c = part; c != drawer; c = c.getParent()) assertTrue(name + " shows without a toggle (" + c + ")", c.isVisible());
                }
                for (JTable table : new JTable[]{ui.events, ui.players, ui.items}) assertNull(named(f.gui, table.getName() + "-column-controls", Component.class));
                return null;
            });
        }
    }

    @Test public void savedModesAreCustomizableTabsThatPersistHideAndRevealTheQuerysMode() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> { f.workspace.selectSession(SessionStore.ALL); return null; });
            await(() -> ArchiveNativeSupport.ready(f.workspace) && f.workspace.displayedPage().matches == 1120);
            edt(() -> {
                CustomizableTabs tabs = savedTabs(f.workspace);
                assertEquals(Arrays.asList(VIEWS), tabs.order());
                assertEquals("events", tabs.selectedId());
                assertEquals(JTabbedPane.class, tabs.component().getClass());
                assertNull("The duplicate export is gone", buttonContaining(f.workspace, "(all matches)"));
                assertTrue(tabs.hide("by-item"));
                assertEquals("events,by-player,by-item|by-item", PropertiesManager.getProperty("ui.tabs.keypops-saved"));
                tabs.move("by-player", -1);   // selects the moved tab, which queries its mode
                return null;
            });
            await(() -> ArchiveNativeSupport.ready(f.workspace) && f.workspace.state().query.facets().mode == KeyPopArchiveClient.Mode.BY_PLAYER);
            edt(() -> {
                CustomizableTabs tabs = savedTabs(f.workspace);
                assertEquals("The next render keeps the order and the hidden tab", Arrays.asList("by-player", "events"), tabs.visibleIds());
                assertEquals("by-player", tabs.selectedId());
                assertEquals("By player", tabs.component().getTitleAt(0));
                // The query's mode is explicit content: a query for a hidden mode brings its tab back.
                KeyPopArchiveClient.Facets facets = f.workspace.state().query.facets(); facets.mode = KeyPopArchiveClient.Mode.BY_ITEM;
                f.workspace.changeQuery(f.workspace.state().query.withFacets(facets));
                return null;
            });
            await(() -> ArchiveNativeSupport.ready(f.workspace) && f.workspace.state().query.facets().mode == KeyPopArchiveClient.Mode.BY_ITEM);
            edt(() -> {
                CustomizableTabs tabs = savedTabs(f.workspace);
                assertEquals("by-item", tabs.selectedId());
                assertEquals(Collections.emptySet(), tabs.hiddenIds());
                assertEquals("by-player,events,by-item|", PropertiesManager.getProperty("ui.tabs.keypops-saved"));
                tabs.component().setSelectedIndex(tabs.visibleIds().indexOf("events"));
                return null;
            });
            await(() -> ArchiveNativeSupport.ready(f.workspace) && f.workspace.state().query.facets().mode == KeyPopArchiveClient.Mode.EVENTS);
            edt(() -> { assertEquals("events", savedTabs(f.workspace).selectedId()); return null; });
        }
    }

    @Test public void liveAndSavedTimesAreRelativeInSimpleAndAbsoluteInAnalyst() throws Exception {
        DisplayModeModel mode = model(DisplayModeModel.Mode.SIMPLE);
        Instant recent = Instant.now().minusSeconds(12 * 60), old = Instant.now().minusSeconds(20L * 24 * 3600);
        edt(() -> {
            KeyPopHistory history = new KeyPopHistory();
            history.add(new KeyPopEvent(old, "Bo", "The Shatters", KeyPopEvent.Kind.KEY));
            history.add(new KeyPopEvent(recent, "Ann", "Lost Halls", KeyPopEvent.Kind.KEY));
            KeyPopDashboard ui = new KeyPopDashboard(history, false, mode);
            // Events Time (column-0), By player Last pop (column-7), By dungeon / item Last pop (column-4).
            Object[][] cells = {{ui.events, "column-0"}, {ui.players, "column-7"}, {ui.items, "column-4"}};
            for (Object[] cell : cells) {
                JTable table = (JTable) cell[0]; int column = table.getColumnModel().getColumnIndex(cell[1]);
                int newest = row(table, column, recent), oldest = row(table, column, old);
                JLabel shown = render(table, newest, column);
                assertEquals(table.getName(), KitFormat.relative(recent.toEpochMilli()), shown.getText());
                assertEquals("The absolute time and zone are in the tooltip", DisplayFormat.formatTimestamp(recent) + " (" + DisplayFormat.timestampZoneLabel() + ")", shown.getToolTipText());
                assertEquals("A 20-day-old pop reads as a date", KitFormat.relative(old.toEpochMilli()), render(table, oldest, column).getText());
                assertEquals("The model keeps the instant", recent, table.getValueAt(newest, column));
            }
            ui.search.setText("min ago"); assertEquals("Relative words match nothing", 0, ui.events.getRowCount()); ui.search.setText("");
            mode.set(DisplayModeModel.Mode.ANALYST);
            for (Object[] cell : cells) {
                JTable table = (JTable) cell[0]; int column = table.getColumnModel().getColumnIndex(cell[1]);
                JLabel shown = render(table, row(table, column, recent), column);
                assertEquals(table.getName(), DisplayFormat.formatTimestamp(recent), shown.getText());
                assertTrue(shown.getToolTipText().contains(DisplayFormat.timestampZoneLabel()));
            }
            return null;
        });
        // Saved: the time column of the rendered page follows the same mode.
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); String session;
        try (SessionStore source = new SessionStore(root, true, "keypop-times")) {
            session = source.currentId();
            source.append("keypops", new KeyPopEvent(recent, "Ann", "Lost Halls", KeyPopEvent.Kind.KEY)); source.flush();
        }
        mode.set(DisplayModeModel.Mode.SIMPLE);
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore store = new SessionStore(root, false, "reader")) {
            KeyPopArchiveClient client = new KeyPopArchiveClient(scratch, mode);
            ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> ws =
                edt(() -> SessionPanel.queried(store, "keypops", new JPanel(), client, memory.states));
            try {
                edt(() -> { ws.changeQuery(KeyPopArchiveClient.query().withScope(session)); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 1);
                edt(() -> {
                    JTable rows = named(ws, "keypop-archive-rows", JTable.class); int column = rows.getColumnModel().getColumnIndex("time");
                    JLabel shown = render(rows, 0, column);
                    assertEquals(KitFormat.relative(recent.toEpochMilli()), shown.getText());
                    assertEquals(DisplayFormat.formatTimestamp(recent) + " (" + DisplayFormat.timestampZoneLabel() + ")", shown.getToolTipText());
                    mode.set(DisplayModeModel.Mode.ANALYST);
                    assertEquals(DisplayFormat.formatTimestamp(recent), render(rows, 0, column).getText());
                    assertEquals(recent, rows.getValueAt(0, column));
                    return null;
                });
            } finally { edt(() -> { ws.close(); return null; }); }
        }
    }

    @Test public void exportsAreIdenticalInSimpleAndAnalyst() throws Exception {
        DisplayModeModel mode = model(DisplayModeModel.Mode.SIMPLE);
        Instant recent = Instant.now().minusSeconds(5 * 60);
        Path out = temp.newFolder().toPath();
        edt(() -> {
            KeyPopHistory history = new KeyPopHistory();
            history.add(new KeyPopEvent(recent, "Ann", "Lost Halls", KeyPopEvent.Kind.KEY)); history.add(new KeyPopEvent(recent.plusSeconds(30), "Bo", "Vial", KeyPopEvent.Kind.VIAL));
            KeyPopDashboard ui = new KeyPopDashboard(history, false, mode);
            Map<String, String> simple = new LinkedHashMap<>(), analyst = new LinkedHashMap<>();
            for (Map<String, String> into : Arrays.asList(simple, analyst)) {
                for (int tab = 1; tab < 3; tab++) { ui.tabs.setSelectedIndex(tab); into.put(VIEWS[tab], ui.summaryCsv()); }
                Path events = out.resolve(into == simple ? "simple.csv" : "analyst.csv");
                KeyPopDashboard.writeCsv(events, ui.filteredEvents()); into.put("events", new String(Files.readAllBytes(events), StandardCharsets.UTF_8));
                mode.set(DisplayModeModel.Mode.ANALYST);
            }
            assertEquals("Live exports ignore the mode", simple, analyst);
            assertTrue("Summary exports keep absolute UTC instants", simple.get("by-player").contains(recent.plusSeconds(30).toString()));
            assertFalse(simple.get("by-item").contains("min ago"));
            return null;
        });
        try (Fixture f = new Fixture()) {
            edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE); f.workspace.selectSession(SessionStore.ALL); return null; });
            await(() -> ArchiveNativeSupport.ready(f.workspace) && f.workspace.displayedPage().matches == 1120);
            JsonArray simple = ArchiveNativeSupport.json(edt(() -> f.workspace.exportTo(out, "simple", ExportSelection.page(0, 50), ArchiveExport.Format.JSON)).get(15, TimeUnit.SECONDS)).getAsJsonArray("rows");
            edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); return null; });
            await(() -> ArchiveNativeSupport.more(f.workspace).item("Export page…").isEnabled());   // the first export has finished on the EDT
            JsonArray analyst = ArchiveNativeSupport.json(edt(() -> f.workspace.exportTo(out, "analyst", ExportSelection.page(0, 50), ArchiveExport.Format.JSON)).get(15, TimeUnit.SECONDS)).getAsJsonArray("rows");
            assertEquals("Saved exports ignore the mode", simple, analyst);
            assertEquals(50, simple.size());
        }
    }

    // ---- helpers ----

    private static KeyPopHistory live() throws Exception {
        Field field = KeypopGUI.class.getDeclaredField("history"); field.setAccessible(true);
        return (KeyPopHistory) field.get(null);
    }
    /** Five recent pops in the live buffer, minutes apart, for the captures. */
    private static void seedLiveBuffer() throws Exception {
        KeyPopHistory history = live(); history.clear(); Instant now = Instant.now();
        String[][] pops = {{"Aster", "Lost Halls", "KEY"}, {"Wren", "The Shatters", "KEY"}, {"Nova", "Vial", "VIAL"}, {"Aster", "Shield Rune", "RUNE"}, {"Kai", "Inc", "INC"}};
        for (int i = 0; i < pops.length; i++) history.add(new KeyPopEvent(now.minusSeconds(60L * (pops.length - i) * 7), pops[i][0], pops[i][1], KeyPopEvent.Kind.valueOf(pops[i][2])));
    }
    private static DisplayModeModel model(DisplayModeModel.Mode initial) {
        return new DisplayModeModel(key -> initial == DisplayModeModel.Mode.ANALYST ? "analyst" : "simple", (key, value) -> { });
    }
    private static List<String> values(KeyPopDashboard ui) { List<String> result = new ArrayList<>(); for (StatTile tile : ui.metrics) result.add(tile.valueText()); return result; }
    private static int y(Component component, Component root) { return SwingUtilities.convertPoint(component, 0, 0, root).y; }
    /** The rendered cell; its tooltip must be read in this EDT turn, before the renderer's deferred reset. */
    private static JLabel render(JTable table, int row, int column) { return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column); }
    private static int row(JTable table, int column, Instant value) {
        for (int row = 0; row < table.getRowCount(); row++) if (value.equals(table.getValueAt(row, column))) return row;
        throw new AssertionError("No row at " + value + " in " + table.getName());
    }
    private static CustomizableTabs savedTabs(ArchiveWorkspace<?, ?, ?> workspace) {
        JTabbedPane pane = named(workspace, "keypop-archive-tabs", JTabbedPane.class);
        assertNotNull("The saved mode tabs", pane);
        Object tabs = pane.getClientProperty(CustomizableTabs.class);
        assertTrue("keypop-archive-tabs is a CustomizableTabs component", tabs instanceof CustomizableTabs);
        return (CustomizableTabs) tabs;
    }
    private static AbstractButton buttonContaining(java.awt.Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && ((AbstractButton) child).getText() != null && ((AbstractButton) child).getText().contains(text)) return (AbstractButton) child;
            if (child instanceof java.awt.Container) { AbstractButton found = buttonContaining((java.awt.Container) child, text); if (found != null) return found; }
        }
        return null;
    }

    /** A live Key-pops page in its workspace over two synthetic saved sessions (1,120 pops), with in-memory view states. */
    private final class Fixture implements AutoCloseable {
        final SessionStore store; final KeypopGUI gui;
        final ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> workspace;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath();
            for (int session = 0; session < 2; session++) try (SessionStore source = new SessionStore(root, true, "native-keypops")) {
                KeyPopNativeFixtures.seed(source, LocalDateTime.of(2026, 9, 20, 12, 0).toInstant(ZoneOffset.UTC), session); source.flush();
            }
            ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
            store = new SessionStore(root, true, "native-reader");
            gui = edt(KeypopGUI::new);
            workspace = edt(() -> (ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort>) gui.workspace(store, scratch, memory.states));
        }
        @Override public void close() throws Exception {
            try { edt(() -> { workspace.close(); return null; }); } finally { store.close(); }
        }
    }
}
