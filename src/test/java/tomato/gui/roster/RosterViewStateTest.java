package tomato.gui.roster;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.awt.event.ActionEvent;
import javax.swing.*;
import org.junit.Test;
import com.google.gson.*;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class RosterViewStateTest {
    @Test public void ownershipGuardsCaptureSaveResetAndQueuedIntentAcrossRoundTrip() throws Exception {
        Memory memory = new Memory(); AtomicBoolean live = new AtomicBoolean(true);
        AtomicInteger captures = new AtomicInteger(), applies = new AtomicInteger();
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("text", "Initial live L");
        RosterViewState[] state = new RosterViewState[1]; CompletableFuture<Void> betweenCallbacks = new CompletableFuture<>();
        SwingUtilities.invokeAndWait(() -> {
            state[0] = new RosterViewState(memory.store, "guarded-live", () -> { assertTrue(live.get()); captures.incrementAndGet(); return fields; },
                values -> () -> applies.incrementAndGet(), live::get);
            state[0].save(); String original = memory.values.get("ux.archive.guarded-live"); int savedCaptures = captures.get(), savedApplies = applies.get();
            fields.put("text", "Old queued live intent"); state[0].changed();
            // This observer is queued after the old callback but before the fresh live callback.
            SwingUtilities.invokeLater(() -> {
                try { assertEquals(original, memory.values.get("ux.archive.guarded-live")); assertEquals(savedCaptures, captures.get()); betweenCallbacks.complete(null); }
                catch (Throwable failure) { betweenCallbacks.completeExceptionally(failure); }
            });
            live.set(false); state[0].ownershipChanged(); fields.put("text", "Historical H");
            assertFalse(state[0].save().toCompletableFuture().join().isSuccess()); state[0].changed(); state[0].restoreLast();
            JButton reset = named(state[0].controls(), "guarded-live-reset-state", JButton.class);
            for (java.awt.event.ActionListener listener : reset.getActionListeners()) listener.actionPerformed(new ActionEvent(reset, ActionEvent.ACTION_PERFORMED, "stale-reset"));
            assertEquals(savedCaptures, captures.get()); assertEquals(savedApplies, applies.get()); assertEquals(original, memory.values.get("ux.archive.guarded-live"));
            live.set(true); state[0].ownershipChanged(); fields.put("text", "Fresh live intent"); state[0].changed();
        });
        betweenCallbacks.get(3, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2, captures.get()); assertEquals(2, memory.writes);
            assertTrue(memory.values.get("ux.archive.guarded-live").contains("Fresh live intent"));
            assertFalse(memory.values.get("ux.archive.guarded-live").contains("Historical H"));
        });
    }
    @Test public void failureKeepsCurrentControlsAndRetryPersistsThem() throws Exception {
        Memory memory = new Memory(); Map<String, String> controls = new LinkedHashMap<>(); controls.put("mode", "UNKNOWN");
        RosterViewState[] state = new RosterViewState[1];
        SwingUtilities.invokeAndWait(() -> {
            state[0] = new RosterViewState(memory.store, "fixture-live", () -> controls, values -> () -> controls.putAll(values));
            memory.fail = true; controls.put("text", "retained draft");
            assertFalse(state[0].save().toCompletableFuture().join().isSuccess()); assertEquals("retained draft", controls.get("text")); assertTrue(memory.values.isEmpty());
        });
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(named(state[0].controls(), "fixture-live-state-status", JTextArea.class).getText().contains("save failed"));
            memory.fail = false; assertTrue(state[0].save().toCompletableFuture().join().isSuccess());
            Map<String, String> restored = new LinkedHashMap<>();
            new RosterViewState(memory.store, "fixture-live", () -> restored, values -> () -> restored.putAll(values));
            assertEquals("UNKNOWN", restored.get("mode")); assertEquals("retained draft", restored.get("text"));
        });
    }
    /** A column KitTables.analystOnly hides in Simple keeps its width in captures and restores; mode switches record nothing. */
    @Test public void modeHiddenWidthsSurviveASimpleCaptureAndRestore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.util.Map<String, String> modeStore = new HashMap<>();
            tomato.gui.kit.DisplayModeModel mode = new tomato.gui.kit.DisplayModeModel(modeStore::get, modeStore::put);
            mode.set(tomato.gui.kit.DisplayModeModel.Mode.ANALYST);
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(new Object[]{"time", "map", "meaning"}, 0);
            model.addRow(new Object[]{"t1", "Nexus", "m1"});
            JTable table = new JTable(model); table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            AtomicInteger changes = new AtomicInteger();
            RosterViewState.listenTable(table, changes::incrementAndGet);
            tomato.gui.kit.KitTables.analystOnly(table, mode, "meaning");
            table.getColumnModel().getColumn(2).setWidth(211);   // the user's width, in Analyst
            int before = changes.get();
            mode.set(tomato.gui.kit.DisplayModeModel.Mode.SIMPLE);
            assertEquals("Hiding the column is not a user change", before, changes.get());
            Map<String, String> values = new LinkedHashMap<>();
            RosterViewState.captureTable(values, table);
            assertEquals("A Simple capture keeps the hidden column's width", "211", values.get("width.2"));
            assertEquals(2, table.getColumnCount());
            Map<String, String> saved = new LinkedHashMap<>(); saved.put("width.0", "90"); saved.put("width.2", "260");
            RosterViewState.prepareTable(saved, table).run();
            assertEquals(90, table.getColumnModel().getColumn(0).getWidth());
            Map<String, String> again = new LinkedHashMap<>();
            RosterViewState.captureTable(again, table);
            assertEquals("A restore in Simple is remembered for the hidden column", "260", again.get("width.2"));
            int restored = changes.get();   // the panel's own restore guard (not this helper) silences the restore's resizes
            mode.set(tomato.gui.kit.DisplayModeModel.Mode.ANALYST);
            assertEquals(3, table.getColumnCount());
            assertEquals("Analyst shows it at the restored width", 260, table.getColumnModel().getColumn(2).getWidth());
            assertEquals("Showing it is not a user change either", restored, changes.get());
        });
    }
    @Test public void unsupportedVersionIsNotAppliedOrOverwritten() throws Exception {
        Memory memory = new Memory();
        SwingUtilities.invokeAndWait(() -> new RosterViewState(memory.store, "fixture-live", () -> Collections.singletonMap("mode", "UNKNOWN"), values -> () -> {}).save());
        JsonObject document = JsonParser.parseString(memory.values.get("ux.archive.fixture-live")).getAsJsonObject();
        document.getAsJsonObject("last").getAsJsonObject("query").getAsJsonObject("facets").addProperty("version", 99);
        String original = document.toString(); memory.values.put("ux.archive.fixture-live", original); AtomicInteger applied = new AtomicInteger(); int writes = memory.writes;
        SwingUtilities.invokeAndWait(() -> {
            RosterViewState state = new RosterViewState(memory.store, "fixture-live", () -> Collections.singletonMap("mode", "ANY"), values -> () -> applied.incrementAndGet());
            state.changed(); assertFalse(state.save().toCompletableFuture().join().isSuccess());
        });
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, applied.get()); assertEquals(writes, memory.writes); assertEquals(original, memory.values.get("ux.archive.fixture-live"));
    }
}
