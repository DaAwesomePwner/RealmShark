package tomato.backend;

import assets.IdToAsset;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.StasisPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.security.SecurityGUI;
import tomato.realmshark.enums.CharacterClass;
import util.Util;

public class SecurityAbilityUseCheck {

    private static int decoyCounter = -1;
    private static final int DECOY_ID = 1813;
    private static final int DECOY_FOOLSPRISM_ID = 5136;
    private static final int DECOY_COIN_ID = 28831;
    private static final int DECOY_BRAIN_ID = 45313;
    private static final int DECOY_BRAIN_PUMPKIN_ID = 25736;
    private static final int[] PRISMS = {
        DECOY_ID,
        DECOY_FOOLSPRISM_ID,
        DECOY_COIN_ID,
        DECOY_BRAIN_ID,
        DECOY_BRAIN_PUMPKIN_ID,
    };

    /**
     * Incoming packets updates after observing an entity becoming stasised.
     *
     * @param p    Stasis packet
     * @param data
     */
    public static void stasis(StasisPacket p, TomatoData data) {
        if (p.unknownByteArray == null || p.unknownByteArray.length < 2 || p.unknownByteArray[1] != 22) return;
        float stasisDuration = p.stasisDuration;

        for (Entity player : data.playerListUpdated.values()) {
            if (player.stasisCounter == data.time) continue;
            StatData ability = player.stat.get(StatType.INVENTORY_1_STAT);
            if (ability == null) continue;
            int item = ability.statValue;
            if (StasisOrbs.usingOrb(item, stasisDuration)) {
                player.stasisCounter = 2;
            }
        }
    }

    /**
     * Checks if the player used mana when stasis is detected.
     *
     * @param entity Players that used stasis orbs.
     * @param stats  Stats of the player to check their mana use.
     */
    public static void checkManaFromStasis(Entity entity, StatData[] stats) {
        if (entity.stasisCounter > 0) {
            entity.stasisCounter--;
            StatData previousMana = entity.stat.get(StatType.MP_STAT);
            StatData ability = entity.stat.get(StatType.INVENTORY_1_STAT);
            if (previousMana == null || ability == null) return;
            for (StatData sd : stats) {
                if (sd.statType == StatType.MP_STAT) {
                    if (
                        previousMana.statValue <=
                        sd.statValue
                    ) {
                        publish(entity, ability.statValue, previousMana.statValue, sd.statValue, "Stasis candidate",
                            "Orb duration candidate followed by non-decreasing MP; not proof of a successful cast.");
                    }
                }
            }
        }
    }

    public static void decoy(Entity entity) {
        for (int prism : PRISMS) {
            if (entity.objectType == prism) {
                decoyCounter = 1;
                break;
            }
        }
    }

    public static void checkManaFromDecoyUsed(Entity entity, StatData[] stats) {
        // A first spawn has neither an assigned object type nor previous stats yet.
        // Optional ability inference must never prevent that spawn from being applied.
        if (!CharacterClass.isPlayerCharacter(entity.objectType)
                || !"Trickster".equals(CharacterClass.getName(entity.objectType))) return;
        if (decoyCounter == 0) {
            StatData previousMana = entity.stat.get(StatType.MP_STAT);
            StatData ability = entity.stat.get(StatType.INVENTORY_1_STAT);
            if (previousMana == null || ability == null) return;
            for (StatData sd : stats) {
                if (sd.statType == StatType.MP_STAT) {
                    if (
                        previousMana.statValue <=
                        sd.statValue
                    ) {
                        publish(entity, ability.statValue, previousMana.statValue, sd.statValue, "Decoy candidate",
                            "Decoy timing candidate followed by non-decreasing MP; attribution may be ambiguous.");
                    }
                }
            }
        }
    }

    public static void decreaseDecoyCounter() {
        decoyCounter = Math.max(-1, decoyCounter - 1);
    }
    private static final String APPLICATION_SESSION = java.util.UUID.randomUUID().toString();
    private static void publish(Entity entity, int ability, int before, int incoming, String heuristic, String explanation) {
        tomato.history.SessionStore history = tomato.history.AppHistory.store();
        tomato.ability.AbilityObservationStore.application().add(new tomato.ability.AbilityObservation(
            history == null ? APPLICATION_SESSION : history.currentId(), entity.abilityVisit(), System.currentTimeMillis(),
            entity.id, entity.name(), CharacterClass.getName(entity.objectType), ability, IdToAsset.objectName(ability),
            heuristic, before, incoming, explanation));
    }
    public static void reset() { decoyCounter = -1; }
}
