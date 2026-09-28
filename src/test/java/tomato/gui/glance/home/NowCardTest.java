package tomato.gui.glance.home;

import java.time.Instant;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import tomato.gui.dps.MeterSummary;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.*;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** Now card: run versus outside-a-run content, your row and rank, the key pop line, states and Evidence. */
public class NowCardTest {
    private static final long NOW = 1_790_000_000_000L;
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
    private Locale previous;
    @Before public void usFormat() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restoreFormat() { Locale.setDefault(Locale.Category.FORMAT, previous); }
    @Test public void inARunShowsTheTopThreeYourRowYourRankAndTheLastPop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] opened = {0};
            NowCard card = new NowCard(() -> opened[0]++, mode);
            card.apply(HomeModels.now(NOW), NOW);
            assertEquals("Lost Halls", named(card, "home-now-area", JLabel.class).getText());
            assertEquals("12m 30s", named(card, "home-now-elapsed", JLabel.class).getText());
            Chip capture = named(card, "home-now-capture", Chip.class);
            assertEquals("Capturing", capture.getText());
            assertEquals(Tokens.Tone.GOOD, capture.tone());
            assertEquals("Ann", named(card, "home-now-row-0-name", JLabel.class).getText());
            assertEquals("3.1k DPS", named(card, "home-now-row-0-dps", JLabel.class).getText());
            assertEquals("Your row says so, not only by color", "Sharkbait (you)", named(card, "home-now-row-1-name", JLabel.class).getText());
            assertEquals("2. Sharkbait, Wizard, 2.5k DPS, you", named(card, "home-now-row-1", JPanel.class).getAccessibleContext().getAccessibleName());
            assertEquals("You're #2 of 8", named(card, "home-now-rank", JLabel.class).getText());
            JLabel pop = named(card, "home-now-pop", JLabel.class);
            assertTrue(pop.isVisible());
            assertEquals("Ann popped Lost Halls · 1 min ago", pop.getText());
            assertEquals("Now: Lost Halls, capturing, You're #2 of 8. Open live meter", card.getAccessibleContext().getAccessibleName());
            assertEquals("Elapsed time and the pop's age tick: they are the description", "12m 30s in this area. Ann popped Lost Halls · 1 min ago",
                card.getAccessibleContext().getAccessibleDescription());
            card.getActionMap().get("open-card").actionPerformed(null);
            assertEquals("The whole card opens the live meter", 1, opened[0]);
        });
    }
    /** HomeCard: an empty state that joins the card on the first model has the font chosen while the card was loading. */
    @Test public void aFontChangeWhileLoadingReachesTheEmptyStateTheFirstModelShows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Font previous = tomato.gui.modern.ContentStyle.body();
            try {
                tomato.gui.modern.ContentStyle.setBodyFont(new java.awt.Font(tomato.gui.modern.ContentStyle.FONT_FAMILY, java.awt.Font.PLAIN, 13));
                NowCard card = new NowCard(() -> {}, mode); // loading
                tomato.gui.modern.ContentStyle.setBodyFont(new java.awt.Font(tomato.gui.modern.ContentStyle.FONT_FAMILY, java.awt.Font.PLAIN, 18));
                tomato.gui.modern.ContentStyle.refreshFonts(card); // what a font change in Settings does to the window
                card.apply(HomeModels.empty().now(), NOW);
                JLabel heading = null;
                for (java.awt.Component c : named(card, "home-now-empty", EmptyState.class).getComponents()) if (c instanceof JLabel) heading = (JLabel) c;
                assertEquals(Type.emphasis().getSize2D(), heading.getFont().getSize2D(), 0.01f);
            } finally {
                tomato.gui.modern.ContentStyle.setBodyFont(previous);
            }
        });
    }
    @Test public void theOneSecondTickChangesOnlyTheElapsedTextAndOnlyWhenItDiffers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NowCard card = new NowCard(() -> {}, mode);
            card.apply(HomeModels.now(NOW), NOW);   // entered 12m 30s ago
            JLabel elapsed = named(card, "home-now-elapsed", JLabel.class), pop = named(card, "home-now-pop", JLabel.class);
            String description = card.getAccessibleContext().getAccessibleDescription();
            List<Object> changes = new ArrayList<>();
            elapsed.addPropertyChangeListener("text", event -> changes.add(event.getNewValue()));
            card.tick(NOW + 999);
            assertEquals("The same second: no change", List.of(), changes);
            card.tick(NOW + 1_000);
            assertEquals("12m 31s", elapsed.getText()); assertEquals(List.of("12m 31s"), changes);
            assertEquals("The pop's age waits for the 10 s age tick", "Ann popped Lost Halls · 1 min ago", pop.getText());
            assertEquals("The spoken description is not re-announced every second", description, card.getAccessibleContext().getAccessibleDescription());
            HomeModel.Now live = HomeModels.now(NOW);
            card.apply(new HomeModel.Now(HomeModel.State.STALE, live.capturing(), live.area(), live.startedAt(), live.top(), live.localRank(),
                live.players(), live.lastPop()), NOW + 2_000);
            card.tick(NOW + 5_000);
            assertEquals("A stale Now does not tick", "12m 32s", elapsed.getText());
            card.apply(new HomeModel.Now(HomeModel.State.LIVE, true, "Nexus", null, List.of(), 0, 0, null), NOW + 6_000);
            card.tick(NOW + 7_000);
            assertFalse("No start time: nothing to tick", elapsed.isVisible());
        });
    }
    @Test public void outsideARunShowsOnlyTheAreaAndCaptureState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NowCard card = new NowCard(() -> {}, mode);
            card.apply(new HomeModel.Now(HomeModel.State.LIVE, false, "Nexus", null, List.of(), 0, 0,
                new KeypopGUI.LastPop("Ann", "Lost Halls", Instant.ofEpochMilli(NOW - 60_000L))), NOW);
            assertEquals("Nexus", named(card, "home-now-area", JLabel.class).getText());
            assertEquals("Capture off", named(card, "home-now-capture", Chip.class).getText());
            assertFalse(named(card, "home-now-elapsed", JLabel.class).isVisible());
            for (int i = 0; i < NowCard.SHOWN; i++) assertFalse(named(card, "home-now-row-" + i, JPanel.class).isVisible());
            assertFalse(named(card, "home-now-rank", JLabel.class).isVisible());
            assertFalse("Outside a run: area and capture state only", named(card, "home-now-pop", JLabel.class).isVisible());
            card.apply(new HomeModel.Now(HomeModel.State.LIVE, true, "Lost Halls", NOW - 60_000L,
                List.of(new MeterSummary.Row("Ann", HomeModels.WARRIOR, "Warrior", 5_000L, 83.3, false)), 0, 3, null), NOW);
            assertEquals("You're not on the meter yet · 3 players", named(card, "home-now-rank", JLabel.class).getText());
            assertTrue(named(card, "home-now-row-0", JPanel.class).isVisible());
            assertFalse(named(card, "home-now-row-1", JPanel.class).isVisible());
        });
    }
    @Test public void loadingEmptyUnavailableAndEvidence() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NowCard card = new NowCard(() -> {}, mode);
            assertEquals(HomeViews.LOADING, card.statusText());
            card.apply(HomeModels.empty().now(), NOW);
            assertNotNull(named(card, "home-now-empty", EmptyState.class));
            assertNull(named(card, "home-now-content", JComponent.class));
            card.apply(HomeModels.unavailable().now(), NOW);
            assertEquals("Live capture data is unavailable right now.", card.statusText());
            assertTrue("Unavailable is a warn banner", card.statusWarns());
            card.apply(HomeModels.now(NOW), NOW);
            assertFalse(card.evidenceShown());
            assertTrue(named(card, "evidence-note", JTextArea.class).getText().startsWith("Area and elapsed time from the current visit"));
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(card.evidenceShown());
        });
    }
}
