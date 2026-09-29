package tomato.gui.stats;

import assets.IdToAsset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.SendLoot;
import tomato.realmshark.Sound;

/**
 * The live loot pipeline, independent of any page (P6a). TomatoData calls {@link #update} for each bag it attributes; once exalt
 * stats have arrived ({@link #updateExaltStats()}) an accepted drop is recorded through the primary {@link #feed()} (the only
 * writer of the saved {@code loot} module), plays its bag sound, runs item and enchant pings, is offered to legacy loot sharing
 * (opt-out) and, in Moonlight Village, resets the flame counter 5 s later.
 * <p>Capture threads only hand data off: this class never constructs Swing components or waits for Swing. Views (Loot ›
 * Highlights and Explore) attach to {@link #feed()} and move to the EDT themselves.
 */
public final class LootCapture {
    /** Each drop's Moonlight flame reset runs this long after the drop (the retired Live log entry's Swing timer did the same). */
    static final long FLAME_RESET_MILLIS = 5000;
    private static volatile LootCapture instance;

    /** Playback seam: bag sounds and item/enchant ping sounds, each for a recorded decision. */
    interface Sounds {
        void bag(Sound sound);
        void item(long decision);
        /** The device: {@link Sound#play()} applies the enable/mute/volume gates and records the decision itself. */
        Sounds DEVICE = new Sounds() {
            @Override public void bag(Sound sound) { sound.play(); }
            @Override public void item(long decision) { Sound.custom.play(decision); }
        };
    }

    private final LootDashboard.Feed feed = new LootDashboard.Feed();
    private final SendLoot.Session sharing;
    private final Sounds sounds;
    private final ScheduledExecutorService flames;
    private final long flameResetMillis;
    private volatile TomatoData data;
    private volatile boolean update;

    /** Device sounds and the production flame delay; the scheduler's daemon thread starts with the first Moonlight drop. */
    LootCapture(TomatoData data, SendLoot.Session sharing) {
        this(data, sharing, Sounds.DEVICE, flameScheduler(), FLAME_RESET_MILLIS);
    }

    LootCapture(TomatoData data, SendLoot.Session sharing, Sounds sounds, ScheduledExecutorService flames, long flameResetMillis) {
        this.data = data;
        this.sharing = Objects.requireNonNull(sharing, "sharing");
        this.sounds = Objects.requireNonNull(sounds, "sounds");
        this.flames = Objects.requireNonNull(flames, "flames");
        this.flameResetMillis = flameResetMillis;
    }

    /** The app's capture, created on first use with the production legacy sharing session. Never constructs Swing components. */
    public static LootCapture get() {
        LootCapture capture = instance;
        if (capture != null) return capture;
        synchronized (LootCapture.class) {
            if (instance == null) instance = new LootCapture(null, SendLoot.session());
            return instance;
        }
    }

    /** Tests only: replaces the app's capture and returns the previous one (null = created again on next use). */
    static LootCapture install(LootCapture capture) {
        synchronized (LootCapture.class) {
            LootCapture previous = instance;
            instance = capture;
            return previous;
        }
    }

    private static ScheduledExecutorService flameScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "RealmShark loot flames");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * The game data capture reads: item ping lists, the Moonlight flame counter and legacy sharing's composition. Set once by the
     * shell ({@code TomatoGUI.createWorkspace}). Until then drops are still recorded, bag sounds and typed item rules still apply,
     * while legacy item lists, enchant pings and flames are unavailable.
     */
    public LootCapture bind(TomatoData data) {
        this.data = Objects.requireNonNull(data, "data");
        return this;
    }

    /** The one live loot state: attach live views to it ({@code new LootDashboard(feed())}); readers poll its revision. */
    public LootDashboard.Feed feed() { return feed; }

    /** The legacy loot sharing session (its delivery status is Loot › ⋯ › Loot sharing status…). */
    public SendLoot.Session sharing() { return sharing; }

    /** File › Opt-out Loot Sharing: {@code optOut} disables sharing and cancels unsent bags (in-flight sends stay uncertain). */
    public void lootSharing(boolean optOut) { sharing.setEnabled(!optOut); }

    /** Opens the update gate once exalt stats are known (loot bonus inputs); earlier drops are ignored, as before. */
    public void updateExaltStats() { update = true; }

    public void update(MapInfoPacket map, Entity bag, Entity dropper, Entity player, long time) {
        update(map, bag, dropper, player, time, DropContext.capture(map, player, time, null));
    }

    /** Capture thread. Ignored without a player or before {@link #updateExaltStats()}. */
    public void update(MapInfoPacket map, Entity bag, Entity dropper, Entity player, long time, DropContext context) {
        if (player == null || !update) return;
        TomatoData game = data;
        feed.receive(map, bag, dropper, time, context);
        if (flames(game, map) > 0) resetFlamesLater(game);
        // play() applies the enable/mute/volume gates itself, so a matching bag whose sound is turned off is still recorded as a
        // "Matched · alert off" decision; nothing more plays.
        Sound sound = bagSound(bag.objectType);
        if (sound != null) sounds.bag(sound);
        notifyItems(bag, sounds::item, () -> sharing.sendLoot(game, map, bag, dropper, player, time));
    }

    /** The Moonlight flame count a drop in {@code map} carries now (0 elsewhere or without game data). */
    private static int flames(TomatoData game, MapInfoPacket map) {
        return game != null && map != null && "Moonlight Village".equals(map.name) ? game.getMoonlightFlameCount() : 0;
    }

    /** One reset per drop (the retired Live log reset once per entry); it separates Umi's flames from other boss phases. */
    private void resetFlamesLater(TomatoData game) {
        try { flames.schedule(game::resetMoonlightFlames, flameResetMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException stopped) { /* shutting down: nothing is sent later */ }
    }

    /** The bag colors with their own sound (boosted variants included). */
    private static Sound bagSound(int objectType) {
        LootFilters.Kind kind = LootFilters.of(objectType);
        if (kind == null) return null;
        switch (kind) {
            case WHITE: return Sound.whitebag;
            case ORANGE: return Sound.orangebag;
            case RED: return Sound.redbag;
            case GOLD: return Sound.goldbag;
            case EGG: return Sound.eggbag;
            case BLUE: return Sound.bluebag;
            default: return null;
        }
    }

    /** Local alerts precede optional sharing. Callbacks allow verification without audio or networking. */
    void notifyItems(Entity bag, LongConsumer alert, Runnable share) {
        TomatoData game = data;
        List<String> legacyRules = game == null ? new ArrayList<>() : game.getItemPings();
        StatData unique = bag.stat.get(StatType.UNIQUE_DATA_STRING);
        String[] enchants = unique == null || unique.stringStatValue == null
            ? new String[0] : unique.stringStatValue.split(",", -1);
        for (int slot = 0; slot < 8; slot++) {
            StatData item = bag.stat.get(StatType.INVENTORY_0_STAT.get() + slot);
            if (item == null || item.statValue < 1) continue;
            String name = IdToAsset.objectName(item.statValue);
            String enchantText = notificationEnchants(slot < enchants.length ? enchants[slot] : null);
            boolean enchantMatch = !enchantText.isEmpty() && game != null && game.isEnchantPing(enchantText);
            // Records the item decision (match or no match); several matching rules still describe one dropped item.
            long decision = tomato.realmshark.AlertDecisions.lootItem(legacyRules, item.statValue, name, enchantText, enchantMatch);
            if (decision != 0) alert.accept(decision);
        }
        if (sharing.isEnabled()) share.run();
    }

    private static String notificationEnchants(String encoded) {
        if (ParseEnchants.summarize(encoded).applied <= 0) return "";
        try {
            // Valid URL Base64 may omit padding; the legacy ID decoder requires complete groups.
            while (encoded.length() % 4 != 0) encoded += "=";
            StringBuilder text = new StringBuilder();
            // Unlike the legacy display parser, include applied enchants after empty/locked slots.
            for (short id : ParseEnchants.extractEnchantIds(encoded)) {
                text.append(ParseEnchants.ENCHANTS.getOrDefault(id, "Unknown"))
                    .append('(').append(id).append(")\n");
            }
            return text.toString();
        } catch (RuntimeException e) {
            // One malformed slot must not suppress other item alerts or ordinary bag processing.
            return "";
        }
    }
}
