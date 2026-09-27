package tomato.gui.glance.home;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.*;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.*;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** Recent runs: outcome badges, linked-only DPS, loot, exact-visit click-through, keyboard, states and Evidence. */
public class RecentRunsCardTest {
    private static final long NOW = 1_790_000_000_000L;
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
    private final List<VisitRef> opened = new ArrayList<>();
    private Locale previous;
    @Before public void usFormat() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restoreFormat() { Locale.setDefault(Locale.Category.FORMAT, previous); }
    private RecentRunsCard populated() {
        RecentRunsCard card = new RecentRunsCard(opened::add, mode);
        card.apply(new HomeModel.Runs(HomeModel.State.LIVE, HomeModels.runs(NOW), null), NOW);
        return card;
    }
    @Test public void rowsShowOutcomeTimeLinkedDpsAndLoot() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecentRunsCard card = populated();
            for (int i = 0; i < HomeArchive.RECENT; i++) assertTrue(named(card, "home-run-" + i, JPanel.class).isVisible());
            assertEquals("Lost Halls", named(card, "home-run-0-map", JLabel.class).getText());
            Chip completed = named(card, "home-run-0-outcome", Chip.class);
            assertEquals("Completed", completed.getText());
            assertEquals(Tokens.Tone.GOOD, completed.tone());
            assertEquals("12 min ago", named(card, "home-run-0-when", JLabel.class).getText());
            assertEquals("DPS 3.1k", named(card, "home-run-0-dps", JLabel.class).getText());
            for (int i = 0; i < RecentRunsCard.LOOT; i++) assertTrue("Up to eight loot sprites", named(card, "home-run-0-loot-" + i, ItemSlot.class).isVisible());
            Chip left = named(card, "home-run-1-outcome", Chip.class);
            assertEquals("Left", left.getText());
            assertEquals(Tokens.Tone.NEUTRAL, left.tone());
            assertEquals("The full outcome stays in the tooltip", "Left · completion unconfirmed", left.getToolTipText());
            assertFalse("No linked recording, no DPS", named(card, "home-run-1-dps", JLabel.class).isVisible());
            assertFalse(named(card, "home-run-1-loot-1", ItemSlot.class).isVisible());
            JPanel row = named(card, "home-run-0", JPanel.class);
            assertTrue(row.isFocusable());
            assertEquals(AccessibleRole.PUSH_BUTTON, row.getAccessibleContext().getAccessibleRole());
            assertEquals("Lost Halls, Completed, DPS 3.1k, 8 loot items. Open run", row.getAccessibleContext().getAccessibleName());
            assertEquals("The ticking age is the description", "12 min ago", row.getAccessibleContext().getAccessibleDescription());
            assertEquals("Sprite World, Left · completion unconfirmed, no linked loot. Open run",
                named(card, "home-run-4", JPanel.class).getAccessibleContext().getAccessibleName());
            assertEquals("Recent runs: 5 runs", card.getAccessibleContext().getAccessibleName());
        });
    }
    @Test public void enterSpaceAndClicksOpenTheExactRun() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecentRunsCard card = populated();
            JPanel row = named(card, "home-run-0", JPanel.class);
            for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE})
                row.getActionMap().get(row.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, 0))).actionPerformed(null);
            assertEquals(List.of(new VisitRef(HomeModels.SESSION, "visit-5"), new VisitRef(HomeModels.SESSION, "visit-5")), opened);
            ItemSlot slot = named(card, "home-run-3-loot-0", ItemSlot.class);
            slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1));
            assertEquals("A click on a loot sprite opens that row's run", new VisitRef(HomeModels.SESSION, "visit-2"), opened.get(2));
        });
    }
    @Test public void aFailedReReadKeepsTheRowsUnderAWarnBanner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecentRunsCard card = new RecentRunsCard(opened::add, mode);
            card.apply(new HomeModel.Runs(HomeModel.State.STALE, HomeModels.runs(NOW), "Last updated 5 min ago · disk full"), NOW);
            HomeViews.Reason note = named(card, "home-runs-note", HomeViews.Reason.class);
            assertTrue(note.isVisible()); assertTrue(note.warns());
            assertEquals("Last updated 5 min ago · disk full", note.text());
            assertTrue("The last rows stay", named(card, "home-run-0", JPanel.class).isVisible());
            assertEquals("Recent runs: 5 runs, last successful read", card.getAccessibleContext().getAccessibleName());
            card.apply(new HomeModel.Runs(HomeModel.State.LIVE, HomeModels.runs(NOW), null), NOW);
            assertFalse(note.isVisible());
        });
    }
    @Test public void inProgressFewerRowsStatesAndEvidence() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecentRunsCard card = new RecentRunsCard(opened::add, mode);
            assertEquals(HomeViews.LOADING, card.statusText());
            card.apply(new HomeModel.Runs(HomeModel.State.LIVE, List.of(new HomeArchive.RecentRun(new VisitRef(HomeModels.SESSION, "visit-9"),
                "Lost Halls", "In progress", NOW - 300_000L, null, List.of(), null)), null), NOW);
            Chip outcome = named(card, "home-run-0-outcome", Chip.class);
            assertEquals("In progress", outcome.getText());
            assertEquals(Tokens.Tone.ACCENT, outcome.tone());
            assertEquals("An unfinished run is timed from its start", "5 min ago", named(card, "home-run-0-when", JLabel.class).getText());
            assertFalse(named(card, "home-run-1", JPanel.class).isVisible());
            card.apply(HomeModels.empty().runs(), NOW);
            assertNotNull(named(card, "home-runs-empty", EmptyState.class));
            card.apply(HomeModels.unavailable().runs(), NOW);
            assertEquals("Saved history is not available: the history folder could not be opened.", card.statusText());
            assertTrue("Unavailable is a warn banner", card.statusWarns());
            assertFalse(card.evidenceShown());
            assertTrue(named(card, "evidence-note", JTextArea.class).getText().contains("never matched by dungeon name or time"));
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(card.evidenceShown());
        });
    }
}
