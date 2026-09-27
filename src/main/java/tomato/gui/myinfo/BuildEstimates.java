package tomato.gui.myinfo;

import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.ParseEnchants;

/**
 * The Build page's two estimate KPIs, weapon DPS and mana per second, extracted so Home and Build show the same numbers.
 * Reads only the given entities and the volatile weapon/enchant definition maps, so any thread may estimate from entities
 * it owns or from detached {@link Inputs}.
 */
public final class BuildEstimates {
    /** Pet ability type of Magic Heal. */
    static final int MAGIC_HEAL = 408;

    private BuildEstimates() {}

    /** Units: damage per second and mana per second. Null = unavailable (an input was not captured), never zero. */
    public record Estimates(Double weaponDps, Double mpPerSecond) {}

    public static Estimates of(Entity player, Entity pet, TomatoData.PetAvailability pets, boolean outOfCombat) {
        if (player == null) return new Estimates(null, null);
        return new Estimates(weaponDps(player), mpPerSecond(player, pet, pets, outOfCombat));
    }

    /**
     * Build's inputs detached from capture: stat-only copies of the local player and its owned pet (the copy My Info makes)
     * and the pet metadata state. Taken on the capture thread and never changed afterwards, so any thread may estimate from
     * them; the copies themselves stay private.
     */
    public static final class Inputs {
        private final Entity player, pet;
        private final TomatoData.PetAvailability pets;

        /** The sources' Entity.observationRevision() when copied; -1 without that entity. */
        private final long playerRevision, petRevision;

        private Inputs(Entity player, Entity pet, TomatoData.PetAvailability pets, long playerRevision, long petRevision) {
            this.player = player; this.pet = pet; this.pets = pets; this.playerRevision = playerRevision; this.petRevision = petRevision;
        }

        /** Capture thread: copies the stats now; later packets change the live entities, never these copies. */
        public static Inputs detach(Entity player, Entity pet, TomatoData.PetAvailability pets) {
            return new Inputs(copyStats(player), copyStats(pet), pets == null ? TomatoData.PetAvailability.UNKNOWN : pets,
                player == null ? -1 : player.observationRevision(), pet == null ? -1 : pet.observationRevision());
        }

        /** Copied from the same player and pet observations with the same pet state; revision 0 (no observation yet) never matches. */
        public boolean sameSource(Inputs other) {
            return other != null && pets == other.pets && (player == null) == (other.player == null) && (pet == null) == (other.pet == null)
                && playerRevision != 0 && petRevision != 0 && playerRevision == other.playerRevision && petRevision == other.petRevision;
        }

        /** Build's default (in-combat) scenario over the copies. Costs a weapon lookup and an enchant decode: memoize per snapshot. */
        public Estimates estimate() { return of(player, pet, pets, false); }
    }

    /** Copies only the build statistics on the producer; combat histories are not needed by Build or Home. */
    static Entity copyStats(Entity source) {
        if (source == null) return null;
        Entity copy = new Entity(null, source.id, 0); copy.objectType = source.objectType;
        for (StatType type : StatType.values()) {
            StatData value = source.stat.get(type);
            if (value == null) continue;
            StatData stat = new StatData();
            stat.statType = value.statType; stat.statTypeNum = value.statTypeNum;
            stat.statValue = value.statValue; stat.statValueTwo = value.statValueTwo;
            stat.stringStatValue = value.stringStatValue; copy.stat.set(type, stat);
        }
        return copy;
    }

    /** The definition of the weapon in the captured weapon slot, or null when uncaptured, empty or unknown. */
    static Weapon weapon(Entity player) {
        Double id = stat(player, StatType.INVENTORY_0_STAT);
        return id != null && id >= 0 ? Equip.get(id.intValue()) : null;
    }

    /** One projectile group against 0 defense, all projectiles hitting continuously. */
    static double projectileDps(Bullet bullet, double atk, double dex, double exalt) {
        return (bullet.min + bullet.max) / 2d * (exalt / 1000d) * (0.5 + atk / 50d)
            * bullet.numProj * (1.5 + 6.5 * dex / 75d) * bullet.rof;
    }

    static Double weaponDps(Entity player) {
        Weapon weapon = weapon(player);
        Double atk = stat(player, StatType.ATTACK_STAT), dex = stat(player, StatType.DEXTERITY_STAT),
            exalt = stat(player, StatType.EXALTATION_BONUS_DAMAGE);
        if (weapon == null || weapon.bullets.isEmpty() || atk == null || dex == null || exalt == null) return null;
        double total = 0;
        for (Bullet bullet : weapon.bullets) total += projectileDps(bullet, atk, dex, exalt);
        return total;
    }

    static Double wisdomMana(Double wisdom) { return wisdom == null ? null : wisdom * .12; }

    /** Supported enchant mana effects; null unless all four equipped slots decoded and maximum mana is known. */
    static Double enchantMana(String[] completeCodes, Double maxMp, boolean outOfCombat) {
        return completeCodes == null || maxMp == null ? null
            : (double) ParseEnchants.getManaRegenPerSecondFromEnchants(completeCodes, maxMp.intValue(), outOfCombat);
    }

    /** Magic Heal mana per second at a pet ability level; 0 when the pet has no active Magic Heal. */
    static double petMana(int level) {
        return level < 1 ? 0 : MyInfoGUI.petManaPerLevel[level - 1] / (double) MyInfoGUI.petRegenTimeMpHp[level - 1];
    }

    /** Null while any part is unknown; pet metadata must be known (ABSENT contributes 0). */
    static Double manaTotal(Double wisdom, Double enchant, double petMana, TomatoData.PetAvailability pets) {
        return wisdom == null || enchant == null || pets == TomatoData.PetAvailability.UNKNOWN ? null : wisdom + enchant + petMana;
    }

    static Double mpPerSecond(Entity player, Entity pet, TomatoData.PetAvailability pets, boolean outOfCombat) {
        Double wisdom = wisdomMana(stat(player, StatType.WISDOM_STAT));
        Double enchant = enchantMana(ParseEnchants.equippedCapture(player).completeCodes(), stat(player, StatType.MAX_MP_STAT), outOfCombat);
        return manaTotal(wisdom, enchant, petMana(petStat(pet, MAGIC_HEAL)), pets);
    }

    /** The level (1–100) of the pet's ability of this type, or -1. */
    static int petStat(Entity pet, int type) {
        StatType[] types = {StatType.PET_FIRST_ABILITY_TYPE_STAT, StatType.PET_SECOND_ABILITY_TYPE_STAT, StatType.PET_THIRD_ABILITY_TYPE_STAT};
        StatType[] powers = {StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT};
        for (int i = 0; i < types.length; i++) {
            Double ability = stat(pet, types[i]), level = stat(pet, powers[i]);
            if (ability != null && ability == type && level != null && level >= 1 && level <= 100) return level.intValue();
        }
        return -1;
    }

    private static Double stat(Entity entity, StatType type) {
        StatData value = entity == null ? null : entity.stat.get(type);
        return value == null ? null : (double) value.statValue;
    }
}
