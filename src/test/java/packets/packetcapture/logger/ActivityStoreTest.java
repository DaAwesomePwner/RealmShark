package packets.packetcapture.logger;

import java.lang.reflect.Field;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ActivityStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void loadAndCloseNeedNoWorkerAndTheFirstOfferStartsOnlyOne() throws Exception {
        Field worker = ActivityStore.class.getDeclaredField("worker"); worker.setAccessible(true);
        java.nio.file.Path directory = temp.newFolder().toPath();
        ActivityStore unused = new ActivityStore(directory);
        assertNull(unused.load()); assertNull(worker.get(unused)); unused.close(); assertNull(worker.get(unused));
        unused.offer(new ActivityJournal.State()); assertNull(worker.get(unused));
        ActivityStore store = new ActivityStore(directory);
        try {
            assertNull(worker.get(store)); store.offer(new ActivityJournal.State());
            Thread started = (Thread)worker.get(store); assertNotNull(started); assertTrue(started.isDaemon());
            store.offer(new ActivityJournal.State()); assertSame(started, worker.get(store));
        } finally { store.close(); }
        assertEquals("", store.error()); assertFalse(((Thread)worker.get(store)).isAlive());
        ActivityStore reader = new ActivityStore(directory);
        try { assertNotNull(reader.load()); assertNull(worker.get(reader)); } finally { reader.close(); }
    }
}
