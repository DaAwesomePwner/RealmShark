package tomato.gui.stats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tomato.backend.data.DungeonStatData;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import tomato.realmshark.enums.StatPotion;

/**
 * Read-only facts about saved loot bags for pages outside this package (Home, Runs, Loot Highlights). The persisted drop
 * classes stay package-private; this projection carries the exact visit recorded at drop time, the saved item
 * classifications, the bag's dungeon and dropper and each item's enchant slots. It is an in-memory view of fields every
 * saved drop already has: no history format changes. Null means unknown, never zero or a guess.
 */
public final class LootFacts {
    /**
     * One saved item occurrence; {@code untiered}/{@code setTiered} are UT/ST gear exactly as the saved loot views count them.
     * {@code slots} (unlocked enchant slots, empty ones included) and {@code applied} (applied enchants) are null when the drop
     * did not record them (legacy drops, capture without enchant data; older builds saved -1 for that).
     */
    public record Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion, Integer slots, Integer applied) {
        public Item {
            if (slots != null && slots < 0) slots = null;   // the legacy -1: unknown
            if (applied != null && applied < 0) applied = null;
        }
        /** An item without enchant data (tests and callers that do not need it). */
        public Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion) {
            this(id, untiered, setTiered, highTier, potion, null, null);
        }
        /** True when its enchant slots were recorded; only then is it counted as enchanted or as not enchanted. */
        public boolean enchantKnown() { return slots != null; }
        /** Rare or better: 2 or more recorded enchant slots. False when unknown ({@link #enchantKnown()}): never guessed. */
        public boolean enchanted() { return slots != null && slots >= 2; }
    }
    /**
     * One saved bag; {@code bag} is the bag name the drop recorded ("White", "B.White", "Orange", …; {@code Tokens.bag} colors it)
     * or null when none was saved (legacy drops); {@code visit} is {@code DropContext.visit} (exact, recorded at drop time) or null.
     * {@code dungeon} is the map the bag dropped in, canonical ({@code DungeonStatData.Snapshot.canonicalName}, as the Dungeons
     * cards key it), or null when it was not known ("Unknown area"); {@code dropper} is the enemy's name, or null when unknown.
     */
    public record Bag(String session, long time, boolean white, String bag, VisitRef visit, List<Item> items, String dungeon, String dropper) {
        public Bag { items = List.copyOf(items); }
        /** A bag without its dungeon and dropper (tests and callers that do not need them). */
        public Bag(String session, long time, boolean white, String bag, VisitRef visit, List<Item> items) {
            this(session, time, white, bag, visit, items, null, null);
        }
    }

    private LootFacts() {}

    /**
     * The stat a stat potion raises ("Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom") for its
     * small, greater and soulbound ids ({@link StatPotion}); null for any other id ("Other potions").
     */
    public static String potionStat(int id) {
        StatPotion potion = StatPotion.getPotion(id);
        return potion == null ? null : potion.name();
    }

    /** Off the EDT only. A bag without its item list fails the read instead of counting as empty. */
    public static void read(SessionStore store, String scope, Consumer<Bag> sink) throws IOException {
        read(store, store.catalog(), scope, sink);
    }

    /** As above over a catalog the caller already listed ({@link SessionStore#catalog()}). */
    public static void read(SessionStore store, List<SessionStore.SessionEntry> catalog, String scope, Consumer<Bag> sink) throws IOException {
        try {
            store.read(catalog, scope, "loot", LootDashboard.Drop.class, (session, drop) -> sink.accept(bag(session.id, drop)));
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /** One drop as saved or as the live dashboard holds it (the same class): both projections apply the same rules. */
    static Bag bag(String session, LootDashboard.Drop drop) {
        if (drop.items == null) throw new UncheckedIOException(new IOException("A saved loot bag has no item list; its contents are unknown"));
        List<Item> items = new ArrayList<>(drop.items.size());
        for (LootDashboard.Item item : drop.items) {
            if (item == null) throw new UncheckedIOException(new IOException("A saved loot bag has an unreadable item"));
            // LootQuery's rule for saved variants: no enchant data or the legacy -1 is unknown.
            items.add(new Item(item.id, item.ut, item.st, item.highTier, item.potion, LootQuery.slots(item), LootQuery.applied(item)));
        }
        VisitRef visit = drop.context == null ? null : drop.context.visit;
        boolean exact = visit != null && visit.sessionId != null && !visit.sessionId.isEmpty() && visit.visitId != null && !visit.visitId.isEmpty();
        String name = drop.bag == null || drop.bag.isBlank() ? null : drop.bag;   // not recorded: unknown, never guessed
        return new Bag(session, drop.time, LootArchiveAdapter.white(drop.bag), name, exact ? visit : null, items,
            known(drop.dungeon == null ? null : DungeonStatData.Snapshot.canonicalName(drop.dungeon)), known(drop.dropper));
    }

    /** Capture writes "Unknown" when the map or the dropper was not known; that, an empty name or none saved is null. */
    private static String known(String name) { return name == null || name.isBlank() || "Unknown".equals(name) ? null : name; }
}
