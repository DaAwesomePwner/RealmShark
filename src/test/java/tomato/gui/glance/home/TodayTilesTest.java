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

    /**
     * P6a Task 11: with Home's loot action the Notable loot tile opens Loot › Highlights like Home's other drill-downs: click
     * (including on its labels), Enter or Space; it is focusable, painted with a focus ring and spoken as a button. The other
     * tiles stay plain, and without the action the tile is not activatable.
     */
    @Test public void theNotableLootTileOpensLootHighlightsByClickEnterOrSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] opened = {0};
            TodayTiles tiles = new TodayTiles(window -> {}, TODAY, mode, window -> opened[0]++);
            tiles.apply(HomeModels.today(TODAY, NOW));
            StatTile loot = named(tiles, "home-tile-loot", StatTile.class);
            assertTrue(loot.isFocusable());
            assertEquals(java.awt.Cursor.HAND_CURSOR, loot.getCursor().getType());
            assertEquals(javax.accessibility.AccessibleRole.PUSH_BUTTON, loot.getAccessibleContext().getAccessibleRole());
            assertEquals("Notable loot: 3, 2 UT · 1 ST · 3 white bags. Open loot highlights", spoken(tiles, "home-tile-loot"));
            for (int key : new int[] {java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.VK_SPACE})
                loot.getActionMap().get(loot.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, 0))).actionPerformed(null);
            assertEquals("Enter and Space open it", 2, opened[0]);
            JLabel label = null;
            for (java.awt.Component child : loot.getComponents()) if (child instanceof JLabel && ((JLabel) child).getToolTipText() != null) label = (JLabel) child;
            assertNotNull("The value label carries its own tooltip (and so its own mouse events)", label);
            label.dispatchEvent(new java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals("A click on the value opens it too", 3, opened[0]);
            loot.dispatchEvent(new java.awt.event.MouseEvent(loot, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON3));
            assertEquals("A right click opens nothing", 3, opened[0]);
            for (String plain : new String[] {"home-tile-runs", "home-tile-fame", "home-tile-potions"}) {
                StatTile tile = named(tiles, plain, StatTile.class);
                assertNull(plain + " stays a plain tile", tile.getActionMap().get("open-tile"));
                assertNotEquals(plain, java.awt.Cursor.HAND_CURSOR, tile.getCursor().getType());
            }
            // Unknown loot still opens Highlights, which says why there is nothing to show; the value stays "—".
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 0, 0, true, null, null, null, 0, 0, 0, 0, false), null));
            assertEquals("Notable loot: —. Open loot highlights", spoken(tiles, "home-tile-loot"));

            TodayTiles plain = new TodayTiles(window -> {}, TODAY, mode);
            plain.apply(HomeModels.today(TODAY, NOW));
            StatTile inert = named(plain, "home-tile-loot", StatTile.class);
            assertNull("Without Home's loot action the tile is not activatable", inert.getActionMap().get("open-tile"));
            assertNotEquals(java.awt.Cursor.HAND_CURSOR, inert.getCursor().getType());
            assertNotEquals(javax.accessibility.AccessibleRole.PUSH_BUTTON, inert.getAccessibleContext().getAccessibleRole());
            assertEquals("Notable loot: 3, 2 UT · 1 ST · 3 white bags", spoken(plain, "home-tile-loot"));
        });
    }

    /** HomeActions gained {@code loot}; the five-action form (tests, fixtures) has none, so its tile is not activatable. */
    @Test public void homeActionsCarryTheLootActionAndHomeHandsItToTheTile() throws Exception {
        int[] ran = {0};
        Runnable loot = () -> ran[0]++;
        HomeActions actions = new HomeActions(key -> {}, () -> {}, () -> {}, visit -> {}, () -> {}, loot);
        actions.loot().accept(TODAY);
        assertEquals("The Runnable form carries the loot action (P6b: adapted to the window form)", 1, ran[0]);
        assertNull("The five-action form has no loot action", HomeModels.NO_ACTIONS.loot());
        SwingUtilities.invokeAndWait(() -> {
            int[] opened = {0};
            Map<String, String> prefs = new HashMap<>();
            HomePage page = new HomePage(null, new HomeActions(key -> {}, () -> {}, () -> {}, visit -> {}, () -> {}, () -> opened[0]++),
                new DisplayModeModel(prefs::get, prefs::put), prefs::get, prefs::put, () -> NOW);
            page.apply(HomeModels.populated(NOW));
            StatTile tile = named(page, "home-tile-loot", StatTile.class);
            assertNotNull("Home makes the Notable loot tile activatable", tile.getActionMap().get("open-tile"));
            tile.getActionMap().get("open-tile").actionPerformed(null);
            assertEquals(1, opened[0]);
            page.close();
        });
    }

    /**
     * P6b Task 9 (R3 B10): the Notable loot tile passes the window it shows, Today or This session, so Loot › Highlights opens on
     * the same period; a window the user picks after is the one passed next. Home hands its window-aware loot action to the tile.
     */
    @Test public void theNotableLootTilePassesTheWindowItShows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<HomeArchive.Window> opened = new ArrayList<>(), changes = new ArrayList<>();
            TodayTiles tiles = new TodayTiles(changes::add, TODAY, mode, opened::add);
            tiles.apply(HomeModels.today(TODAY, NOW));
            StatTile loot = named(tiles, "home-tile-loot", StatTile.class);
            loot.getActionMap().get("open-tile").actionPerformed(null);
            assertEquals("Today opens Highlights on Today", List.of(TODAY), opened);
            named(tiles, "home-today-window-1", JToggleButton.class).doClick();
            assertEquals(List.of(SESSION), changes);
            tiles.apply(HomeModels.today(SESSION, NOW));
            loot.getActionMap().get(loot.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0))).actionPerformed(null);
            loot.dispatchEvent(new java.awt.event.MouseEvent(loot, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals("This session opens it on This session, by key or click", List.of(TODAY, SESSION, SESSION), opened);
            opened.clear();
            TodayTiles restored = new TodayTiles(window -> {}, SESSION, mode, opened::add);
            restored.apply(HomeModels.today(SESSION, NOW));
            named(restored, "home-tile-loot", StatTile.class).getActionMap().get("open-tile").actionPerformed(null);
            assertEquals("A restored This session passes it", List.of(SESSION), opened);

            List<HomeArchive.Window> windows = new ArrayList<>();
            Map<String, String> prefs = new HashMap<>();
            prefs.put(HomePage.WINDOW_KEY, "session");
            HomePage page = new HomePage(null, new HomeActions(key -> {}, () -> {}, () -> {}, visit -> {}, () -> {}, windows::add),
                new DisplayModeModel(prefs::get, prefs::put), prefs::get, prefs::put, () -> NOW);
            page.apply(HomeModels.populated(NOW).withToday(HomeModels.today(SESSION, NOW)));
            named(page, "home-tile-loot", StatTile.class).getActionMap().get("open-tile").actionPerformed(null);
            assertEquals("Home's tile passes Home's window", List.of(SESSION), windows);
            page.close();
        });
    }
}
