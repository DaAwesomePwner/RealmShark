package tomato.backend.data;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.StatData;
import static org.junit.Assert.*;

public class CruciblePublicationTest {
    @Before @After public void reset() { CrucibleBonusManager.clear(); }

    @Test public void packetConfigurationWinsAgainstBlockedStartupApiWithoutBlockingReaders() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        setPlayerIds();
        try {
            Future<?> fetch = threads.submit(() -> CrucibleBonusManager.fetchCrucibleDataFromApi(() -> {
                entered.countDown(); await(release); return configuration(2);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, threads.submit(CrucibleBonusManager::getPlayerDamageMultiplier).get(2, TimeUnit.SECONDS), 0);
            CrucibleBonusManager.applyPacketJson(configuration(3));
            release.countDown(); fetch.get(2, TimeUnit.SECONDS);
            assertEquals(9, CrucibleBonusManager.getPlayerDamageMultiplier(), 0);
            assertFalse(CrucibleBonusManager.isApiDataLoaded());
            CrucibleBonusManager.fetchCrucibleDataFromApi(() -> { fail("Packet config must suppress later API fetches"); return null; });
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test public void clearInvalidatesInFlightApiAndReadersSeeCompleteConfigurations() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        AtomicBoolean finished = new AtomicBoolean();
        try {
            Future<?> old = threads.submit(() -> CrucibleBonusManager.fetchCrucibleDataFromApi(() -> {
                entered.countDown(); await(release); return configuration(2);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CrucibleBonusManager.clear(); setPlayerIds();
            CrucibleBonusManager.fetchCrucibleDataFromApi(() -> configuration(3));
            release.countDown(); old.get(2, TimeUnit.SECONDS);
            assertTrue(CrucibleBonusManager.isApiDataLoaded());
            assertEquals(9, CrucibleBonusManager.getPlayerDamageMultiplier(), 0);
            Future<?> reader = threads.submit(() -> {
                while (!finished.get()) {
                    double value = CrucibleBonusManager.getPlayerDamageMultiplier();
                    assertTrue("Partial or mixed config: " + value, value == 4 || value == 9);
                }
            });
            for (int i = 0; i < 100; i++) CrucibleBonusManager.applyPacketJson(configuration(i % 2 == 0 ? 2 : 3));
            finished.set(true); reader.get(2, TimeUnit.SECONDS);
        } finally { finished.set(true); release.countDown(); threads.shutdownNow(); }
    }

    private static void setPlayerIds() {
        Entity player = new Entity(null, 1, 0);
        StatData a = new StatData(); a.stringStatValue = "a"; player.stat.stats[128] = a;
        StatData b = new StatData(); b.stringStatValue = "b"; player.stat.stats[155] = b;
        CrucibleBonusManager.updatePlayerCrucibleBonus(player);
    }
    private static String configuration(int multiplier) {
        return "[{\"array\":[{\"id\":\"a\",\"bonuses\":[{\"type\":5,\"amount\":" + multiplier
            + "}]},{\"id\":\"b\",\"bonuses\":[{\"type\":5,\"amount\":" + multiplier + "}]}]}]";
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Blocked request not released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
