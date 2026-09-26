package tomato.gui.stats;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSession;
import tomato.gui.stats.session.FameSessionViewer;
import static org.junit.Assert.*;

/** STAT-2: timestamp pins, keyboard endpoints, text delta and honest map association. Synthetic samples only. */
public class FameGraphPinTest {
    private static ArrayList<Fame> samples(long... fameAndTime) {
        ArrayList<Fame> result = new ArrayList<>();
        for (int i = 0; i < fameAndTime.length; i += 2) result.add(new Fame(fameAndTime[i], fameAndTime[i + 1]));
        return result;
    }
    private static void mouse(GraphPanel graph, int id, int x) {
        MouseEvent event = new MouseEvent(graph, id, System.currentTimeMillis(), 0, x, 150, 1, false, MouseEvent.BUTTON1);
        if (id == MouseEvent.MOUSE_PRESSED) graph.mousePressed(event);
        else if (id == MouseEvent.MOUSE_DRAGGED) graph.mouseDragged(event);
        else if (id == MouseEvent.MOUSE_RELEASED) graph.mouseReleased(event);
        else graph.mouseMoved(event);
    }
    private static void key(GraphPanel graph, String action) { graph.getActionMap().get(action).actionPerformed(null); }

    @Test public void dragPinsTimestampsThatSurvivePointerMovementAndRefreshedSamples() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GraphPanel graph = new GraphPanel(samples(100, 60000, 110, 120000, 130, 180000, 160, 240000), false);
            graph.setSize(800, 400);
            // Plot spans x=78..776 for 180 s: 60 s at 78, 120 s at ~310, 180 s at ~543.
            mouse(graph, MouseEvent.MOUSE_PRESSED, 78); mouse(graph, MouseEvent.MOUSE_DRAGGED, 310); mouse(graph, MouseEvent.MOUSE_DRAGGED, 543);
            mouse(graph, MouseEvent.MOUSE_RELEASED, 543);
            assertEquals(Long.valueOf(60000), graph.pinnedStart()); assertEquals(Long.valueOf(180000), graph.pinnedEnd());
            String pinned = graph.inspectionSummary();
            assertTrue(pinned, pinned.startsWith("Pinned ")); assertTrue(pinned, pinned.contains("+30"));
            for (int x = 0; x < 800; x += 37) mouse(graph, MouseEvent.MOUSE_MOVED, x);
            assertEquals(Long.valueOf(60000), graph.pinnedStart()); assertEquals(pinned, graph.inspectionSummary());
            ArrayList<Fame> refreshed = samples(100, 60000, 110, 120000, 130, 180000, 160, 240000, 200, 300000, 90, 30000);
            graph.setScores(refreshed);
            assertEquals(Long.valueOf(60000), graph.pinnedStart()); assertEquals(Long.valueOf(180000), graph.pinnedEnd());
            assertEquals(pinned, graph.inspectionSummary());
            assertEquals(pinned, graph.getAccessibleContext().getAccessibleDescription());
            // Samples leaving the shown window keep the pin but say so rather than shifting it.
            graph.setScores(samples(160, 240000, 200, 300000));
            assertTrue(graph.inspectionSummary().contains("is not in the shown samples"));
            graph.setScores(refreshed); assertEquals(pinned, graph.inspectionSummary());
            // A click without a drag clears it.
            mouse(graph, MouseEvent.MOUSE_PRESSED, 310); mouse(graph, MouseEvent.MOUSE_RELEASED, 310);
            assertNull(graph.pinnedStart()); assertTrue(graph.inspectionSummary().startsWith("Shown range"));
        });
    }

    @Test public void keyboardEndpointsProduceATextDeltaAndEscapeClears() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GraphPanel graph = new GraphPanel(samples(100, 60000, 110, 120000, 130, 180000), false);
            assertTrue(graph.isFocusable());
            key(graph, "fame-graph-first"); key(graph, "fame-graph-pin-start");
            assertTrue(graph.inspectionSummary().startsWith("Start pinned"));
            key(graph, "fame-graph-next"); key(graph, "fame-graph-next"); key(graph, "fame-graph-next");
            assertEquals(Long.valueOf(180000), graph.cursorTime());
            key(graph, "fame-graph-pin-end");
            assertEquals(Long.valueOf(60000), graph.pinnedStart()); assertEquals(Long.valueOf(180000), graph.pinnedEnd());
            String text = graph.inspectionSummary();
            assertTrue(text, text.contains("+30")); assertTrue(text, text.contains("0:02:00") || text.contains("00:02:00")); assertTrue(text, text.contains("fame/h"));
            key(graph, "fame-graph-previous"); key(graph, "fame-graph-pin-start");
            assertEquals(Long.valueOf(120000), graph.pinnedStart()); assertTrue(graph.inspectionSummary().contains("+20"));
            key(graph, "fame-graph-clear-pin"); assertNull(graph.pinnedStart()); assertNull(graph.pinnedEnd());
            key(graph, "fame-graph-pin-toggle"); key(graph, "fame-graph-next"); key(graph, "fame-graph-pin-toggle");
            assertEquals(Long.valueOf(120000), graph.pinnedStart()); assertEquals(Long.valueOf(180000), graph.pinnedEnd());
        });
    }

    @Test public void savedViewerHasRangeGainControlsKeepsPinsAndSaysMapNotRecorded() throws Exception {
        FameSession session = new FameSession("Saved parity");
        session.addCharacterData(1, "Wizard", samples(100, 60000, 110, 120000, 130, 180000));
        session.addCharacterData(2, "Knight", samples(500, 60000, 520, 120000));
        session.addCharacterMapData(2, Collections.singletonList(new MapFameData("Nexus", 60000, 500)));
        session.setReadOnly(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FameSessionViewer viewer = new FameSessionViewer(session);
            try {
                JComboBox<?> character = named(viewer, "saved-fame-character", JComboBox.class);
                GraphPanel graph = named(viewer, "saved-fame-graph", GraphPanel.class);
                JTextArea map = named(viewer, "saved-fame-map-association", JTextArea.class); JLabel delta = named(viewer, "saved-fame-delta", JLabel.class);
                assertEquals(0, character.getSelectedIndex());
                assertTrue(map.getText(), map.getText().startsWith(FameSessionViewer.MAP_NOT_RECORDED));
                assertTrue(named(viewer, "saved-fame-map-status", JLabel.class).getText().contains("Not recorded"));
                assertTrue(named(viewer, "saved-fame-session-info", JTextArea.class).getText().contains("Map association: Not recorded"));
                graph.pinInterval(60000L, 180000L);
                assertTrue(delta.getText(), delta.getText().startsWith("Pinned") && delta.getText().contains("+30"));
                named(viewer, "saved-fame-measure", JComboBox.class).setSelectedItem("Gain in range");
                assertEquals(0, graph.getScores().get(0).getFame(), 0);
                assertEquals(Long.valueOf(60000), graph.pinnedStart()); assertTrue(delta.getText().contains("+30"));
                named(viewer, "saved-fame-range", JComboBox.class).setSelectedItem("1 min");
                assertEquals(2, graph.getScores().size()); assertEquals(Long.valueOf(60000), graph.pinnedStart());
                assertTrue(delta.getText().contains("not in the shown samples"));
                named(viewer, "saved-fame-range", JComboBox.class).setSelectedItem("All samples");
                assertTrue(delta.getText().startsWith("Pinned"));
                character.setSelectedIndex(1);
                assertNull(graph.pinnedStart());
                assertTrue(map.getText(), map.getText().contains("1 saved map visits"));
                assertFalse(named(viewer, "saved-fame-map-status", JLabel.class).getText().contains("Not recorded"));
            } catch (Throwable t) { failure.set(t); }
            finally { viewer.dispose(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test public void liveTrackerShowsTheSameTextDelta() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FameTrackerGUI fame = new FameTrackerGUI((session, done) -> done.accept(true));
            fame.trackCapturedFame(7, 100, 60000); fame.trackCapturedFame(7, 130, 180000); fame.refreshNow();
            GraphPanel graph = named(fame, null, GraphPanel.class);
            graph.pinInterval(60000L, 180000L);
            assertTrue(named(fame, "fame-graph-delta", JLabel.class).getText().contains("+30"));
            fame.trackCapturedFame(7, 150, 240000); fame.refreshNow();
            assertEquals(Long.valueOf(60000), graph.pinnedStart());
            assertTrue(named(fame, "fame-graph-delta", JLabel.class).getText().startsWith("Pinned"));
        });
    }

    static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if ((name == null || name.equals(child.getName())) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = named((Container)child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
