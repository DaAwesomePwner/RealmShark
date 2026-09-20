package tomato.gui.dps.shared;

import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.gui.modern.DisplayFormat;

/**
 * Centralized helpers for building "extra" damage notes and computing guarded damage stats.
 *
 * This consolidates repeated logic in DPS views for:
 * - Guarded hits (O3 guards)
 * - Dammah counter phase
 * - Garden reflectors
 *
 * Semantics (consistent with existing behavior):
 * - The "extra" tag shows the first applicable context in priority order:
 *   Guarded -> Dammah -> Garden.
 * - "Guarded damage percentage" is computed as (counterDmg / totalDamage) * 100
 *   when any guarded-type condition is active; otherwise 0.
 */
public final class GuardsHandler {

    private GuardsHandler() {
        // no instances
    }

    /**
     * Returns true if any guarded-type condition applies to this damage line:
     * - Oryx 3 guarded damage
     * - Dammah countered phase (entity.dammahCountered + dmg.chancellorDammahDmg)
     * - Walled Garden reflectors
     */
    public static boolean hasGuardedDamage(Entity entity, Damage dmg) {
        if (dmg == null) return false;
        boolean dammah =
            entity != null && entity.dammahCountered && dmg.chancellorDammahDmg;
        return dmg.oryx3GuardDmg || dammah || dmg.walledGardenReflectors;
    }

    /**
     * Builds the "extra" tag string that appears at the end of a DPS row, e.g.:
     * - "[Guarded Hits:12 Dmg:3,456]" (US formatting)
     * - "[Dammah Hits:8 Dmg:2,100]"
     * - "[Reflector Hits:3 Dmg:420]"
     *
     * Human-readable text for the DPS text/icon views, not a persisted or parsed protocol value.
     * Counts follow the FORMAT locale; phase selection and recorded values are unchanged.
     * If no special condition applies, returns an empty string.
     */
    public static String buildExtraTag(Entity entity, Damage dmg) {
        if (dmg == null) return "";
        if (dmg.oryx3GuardDmg) {
            return (
                "[Guarded Hits:" +
                DisplayFormat.formatInteger(dmg.counterHits) +
                " Dmg:" +
                DisplayFormat.formatInteger(dmg.counterDmg) +
                "]"
            );
        } else if (
            entity != null && entity.dammahCountered && dmg.chancellorDammahDmg
        ) {
            return (
                "[Dammah Hits:" +
                DisplayFormat.formatInteger(dmg.counterHits) +
                " Dmg:" +
                DisplayFormat.formatInteger(dmg.counterDmg) +
                "]"
            );
        } else if (dmg.walledGardenReflectors) {
            return (
                "[Reflector Hits:" +
                DisplayFormat.formatInteger(dmg.counterHits) +
                " Dmg:" +
                DisplayFormat.formatInteger(dmg.counterDmg) +
                "]"
            );
        }
        return "";
    }

    /**
     * Returns the guarded damage percentage for this damage line, computed as:
     * (counterDmg / totalDmg) * 100 iff a guarded-type condition applies; otherwise 0.0.
     *
     * Note: total damage is clamped to at least 1 to avoid division by zero.
     */
    public static double guardedDamagePercentage(Entity entity, Damage dmg) {
        if (dmg == null) return 0.0;
        if (!hasGuardedDamage(entity, dmg)) return 0.0;
        int total = Math.max(1, dmg.damage);
        return (dmg.counterDmg * 100.0) / total;
    }

}
