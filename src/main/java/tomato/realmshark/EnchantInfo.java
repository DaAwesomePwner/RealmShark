package tomato.realmshark;

import java.util.ArrayList;
import java.util.List;

/**
 * One item's enchantments as every item surface shows them, decoded from its UNIQUE_DATA_STRING entry with the strict decoder
 * ({@link ParseEnchants#evidence}). The rarity is the unlocked slot count (0 Unenchanted … 4 Divine); {@code slots} are the
 * unlocked slots in order, locked ones omitted. Not-recorded and unreadable data never claim a rarity. Immutable.
 */
public record EnchantInfo(State state, Rarity rarity, List<Slot> slots) {
    /** COUNT_ONLY: the unlocked slot count (so the rarity) is known but not which enchantments were rolled. */
    public enum State { RECORDED, COUNT_ONLY, NOT_RECORDED, UNREADABLE }

    public enum Rarity {
        UNENCHANTED("Unenchanted"), UNCOMMON("Uncommon"), RARE("Rare"), LEGENDARY("Legendary"), DIVINE("Divine"), UNKNOWN("Unknown");
        public final String label;
        Rarity(String label) { this.label = label; }
        static Rarity ofSlots(int unlocked) { return unlocked >= 0 && unlocked <= 4 ? values()[unlocked] : UNKNOWN; }
    }

    /** One unlocked slot: an applied enchantment's type, or empty (-1). */
    public record Slot(int typeId) {
        public boolean empty() { return typeId < 0; }
    }

    private static final EnchantInfo NOT_RECORDED = new EnchantInfo(State.NOT_RECORDED, Rarity.UNKNOWN, List.of());
    private static final EnchantInfo UNREADABLE = new EnchantInfo(State.UNREADABLE, Rarity.UNKNOWN, List.of());

    /** The one line a rarity-only record shows in place of its slots. */
    public static final String NAMES_NOT_AVAILABLE = "Enchant names not available";

    /**
     * A record that saved only its unlocked slot count (older loot history, or a merged row of several drops): the rarity without
     * enchant names. Null or negative is not recorded; more than four is unreadable.
     */
    public static EnchantInfo ofSlotCount(Integer unlocked) {
        if (unlocked == null || unlocked < 0) return NOT_RECORDED;
        if (unlocked > 4) return UNREADABLE;
        return new EnchantInfo(State.COUNT_ONLY, Rarity.ofSlots(unlocked), List.of());
    }

    public EnchantInfo { slots = List.copyOf(slots); }

    public static EnchantInfo notRecorded() { return NOT_RECORDED; }
    public static EnchantInfo unreadable() { return UNREADABLE; }

    /** One item's blob: null is not recorded, "" is the protocol's known-unenchanted shorthand. */
    public static EnchantInfo of(String blob) { return fromEvidence(ParseEnchants.evidence(blob)); }

    /**
     * A blob a DPS hit retained. Hits saved before enchant rarity stored a missing stat as "", so "" (like null) reads as not
     * recorded; newer hits store a known-unenchanted slot as {@link ParseEnchants#UNENCHANTED_ENTRY}.
     */
    public static EnchantInfo ofRetained(String blob) { return blob == null || blob.isEmpty() ? NOT_RECORDED : of(blob); }

    public static EnchantInfo fromEvidence(ParseEnchants.Evidence evidence) {
        // Saved evidence is Gson-read without constructors, so a partial or hand-edited record may lack any field.
        if (evidence == null || evidence.state == null) return NOT_RECORDED;
        switch (evidence.state) {
            case RECORDED: case RECORDED_EMPTY: break;
            case INVALID: return UNREADABLE;
            default: return NOT_RECORDED;
        }
        // Gson defaults a missing primitive count to 0; real captures always carry the ordered ids.
        if (evidence.orderedSlotIds == null) return evidence.slots > 0 ? ofSlotCount(evidence.slots) : NOT_RECORDED;
        List<Slot> unlocked = new ArrayList<>();
        for (Integer id : evidence.orderedSlotIds) {
            if (id == null) return UNREADABLE;
            if (id == -3) break;
            if (id >= -1) unlocked.add(new Slot(id));
        }
        return new EnchantInfo(State.RECORDED, Rarity.ofSlots(unlocked.size()), unlocked);
    }

    /** True when there is a rarity to show: recorded or counted, with at least one unlocked slot. */
    public boolean enchanted() {
        return (state == State.RECORDED || state == State.COUNT_ONLY) && rarity != Rarity.UNENCHANTED && rarity != Rarity.UNKNOWN;
    }

    /** "Rare · 2 enchant slots", "Unenchanted", "Enchants not recorded" or "Enchant data unreadable". */
    public String summary() {
        switch (state) {
            case NOT_RECORDED: return "Enchants not recorded";
            case UNREADABLE: return "Enchant data unreadable";
            default: {
                // No slot count maps to UNKNOWN, so a record built with it has no honest count to show.
                if (rarity == Rarity.UNKNOWN) return "Enchant data unreadable";
                // Rarity constants are declared in slot-count order, so a count-only record's count is its rarity's ordinal.
                int count = state == State.COUNT_ONLY ? rarity.ordinal() : slots.size();
                return rarity == Rarity.UNENCHANTED ? "Unenchanted"
                    : rarity.label + " · " + count + (count == 1 ? " enchant slot" : " enchant slots");
            }
        }
    }

    /** Each unlocked slot in order: "Attack Bonus I — Increases Attack by 1.4", the name alone without a description, or "(empty slot)". */
    public List<String> slotLines() {
        if (state == State.COUNT_ONLY) return rarity == Rarity.UNENCHANTED ? List.of() : List.of(NAMES_NOT_AVAILABLE);
        List<String> lines = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.empty()) { lines.add("(empty slot)"); continue; }
            ParseEnchants.Definition definition = ParseEnchants.definition(slot.typeId());
            lines.add(definition.description().isEmpty() ? definition.displayName() : definition.displayName() + " — " + definition.description());
        }
        return lines;
    }

    /** Plain text for dialogs and accessible descriptions: the summary, then one indented line per unlocked slot. */
    public String text() {
        StringBuilder text = new StringBuilder(summary());
        for (String line : slotLines()) text.append("\n  ").append(line);
        return text.toString();
    }
}
