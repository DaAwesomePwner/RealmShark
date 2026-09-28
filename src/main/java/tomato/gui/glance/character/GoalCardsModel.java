package tomato.gui.glance.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.RosterDefinitions;
import tomato.planning.CharacterGoals;
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
import tomato.realmshark.enums.CharacterClass;

/**
 * Sheet › Goals as cards (spec §6.2; user decision "Goals show this character's cards"): this character's stat goals and this
 * class's exalt goals from the sheet's own account's saved plan, nothing else. Progress is CharacterGoals' own (the Manage goals
 * table's numbers, never new analytics); an unknown remaining stays null with the reason as its text, never 0. Pure.
 */
final class GoalCardsModel {
    private GoalCardsModel() {}

    /**
     * One goal. {@code key} is unique within one sheet ("stat-3", "exalt-0": a character has at most one goal per stat, a class one
     * exalt goal per stat). {@code current}/{@code goal}: base toward the fixed target, or completions toward the tier's threshold;
     * {@code remaining} null = unknown (then {@code remainingText} is the reason). {@code state} is CharacterGoals' state verbatim
     * ("Complete; fixed target retained": targets never advance). {@code earnIn}: where the exalt stat is earned, "" when the
     * dungeon mapping does not name it (and for stat goals).
     */
    record GoalCard(String key, Kind kind, String title, String target, Integer current, Integer goal, Integer remaining,
                    String remainingText, String state, boolean reconfirm, String earnIn, long updatedAt) {
        enum Kind { STAT, EXALT }
    }

    /**
     * The cards for {@code record}: stat goals whose character key is the record's key, then exalt goals of the record's class,
     * each in canonical stat order. {@code plan} is the saved plan of the record's account (null: not read yet, no cards);
     * {@code account} supplies exalt completions only when it is the record's own account. Malformed goals (a stat outside 0–7,
     * a tier outside 1–5; PlanningStore never saves one) are skipped, never thrown.
     */
    static List<GoalCard> build(CharacterRecord record, PlanData.AccountPlan plan, AccountRecord account, RosterDefinitions defs,
                                PlanningMetadata metadata) {
        if (record == null || plan == null) return List.of();
        AccountRecord own = account != null && Objects.equals(account.key, record.account) ? account : null;
        TreeMap<Integer, GoalCard> stats = new TreeMap<>(), exalts = new TreeMap<>();
        if (plan.characterGoals != null) for (PlanData.CharacterGoal g : plan.characterGoals.values()) {
            if (g == null || !Objects.equals(g.characterKey, record.key) || !stat(g.statIndex)) continue;
            CharacterGoals.Progress p = CharacterGoals.character(g, record, defs);
            String name = CharacterJournal.STATS[g.statIndex];
            stats.putIfAbsent(g.statIndex, new GoalCard("stat-" + g.statIndex, GoalCard.Kind.STAT, name + " → " + g.targetBaseValue + " base",
                g.targetBaseValue + " base", record.stats[g.statIndex], g.targetBaseValue, p.remaining, remaining(p, "potion"), p.state,
                p.metadataChanged, "", g.updatedAt));
        }
        if (plan.exaltGoals != null) for (PlanData.ExaltGoal g : plan.exaltGoals.values()) {
            if (g == null || g.classId != record.classId || !stat(g.statIndex) || g.targetTier < 1 || g.targetTier > 5) continue;
            Integer count = count(own, g.classId, g.statIndex);
            CharacterGoals.Progress p = CharacterGoals.exalt(g, count, metadata);
            int threshold = PlanningMetadata.threshold(g.targetTier);
            exalts.putIfAbsent(g.statIndex, new GoalCard("exalt-" + g.statIndex, GoalCard.Kind.EXALT,
                CharacterJournal.STATS[g.statIndex] + " exalt tier " + g.targetTier, "Tier " + g.targetTier + " · " + threshold + " completions",
                count, threshold, p.remaining, remaining(p, "completion"), p.state, p.metadataChanged,
                String.join(", ", metadata.dungeons(g.statIndex)), g.updatedAt));
        }
        List<GoalCard> cards = new ArrayList<>(stats.values());
        cards.addAll(exalts.values());
        return List.copyOf(cards);
    }

    /** "Goals for Wizard #7": the character's class and id, as the Manage goals table names it. */
    static String title(CharacterRecord record) {
        String kind = record.className != null && !record.className.isBlank() ? record.className : CharacterClass.getName(record.classId);
        return "Goals for " + (kind == null || kind.isBlank() ? "Class #" + record.classId : kind) + " #" + record.characterId;
    }

    /** "5 potions to go", "1 completion to go", "Complete", or (unknown remaining) CharacterGoals' reason, never a 0. */
    private static String remaining(CharacterGoals.Progress p, String unit) {
        if (p.remaining == null) return p.state;
        if (p.remaining == 0) return "Complete";
        return p.remaining + " " + unit + (p.remaining == 1 ? "" : "s") + " to go";
    }

    /** The account's completions for {@code stat} of {@code classId} (counts are stored in RealmCharacter order); null = unknown. */
    private static Integer count(AccountRecord account, int classId, int stat) {
        int[] counts = account == null || account.exalts == null ? null : account.exalts.get(classId);
        int index = CharacterJournal.EXALT_ORDER[stat];
        return counts == null || counts.length <= index || counts[index] < 0 ? null : counts[index];
    }

    private static boolean stat(int index) { return index >= 0 && index < 8; }
}
