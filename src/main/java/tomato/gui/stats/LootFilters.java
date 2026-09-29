package tomato.gui.stats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import tomato.realmshark.enums.LootBags;
import util.PropertiesManager;

/**
 * Filter Loot (P6a): which bag colors show, one model for the Edit › Filter Loot menu, a Settings section and Loot views. The
 * preference keys are the menu's ({@code filterWhiteBag} … {@code filterBrownBag}); an absent key means shown and only the literal
 * "true" shows a kind once set, as the menu read them. Boosted bags count as their color; other or unknown bags (Soulbound, an
 * unrecognized id or name) are always shown. Filters change what is listed, never what is counted.
 */
public final class LootFilters {
    /** The ten filterable bag colors, in the menu's order. */
    public enum Kind {
        WHITE("filterWhiteBag", "White bags", LootBags.WHITE, LootBags.BOOSTED_WHITE),
        ORANGE("filterOrangeBag", "Orange bags", LootBags.ORANGE, LootBags.BOOSTED_ORANGE),
        RED("filterRedBag", "Red bags", LootBags.RED, LootBags.BOOSTED_RED),
        GOLD("filterGoldBag", "Gold bags", LootBags.GOLD, LootBags.BOOSTED_GOLD),
        EGG("filterEggBag", "Egg bags", LootBags.EGG, LootBags.BOOSTED_EGG),
        BLUE("filterBlueBag", "Blue bags", LootBags.BLUE, LootBags.BOOSTED_BLUE),
        TEAL("filterTealBag", "Teal bags", LootBags.TEAL, LootBags.BOOSTED_TEAL),
        PURPLE("filterPurpleBag", "Purple bags", LootBags.PURPLE, LootBags.BOOSTED_PURPLE),
        PINK("filterPinkBag", "Pink bags", LootBags.PINK, LootBags.BOOSTED_PINK),
        BROWN("filterBrownBag", "Brown bags", LootBags.BROWN, LootBags.BOOSTED_BROWN);

        private final String key, label;
        private final LootBags plain, boosted;

        Kind(String key, String label, LootBags plain, LootBags boosted) {
            this.key = key; this.label = label; this.plain = plain; this.boosted = boosted;
        }
        /** The preference key the Filter Loot menu has always written. */
        public String key() { return key; }
        /** A short label for settings and chips ("White bags"). */
        public String label() { return label; }
    }

    private static final LootFilters INSTANCE = new LootFilters();
    private static final Map<Integer, Kind> BY_ID = new HashMap<>();
    private static final Map<String, Kind> BY_NAME = new HashMap<>();
    static {
        for (Kind kind : Kind.values()) {
            for (LootBags bag : new LootBags[]{kind.plain, kind.boosted}) {
                BY_ID.put(bag.getId(), kind);
                // The persisted bag name is LootBags' ("White", "B.White", "Egg Basket", "B.Egg", …).
                BY_NAME.put(LootBags.lootBagName(bag.getId()), kind);
            }
        }
    }

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private LootFilters() { }

    public static LootFilters get() { return INSTANCE; }

    /** Any thread. Absent = shown; once set, only "true" shows (the menu's reading). */
    public boolean shows(Kind kind) {
        String value = PropertiesManager.getProperty(kind.key);
        return value == null || value.equals("true");
    }

    /** Writes the preference (memory now, disk asynchronously) and, when visibility changed, notifies listeners on the EDT. */
    public void set(Kind kind, boolean shown) {
        boolean changed = shows(kind) != shown;
        PropertiesManager.setProperties(kind.key, Boolean.toString(shown));
        if (changed) changed();
    }

    /** The color of a bag object type, boosted variants included; null for other or unknown bags. */
    public static Kind of(int objectType) { return BY_ID.get(objectType); }

    /** The color of a persisted bag name ("B." prefix = boosted); null for other, unknown or missing names. */
    public static Kind ofBagName(String bag) { return bag == null ? null : BY_NAME.get(bag); }

    /** Any thread. Other or unknown bags are always shown. */
    public boolean showsBag(int objectType) { Kind kind = of(objectType); return kind == null || shows(kind); }

    /** Any thread. Other, unknown or unnamed bags are always shown. */
    public boolean showsBagName(String bag) { Kind kind = ofBagName(bag); return kind == null || shows(kind); }

    /** Listeners run on the EDT after a visibility change (at once when the change is made on the EDT). */
    public void addListener(Runnable listener) { listeners.add(listener); }

    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private void changed() {
        Runnable notify = () -> { for (Runnable listener : listeners) listener.run(); };
        if (SwingUtilities.isEventDispatchThread()) notify.run(); else SwingUtilities.invokeLater(notify);
    }
}
