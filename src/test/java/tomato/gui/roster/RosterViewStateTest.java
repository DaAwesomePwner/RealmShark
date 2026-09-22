package tomato.gui.roster;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Test;
import com.google.gson.*;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class RosterViewStateTest {
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
