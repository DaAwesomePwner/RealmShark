package tomato.gui.glance.character;

import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.character.GoalCardsModel.GoalCard;
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/**
 * The Goals tab's cards: only this character's stat goals and this class's exalt goals of the sheet's own account, with progress
 * from CharacterGoals (never invented), unknown remaining never 0, and stat goals before exalt goals in canonical stat order.
 */
public class GoalCardsModelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static List<String> keys(List<GoalCard> cards) { List<String> keys = new ArrayList<>(); for (GoalCard c : cards) keys.add(c.key()); return keys; }
    private static GoalCard card(List<GoalCard> cards, String key) {
        for (GoalCard c : cards) if (c.key().equals(key)) return c;
        throw new AssertionError("No card " + key + " in " + keys(cards));
    }

    @Test public void onlyThisCharactersStatGoalsAndThisClassesExaltGoals() {
        RosterDefinitions defs = defs();
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        statGoal(plan, KEY, 3, 25, defs, NOW);            // Wizard #7 DEF
        statGoal(plan, ACCOUNT + ":8", 3, 25, defs, NOW); // another character of the same account: not this sheet's
        exaltGoal(plan, WIZARD, 0, 2, PlanningMetadata.unavailable(), NOW);
        exaltGoal(plan, PRIEST, 0, 2, PlanningMetadata.unavailable(), NOW); // another class: not this sheet's
        List<GoalCard> cards = GoalCardsModel.build(record(), plan, account(10, 0, 0, 0, 0, 0, 0, 0), defs, PlanningMetadata.unavailable());
        assertEquals(List.of("stat-3", "exalt-0"), keys(cards));
        assertEquals(GoalCard.Kind.STAT, cards.get(0).kind());
        assertEquals(GoalCard.Kind.EXALT, cards.get(1).kind());
        assertTrue("No goals: no cards", GoalCardsModel.build(record(), new PlanData.AccountPlan(), null, defs, PlanningMetadata.unavailable()).isEmpty());
        assertTrue("No plan (loading): no cards", GoalCardsModel.build(record(), null, null, defs, PlanningMetadata.unavailable()).isEmpty());
    }

    @Test public void statProgressComesFromCharacterGoals() {
        RosterDefinitions defs = defs(); // Wizard caps 720, 252, 75, 25, 50, 75, 40, 60; record base DEF 20, VIT 37, Life 720
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        statGoal(plan, KEY, 3, 25, defs, NOW - HOUR);
        statGoal(plan, KEY, 6, 38, defs, NOW);
        statGoal(plan, KEY, 0, 720, defs, NOW);
        List<GoalCard> cards = GoalCardsModel.build(record(), plan, null, defs, PlanningMetadata.unavailable());
        GoalCard def = card(cards, "stat-3");
        assertEquals("Defense → 25 base", def.title());
        assertEquals("25 base", def.target());
        assertEquals(Integer.valueOf(20), def.current());
        assertEquals(Integer.valueOf(25), def.goal());
        assertEquals(Integer.valueOf(5), def.remaining());
        assertEquals("5 potions to go", def.remainingText());
        assertEquals("Near complete", def.state());
        assertFalse(def.reconfirm());
        assertEquals("", def.earnIn());
        assertEquals(NOW - HOUR, def.updatedAt());
        assertEquals("1 potion to go", card(cards, "stat-6").remainingText());
        assertEquals("In progress", GoalCardsModel.build(record(), statOnly(KEY, 7, 60, defs), null, defs, PlanningMetadata.unavailable()).get(0).state());
        GoalCard life = card(cards, "stat-0");
        assertEquals(Integer.valueOf(0), life.remaining());
        assertEquals("Complete", life.remainingText());
        assertEquals("Targets never advance: the saved state says so", "Complete; fixed target retained", life.state());
    }

    @Test public void unknownBaseOrCapIsNeverZero() {
        RosterDefinitions defs = defs();
        CharacterRecord record = record(); record.stats[3] = null;
        GoalCard base = GoalCardsModel.build(record, statOnly(KEY, 3, 25, defs), null, defs, PlanningMetadata.unavailable()).get(0);
        assertNull(base.remaining());
        assertNull(base.current());
        assertEquals(Integer.valueOf(25), base.goal());
        assertEquals("Unknown: base not captured", base.remainingText());
        assertEquals("Unknown: base not captured", base.state());
        GoalCard cap = GoalCardsModel.build(record(), statOnly(KEY, 3, 25, defs), null, RosterDefinitions.empty(), PlanningMetadata.unavailable()).get(0);
        assertNull(cap.remaining());
        assertEquals("Unknown: cap unavailable", cap.remainingText());
        assertTrue("Without caps the saved cap version no longer matches", cap.reconfirm());
    }

    @Test public void aTargetAboveTheCurrentCapAsksToReconfirm() {
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        PlanData.CharacterGoal goal = new PlanData.CharacterGoal(); // saved when DEF's cap was higher
        goal.characterKey = KEY; goal.statIndex = 3; goal.targetBaseValue = 30; goal.metadataVersion = PlanningMetadata.capVersion(defs(), WIZARD);
        goal.createdAt = goal.updatedAt = NOW;
        plan.characterGoals.put(PlanData.characterGoalKey(KEY, 3), goal);
        GoalCard card = GoalCardsModel.build(record(), plan, null, defs(), PlanningMetadata.unavailable()).get(0);
        assertNull(card.remaining());
        assertEquals("Saved target exceeds current cap; reconfirm", card.remainingText());
        assertTrue(card.reconfirm());
        assertEquals(Integer.valueOf(20), card.current());
        assertEquals(Integer.valueOf(30), card.goal());
    }

    @Test public void exaltRemainingAndWhereToEarnIt() throws Exception {
        PlanningMetadata mapped = dungeonMapping(temp.getRoot().toPath()); // Life: Fixture Vault, Second Vault; no other stat
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        exaltGoal(plan, WIZARD, 0, 2, mapped, NOW);
        exaltGoal(plan, WIZARD, 2, 1, mapped, NOW);
        List<GoalCard> cards = GoalCardsModel.build(record(), plan, account(10, 0, 5, 0, 0, 0, 0, 0), defs(), mapped);
        GoalCard life = card(cards, "exalt-0");
        assertEquals("Life exalt tier 2", life.title());
        assertEquals("Tier 2 · 15 completions", life.target());
        assertEquals(Integer.valueOf(10), life.current());
        assertEquals(Integer.valueOf(15), life.goal());
        assertEquals(Integer.valueOf(5), life.remaining());
        assertEquals("5 completions to go", life.remainingText());
        assertEquals("Fixture Vault, Second Vault", life.earnIn());
        assertFalse(life.reconfirm());
        GoalCard attack = card(cards, "exalt-2");
        assertEquals("Complete", attack.remainingText());
        assertEquals("Unmapped: empty, never guessed", "", attack.earnIn());
        GoalCard unknown = GoalCardsModel.build(record(), plan, null, defs(), mapped).get(0);
        assertNull("No saved exalt counts: unknown, never 0", unknown.remaining());
        assertNull(unknown.current());
        assertEquals("Unknown: completions not captured", unknown.remainingText());
        AccountRecord other = account(10, 0, 5, 0, 0, 0, 0, 0); other.key = "0".repeat(64);
        assertNull("Another account's counts are never used", GoalCardsModel.build(record(), plan, other, defs(), mapped).get(0).current());
    }

    @Test public void metadataChangesAskToReconfirm() throws Exception {
        PlanningMetadata mapped = dungeonMapping(temp.getRoot().toPath());
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        exaltGoal(plan, WIZARD, 0, 2, PlanningMetadata.unavailable(), NOW); // pinned before the mapping loaded
        PlanData.CharacterGoal stat = new PlanData.CharacterGoal();
        stat.characterKey = KEY; stat.statIndex = 3; stat.targetBaseValue = 25; stat.metadataVersion = "local-caps-v1:782:older";
        plan.characterGoals.put(PlanData.characterGoalKey(KEY, 3), stat);
        List<GoalCard> cards = GoalCardsModel.build(record(), plan, account(10, 0, 0, 0, 0, 0, 0, 0), defs(), mapped);
        assertTrue(card(cards, "stat-3").reconfirm());
        assertEquals("Progress is still shown", Integer.valueOf(5), card(cards, "stat-3").remaining());
        assertTrue(card(cards, "exalt-0").reconfirm());
        assertFalse(card(GoalCardsModel.build(record(), plan, account(10, 0, 0, 0, 0, 0, 0, 0), defs(), PlanningMetadata.unavailable()), "exalt-0").reconfirm());
    }

    @Test public void statGoalsComeFirstThenExaltGoalsEachInCanonicalStatOrder() {
        RosterDefinitions defs = defs();
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        exaltGoal(plan, WIZARD, 7, 1, PlanningMetadata.unavailable(), NOW);
        statGoal(plan, KEY, 6, 40, defs, NOW);
        exaltGoal(plan, WIZARD, 1, 1, PlanningMetadata.unavailable(), NOW);
        statGoal(plan, KEY, 0, 720, defs, NOW);
        statGoal(plan, KEY, 3, 25, defs, NOW);
        assertEquals(List.of("stat-0", "stat-3", "stat-6", "exalt-1", "exalt-7"),
            keys(GoalCardsModel.build(record(), plan, null, defs, PlanningMetadata.unavailable())));
    }

    @Test public void equalInputsMakeEqualCardsAndTheTitleNamesTheCharacter() {
        RosterDefinitions defs = defs();
        PlanData.AccountPlan plan = statOnly(KEY, 3, 25, defs);
        assertEquals(GoalCardsModel.build(record(), plan, null, defs, PlanningMetadata.unavailable()),
            GoalCardsModel.build(record(), PlanData.copy(plan), null, defs, PlanningMetadata.unavailable()));
        assertEquals("Goals for Wizard #7", GoalCardsModel.title(record()));
        CharacterRecord unnamed = record(); unnamed.className = null;
        assertTrue(GoalCardsModel.title(unnamed), GoalCardsModel.title(unnamed).endsWith(" #7"));
    }

    private static PlanData.AccountPlan statOnly(String key, int stat, int target, RosterDefinitions defs) {
        PlanData.AccountPlan plan = new PlanData.AccountPlan(); statGoal(plan, key, stat, target, defs, NOW); return plan;
    }
}
