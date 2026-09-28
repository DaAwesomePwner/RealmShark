package tomato.gui.quest;

import java.util.*;
import java.util.function.IntFunction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.planning.PlanData;
import tomato.planning.PlanData.*;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestPlanningTest.entry;

/**
 * Plan cards (spec §6.5 Planner): every number is QuestPlanning.totals' (a plan alone for its card, every plan for All plans),
 * covered is the unreserved share min(need, available) − reserved, and stock without a manual held entry is unknown, never 0.
 */
public class PlanCardModelTest {
    static final IntFunction<String> NAMES = id -> id == 1 ? "Mark of the Forgotten King" : id == 2 ? "Festival Token" : null;
    private Locale format;

    @Before public void usFormat() { format = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, format); }

    /** a needs 2 × item 1; b needs 3 × item 1 and 1 × item 2. Held item 1: 4 (manual); a reserves 2, b reserves 1; item 2 has no held entry. */
    static AccountPlan plan() {
        AccountPlan p = new AccountPlan();
        p.quests.put("a", entry("a", 1, 1)); p.quests.put("b", entry("b", 1, 1, 1, 2));
        QuestPlanning.held(p, 1, 4, "counted", false, 120);
        QuestPlanning.reserve(p, "a", 1, 2); QuestPlanning.reserve(p, "b", 1, 1);
        PlanData.validate("account", p);
        return p;
    }

    @Test public void eachCardShowsItsPlanAloneFromTotalsWithCoveredAsTheUnreservedShare() {
        AccountPlan p = plan();
        List<PlanCardModel> cards = PlanCardModel.cards(p, NAMES);
        assertEquals("One card per plan, in the plan order", List.of("a", "b"), List.of(cards.get(0).entryId(), cards.get(1).entryId()));

        PlanCardModel a = cards.get(0);
        QuestPlanning.Totals alone = QuestPlanning.totals(p, List.of("a"));
        assertEquals("a", a.name());
        assertEquals(alone.readiness(), a.readiness());
        assertEquals("Saved requirements; verify server", a.status());
        assertEquals("Repeats: 1", a.repeats());
        assertTrue(a.actionable()); assertTrue(a.requirementsKnown());
        assertEquals(1, a.rows().size());
        PlanCardModel.Row mark = a.rows().get(0);
        assertEquals(1, mark.itemId()); assertEquals("Mark of the Forgotten King", mark.name());
        assertEquals(alone.demand.get(1).longValue(), mark.need());
        assertEquals(alone.reserved.get(1).longValue(), mark.reserved());
        assertEquals("available = held 4 − b's reservation 1", alone.available.get(1), mark.available());
        assertEquals(alone.missing.get(1), mark.missing());
        assertEquals("covered = min(need 2, available 3) − reserved 2", Long.valueOf(0), mark.covered());
        assertEquals("need 2 · reserved 2 · covered 0 · missing 0", mark.numbers());

        PlanCardModel b = cards.get(1);
        QuestPlanning.Totals bAlone = QuestPlanning.totals(p, List.of("b"));
        assertEquals("Rows follow the totals (ascending item id)", List.of(1, 2), List.of(b.rows().get(0).itemId(), b.rows().get(1).itemId()));
        PlanCardModel.Row bMark = b.rows().get(0);
        assertEquals(3, bMark.need()); assertEquals(1, bMark.reserved());
        assertEquals("available = held 4 − a's reservation 2", Long.valueOf(2), bMark.available());
        assertEquals(bAlone.missing.get(1), bMark.missing());
        assertEquals("covered = min(3, 2) − 1", Long.valueOf(1), bMark.covered());
        assertEquals("The three segments make up the need", bMark.need(), bMark.reserved() + bMark.covered() + bMark.missing());
        assertEquals(bAlone.readiness(), b.readiness());
    }

    @Test public void stockWithoutAHeldEntryIsUnconfirmedNeverZero() {
        PlanCardModel.Row token = PlanCardModel.of(plan(), plan().quests.get("b"), NAMES).rows().get(1);
        assertEquals("Festival Token", token.name());
        assertEquals(1, token.need()); assertEquals(0, token.reserved());
        assertFalse(token.stockKnown());
        assertNull(token.available()); assertNull(token.missing()); assertNull("Unknown, not 0", token.covered());
        assertEquals("need 1 · Stock unconfirmed", token.numbers());

        AccountPlan zero = plan(); QuestPlanning.held(zero, 2, 0, "explicit zero", false, 130);
        PlanCardModel.Row confirmed = PlanCardModel.of(zero, zero.quests.get("b"), NAMES).rows().get(1);
        assertTrue("A confirmed zero is known", confirmed.stockKnown());
        assertEquals(Long.valueOf(0), confirmed.available()); assertEquals(Long.valueOf(0), confirmed.covered()); assertEquals(Long.valueOf(1), confirmed.missing());
        assertEquals("need 1 · reserved 0 · covered 0 · missing 1", confirmed.numbers());
    }

    @Test public void staleCompletedAndUnknownRequirementPlansSaySo() {
        AccountPlan p = plan();
        p.quests.get("a").stale = true;
        PlanCardModel stale = PlanCardModel.of(p, p.quests.get("a"), NAMES);
        assertEquals("Changed / removed; reconfirm", stale.status());
        assertEquals("Unavailable / reconfirmation required", stale.readiness());
        assertFalse(stale.actionable());

        QuestPlanEntry done = entry("done", 1); done.repeatable = false; done.completed = true; p.quests.put("done", done);
        PlanCardModel completed = PlanCardModel.of(p, done, NAMES);
        assertEquals("Completed one-time", completed.status());
        assertEquals("One-time", completed.repeats());
        assertFalse(completed.actionable());

        QuestPlanEntry unknown = entry("unknown"); unknown.requirementsKnown = false; p.quests.put("unknown", unknown);
        PlanCardModel uncaptured = PlanCardModel.of(p, unknown, NAMES);
        assertEquals("Requirements unknown", uncaptured.status());
        assertFalse(uncaptured.requirementsKnown());
        assertTrue("No rows, and the card says not captured instead", uncaptured.rows().isEmpty());
        assertTrue(uncaptured.readiness().startsWith("Unknown"));

        p.quests.get("b").desiredRepeats = 3;
        PlanCardModel repeated = PlanCardModel.of(p, p.quests.get("b"), NAMES);
        assertEquals("Repeats: 3", repeated.repeats());
        assertEquals("need = per repeat × repeats", 9, repeated.rows().get(0).need());

        QuestPlanEntry nameless = entry("nameless", 7); p.quests.put("nameless", nameless);
        assertEquals("A missing asset name", "Unknown item", PlanCardModel.of(p, nameless, NAMES).rows().get(0).name());
    }

    @Test public void theAllPlansSummaryIsTotalsOverEveryPlan() {
        AccountPlan p = plan();
        PlanCardModel.Summary summary = PlanCardModel.summary(p, NAMES);
        QuestPlanning.Totals all = QuestPlanning.totals(p, p.quests.keySet());
        assertEquals(2, summary.plans());
        assertEquals(all.readiness(), summary.readiness());
        assertEquals(new ArrayList<>(all.demand.keySet()), List.of(summary.rows().get(0).itemId(), summary.rows().get(1).itemId()));
        for (PlanCardModel.Row row : summary.rows()) {
            assertEquals(all.demand.get(row.itemId()).longValue(), row.need());
            assertEquals(all.reserved.get(row.itemId()).longValue(), row.reserved());
            assertEquals(all.available.get(row.itemId()), row.available());
            assertEquals(all.missing.get(row.itemId()), row.missing());
        }
        PlanCardModel.Row mark = summary.rows().get(0);
        assertEquals("Combined: need 5, reserved 3, available 4, missing 1, so covered min(5, 4) − 3 = 1",
            "need 5 · reserved 3 · covered 1 · missing 1", mark.numbers());
        assertEquals("need 1 · Stock unconfirmed", summary.rows().get(1).numbers());

        PlanCardModel.Summary none = PlanCardModel.summary(new AccountPlan(), NAMES);
        assertEquals(0, none.plans());
        assertEquals("No plans is not \"covered\"", "No saved plans", none.readiness());
        assertTrue(none.rows().isEmpty());
    }

    @Test public void modelsAreImmutableValues() {
        AccountPlan p = plan();
        assertEquals("Equal inputs give equal cards (the TileList updates nothing)", PlanCardModel.cards(p, NAMES), PlanCardModel.cards(PlanData.copy(p), NAMES));
        assertEquals(PlanCardModel.summary(p, NAMES), PlanCardModel.summary(PlanData.copy(p), NAMES));
        List<PlanCardModel.Row> rows = PlanCardModel.of(p, p.quests.get("a"), NAMES).rows();
        try { rows.clear(); fail("Rows are an immutable copy"); } catch (UnsupportedOperationException expected) { }
    }
}
