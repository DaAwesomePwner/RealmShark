package tomato;

import assets.AssetCache;
import assets.AssetGenerationFixture;
import assets.ImageBuffer;
import java.awt.Color;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.PacketProcessor;
import tomato.backend.data.TomatoData;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class AssetReadinessRecoveryTest {
    @Test public void failedReplacementKeepsManualCaptureAndRetryUsesValidatedCacheWithoutOriginalResources() throws Exception {
        Map<Field,Object> original = new LinkedHashMap<>();
        String savedPreference = PropertiesManager.getProperty("sniffer");
        AtomicInteger created = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicReference<PacketProcessor> active = new AtomicReference<>();
        java.util.function.Supplier<PacketProcessor> factory = () -> {
            created.incrementAndGet();
            PacketProcessor worker = new PacketProcessor() {
                @Override public void tapPackets() {
                    started.countDown();
                    try { stopped.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                @Override public void stopSniffer() { requestStop(); stopped.countDown(); }
            };
            active.set(worker); return worker;
        };
        Path invalid = Files.createTempFile(Paths.get("."), "invalid-replacement-", ".assets");
        try (AssetGenerationFixture fixture = new AssetGenerationFixture()) {
            fixture.install(false); fixture.removeSource();
            Path previous = AssetCache.root();
            SwingUtilities.invokeAndWait(() -> {
                replace(original, "packetProcessor", null); replace(original, "capturePublication", new CapturePublication(new TomatoData()));
                replace(original, "preview", false); replace(original, "assetsReady", false); replace(original, "setupBusy", false);
                replace(original, "stoppingCapture", false); replace(original, "restartCapture", false);
                PropertiesManager.setProperties("sniffer", "F");
            });
            assertTrue(runSetup(null, false, factory).get(5, TimeUnit.SECONDS));
            await(() -> !flag("setupBusy")); assertTrue(flag("assetsReady"));
            PropertiesManager.setProperties("sniffer", "T");
            try { runSetup(invalid.toFile(), true, factory).get(5, TimeUnit.SECONDS); fail("Replacement must fail"); }
            catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
            await(() -> !flag("setupBusy"));
            assertTrue("A replacement error is not an active-cache validation failure", flag("assetsReady"));
            assertEquals(previous, AssetCache.root());
            assertEquals(Color.RED.getRGB(), ImageBuffer.getImage(AssetGenerationFixture.ITEM).getRGB(0, 0));
            // Both the short-header reader and the later serialized-data reader must release failed inputs.
            Files.delete(invalid);
            java.nio.ByteBuffer truncated = java.nio.ByteBuffer.allocate(64);
            truncated.putInt(0).putInt(64).putInt(22).putInt(64).putInt(0).putInt(0).putLong(64).putLong(64).putLong(0);
            while (truncated.hasRemaining()) truncated.put((byte)'X'); // Unity version string has no terminator.
            Files.write(invalid, truncated.array());
            try { runSetup(invalid.toFile(), true, factory).get(5, TimeUnit.SECONDS); fail("Truncated replacement must fail promptly"); }
            catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
            await(() -> !flag("setupBusy")); assertTrue(flag("assetsReady"));
            Files.delete(invalid);
            assertEquals(0, created.get());
            assertTrue("Retry revalidates the active cache instead of forcing recover(null)", runSetup(null, true, factory).get(5, TimeUnit.SECONDS));
            await(() -> !flag("setupBusy")); assertTrue(flag("assetsReady"));
            assertEquals(0, created.get()); assertEquals("T", PropertiesManager.getProperty("sniffer"));
            SwingUtilities.invokeAndWait(() -> Tomato.setCaptureRequested(true, factory));
            assertTrue(started.await(2, TimeUnit.SECONDS)); assertEquals(1, created.get());
            SwingUtilities.invokeAndWait(Tomato::stopPacketSniffer);
            active.get().join(3000); assertFalse(active.get().isAlive());
            SwingUtilities.invokeAndWait(() -> assertFalse(Tomato.isCaptureRunning()));
        } finally {
            SwingUtilities.invokeAndWait(Tomato::stopPacketSniffer);
            stopped.countDown();
            if (active.get() != null) { active.get().join(3000); assertFalse(active.get().isAlive()); }
            SwingUtilities.invokeAndWait(() -> {
                try { for (Map.Entry<Field,Object> entry : original.entrySet()) entry.getKey().set(null, entry.getValue()); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            PropertiesManager.setProperties("sniffer", savedPreference == null ? "" : savedPreference);
            Files.deleteIfExists(invalid);
        }
    }
    private static SwingWorker<Boolean,String> runSetup(java.io.File chosen, boolean recover, java.util.function.Supplier<PacketProcessor> factory) throws Exception {
        AtomicReference<SwingWorker<Boolean,String>> worker = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> worker.set(Tomato.beginAssetSetup(chosen, recover, factory)));
        assertNotNull(worker.get()); return worker.get();
    }
    private static void replace(Map<Field,Object> original, String name, Object value) {
        try { Field field = Tomato.class.getDeclaredField(name); field.setAccessible(true); original.put(field, field.get(null)); field.set(null, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static boolean flag(String name) {
        try { Field field = Tomato.class.getDeclaredField(name); field.setAccessible(true); return field.getBoolean(null); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
