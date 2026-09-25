package tomato.gui.activity;

import com.google.gson.Gson;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.modern.FormattingTestSupport.*;

public class ActivityFormattingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void activityRendersTypedCountsPercentagesAndLocalTimesWithoutChangingExportedHistory() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        Path directory = temp.newFolder().toPath();
        ActivityJournal.State state = new ActivityJournal.State();
        ActivityJournal.Visit visit = new ActivityJournal.Visit();
        visit.id = "visit-12345"; visit.map = "Lost Halls";
        visit.started = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(); visit.lastSeen = visit.started + 1234000;
        visit.useRequests = 9007199254740993L; visit.exaltIncrease = -1234;
        visit.hpMin = 0; visit.hpMax = 1234567; visit.partyId = 12345; visit.rosterSize = 1234;
        visit.conditionObservedMillis = 1000000; visit.conditions.put("Damaging", 105000L); visit.conditions.put("Quiet", 0L);
        visit.extraConditions.put("Unobserved", 0L); state.visits.add(visit);
        ActivityJournal.ResourcePoint point = new ActivityJournal.ResourcePoint();
        point.time = visit.started + 10500; point.hp = 1234567; point.mp = 0; visit.resourceTimeline.add(point);
        Files.write(directory.resolve("activity-history.json"), new Gson().toJson(state).getBytes(StandardCharsets.UTF_8));
        DiscoveryLog log = new DiscoveryLog(directory); log.setSaving(false);
        ActivityPanel[] runs = new ActivityPanel[1], combat = new ActivityPanel[1];
        AtomicReference<SwingWorker<Path,Void>> export = new AtomicReference<>();
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            SwingUtilities.invokeAndWait(() -> {
                runs[0] = new ActivityPanel(log, ActivityPanel.Mode.RUNS); runs[0].refresh();
                combat[0] = new ActivityPanel(log, ActivityPanel.Mode.COMBAT); combat[0].refresh();
            });
            await(() -> named(runs[0], "activity-table", JTable.class).getRowCount() == 1
                && named(combat[0], "activity-table", JTable.class).getRowCount() == 3);
            SwingUtilities.invokeAndWait(() -> {
                JTable table = named(runs[0], "activity-table", JTable.class);
                assertEquals(Instant.class, table.getColumnClass(1)); assertEquals(Long.class, table.getColumnClass(6));
                assertEquals("2026-01-02 03:04:05", cell(table, 0, 1));
                assertEquals("9,007,199,254,740,993", cell(table, 0, 6));
                assertEquals("-1,234", cell(table, 0, 5));
                JTable buffs = named(combat[0], "activity-table", JTable.class);
                assertEquals("10.5%", cell(buffs, 0, 3)); assertEquals("0.0%", cell(buffs, 1, 3));
                assertEquals("—", cell(buffs, 2, 3));
                CombatTimelineChart chart = named(combat[0], "combat-timeline-chart", CombatTimelineChart.class);
                chart.getActionMap().get("first-sample").actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, "first-sample"));
                assertTrue(chart.getInspectionSummary().contains("10.5s · HP 1,234,567 · MP 0"));
                assertEquals(chart.getInspectionSummary(), named(combat[0], "combat-sample-summary", JTextArea.class).getText());
                export.set(runs[0].exportTo(directory));
            });
            byte[] before = Files.readAllBytes(export.get().get(5, TimeUnit.SECONDS));
            await(() -> !field(runs[0], "exporting", Boolean.class));
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            SwingUtilities.invokeAndWait(() -> {
                JTable table = named(runs[0], "activity-table", JTable.class);
                assertEquals("2026-01-02 04:04:05", cell(table, 0, 1));
                assertEquals("9.007.199.254.740.993", cell(table, 0, 6));
                assertEquals("-1.234", cell(table, 0, 5));
                table.setRowSelectionInterval(0, 0);
                String details = named(runs[0], "activity-detail", JTextArea.class).getText();
                assertTrue(details.contains("Europe/Berlin")); assertTrue(details.contains("party 12345, 1.234 observed members"));
                assertTrue(details.contains("HP: 0–1.234.567 · MP: —"));
                JTable buffs = named(combat[0], "activity-table", JTable.class);
                assertEquals("10,5%", cell(buffs, 0, 3)); assertEquals("0,0%", cell(buffs, 1, 3));
                assertEquals("—", cell(buffs, 2, 3)); assertEquals("1.000", cell(buffs, 0, 2));
                assertEquals(Double.class, buffs.getColumnClass(3));
                buffs.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
                assertEquals(10.5, buffs.getValueAt(0, 3)); assertEquals(0.0, buffs.getValueAt(1, 3));
                CombatTimelineChart chart = named(combat[0], "combat-timeline-chart", CombatTimelineChart.class);
                chart.getActionMap().get("first-sample").actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, "first-sample"));
                assertTrue(chart.getInspectionSummary().contains("10,5s · HP 1.234.567 · MP 0"));
                assertEquals(chart.getInspectionSummary(), chart.getAccessibleContext().getAccessibleDescription());
                assertEquals(chart.getInspectionSummary(), named(combat[0], "combat-sample-summary", JTextArea.class).getText());
                export.set(runs[0].exportTo(directory));
            });
            assertArrayEquals(before, Files.readAllBytes(export.get().get(5, TimeUnit.SECONDS)));
        } finally { log.close(); Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }

    @Test public void chartInspectionAndCoordinateTooltipsFollowLocaleWithoutChangingSamplesOrFlags() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivityJournal.Visit visit = new ActivityJournal.Visit();
                visit.id = "visit-12345"; visit.map = "Lost Halls"; visit.started = 1000;
                for (int i = 0; i < 1001; i++) {
                    ActivityJournal.ResourcePoint point = new ActivityJournal.ResourcePoint();
                    point.time = visit.started + i * 10500L;
                    point.hp = i == 1 ? 0 : 1234567; point.mp = i == 0 ? null : i == 1 ? -1234 : 0;
                    visit.resourceTimeline.add(point);
                }
                visit.lastSeen = visit.resourceTimeline.get(1000).time;
                ActivityJournal.ConditionSlice slice = new ActivityJournal.ConditionSlice();
                slice.start = visit.started; slice.end = visit.lastSeen + 1; slice.primary = 0; slice.secondary = null;
                visit.conditionTimeline.add(slice);
                Gson json = new Gson(); String before = json.toJson(visit);
                Locale.setDefault(Locale.Category.FORMAT, Locale.US);
                CombatTimelineChart chart = new CombatTimelineChart(); chart.setSize(760, 400); chart.setVisit(visit);
                assertTrue(chart.getInspectionSummary().startsWith("1,001 resource samples"));
                chart.getActionMap().get("first-sample").actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, "first-sample"));
                assertTrue(chart.getInspectionSummary().startsWith("Sample 1 of 1,001 · 0.0s · HP 1,234,567 · MP —"));
                chart.getActionMap().get("next-sample").actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, "next-sample"));
                assertTrue(chart.getInspectionSummary().startsWith("Sample 2 of 1,001 · 10.5s · HP 0 · MP -1,234"));
                String[] notification = new String[1];
                chart.addPropertyChangeListener("inspectionSummary", event -> notification[0] = (String)event.getNewValue());
                Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
                chart.setVisit(visit); // Identical plot data must still refresh cached accessible text for a changed locale.
                assertTrue(chart.getInspectionSummary().startsWith("Sample 2 of 1.001 · 10,5s · HP 0 · MP -1.234"));
                assertEquals(chart.getInspectionSummary(), notification[0]);
                assertEquals(chart.getInspectionSummary(), chart.getAccessibleContext().getAccessibleDescription());
                chart.getActionMap().get("first-sample").actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, "first-sample"));
                assertTrue(chart.getInspectionSummary().startsWith("Sample 1 of 1.001 · 0,0s · HP 1.234.567 · MP —"));
                chart.getActionMap().get("last-sample").actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, "last-sample"));
                assertTrue(chart.getInspectionSummary().startsWith("Sample 1.001 of 1.001 · 10.500,0s · HP 1.234.567 · MP 0"));
                // The right plot boundary is the final timestamp, independent of font metrics and lane geometry.
                MouseEvent end = new MouseEvent(chart, MouseEvent.MOUSE_MOVED, 0, 0, chart.getWidth() - 20, 50, 0, false);
                assertEquals("10.500,0s · HP 1.234.567 · MP 0 (nearest sample)", chart.getToolTipText(end));
                assertEquals(before, json.toJson(visit));
                assertEquals(Integer.valueOf(0), visit.conditionTimeline.get(0).primary);
                assertNull(visit.conditionTimeline.get(0).secondary);
                assertEquals(Integer.valueOf(1234567), visit.resourceTimeline.get(0).hp);
                assertNull(visit.resourceTimeline.get(0).mp);
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void runMinutesAndSecondsKeepNumericSortingFrozenSelectionAndStoredTimestamps() throws Exception {
        Path directory = temp.newFolder().toPath();
        ActivityJournal.State state = new ActivityJournal.State();
        for (int duration : new int[]{30, 90}) {
            ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "run-" + duration; visit.map = "Ice Citadel";
            visit.started = 1000; visit.lastSeen = visit.ended = 1000 + duration * 1000L;
            visit.completionEvidence = "Server victory notification"; visit.status = "Completed";
            state.visits.add(visit);
        }
        Files.write(directory.resolve("activity-history.json"), new Gson().toJson(state).getBytes(StandardCharsets.UTF_8));
        DiscoveryLog log = new DiscoveryLog(directory); log.setSaving(false);
        ActivityPanel[] panel = new ActivityPanel[1]; Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            SwingUtilities.invokeAndWait(() -> { panel[0] = new ActivityPanel(log, ActivityPanel.Mode.RUNS); panel[0].refresh(); });
            await(() -> named(panel[0], "activity-table", JTable.class).getRowCount() == 2);
            SwingUtilities.invokeAndWait(() -> {
                JTable table = named(panel[0], "activity-table", JTable.class);
                assertEquals("Observed minutes", table.getColumnName(2));
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
                assertEquals(1.5, table.getValueAt(0, 2)); assertEquals("1.5", cell(table, 0, 2));
                assertEquals("Completed", table.getValueAt(0, 3));
                table.setRowSelectionInterval(0, 0);
                JCheckBox freeze = field(panel[0], "freeze", JCheckBox.class); freeze.setSelected(true);
                JComboBox<?> units = named(panel[0], "run-duration-unit", JComboBox.class);
                units.setSelectedItem(RunDurationUnit.SECONDS);
                assertEquals("Observed seconds", table.getColumnName(2));
                assertEquals(90.0, table.getValueAt(0, 2)); assertEquals("90", cell(table, 0, 2));
                assertEquals(0, table.getSelectedRow());
                assertTrue(named(panel[0], "activity-detail", JTextArea.class).getText().contains("Server victory notification"));
                Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
                units.setSelectedItem(RunDurationUnit.MINUTES);
                assertEquals("1,5", cell(table, 0, 2));
            });
            assertEquals(91000, log.activityHistory().visits.get(1).lastSeen);
        } finally { log.close(); Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void unchangedAndFrozenHistoriesReformatAndSearchDisplayedValuesWithoutObserverCopies() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        ActivityJournal.State history = presentationHistory();
        Path directory = temp.newFolder().toPath(); Gson json = new Gson();
        Files.write(directory.resolve("activity-history.json"), json.toJson(history).getBytes(StandardCharsets.UTF_8));
        DiscoveryLog log = new DiscoveryLog(directory); log.setSaving(false);
        ActivityPanel[] panels = new ActivityPanel[3];
        DiscoveryLog.ActivitySnapshot[] retained = new DiscoveryLog.ActivitySnapshot[3];
        ActivityJournal.State[] states = new ActivityJournal.State[3];
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            SwingUtilities.invokeAndWait(() -> {
                ActivityPanel.Mode[] modes = {ActivityPanel.Mode.RUNS, ActivityPanel.Mode.TIMELINE, ActivityPanel.Mode.COMBAT};
                for (int i = 0; i < panels.length; i++) { panels[i] = new ActivityPanel(log, modes[i]); panels[i].refresh(); }
            });
            await(() -> activityTable(panels[0]).getRowCount() == 3 && activityTable(panels[1]).getRowCount() == 1000
                && named(panels[2], "activity-visit", JComboBox.class).getItemCount() == 3 && idle(panels));
            SwingUtilities.invokeAndWait(() -> { panels[1].selectVisit("visit-12345"); panels[2].selectVisit("visit-12345"); });
            await(() -> activityTable(panels[2]).getRowCount() == 1 && idle(panels));
            SwingUtilities.invokeAndWait(() -> {
                for (int i = 0; i < panels.length; i++) {
                    retained[i] = field(panels[i], "displayed", DiscoveryLog.ActivitySnapshot.class);
                    states[i] = field(panels[i], "state", ActivityJournal.State.class);
                }
                activitySearch(panels[0]).setText("2026-01-02 04:04:05"); assertEquals(0, activityTable(panels[0]).getRowCount());
                activitySearch(panels[1]).setText("12345"); activityTable(panels[1]).setRowSelectionInterval(0, 0);
                activitySearch(panels[2]).setText("Damaging"); activityTable(panels[2]).setRowSelectionInterval(0, 0);
                CombatTimelineChart chart = named(panels[2], "combat-timeline-chart", CombatTimelineChart.class);
                chart.getActionMap().get("first-sample").actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, "first-sample"));
                assertTrue(named(panels[1], "activity-summary", JLabel.class).getText().contains("1,000 retained events"));
            });
            String raw = json.toJson(states), copies = json.toJson(log.activitySnapshotStats());
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            SwingUtilities.invokeAndWait(() -> {
                for (ActivityPanel panel : panels) panel.refresh();
                assertEquals(1, activityTable(panels[0]).getRowCount()); // The query itself did not change.
                activityTable(panels[0]).setRowSelectionInterval(0, 0);
                assertRetainedPresentation(panels, retained, states, true, "Europe/Berlin", "04:04:05");
                assertEquals("Party 12345 · 1.234 observed members", activityTable(panels[1]).getValueAt(0, 3));
                activitySearch(panels[0]).setText("1.234"); assertEquals(1, activityTable(panels[0]).getRowCount());
                activitySearch(panels[0]).setText("1234"); assertEquals(1, activityTable(panels[0]).getRowCount());
                activitySearch(panels[1]).setText("2026-01-02 04:04:05"); assertEquals(1000, activityTable(panels[1]).getRowCount());
                activitySearch(panels[1]).setText("2026-01-02T03:04:05Z"); assertEquals(1000, activityTable(panels[1]).getRowCount());
                activitySearch(panels[1]).setText("1.234"); assertEquals(1000, activityTable(panels[1]).getRowCount());
                activitySearch(panels[1]).setText("12.345"); assertEquals(0, activityTable(panels[1]).getRowCount()); // IDs stay raw.
                activitySearch(panels[1]).setText(".*"); assertEquals(0, activityTable(panels[1]).getRowCount());
                activitySearch(panels[1]).setText("["); assertEquals(1000, activityTable(panels[1]).getRowCount());
                activitySearch(panels[2]).setText("10,5%"); assertEquals(1, activityTable(panels[2]).getRowCount());
                activitySearch(panels[2]).setText("10.5"); assertEquals(1, activityTable(panels[2]).getRowCount());
                JTable runs = activityTable(panels[0]); activitySearch(panels[0]).setText("");
                runs.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
                assertEquals(Instant.class, runs.getColumnClass(1));
                assertEquals(Instant.parse("2026-10-25T00:50:00Z"), runs.getValueAt(1, 1));
                assertEquals(Instant.parse("2026-10-25T01:10:00Z"), runs.getValueAt(2, 1));
                assertEquals("2026-10-25 02:50:00", cell(runs, 1, 1)); assertEquals("2026-10-25 02:10:00", cell(runs, 2, 1));
                assertEquals(Long.class, runs.getColumnClass(6)); assertEquals(Double.class, activityTable(panels[2]).getColumnClass(3));
                activitySearch(panels[0]).setText("Lost Halls"); activitySearch(panels[1]).setText("12345"); activitySearch(panels[2]).setText("Damaging");
                for (ActivityPanel panel : panels) activityTable(panel).setRowSelectionInterval(0, 0);
            });
            await(() -> idle(panels));
            assertEquals(copies, json.toJson(log.activitySnapshotStats())); assertEquals(raw, json.toJson(states));
            SwingUtilities.invokeAndWait(() -> { for (ActivityPanel panel : panels) field(panel, "freeze", JCheckBox.class).setSelected(true); });
            log.clear(); // Reformatting frozen UI must not read this newer, empty capture.
            String frozenCopies = json.toJson(log.activitySnapshotStats());
            Locale[] locales = {Locale.US, Locale.GERMANY, Locale.GERMANY};
            String[] zones = {"UTC", "Europe/Berlin", "UTC"};
            for (int i = 0; i < locales.length; i++) {
                Locale locale = locales[i]; String zoneName = zones[i];
                Locale.setDefault(Locale.Category.FORMAT, locale); TimeZone.setDefault(TimeZone.getTimeZone(zoneName));
                SwingUtilities.invokeAndWait(() -> {
                    for (ActivityPanel panel : panels) panel.refresh();
                    assertRetainedPresentation(panels, retained, states, locale.equals(Locale.GERMANY), zoneName,
                        zoneName.equals("UTC") ? "03:04:05" : "04:04:05");
                });
                assertEquals(frozenCopies, json.toJson(log.activitySnapshotStats())); assertEquals(raw, json.toJson(states));
            }
        } finally { log.close(); Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }

    private static ActivityJournal.State presentationHistory() {
        ActivityJournal.State history = new ActivityJournal.State();
        String[] times = {"2026-01-02T03:04:05Z", "2026-10-25T00:50:00Z", "2026-10-25T01:10:00Z"};
        String[] maps = {"Lost Halls", "Sprite World", "Ice Citadel"};
        for (int i = 0; i < times.length; i++) {
            ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = i == 0 ? "visit-12345" : "visit-" + i;
            visit.map = maps[i]; visit.started = Instant.parse(times[i]).toEpochMilli(); visit.lastSeen = visit.started + 20000;
            if (i == 0) {
                visit.useRequests = 1234; visit.partyId = 12345; visit.rosterSize = 1234;
                visit.conditionObservedMillis = 1000000; visit.conditions.put("Damaging", 105000L);
                ActivityJournal.ResourcePoint point = new ActivityJournal.ResourcePoint(); point.time = visit.started + 10500;
                point.hp = 1234567; point.mp = 0; visit.resourceTimeline.add(point);
                for (int entry = 0; entry < 1000; entry++) {
                    ActivityJournal.Entry event = new ActivityJournal.Entry(); event.id = "entry-" + entry; event.visitId = visit.id;
                    event.map = visit.map; event.time = visit.started; event.kind = "Party roster"; event.detail = "[captured]";
                    event.values = new LinkedHashMap<>(); event.values.put("partyId", 12345); event.values.put("memberCount", 1234);
                    history.entries.add(event);
                }
            }
            history.visits.add(visit);
        }
        return history;
    }
    private static JTable activityTable(ActivityPanel panel) { return named(panel, "activity-table", JTable.class); }
    private static JTextField activitySearch(ActivityPanel panel) { return named(panel, "activity-search", JTextField.class); }
    private static boolean idle(ActivityPanel... panels) {
        for (ActivityPanel panel : panels) if (field(field(panel, "snapshots", Object.class), "running", Boolean.class)) return false;
        return true;
    }
    private static void assertRetainedPresentation(ActivityPanel[] panels, DiscoveryLog.ActivitySnapshot[] retained,
            ActivityJournal.State[] states, boolean german, String zone, String time) {
        for (int i = 0; i < panels.length; i++) {
            assertSame(retained[i], field(panels[i], "displayed", DiscoveryLog.ActivitySnapshot.class));
            assertSame(states[i], field(panels[i], "state", ActivityJournal.State.class));
            assertTrue(activityTable(panels[i]).getSelectedRow() >= 0);
            assertTrue(named(panels[i], "activity-detail", JTextArea.class).getText().contains("2026-01-02 " + time + " (" + zone + ")"));
            if (i > 0) assertTrue(named(panels[i], "activity-visit", JComboBox.class).getSelectedItem().toString().startsWith("2026-01-02 " + time + " · Lost Halls"));
        }
        assertEquals("2026-01-02 " + time, cell(activityTable(panels[0]), activityTable(panels[0]).getSelectedRow(), 1));
        assertTrue(named(panels[0], "activity-detail", JTextArea.class).getText().contains(german ? "party 12345, 1.234 observed members" : "party 12345, 1,234 observed members"));
        assertTrue(named(panels[1], "activity-summary", JLabel.class).getText().contains(german ? "1.000 retained events" : "1,000 retained events"));
        assertEquals("Party 12345 · " + (german ? "1.234" : "1,234") + " observed members", activityTable(panels[1]).getValueAt(activityTable(panels[1]).getSelectedRow(), 3));
        CombatTimelineChart chart = named(panels[2], "combat-timeline-chart", CombatTimelineChart.class);
        assertEquals(Integer.valueOf(0), field(chart, "inspected", Integer.class));
        assertTrue(chart.getInspectionSummary().contains(german ? "10,5s · HP 1.234.567 · MP 0" : "10.5s · HP 1,234,567 · MP 0"));
        assertEquals(chart.getInspectionSummary(), chart.getAccessibleContext().getAccessibleDescription());
        assertEquals(chart.getInspectionSummary(), named(panels[2], "combat-sample-summary", JTextArea.class).getText());
    }
}
