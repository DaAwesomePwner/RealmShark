package tomato.gui.activity;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.assertTrue;

/** Bounded asynchronous UI assertions; the EDT keeps dispatching while reads finish. */
public final class SnapshotTestSupport {
    private SnapshotTestSupport() {}
    public static void await(BooleanSupplier condition) {
        if (!SwingUtilities.isEventDispatchThread()) {
            try { SwingUtilities.invokeAndWait(()->await(condition)); }
            catch (Exception error) { throw new AssertionError(error); }
            return;
        }
        if (condition.getAsBoolean()) return;
        SecondaryLoop loop=Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        long deadline=System.nanoTime()+5_000_000_000L;
        AtomicBoolean satisfied=new AtomicBoolean();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Timer timer=new Timer(10,e->{
            try { satisfied.set(condition.getAsBoolean()); }
            catch (Throwable error) { failure.set(error); }
            if (satisfied.get() || failure.get()!=null || System.nanoTime()>=deadline) loop.exit();
        });
        timer.start();try { loop.enter(); } finally { timer.stop(); }
        if (failure.get()!=null) throw new AssertionError(failure.get());
        assertTrue("Asynchronous UI condition did not complete",satisfied.get());
    }
}
