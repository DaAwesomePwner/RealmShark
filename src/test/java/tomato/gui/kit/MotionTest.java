package tomato.gui.kit;

import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.*;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class MotionTest {
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(Motion.REDUCE_KEY); }
    @After public void restore() {
        Motion.systemOverride = null;
        PropertiesManager.setProperties(Motion.REDUCE_KEY, saved == null ? "" : saved);
    }

    @Test public void reduceMotionCompletesImmediately() throws Exception {
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "true");
        Motion.systemOverride = true;
        List<Double> frames = new ArrayList<>(); boolean[] done = {false};
        SwingUtilities.invokeAndWait(() -> assertNull(Motion.run(100, frames::add, () -> done[0] = true)));
        assertEquals(Collections.singletonList(1.0), frames);
        assertTrue(done[0]);
    }

    @Test public void windowsAnimationSettingAlsoDisablesMotion() {
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "");
        Motion.systemOverride = false;
        assertFalse(Motion.enabled());
        Motion.systemOverride = true;
        assertTrue(Motion.enabled());
    }

    @Test public void enabledMotionFinishesWithinTheCap() throws Exception {
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "");
        Motion.systemOverride = true;
        CountDownLatch finished = new CountDownLatch(1);
        List<Double> frames = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> assertNotNull(Motion.run(5_000, frames::add, finished::countDown)));
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue("Capped at 100 ms plus timer slack: " + elapsedMillis, elapsedMillis < 600);
        assertEquals(1.0, frames.get(frames.size() - 1), 1e-9);
    }
}
