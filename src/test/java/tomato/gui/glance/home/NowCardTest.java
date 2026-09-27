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
