package tomato.gui.kit;

import java.util.regex.Pattern;
import tomato.backend.data.RosterDefinitions;

/** Short tier labels for items: "UT", "ST" or "T12", from the loaded item definitions. */
public final class ItemTiers {
    private static final Pattern TIER_LABEL = Pattern.compile("T\\d{1,2}");

    private ItemTiers() {}

    /** The label for an item id; "" for an empty or unknown slot, or while the definitions load. Constant time on the EDT. */
    public static String label(int itemId) { return itemId > 0 ? label(RosterDefinitions.current().item(itemId)) : ""; }

    /** UT, then ST, then an explicit "T<n>" label, then the numeric tier; "" when the definition has none. */
    public static String label(RosterDefinitions.Item item) {
        if (item == null) return "";
        if (item.labels != null) {
            if (item.labels.contains("UT")) return "UT";
            if (item.labels.contains("ST")) return "ST";
            for (String label : item.labels) if (TIER_LABEL.matcher(label).matches()) return label;
        }
        return item.tier == null ? "" : "T" + item.tier;
    }
}
