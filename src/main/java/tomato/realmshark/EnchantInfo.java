package tomato.realmshark;

import java.util.ArrayList;
import java.util.List;

/**
 * One item's enchantments as every item surface shows them, decoded from its UNIQUE_DATA_STRING entry with the strict decoder
 * ({@link ParseEnchants#evidence}). The rarity is the unlocked slot count (0 Unenchanted … 4 Divine); {@code slots} are the
 * unlocked slots in order, locked ones omitted. Not-recorded and unreadable data never claim a rarity. Immutable.
 */
public record EnchantInfo(State state, Rarity rarity, List<Slot> slots) {
    public enum State { RECORDED, NOT_RECORDED, UNREADABLE }

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

    public EnchantInfo { slots = List.copyOf(slots); }

    public static EnchantInfo notRecorded() { return NOT_RECORDED; }
    public static EnchantInfo unreadable() { return UNREADABLE; }

    /** One item's blob: null is not recorded, "" is the protocol's known-unenchanted shorthand. */
    public static EnchantInfo of(String blob) { return fromEvidence(ParseEnchants.evidence(blob)); }

    /**
     * A blob retained through {@link ParseEnchants#getEnchantStrings}, which turns a missing stat into "": there "" cannot be told
     * from unenchanted, so it reads as not recorded.
     */
    public static EnchantInfo ofRetained(String blob) { return blob == null || blob.isEmpty() ? NOT_RECORDED : of(blob); }

    public static EnchantInfo fromEvidence(ParseEnchants.Evidence evidence) {
        if (evidence == null) return NOT_RECORDED;
        switch (evidence.state) {
            case RECORDED: case RECORDED_EMPTY: break;
            case INVALID: return UNREADABLE;
            default: return NOT_RECORDED;
        }
        List<Slot> unlocked = new ArrayList<>();
        for (int id : evidence.orderedSlotIds) {
            if (id == -3) break;
            if (id >= -1) unlocked.add(new Slot(id));
        }
        return new EnchantInfo(State.RECORDED, Rarity.ofSlots(unlocked.size()), unlocked);
    }

    /** True when there is a rarity to show: recorded, with at least one unlocked slot. */
    public boolean enchanted() { return state == State.RECORDED && rarity != Rarity.UNENCHANTED && rarity != Rarity.UNKNOWN; }

    /** "Rare · 2 enchant slots", "Unenchanted", "Enchants not recorded" or "Enchant data unreadable". */
    public String summary() {
        switch (state) {
            case NOT_RECORDED: return "Enchants not recorded";
            case UNREADABLE: return "Enchant data unreadable";
            default: return rarity == Rarity.UNENCHANTED ? "Unenchanted"
                : rarity.label + " · " + slots.size() + (slots.size() == 1 ? " enchant slot" : " enchant slots");
        }
    }

    /** Each unlocked slot in order: "Attack Bonus I — Increases Attack by 1.4", the name alone without a description, or "(empty slot)". */
    public List<String> slotLines() {
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
