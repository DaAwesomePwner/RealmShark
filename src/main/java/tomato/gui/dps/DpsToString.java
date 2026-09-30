package tomato.gui.dps;

import assets.IdToAsset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Damage;
import tomato.backend.data.DpsData.LocalPlayerContext;
import tomato.backend.data.Entity;
import tomato.backend.data.Equipment;
import tomato.gui.dps.shared.DpsTextFormat;
import tomato.gui.dps.shared.EquipmentUsageAggregator;
import tomato.gui.dps.shared.GuardsHandler;
import tomato.gui.modern.DisplayFormat;
import tomato.realmshark.enums.CharacterClass;

/**
 * Builds the string-based DPS view used in the "logs" tab.
 *
 * Behavior preserved:
 * - Header and per-entity sections
 * - Player filter/highlight and "me" indicator
 * - Extra notes (guarded/dammah/garden) + death/nexus info
 * - Equipment:
 *   - option 0: hidden
 *   - option 1: bracketed "[slot0 / slot1 / slot2 / slot3]" showing most-used item per slot
 *   - option 2: per-slot line with slash-separated items in that slot, sorted by descending %
 *     - Single-item slot shows "100%"
 *     - Tiny non-zero percentages show "< 0.1%"
 */
public class DpsToString {

    /** Legacy entry: outcomes from the notices only (no presence timeline). */
    public static String stringDmgRealtime(
        MapInfoPacket map,
        List<Entity> sortedEntityHitList,
        ArrayList<NotificationPacket> notifications,
        LocalPlayerContext player,
        long totalDungeonPcTime
    ) {
        return stringDmgRealtime(map, sortedEntityHitList, notifications, player, totalDungeonPcTime,
            EncounterOutcomes.of(null, -1, false, EncounterOutcomes.playersOf(sortedEntityHitList), notifications));
    }

    /**
     * Real time string display.
     *
     * @return logged dps output as a string.
     */
    public static String stringDmgRealtime(
        MapInfoPacket map,
        List<Entity> sortedEntityHitList,
        ArrayList<NotificationPacket> notifications,
        LocalPlayerContext player,
        long totalDungeonPcTime,
        EncounterOutcomes outcomes
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Legacy: % enemy max HP = player damage / captured enemy max HP; hidden players do not change it.\n")
            .append(CombatMeterData.WINDOW_DEFINITION).append("\n\n");

        if (DpsDisplayOptions.equipmentOption == 3) {
            sb.append(
                "Icons are not visible in live tab. Use \"<\" to see icons.\n\n"
            );
        }

        if (map != null) {
            sb
                .append(map.name)
                .append(" ")
                .append(" [").append(DisplayFormat.formatDurationMillis(totalDungeonPcTime)).append("]")
                .append("\n\n");
        }

        appendPartyOutcome(sb, outcomes);

        for (Entity e : sortedEntityHitList) {
            if (!isValidEntity(e)) continue;

            EquipmentUsageAggregator eqAgg =
                (DpsDisplayOptions.equipmentOption == 0)
                    ? null
                    : EquipmentUsageAggregator.of(e);

            sb.append(display(e, outcomes, player, eqAgg)).append("\n");
        }

        return sb.toString();
    }

    /** "Party outcome: …" then one line per player (name, class, outcome); nothing when there are no players. */
    static void appendPartyOutcome(StringBuilder sb, EncounterOutcomes outcomes) {
        if (outcomes == null || outcomes.lines().isEmpty()) return;
        sb.append("Party outcome: ").append(outcomes.summary()).append('\n');
        for (EncounterOutcomes.Line line : outcomes.lines()) {
            sb.append("    ");
            DpsTextFormat.appendPaddedRight(sb, line.name, 12);
            sb.append(' ');
            String className = CharacterClass.getName(line.classType);
            DpsTextFormat.appendPaddedRight(sb, className == null ? "Unknown" : className, 10);
            sb.append(' ').append(line.outcome.label()).append('\n');
        }
        sb.append('\n');
    }

    /**
     * Renders a single entity section.
     */
    public static String display(
        Entity entity,
        EncounterOutcomes outcomes,
        LocalPlayerContext player,
        EquipmentUsageAggregator eqAgg
    ) {
        if (entity == null) return "";

        StringBuilder sb = new StringBuilder();

        // Entity header + table header
        sb.append(buildEntityHeader(entity));

        List<Damage> playerDamageList = entity.getPlayerDamageList();
        int counter = 0;

        for (Damage dmg : playerDamageList) {
            if (dmg == null || dmg.owner == null) continue;

            counter++;

            // Filter logic
            int filterDecision = Filter.filter(dmg.owner, player);
            if (Filter.shouldFilter(player) && filterDecision != 1) continue;

            boolean highlight = (filterDecision == 2);

            // Name cleanup and prefix
            String rawName = dmg.owner.getStatName();
            if (rawName == null) continue;
            String name = cleanName(rawName);

            String prefix = formatUserPrefix(dmg, highlight);

            // Damage contribution vs mob HP
            float percentOfMob = safePercent(dmg.damage, entity.maxHp());

            // Extra tag (guarded/dammah/garden), or 4 spaces
            String extra = GuardsHandler.buildExtraTag(entity, dmg);
            if (extra.isEmpty()) extra = "    ";

            // Dungeon-level outcome for players who did not complete (docs/DPS-METERS.md, "Party outcomes")
            EncounterOutcomes.Outcome outcome = outcomes == null ? null : outcomes.outcome(dmg.owner);
            if (outcome != null && outcome.didNotComplete()) extra += outcome.label();

            // Equipment
            String inv = "";
            if (DpsDisplayOptions.equipmentOption != 0 && eqAgg != null) {
                int ownerId = dmg.owner.id;
                if (DpsDisplayOptions.equipmentOption == 1) {
                    inv = formatEquipmentOption1(eqAgg, ownerId);
                } else if (DpsDisplayOptions.equipmentOption == 2) {
                    inv = formatEquipmentOption2(eqAgg, ownerId);
                }
            }

            // Row
            sb.append(prefix).append(' ');
            sb.append(String.format(Locale.ROOT, "%3s", DisplayFormat.formatInteger(counter)));
            sb.append("  ");
            DpsTextFormat.appendPaddedRight(sb, name, 10);
            sb.append(" DMG: ");
            sb.append(String.format(Locale.ROOT, "%7s", DisplayFormat.formatInteger(dmg.damage)));
            sb
                .append(' ')
                .append(DisplayFormat.formatPercentage(percentOfMob, 3))
                .append(" of max HP ")
                .append(extra);
            if (!inv.isEmpty()) {
                sb.append(' ').append(inv);
            }
            sb.append('\n');
        }

        sb.append("\n");
        return sb.toString();
    }

    // === Helpers ========================================================================

    private static boolean isValidEntity(Entity e) {
        return (
            e != null &&
            e.maxHp() > 0 &&
            !CharacterClass.isPlayerCharacter(e.objectType)
        );
    }

    private static String buildEntityHeader(Entity entity) {
        StringBuilder sb = new StringBuilder();
        sb
            .append(entity.name())
            .append(" HP: ")
            .append(DisplayFormat.formatInteger(entity.maxHp()))
            .append(" [").append(DisplayFormat.formatDurationMillis(CombatMeterData.windowMillis(entity))).append(" first-to-last hit window]")
            .append("\n")
            .append("    #   Player      DMG         % enemy max HP \n")
            .append("    -----------------------------------------------\n");
        return sb.toString();
    }

    private static String cleanName(String statName) {
        int index = statName.indexOf(',');
        return (index != -1) ? statName.substring(0, index) : statName;
    }

    private static String formatUserPrefix(Damage dmg, boolean highlight) {
        if (dmg.owner.isUser() && DpsDisplayOptions.showMe) return " ->";
        return highlight ? ">>>" : "   ";
    }

    private static float safePercent(int part, int total) {
        if (total <= 0) return Float.NaN;
        return ((float) part * 100f) / (float) total;
    }

    private static String formatEquipmentOption1(
        EquipmentUsageAggregator eqAgg,
        int ownerId
    ) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < EquipmentUsageAggregator.SLOT_COUNT; i++) {
            if (i != 0) s.append(" / ");
            Equipment max = eqAgg.getMostUsedItem(ownerId, i);
            int id = (max != null) ? max.id : 0;
            s.append(IdToAsset.objectName(id));
        }
        s.append("]");
        return s.toString();
    }

    private static String formatEquipmentOption2(
        EquipmentUsageAggregator eqAgg,
        int ownerId
    ) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < EquipmentUsageAggregator.SLOT_COUNT; i++) {
            s.append("\n       ");
            Collection<Equipment> list = eqAgg.getSlotBreakdown(ownerId, i);
            int total = eqAgg.getSlotTotalDamage(ownerId, i);

            // Sort by descending contribution
            List<Equipment> sorted = new ArrayList<>(list);
            sorted.sort((a, b) -> Integer.compare(b.dmg, a.dmg));

            boolean first = true;
            int size = sorted.size();

            for (Equipment e2 : sorted) {
                if (size > 1) {
                    if (!first) s.append(" /");
                    double pct = (total > 0) ? (100.0 * e2.dmg) / total : 0.0;
                    s
                        .append(' ')
                        .append(
                            pct > 0 && pct < .1 ? "< " + DisplayFormat.formatPercentage(.1, 1)
                                : DisplayFormat.formatPercentage(pct, 1)
                        )
                        .append(' ');
                } else {
                    // Single item => 100%
                    s.append(" 100% ");
                }
                s.append(IdToAsset.objectName(e2.id));
                first = false;
            }
        }
        return s.toString();
    }
}
