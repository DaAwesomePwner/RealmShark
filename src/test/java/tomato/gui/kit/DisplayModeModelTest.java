package tomato.gui.kit;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayModeModelTest {
    @Test public void defaultsToSimpleAndPersistsChanges() {
        Map<String, String> store = new HashMap<>();
        DisplayModeModel model = new DisplayModeModel(store::get, store::put);
        assertEquals(DisplayModeModel.Mode.SIMPLE, model.mode());
        model.set(DisplayModeModel.Mode.ANALYST);
        assertEquals("analyst", store.get(DisplayModeModel.KEY));
        assertTrue(new DisplayModeModel(store::get, store::put).analyst());
        model.toggle();
        assertEquals("simple", store.get(DisplayModeModel.KEY));
    }

    @Test public void boundListenersFollowTheOwnersLifecycle() throws Exception {
        Map<String, String> store = new HashMap<>();
        DisplayModeModel model = new DisplayModeModel(store::get, store::put);
        List<DisplayModeModel.Mode> seen = new ArrayList<>();
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JPanel owner = new JPanel();
            model.bind(owner, seen::add);
            assertEquals(Collections.singletonList(DisplayModeModel.Mode.SIMPLE), seen);
            frame[0] = new JFrame(); frame[0].setContentPane(owner); frame[0].pack();
            model.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(DisplayModeModel.Mode.ANALYST, seen.get(seen.size() - 1));
            frame[0].dispose();
            int before = seen.size();
            assertEquals(0, model.listenerCount());
            model.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(before, seen.size());
        });
    }
}
