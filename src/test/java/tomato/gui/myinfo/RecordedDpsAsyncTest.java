package tomato.gui.myinfo;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;
import tomato.gui.dps.*;
import static tomato.gui.activity.ActivityArchiveUiTest.edt;

public class RecordedDpsAsyncTest {
    private static RecordedEncounter row(String id) { return new RecordedEncounter(id, id, 1000L, 1000L, EncounterLink.live()); }
    @Test public void projectionNeverRunsOnEdtAndOlderRefreshCannotOverwriteNewerResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger(); AtomicBoolean wrongThread = new AtomicBoolean();
        RecordedDpsPanel panel = edt(() -> new RecordedDpsPanel(() -> {
            wrongThread.set(wrongThread.get() || SwingUtilities.isEventDispatchThread());
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); boolean interrupted = false;
                while (true) try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt(); finished.countDown(); return Collections.singletonList(row("old"));
            }
            return Collections.singletonList(row("new"));
        }, () -> "fixture"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            edt(() -> { assertTrue(panel.loading()); assertFalse(panel.openButton().isEnabled()); assertTrue(panel.explanationText().contains("background")); panel.reload(); return null; });
            RecordedDpsHandoffTest.awaitLoaded(panel); release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS));
            edt(() -> { assertEquals("new", ((RecordedEncounter)panel.choice().getSelectedItem()).recordingId); return null; });
            assertFalse(wrongThread.get());
        } finally { release.countDown(); edt(() -> { panel.removeNotify(); return null; }); }
    }
    @Test public void failedRefreshRetainsSelectionAndRetryPreservesExactRecording() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(); AtomicReference<List<RecordedEncounter>> source = new AtomicReference<>(Arrays.asList(row("A"), row("B")));
        RecordedDpsPanel panel = edt(() -> new RecordedDpsPanel(() -> { if (fail.get()) throw new IllegalStateException("fixture"); return source.get(); }, () -> "fixture"));
        try {
            RecordedDpsHandoffTest.awaitLoaded(panel);
            edt(() -> { panel.choice().setSelectedIndex(1); assertEquals("A", ((RecordedEncounter)panel.choice().getSelectedItem()).recordingId); fail.set(true); panel.reload(); return null; });
            RecordedDpsHandoffTest.awaitLoaded(panel);
            edt(() -> { assertEquals(2, panel.choice().getItemCount()); assertEquals("A", ((RecordedEncounter)panel.choice().getSelectedItem()).recordingId); assertFalse(panel.openButton().isEnabled()); assertTrue(panel.explanationText().contains("Could not refresh")); return null; });
            fail.set(false); source.set(Arrays.asList(row("A"), row("B"), row("C"))); edt(() -> { panel.reload(); return null; }); RecordedDpsHandoffTest.awaitLoaded(panel);
            edt(() -> { assertEquals(3, panel.choice().getItemCount()); assertEquals("A", ((RecordedEncounter)panel.choice().getSelectedItem()).recordingId); return null; });
        } finally { edt(() -> { panel.removeNotify(); return null; }); }
    }
    @Test public void disposalInvalidatesQueuedPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        RecordedDpsPanel panel = edt(() -> new RecordedDpsPanel(() -> {
            entered.countDown(); while (true) try { release.await(); break; } catch (InterruptedException ignored) { }
            finished.countDown(); return Collections.singletonList(row("late"));
        }, () -> "fixture"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); edt(() -> { panel.removeNotify(); return null; }); release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS));
            edt(() -> { assertEquals(0, panel.choice().getItemCount()); assertFalse(panel.loading()); return null; });
        } finally { release.countDown(); }
    }
}
