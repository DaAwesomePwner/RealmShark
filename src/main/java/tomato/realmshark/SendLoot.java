package tomato.realmshark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.Tomato;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.enums.CharacterClass;
import tomato.version.Version;
import util.PropertiesManager;

/** Legacy wire composition and tick policy, independent of transport and local notifications. */
public final class SendLoot {
    private static final Set<String> SPECIAL_ATTRIBUTION_DUNGEONS = new HashSet<>(Arrays.asList(
        "The Shatters", "Oryx's Sanctuary", "Moonlight Village"));

    private SendLoot() { }

    private static final class Holder {
        // Match File > Disable data sending: only the literal "true" opts out; absent is enabled.
        static final Session SESSION = new Session(new LootDelivery(
            () -> new WebSocket("ws://38.45.66.65:3008"), LootDelivery.DEFAULT_CAPACITY,
            defaultEnabled(PropertiesManager.getProperty("disableDataSending")), Tomato.isPreview()));
        static {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                SESSION.close();
                try { SESSION.awaitStopped(3500); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }, "legacy-loot-shutdown"));
        }
    }

    public static boolean defaultEnabled(String disableDataSending) { return !"true".equals(disableDataSending); }
    public static Session session() { return Holder.SESSION; }
    public static void beginLootTick(int tickSeed) { session().beginLootTick(tickSeed); }
    public static void flushPendingOverflow() { session().flushPendingOverflow(); }
    public static void sendLoot(TomatoData data, MapInfoPacket map, Entity bag, Entity dropper, Entity player, long time) {
        session().sendLoot(data, map, bag, dropper, player, time);
    }

    /** Detached JSON composition. Does not initialize the production sender or open a socket. */
    public static JsonObject compose(TomatoData data, MapInfoPacket map, Entity bag, Entity dropper, Entity player, long time) {
        JsonArray items = new JsonArray();
        StatData unique = bag.stat.get(StatType.UNIQUE_DATA_STRING);
        String[] enchants = unique == null || unique.stringStatValue == null
            ? new String[0] : unique.stringStatValue.split(",", -1);
        for (int i = 0; i < 8; i++) {
            StatData stat = bag.stat.get(StatType.INVENTORY_0_STAT.get() + i);
            if (stat == null || stat.statValue < 1) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", stat.statValue);
            int sl = 0;
            if (i < enchants.length && !enchants[i].isEmpty() && !enchants[i].equals("AAIE_f_9__3__f8=")
                    && ParseEnchants.summarize(enchants[i]).slots >= 0) {
                try {
                    String encoded = enchants[i];
                    while (encoded.length() % 4 != 0) encoded += "=";
                    String text = ParseEnchants.parse(encoded);
                    // Legacy sl counts parser lines (including empty-slot descriptions), NOT rarity.
                    if (!text.isEmpty()) sl = Math.min(4, text.split("\n").length);
                } catch (RuntimeException ignored) { /* A malformed enchant never loses the item. */ }
            }
            item.addProperty("sl", sl);
            items.add(item);
        }
        String dungeon = map == null ? "" : map.name;
        JsonArray mods = new JsonArray();
        if (map != null) {
            for (int mod : ParseDungeon.getModIds(ParseDungeon.getModifiersString(map))) mods.add(mod);
            if ("Moonlight Village".equals(dungeon) && data != null) {
                int flames = data.getMoonlightFlameCount();
                if (flames > 0) mods.add("Flames:" + flames);
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("bag", bag.objectType);
        json.addProperty("pos", String.format(Locale.ROOT, "%f,%f", bag.pos.x, bag.pos.y));
        json.addProperty("dung", dungeon);
        json.add("mods", mods);
        if (dropper != null && dropper.lootMobIdOverride != null && !dropper.lootMobIdOverride.isEmpty()) {
            json.addProperty("mob", dropper.lootMobIdOverride);
        } else json.addProperty("mob", dropper == null ? -1 : dropper.objectType);
        json.addProperty("share", dropper == null ? -1 : dropper.playersRemainAtKill());
        json.add("items", items);
        json.addProperty("exalt", player == null || CharacterClass.weaponClasses(player.objectType) == null
            ? -1 : RealmCharacter.exaltLootBonus(player.objectType));
        json.addProperty("ld", player != null && player.lootDropTime(time) > 0);
        StatData seasonal = player == null ? null : player.stat.get(StatType.SEASONAL);
        json.addProperty("seas", seasonal != null && seasonal.statValue == 1);
        json.addProperty("cruc", player != null && player.isCrucible() ? 1 : 0);
        json.addProperty("lben", player == null ? 0f : ParseEnchants.getTotalLootBonusPercent(ParseEnchants.getEnchantStrings(player)));
        json.addProperty("ver", Version.VERSION);
        return json;
    }

    /** Injectable session: tests can exercise the real tick/merge path with a fake transport. */
    public static final class Session implements AutoCloseable {
        private final Object pendingLock = new Object();
        private final LootDelivery delivery;
        private JsonObject pending;
        private long tickGeneration;

        public Session(LootDelivery delivery) { this.delivery = delivery; }
        public boolean isEnabled() { return delivery.isEnabled(); }
        public LootDelivery.Status snapshot() { return delivery.snapshot(); }
        public int pendingBags() { synchronized (pendingLock) { return pending == null ? 0 : 1; } }

        public void setEnabled(boolean enabled) {
            synchronized (pendingLock) {
                delivery.setEnabled(enabled);
                if (!delivery.isEnabled()) discardPending();
            }
        }

        public void beginLootTick(int seed) {
            JsonObject flush;
            long generation;
            synchronized (pendingLock) {
                // TomatoData passes a map seed, which may be identical across many loot ticks.
                tickGeneration++;
                if (!delivery.isEnabled()) { discardPending(); return; }
                generation = delivery.generation();
                flush = pending; pending = null;
            }
            publish(flush, generation);
        }

        public void flushPendingOverflow() {
            JsonObject flush;
            long generation;
            synchronized (pendingLock) {
                if (!delivery.isEnabled()) { discardPending(); return; }
                generation = delivery.generation();
                flush = pending; pending = null;
            }
            publish(flush, generation);
        }

        public void sendLoot(TomatoData data, MapInfoPacket map, Entity bag, Entity dropper, Entity player, long time) {
            long generation;
            long tick;
            synchronized (pendingLock) {
                if (!delivery.isEnabled()) return;
                generation = delivery.generation(); tick = tickGeneration;
            }
            final JsonObject json;
            try { json = compose(data, map, bag, dropper, player, time); }
            catch (RuntimeException ex) { delivery.recordDrop("Loot composition failed: " + ex.getClass().getSimpleName()); return; }
            JsonObject flush;
            synchronized (pendingLock) {
                if (!delivery.isEnabled() || generation != delivery.generation()) {
                    delivery.recordDrop(null); return;
                }
                boolean mergeable = !SPECIAL_ATTRIBUTION_DUNGEONS.contains(json.get("dung").getAsString())
                    && !json.getAsJsonPrimitive("mob").isString();
                if (!mergeable || tick != tickGeneration) flush = json;
                else if (pending == null) { pending = json; return; }
                else if (pending.getAsJsonArray("items").size() >= 8 || json.getAsJsonArray("items").size() >= 8) {
                    flush = pending; pending = null;
                    flush.getAsJsonArray("items").addAll(json.getAsJsonArray("items"));
                    // Preserve legacy merged payload: first bag metadata, second bag's mods.
                    flush.add("mods", json.get("mods"));
                } else { flush = pending; pending = json; }
            }
            publish(flush, generation);
        }

        private void publish(JsonObject json, long generation) {
            if (json != null) delivery.offer(json.toString().getBytes(StandardCharsets.UTF_8), generation);
        }

        private void discardPending() {
            if (pending != null) { delivery.recordDrop(null); pending = null; }
        }

        @Override public void close() {
            synchronized (pendingLock) { discardPending(); delivery.close(); }
        }

        public boolean awaitStopped(long timeoutMillis) throws InterruptedException { return delivery.awaitStopped(timeoutMillis); }
    }
}
