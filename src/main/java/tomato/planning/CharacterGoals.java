package tomato.planning;

import java.util.Objects;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;

/** Pure calculations; saved targets are fixed intent, never silently replaced by current metadata. */
public final class CharacterGoals {
    private CharacterGoals() { }
    public static final class Progress {
        public final Integer remaining;
        public final boolean metadataChanged;
        public final String state;
        private Progress(Integer remaining, boolean changed, String state) { this.remaining = remaining; metadataChanged = changed; this.state = state; }
    }
    public static Progress character(PlanData.CharacterGoal goal, CharacterJournal.CharacterRecord record, RosterDefinitions definitions) {
        boolean changed = !Objects.equals(goal.metadataVersion, PlanningMetadata.capVersion(definitions, record.classId));
        Integer cap = definitions.cap(record.classId, goal.statIndex), base = record.stats[goal.statIndex];
        if (cap == null || base == null) return new Progress(null, changed, "Unknown: " + (cap == null ? "cap unavailable" : "base not captured"));
        if (goal.targetBaseValue > cap) return new Progress(null, true, "Saved target exceeds current cap; reconfirm");
        int remaining = CharacterJournal.potions(base, goal.targetBaseValue, goal.statIndex);
        return new Progress(remaining, changed, remaining == 0 ? "Complete; fixed target retained" : remaining <= 5 ? "Near complete" : "In progress");
    }
    public static Progress exalt(PlanData.ExaltGoal goal, Integer count, PlanningMetadata metadata) {
        boolean changed = !Objects.equals(goal.metadataVersion, metadata.version);
        if (count == null) return new Progress(null, changed, "Unknown: completions not captured");
        int remaining = Math.max(0, PlanningMetadata.threshold(goal.targetTier) - count);
        return new Progress(remaining, changed, remaining == 0 ? "Complete; fixed target retained" : remaining <= 5 ? "Near complete" : "In progress");
    }
    public static int nextTier(Integer count) {
        if (count == null) throw new IllegalArgumentException("Observed completions required for a next-tier target; choose a custom tier");
        for (int i = 1; i <= 5; i++) if (count < PlanningMetadata.threshold(i)) return i;
        return 5;
    }
    public static void pinCharacter(PlanData.AccountPlan plan, CharacterJournal.CharacterRecord record, int stat, int target, RosterDefinitions definitions, long now) {
        Integer cap = definitions.cap(record.classId, stat);
        if (cap == null) throw new IllegalArgumentException("Cap unavailable; cannot verify an attainable target");
        if (target < 0 || target > cap) throw new IllegalArgumentException("Target must be 0–" + cap);
        String key = PlanData.characterGoalKey(record.key, stat);
        PlanData.CharacterGoal old = plan.characterGoals.get(key), goal = new PlanData.CharacterGoal();
        goal.characterKey = record.key; goal.statIndex = stat; goal.targetBaseValue = target;
        goal.createdAt = old == null ? now : old.createdAt; goal.updatedAt = now;
        goal.metadataVersion = PlanningMetadata.capVersion(definitions, record.classId); plan.characterGoals.put(key, goal);
    }
    public static void pinExalt(PlanData.AccountPlan plan, int classId, int stat, int tier, PlanningMetadata metadata, long now) {
        PlanningMetadata.threshold(tier);
        String key = PlanData.exaltGoalKey(classId, stat); PlanData.ExaltGoal old = plan.exaltGoals.get(key), goal = new PlanData.ExaltGoal();
        goal.classId = classId; goal.statIndex = stat; goal.targetTier = tier; goal.createdAt = old == null ? now : old.createdAt;
        goal.updatedAt = now; goal.metadataVersion = metadata.version; plan.exaltGoals.put(key, goal);
    }
}
