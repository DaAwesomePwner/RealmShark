package tomato.gui.stats;

import assets.IdToAsset;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.LootDelivery;
import tomato.realmshark.SendLoot;
import tomato.realmshark.AlertRules;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class LootNotificationTest {
    private String savedEnchantRules;
    private String savedItemRules;
    private TomatoData data;
    private LootGUI view;
    private SendLoot.Session sharing;

    @Before public void setup() throws Exception {
        savedEnchantRules = PropertiesManager.getProperty("enchantPing.selected");
        savedItemRules = PropertiesManager.getProperty(AlertRules.Domain.ITEM.key());
        PropertiesManager.flush().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
        preferences().remove(AlertRules.Domain.ITEM.key());
        PropertiesManager.setProperties("enchantPing.selected", "777,888");
        data = new TomatoData();
        sharing = new SendLoot.Session(new LootDelivery(() -> { throw new AssertionError("Notification tests must not connect"); }, 2, true, false));
        SwingUtilities.invokeAndWait(() -> view = new LootGUI(data, sharing));
    }

    @After public void restore() throws Exception {
        PropertiesManager.flush().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
        if (savedItemRules == null) preferences().remove(AlertRules.Domain.ITEM.key());
        else preferences().setProperty(AlertRules.Domain.ITEM.key(), savedItemRules);
        PropertiesManager.setProperties("enchantPing.selected", savedEnchantRules == null ? "" : savedEnchantRules);
        PropertiesManager.flush().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
        LootGUI.lootSharing(true);
        sharing.close();
    }
    private static java.util.Properties preferences() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        return (java.util.Properties)field.get(null);
    }

    @Test public void typedExactIdAtTheLootProducerCoalescesEnchantsAndRejects142() {
        data.setPropList("itemPings", new ArrayList<>(Collections.singletonList("142")));
        PropertiesManager.setProperties(AlertRules.Domain.ITEM.key(),
            "{\"version\":1,\"rules\":[{\"mode\":\"ITEM_ID\",\"value\":\"42\"}]}");
        Entity bag = new Entity(null, 9, 0);
        item(bag, 0, 42); item(bag, 1, 142); item(bag, 2, -1); item(bag, 3, 42); item(bag, 7, 999997);
        enchants(bag, String.join(",", encode(777), "", encode(777), "!!!", "", "", "", encode(777)));
        for (boolean disabled : new boolean[]{true, false}) {
            LootGUI.lootSharing(disabled); List<String> calls = new ArrayList<>();
            view.notifyItems(bag, () -> calls.add("alert"), () -> {
                assertEquals(Arrays.asList("alert", "alert", "alert"), calls); calls.add("share");
            });
            assertEquals(disabled ? Arrays.asList("alert", "alert", "alert")
                : Arrays.asList("alert", "alert", "alert", "share"), calls);
        }
    }

    @Test public void unsupportedTypedRulesDoNotReactivateTheLegacyLootProbe() {
        data.setPropList("itemPings", new ArrayList<>(Collections.singletonList("42")));
        PropertiesManager.setProperties(AlertRules.Domain.ITEM.key(), "{\"version\":999,\"rules\":[]}");
        Entity bag = new Entity(null, 9, 0); item(bag, 0, 42); item(bag, 7, 142);
        LootGUI.lootSharing(true);
        view.notifyItems(bag, () -> fail("Unsupported typed rules must remain inactive"), () -> fail("Sharing opted out"));
    }

    @Test public void alertsOncePerMatchingItemBeforeSharingWithEitherOptOutState() {
        Entity bag = new Entity(null, 42, 0);
        item(bag, 1, -1); // An encoded enchant in an empty inventory slot must not alert.
        item(bag, 2, 999992); item(bag, 5, 999995); item(bag, 7, 999997);
        data.setPropList("itemPings", new ArrayList<>(Arrays.asList("999992", "999997")));
        enchants(bag, String.join(",", encode(777), encode(777), encode(777, 888), "", "",
            encode(-1, -2, 777), "", encode(999)));

        for (boolean disabled : new boolean[]{true, false}) {
            LootGUI.lootSharing(disabled);
            List<String> calls = new ArrayList<>();
            view.notifyItems(bag, () -> calls.add("alert"), () -> {
                assertEquals(Arrays.asList("alert", "alert", "alert"), calls);
                calls.add("share");
            });
            assertEquals(disabled ? Arrays.asList("alert", "alert", "alert")
                : Arrays.asList("alert", "alert", "alert", "share"), calls);
        }
    }

    @Test public void malformedSlotsDoNotAbortOrdinaryAlertsLaterEnchantMatchesOrSharing() {
        byte[] truncated = Arrays.copyOf(Base64.getUrlDecoder().decode(encode(777)), 10);
        byte[] wrongHeader = Base64.getUrlDecoder().decode(encode(777)); wrongHeader[0] = 1;
        for (String bad : new String[]{"!!!", "AA", "AAIEAQ==", encode(777, -4),
                Base64.getUrlEncoder().encodeToString(truncated), Base64.getUrlEncoder().encodeToString(wrongHeader)}) {
            Entity bag = new Entity(null, 42, 0);
            for (int slot : new int[]{0, 2, 4, 7}) item(bag, slot, 999990 + slot);
            data.setPropList("itemPings", new ArrayList<>(Arrays.asList("999990", "999997")));
            // A bad slot with a matching prefix is still invalid. Slot 7's enchant data is missing.
            enchants(bag, String.join(",", bad, "", bad, "", encode(777)));
            for (boolean disabled : new boolean[]{true, false}) {
                LootGUI.lootSharing(disabled);
                List<String> calls = new ArrayList<>();
                view.notifyItems(bag, () -> calls.add("alert"), () -> calls.add("share"));
                assertEquals(bad, disabled ? Arrays.asList("alert", "alert", "alert")
                    : Arrays.asList("alert", "alert", "alert", "share"), calls);
            }
        }
    }

    @Test public void matchingCopiesOfAnUnknownItemAlertForEachRealInventorySlot() {
        Entity bag = new Entity(null, 42, 0);
        item(bag, 0, 999990); item(bag, 7, 999990);
        String unpadded = encode(777, 888).replace("=", "");
        enchants(bag, String.join(",", encode(777, 888), "", "", "", "", "", "", unpadded));
        LootGUI.lootSharing(true);
        List<String> calls = new ArrayList<>();
        view.notifyItems(bag, () -> calls.add("alert"), () -> fail("Sharing is opted out"));
        assertEquals(Arrays.asList("alert", "alert"), calls);
    }

    @Test public void absentEmptyLockedAndNonMatchingEnchantsDoNotAlert() {
        Entity bag = new Entity(null, 42, 0);
        item(bag, 0, 999990); item(bag, 7, 999997);
        LootGUI.lootSharing(true);
        Runnable unexpected = () -> fail("No matching local rule; sharing is opted out");
        view.notifyItems(bag, unexpected, unexpected); // Entire UNIQUE_DATA_STRING stat absent.
        for (String code : new String[]{null, "", ",,,,,,,", "AAIE_f_9__3__f8=", encode(-1, -2), encode(999)}) {
            enchants(bag, code);
            view.notifyItems(bag, unexpected, unexpected);
        }
    }

    @Test public void clearingEnchantRulesStopsEnchantAlertsButKeepsOrdinaryItemAlerts() {
        Entity bag = new Entity(null, 42, 0);
        item(bag, 0, 999990); item(bag, 1, 999991);
        enchants(bag, encode(777) + "," + encode(888));
        data.setPropList("itemPings", new ArrayList<>(Collections.singletonList("999991")));
        LootGUI.lootSharing(true);
        List<String> calls = new ArrayList<>();
        Runnable unexpectedShare = () -> fail("Sharing is opted out");
        view.notifyItems(bag, () -> calls.add("alert"), unexpectedShare);
        assertEquals(Arrays.asList("alert", "alert"), calls);
        PropertiesManager.setProperties("enchantPing.selected", "");
        calls.clear();
        view.notifyItems(bag, () -> calls.add("alert"), unexpectedShare);
        assertEquals(Collections.singletonList("alert"), calls);
    }

    @Test public void nameIdAndEnchantRulesCoalesceToOneAlertForTheSameItem() throws Exception {
        Field registry = IdToAsset.class.getDeclaredField("objectID"); registry.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer, IdToAsset> assets = (Map<Integer, IdToAsset>)registry.get(null);
        int id = 999989;
        IdToAsset previous = assets.put(id, new IdToAsset("", id, "Notification test blade", "", "Equipment", null, "", "EQUIPMENT", ""));
        try {
            Entity bag = new Entity(null, 42, 0); item(bag, 0, id); enchants(bag, encode(777, 888));
            LootGUI.lootSharing(true);
            for (List<String> rules : Arrays.asList(Collections.singletonList("Notification test blade"),
                    Arrays.asList("Notification test blade", Integer.toString(id)))) {
                data.setPropList("itemPings", new ArrayList<>(rules));
                List<String> calls = new ArrayList<>();
                view.notifyItems(bag, () -> calls.add("alert"), () -> fail("Sharing is opted out"));
                assertEquals(Collections.singletonList("alert"), calls);
            }
            PropertiesManager.setProperties("enchantPing.selected", "");
            data.setPropList("itemPings", new ArrayList<>(Collections.singletonList("Notification test blade")));
            List<String> calls = new ArrayList<>();
            view.notifyItems(bag, () -> calls.add("alert"), () -> fail("Sharing is opted out"));
            assertEquals("Name rule alone still alerts", Collections.singletonList("alert"), calls);
        } finally { if (previous == null) assets.remove(id); else assets.put(id, previous); }
    }

    private static String encode(int... ids) { return LootEquipmentTest.encode(ids); }

    private static void item(Entity bag, int slot, int id) {
        StatData stat = new StatData(); stat.statValue = id;
        bag.stat.set(StatType.byOrdinal(StatType.INVENTORY_0_STAT.get() + slot), stat);
    }

    private static void enchants(Entity bag, String encoded) {
        StatData stat = new StatData(); stat.stringStatValue = encoded;
        bag.stat.set(StatType.UNIQUE_DATA_STRING, stat);
    }
}
