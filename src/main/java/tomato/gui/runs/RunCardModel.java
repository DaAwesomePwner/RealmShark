package tomato.gui.runs;

import java.util.*;
import tomato.gui.kit.ItemTiers;
import tomato.gui.kit.Portals;
import tomato.gui.stats.LootFacts;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;

/**
 * One saved dungeon run as the run feed shows it (spec §6.3 Feed). Immutable; built off the EDT by {@link RunFeedSource}.
 * Every fact comes from the run's own saved visit or from records linked to exactly its {@link #ref} (session and visit ID):
 * loot bags by their drop-time visit, fame readings by their visit tag, combat recordings by their entry context. Nothing is
 * matched by dungeon name or time. Unknown is null (never 0): {@code durationMs} without an observed span, {@code partySize}
 * without an observed party, {@code fameGained} when the gain cannot be shown ({@link FameGains}) or the session's fame could
 * not be read, {@code combat} without a linked recording or when the session's combat records could not be read
 * ({@code combatReason} says which), and {@code exaltProgress} unless the visit recorded an increase.
 *
 * <p>Loot is known when the run's session's loot was read and holds at least one saved bag (of any run, or of none): a run
 * without a bag recorded inside it then shows a known none ({@code lootReason} null, {@code lootCount} 0). A session that
 * saved no loot bag at all cannot show a known none, so its runs' loot is unknown ({@link #LOOT_NOT_SAVED}, the recap's
 * wording), and so is a session whose loot could not be read ({@link #LOOT_UNREADABLE}).
 *
 * @param map        the saved visit's area name (the filter and portal key); {@code mapName} is its display text
 * @param portalId   the portal sprite ({@link Portals#spriteId}), 0 for the kit's placeholder
 * @param entered    the visit's entry time (epoch ms)
 * @param durationMs the observed span, entry to the last saved observation (not a verified clear time); null when unknown
 * @param partySize  the observed RotMG party ({@code Visit.rosterSize}); null when no party was observed
 * @param combatReason null when {@code combat} has your verified row; else why your facts are missing, in words
 * @param loot       at most {@link #LOOT_ICONS} items of this run's bags, most notable first (Home's order)
 * @param lootCount  every item of this run's bags
 * @param lootSummary "1 UT · 2 potions" over every item; "" when this run has no saved loot or its loot is unknown
 * @param lootReason {@link #LOOT_NOT_SAVED} or {@link #LOOT_UNREADABLE} when the run's loot is unknown (no loot is then
 *                   shown: the loot, count and summary are emptied); null whenever loot is known, a known none included
 * @param exaltProgress the progress increase observed inside this visit; null unless positive
 */
public record RunCardModel(VisitRef ref, String map, String mapName, int portalId, RunOutcome outcome, long entered, Long durationMs,
                           Integer partySize, Combat combat, String combatReason, List<LootItem> loot, int lootCount, String lootSummary,
                           String lootReason, Long fameGained, Integer exaltProgress) {
    /** Loot sprites on a card, as on Home's Recent runs. */
    public static final int LOOT_ICONS = 8;
    public static final String NO_RECORDING = "No combat recording is linked to this run.";
    public static final String COMBAT_UNREADABLE = "Combat records for this session could not be read.";
    public static final String LOOT_UNREADABLE = "Loot for this session could not be read.";
    /** The run recap's wording ({@code RunRecapBuilder}) for a session without any saved loot bag. */
    public static final String LOOT_NOT_SAVED = "No loot bag was saved in this run's session, so its loot is unknown.";
    /** {@code RecordedEncounter.unavailableReason()}'s wording for a recording without a verified local row. */
    public static final String UNVERIFIED_LOCAL = "The local player's row was not verified for this encounter; another player's row is never substituted.";
    /** Map text when the saved visit has none. */
    static final String UNKNOWN_AREA = "Unknown area";

    public RunCardModel {
        Objects.requireNonNull(ref, "ref"); Objects.requireNonNull(outcome, "outcome");
        loot = loot == null || lootReason != null ? List.of() : List.copyOf(loot);
        if (lootReason != null) lootCount = 0;
        lootSummary = lootSummary == null || lootReason != null ? "" : lootSummary;
    }

    /**
     * The run's combat line from the recording that represents it ({@link CombatFacts#longest}). Your facts come only from
     * that recording's verified local row: without one, {@code localDamage}, {@code localDps}, {@code rank}, {@code share} and
     * {@code localDeaths} are null and {@code localUnavailable} says why; another row is never substituted. A verified local
     * player without a row recorded no damage: damage, DPS and share are then real zeros, and rank and deaths unknown.
     *
     * @param recordings  recordings linked to this run (the recap offers a picker when more than one)
     * @param rank        your 1-based place among {@code contributors} ("#2 of 6")
     * @param share       your share of the recording's damage in percent, unattributed hits included
     * @param localDeaths death notifications naming you in that recording; null when your name is unknown or shared there
     */
    public record Combat(String recordingId, int recordings, Long localDamage, Double localDps, Integer rank, int contributors,
                         Double share, Integer localDeaths, String localUnavailable) {}

    /**
     * One loot sprite: the item, its bag's name ({@code Tokens.bag}; null when not saved) and its tier label: the saved UT/ST
     * classification, else the definitions' label when the card was read; "" while unknown (a renderer may then ask
     * {@code ItemTiers.label} at paint time, for definitions that load later).
     * {@code enchant} drives the loot well's rarity pips and glow (not recorded = tier text when space permits).
     */
    public record LootItem(int id, String bag, String tier, EnchantInfo enchant) {
        public LootItem {
            if (enchant == null) enchant = EnchantInfo.notRecorded();
        }
        public LootItem(int id, String bag, String tier) { this(id, bag, tier, null); }
    }

    /**
     * A card for the saved visit {@code ref}. {@code records} may hold anything of the run's session and {@code bags} is every
     * bag the session saved: only those linked to exactly {@code ref} are shown. Null means that session's combat records or
     * loot could not be read ({@link #COMBAT_UNREADABLE}, {@link #LOOT_UNREADABLE}); no bag at all means the session saved no
     * loot ({@link #LOOT_NOT_SAVED}). {@code fameGained} is the known gain ({@link FameGains}) or null.
     */
    static RunCardModel of(VisitRef ref, String map, RunOutcome outcome, long entered, Long durationMs, Integer rosterSize,
                           long exaltIncrease, Collection<CombatRecord> records, List<LootFacts.Bag> bags, Long fameGained) {
        List<CombatRecord> linked = new ArrayList<>();
        if (records != null) for (CombatRecord record : records) if (record != null && ref.equals(record.visit())) linked.add(record);
        Combat combat = records == null ? null : combat(linked);
        List<LootFacts.Bag> own = new ArrayList<>();
        if (bags != null) for (LootFacts.Bag bag : bags) if (bag != null && ref.equals(bag.visit())) own.add(bag);
        int count = 0;
        for (LootFacts.Bag bag : own) count += bag.items().size();
        String name = map == null || map.isBlank() ? UNKNOWN_AREA : map;
        return new RunCardModel(ref, map, name, Portals.spriteId(map), outcome, entered, durationMs, rosterSize, combat,
            records == null ? COMBAT_UNREADABLE : reason(combat), loot(own), count, summary(own),
            bags == null ? LOOT_UNREADABLE : bags.isEmpty() ? LOOT_NOT_SAVED : null,
            fameGained, exaltIncrease > 0 ? (int) Math.min(exaltIncrease, Integer.MAX_VALUE) : null);
    }

    /** The combat line of one run's linked recordings, or null when none is linked. */
    static Combat combat(List<CombatRecord> linked) {
        CombatRecord shown = CombatFacts.longest(linked);
        if (shown == null) return null;
        if (shown.localObjectId == null)
            return new Combat(shown.recordingId, linked.size(), null, null, null, shown.contributors, null, null, UNVERIFIED_LOCAL);
        CombatRecord.PlayerLine local = shown.local();
        Long damage = shown.localDamage();
        // Without a row the verified local player dealt no recorded damage: 0 over a known window, 0 % of a known total.
        Double dps = local != null ? shown.dps(local) : shown.dps(new CombatRecord.PlayerLine());
        Double share = local != null ? shown.share(local) : shown.share(new CombatRecord.PlayerLine());
        Integer rank = local == null || local.rank <= 0 ? null : local.rank;
        return new Combat(shown.recordingId, linked.size(), damage, dps, rank, shown.contributors, share, local == null ? null : local.deaths, null);
    }

    /** Why the card shows no "your" facts: no linked recording, or no verified local row in it; null when they are shown. */
    static String reason(Combat combat) { return combat == null ? NO_RECORDING : combat.localUnavailable(); }

    /** Home's Recent runs order (HomeArchive.notability): UT and ST, then high tier, then potions, then the rest; drop order inside. */
    static int notability(LootFacts.Item item) { return item.untiered() || item.setTiered() ? 0 : item.highTier() ? 1 : item.potion() ? 2 : 3; }

    private static List<LootItem> loot(List<LootFacts.Bag> bags) {
        List<LootItem> items = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        for (LootFacts.Bag bag : bags) for (LootFacts.Item item : bag.items()) {
            // The saved UT/ST classification is drop-time evidence; other tiers come from the loaded definitions ("" while unknown).
            String tier = item.untiered() ? "UT" : item.setTiered() ? "ST" : ItemTiers.label(item.id());
            items.add(new LootItem(item.id(), bag.bag(), tier, item.enchant()));
            ranks.add(notability(item));
        }
        Integer[] order = new Integer[items.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(ranks::get));   // stable: drop order within a rank
        List<LootItem> shown = new ArrayList<>();
        for (int i = 0; i < order.length && shown.size() < LOOT_ICONS; i++) shown.add(items.get(order[i]));
        return shown;
    }

    /**
     * "1 UT · 1 ST · 2 potions" over every item (Home's wording); "3 items" when none is notable; "" without items. The card and
     * the run recap both use it, so a run's loot reads the same in both.
     */
    static String summary(List<LootFacts.Bag> bags) {
        List<LootFacts.Item> items = new ArrayList<>();
        for (LootFacts.Bag bag : bags) items.addAll(bag.items());
        String kinds = tomato.gui.loot.haul.LootLine.kinds(items);
        if (!kinds.isEmpty() || items.isEmpty()) return kinds;
        return items.size() + (items.size() == 1 ? " item" : " items");
    }
}
