package tomato.gui.quest;

import org.junit.Test;
import static org.junit.Assert.*;
import packets.data.QuestData;
import tomato.planning.PlanData;
import tomato.planning.PlanData.*;
import java.util.*;

public class QuestPlanningTest {
    static QuestGUI.Quest quest(String id, int... requirements) {
        QuestData q = new QuestData(); q.id = id; q.name = id; q.requirements = requirements; q.rewards = new int[]{20, 21}; q.repeatable = true;
        return new QuestGUI.Quest(q);
    }
    static QuestPlanEntry entry(String id, int... requirements) { return QuestPlanning.snapshot(quest(id, requirements), 100, 1); }
    @Test public void multiplicityRepeatsAndGroupAvailabilityDoNotDoubleSubtractReservations() {
        AccountPlan p = new AccountPlan(); p.quests.put("a", entry("a", 7, 7)); p.quests.put("b", entry("b", 7, 7, 7));
        QuestPlanning.held(p, 7, 4, "counted", false, 120);
        QuestPlanning.reserve(p, "a", 7, 2); QuestPlanning.reserve(p, "b", 7, 2); PlanData.validate("account", p);
        QuestPlanning.Totals total = QuestPlanning.totals(p, Arrays.asList("a", "b"));
        assertEquals(Long.valueOf(5), total.demand.get(7)); assertEquals(Long.valueOf(4), total.available.get(7)); assertEquals(Long.valueOf(1), total.missing.get(7));
        assertEquals(Long.valueOf(2), QuestPlanning.totals(p, Arrays.asList("a")).available.get(7));
        p.quests.get("a").desiredRepeats = 2;
        assertEquals(Long.valueOf(4), QuestPlanning.totals(p, Arrays.asList("a")).demand.get(7));
    }
    @Test public void unknownRequirementsAndUnknownStockDifferFromEmptyAndZero() {
        QuestData raw = new QuestData(); raw.id = "unknown"; raw.requirements = null; raw.rewards = null;
        QuestPlanEntry unknown = QuestPlanning.snapshot(new QuestGUI.Quest(raw), 0, 1);
        assertFalse(unknown.requirementsKnown); assertFalse(unknown.rewardsKnown);
        AccountPlan p = new AccountPlan(); p.quests.put("unknown", unknown);
        assertTrue(QuestPlanning.totals(p, p.quests.keySet()).readiness().startsWith("Unknown"));
        p.quests.clear(); p.quests.put("empty", entry("empty"));
        assertTrue(QuestPlanning.totals(p, p.quests.keySet()).readiness().startsWith("Requirements covered"));
        p.quests.put("needed", entry("needed", 1));
        assertFalse(QuestPlanning.totals(p, p.quests.keySet()).stockKnown);
        QuestPlanning.held(p, 1, 0, "explicit zero", false, 123);
        assertEquals("More items needed", QuestPlanning.totals(p, p.quests.keySet()).readiness());
    }
    @Test public void observationsMarkStaleWithoutOverwritingSavedRequirementsOrManualStock() {
        AccountPlan p = new AccountPlan(); p.quests.put("a", entry("a", 1)); QuestPlanning.held(p, 1, 6, "manual", false, 123);
        Map<String, QuestPlanEntry> observed = new HashMap<>(); observed.put("a", entry("a", 2));
        AccountPlan next = QuestPlanning.reconcile(p, observed);
        assertTrue(next.quests.get("a").stale); assertFalse(p.quests.get("a").stale);
        assertEquals(Long.valueOf(1), next.quests.get("a").requirements.get(1)); assertEquals(6, next.held.get(1).quantity);
        assertFalse(QuestPlanning.totals(next, next.quests.keySet()).actionable);
        assertTrue(QuestPlanning.reconcile(p, Collections.emptyMap()).quests.get("a").stale);
    }
    @Test public void reductionsNeedAtomicReleaseAndCompletedOneTimeIsNeverActionable() {
        AccountPlan p = new AccountPlan(); p.quests.put("a", entry("a", 1, 1)); QuestPlanning.held(p, 1, 2, "", false, 1); QuestPlanning.reserve(p, "a", 1, 2);
        AccountPlan smaller = PlanData.copy(p); QuestPlanning.held(smaller, 1, 1, "", false, 2);
        try { PlanData.validate("x", smaller); fail(); } catch (IllegalArgumentException expected) { }
        QuestPlanning.held(smaller, 1, 1, "", true, 2); PlanData.validate("x", smaller);
        QuestPlanEntry q = smaller.quests.get("a"); q.repeatable = false; q.completed = true;
        assertFalse(QuestPlanning.totals(smaller, smaller.quests.keySet()).actionable);
        q.desiredRepeats = 2;
        try { PlanData.validate("x", smaller); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void checkedOverflowAndMissingStableIdAreRejected() {
        AccountPlan p = new AccountPlan(); QuestPlanEntry q = entry("a", 1); q.desiredRepeats = Long.MAX_VALUE; q.requirements.put(1, 2L); p.quests.put("a", q);
        try { QuestPlanning.totals(p, p.quests.keySet()); fail(); } catch (ArithmeticException expected) { }
        try { QuestPlanning.snapshot(quest("", 1), 0, 0); fail(); } catch (IllegalArgumentException expected) { }
    }
}
