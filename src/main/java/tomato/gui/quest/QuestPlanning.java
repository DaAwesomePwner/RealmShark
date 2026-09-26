package tomato.gui.quest;

import java.util.*;
import tomato.planning.PlanData;
import tomato.planning.PlanData.*;

/** Pure manual planning operations. Observations never change held stock or allocate it. */
public final class QuestPlanning {
    private QuestPlanning() { }
    public static QuestPlanEntry snapshot(QuestGUI.Quest q, long at, long generation) {
        if (q.id.trim().isEmpty()) throw new IllegalArgumentException("No stable quest ID; keep this as a global interest only");
        QuestPlanEntry p = new QuestPlanEntry();
        p.entryId = q.id; p.stableQuestId = q.id; p.name = q.name; p.rawExpiration = q.expiration;
        p.metadataVersion = "quest-packet-v1:category=" + q.category;
        p.capturedAt = at; p.sourceGeneration = generation;
        p.requirementsKnown = q.requirementsKnown; p.rewardsKnown = q.rewardsKnown;
        p.repeatable = q.repeatable; p.completed = q.completed; p.chooseOne = q.choice;
        p.requirements = quantities(q.requirements); p.rewards = quantities(q.rewards);
        return p;
    }
    static Map<Integer, Long> quantities(int[] items) {
        Map<Integer, Long> values = new LinkedHashMap<>();
        for (int id : items) values.put(id, Math.addExact(values.getOrDefault(id, 0L), 1));
        return values;
    }
    public static boolean sameDefinition(QuestPlanEntry a, QuestPlanEntry b) {
        return Objects.equals(a.stableQuestId, b.stableQuestId) && Objects.equals(a.name, b.name)
            && Objects.equals(a.rawExpiration, b.rawExpiration) && Objects.equals(a.metadataVersion, b.metadataVersion)
            && a.requirementsKnown == b.requirementsKnown && a.rewardsKnown == b.rewardsKnown
            && a.requirements.equals(b.requirements) && a.rewards.equals(b.rewards)
            && a.repeatable == b.repeatable && a.completed == b.completed && a.chooseOne == b.chooseOne;
    }
    /** Returns a detached projection; changed/removed definitions keep their saved requirements. */
    public static AccountPlan reconcile(AccountPlan saved, Map<String, QuestPlanEntry> current) {
        AccountPlan result = PlanData.copy(saved);
        for (QuestPlanEntry p : result.quests.values())
            if (!current.containsKey(p.stableQuestId) || !sameDefinition(p, current.get(p.stableQuestId))) p.stale = true;
        return result;
    }
    public static final class Totals {
        public final Map<Integer, Long> demand = new TreeMap<>(), reserved = new TreeMap<>(), available = new TreeMap<>(), missing = new TreeMap<>();
        public boolean requirementsKnown = true, stockKnown = true, actionable = true;
        public String readiness() {
            if (!actionable) return "Unavailable / reconfirmation required";
            if (!requirementsKnown || !stockKnown) return "Unknown — requirements or manual stock unconfirmed";
            return missing.values().stream().anyMatch(n -> n > 0) ? "More items needed" : "Requirements covered by manual stock; server eligibility unverified";
        }
    }
    public static Totals totals(AccountPlan p, Collection<String> selected) {
        Set<String> group = new HashSet<>(selected); Totals t = new Totals();
        for (String id : group) {
            QuestPlanEntry q = p.quests.get(id); if (q == null) continue;
            t.requirementsKnown &= q.requirementsKnown;
            t.actionable &= !q.stale && !(q.completed && !q.repeatable);
            for (Map.Entry<Integer, Long> r : q.requirements.entrySet())
                t.demand.put(r.getKey(), Math.addExact(t.demand.getOrDefault(r.getKey(), 0L), Math.multiplyExact(r.getValue(), q.desiredRepeats)));
        }
        for (Map.Entry<Integer, Long> d : t.demand.entrySet()) {
            long outside = 0, inside = 0;
            for (Map.Entry<String, Map<Integer, Long>> r : p.reservations.entrySet()) {
                long n = r.getValue().getOrDefault(d.getKey(), 0L);
                if (group.contains(r.getKey())) inside = Math.addExact(inside, n); else outside = Math.addExact(outside, n);
            }
            ManualHeld held = p.held.get(d.getKey()); t.reserved.put(d.getKey(), inside);
            if (held == null) { t.stockKnown = false; continue; }
            long available = Math.max(0, held.quantity - outside);
            t.available.put(d.getKey(), available); t.missing.put(d.getKey(), Math.max(0, d.getValue() - available));
        }
        return t;
    }
    /** An explicit release-all choice is atomic with the new confirmed stock value. */
    public static void held(AccountPlan p, int item, long n, String note, boolean release, long at) {
        if (release) for (Map<Integer, Long> r : p.reservations.values()) r.remove(item);
        p.held.put(item, new ManualHeld(n, at, note));
    }
    public static void reserve(AccountPlan p, String entry, int item, long n) {
        if (n == 0) { Map<Integer, Long> existing = p.reservations.get(entry); if (existing != null) { existing.remove(item); if (existing.isEmpty()) p.reservations.remove(entry); } }
        else p.reservations.computeIfAbsent(entry, k -> new LinkedHashMap<>()).put(item, n);
    }
}
