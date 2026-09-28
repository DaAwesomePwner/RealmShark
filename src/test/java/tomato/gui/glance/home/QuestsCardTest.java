package tomato.gui.glance.home;

import java.util.*;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.*;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** Quests card: pinned rows with rewards, counts, list age, stale state, click-through, states and Evidence. */
public class QuestsCardTest {
    private static final long NOW = 1_790_000_000_000L;
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
    private final int[] opened = {0};
    @Test public void pinnedQuestsShowRewardsCountsAndAge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestsCard card = new QuestsCard(() -> opened[0]++, mode);
            card.apply(HomeModels.quests(NOW, false), NOW);
            assertEquals("3 pinned · 5 repeatable · 2 done", named(card, "home-quests-counts", JLabel.class).getText());
            assertEquals("Mighty Lost Halls", named(card, "home-quest-0-name", JLabel.class).getText());
            assertTrue(named(card, "home-quest-0-kind", Chip.class).getText().endsWith("Repeatable"));
            assertEquals("One-time", named(card, "home-quest-1-kind", Chip.class).getText());
            for (int i = 0; i < QuestsCard.REWARDS; i++)
                assertEquals(ItemSlot.State.ITEM, named(card, "home-quest-0-reward-" + i, ItemSlot.class).state());
            JLabel more = named(card, "home-quest-0-more", JLabel.class);
            assertTrue(more.isVisible());
            assertEquals("+2", more.getText());
            assertFalse(named(card, "home-quest-1-reward-1", ItemSlot.class).isVisible());
            assertFalse(named(card, "home-quest-0-done", Chip.class).isVisible());
            assertTrue(named(card, "home-quest-2-done", Chip.class).isVisible());
            assertEquals("Captured 14 min ago", named(card, "home-quests-age", JLabel.class).getText());
            assertFalse(named(card, "home-quests-stale", Chip.class).isVisible());
            assertFalse(named(card, "home-quests-none", JLabel.class).isVisible());
            assertEquals("Quests: 3 pinned, 5 repeatable, 2 done. Open quests", card.getAccessibleContext().getAccessibleName());
            assertEquals("The list's age ticks: it is the description", "Captured 14 min ago", card.getAccessibleContext().getAccessibleDescription());
            card.getActionMap().get("open-card").actionPerformed(null);
            assertEquals(1, opened[0]);
        });
    }
    @Test public void staleListsAndNoPinsAreSaidPlainly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestsCard card = new QuestsCard(() -> {}, mode);
            card.apply(HomeModels.quests(NOW, true), NOW);
            assertTrue(named(card, "home-quests-stale", Chip.class).isVisible());
            assertEquals("Captured 3 h ago", named(card, "home-quests-age", JLabel.class).getText());
            assertTrue(card.getAccessibleContext().getAccessibleName().contains("may be out of date"));
            card.apply(new HomeModel.Quests(HomeModel.State.LIVE, 0, 4, 1, List.of(), NOW - 60_000L, false), NOW);
            assertTrue(named(card, "home-quests-none", JLabel.class).isVisible());
            assertEquals("No pinned quests · Pin quests on the Quests page", named(card, "home-quests-none", JLabel.class).getText());
            assertEquals("The counts stay", "0 pinned · 4 repeatable · 1 done", named(card, "home-quests-counts", JLabel.class).getText());
            for (int i = 0; i < QuestsCard.SHOWN; i++) assertFalse(named(card, "home-quest-" + i, JPanel.class).isVisible());
        });
    }
    /** Spec §1: rewards that were not captured are said so (muted), never an empty strip or "no rewards listed". */
    @Test public void uncapturedRewardsAreToldApartFromNone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestsCard card = new QuestsCard(() -> {}, mode);
            card.apply(HomeModels.questsWithUncapturedRewards(NOW), NOW);
            KitText unknown = named(card, "home-quest-0-rewards-unknown", KitText.class);
            assertTrue(unknown.isVisible());
            assertEquals("Rewards not captured", unknown.getText());
            assertEquals("Muted, as other not-captured text", Tokens.Role.TEXT_MUTED, unknown.role());
            assertNotNull("It says why", unknown.getToolTipText());
            for (int i = 0; i < QuestsCard.REWARDS; i++) assertFalse(named(card, "home-quest-0-reward-" + i, ItemSlot.class).isVisible());
            assertFalse(named(card, "home-quest-0-more", JLabel.class).isVisible());
            assertEquals("Oryx's Castle, one-time, rewards not captured", named(card, "home-quest-0", JPanel.class).getAccessibleContext().getAccessibleName());
            assertFalse("A known empty list is not 'not captured'", named(card, "home-quest-1-rewards-unknown", KitText.class).isVisible());
            assertEquals("A known empty list keeps today's wording", "Pirate Cave, repeatable, no rewards listed",
                named(card, "home-quest-1", JPanel.class).getAccessibleContext().getAccessibleName());
            assertFalse(named(card, "home-quest-2-rewards-unknown", KitText.class).isVisible());
            assertTrue(named(card, "home-quest-2-reward-0", ItemSlot.class).isVisible());
            // The unchanged-model skip compares rewardsKnown: a line that differs only there is applied.
            List<HomeModel.QuestLine> lines = new ArrayList<>(HomeModels.questsWithUncapturedRewards(NOW).top());
            lines.set(0, new HomeModel.QuestLine("Oryx's Castle", new int[0], true, false, false));
            card.apply(new HomeModel.Quests(HomeModel.State.LIVE, 3, 2, 0, lines, NOW - 840_000L, false), NOW);
            assertFalse(named(card, "home-quest-0-rewards-unknown", KitText.class).isVisible());
            assertEquals("Oryx's Castle, one-time, no rewards listed", named(card, "home-quest-0", JPanel.class).getAccessibleContext().getAccessibleName());
            card.apply(HomeModels.questsWithUncapturedRewards(NOW), NOW);
            assertTrue(named(card, "home-quest-0-rewards-unknown", KitText.class).isVisible());
        });
    }
    @Test public void loadingEmptyUnavailableAndEvidence() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestsCard card = new QuestsCard(() -> {}, mode);
            assertEquals(HomeViews.LOADING, card.statusText());
            card.apply(HomeModels.empty().quests(), NOW);
            assertNotNull(named(card, "home-quests-empty", EmptyState.class));
            card.apply(HomeModels.unavailable().quests(), NOW);
            assertEquals("Quest data is unavailable right now.", card.statusText());
            assertTrue("Unavailable is a warn banner", card.statusWarns());
            card.apply(HomeModels.quests(NOW, false), NOW);
            assertFalse(card.evidenceShown());
            assertTrue(named(card, "evidence-note", JTextArea.class).getText().endsWith("Expiry countdowns are not shown until the expiry format is confirmed."));
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(card.evidenceShown());
        });
    }
}
