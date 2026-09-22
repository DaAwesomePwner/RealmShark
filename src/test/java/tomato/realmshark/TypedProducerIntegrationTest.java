package tomato.realmshark;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import packets.data.*;
import packets.incoming.UpdatePacket;
import tomato.backend.data.TomatoData;
import util.PropertiesManager;
import static org.junit.Assert.*;

/** Production entity/item entry points; audio is recorded, never played. */
public class TypedProducerIntegrationTest {
    private final Map<String, String> preferences = new HashMap<>();
    private boolean custom, muted;
    private int master, volume;

    @Before public void isolate() {
        for (AlertRules.Domain domain : new AlertRules.Domain[]{AlertRules.Domain.ITEM, AlertRules.Domain.ENTITY})
            preferences.put(domain.key(), PropertiesManager.getProperty(domain.key()));
        custom = Sound.custom.isEnabled(); muted = Sound.isMuted();
        master = Sound.getMasterVolume(); volume = Sound.custom.getVolume();
        Sound.custom.setEnabled(true); Sound.setMuted(false); Sound.setVolume(100); Sound.custom.setAlertVolume(100);
    }
    @After public void restore() throws Exception {
        Sound.playbackOverride = null;
        Sound.custom.setEnabled(custom); Sound.setMuted(muted); Sound.setVolume(master); Sound.custom.setAlertVolume(volume);
        PropertiesManager.flush().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
        java.lang.reflect.Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Properties memory = (Properties)field.get(null);
        preferences.forEach((key, value) -> { if (value == null) memory.remove(key); else memory.setProperty(key, value); });
        // Persist the restored test-only snapshot so another isolated run does not inherit the fixture rules.
        PropertiesManager.setProperties("soundMuted", Boolean.toString(muted));
        PropertiesManager.flush().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test public void exactEntityTypeUsesUnsignedTypeAndOnlyNewObjectsSubmitOneAlert() {
        AtomicInteger played = new AtomicInteger();
        Sound.playbackOverride = (sound, preview) -> { assertSame(Sound.custom, sound); assertFalse(preview); played.incrementAndGet(); };
        TomatoData data = new TomatoData();
        data.setPropList("entityIdPings", new ArrayList<>(Arrays.asList("142")));
        PropertiesManager.setProperties(AlertRules.Domain.ENTITY.key(), rules("ENTITY_TYPE", "42"));
        data.update(update(42, 142)); // Matching instance ID must not substitute for entity type.
        assertEquals(0, played.get());
        data.update(update(99, 42)); data.update(update(99, 42));
        assertEquals(1, played.get());
        PropertiesManager.setProperties(AlertRules.Domain.ENTITY.key(), rules("ENTITY_TYPE", "65535"));
        data.update(update(100, 65535)); assertEquals(2, played.get());
        PropertiesManager.setProperties(AlertRules.Domain.ENTITY.key(), "{\"version\":1,\"rules\":[]}");
        data.update(update(101, 142)); assertEquals(2, played.get());
    }

    @Test public void typedItemsMatchWithoutNamesAndLegacyStringCallerRemainsCompatible() {
        TomatoData data = new TomatoData();
        data.setPropList("itemPings", new ArrayList<>(Arrays.asList("42")));
        PropertiesManager.setProperties(AlertRules.Domain.ITEM.key(), rules("ITEM_ID", "42"));
        assertTrue(data.isItemPing(42, null));
        assertFalse(data.isItemPing(142, "Synthetic item"));
        assertTrue(data.isItemPing("142")); // Existing string API intentionally retains substring behavior.
        PropertiesManager.setProperties(AlertRules.Domain.ITEM.key(), rules("NAME_CONTAINS", "synthetic"));
        assertTrue(data.isItemPing(142, "Synthetic item")); assertFalse(data.isItemPing(42, null));
        PropertiesManager.setProperties(AlertRules.Domain.ITEM.key(), "{\"version\":999,\"rules\":[]}");
        assertFalse(data.isItemPing(42, "42")); // Unsupported typed data never resurrects legacy matching.
    }

    private static String rules(String mode, String value) {
        return "{\"version\":1,\"rules\":[{\"mode\":\"" + mode + "\",\"value\":\"" + value + "\"}]}";
    }
    private static UpdatePacket update(int objectId, int type) {
        ObjectStatusData status = new ObjectStatusData(); status.objectId = objectId;
        status.pos = new WorldPosData(); status.stats = new StatData[0];
        ObjectData object = new ObjectData(); object.objectType = type; object.status = status;
        UpdatePacket packet = new UpdatePacket(); packet.tiles = new GroundTileData[0];
        packet.newObjects = new ObjectData[]{object}; packet.drops = new int[0]; return packet;
    }
}
