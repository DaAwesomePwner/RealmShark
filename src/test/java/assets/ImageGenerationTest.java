package assets;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImageGenerationTest {
    @Test public void pausedOldCoordinatesCannotLoadTheNewAtlas() throws Exception { interleave(false); }
    @Test public void newCoordinatesCannotUseTheCachedOldAtlas() throws Exception { interleave(true); }

    private void interleave(boolean warmAtlas) throws Exception {
        try (AssetGenerationFixture fixture = new AssetGenerationFixture()) {
            fixture.install(false);
            if (warmAtlas) assertEquals(Color.RED.getRGB(), ImageBuffer.getImage(AssetGenerationFixture.ITEM).getRGB(0, 0));
            else ImageBuffer.clear();
            Path red = AssetCache.root();
            CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1), prepared = new CountDownLatch(1);
            AtomicReference<BufferedImage> observed = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    if (warmAtlas) synchronized (ImageBuffer.class) {
                        paused.countDown(); await(release);
                        observed.set(ImageBuffer.getImage(AssetGenerationFixture.ITEM));
                    } else observed.set(ImageBuffer.getImage(AssetGenerationFixture.ITEM, () -> { paused.countDown(); await(release); }));
                } catch (Throwable e) { failure.compareAndSet(null, e); }
            }, "paused-image-reader");
            Thread publisher = new Thread(() -> {
                try { fixture.install(true, message -> { if (message.equals("Publishing validated assets")) prepared.countDown(); }); }
                catch (Throwable e) { failure.compareAndSet(null, e); }
            }, "prepared-image-publisher");
            try {
                reader.start(); assertTrue(paused.await(3, TimeUnit.SECONDS)); publisher.start();
                assertTrue(prepared.await(3, TimeUnit.SECONDS));
                awaitImageMonitor(publisher, reader);
                assertEquals("The active root cannot advance while old image coordinates/atlas are in use", red, AssetCache.root());
            } finally {
                release.countDown(); reader.join(5000); publisher.join(5000);
            }
            assertFalse(reader.isAlive()); assertFalse(publisher.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(Color.RED.getRGB(), observed.get().getRGB(0, 0));
            assertNotEquals(red, AssetCache.root());
            assertEquals(Color.BLUE.getRGB(), ImageBuffer.getImage(AssetGenerationFixture.ITEM).getRGB(0, 0));
            assertArrayEquals(new float[]{0, 0, 1, 1}, ImageBuffer.getColor(1), 0f);
        }
    }
    private static void awaitImageMonitor(Thread publisher, Thread reader) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(publisher.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockOwnerId() == reader.getId()
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(ImageBuffer.class)) return;
            Thread.sleep(2);
        }
        fail("Publisher did not wait at the image-read monitor");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Paused image reader was not released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
