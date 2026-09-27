package tomato.gui.glance.character;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.gui.glance.character.GoalCardsModel.GoalCard;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Sheet › Goals: one card per goal with its progress and chips, the empty state, and Manage goals below (collapsed in Simple). */
public class GoalCardsTest {
    private static final String MANAGE = "ui.collapse.character-goals-manage";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> modeStore = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(modeStore::get, modeStore::put); // never the application's ui.mode
    private String savedManage;

    @Before public void remember() { savedManage = PropertiesManager.getProperty(MANAGE); PropertiesManager.setProperties(MANAGE, ""); }
    @After public void restore() { PropertiesManager.setProperties(MANAGE, savedManage == null ? "" : savedManage); }

    private static String text(JComponent root, String name) { return named(root, name, JTextArea.class).getText(); }
    private static boolean shows(JComponent root, String name) { JComponent c = named(root, name, JComponent.class); return c.isVisible(); }
    private static java.awt.Component search(java.awt.Container root, String name) {
        for (java.awt.Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof java.awt.Container) { java.awt.Component found = search((java.awt.Container) child, name); if (found != null) return found; }
        }
        return null;
    }

    private List<GoalCard> cards() throws Exception {
        PlanningMetadata mapped = dungeonMapping(temp.getRoot().toPath());
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        statGoal(plan, KEY, 3, 25, defs(), NOW);  // DEF 20 → 25: 5 potions, near complete
        statGoal(plan, KEY, 0, 720, defs(), NOW); // Life at 720: complete
        exaltGoal(plan, WIZARD, 0, 2, PlanningMetadata.unavailable(), NOW); // pinned before the mapping loaded: reconfirm
        exaltGoal(plan, WIZARD, 3, 1, mapped, NOW); // DEF is not mapped: earn-in unknown
        return GoalCardsModel.build(record(), plan, account(10, 0, 0, 2, 0, 0, 0, 0), defs(), mapped);
    }

    @Test public void eachGoalIsACardWithItsProgressAndChips() throws Exception {
        List<GoalCard> cards = cards();
        SwingUtilities.invokeAndWait(() -> {
            GoalCards tab = new GoalCards(mode, new JPanel());
            assertEquals("character-goals", tab.getName());
            tab.apply("Goals for Wizard #7", cards, null);
            assertEquals("Goals for Wizard #7", named(tab, "character-goals-title", JLabel.class).getText());
            assertFalse(shows(tab, "character-goals-empty"));
            assertTrue(shows(tab, "character-goals-cards"));
            assertEquals(4, named(tab, "character-goals-cards", JPanel.class).getComponentCount());

            Card def = named(tab, "character-goal-stat-3", Card.class);
            assertEquals("Defense → 25 base", text(tab, "character-goal-stat-3-title"));
            assertEquals("Base 20 of 25", named(tab, "character-goal-stat-3-progress", JLabel.class).getText());
            StatBar bar = named(tab, "character-goal-stat-3-bar", StatBar.class);
            assertTrue(bar.isVisible());
            assertEquals("StatBar.set(current, goal)", "20 of 25", bar.getToolTipText());
            assertEquals("5 potions to go", text(tab, "character-goal-stat-3-remaining"));
            Chip state = named(tab, "character-goal-stat-3-state", Chip.class);
            assertTrue(state.isVisible()); assertEquals("Near complete", state.getText()); assertEquals(Tokens.Tone.NEUTRAL, state.tone());
            assertFalse("Matching metadata: no reconfirm chip", shows(tab, "character-goal-stat-3-reconfirm"));
            assertNull("A stat goal has no earn-in line", search(tab, "character-goal-stat-3-earn"));

            Chip complete = named(tab, "character-goal-stat-0-state", Chip.class);
            assertEquals("Complete", complete.getText());
            assertEquals(Tokens.Tone.GOOD, complete.tone());
            assertEquals("Targets never advance: the full state stays with the chip", "Complete; fixed target retained", complete.getToolTipText());
            assertEquals("Complete; fixed target retained", complete.getAccessibleContext().getAccessibleDescription());
            assertTrue(named(tab, "character-goal-stat-0-bar", StatBar.class).maxed());

            assertEquals("5 completions to go", text(tab, "character-goal-exalt-0-remaining"));
            assertEquals("10 of 15 completions", named(tab, "character-goal-exalt-0-progress", JLabel.class).getText());
            assertEquals("10 of 15", named(tab, "character-goal-exalt-0-bar", StatBar.class).getToolTipText());
            Chip reconfirm = named(tab, "character-goal-exalt-0-reconfirm", Chip.class);
            assertTrue(reconfirm.isVisible()); assertEquals("Reconfirm target", reconfirm.getText()); assertEquals(Tokens.Tone.WARN, reconfirm.tone());
            assertEquals("Earn in: Fixture Vault, Second Vault", named(tab, "character-goal-exalt-0-earn", JTextArea.class).getText());
            JTextArea unmapped = named(tab, "character-goal-exalt-3-earn", JTextArea.class);
            assertEquals("Unmapped: unknown, never guessed", "Earn in: " + DisplayFormat.UNAVAILABLE, unmapped.getText());
            assertNotNull("…with the reason", unmapped.getToolTipText());
            assertTrue(def.getAccessibleContext().getAccessibleName(), def.getAccessibleContext().getAccessibleName().contains("5 potions to go"));
            assertFalse("A read-only card takes no focus", def.isFocusable());
            assertFalse(named(tab, "character-goal-stat-3-remaining", JTextArea.class).isFocusable());

            tab.apply("Goals for Wizard #7", new java.util.ArrayList<>(cards), null); // an equal list, not the same one
            assertSame("Equal cards are not rebuilt", def, named(tab, "character-goal-stat-3", Card.class));
        });
    }

    @Test public void unknownRemainingDrawsNoBarAndNeverReadsZero() throws Exception {
        CharacterRecord record = record(); record.stats[3] = null;
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        statGoal(plan, KEY, 3, 25, defs(), NOW);
        exaltGoal(plan, WIZARD, 0, 2, PlanningMetadata.unavailable(), NOW);
        List<GoalCard> cards = GoalCardsModel.build(record, plan, null, defs(), PlanningMetadata.unavailable()); // no saved exalt counts
        SwingUtilities.invokeAndWait(() -> {
            GoalCards tab = new GoalCards(mode, new JPanel());
            tab.apply("Goals for Wizard #7", cards, null);
            assertFalse("Unknown remaining: no bar that could read as 0%", shows(tab, "character-goal-stat-3-bar"));
            assertEquals("Unknown: base not captured", text(tab, "character-goal-stat-3-remaining"));
            assertEquals("Base " + DisplayFormat.UNAVAILABLE + " of 25", named(tab, "character-goal-stat-3-progress", JLabel.class).getText());
            assertFalse(shows(tab, "character-goal-exalt-0-bar"));
            assertEquals("Unknown: completions not captured", text(tab, "character-goal-exalt-0-remaining"));
            assertEquals(DisplayFormat.UNAVAILABLE + " of 15 completions", named(tab, "character-goal-exalt-0-progress", JLabel.class).getText());
            for (String key : new String[]{"stat-3", "exalt-0"}) {
                assertFalse("The remaining line already says it; no duplicate state chip", shows(tab, "character-goal-" + key + "-state"));
                assertFalse(text(tab, "character-goal-" + key + "-remaining").matches(".*\\b0\\b.*"));
            }
        });
    }

    @Test public void noGoalsShowTheEmptyStateLoadingShowsNothingAndAnUnreadyStoreSaysSo() throws Exception {
        List<GoalCard> cards = cards();
        SwingUtilities.invokeAndWait(() -> {
            GoalCards tab = new GoalCards(mode, new JPanel());
            assertFalse("Before anything applies: no empty state", shows(tab, "character-goals-empty"));
            tab.apply("Goals for Wizard #7", List.of(), null);
            EmptyState empty = named(tab, "character-goals-empty", EmptyState.class);
            assertTrue(empty.isVisible());
            assertEquals("No goals for this character", empty.getAccessibleContext().getAccessibleName());
            assertEquals("Add goals under Manage goals.", empty.getAccessibleContext().getAccessibleDescription());
            assertFalse(shows(tab, "character-goals-cards"));
            assertFalse(shows(tab, "character-goals-status"));
            tab.apply("Goals for Wizard #7", cards, null);
            assertFalse(empty.isVisible());
            assertEquals(4, named(tab, "character-goals-cards", JPanel.class).getComponentCount());
            tab.apply("Goals", null, null); // the sheet is loading its character
            assertFalse(empty.isVisible()); assertFalse(shows(tab, "character-goals-cards"));
            assertEquals(0, named(tab, "character-goals-cards", JPanel.class).getComponentCount());
            tab.apply("Goals for Wizard #7", List.of(), "Loading local plans…"); // goals unknown until the store has read them
            assertFalse("Not yet known is never \"no goals\"", empty.isVisible());
            assertTrue(shows(tab, "character-goals-status"));
            assertEquals("Loading local plans…", named(tab, "character-goals-status", JTextArea.class).getText());
        });
    }

    @Test public void manageGoalsIsCollapsedInSimpleAndShownBelowTheCardsInAnalyst() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel manage = new JPanel(); manage.setName("manage-fixture");
            GoalCards tab = new GoalCards(mode, manage);
            tab.apply("Goals for Wizard #7", List.of(), null);
            Collapsible section = named(tab, "character-goals-manage", Collapsible.class);
            assertTrue(section.isVisible());
            assertEquals("Manage goals", section.toggle().getText());
            assertEquals("collapsible-character-goals-manage", section.toggle().getName());
            assertFalse("Simple: collapsed by default", section.expanded());
            assertTrue(SwingUtilities.isDescendingFrom(manage, section));
            assertFalse("…so the panel is not shown", visibleUpTo(manage, tab));
            assertFalse(shows(tab, "character-goals-manage-panel"));

            mode.set(DisplayModeModel.Mode.ANALYST);
            assertFalse("Analyst: no collapsible section", section.isVisible());
            assertFalse(SwingUtilities.isDescendingFrom(manage, section));
            assertTrue(SwingUtilities.isDescendingFrom(manage, named(tab, "character-goals-manage-panel", JComponent.class)));
            assertTrue("…the panel shows expanded below the cards", visibleUpTo(manage, tab));
            assertEquals("Switching modes never writes the Simple section's choice", "", PropertiesManager.getProperty(MANAGE));

            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertTrue(section.isVisible());
            assertTrue(SwingUtilities.isDescendingFrom(manage, section));
            assertFalse("Simple keeps its own (collapsed) choice", section.expanded());
            assertFalse(visibleUpTo(manage, tab));
            section.toggle().doClick(); // opening shows the content at once (the motion only grows its height)
            assertTrue(section.expanded());
            assertTrue(visibleUpTo(manage, tab));
            assertEquals("true", PropertiesManager.getProperty(MANAGE));

            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(visibleUpTo(manage, tab));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertTrue("The opened Simple section stays open across a mode switch", section.expanded());
            assertTrue(visibleUpTo(manage, tab));
            assertEquals("true", PropertiesManager.getProperty(MANAGE));
        });
    }

    /** True when {@code c} and every ancestor up to {@code root} are visible (no frame needed). */
    private static boolean visibleUpTo(java.awt.Component c, java.awt.Component root) {
        for (java.awt.Component at = c; at != null; at = at.getParent()) { if (!at.isVisible()) return false; if (at == root) return true; }
        return false;
    }
}
