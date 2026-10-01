package tomato.gui.loot;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.Themes;
import tomato.gui.stats.LootDashboard;
import tomato.gui.stats.LootFacts;
import tomato.gui.stats.LootFilters;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;
import static tomato.gui.loot.HighlightsFixture.*;
import static tomato.gui.loot.HighlightsModel.Source.LIVE_UNSAVED;
import static tomato.gui.loot.HighlightsModel.Source.SAVED;
import static tomato.gui.loot.HighlightsModel.Window.SESSION;
import static tomato.gui.loot.HighlightsModel.Window.TODAY;

/**
 * Loot › Highlights: component names, the Today / This session choice kept in {@code ui.loot.highlights}, reads on first show,
 * a window change and a nudge (never on a repeat show), Filter Loot hiding grid drops only, run links only for exact visits,
 * and the loading, unavailable, empty, stale and partial states. A fake reader; Filter Loot keys and the window key isolated.
 */
public class LootHighlightsTest {
    @Rule public ui.VisualEvidence evidence = new ui.VisualEvidence("p6a");
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private static final String[] FILTER_KEYS = {"filterWhiteBag", "filterOrangeBag", "filterRedBag", "filterGoldBag", "filterEggBag",
        "filterBlueBag", "filterTealBag", "filterPurpleBag", "filterPinkBag", "filterBrownBag", LootHighlights.WINDOW_KEY};
    private static final long NOON = at(0, 12, 0);
    private static final VisitRef RUN = new VisitRef("session-a", "v1"), OTHER_RUN = new VisitRef("session-a", "v2");
    private final String[] saved = new String[FILTER_KEYS.length];
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new CopyOnWriteArrayList<>();
    private final List<LootHighlights> views = new ArrayList<>();
    private final List<JFrame> frames = new ArrayList<>();

    @Before public void isolate() throws Exception {
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        for (int i = 0; i < FILTER_KEYS.length; i++) { saved[i] = PropertiesManager.getProperty(FILTER_KEYS[i]); preferences().remove(FILTER_KEYS[i]); }
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (LootHighlights view : views) view.close(); for (JFrame frame : frames) frame.dispose(); });
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        for (int i = 0; i < FILTER_KEYS.length; i++) {
            if (saved[i] == null) preferences().remove(FILTER_KEYS[i]); else PropertiesManager.setProperties(FILTER_KEYS[i], saved[i]);
        }
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static Properties preferences() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        return (Properties) field.get(null);
    }

    /** A reader that counts reads per window, can block, and answers what the test sets. */
    static final class Fake implements LootHighlights.Reader {
        final AtomicInteger reads = new AtomicInteger();
        final List<HighlightsModel.Window> windows = new CopyOnWriteArrayList<>();
        final AtomicLong revision = new AtomicLong();
        volatile Function<HighlightsModel.Window, HighlightsModel> answer;
        volatile CountDownLatch gate;
        Fake(Function<HighlightsModel.Window, HighlightsModel> answer) { this.answer = answer; }
        @Override public HighlightsModel read(HighlightsModel.Window window, Cancellation cancel) {
            reads.incrementAndGet();
            windows.add(window);
            CountDownLatch wait = gate;
            if (wait != null) try { wait.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return answer.apply(window);
        }
        @Override public long revision() { return revision.get(); }
    }

    /** Notable drops in White, Orange (two), Brown and an unnamed bag; three areas and Unknown area. */
    static HighlightsModel populated(HighlightsModel.Window window) {
        List<LootFacts.Bag> bags = List.of(
            bag("White", "Lost Halls", at(0, 9, 5), RUN, item(1001, true, false, false, 0), item(LIFE, false, false, true, null), item(GREATER_LIFE, false, false, true, null)),
            bag("Orange", "Lost Halls", at(0, 9, 20), RUN, item(1002, false, false, false, 3), item(1003, false, false, false, null)),
            bag("Orange", "Pirate Cave", at(0, 10, 10), OTHER_RUN, item(1004, false, true, false, 2), item(DEFENSE, false, false, true, null)),
            bag("Brown", "Snake Pit", at(0, 10, 40), null, item(MANA, false, false, true, null), item(OTHER_POTION, false, false, true, null)),
            bag(null, HighlightsModel.UNRECOGNIZED, at(0, 11, 0), null, item(1005, false, true, false, null)),
            bag("B.White", "Lost Halls", at(0, 11, 30), RUN, item(1006, true, false, false, 4), item(GREATER_DEFENSE, false, false, true, null)));
        return HighlightsModel.of(window, SAVED, bags, true, 0, false, NOON);
    }
    private static HighlightsModel empty(HighlightsModel.Window window) { return HighlightsModel.of(window, SAVED, List.of(), false, 0, false, NOON); }

    private LootHighlights view(Fake reader) throws Exception {
        LootHighlights view = edt(() -> new LootHighlights(reader, ZONE, prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }, 0, 0));
        views.add(view);
        return view;
    }
    private JFrame frame(JComponent content, int width, int height) throws Exception {
        return edt(() -> {
            JFrame frame = new JFrame("Loot highlights fixture");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setContentPane(content);
            frame.setSize(width, height);
            frame.setVisible(true);
            frames.add(frame);
            return frame;
        });
    }
    private static void loaded(LootHighlights view) throws Exception { await("the highlights read", () -> view.model() != null && !view.loading()); }
    private static List<Integer> grid(LootHighlights view) throws Exception {
        return edt(() -> view.notableList().items().stream().map(HighlightsModel.Notable::itemId).collect(Collectors.toList()));
    }

    @Test public void tilesToggleFocusWithMouseKeyboardAndClearAndKeepItAcrossReads() throws Exception {
        LootHighlights view = view(new Fake(LootHighlightsTest::populated));
        edt(() -> { view.request(); return null; });
        loaded(view);
        List<Integer> all = grid(view);
        edt(() -> {
            StatTile enchanted = named(view, "loot-tile-enchanted", StatTile.class);
            assertEquals("3", enchanted.valueText());
            assertEquals("1 Rare · 1 Legendary · 1 Divine", subline(enchanted));
            clickTile(named(view, "loot-tile-ut", StatTile.class));
            return null;
        });
        assertEquals(List.of(1006, 1001), grid(view));
        edt(() -> {
            StatTile ut = named(view, "loot-tile-ut", StatTile.class);
            assertTrue(ut.getAccessibleContext().getAccessibleStateSet().contains(javax.accessibility.AccessibleState.SELECTED));
            assertEquals("Showing only UT drops", named(view, "loot-notable-focus", KitText.class).getText());
            clickTile(ut);
            return null;
        });
        assertEquals(all, grid(view));
        edt(() -> {
            keyboardTile(named(view, "loot-tile-enchanted", StatTile.class), java.awt.event.KeyEvent.VK_SPACE);
            return null;
        });
        assertEquals(List.of(1006, 1004, 1002), grid(view));
        edt(() -> { view.refresh(); return null; });
        loaded(view);
        assertEquals(List.of(1006, 1004, 1002), grid(view));
        edt(() -> { view.showWindow(SESSION); return null; });
        loaded(view);
        assertEquals(List.of(1006, 1004, 1002), grid(view));
        edt(() -> {
            named(view, "loot-notable-focus-clear", KitButton.class).doClick();
            return null;
        });
        assertEquals(all, grid(view));
        edt(() -> {
            keyboardTile(named(view, "loot-tile-potions", StatTile.class), java.awt.event.KeyEvent.VK_ENTER);
            return null;
        });
        assertEquals(List.of(GREATER_DEFENSE, MANA, DEFENSE, LIFE, GREATER_LIFE), grid(view));
        assertEquals("Only the period is persisted", List.of(LootHighlights.WINDOW_KEY + "=session"), writes);
    }

    @Test public void unknownTilesCannotToggle() throws Exception {
        LootHighlights view = view(new Fake(LootHighlightsTest::empty));
        edt(() -> { view.request(); return null; });
        loaded(view);
        edt(() -> {
            for (StatTile tile : tiles(view)) {
                assertFalse(tile.isEnabled()); assertFalse(tile.isFocusable());
                clickTile(tile);
                keyboardTile(tile, java.awt.event.KeyEvent.VK_SPACE);
                assertFalse(tile.getAccessibleContext().getAccessibleStateSet().contains(javax.accessibility.AccessibleState.SELECTED));
            }
            assertEquals("", named(view, "loot-notable-focus", KitText.class).getText());
            return null;
        });
    }

    @Test public void activeFilterClearsWhenItsTileBecomesUnknown() throws Exception {
        Fake reader = new Fake(LootHighlightsTest::populated);
        LootHighlights view = view(reader);
        edt(() -> { view.request(); return null; });
        loaded(view);
        edt(() -> {
            clickTile(named(view, "loot-tile-ut", StatTile.class));
            assertTrue(named(view, "loot-tile-ut", StatTile.class).getAccessibleContext().getAccessibleStateSet()
                .contains(javax.accessibility.AccessibleState.SELECTED));
            reader.answer = LootHighlightsTest::empty;
            view.refresh();
            return null;
        });
        loaded(view);
        edt(() -> {
            StatTile tile = named(view, "loot-tile-ut", StatTile.class);
            assertFalse(tile.isEnabled());
            assertFalse(tile.getAccessibleContext().getAccessibleStateSet().contains(javax.accessibility.AccessibleState.SELECTED));
            assertEquals("", named(view, "loot-notable-focus", KitText.class).getText());
            reader.answer = LootHighlightsTest::populated;
            view.refresh();
            return null;
        });
        loaded(view);
        assertEquals("Returning data must not restore the discarded filter", populated(TODAY).notable().size(), grid(view).size());
    }

    @Test public void focusedEmptyStateDistinguishesNoMatchesFromHiddenMatches() throws Exception {
        LootHighlights view = view(new Fake(window -> HighlightsModel.of(window, SAVED,
            List.of(bag("White", "Lost Halls", NOON, RUN, item(1001, true, false, false, 2))), true, 0, false, NOON)));
        frame(view, 1240, 800);
        loaded(view);
        edt(() -> {
            clickTile(named(view, "loot-tile-st", StatTile.class));
            assertEquals("No ST drops in this period", named(view, "loot-notable-empty", EmptyState.class).getAccessibleContext().getAccessibleName());
            clickTile(named(view, "loot-tile-ut", StatTile.class));
            assertEquals(1, view.notableList().items().size());
            LootFilters.get().set(LootFilters.Kind.WHITE, false);
            assertTrue(view.notableList().items().isEmpty());
            assertEquals("Filter Loot hides all UT drops", named(view, "loot-notable-empty", EmptyState.class).getAccessibleContext().getAccessibleName());
            assertEquals("1", named(view, "loot-tile-ut", StatTile.class).valueText());
            LootFilters.get().set(LootFilters.Kind.WHITE, true);
            assertEquals(1, view.notableList().items().size());
            return null;
        });
    }

    private static void clickTile(StatTile tile) {
        tile.dispatchEvent(new java.awt.event.MouseEvent(tile, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false,
            java.awt.event.MouseEvent.BUTTON1));
    }

    private static void keyboardTile(StatTile tile, int key) {
        Object action = tile.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, 0));
        assertNotNull(action);
        tile.getActionMap().get(action).actionPerformed(new java.awt.event.ActionEvent(tile, 0, "test"));
    }

    @Test public void namesItsPartsAndAddsOverflowActions() throws Exception {
        LootHighlights view = view(new Fake(LootHighlightsTest::populated));
        AtomicInteger ran = new AtomicInteger();
        edt(() -> {
            assertEquals("loot-highlights", view.getName());
            named(view, "loot-highlights-window", SegmentedControl.class);
            OverflowMenu more = named(view, "loot-highlights-more", OverflowMenu.class);
            for (String tile : new String[] {"loot-tile-ut", "loot-tile-st", "loot-tile-potions", "loot-tile-whites", "loot-tile-enchanted"}) named(view, tile, StatTile.class);
            assertSame(view.notableList(), named(view, "loot-notable-grid", TileList.class));
            assertSame(view.stripList(), named(view, "loot-dungeon-strip", TileList.class));
            view.addOverflowAction("loot-sharing-status", "Loot sharing status…", ran::incrementAndGet);
            JMenuItem item = more.item("Loot sharing status…");
            assertNotNull(item); assertEquals("loot-sharing-status", item.getName());
            item.doClick();
            assertNotNull("⋯ Refresh reads again", more.item("Refresh"));
            return null;
        });
        assertEquals(1, ran.get());
    }

    @Test public void readsOnFirstShowAndOnANudgeButNeverOnARepeatShow() throws Exception {
        Fake reader = new Fake(LootHighlightsTest::populated);
        LootHighlights view = view(reader);
        Thread.sleep(150);
        assertEquals("Building the tab reads nothing", 0, reader.reads.get());
        JFrame frame = frame(view, 1240, 800);
        loaded(view);
        assertEquals(1, reader.reads.get());
        assertEquals(List.of(TODAY), reader.windows);
        edt(() -> { frame.setVisible(false); return null; });
        edt(() -> { frame.setVisible(true); return null; });
        Thread.sleep(150);
        edt(() -> { view.tick(); return null; });
        Thread.sleep(150);
        assertEquals("A repeat show and an unchanged revision read nothing", 1, reader.reads.get());
        reader.revision.set(1);
        edt(() -> { view.tick(); return null; });
        await("the nudged read", () -> reader.reads.get() == 2 && !view.loading());
        for (int i = 0; i < LootHighlights.PERIODIC_TICKS - 1; i++) edt(() -> { view.tick(); return null; });
        Thread.sleep(150);
        assertEquals("No new bag: the next read is the 30 s one", 2, reader.reads.get());
        edt(() -> { view.tick(); return null; });
        await("the periodic read", () -> reader.reads.get() == 3 && !view.loading());
        edt(() -> { frame.setVisible(false); view.tick(); return null; });
        reader.revision.set(2);
        edt(() -> { view.tick(); return null; });
        Thread.sleep(150);
        assertEquals("Hidden: nothing is checked", 3, reader.reads.get());
        assertEquals(2_000, LootHighlights.CHECK_MILLIS);
        assertEquals(30_000, LootHighlights.CHECK_MILLIS * LootHighlights.PERIODIC_TICKS);
    }

    @Test public void theWindowChoiceIsKeptAndANewerWindowsResultWins() throws Exception {
        assertEquals("ui.loot.highlights", LootHighlights.WINDOW_KEY);
        Fake reader = new Fake(LootHighlightsTest::populated);
        LootHighlights view = view(reader);
        assertEquals("Today by default", TODAY, edt(view::window));
        frame(view, 1240, 800);
        loaded(view);
        reader.gate = new CountDownLatch(1);
        edt(() -> { named(view, "loot-highlights-window-1", JToggleButton.class).doClick(); return null; });
        await("the This session read", () -> reader.windows.size() == 2);
        assertEquals(List.of("ui.loot.highlights=session"), writes);
        assertEquals(SESSION, edt(view::window));
        assertEquals("A result for the other window is not shown as this one", "loading", edt(view::state));
        reader.gate.countDown();
        reader.gate = null;
        await("the This session result", () -> view.model() != null && view.model().window() == SESSION && !view.loading());
        assertEquals("content", edt(view::state));

        prefs.put(LootHighlights.WINDOW_KEY, "session");
        Fake second = new Fake(LootHighlightsTest::populated);
        LootHighlights restored = view(second);
        assertEquals("Restored without writing", SESSION, edt(restored::window));
        frame(restored, 1240, 800);
        loaded(restored);
        assertEquals(List.of(SESSION), second.windows);
        assertEquals(1, writes.size());
    }

    @Test public void theProductionViewKeepsTheWindowInTheAppPreferences() throws Exception {
        PropertiesManager.setProperties(LootHighlights.WINDOW_KEY, "session");
        HighlightsSource source = new HighlightsSource(() -> null, new LootDashboard.Feed(), ZONE, () -> NOON);
        LootHighlights view = edt(() -> new LootHighlights(source));
        views.add(view);
        assertEquals(SESSION, edt(view::window));
        edt(() -> { named(view, "loot-highlights-window-0", JToggleButton.class).doClick(); return null; });
        assertEquals("today", PropertiesManager.getProperty(LootHighlights.WINDOW_KEY));
    }

    @Test public void filterLootShowsOlderVisibleDropsBehindNewerHiddenOnes() throws Exception {
        // Codex review (PR #27): 250 newer orange drops must not crowd 50 older white drops out of the grid when orange is hidden.
        List<LootFacts.Bag> bags = new ArrayList<>();
        for (int i = 0; i < 50; i++) bags.add(bag("White", "Lost Halls", at(0, 8, 0) + i, null, item(10_000 + i, true, false, false, 0)));
        for (int i = 0; i < 250; i++) bags.add(bag("Orange", "Lost Halls", at(0, 10, 0) + i, null, item(i, true, false, false, 0)));
        HighlightsModel crowded = HighlightsModel.of(TODAY, SAVED, bags, true, 0, false, NOON);
        LootHighlights view = view(new Fake(window -> crowded));
        frame(view, 1240, 800);
        loaded(view);
        try {
            edt(() -> {
                assertEquals(HighlightsModel.NOTABLE_LIMIT, view.notableList().items().size());
                assertEquals("Showing the newest 200 of 300 notable drops", named(view, "loot-notable-notes", JLabel.class).getText());
                return null;
            });
            edt(() -> { LootFilters.get().set(LootFilters.Kind.ORANGE, false); return null; });
            edt(() -> {
                List<HighlightsModel.Notable> shown = view.notableList().items();
                assertEquals("Every white drop shows", 50, shown.size());
                assertTrue(shown.stream().allMatch(drop -> "White".equals(drop.bag())));
                assertEquals("Filter Loot hides 250 of 300 notable drops; the tiles still count them",
                    named(view, "loot-notable-filtered", JLabel.class).getText());
                assertFalse("All 50 visible drops are listed", named(view, "loot-notable-notes", JLabel.class).isVisible());
                return null;
            });
        } finally {
            edt(() -> { LootFilters.get().set(LootFilters.Kind.ORANGE, true); return null; });
        }
    }

    @Test public void filterLootHidesGridDropsOnlyAndSaysHowMany() throws Exception {
        Fake reader = new Fake(LootHighlightsTest::populated);
        LootHighlights view = view(reader);
        frame(view, 1240, 800);
        loaded(view);
        List<Integer> all = grid(view);
        assertEquals(List.of(1006, GREATER_DEFENSE, 1005, MANA, 1004, DEFENSE, 1002, 1001, LIFE, GREATER_LIFE), all);
        String ut = edt(() -> named(view, "loot-tile-ut", StatTile.class).valueText());
        edt(() -> { LootFilters.get().set(LootFilters.Kind.ORANGE, false); return null; });
        assertEquals("The two orange bags' drops are hidden; the unnamed bag is always shown", List.of(1006, GREATER_DEFENSE, 1005, MANA, 1001, LIFE, GREATER_LIFE), grid(view));
        edt(() -> {
            JLabel line = named(view, "loot-notable-filtered", JLabel.class);
            assertTrue(line.isVisible());
            assertEquals("Filter Loot hides 3 of 10 notable drops; the tiles still count them", line.getText());
            assertEquals("Tiles count every observed drop", ut, named(view, "loot-tile-ut", StatTile.class).valueText());
            return null;
        });
        assertEquals("Re-filtered without a read", 1, reader.reads.get());
        edt(() -> { for (LootFilters.Kind kind : LootFilters.Kind.values()) LootFilters.get().set(kind, false); return null; });
        edt(() -> {
            assertEquals(List.of(1005), view.notableList().items().stream().map(HighlightsModel.Notable::itemId).collect(Collectors.toList()));
            return null;
        });
        edt(() -> { for (LootFilters.Kind kind : LootFilters.Kind.values()) LootFilters.get().set(kind, true); return null; });
        assertEquals(all, grid(view));
        edt(() -> { assertFalse(named(view, "loot-notable-filtered", JLabel.class).isVisible()); return null; });
        assertEquals(1, reader.reads.get());
    }

    @Test public void runLinksOpenOnlyForExactVisitsAndStripCellsNameTheirDungeon() throws Exception {
        LootHighlights view = view(new Fake(LootHighlightsTest::populated));
        List<VisitRef> opened = new ArrayList<>();
        List<String> dungeons = new ArrayList<>();
        view.onOpenRun(opened::add);
        view.onDungeon(dungeons::add);
        frame(view, 1240, 800);
        loaded(view);
        edt(() -> {
            HighlightsModel.Notable linked = find(view, 1006), unlinked = find(view, 1005);
            view.open(unlinked);
            assertEquals("Not linked to a run: nothing opens", List.of(), opened);
            JMenuItem disabled = (JMenuItem) view.notableMenu(unlinked).getComponent(0);
            assertEquals("Open run recap", disabled.getText());
            assertFalse(disabled.isEnabled()); assertEquals("Not linked to a run", disabled.getToolTipText());
            view.open(linked);
            assertEquals(List.of(RUN), opened);
            JMenuItem recap = (JMenuItem) view.notableMenu(find(view, 1004)).getComponent(0);
            assertTrue(recap.isEnabled());
            recap.doClick();
            assertEquals(List.of(RUN, OTHER_RUN), opened);
            TileList<HighlightsModel.Notable> list = view.notableList();
            list.setSelectedIndex(list.items().indexOf(linked));
            list.getActionMap().get(TileList.OPEN).actionPerformed(null);
            assertEquals("Enter opens the selected drop's run", List.of(RUN, OTHER_RUN, RUN), opened);
            List<HighlightsModel.DungeonCell> cells = view.stripList().items();
            assertEquals("Lost Halls", cells.get(0).dungeon());
            assertNull("Unknown area last", cells.get(cells.size() - 1).dungeon());
            view.openDungeon(cells.get(0));
            view.openDungeon(cells.get(cells.size() - 1));
            assertEquals(Arrays.asList("Lost Halls", null), dungeons);
            return null;
        });
    }

    @Test public void statesSayLoadingUnavailableEmptyStaleAndPartial() throws Exception {
        AtomicReference<HighlightsModel> answer = new AtomicReference<>(HighlightsModel.unavailable(TODAY, SAVED, "Saved history could not be read: disk offline", NOON));
        Fake reader = new Fake(window -> answer.get());
        reader.gate = new CountDownLatch(1);
        LootHighlights view = view(reader);
        frame(view, 1240, 800);
        await("the first read", () -> reader.reads.get() == 1);
        edt(() -> {
            assertEquals("loading", view.state());
            assertTrue(named(view, "loot-highlights-loading", EmptyState.class).isShowing());
            return null;
        });
        reader.gate.countDown();
        reader.gate = null;
        await("the failed read", () -> !view.loading());
        edt(() -> {
            assertEquals("unavailable", view.state());
            EmptyState unavailable = named(view, "loot-highlights-unavailable", EmptyState.class);
            assertTrue(unavailable.isShowing());
            assertTrue(unavailable.getAccessibleContext().getAccessibleDescription(), unavailable.getAccessibleContext().getAccessibleDescription().contains("disk offline"));
            return null;
        });
        answer.set(populated(TODAY));
        edt(() -> { named(view, "loot-highlights-retry", JButton.class).doClick(); return null; });
        await("the retried read", () -> view.model() != null && !view.loading());
        edt(() -> {
            assertEquals("content", view.state());
            assertEquals("Saved history · Today", named(view, "loot-highlights-source", JLabel.class).getText());
            assertEquals("2", named(view, "loot-tile-ut", StatTile.class).valueText());
            assertEquals("2 Life · 1 Mana · 2 Def · 1 other", subline(named(view, "loot-tile-potions", StatTile.class)));
            assertFalse(named(view, "loot-highlights-stale", Banner.class).isVisible());
            return null;
        });
        answer.set(HighlightsModel.unavailable(TODAY, SAVED, "Saved history could not be read: disk offline", NOON + 60_000));
        edt(() -> { view.refresh(); return null; });
        await("the failed re-read", () -> !view.loading());
        edt(() -> {
            assertEquals("The last successful read stays", "content", view.state());
            Banner stale = named(view, "loot-highlights-stale", Banner.class);
            assertTrue(stale.isVisible());
            assertTrue(stale.text(), stale.text().contains("disk offline") && stale.text().startsWith("Showing the last successful read"));
            StatTile ut = named(view, "loot-tile-ut", StatTile.class);
            assertEquals(DisplayValue.State.STALE, ut.value().state);
            return null;
        });
        answer.set(HighlightsModel.of(TODAY, SAVED, List.of(bag("White", "Lost Halls", at(0, 9, 0), RUN, item(1, true, false, false, 0))), true, 2, false, NOON));
        edt(() -> { view.refresh(); return null; });
        await("the partial read", () -> view.model() != null && view.model().sessionsSkipped() == 2 && !view.loading());
        edt(() -> {
            assertFalse(named(view, "loot-highlights-stale", Banner.class).isVisible());
            Banner partial = named(view, "loot-highlights-partial", Banner.class);
            assertTrue(partial.isVisible());
            assertEquals("◐ 2 saved sessions could not be read; their loot is missing from these counts.", partial.text());
            assertEquals(DisplayValue.State.PARTIAL, named(view, "loot-tile-ut", StatTile.class).value().state);
            return null;
        });
        answer.set(empty(TODAY));
        edt(() -> { view.refresh(); return null; });
        await("the empty read", () -> view.model() != null && view.model().bags() == 0 && !view.loading());
        edt(() -> {
            EmptyState none = named(view, "loot-notable-empty", EmptyState.class);
            assertTrue(none.isShowing());
            assertEquals("No notable drops yet", none.getAccessibleContext().getAccessibleName());
            assertFalse(view.notableList().isVisible());
            assertEquals("—", named(view, "loot-tile-ut", StatTile.class).valueText());
            assertEquals(HighlightsModel.NO_LOOT, named(view, "loot-tile-ut", StatTile.class).value().detail);
            return null;
        });
        answer.set(HighlightsModel.of(TODAY, LIVE_UNSAVED, List.of(bag("White", "Lost Halls", at(0, 9, 0), RUN, item(1, true, false, false, 0))), true, 0, true, NOON));
        edt(() -> { view.refresh(); return null; });
        await("the live read", () -> view.model() != null && view.model().source() == LIVE_UNSAVED && !view.loading());
        edt(() -> {
            assertEquals("This app run · not saved · latest 1,000 bags", named(view, "loot-highlights-source", JLabel.class).getText());
            return null;
        });
    }

    @Test public void closeStopsTheWorkerAndTheChecks() throws Exception {
        Fake reader = new Fake(LootHighlightsTest::populated);
        LootHighlights view = view(reader);
        frame(view, 1240, 800);
        loaded(view);
        edt(() -> { view.close(); view.close(); view.refresh(); reader.revision.set(5); view.tick(); view.tick(); return null; });
        edt(() -> { LootFilters.get().set(LootFilters.Kind.WHITE, false); return null; });
        Thread.sleep(150);
        assertEquals("Nothing reads after close", 1, reader.reads.get());
    }

    @Test public void populatedHighlightsFitBothThemesAndNarrowLargeFonts() throws Exception {
        LootHighlights view = view(new Fake(LootHighlightsTest::populated));
        // The shell's workspace padding; the top also clears the test harness's painted window title.
        JPanel workspace = edt(() -> {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(new javax.swing.border.EmptyBorder(34, 12, 10, 12));
            panel.add(view, BorderLayout.CENTER);
            return panel;
        });
        SwingUtilities.invokeAndWait(() -> evidence.show(workspace, "Loot highlights 1240x800 font 13", 1240, 800, 13));
        loaded(view);
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane scroll = named(view, "loot-highlights-scroll", JScrollPane.class);
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
            List<StatTile> tiles = tiles(view);
            assertEquals(5, tiles.size());
            assertEquals("Five tiles in one row at 1240", 1, tiles.stream().map(tile -> tile.getY()).distinct().count());
            evidence.capture("loot-highlights-populated-1240x800-font13-dark");
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(SwingUtilities.getWindowAncestor(view));
            evidence.capture("loot-highlights-populated-1240x800-font13-light");
            evidence.show(workspace, "Loot highlights 680x520 font 18", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane scroll = named(view, "loot-highlights-scroll", JScrollPane.class);
            JComponent page = (JComponent) scroll.getViewport().getView();
            assertTrue("The page scrolls instead of squeezing", page.getHeight() > scroll.getViewport().getHeight());
            assertTrue("No horizontal overflow", page.getWidth() <= scroll.getViewport().getWidth());
            List<StatTile> tiles = tiles(view);
            assertTrue("The tiles wrap at 680", tiles.stream().map(tile -> tile.getY()).distinct().count() > 1);
            for (StatTile tile : tiles) assertTrue(tile.getName() + " fits", tile.getX() + tile.getWidth() <= page.getWidth());
            TileList<?> grid = view.notableList();
            assertTrue("The grid wraps within the page", grid.getWidth() <= page.getWidth() && grid.getHeight() > grid.getFixedCellHeight());
            evidence.capture("loot-highlights-populated-680x520-font18-light");
        });
    }

    /**
     * Polish A: at 1240×800 font 13 (five cards a row) and 680×520 font 18 (two) every notable card paints its area whole ("Lost
     * Halls", "Pirate Cave", "Unknown area"…), whatever its kind chip, at the grid's own cell size.
     */
    @Test public void notableCardsPaintTheirAreaWholeAtBothReferenceSizes() throws Exception {
        LootHighlights view = view(new Fake(LootHighlightsTest::populated));
        JPanel workspace = edt(() -> {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(new javax.swing.border.EmptyBorder(34, 12, 10, 12));
            panel.add(view, BorderLayout.CENTER);
            return panel;
        });
        SwingUtilities.invokeAndWait(() -> evidence.show(workspace, "Loot highlights cards 1240x800 font 13", 1240, 800, 13));
        loaded(view);
        evidence.settle();
        // Each size is captured before it is checked; the collector reports every size's failure at the end.
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("loot-highlights-cards-1240x800-font13-dark");
            errors.checkSucceeds(() -> { assertAreasWhole(view, 5, "1240x800 font 13"); return null; });
            evidence.show(workspace, "Loot highlights cards 680x520 font 18", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("loot-highlights-cards-680x520-font18-dark");
            errors.checkSucceeds(() -> { assertAreasWhole(view, 2, "680x520 font 18"); return null; });
        });
    }

    /**
     * Polish B2 (P6a evidence finding 1): the potions and white-bag sub-lines of the evidence day ("2 Life · 1 Mana · 1 Att · 1 Def
     * · +2 more", "of 9 bags · 1 without a bag name") no longer widen their tiles. At the real shell's content width at 1240×800
     * font 13 (about 1,010 px) the five tiles share one row and one height; at its 680×520 font 18 width (about 590 px) they wrap
     * (two by two). At both sizes every tile lies inside the page and each sub-line is whole: painted (wrapped between its parts)
     * or, only if a word cannot wrap, in the tile's tooltip.
     */
    @Test public void longSubLinesKeepFiveTilesInOneRowAtTheShellsDesktopWidth() throws Exception {
        LootHighlights view = view(new Fake(LootHighlightsTest::longLines));
        // The page as wide as the real shell's content (the sidebar's width is padded at the left).
        JPanel workspace = edt(() -> {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(new javax.swing.border.EmptyBorder(34, 1240 - 1010 - 12, 10, 12));
            panel.add(view, BorderLayout.CENTER);
            return panel;
        });
        SwingUtilities.invokeAndWait(() -> evidence.show(workspace, "Loot highlights long sub-lines 1240x800 font 13", 1240, 800, 13));
        loaded(view);
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("loot-highlights-long-sublines-1240x800-font13-dark");
            errors.checkSucceeds(() -> {
                assertEquals("The shell's content width", 1010, view.getWidth());
                assertEquals("2 Life · 1 Mana · 1 Att · 1 Def · +2 more", subline(named(view, "loot-tile-potions", StatTile.class)));
                assertEquals("of 9 bags · 1 without a bag name", subline(named(view, "loot-tile-whites", StatTile.class)));
                List<StatTile> tiles = tiles(view);
                assertEquals(5, tiles.size());
                assertEquals("Five tiles in one row at 1,010 px: " + bounds(tiles), 1, tiles.stream().map(Component::getY).distinct().count());
                assertEquals("One height for the row: " + bounds(tiles), 1, tiles.stream().map(Component::getHeight).distinct().count());
                assertTilesInside(view, tiles);
                for (String name : new String[] {"loot-tile-potions", "loot-tile-whites"}) assertSubLineWhole(named(view, name, StatTile.class));
                return null;
            });
            evidence.show(workspace, "Loot highlights long sub-lines 680x520 font 18", 680, 520, 18);
            workspace.setBorder(new javax.swing.border.EmptyBorder(34, 680 - 590 - 12, 10, 12));
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("loot-highlights-long-sublines-680x520-font18-dark");
            errors.checkSucceeds(() -> {
                assertEquals("The shell's compact content width", 590, view.getWidth());
                List<StatTile> tiles = tiles(view);
                long rows = tiles.stream().map(Component::getY).distinct().count();
                assertTrue("The tiles wrap at 590 px font 18 (two by two, or one per row): " + bounds(tiles), rows > 1);
                assertTilesInside(view, tiles);
                for (String name : new String[] {"loot-tile-potions", "loot-tile-whites"}) assertSubLineWhole(named(view, name, StatTile.class));
                return null;
            });
        });
    }

    /**
     * P6b Task 9 (R3 B9): at 1240×800 font 13 (five cards a row) the evidence day's synthetic names ("Synthetic Crystal Mail") and
     * the strip's summaries ("4 bags · 1 UT · 1 ST · 3 potions") paint whole: a long name takes a second line, a strip caption wraps
     * at its " · " boundaries. Tooltips and accessible names are unchanged. Captured in the dark and the light theme (p6a folder).
     */
    @Test public void longNamesAndStripSummariesPaintWholeAt1240x800Font13() throws Exception {
        Runnable restore = names(EVIDENCE_NAMES);
        try {
            LootHighlights view = view(new Fake(LootHighlightsTest::evidenceDay));
            JPanel workspace = edt(() -> {
                JPanel panel = new JPanel(new BorderLayout());
                panel.setBorder(new javax.swing.border.EmptyBorder(34, 12, 10, 12));
                panel.add(view, BorderLayout.CENTER);
                return panel;
            });
            SwingUtilities.invokeAndWait(() -> evidence.show(workspace, "Loot highlights names 1240x800 font 13", 1240, 800, 13));
            loaded(view);
            evidence.settle();
            // Each theme is captured before it is checked; the collector reports every failure at the end.
            SwingUtilities.invokeAndWait(() -> {
                evidence.capture("loot-highlights-names-1240x800-font13-dark");
                errors.checkSucceeds(() -> { assertNothingCut(view, "dark"); return null; });
                Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
                SwingUtilities.updateComponentTreeUI(SwingUtilities.getWindowAncestor(view));
            });
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                evidence.capture("loot-highlights-names-1240x800-font13-light");
                errors.checkSucceeds(() -> { assertNothingCut(view, "light"); return null; });
                TileList<HighlightsModel.Notable> grid = view.notableList();
                HighlightsModel.Notable mail = find(view, 9501);
                assertEquals("The accessible name includes the recorded rarity", "Synthetic Crystal Mail, ST (Rare · 2 enchant slots); Pirate Cave, today at 10:10; Orange bag; Enter opens the run recap",
                    NotableDropRenderer.accessibleName(mail, ZONE, NOON));
                @SuppressWarnings("unchecked") ListCellRenderer<HighlightsModel.Notable> renderer = (ListCellRenderer<HighlightsModel.Notable>) grid.getCellRenderer();
                JComponent card = (JComponent) renderer.getListCellRendererComponent(grid, mail, 0, false, false);
                assertEquals("…and the tooltip includes the shared enchant lines", EnchantTooltip.html("Synthetic Crystal Mail, ST; Pirate Cave, today at 10:10; Orange bag; Enter opens the run recap · " + HighlightsModel.OBSERVED, mail.enchant()), card.getToolTipText());
                HighlightsModel.DungeonCell halls = view.stripList().items().get(0);
                assertEquals("Lost Halls; 4 bags; 1 UT · 1 ST · 3 potions", DungeonStripRenderer.accessibleName(halls));
            });
        } finally {
            restore.run();
            SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
        }
    }

    /**
     * P6b Task 9 (R3 B10): Home's Notable loot tile passes its window, and {@code showWindow} applies it as a click on the window
     * choice does: the segment shows it, {@value LootHighlights#WINDOW_KEY} keeps it and the window is read. The window already shown
     * writes and reads nothing. Before the first show it is kept and the first show reads it, once.
     */
    @Test public void showWindowSelectsSavesAndReadsTheWindowAsAClickDoes() throws Exception {
        Fake reader = new Fake(LootHighlightsTest::populated);
        LootHighlights view = view(reader);
        frame(view, 1240, 800);
        loaded(view);
        assertEquals(List.of(TODAY), reader.windows);
        edt(() -> { view.showWindow(SESSION); return null; });
        await("the This session result", () -> view.model() != null && view.model().window() == SESSION && !view.loading());
        assertEquals(SESSION, edt(view::window));
        assertEquals("Kept as a click keeps it", List.of("ui.loot.highlights=session"), writes);
        assertEquals("The window choice shows it", 1, (int) edt(() -> named(view, "loot-highlights-window", SegmentedControl.class).selected()));
        assertEquals("Read once", List.of(TODAY, SESSION), reader.windows);
        edt(() -> { view.showWindow(SESSION); return null; });
        Thread.sleep(150);
        assertEquals("The window already shown writes nothing", 1, writes.size());
        assertEquals("…and reads nothing", 2, reader.reads.get());
        edt(() -> { view.showWindow(TODAY); return null; });
        await("the Today result", () -> view.model() != null && view.model().window() == TODAY && !view.loading());
        assertEquals(List.of("ui.loot.highlights=session", "ui.loot.highlights=today"), writes);
        assertEquals(0, (int) edt(() -> named(view, "loot-highlights-window", SegmentedControl.class).selected()));
        try { edt(() -> { view.showWindow(null); return null; }); fail(); } catch (AssertionError expected) {
            assertTrue(String.valueOf(expected.getCause()), expected.getCause() instanceof NullPointerException);
        }

        writes.clear();
        Fake later = new Fake(LootHighlightsTest::populated);
        LootHighlights hidden = view(later);
        edt(() -> { hidden.showWindow(SESSION); return null; });
        assertEquals("Kept before the first show", List.of("ui.loot.highlights=session"), writes);
        assertEquals(SESSION, edt(hidden::window));
        Thread.sleep(150);
        assertEquals("Not read while never shown", 0, later.reads.get());
        frame(hidden, 1240, 800);
        loaded(hidden);
        assertEquals("The first show reads the kept window, once", List.of(SESSION), later.windows);
    }

    /** Synthetic item names of the evidence day (no game assets). */
    static final Map<Integer, String> EVIDENCE_NAMES = Map.of(9101, "Synthetic Voidblade", 9102, "Synthetic Aegis Robe",
        9103, "Synthetic Tier Sword", 9201, "Synthetic Frost Staff", 9301, "Synthetic Tidal Dagger", 9401, "Synthetic Viper Bow",
        9501, "Synthetic Crystal Mail", 9601, "Synthetic Plain Ring", LIFE, "Life potion", GREATER_LIFE, "Greater life potion");

    /** The evidence day: long synthetic names; Lost Halls 4 bags (1 UT, 1 ST, 3 potions), Pirate Cave, Ice Citadel, Unknown area. */
    static HighlightsModel evidenceDay(HighlightsModel.Window window) {
        List<LootFacts.Bag> bags = List.of(
            bag("White", "Lost Halls", at(0, 9, 5), RUN, item(9101, true, false, false, 0), item(LIFE, false, false, true, null)),
            bag("Orange", "Lost Halls", at(0, 9, 20), RUN, item(9102, false, true, false, 0), item(GREATER_LIFE, false, false, true, null)),
            bag("Brown", "Lost Halls", at(0, 9, 40), RUN, item(DEFENSE, false, false, true, null)),
            bag("Cyan", "Lost Halls", at(0, 9, 50), RUN, item(9103, false, false, false, 0)),
            bag("Orange", "Pirate Cave", at(0, 10, 10), OTHER_RUN, item(9501, false, true, false, 2), item(9201, false, false, false, 3)),
            bag("B.White", "Pirate Cave", at(0, 10, 30), OTHER_RUN, item(9301, true, false, false, 4), item(MANA, false, false, true, null)),
            bag(null, HighlightsModel.UNRECOGNIZED, at(0, 11, 0), null, item(9401, false, true, false, null), item(GREATER_DEFENSE, false, false, true, null)),
            bag("Purple", "Ice Citadel", at(0, 11, 20), null, item(9601, false, false, false, 2), item(OTHER_POTION, false, false, true, null)));
        return HighlightsModel.of(window, SAVED, bags, true, 0, false, NOON);
    }

    /** Installs synthetic item names ({@code Sprites.name}); the returned action puts the previous names back. */
    static Runnable names(Map<Integer, String> names) {
        try {
            Field field = assets.IdToAsset.class.getDeclaredField("objectID");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") HashMap<Integer, assets.IdToAsset> previous = (HashMap<Integer, assets.IdToAsset>) field.get(null);
            HashMap<Integer, assets.IdToAsset> next = new HashMap<>(previous);
            for (Map.Entry<Integer, String> name : names.entrySet())
                next.put(name.getKey(), new assets.IdToAsset("", name.getKey(), name.getValue(), name.getValue(), "", null, "", "", ""));
            field.set(null, next);
            return () -> { try { field.set(null, previous); } catch (IllegalAccessException e) { throw new AssertionError(e); } };
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /**
     * Every notable card and strip cell, painted at its list's own cell size, paints every line whole (none ends in "…"); a card's
     * name lines rejoin to the item's name. Five cards a row.
     */
    private static void assertNothingCut(LootHighlights view, String theme) {
        TileList<HighlightsModel.Notable> grid = view.notableList();
        assertEquals(theme + ": five cards a row", 5, grid.getWidth() / grid.getFixedCellWidth());
        @SuppressWarnings("unchecked") ListCellRenderer<HighlightsModel.Notable> cards = (ListCellRenderer<HighlightsModel.Notable>) grid.getCellRenderer();
        List<HighlightsModel.Notable> items = grid.items();
        assertEquals(theme + ": every notable drop is listed", 12, items.size());
        for (int i = 0; i < items.size(); i++) {
            HighlightsModel.Notable drop = items.get(i);
            NotableDropRenderer card = (NotableDropRenderer) cards.getListCellRendererComponent(grid, drop, i, false, false);
            List<String> painted = painted(card, grid.getFixedCellWidth(), grid.getFixedCellHeight(), card::painted);
            String name = Sprites.name(drop.itemId());
            for (String line : painted) assertFalse(theme + ": '" + name + "' card cuts nothing: " + painted, line.endsWith("…"));
            int chip = painted.indexOf(drop.kind().label());
            assertEquals(theme + ": the name lines are the whole name: " + painted, name, String.join(" ", painted.subList(0, chip)));
        }
        TileList<HighlightsModel.DungeonCell> strip = view.stripList();
        @SuppressWarnings("unchecked") ListCellRenderer<HighlightsModel.DungeonCell> cells = (ListCellRenderer<HighlightsModel.DungeonCell>) strip.getCellRenderer();
        List<HighlightsModel.DungeonCell> areas = strip.items();
        assertEquals(theme + ": Lost Halls, Pirate Cave, Ice Citadel and Unknown area", 4, areas.size());
        for (int i = 0; i < areas.size(); i++) {
            DungeonStripRenderer cell = (DungeonStripRenderer) cells.getListCellRendererComponent(strip, areas.get(i), i, false, false);
            List<String> painted = painted(cell, strip.getFixedCellWidth(), strip.getFixedCellHeight(), cell::painted);
            for (String line : painted) assertFalse(theme + ": the strip cuts nothing: " + painted, line.endsWith("…"));
            assertEquals(theme + ": the caption rejoins whole: " + painted, DungeonStripRenderer.caption(areas.get(i)), String.join(" · ", painted.subList(1, painted.size())));
        }
    }

    private static List<String> painted(JComponent cell, int width, int height, java.util.function.Supplier<List<String>> painted) {
        cell.setSize(width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        cell.paint(g);
        g.dispose();
        return painted.get();
    }

    /** A sub-line wider than its tile breaks between its parts, then at spaces; every part stays whole where it fits. */
    @Test public void subLinesBreakBetweenPartsBeforeInsideAPart() throws Exception {
        edt(() -> {
            FontMetrics metrics = new JLabel().getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            int ch = metrics.charWidth('x');
            String text = "2 Life · 1 Mana · 1 Att · 1 Def · +2 more";
            assertEquals("Wide enough: one line", List.of(text), LootHighlights.SubLine.lines(text, metrics, ch * text.length()));
            assertEquals("Between parts, the separator dropped at the break", List.of("2 Life · 1 Mana · 1 Att", "1 Def · +2 more"),
                LootHighlights.SubLine.lines(text, metrics, ch * 24));
            assertEquals("One part a line when two do not fit", List.of("of 9 bags", "1 without a bag name"),
                LootHighlights.SubLine.lines("of 9 bags · 1 without a bag name", metrics, ch * 20));
            assertEquals("A part wider than the line breaks at its spaces", List.of("of 9 bags", "1 without a", "bag name"),
                LootHighlights.SubLine.lines("of 9 bags · 1 without a bag name", metrics, ch * 12));
            assertEquals(List.of(), LootHighlights.SubLine.lines("", metrics, 100));
            return null;
        });
    }

    /**
     * The tile's wrapping sub-line (its child 2, under the value) is whole: it has the height of its lines inside the tile, and its
     * last paint drew every word of the text (the lines rejoin to it, none cut with "…"), or, if a word was cut, its tooltip is the
     * whole text. The line count is printed.
     */
    private static void assertSubLineWhole(StatTile tile) {
        LootHighlights.SubLine sub = (LootHighlights.SubLine) tile.getComponent(2);
        String text = sub.getText();
        List<String> painted = sub.painted();
        System.out.println(tile.getName() + " sub-line in " + painted.size() + " line(s) at " + sub.getWidth() + " px: " + painted);
        assertTrue(tile.getName() + " shows its sub-line", sub.isVisible() && sub.getWidth() > 0);
        assertTrue(tile.getName() + ": the sub-line lies inside the tile: " + sub.getBounds() + " in " + tile.getSize(), new Rectangle(tile.getSize()).contains(sub.getBounds()));
        assertTrue(tile.getName() + ": the sub-line has the height of its lines", sub.getHeight() >= painted.size() * sub.getFontMetrics(sub.getFont()).getHeight());
        boolean whole = painted.stream().noneMatch(line -> line.endsWith("…")) && words(String.join(" ", painted)).equals(words(text));
        assertTrue(tile.getName() + ": painted " + painted + " for '" + text + "', tooltip " + sub.getToolTipText(), whole || text.equals(sub.getToolTipText()));
    }

    private static List<String> words(String text) { return Arrays.asList(text.replace("·", " ").trim().split("\\s+")); }

    /** Today in the evidence's shape: seven potions over six stats and nine bags, two of them white and one without a name. */
    static HighlightsModel longLines(HighlightsModel.Window window) {
        int attack = 2591, wisdom = 2613;
        List<LootFacts.Bag> bags = List.of(
            bag("White", "Lost Halls", at(0, 9, 5), RUN, item(1001, true, false, false, 2), item(LIFE, false, false, true, null)),
            bag("Orange", "Lost Halls", at(0, 9, 20), RUN, item(1002, false, true, false, 0), item(1003, false, false, false, 3)),
            bag("Cyan", "Ice Citadel", at(0, 9, 40), OTHER_RUN, item(1004, false, false, false, 2), item(DEFENSE, false, false, true, null), item(GREATER_LIFE, false, false, true, null)),
            bag("B.White", "Pirate Cave", at(0, 10, 0), OTHER_RUN, item(1005, true, false, false, 1)),
            bag("Purple", "Snake Pit", at(0, 10, 20), null, item(1006, false, false, false, null), item(MANA, false, false, true, null)),
            bag("Brown", HighlightsModel.UNRECOGNIZED, at(0, 10, 40), null, item(OTHER_POTION, false, false, true, null)),
            bag("Blue", "Lost Halls", at(0, 11, 0), RUN, item(attack, false, false, true, null), item(wisdom, false, false, true, null)),
            bag(null, "Lost Halls", at(0, 11, 10), RUN, item(1007, false, false, false, 0)),
            bag("Orange", "Ice Citadel", at(0, 11, 30), OTHER_RUN, item(1008, false, true, false, 1)));
        return HighlightsModel.of(window, SAVED, bags, true, 0, false, NOON);
    }

    private static void assertTilesInside(LootHighlights view, List<StatTile> tiles) {
        JComponent page = (JComponent) named(view, "loot-highlights-scroll", JScrollPane.class).getViewport().getView();
        for (StatTile tile : tiles) {
            Rectangle bounds = SwingUtilities.convertRectangle(tile.getParent(), tile.getBounds(), page);
            assertTrue(tile.getName() + " lies inside the page: " + bounds, bounds.x >= 0 && bounds.x + bounds.width <= page.getWidth());
        }
    }

    private static String bounds(List<StatTile> tiles) {
        return tiles.stream().map(tile -> tile.getName() + "@" + tile.getX() + "," + tile.getY() + " " + tile.getWidth() + "x" + tile.getHeight())
            .collect(Collectors.joining("; "));
    }

    private static void assertAreasWhole(LootHighlights view, int columns, String size) {
        TileList<HighlightsModel.Notable> grid = view.notableList();
        assertEquals(size + ": cards a row", columns, grid.getWidth() / grid.getFixedCellWidth());
        @SuppressWarnings("unchecked")
        ListCellRenderer<HighlightsModel.Notable> renderer = (ListCellRenderer<HighlightsModel.Notable>) grid.getCellRenderer();
        List<HighlightsModel.Notable> items = grid.items();
        assertFalse(items.isEmpty());
        for (int i = 0; i < items.size(); i++) {
            HighlightsModel.Notable drop = items.get(i);
            NotableDropRenderer card = (NotableDropRenderer) renderer.getListCellRendererComponent(grid, drop, i, false, false);
            card.setSize(grid.getFixedCellWidth(), grid.getFixedCellHeight());
            BufferedImage image = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            card.paint(g);
            g.dispose();
            String area = drop.dungeon() == null ? HighlightsModel.UNKNOWN_AREA : drop.dungeon();
            assertTrue(size + ": " + drop.kind() + " card paints '" + area + "' whole: " + card.painted(), card.painted().contains(area));
        }
    }

    private static List<StatTile> tiles(LootHighlights view) {
        List<StatTile> tiles = new ArrayList<>();
        for (String name : new String[] {"loot-tile-ut", "loot-tile-st", "loot-tile-potions", "loot-tile-whites", "loot-tile-enchanted"}) tiles.add(named(view, name, StatTile.class));
        return tiles;
    }
    private static String subline(StatTile tile) {
        JLabel sub = (JLabel) tile.getComponent(2);
        return sub.isVisible() ? sub.getText() : null;
    }
    private static HighlightsModel.Notable find(LootHighlights view, int id) {
        return view.notableList().items().stream().filter(n -> n.itemId() == id).findFirst().orElseThrow(() -> new AssertionError("No drop " + id));
    }

    static <T extends Component> T named(Container root, String name, Class<T> type) {
        T found = search(root, name, type);
        if (found == null) throw new AssertionError("No " + type.getSimpleName() + " named " + name);
        return found;
    }
    private static <T extends Component> T search(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = search((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
    @FunctionalInterface interface Checked<T> { T get() throws Exception; }
    static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
    static void await(String what, BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for " + what);
    }
}
