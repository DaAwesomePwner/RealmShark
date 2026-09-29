package tomato.gui.activity;

import java.awt.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveNativeSupport.Memory;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.HistoryTables;
import tomato.gui.history.SessionPanel;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.CollectionControl;
import tomato.gui.modern.DisplayFormat;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;
import static tomato.gui.activity.ActivityLiveStateTest.retainedLog;

/**
 * P6b Task 11: the Timeline restyle (spec §6.7, R2 decision 8) and the mode-aware time columns of Runs, Timeline and Resources.
 * Visit and kind sit in a Filters drawer with chips, collection and Pause are one status line under the filter row, the export and
 * the view state are ⋯ items, Meaning is Analyst-only (with its width kept) and times read relatively in Simple. Synthetic history
 * and in-memory modes and view states only.
 */
public class TimelineRestyleTest {
    private static final String[] FILTER_KEYS = {"ui.filters.activity-runs.open", "ui.filters.activity-timeline.open", "ui.filters.activity-combat.open", "ui.filters.timeline.open"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> prefs = new HashMap<>(), saved = new HashMap<>();
    /** Simple until a test switches it; never the application's mode. */
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
    private final long now = System.currentTimeMillis();

    @Before public void isolate() { for (String key : FILTER_KEYS) { saved.put(key, util.PropertiesManager.getProperty(key)); util.PropertiesManager.setProperties(key, "false"); } }
    @After public void restore() { for (String key : FILTER_KEYS) util.PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key)); }

    /** Two dungeon visits (3 h and 12 min ago), three events each; the newest event is 12 minutes old. */
    private ActivityJournal.State history() {
        ActivityJournal.State state = new ActivityJournal.State();
        String[] maps = {"Lost Halls", "Ice Citadel"};
        long[] started = {now - 3 * 3_600_000L, now - 20 * 60_000L};
        String[] kinds = {"Equipment changed", "Resources", "Party roster"};
        for (int v = 0; v < maps.length; v++) {
            ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "synthetic-visit-" + v; visit.map = maps[v];
            visit.started = started[v]; visit.lastSeen = visit.ended = started[v] + 6 * 60_000L;
            visit.conditionObservedMillis = 300_000; visit.conditions.put("Damaging", 120_000L);
            state.visits.add(visit);
            for (int e = 0; e < kinds.length; e++) {
                ActivityJournal.Entry entry = new ActivityJournal.Entry(); entry.id = visit.id + "-event-" + e; entry.visitId = visit.id; entry.map = maps[v];
                entry.time = started[v] + e * 4 * 60_000L; entry.kind = kinds[e]; entry.detail = "Meaning of " + entry.id;
                entry.values = new LinkedHashMap<>(); entry.values.put("hp", 700); entry.values.put("mp", 150); entry.values.put("partyId", 4321); entry.values.put("memberCount", 3);
                state.entries.add(entry);
            }
        }
        return state;
    }
    private ActivityPanel panel(DiscoveryLog log, ActivityPanel.Mode which) throws Exception {
        return edt(() -> { ActivityPanel panel = new ActivityPanel(log, which, mode); panel.refresh(); return panel; });
    }
    private static JTable table(Container panel) { return named(panel, JTable.class, "activity-table"); }
    private static FilterBar bar(Container panel, ActivityPanel.Mode which) {
        return named(panel, FilterBar.class, "activity-" + which.name().toLowerCase(Locale.ROOT) + "-filter-bar");
    }

    @Test public void visitAndKindLiveInTheFiltersDrawerWithChips() throws Exception {
        ActivityJournal.State history = history();
        String older = history.visits.get(0).id, newer = history.visits.get(1).id;
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history)) {
            ActivityPanel timeline = panel(log, ActivityPanel.Mode.TIMELINE);
            await(() -> table(timeline).getRowCount() == 6 && named(timeline, JComboBox.class, "activity-visit").getItemCount() == 3);
            edt(() -> {
                FilterBar bar = bar(timeline, ActivityPanel.Mode.TIMELINE);
                assertNotNull("Visit and kind live in a Filters drawer", bar.drawerContent());
                JComboBox<?> visit = named(timeline, JComboBox.class, "activity-visit"), kind = named(timeline, JComboBox.class, "activity-kind");
                assertTrue(SwingUtilities.isDescendingFrom(visit, bar.drawerContent()));
                assertTrue(SwingUtilities.isDescendingFrom(kind, bar.drawerContent()));
                JTextField search = named(timeline, JTextField.class, "activity-search");
                assertTrue("Search stays in the row", SwingUtilities.isDescendingFrom(search, bar));
                assertFalse(SwingUtilities.isDescendingFrom(search, bar.drawerContent()));
                assertFalse("The drawer starts closed", bar.drawerOpen());
                assertEquals("The defaults narrow nothing", Collections.emptyList(), ArchiveNativeSupport.chipLabels(bar));
                timeline.selectVisit(newer); kind.setSelectedItem("Party");
                String label = "Visit: " + ActivityPanel.time(history.visits.get(1).started) + " · Ice Citadel";
                assertEquals(Arrays.asList(label, "Activity: Party"), ArchiveNativeSupport.chipLabels(bar));
                assertEquals(2, bar.activeCount());
                assertEquals(1, table(timeline).getRowCount());
                ArchiveNativeSupport.removeChip(bar, "Activity: Party");
                assertEquals("All activities", kind.getSelectedItem());
                assertEquals(Collections.singletonList(label), ArchiveNativeSupport.chipLabels(bar));
                assertEquals(3, table(timeline).getRowCount());
                named(timeline, AbstractButton.class, "activity-timeline-clear-filters").doClick();
                assertEquals("Clear shows all visits again", 0, visit.getSelectedIndex());
                assertEquals(Collections.emptyList(), ArchiveNativeSupport.chipLabels(bar));
                assertEquals(6, table(timeline).getRowCount());
                return null;
            });
            ActivityPanel combat = panel(log, ActivityPanel.Mode.COMBAT);
            await(() -> named(combat, JComboBox.class, "activity-visit").getItemCount() == 2 && table(combat).getRowCount() == 1);
            edt(() -> {
                FilterBar bar = bar(combat, ActivityPanel.Mode.COMBAT);
                assertNotNull("Resources keeps its visit in a Filters drawer too", bar.drawerContent());
                assertTrue(SwingUtilities.isDescendingFrom(named(combat, JComboBox.class, "activity-visit"), bar.drawerContent()));
                assertNull("Resources has no activity type", named(combat, JComboBox.class, "activity-kind"));
                assertEquals("The newest visit is the default: no chip", Collections.emptyList(), ArchiveNativeSupport.chipLabels(bar));
                combat.selectVisit(older);
                String label = "Visit: " + ActivityPanel.time(history.visits.get(0).started) + " · Lost Halls";
                assertEquals(Collections.singletonList(label), ArchiveNativeSupport.chipLabels(bar));
                ArchiveNativeSupport.removeChip(bar, label);
                assertEquals("Removing the chip returns to the newest visit", 0, named(combat, JComboBox.class, "activity-visit").getSelectedIndex());
                assertEquals(Collections.emptyList(), ArchiveNativeSupport.chipLabels(bar));
                return null;
            });
        }
    }

    @Test public void collectionAndPauseAreOneStatusLineUnderTheFilterRow() throws Exception {
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history())) {
            for (ActivityPanel.Mode which : ActivityPanel.Mode.values()) {
                ActivityPanel panel = panel(log, which);
                edt(() -> {
                    FilterBar bar = bar(panel, which);
                    JComponent line = named(panel, JComponent.class, "activity-status-line");
                    assertNotNull(which + ": a status line", line);
                    assertFalse(which + ": the status line is not part of the filter row", SwingUtilities.isDescendingFrom(line, bar));
                    Container top = bar.getParent();
                    assertSame(which + ": the status line sits directly under the filter row", top, line.getParent());
                    assertEquals(Arrays.asList(top.getComponents()).indexOf(bar) + 1, Arrays.asList(top.getComponents()).indexOf(line));
                    assertTrue(SwingUtilities.isDescendingFrom(named(panel, CollectionControl.class, null), line));
                    assertTrue("Pause this view stays a checkbox on the status line", SwingUtilities.isDescendingFrom(checkbox(panel, "Pause this view"), line));
                    if (which == ActivityPanel.Mode.RUNS) assertTrue(SwingUtilities.isDescendingFrom(named(panel, JComboBox.class, "run-duration-unit"), line));
                    assertTrue(which + ": the export is not a button", all(panel, JButton.class).stream().noneMatch(b -> String.valueOf(b.getText()).startsWith("Export displayed history")));
                    return null;
                });
            }
        }
    }

    @Test public void theExportAndTheViewStateAreOverflowItems() throws Exception {
        Memory memory = new Memory();
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history())) {
            for (ActivityPanel.Mode which : ActivityPanel.Mode.values()) {
                String key = "activity-live-" + which.name().toLowerCase(Locale.ROOT);
                ActivityPanel panel = edt(() -> { ActivityPanel p = new ActivityPanel(log, which, mode); p.bindViewState(memory.states); p.refresh(); return p; });
                await(() -> table(panel).getRowCount() > 0);
                edt(() -> {
                    OverflowMenu more = bar(panel, which).overflow();
                    JMenuItem export = more.item("Export displayed history…"), save = more.item("Save view state"), reset = more.item("Reset saved view state");
                    assertNotNull(which + ": ⋯ Export displayed history…", export);
                    assertEquals("activity-export", export.getName());
                    assertTrue(export.getToolTipText(), export.getToolTipText().contains("filters do not limit the export"));
                    assertNotNull(which + ": ⋯ Save view state", save); assertNotNull(which + ": ⋯ Reset saved view state", reset);
                    assertEquals(key + "-save-state", save.getName()); assertEquals(key + "-reset-state", reset.getName());
                    assertTrue(which + ": no view-state buttons", all(panel, JButton.class).stream().noneMatch(b -> "Save view state".equals(b.getText()) || "Reset saved view state".equals(b.getText())));
                    assertFalse(which + ": the view-state line shows only failures", named(panel, JComponent.class, key + "-view-state").isVisible());
                    memory.failSaves = true; save.doClick(); return null;
                });
                await(() -> named(panel, JComponent.class, key + "-view-state").isVisible());
                edt(() -> { memory.failSaves = false; bar(panel, which).overflow().item("Save view state").doClick(); return null; });
                await(() -> !named(panel, JComponent.class, key + "-view-state").isVisible());
                edt(() -> { bar(panel, which).overflow().item("Reset saved view state").doClick(); return null; });
            }
            ActivityPanel timeline = panel(log, ActivityPanel.Mode.TIMELINE);
            await(() -> table(timeline).getRowCount() == 6);
            edt(() -> { bar(timeline, ActivityPanel.Mode.TIMELINE).overflow().item("Export displayed history…").doClick(); return null; });
            await(() -> named(timeline, JLabel.class, null) != null && labelText(timeline).startsWith("Saved activity-"));
            Path written = Paths.get(edt(() -> labelTip(timeline)));
            try { assertTrue("The ⋯ item writes the displayed history", Files.size(written) > 0); } finally { Files.deleteIfExists(written); }
        }
    }

    @Test public void meaningIsAnalystOnlyAndComesBackWithItsWidth() throws Exception {
        Memory memory = new Memory();
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history())) {
            ActivityPanel panel = edt(() -> { ActivityPanel p = new ActivityPanel(log, ActivityPanel.Mode.TIMELINE, mode); p.bindViewState(memory.states); p.refresh(); return p; });
            await(() -> table(panel).getRowCount() == 6);
            edt(() -> {
                JTable table = table(panel);
                assertEquals("Simple hides Meaning", Arrays.asList("column-0", "column-1", "column-2", "column-3"), ids(table));
                assertEquals(Collections.singleton("column-4"), KitTables.modeHidden(table));
                table.setRowSelectionInterval(0, 0);
                assertTrue("The detail pane keeps the meaning", named(panel, JTextArea.class, "activity-detail").getText().contains("Meaning of synthetic-visit-1-event-2"));
                mode.set(DisplayModeModel.Mode.ANALYST);
                assertEquals("Analyst shows it where it stood", Arrays.asList("column-0", "column-1", "column-2", "column-3", "column-4"), ids(table));
                assertEquals("Meaning", table.getColumnName(4));
                table.getColumnModel().getColumn(4).setPreferredWidth(260); table.getColumnModel().getColumn(4).setWidth(260);
                int widths = table.getColumnModel().getColumn(0).getWidth();
                mode.set(DisplayModeModel.Mode.SIMPLE);
                assertEquals(4, table.getColumnCount());
                assertEquals("No other width is refitted on a switch", widths, table.getColumnModel().getColumn(0).getWidth());
                mode.set(DisplayModeModel.Mode.ANALYST);
                assertEquals("column-4", table.getColumnModel().getColumn(4).getIdentifier());
                assertEquals("Its width comes back", 260, table.getColumnModel().getColumn(4).getWidth());
                mode.set(DisplayModeModel.Mode.SIMPLE);
                return null;
            });
            edt(panel::saveViewState).toCompletableFuture().get(5, TimeUnit.SECONDS);
            // A layout saved in Simple keeps Meaning (shown, with its width): a restore in Analyst shows it, and never fails.
            DisplayModeModel analyst = new DisplayModeModel(key -> "analyst", (key, value) -> { });
            ActivityPanel reopened = edt(() -> { ActivityPanel p = new ActivityPanel(log, ActivityPanel.Mode.TIMELINE, analyst); p.bindViewState(memory.states); p.refresh(); return p; });
            await(() -> table(reopened).getRowCount() == 6);
            edt(() -> {
                JTable table = table(reopened);
                assertFalse("The saved state was readable", named(reopened, JComponent.class, "activity-live-timeline-view-state").isVisible());
                assertEquals(Arrays.asList("column-0", "column-1", "column-2", "column-3", "column-4"), ids(table));
                assertEquals(260, table.getColumnModel().getColumn(table.convertColumnIndexToView(4)).getWidth());
                return null;
            });
        }
    }

    @Test public void timesReadRelativelyInSimpleAndAbsolutelyInAnalyst() throws Exception {
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history())) {
            ActivityPanel timeline = panel(log, ActivityPanel.Mode.TIMELINE), runs = panel(log, ActivityPanel.Mode.RUNS);
            await(() -> table(timeline).getRowCount() == 6 && table(runs).getRowCount() == 2);
            edt(() -> {
                assertTimes(table(timeline), 0, named(timeline, JTextField.class, "activity-search"), true);
                assertTimes(table(runs), 1, named(runs, JTextField.class, "activity-search"), true);
                mode.set(DisplayModeModel.Mode.ANALYST);
                assertTimes(table(timeline), 0, named(timeline, JTextField.class, "activity-search"), false);
                assertTimes(table(runs), 1, named(runs, JTextField.class, "activity-search"), false);
                mode.set(DisplayModeModel.Mode.SIMPLE);
                return null;
            });
        }
    }

    /** Row 0's time (newest first): relative text and the absolute tooltip in Simple, the base text in Analyst; search stays absolute. */
    private static void assertTimes(JTable table, int column, JTextField search, boolean simple) {
        Instant value = (Instant) table.getValueAt(0, column);
        String absolute = DisplayFormat.formatTimestamp(value), zone = " (" + DisplayFormat.timestampZoneLabel() + ")";
        JLabel cell = (JLabel) table.prepareRenderer(table.getCellRenderer(0, column), 0, column);
        if (simple) {
            assertEquals(KitFormat.relative(value.toEpochMilli()), cell.getText());
            assertTrue(cell.getText(), cell.getText().endsWith("min ago") || cell.getText().endsWith("h ago"));
            assertEquals("The absolute time is in the tooltip", absolute + zone, cell.getToolTipText());
        } else {
            assertEquals(absolute, cell.getText());
            assertEquals(absolute + zone, cell.getToolTipText());
        }
        int rows = table.getRowCount();
        search.setText(KitFormat.relative(value.toEpochMilli()));
        assertEquals("Typing the relative text matches nothing", 0, table.getRowCount());
        search.setText(absolute);
        assertTrue("Search keeps matching the absolute text", table.getRowCount() >= 1);
        search.setText("");
        assertEquals(rows, table.getRowCount());
        assertEquals("The model keeps the instant", value, table.getModel().getValueAt(table.convertRowIndexToModel(0), column));
    }

    @Test public void theSavedTimeColumnFollowsTheModeAndExportsAreIdenticalInBothModes() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(), output = temp.newFolder().toPath();
        Memory memory = new Memory();
        ActivityJournal.State history = history();
        try (SessionStore store = new SessionStore(root, true, "synthetic")) {
            for (ActivityJournal.Entry entry : history.entries) store.append("timeline", entry);
            for (ActivityJournal.Visit visit : history.visits) store.put("runs", visit.id, visit);
            store.flush();
            ActivityArchiveClient client = new ActivityArchiveClient(ActivityPanel.Mode.TIMELINE, scratch, null, mode);
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace =
                edt(() -> SessionPanel.queried(store, "timeline", new JLabel("Live timeline"), client, memory.states));
            try {
                edt(() -> { workspace.showSaved(); return null; });
                await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().rows.size() == 6);
                String[] copies = new String[3];
                edt(() -> {
                    JTable table = ActivityArchiveUiTest.table(workspace);
                    ZoneId zone = ZoneId.of(workspace.state().query.bounds().zone);
                    int column = table.getColumnModel().getColumnIndex("time");
                    int row = 0; for (int r = 0; r < table.getRowCount(); r++) if (((Instant) table.getValueAt(r, column)).isAfter((Instant) table.getValueAt(row, column))) row = r;
                    Instant value = (Instant) table.getValueAt(row, column);
                    String absolute = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").format(value.atZone(zone));
                    JLabel cell = (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                    assertEquals("Simple: relative", KitFormat.relative(value.toEpochMilli()), cell.getText());
                    assertEquals("Simple: the absolute time and zone in the tooltip", absolute + " (" + zone.getId() + ")", cell.getToolTipText());
                    table.setRowSelectionInterval(row, row); copies[0] = HistoryTables.selectedText(table); copies[2] = value.toString();
                    mode.set(DisplayModeModel.Mode.ANALYST);
                    cell = (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                    assertEquals("Analyst: absolute", absolute, cell.getText());
                    assertEquals("Analyst keeps the zone tooltip", zone.getId(), cell.getToolTipText());
                    copies[1] = HistoryTables.selectedText(table);
                    mode.set(DisplayModeModel.Mode.SIMPLE);
                    return null;
                });
                assertEquals("Copy keeps raw values in both modes", copies[0], copies[1]);
                assertTrue("Copy keeps the raw instant: " + copies[0], copies[0].contains(copies[2]));
                ArchivePage<ActivityQueries.Row> page = edt(workspace::displayedPage);
                byte[][] exports = new byte[2][];
                for (int i = 0; i < 2; i++) {
                    DisplayModeModel.Mode shown = i == 0 ? DisplayModeModel.Mode.SIMPLE : DisplayModeModel.Mode.ANALYST;
                    edt(() -> { mode.set(shown); return null; });
                    try (ArchiveResult.Lease<ActivityQueries.Row> lease = edt(page::lease)) {
                        exports[i] = Files.readAllBytes(client.writeExport(lease, ExportSelection.all(), ArchiveExport.Format.CSV, output, "timeline-" + i, new Cancellation()));
                    }
                }
                assertArrayEquals("Saved exports are identical in both modes", exports[0], exports[1]);
            } finally { edt(() -> { workspace.close(); mode.set(DisplayModeModel.Mode.SIMPLE); return null; }); }
        }
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history)) {
            ActivityPanel timeline = panel(log, ActivityPanel.Mode.TIMELINE);
            await(() -> table(timeline).getRowCount() == 6);
            byte[] simple = Files.readAllBytes(edt(() -> timeline.exportTo(output)).get(5, TimeUnit.SECONDS));
            await(() -> !tomato.gui.modern.FormattingTestSupport.field(timeline, "exporting", Boolean.class));
            edt(() -> { mode.set(DisplayModeModel.Mode.ANALYST); return null; });
            byte[] analyst = Files.readAllBytes(edt(() -> timeline.exportTo(output)).get(5, TimeUnit.SECONDS));
            edt(() -> { mode.set(DisplayModeModel.Mode.SIMPLE); return null; });
            assertArrayEquals("Live exports are identical in both modes", simple, analyst);
        }
    }

    private static List<Object> ids(JTable table) {
        List<Object> ids = new ArrayList<>(); for (int i = 0; i < table.getColumnCount(); i++) ids.add(table.getColumnModel().getColumn(i).getIdentifier()); return ids;
    }
    /** The export status line ("Saved activity-….json"): the panel's label whose tooltip is the written file. */
    private static String labelText(Container panel) { JLabel label = exportLabel(panel); return label == null ? "" : label.getText(); }
    private static String labelTip(Container panel) { return exportLabel(panel).getToolTipText(); }
    private static JLabel exportLabel(Container panel) {
        for (JLabel label : all(panel, JLabel.class)) if (label.getText() != null && label.getText().startsWith("Saved activity-")) return label;
        return null;
    }
    private static JCheckBox checkbox(Container root, String text) {
        for (JCheckBox box : all(root, JCheckBox.class)) if (text.equals(box.getText())) return box;
        return null;
    }
    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container) found.addAll(all((Container) child, type));
        }
        return found;
    }
}
