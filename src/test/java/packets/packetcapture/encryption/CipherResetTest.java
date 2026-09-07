package packets.packetcapture.encryption;

import org.junit.Test;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class CipherResetTest {
    @Test public void reconnectClearsAllAlignmentHistory() throws Exception {
        TickAligner aligner = new TickAligner(new RC4(new byte[]{1,2,3}));
        set(aligner, "synced", true); set(aligner, "packetBytes", 999); set(aligner, "TickA", new byte[4]);
        aligner.reset();
        assertEquals(true, get(aligner,"synced")); assertEquals(0, get(aligner,"packetBytes"));
        assertNull(get(aligner,"TickA")); assertEquals(-1, get(aligner,"CURRENT_TICK"));
        aligner.awaitAlignment();
        assertEquals(false, get(aligner,"synced"));
    }
    private static void set(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); f.set(obj, value);
    }
    private static Object get(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj);
    }
}
