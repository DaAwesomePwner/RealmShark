package tomato.gui.glance.home;

import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.*;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** Hero card states, text, tones, names and click-throughs. A private DisplayModeModel: application() is never changed. */
public class HeroCardTest {
    private static final long NOW = 1_790_000_000_000L;
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
    private final int[] opened = new int[2]; // [0] Characters, [1] Build
    private Locale previous;
    @Before public void usFormat() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restoreFormat() { Locale.setDefault(Locale.Category.FORMAT, previous); }
    private HeroCard card() { return new HeroCard(() -> opened[0]++, () -> opened[1]++, mode); }
    @Test public void liveHeroShowsIdentityChipsGearBarsAndEstimates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            card.apply(HomeModels.hero(HomeModel.State.LIVE, NOW), NOW);
            assertEquals("Sharkbait", named(card, "home-hero-name", JLabel.class).getText());
            assertEquals("The name is the card title: no separate header row", "Sharkbait", card.header().title());
            assertEquals("Wizard · Level 20 · Fame 1,234", named(card, "home-hero-meta", JLabel.class).getText());
            Chip maxed = named(card, "home-hero-maxed", Chip.class);
            assertEquals("7/8 maxed", maxed.getText());
            assertEquals(Tokens.Tone.WARN, maxed.tone());
            assertEquals("Exalts 12/40", named(card, "home-hero-exalts", Chip.class).getText());
            assertFalse("A live character is not 'last seen'", named(card, "home-hero-seen", Chip.class).isVisible());
            ItemSlot weapon = named(card, "home-hero-slot-0", ItemSlot.class);
            assertEquals(ItemSlot.State.ITEM, weapon.state());
            assertEquals(2593, weapon.itemId());
            assertEquals(ItemSlot.State.EMPTY, named(card, "home-hero-slot-3", ItemSlot.class).state());
            assertEquals("An empty slot is labeled by its slot", "Ring", named(card, "home-hero-tier-3", JLabel.class).getText());
            assertTrue(named(card, "home-hero-bar-0", StatBar.class).maxed());
            assertFalse(named(card, "home-hero-bar-7", StatBar.class).maxed());
            assertEquals("60/75", named(card, "home-hero-value-7", JLabel.class).getText());
            JLabel boost = named(card, "home-hero-boost-0", JLabel.class);
            assertTrue("Live boost beside the base value", boost.isVisible());
            assertEquals("+50", boost.getText());
            assertEquals("Needs WIS 3 potions", named(card, "home-hero-needs", JLabel.class).getText());
            StatTile dps = named(card, "home-hero-weapon-dps", StatTile.class);
            assertEquals(DisplayValue.State.ESTIMATE, dps.value().state);
            assertEquals("Estimates carry ≈", DisplayValue.estimate("1,480", "x").text(), dps.value().text());
            assertEquals("70 stars · 12,345 account fame · 1,200 gold", named(card, "home-hero-account", JLabel.class).getText());
            assertEquals("Sharkbait, Wizard level 20, 7 of 8 maxed. Open Characters", card.getAccessibleContext().getAccessibleName());
            assertNotNull(named(card, "home-hero-content", JComponent.class));
        });
    }
    @Test public void uncapturedSlotsAreUnknownAndEmptySlotsAreEmpty() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            HomeModel.Hero h = HomeModels.hero(HomeModel.State.STALE, NOW);
            // Hero.equipment: item id > 0, 0 empty, -1 not captured (a journal record without that slot).
            card.apply(new HomeModel.Hero(h.state(), h.name(), h.classId(), h.className(), h.skin(), h.level(), h.fame(), h.maxed(),
                h.base(), h.caps(), h.totals(), h.potionsNeeded(), h.needsLine(), h.exaltTiers(), new int[]{2593, 0, -1, -1},
                h.weaponDps(), h.mpPerSecond(), h.accountLine(), h.lastSeenAt(), h.evidence()), NOW);
            assertEquals(ItemSlot.State.ITEM, named(card, "home-hero-slot-0", ItemSlot.class).state());
            assertEquals(ItemSlot.State.EMPTY, named(card, "home-hero-slot-1", ItemSlot.class).state());
            assertEquals("-1 is a slot that was not captured, never an empty one", ItemSlot.State.UNKNOWN,
                named(card, "home-hero-slot-2", ItemSlot.class).state());
            assertEquals("Armor", named(card, "home-hero-tier-2", JLabel.class).getText());
        });
    }
    @Test public void maxedAndExaltChipsUseGoodToneWhenCompleteAndHideWhenUnknown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            card.apply(HomeModels.hero(HomeModel.State.LIVE, NOW, 8, 40), NOW);
            assertEquals("8/8 maxed", named(card, "home-hero-maxed", Chip.class).getText());
            assertEquals(Tokens.Tone.GOOD, named(card, "home-hero-maxed", Chip.class).tone());
            assertEquals(Tokens.Tone.GOOD, named(card, "home-hero-exalts", Chip.class).tone());
            assertFalse("Maxed characters have no needs line", named(card, "home-hero-needs", JLabel.class).isVisible());
            card.apply(HomeModels.hero(HomeModel.State.LIVE, NOW, -1, -1), NOW);
            assertFalse("Unknown is never shown as 0/8", named(card, "home-hero-maxed", Chip.class).isVisible());
            assertFalse(named(card, "home-hero-exalts", Chip.class).isVisible());
        });
    }
    @Test public void staleHeroIsDimmedAndSaysWhenItWasLastSeen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            card.apply(HomeModels.hero(HomeModel.State.STALE, NOW), NOW);
            Chip seen = named(card, "home-hero-seen", Chip.class);
            assertTrue(seen.isVisible());
            assertEquals("Last seen 2 h ago", seen.getText());
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), named(card, "home-hero-name", JLabel.class).getForeground());
            assertEquals("Wizard · Level 20 · Fame 1,234 (stale)", named(card, "home-hero-meta", JLabel.class).getText());
            assertFalse("No live boost when not in game", named(card, "home-hero-boost-0", JLabel.class).isVisible());
            assertEquals("Sharkbait, Wizard level 20, 7 of 8 maxed, not in game. Open Characters", card.getAccessibleContext().getAccessibleName());
            assertEquals("The ticking age is the description, never the name", "Last seen 2 h ago",
                card.getAccessibleContext().getAccessibleDescription());
        });
    }
    @Test public void loadingEmptyAndUnavailableAreExplicit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            assertEquals("Static loading text, never a blank card", HomeViews.LOADING, card.statusText());
            assertFalse("Loading is not a warning", card.statusWarns());
            assertNotNull(named(card, "home-hero-status", JComponent.class));
            card.apply(HomeModels.empty().hero(), NOW);
            EmptyState empty = named(card, "home-hero-empty", EmptyState.class);
            assertNotNull(empty);
            assertEquals("Start capture and enter the game to see your character.", empty.getAccessibleContext().getAccessibleDescription());
            assertNull(named(card, "home-hero-content", JComponent.class));
            card.apply(HomeModels.unavailable().hero(), NOW);
            assertEquals("The one-line reason", "The character journal could not be read.", card.statusText());
            assertTrue("Unavailable is a warn banner", card.statusWarns());
            assertNull(named(card, "home-hero-empty", EmptyState.class));
        });
    }
    @Test public void anEqualHeroIsSkippedButItsAgeStillAdvances() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            card.apply(HomeModels.hero(HomeModel.State.STALE, NOW), NOW);
            StatTile dps = named(card, "home-hero-weapon-dps", StatTile.class);
            dps.setValue(DisplayValue.unknown("marker"), null);
            card.apply(HomeModels.hero(HomeModel.State.STALE, NOW), NOW);
            assertEquals("An equal hero at the same age is not applied again", DisplayValue.State.UNKNOWN, dps.value().state);
            card.apply(HomeModels.hero(HomeModel.State.STALE, NOW), NOW + 3_600_000L);
            assertEquals("Last seen 3 h ago", named(card, "home-hero-seen", Chip.class).getText());
            assertEquals(DisplayValue.State.ESTIMATE, dps.value().state);
        });
    }
    @Test public void wholeCardOpensCharactersAndBuildOpensBuild() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            card.apply(HomeModels.hero(HomeModel.State.LIVE, NOW), NOW);
            assertTrue(card.isFocusable());
            card.getActionMap().get("open-card").actionPerformed(null);
            assertEquals(1, opened[0]);
            ItemSlot slot = named(card, "home-hero-slot-0", ItemSlot.class);
            slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1));
            assertEquals("A click on a gear slot opens Characters too", 2, opened[0]);
            AbstractButton build = named(card, "home-build", AbstractButton.class);
            assertEquals("Open Build: weapon damage and recovery", build.getAccessibleContext().getAccessibleName());
            build.doClick();
            assertEquals(1, opened[1]);
            assertEquals("Build does not also open Characters", 2, opened[0]);
        });
    }
    @Test public void evidenceIsCollapsedInSimpleAndExpandedInAnalyst() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            HomeModel.Hero hero = HomeModels.hero(HomeModel.State.LIVE, NOW);
            card.apply(hero, NOW);
            assertFalse(card.evidenceShown());
            assertEquals(hero.evidence(), named(card, "evidence-note", JTextArea.class).getText());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(card.evidenceShown());
        });
    }
}
