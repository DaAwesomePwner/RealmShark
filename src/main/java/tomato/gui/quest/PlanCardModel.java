package tomato.gui.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import tomato.gui.modern.DisplayFormat;
import tomato.planning.PlanData.AccountPlan;
import tomato.planning.PlanData.QuestPlanEntry;

/**
 * One plan card of the quest Planner (spec §6.5): the plan alone, as QuestPlanning.totals computes it for that one entry, and the
 * All plans summary, the same totals over every plan of the account. Pure and immutable (the rows are copies), so equal inputs give
 * equal models and the TileList updates nothing.
 * - Every number is QuestPlanning.totals': need, reserved (this plan's manual reservation), available (manually confirmed held
 *   stock minus the other plans' reservations; captured inventory is never a source) and missing. Covered is the unreserved share
 *   of the need, min(need, available) − reserved, so reserved + covered + missing = need.
 * - Stock without a manual held entry is unknown: available, missing and covered are null ("Stock unconfirmed"), never 0.
 * - Unknown requirements give no rows and requirementsKnown false (the card says "not captured", never "none").
 * - A card counts the unreserved stock as if no other plan wanted it (as the table does for one selected row); the All plans
 *   summary is the combined view without that double counting.
 */
record PlanCardModel(String entryId, String name, String status, String readiness, String repeats, boolean actionable,
                     boolean requirementsKnown, List<Row> rows) {
    static final String UNCONFIRMED = "Stock unconfirmed";

    PlanCardModel { rows = List.copyOf(rows); }

    /** One requirement item: {@code available} and {@code missing} are null when no manual held entry exists (stock unknown). */
    record Row(int itemId, String name, long need, long reserved, Long available, Long missing) {
        boolean stockKnown() { return available != null && missing != null; }
        /** The item's name with its id, once ({@link PlanCardModel#label}). */
        String label() { return PlanCardModel.label(name, itemId); }

        /**
         * min(need, available) − reserved, or null when stock is unknown. PlanData.validate keeps a reservation within the plan's
         * demand and the held stock, so this is never negative for a saved plan; the floor only keeps a bar from drawing backwards.
         */
        Long covered() { return stockKnown() ? Math.max(0, Math.min(need, available) - reserved) : null; }

        /** "need 5 · reserved 3 · covered 1 · missing 1", or "need 1 · Stock unconfirmed". */
        String numbers() {
            return "need " + DisplayFormat.formatInteger(need) + (stockKnown() ? " · reserved " + DisplayFormat.formatInteger(reserved)
                + " · covered " + DisplayFormat.formatInteger(covered().longValue()) + " · missing " + DisplayFormat.formatInteger(missing.longValue())
                : " · " + UNCONFIRMED);
        }
    }

    /** The All plans card: how many plans, the combined readiness and one row per requirement item across every plan. */
    record Summary(int plans, String readiness, List<Row> rows) {
        Summary { rows = List.copyOf(rows); }
    }

    /** Every plan of the (effective, detached) account plan, in its saved order. */
    static List<PlanCardModel> cards(AccountPlan plan, IntFunction<String> names) {
        List<PlanCardModel> cards = new ArrayList<>();
        for (QuestPlanEntry entry : plan.quests.values()) cards.add(of(plan, entry, names));
        return cards;
    }

    /** The card of one plan: QuestPlanning.totals over this entry alone. */
    static PlanCardModel of(AccountPlan plan, QuestPlanEntry entry, IntFunction<String> names) {
        QuestPlanning.Totals totals = QuestPlanning.totals(plan, List.of(entry.entryId));
        return new PlanCardModel(entry.entryId, entry.name == null ? "" : entry.name, status(entry), totals.readiness(), repeats(entry),
            totals.actionable, entry.requirementsKnown, rows(totals, names));
    }

    /** The All plans summary: QuestPlanning.totals over every plan's id ("No saved plans" when there are none, never "covered"). */
    static Summary summary(AccountPlan plan, IntFunction<String> names) {
        if (plan.quests.isEmpty()) return new Summary(0, "No saved plans", List.of());
        QuestPlanning.Totals totals = QuestPlanning.totals(plan, plan.quests.keySet());
        return new Summary(plan.quests.size(), totals.readiness(), rows(totals, names));
    }

    /** The plan's state, as the table's Status column says it. */
    static String status(QuestPlanEntry q) {
        return q.stale ? "Changed / removed; reconfirm" : q.completed && !q.repeatable ? "Completed one-time"
            : q.requirementsKnown ? "Saved requirements; verify server" : "Requirements unknown";
    }

    /** "Repeats: 3" for a repeatable quest; "One-time" otherwise (a one-time plan always has one repeat). */
    static String repeats(QuestPlanEntry q) { return q.repeatable ? "Repeats: " + DisplayFormat.formatInteger(q.desiredRepeats) : "One-time"; }

    /** An item's display name ("Unknown item" when the lookup has none, as the table's text says). */
    static String name(IntFunction<String> names, int id) { String value = names.apply(id); return value == null ? "Unknown item" : value; }

    /**
     * "Festival Token (#3)": an item's name with its id, once. A name that already ends with "#id" (the Board's lookup names an item
     * without an asset name "Unknown item #9999") is shown as it is, never "Unknown item #9999 (#9999)".
     */
    static String label(String name, int id) { return name.endsWith("#" + id) ? name : name + " (#" + id + ")"; }

    /** One row per demanded item, in the totals' order (ascending item id). */
    private static List<Row> rows(QuestPlanning.Totals totals, IntFunction<String> names) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Integer, Long> demand : totals.demand.entrySet()) {
            int id = demand.getKey();
            rows.add(new Row(id, name(names, id), demand.getValue(), totals.reserved.getOrDefault(id, 0L), totals.available.get(id), totals.missing.get(id)));
        }
        return rows;
    }
}
