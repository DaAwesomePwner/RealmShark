package tomato.gui.dps;

import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.Test;
import tomato.backend.data.*;
import static org.junit.Assert.*;

public class CapturedPublicationRaceTest {
    @Test public void realProducerSnapshotTakenBeforeEdtClearCannotResurrectOldEncounter() throws Exception {
        BlockingHistory data = new BlockingHistory(); DpsData old = EncounterCatalogTest.encounter("A"); data.dpsData.add(old);
        DpsGUI[] view = new DpsGUI[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new DpsGUI(data); view[0].setIndex(0);
            view[0].encounters().check(view[0].currentEncounterId(), true);
        });
        data.block = true;
        ExecutorService capture = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = capture.submit(() -> DpsGUI.updateMapPacket(data));
            assertTrue(data.copied.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(DpsGUI::clearDpsLogs);
            long clearedRevision = view[0].encounters().revision();
            data.release.countDown(); pending.get(3, TimeUnit.SECONDS);
            assertTrue(view[0].encounters().entries().isEmpty()); assertTrue(view[0].encounters().checkedEntries().isEmpty());
            assertEquals(clearedRevision, view[0].encounters().revision()); assertNull(view[0].encounters().find(old));
            DpsData fresh = EncounterCatalogTest.encounter("B");
            capture.submit(() -> { data.dpsData.add(fresh); DpsGUI.updateMapPacket(data); }).get(3, TimeUnit.SECONDS);
            assertEquals(1, view[0].encounters().entries().size()); assertSame(fresh, view[0].encounters().entries().get(0).data);
        } finally { data.release.countDown(); capture.shutdownNow(); }
    }
    private static final class BlockingHistory extends TomatoData {
        volatile boolean block;
        final CountDownLatch copied = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public DpsData[] closedDpsSnapshot() {
            DpsData[] snapshot = super.closedDpsSnapshot();
            if (block) {
                block = false; copied.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Snapshot was not released"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            return snapshot;
        }
    }
}
