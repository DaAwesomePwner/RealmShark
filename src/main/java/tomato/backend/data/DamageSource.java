package tomato.backend.data;

import assets.IdToAsset;

/**
 * Display-only origin of an outgoing hit. It is recorded on the {@link Projectile} when the shot or
 * server damage report is seen and never feeds damage calculations. Recordings made before source
 * tracking have no stored value and read as {@link #UNKNOWN}.
 */
public enum DamageSource {
    WEAPON("Weapon"),
    ABILITY("Ability"),
    SUMMON("Summon / minion"),
    ITEM_EFFECT("Item effect / proc"),
    OTHER("Other server damage"),
    UNKNOWN("Unknown source");

    public static final String OTHER_DEFINITION = "Other server damage = server-reported hits with no matching shot: AoE, poison, status effects, or a shot packet that was not captured.";
    public static final String UNKNOWN_DEFINITION = "Unknown source = hits recorded before source tracking, or shots whose item could not be identified.";

    public final String label;

    DamageSource(String label) { this.label = label; }

    /** Classifies an item by its projectile slot type; {@code unknownSlot} is used when the slot is not known. */
    static DamageSource forSlot(int slot, DamageSource unknownSlot) {
        switch (slot) {
            case 0: return unknownSlot;
            case 1: case 2: case 3: case 8: case 17: case 24: return WEAPON;
            case 6: case 7: case 9: case 14: return ITEM_EFFECT;
            default: return ABILITY;
        }
    }

    static DamageSource forItem(int itemId, DamageSource unknownSlot) {
        return forSlot(slotOf(itemId), unknownSlot);
    }

    private static int slotOf(int itemId) {
        if (itemId <= 0) return 0;
        try { return IdToAsset.getIdProjectileSlotType(itemId); }
        catch (RuntimeException missingProjectileData) { return 0; }
    }

    public static DamageSource of(Damage hit) {
        DamageSource source = hit == null || hit.projectile == null ? null : hit.projectile.getSource();
        return source == null ? UNKNOWN : source;
    }

    /** Item (or summon object) type behind the hit, or 0 when none was captured. */
    public static int itemOf(Damage hit) {
        if (hit == null || hit.projectile == null) return 0;
        if (hit.projectile.getSourceItem() > 0) return hit.projectile.getSourceItem();
        return Math.max(0, hit.projectile.getContainerType());
    }

    /** Readable item name, or null when the hit has no item. */
    public static String itemName(Damage hit) {
        int id = itemOf(hit);
        if (id <= 0) return null;
        String name = IdToAsset.objectName(id);
        return name == null || name.isEmpty() ? "Item #" + id : name;
    }

    public static String describe(Damage hit) {
        String item = itemName(hit);
        return of(hit).label + (item == null ? "" : " · " + item);
    }
}
