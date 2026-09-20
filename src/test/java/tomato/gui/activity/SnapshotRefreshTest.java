package tomato.gui.activity;

import org.junit.Test;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SnapshotRefreshTest {
    @Test public void blockedReadCoalescesToNewestSelectionAndCannotOverwriteIt() throws Exception {
        SnapshotRefresh<String> refresh=new SnapshotRefresh<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),applied=new CountDownLatch(1);
        AtomicInteger reads=new AtomicInteger();List<String> results=new ArrayList<>();AtomicReference<Exception> failure=new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(()->refresh.request("old-visit",()->{
                reads.incrementAndGet();entered.countDown();waitFor(release);return "old-visit";
            },results::add,failure::set));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{
                for(int n=0;n<100;n++){
                    String visit="visit-"+n;
                    refresh.request(visit,()->{reads.incrementAndGet();return visit;},value->{results.add(value);applied.countDown();},failure::set);
                }
            });
            CountDownLatch heartbeat=new CountDownLatch(1);SwingUtilities.invokeLater(heartbeat::countDown);
            assertTrue("blocked reader must not block the EDT",heartbeat.await(2,TimeUnit.SECONDS));assertEquals(1,reads.get());
            release.countDown();assertTrue(applied.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->assertEquals(Arrays.asList("visit-99"),results));
            assertEquals("one running read and one coalesced successor",2,reads.get());assertNull(failure.get());
        } finally {release.countDown();}
    }
    @Test public void freezeOrHideInvalidatesPendingAndInFlightReadsThenResumeCatchesUp() throws Exception {
        SnapshotRefresh<String> refresh=new SnapshotRefresh<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),applied=new CountDownLatch(1);
        List<String> results=new ArrayList<>();AtomicInteger discardedRead=new AtomicInteger();AtomicReference<Exception> failure=new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(()->refresh.request("visit",()->{entered.countDown();waitFor(release);return "before-freeze";},results::add,failure::set));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{
                refresh.request("visit",()->{discardedRead.incrementAndGet();return "pending";},results::add,failure::set);
                refresh.invalidate();
                refresh.request("visit",()->"after-resume",value->{results.add(value);applied.countDown();},failure::set);
            });
            release.countDown();assertTrue(applied.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->assertEquals(Arrays.asList("after-resume"),results));
            assertEquals(0,discardedRead.get());assertNull(failure.get());
        } finally {release.countDown();}
    }
    private static void waitFor(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Reader was not released"); }
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
    }
}
