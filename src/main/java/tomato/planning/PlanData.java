package tomato.planning;

import com.google.gson.Gson;
import java.util.*;

/** Detached edit models. A store never exposes or retains the caller's mutable instance. */
public final class PlanData {
    private PlanData() { }
    private static final Gson JSON = new Gson();
    public static final long MAX_QUANTITY = 1000000000L;
    public static final class AccountPlan {
        public Map<String, CharacterGoal> characterGoals = new LinkedHashMap<>();
        public Map<String, ExaltGoal> exaltGoals = new LinkedHashMap<>();
        public Map<String, QuestPlanEntry> quests = new LinkedHashMap<>();
        public Map<Integer, ManualHeld> held = new LinkedHashMap<>();
        public Map<String, Map<Integer, Long>> reservations = new LinkedHashMap<>();
    }
    public static final class CharacterGoal {
        public String characterKey, metadataVersion;
        public int statIndex, targetBaseValue;
        public long createdAt, updatedAt;
    }
    public static final class ExaltGoal {
        public int classId, statIndex, targetTier;
        public String metadataVersion;
        public long createdAt, updatedAt;
    }
    public static final class QuestPlanEntry {
        public String entryId, stableQuestId, name, legacyPinKey, rawExpiration, metadataVersion;
        public long capturedAt, sourceGeneration, desiredRepeats = 1;
        public boolean requirementsKnown, rewardsKnown, repeatable, completed, chooseOne, stale;
        public Map<Integer, Long> requirements = new LinkedHashMap<>();
        public Map<Integer, Long> rewards = new LinkedHashMap<>();
    }
    public static final class ManualHeld {
        public long quantity, confirmedAt;
        public String note;
        public ManualHeld() { }
        public ManualHeld(long quantity, long confirmedAt, String note) {
            this.quantity = quantity; this.confirmedAt = confirmedAt; this.note = note;
        }
    }
    public static AccountPlan copy(AccountPlan value) { return JSON.fromJson(JSON.toJson(value), AccountPlan.class); }
    public static String characterGoalKey(String characterKey, int stat) { return characterKey + ":" + stat; }
    public static String exaltGoalKey(int classId, int stat) { return classId + ":" + stat; }
    public static void validate(String account, AccountPlan p) {
        if (account == null || account.trim().isEmpty()) throw new IllegalArgumentException("Select a known account first");
        if (p == null || p.characterGoals == null || p.exaltGoals == null || p.quests == null || p.held == null || p.reservations == null)
            throw new IllegalArgumentException("Incomplete planning document");
        for (Map.Entry<String, CharacterGoal> e : p.characterGoals.entrySet()) {
            CharacterGoal g = e.getValue();
            if (g == null || g.characterKey == null || !g.characterKey.startsWith(account + ":") || g.statIndex < 0 || g.statIndex > 7
                    || g.targetBaseValue < 0 || !e.getKey().equals(characterGoalKey(g.characterKey, g.statIndex)))
                throw new IllegalArgumentException("Invalid character goal or account");
        }
        for (Map.Entry<String, ExaltGoal> e : p.exaltGoals.entrySet()) {
            ExaltGoal g = e.getValue();
            if (g == null || g.classId < 0 || g.statIndex < 0 || g.statIndex > 7 || g.targetTier < 1 || g.targetTier > 5
                    || !e.getKey().equals(exaltGoalKey(g.classId, g.statIndex))) throw new IllegalArgumentException("Invalid exalt goal");
        }
        for (Map.Entry<String, QuestPlanEntry> e : p.quests.entrySet()) {
            QuestPlanEntry q = e.getValue();
            if (q == null || q.entryId == null || !q.entryId.equals(e.getKey()) || q.stableQuestId == null || q.stableQuestId.trim().isEmpty())
                throw new IllegalArgumentException("An actionable quest needs its stable ID");
            quantity(q.desiredRepeats);
            if (q.desiredRepeats < 1 || (!q.repeatable && q.desiredRepeats != 1)) throw new IllegalArgumentException("Invalid repeat count");
            counts(q.requirements); counts(q.rewards);
            for (long count : q.requirements.values()) quantity(Math.multiplyExact(count, q.desiredRepeats));
        }
        for (Map.Entry<Integer, ManualHeld> e : p.held.entrySet()) {
            if (e.getKey() == null || e.getKey() < 0 || e.getValue() == null) throw new IllegalArgumentException("Invalid manual stock");
            quantity(e.getValue().quantity);
        }
        Map<Integer, Long> allocated = new HashMap<>();
        for (Map.Entry<String, Map<Integer, Long>> e : p.reservations.entrySet()) {
            QuestPlanEntry q = p.quests.get(e.getKey());
            if (q == null || !q.requirementsKnown || q.stale || (q.completed && !q.repeatable))
                throw new IllegalArgumentException("Release reservations for unavailable or completed plans");
            counts(e.getValue());
            for (Map.Entry<Integer, Long> r : e.getValue().entrySet()) {
                long demand = Math.multiplyExact(q.requirements.getOrDefault(r.getKey(), 0L), q.desiredRepeats);
                if (r.getValue() > demand) throw new IllegalArgumentException("Release reservations above the revised requirement");
                long total = Math.addExact(allocated.getOrDefault(r.getKey(), 0L), r.getValue());
                ManualHeld held = p.held.get(r.getKey());
                if (held == null || total > held.quantity) throw new IllegalArgumentException("Reservation exceeds manually confirmed stock");
                allocated.put(r.getKey(), total);
            }
        }
    }
    private static void counts(Map<Integer, Long> values) {
        if (values == null) throw new IllegalArgumentException("Missing quantity map");
        for (Map.Entry<Integer, Long> e : values.entrySet()) {
            if (e.getKey() == null || e.getKey() < 0 || e.getValue() == null) throw new IllegalArgumentException("Invalid item quantity");
            quantity(e.getValue());
        }
    }
    private static void quantity(long n) { if (n < 0 || n > MAX_QUANTITY) throw new IllegalArgumentException("Quantity must be between 0 and " + MAX_QUANTITY); }
}
