package tomato.gui.glance.home;

import java.util.*;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.*;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** Today tiles: values, honest unknowns, the Today / This session choice, states and Evidence. */
public class TodayTilesTest {
    private static final long NOW = 1_790_000_000_000L;
    private static final HomeArchive.Window TODAY = HomeArchive.Window.TODAY, SESSION = HomeArchive.Window.SESSION;
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
    private Locale previous;
    @Before public void usFormat() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restoreFormat() { Locale.setDefault(Locale.Category.FORMAT, previous); }
    private static String spoken(TodayTiles tiles, String tile) { return named(tiles, tile, StatTile.class).getAccessibleContext().getAccessibleName(); }
    @Test public void tilesShowRunsFameLootAndPotions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TodayTiles tiles = new TodayTiles(window -> {}, TODAY, mode);
            tiles.apply(HomeModels.today(TODAY, NOW));
            assertEquals("Runs: 4, 5 entered", spoken(tiles, "home-tile-runs"));
            assertEquals("Fame: +1,234, 540 fame/hour", spoken(tiles, "home-tile-fame"));
            assertEquals("Notable loot: 3, 2 UT · 1 ST · 3 white bags", spoken(tiles, "home-tile-loot"));
            assertEquals("Potions: 6", spoken(tiles, "home-tile-potions"));
            Sparkline trend = named(tiles, "home-today-fame-trend", Sparkline.class);
            assertTrue(trend.isVisible());
            assertEquals(12, trend.values().length);
            assertFalse(named(tiles, "home-today-note", HomeViews.Reason.class).isVisible());
        });
    }
    @Test public void unknownFameIsNeverZeroAndZeroRunsAreARealZero() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TodayTiles tiles = new TodayTiles(window -> {}, TODAY, mode);
            // Visits were saved (runsRecorded) but none was a dungeon run: zero runs is a real zero.
            HomeArchive.Totals none = new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 0, 0, true, null, null, null, 0, 0, 0, 0, false);
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, none, null));
            for (String tile : new String[] {"home-tile-loot", "home-tile-potions"}) {
                DisplayValue loot = named(tiles, tile, StatTile.class).value();
                assertEquals("No loot saved: unknown, never 0", DisplayValue.State.UNKNOWN, loot.state);
                assertEquals(TodayTiles.NO_LOOT, loot.detail);
            }
            DisplayValue fame = named(tiles, "home-tile-fame", StatTile.class).value();
            assertEquals(DisplayValue.State.UNKNOWN, fame.state);
            assertEquals("—", fame.text());
            assertFalse(named(tiles, "home-today-fame-trend", Sparkline.class).isVisible());
            assertEquals(DisplayValue.State.ZERO, named(tiles, "home-tile-runs", StatTile.class).value().state);
            HomeArchive.Totals looted = new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 0, 0, true, null, null, null, 0, 0, 0, 0, true);
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, looted, null));
            assertEquals("Loot was saved and none dropped: a real zero", DisplayValue.State.ZERO, named(tiles, "home-tile-loot", StatTile.class).value().state);
            assertEquals(DisplayValue.State.ZERO, named(tiles, "home-tile-potions", StatTile.class).value().state);
            tiles.apply(new HomeModel.Today(HomeModel.State.STALE, TODAY, HomeModels.totals(TODAY, NOW), "Last updated 2 min ago · disk full"));
            assertEquals(DisplayValue.State.STALE, named(tiles, "home-tile-runs", StatTile.class).value().state);
            HomeViews.Reason note = named(tiles, "home-today-note", HomeViews.Reason.class);
            assertTrue(note.isVisible()); assertTrue("A failed re-read is a warn banner", note.warns());
            assertEquals("Last updated 2 min ago · disk full", note.text());
            tiles.apply(new HomeModel.Today(HomeModel.State.STALE, TODAY, HomeModels.totals(TODAY, NOW), null));
            assertEquals("Showing the last successful read of saved history.", note.text());
        });
    }
    @Test public void unreadableSessionsWarnAndMarkTheTotalsPartial() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TodayTiles tiles = new TodayTiles(window -> {}, TODAY, mode);
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 2, 3, true, 120L, null, null, 1, 0, 1, 2, true, 2), null));
            HomeViews.Reason banner = named(tiles, "home-today-unreadable", HomeViews.Reason.class);
            assertTrue(banner.isVisible()); assertTrue(banner.warns()); assertEquals("2 saved sessions could not be read", banner.text());
            for (String tile : new String[] {"home-tile-runs", "home-tile-fame", "home-tile-loot", "home-tile-potions"}) {
                DisplayValue value = named(tiles, tile, StatTile.class).value();
                assertEquals(tile, DisplayValue.State.PARTIAL, value.state); assertEquals(tile, "2 saved sessions could not be read", value.detail);
            }
            assertFalse("Not stale", named(tiles, "home-today-note", HomeViews.Reason.class).isVisible());
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 2, 3, true, 120L, null, null, 1, 0, 1, 2, true, 1), null));
            assertEquals("1 saved session could not be read", banner.text());
            tiles.apply(HomeModels.today(TODAY, NOW));
            assertFalse("Every session readable: no banner", banner.isVisible());
            assertEquals(DisplayValue.State.KNOWN, named(tiles, "home-tile-runs", StatTile.class).value().state);
        });
    }

    @Test public void runsAreUnknownWhenNoVisitWasSaved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TodayTiles tiles = new TodayTiles(window -> {}, TODAY, mode);
            HomeArchive.Totals unrecorded = new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 0, 0, false, 250L, null, null, 0, 0, 0, 0, false);
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, unrecorded, null));
            DisplayValue runs = named(tiles, "home-tile-runs", StatTile.class).value();
            assertEquals("No visits saved: unknown, never 0", DisplayValue.State.UNKNOWN, runs.state);
            assertEquals(TodayTiles.NO_RUNS, runs.detail);
            assertFalse("No \"0 entered\" line either", spoken(tiles, "home-tile-runs").contains("entered"));
        });
    }
    @Test public void windowChoiceNotifiesAndWaitsForItsOwnResult() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<HomeArchive.Window> changes = new ArrayList<>();
            TodayTiles tiles = new TodayTiles(changes::add, TODAY, mode);
            tiles.apply(HomeModels.today(TODAY, NOW));
            named(tiles, "home-today-window-1", AbstractButton.class).doClick();
            assertEquals(List.of(SESSION), changes);
            assertEquals(SESSION, tiles.selected());
            assertEquals(HomeViews.LOADING, tiles.statusText());
            tiles.apply(HomeModels.today(TODAY, NOW));
            assertNull("A result read for the previous window is not shown", named(tiles, "home-today-content", JComponent.class));
            tiles.apply(HomeModels.today(SESSION, NOW));
            assertNotNull(named(tiles, "home-today-content", JComponent.class));
            TodayTiles restored = new TodayTiles(window -> {}, SESSION, mode);
            assertEquals(SESSION, restored.selected());
            assertTrue(named(restored, "home-today-window-1", AbstractButton.class).isSelected());
        });
    }
    @Test public void loadingEmptyUnavailableAndEvidence() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TodayTiles tiles = new TodayTiles(window -> {}, TODAY, mode);
            assertEquals(HomeViews.LOADING, tiles.statusText());
            tiles.apply(new HomeModel.Today(HomeModel.State.EMPTY, TODAY, null, null));
            assertNotNull(named(tiles, "home-today-empty", EmptyState.class));
            tiles.apply(new HomeModel.Today(HomeModel.State.UNAVAILABLE, TODAY, null, "Saved history could not be opened."));
            assertEquals("Saved history could not be opened.", tiles.statusText());
            assertTrue("Unavailable is a warn banner", tiles.statusWarns());
            TodayTiles session = new TodayTiles(window -> {}, SESSION, mode);
            session.apply(new HomeModel.Today(HomeModel.State.EMPTY, SESSION, null, null));
            assertNotNull(named(session, "home-today-empty-session", EmptyState.class));
            tiles.apply(HomeModels.today(TODAY, NOW));
            assertFalse(tiles.evidenceShown());
            assertTrue(named(tiles, "evidence-note", JTextArea.class).getText().startsWith("Today: saved history from "));
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(tiles.evidenceShown());
        });
    }
}
