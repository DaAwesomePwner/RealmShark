package tomato.gui.stats;

import java.awt.GridLayout;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.backend.data.FameTracker;
import tomato.gui.stats.data.MapFameData;
import static org.junit.Assert.*;

public class FameTrackingStateTest {
    private FameTablePanel table;
    private FameTrackerGUI graph;
    private JFrame frame;

    @After public void detachViews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) frame.dispose();
            FameTableBridge.getInstance().setFameTrackerGUI(null);
            FameTableBridge.getInstance().setFameTablePanel(null);
        });
    }

    @Test public void hiddenLongBurstRetainsHistoryButNoTableSampleSeriesOrPresentationCopies() throws Exception {
        createViews(false);
        table.onMapChange("Realm", 0);
        int count = 20000;
        for (int i = 0; i < count; i++) {
            table.updateFame(1, 100, i * 10L, "Wizard");
            FameTrackerGUI.updateFame(1, 100, i * 10L);
        }
        FameTrackingModel.TableCounts tableCounts = table.trackingCounts();
        FameTrackingModel.HistoryCounts graphCounts = graph.trackingCounts();
        assertEquals(1, tableCounts.characters); assertEquals(1, tableCounts.lastSamples);
        assertEquals(0, tableCounts.closedVisits); assertEquals(1, tableCounts.openVisits);
        assertEquals(0, tableCounts.snapshots); assertEquals(0, tableCounts.copiedVisits);
        assertEquals(count, graphCounts.samples); assertEquals(1, graphCounts.characters);
        assertEquals(0, graphCounts.snapshots); assertEquals(0, graphCounts.copiedSamples);
        assertEquals(0, table.refreshCounts().scheduled); assertEquals(0, graph.refreshCounts().scheduled);

        FameTrackingModel.TableSnapshot state = table.trackingSnapshot();
        assertEquals((count - 1) * 10L, state.observed.get(1).longValue());
        assertEquals((count - 1) * 10L, state.open.get(1).endTime);
        assertEquals(0, state.gain(1), 0);
        FameTrackingModel.GraphSnapshot tail = graph.trackingSnapshot(1, 20);
        assertEquals(3, tail.samples.size());
        assertEquals(3, graph.trackingCounts().copiedSamples);
        tail.samples.clear(); state.open.get(1).endFame = -999;
        assertEquals(count, graph.trackingCounts().samples);
        assertEquals(100, table.getCurrentMapData().get(1).endFame, 0);

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(0, characters().getRowCount());
            table.refreshNow(); graph.refreshNow();
            assertEquals(1, characters().getRowCount());
            assertEquals((count - 1) * 10L, characters().getValueAt(0, 5));
            assertEquals(count, graphPanel().getScores().size());
        });
    }

    @Test public void orderedCharactersAndMapBoundariesKeepZeroNegativeAndReturnVisits() throws Exception {
        createViews(false);
        table.onMapChange("A", 0);
        table.updateFame(1, 100, 1000, "Wizard");
        table.updateFame(1, 100, 2000, "Wizard");
        table.onMapChange("B", 3000);
        table.updateFame(1, 95, 4000, "Wizard");
        table.updateFame(2, 500, 5000, "Priest");
        table.updateFame(2, 500, 6000, "Priest");
        table.onMapChange("C", 7000);
        table.updateFame(1, 95, 8000, "Wizard");
        table.updateFame(1, 100, 9000, "Wizard");
        table.updateFame(1, 999, 8500, "Wizard"); // A stale sample cannot reopen/extend a visit.
        table.onMapChange("D", 10000);
        table.onMapChange("E", 11000); // No sample: no invented visit or character assignment.

        FameTrackingModel.TableSnapshot state = table.trackingSnapshot();
        assertEquals(4000L, state.observed.get(1).longValue());
        assertEquals(1000L, state.observed.get(2).longValue());
        assertEquals(3, state.maps.get(1).size()); assertEquals(1, state.maps.get(2).size());
        assertVisit(state.maps.get(1).get(0), "A", 1000, 3000, 0);
        assertVisit(state.maps.get(1).get(1), "B", 3000, 4000, -5);
        assertVisit(state.maps.get(1).get(2), "C", 8000, 10000, 5);
        assertVisit(state.maps.get(2).get(0), "B", 5000, 7000, 0);
        assertTrue(state.open.isEmpty()); assertEquals(0, table.refreshCounts().rendered);
        SwingUtilities.invokeAndWait(() -> {
            table.refreshNow();
            JTable maps = StatisticsExplorerTest.named(table, "fame-maps", JTable.class);
            assertEquals(3, maps.getRowCount());
        });
    }

    @Test public void blockedEdtHasOneRefreshPerViewAndHiddenViewsCatchUpOnShow() throws Exception {
        createViews(true);
        table.onMapChange("Realm", 0);
        flushEdt();
        FameRefresh.Counts beforeTable = table.refreshCounts(), beforeGraph = graph.refreshCounts();
        long tableSnapshots = table.trackingCounts().snapshots, graphSnapshots = graph.trackingCounts().snapshots;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> { entered.countDown(); await(release); });
        try {
            assertTrue("EDT blocker entered", entered.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 10000; i++) {
                FameTrackerGUI.updateFame(1, 100 + i, 1000 + i);
                table.updateFame(1, 100 + i, 1000 + i, "Wizard");
            }
            assertEquals(10000, graph.trackingCounts().samples);
            assertEquals(10099, table.getCurrentFame(1), 0);
            assertEquals(tableSnapshots, table.trackingCounts().snapshots);
            assertEquals(graphSnapshots, graph.trackingCounts().snapshots);
            assertEquals(1, table.refreshCounts().pending); assertEquals(1, graph.refreshCounts().pending);
            assertEquals(beforeTable.scheduled + 1, table.refreshCounts().scheduled);
            assertEquals(beforeGraph.scheduled + 1, graph.refreshCounts().scheduled);
        } finally { release.countDown(); }
        flushEdt();
        assertEquals(beforeTable.rendered + 1, table.refreshCounts().rendered);
        assertEquals(beforeGraph.rendered + 1, graph.refreshCounts().rendered);
        assertEquals(0, table.refreshCounts().pending); assertEquals(0, graph.refreshCounts().pending);
        assertEquals("Characters view never copies map history", 0, table.trackingCounts().copiedVisits);

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(10099.0, (Double)characters().getValueAt(0, 3), 0);
            assertEquals(10000, graphPanel().getScores().size());
            table.setVisible(false); graph.setVisible(false);
        });
        flushEdt();
        long tableCopies = table.trackingCounts().copiedVisits, graphCopies = graph.trackingCounts().copiedSamples;
        long tableRenders = table.refreshCounts().rendered, graphRenders = graph.refreshCounts().rendered;
        for (int i = 0; i < 1000; i++) {
            FameTrackerGUI.updateFame(2, 500, 20000 + i);
            table.updateFame(2, 500, 20000 + i, "Priest");
        }
        flushEdt();
        assertEquals(tableCopies, table.trackingCounts().copiedVisits);
        assertEquals(graphCopies, graph.trackingCounts().copiedSamples);
        assertEquals(tableRenders, table.refreshCounts().rendered);
        assertEquals(graphRenders, graph.refreshCounts().rendered);
        assertEquals(11000, graph.trackingCounts().samples);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, characters().getRowCount()); assertEquals(10000, graphPanel().getScores().size());
            table.setVisible(true); graph.setVisible(true);
        });
        flushEdt();
        assertEquals(tableRenders + 1, table.refreshCounts().rendered);
        assertEquals(graphRenders + 1, graph.refreshCounts().rendered);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2, characters().getRowCount()); assertEquals(1000, graphPanel().getScores().size());
            ((JTabbedPane)SwingUtilities.getAncestorOfClass(JTabbedPane.class,
                StatisticsExplorerTest.named(table, "fame-maps", JTable.class))).setSelectedIndex(1);
        });
        flushEdt();
        assertEquals(2, table.trackingCounts().copiedVisits);
    }

    @Test public void resetWhileRefreshQueuedCannotRestoreOldRowsOrSuppressSameFameBaseline() throws Exception {
        createViews(true);
        FameTracker.trackFame(1, 159929, 1000);
        table.updateFame(1, 100, 1000, "Wizard");
        flushEdt();
        SwingUtilities.invokeAndWait(() -> {
            StatisticsExplorerTest.named(graph, "fame-graph-character", JComboBox.class).setSelectedItem("Character #1");
            graph.refreshNow();
            // Both updates queue refreshes, then reset occurs before either can render.
            FameTracker.trackFame(1, 179929, 2000);
            table.updateFame(1, 110, 2000, "Wizard");
            table.resetSessions(false);
            FameTracker.trackFame(1, 179929, 9000);
            table.updateFame(1, 110, 9000, "Wizard");
            assertEquals(1, graph.trackingCounts().samples);
            assertEquals(1, graph.refreshCounts().pending); assertEquals(1, table.refreshCounts().pending);
            assertEquals(0L, table.trackingSnapshot().observed.getOrDefault(1, 0L).longValue());
        });
        flushEdt();
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Follow current character", StatisticsExplorerTest.named(graph, "fame-graph-character", JComboBox.class).getSelectedItem());
            assertEquals(Collections.singletonList(new Fame(110, 9000)), graphPanel().getScores());
            assertEquals(0.0, (Double)characters().getValueAt(0, 4), 0);
            assertEquals(0L, characters().getValueAt(0, 5));
        });
    }

    @Test public void mutableMapInputsAndPublicSnapshotsNeverAliasTrackingState() throws Exception {
        createViews(false);
        MapFameData row = new MapFameData("Saved zero", 100, 50); row.endTime = 200;
        HashMap<Integer, ArrayList<MapFameData>> closed = new HashMap<>();
        closed.put(1, new ArrayList<>(Collections.singletonList(row)));
        table.setMapFameData(closed); row.mapName = "Mutated input"; closed.clear();
        MapFameData current = new MapFameData("Open", 300, 50);
        HashMap<Integer, MapFameData> open = new HashMap<>(); open.put(1, current);
        table.setCurrentMapData(open); current.endFame = 999; open.clear();
        HashMap<Integer, ArrayList<MapFameData>> snapshot = table.getMapFameData();
        assertEquals(2, snapshot.get(1).size()); assertEquals("Saved zero", snapshot.get(1).get(0).mapName);
        assertEquals(50, snapshot.get(1).get(1).endFame, 0);
        snapshot.get(1).get(0).mapName = "Mutated output"; snapshot.get(1).get(1).endFame = -1;
        assertEquals("Saved zero", table.getMapFameData().get(1).get(0).mapName);
        assertEquals(50, table.getCurrentMapData().get(1).endFame, 0);
        FameTrackerGUI.updateFame(1, 50, 300);
        graph.getFameData().get(1).clear(); assertEquals(1, graph.trackingCounts().samples);
    }

    private void createViews(boolean visible) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FameTableBridge bridge = FameTableBridge.getInstance();
            bridge.setFameTrackerGUI(null); bridge.setFameTablePanel(null);
            table = new FameTablePanel(null);
            graph = new FameTrackerGUI((session, completion) -> completion.accept(true));
            bridge.setFameTablePanel(table); bridge.setFameTrackerGUI(graph);
            if (visible) {
                JPanel content = new JPanel(new GridLayout(1, 2)); content.add(table); content.add(graph);
                frame = new JFrame("Fame tracking separation"); frame.setContentPane(content);
                frame.setSize(1200, 700); frame.setVisible(true); frame.validate();
                assertTrue(table.isShowing()); assertTrue(graph.isShowing());
            }
        });
        flushEdt();
    }

    private JTable characters() { return StatisticsExplorerTest.named(table, "fame-characters", JTable.class); }
    private GraphPanel graphPanel() {
        for (java.awt.Component component : graph.getComponents()) if (component instanceof GraphPanel) return (GraphPanel)component;
        throw new AssertionError("Missing fame graph");
    }
    private static void flushEdt() throws Exception { SwingUtilities.invokeAndWait(() -> {}); }
    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void assertVisit(MapFameData row, String name, long start, long end, double gained) {
        assertEquals(name, row.mapName); assertEquals(start, row.startTime); assertEquals(end, row.endTime);
        assertEquals(gained, row.getFameGained(), 0);
    }
}
