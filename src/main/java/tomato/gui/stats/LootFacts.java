package tomato.gui.stats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tomato.backend.data.DungeonStatData;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;
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
     * {@code enchant} is the item's enchantments for display: exact when captured, rarity only for older records, not recorded when unknown.
     * {@code tier} is the tier label saved with the drop ("UT", "ST", "T12"), or null when none was saved; it outlives asset changes
     * that drop the item from the current definitions.
     */
    public record Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion, Integer slots, Integer applied,
                       EnchantInfo enchant, String tier) {
        public Item {
            if (tier != null && (tier.isBlank() || "—".equals(tier))) tier = null;   // capture's "—": no tier saved
            if (slots != null && slots < 0) slots = null;   // the legacy -1: unknown
            if (applied != null && applied < 0) applied = null;
            // No enchantments given: the rarity the slot count implies (not recorded when it is unknown).
            if (enchant == null) enchant = EnchantInfo.ofSlotCount(slots);
        }

        public Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion, Integer slots, Integer applied,
                    EnchantInfo enchant) {
            this(id, untiered, setTiered, highTier, potion, slots, applied, enchant, null);
        }
        public Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion, Integer slots, Integer applied) {
            this(id, untiered, setTiered, highTier, potion, slots, applied, null);
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
     * cards key it), or null when it was not known or the catalog did not know it ({@link #area}; "Unknown area"); {@code dropper} is
     * the enemy's name, or null when unknown.
     */
    public record Bag(String session, long time, boolean white, String bag, VisitRef visit, List<Item> items, String dungeon, String dropper) {
        public Bag { items = List.copyOf(items); }
        /** A bag without its dungeon and dropper (tests and callers that do not need them). */
        public Bag(String session, long time, boolean white, String bag, VisitRef visit, List<Item> items) {
            this(session, time, white, bag, visit, items, null, null);
        }
    }

    /** How an area without a known name reads, everywhere loot is shown ({@link #areaLabel}). */
    public static final String UNKNOWN_AREA = "Unknown area";
    /**
     * What capture saves for an area the dungeon catalog does not know ({@code DungeonCatalog.UNRECOGNIZED}, package-private in
     * {@code tomato.realmshark}; {@code ParseDungeon.canonicalName} returns it for any unknown name), and capture's word for none.
     */
    public static final String UNRECOGNIZED = "Unrecognized area", UNKNOWN = "Unknown";

    private LootFacts() {}

    /** Whether a saved bag name is white (including boosted white); unknown names are never inferred. */
    public static boolean whiteBag(String name) { return LootArchiveAdapter.white(name); }

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

    /** Incremental journal projection using exactly the same bag construction as a full saved-history read. */
    public static SessionStore.JournalCursor readJournalFrom(SessionStore store, SessionStore.Session session,
            SessionStore.JournalCursor cursor, Consumer<Bag> sink, tomato.history.archive.Cancellation cancel) throws IOException {
        try {
            return store.readJournalFrom(session, "loot", cursor, LootDashboard.Drop.class,
                drop -> sink.accept(bag(session.id, drop)), cancel);
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
    }

    /** One drop as saved or as the live dashboard holds it (the same class): both projections apply the same rules. */
    static Bag bag(String session, LootDashboard.Drop drop) {
        if (drop.items == null) throw new UncheckedIOException(new IOException("A saved loot bag has no item list; its contents are unknown"));
        List<Item> items = new ArrayList<>(drop.items.size());
        for (LootDashboard.Item item : drop.items) {
            if (item == null) throw new UncheckedIOException(new IOException("A saved loot bag has an unreadable item"));
            // LootQuery's rule for saved variants: no enchant data or the legacy -1 is unknown.
            items.add(new Item(item.id, item.ut, item.st, item.highTier, item.potion, LootQuery.slots(item), LootQuery.applied(item), item.enchantInfo(),
                item.tier));
        }
        VisitRef visit = drop.context == null ? null : drop.context.visit;
        boolean exact = visit != null && visit.sessionId != null && !visit.sessionId.isEmpty() && visit.visitId != null && !visit.visitId.isEmpty();
        String name = drop.bag == null || drop.bag.isBlank() ? null : drop.bag;   // not recorded: unknown, never guessed
        return new Bag(session, drop.time, LootArchiveAdapter.white(drop.bag), name, exact ? visit : null, items,
            area(drop.dungeon == null ? null : DungeonStatData.Snapshot.canonicalName(drop.dungeon)), known(drop.dropper));
    }

    /** Capture writes "Unknown" when the dropper was not known; that, an empty name or none saved is null. */
    private static String known(String name) { return name == null || name.isBlank() || UNKNOWN.equals(name) ? null : name; }

    /**
     * The area a bag dropped in, or null when it is not known: none saved, blank, capture's "Unknown" (no map when the bag dropped)
     * or the catalog's {@value #UNRECOGNIZED} (a name it does not know). Highlights groups those under {@value #UNKNOWN_AREA}.
     */
    public static String area(String name) { return known(name) == null || UNRECOGNIZED.equals(name) ? null : name; }

    /**
     * The area's name for display: {@value #UNKNOWN_AREA} for every unknown form ({@link #area}), else the name. Display only:
     * saved rows, facet keys ("facet.dungeon.Unknown"), saved view states and drill keys keep the saved name.
     */
    public static String areaLabel(String name) { String area = area(name); return area == null ? UNKNOWN_AREA : area; }
}
