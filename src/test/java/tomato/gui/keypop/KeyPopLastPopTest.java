package tomato.gui.keypop;

import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.*;
import packets.data.enums.NotificationEffectType;
import packets.incoming.NotificationPacket;
import static org.junit.Assert.*;

/** Home's last-pop line reads the live buffer that KeypopGUI.packet records into; the buffer is restored afterwards. */
public class KeyPopLastPopTest {
    private KeyPopHistory.Snapshot before;

    private static KeyPopHistory live() throws Exception {
        Field field = KeypopGUI.class.getDeclaredField("history"); field.setAccessible(true);
        return (KeyPopHistory) field.get(null);
    }
    private static NotificationPacket server(String message) {
        NotificationPacket packet = new NotificationPacket(); packet.effect = NotificationEffectType.ServerMessage; packet.message = message;
        return packet;
    }
    @Before public void remember() throws Exception { before = live().snapshot(); }
    @After public void restore() throws Exception {
        KeyPopHistory history = live(); history.clear();
        for (KeyPopEvent event : before.events) history.add(event);
    }

    @Test public void lastPopIsTheNewestRecordedPopAndTheRevisionCountsEachOne() throws Exception {
        long revision = KeypopGUI.popRevision();
        Instant start = Instant.now();
        KeypopGUI.packet(null, server("{\"player\":\"Aster,metadata\",\"name\":\"The Void\"}"));
        KeypopGUI.LastPop vial = KeypopGUI.lastPop();
        assertEquals("Aster", vial.player()); assertEquals("Vial", vial.dungeon()); assertFalse(vial.time().isBefore(start));
        assertEquals(revision + 1, KeypopGUI.popRevision());
        KeypopGUI.packet(null, server("{\"name\":\"Wine Cellar\",\"player\":\"Wren\"}"));
        assertEquals("Wren", KeypopGUI.lastPop().player()); assertEquals("Inc", KeypopGUI.lastPop().dungeon());
        assertEquals(revision + 2, KeypopGUI.popRevision());
        KeypopGUI.packet(null, server("{\"name\":\"Some achievement\",\"player\":\"Nova\"}"));
        assertEquals("Not a pop: nothing recorded", revision + 2, KeypopGUI.popRevision());
        assertEquals("Wren", KeypopGUI.lastPop().player());
        live().clear();
        assertNull("Clearing the live buffer clears the last pop", KeypopGUI.lastPop());
    }
}
