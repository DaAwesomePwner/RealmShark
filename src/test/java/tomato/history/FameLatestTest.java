package tomato.history;

import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

public class FameLatestTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AtomicLong clock = new AtomicLong(1000);
    private SessionStore store;
    private Field storeField;
    private Object previousStore;
    private LongSupplier previousClock;
    private Method record;
    private static final String ACCOUNT = "a".repeat(64);

    @Before public void setup() throws Exception {
        storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        previousStore = storeField.get(null); previousClock = AppHistory.fameClock;
        store = new SessionStore(temp.newFolder().toPath(), true, "test");
        storeField.set(null, store); AppHistory.fameClock = clock::get;
        record = AppHistory.class.getDeclaredMethod("record", int.class, String.class, long.class, long.class, String.class, VisitRef.class, String.class);
        record.setAccessible(true);
    }
    @After public void cleanup() throws Exception {
        store.close(); storeField.set(null, previousStore); AppHistory.fameClock = previousClock;
    }
    private void sample(String account, long fame, long time, String type, VisitRef visit, String map) throws Exception {
        record.invoke(null, 98701, account, fame, time, type, visit, map);
        store.flush();
    }
    private AppHistory.FameSample latest(String account) throws Exception {
        return store.readCheckpoint(store.currentId(), "fame-latest", account == null ? "98701" : account + ":98701", AppHistory.FameSample.class).get();
    }
    @Test public void unchangedSamplesWaitForTheIntervalAndCollectFlushesWithoutAnotherSample() throws Exception {
        sample(ACCOUNT, 10, 1000, "Wizard", null, null);
        clock.set(2000); sample(ACCOUNT, 10, 2000, "Wizard", null, null);
        clock.set(30999); store.flush(); assertEquals(1000, latest(ACCOUNT).time);
        clock.set(31000); store.flush(); assertEquals(2000, latest(ACCOUNT).time);
        clock.set(61000); sample(ACCOUNT, 10, 61000, "Wizard", null, null);
        assertEquals(61000, latest(ACCOUNT).time);
        assertEquals("Heartbeat checkpoints do not change journal gating", 1, store.read(store.currentId(), "fame", AppHistory.FameSample.class).size());
    }
    @Test public void everyNonTimeFieldAndAccountStreamIsUpdatedImmediately() throws Exception {
        sample(ACCOUNT, 20, 1000, "Wizard", null, null);
        sample(ACCOUNT, 21, 1001, "Wizard", null, null); assertEquals(21, latest(ACCOUNT).fame);
        sample(ACCOUNT, 21, 1002, "Priest", null, null); assertEquals("Priest", latest(ACCOUNT).className);
        VisitRef first = new VisitRef(store.currentId(), "one"), second = new VisitRef(store.currentId(), "two");
        sample(ACCOUNT, 21, 1003, "Priest", first, "Vault"); assertEquals(first, latest(ACCOUNT).visit());
        sample(ACCOUNT, 21, 1004, "Priest", second, "Vault"); assertEquals(second, latest(ACCOUNT).visit());
        sample(ACCOUNT, 21, 1005, "Priest", second, "Nexus"); assertEquals("Nexus", latest(ACCOUNT).map);
        sample(ACCOUNT, 21, 1006, "Priest", null, null); assertNull(latest(ACCOUNT).visitSession);
        sample(null, 21, 1007, "Priest", null, null); assertNull(latest(null).account);
        sample("b".repeat(64), 21, 1008, "Priest", null, null); assertEquals("b".repeat(64), latest("b".repeat(64)).account);
        assertEquals(1006, latest(ACCOUNT).time);
    }
    @Test public void backwardsSampleTimeDoesNotDelayTheMonotonicExpiry() throws Exception {
        sample(ACCOUNT, 40, 100000, "Wizard", null, null);
        clock.set(2000); sample(ACCOUNT, 40, 50000, "Wizard", null, null);
        clock.set(30999); store.flush(); assertEquals(100000, latest(ACCOUNT).time);
        clock.set(31000); store.flush(); assertEquals(50000, latest(ACCOUNT).time);
    }
    @Test public void closeFlushesTheNewestSuppressedSampleForEveryKey() throws Exception {
        sample(ACCOUNT, 30, 1000, "Wizard", null, null);
        sample(null, 30, 1001, "Wizard", null, null);
        sample(ACCOUNT, 30, 2000, "Wizard", null, null);
        sample(ACCOUNT, 30, 3000, "Wizard", null, null);
        sample(null, 30, 4000, "Wizard", null, null);
        assertEquals(1000, latest(ACCOUNT).time); assertEquals(1001, latest(null).time);
        store.close();
        assertEquals(3000, latest(ACCOUNT).time); assertEquals(4000, latest(null).time);
    }
}
