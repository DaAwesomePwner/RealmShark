package tomato.gui.stats;

import tomato.gui.modern.DisplayFormat;

/**
 * Presents the dungeon rate that {@link StatisticsArchiveAdapter} already computed for its eligible cohort.
 * It only formats adapter values (numerator, eligible runs, observed duration, exclusions); it never derives a
 * rate from visible occurrence rows, so paging or item facets cannot change a denominator.
 */
public final class RateCalculation {
    private RateCalculation() { }

    /** Why the per-run rate is unavailable, or null when the adapter produced one. */
    public static String perRunUnavailable(LootQuery.Row r) {
        if (r.items == null) return "no saved loot evidence in this cohort's sessions; absence of records does not establish zero";
        if (r.runs == null || r.runs == 0) return "no eligible observed runs";
        if (r.unassignedBags != null && r.unassignedBags > 0) return r.unassignedBags + " unassigned bag(s) would mix numerator and denominator scopes";
        return r.perRun == null ? "the eligible cohort did not produce a rate" : null;
    }
    /** Why the hourly rate is unavailable, or null when the adapter produced one. */
    public static String perHourUnavailable(LootQuery.Row r) {
        String perRun = perRunUnavailable(r);
        if (perRun != null) return perRun;
        return r.perHour == null ? "at least one eligible run has no positive observed duration" : null;
    }

    public static String describe(LootQuery.Row r) {
        StringBuilder text = new StringBuilder("Rate calculation · ").append(r.dungeon == null || r.dungeon.isEmpty() ? r.name : r.dungeon).append('\n');
        text.append("Numerator: ").append(r.items == null ? "unavailable (loot not captured for these sessions)" : DisplayFormat.formatInteger(r.items) + " observed items in eligible runs (drops, not pickups)").append('\n');
        text.append("Eligible runs: ").append(DisplayFormat.formatInteger(r.runs)).append(r.zeroLootRuns == null ? "" : " (" + DisplayFormat.formatInteger(r.zeroLootRuns) + " with no linked bags, kept in the denominator)").append('\n');
        text.append("Observed duration: ").append(r.millis == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(r.millis) + " ms (" + DisplayFormat.formatDurationHMS(r.millis) + ")").append('\n');
        String perRun = perRunUnavailable(r), perHour = perHourUnavailable(r);
        text.append("Items / run = ").append(perRun != null ? "unavailable: " + perRun
            : DisplayFormat.formatInteger(r.items) + " ÷ " + DisplayFormat.formatInteger(r.runs) + " = " + DisplayFormat.formatNumber(r.perRun, 0, 3)).append('\n');
        text.append("Items / hour = ").append(perHour != null ? "unavailable: " + perHour
            : DisplayFormat.formatInteger(r.items) + " × 3,600,000 ÷ " + DisplayFormat.formatInteger(r.millis) + " ms = " + DisplayFormat.formatNumber(r.perHour, 0, 3)).append('\n');
        text.append("Exclusions: ").append(DisplayFormat.formatInteger(r.importedRuns)).append(" run-only imported runs; ")
            .append(DisplayFormat.formatInteger(r.unknownRuns)).append(" unknown-coverage runs (sessions without saved loot evidence)").append('\n');
        text.append("Unassigned drops: ").append(r.unassignedBags == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(r.unassignedBags) + " bags (session + visit + canonical dungeon must agree)").append('\n');
        text.append("Sample rates, not drop probabilities. Item/bag/enchant facets never change this cohort.");
        return text.toString();
    }
}
