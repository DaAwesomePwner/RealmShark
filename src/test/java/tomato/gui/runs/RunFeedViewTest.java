package tomato.gui.runs;

import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.TileList;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;

/** The feed's Cards/Table views, filters, paging, empty states, reads only on new data, keyboard and activation, and its fit at 680 px. */
public class RunFeedViewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("redesign-p5a-runs");
    private static final String DRAWER = "ui.filters.run-feed.open";
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
    private final List<SessionStore> stores = new ArrayList<>();
    private final List<RunFeedView> views = new ArrayList<>();
    private String drawer;

    @Before public void remember() { drawer = PropertiesManager.getProperty(DRAWER); }

    @After public void release() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (RunFeedView view : views) view.close(); });
        for (SessionStore store : stores) store.close();
        PropertiesManager.setProperties(DRAWER, drawer == null ? "" : drawer);
    }

    /** A reader that counts reads, can fail or block, and whose stamp the test sets. */
    static final class Counting implements RunFeedView.Feed {
        final RunFeedView.Feed inner;
        final AtomicInteger firsts = new AtomicInteger(), mores = new AtomicInteger();
        final AtomicReference<Object> stamp = new AtomicReference<>("stamp-1");
        volatile IOException fail;
        volatile CountDownLatch gate;
        Counting(RunFeedView.Feed inner) { this.inner = inner; }
        @Override public Object stamp(Cancellation cancel) { return stamp.get(); }
        @Override public RunFeedSource.Page first(RunFeedQuery query, Cancellation cancel) throws IOException {
            firsts.incrementAndGet();
            CountDownLatch wait = gate;
            if (wait != null) try { wait.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (fail != null) throw fail;
            return inner.first(query, cancel);
        }
        @Override public RunFeedSource.Page more(RunFeedSource.Page previous, Cancellation cancel) throws IOException {
            mores.incrementAndGet();
            return inner.more(previous, cancel);
        }
    }

    private SessionStore store(Path root) {
        SessionStore store = new SessionStore(root, false, "fixture");
        stores.add(store);
        return store;
    }
    private Counting feed(SessionStore store, long now) throws Exception {
        RunFeedSource source = new RunFeedSource(store, HomeHistoryFixture.ZONE, () -> now);
        return new Counting(RunFeedView.feed(store, source));
    }
    private Counting scenario() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        return feed(store(root), RunFixtures.NOW);
    }
    private Counting twoDays() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.write(root);
        return feed(store(root), HomeHistoryFixture.NOW);
    }
    private RunFeedView view(JComponent table, Supplier<RunFeedView.Feed> feeds) throws Exception {
        RunFeedView view = edt(() -> new RunFeedView(table, feeds, HomeHistoryFixture.ZONE, mode, prefs::get,
            (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        views.add(view);
        return view;
    }
    private RunFeedView view(RunFeedView.Feed feed) throws Exception { return view(new JPanel(), () -> feed); }
    private static void load(RunFeedView view) throws Exception {
        edt(() -> { view.check(); return null; });
        await("the feed read", () -> !view.loading());
    }
    private static List<String> titles(RunFeedView view) {
        List<String> titles = new ArrayList<>();
        for (RunFeedModel.Day day : view.model().days()) titles.add(day.header());
        return titles;
    }
    private static String emptyTitle(RunFeedView view) {
        EmptyState empty = view.emptyState();
        return empty == null ? null : empty.getAccessibleContext().getAccessibleName();
    }

    @Test public void cardsShowFirstAndEachModeOffersTheOtherViewWhichIsRemembered() throws Exception {
        JPanel table = new JPanel(new BorderLayout());
        FilterBar tableBar = edt(() -> new FilterBar("runs-fixture"));
        edt(() -> { table.add(tableBar); return null; });
        RunFeedView view = view(table, () -> null);
        edt(() -> {
            assertFalse("The Runs page opens on the cards", view.tableShown());
            assertTrue("Restoring the default writes nothing", writes.isEmpty());
            JComponent row = named(view, "run-feed-view-row", JComponent.class);
            assertFalse("Simple: no toggle above the views", row.isVisible());
            JMenuItem toTable = view.filterBar().overflow().item("Table view"), toCards = tableBar.overflow().item("Cards view");
            assertEquals("run-feed-view-item", toTable.getName());
            assertEquals("run-feed-cards-item", toCards.getName());
            assertTrue(toTable.isVisible()); assertTrue(toCards.isVisible());
            assertSame("The Table view's own ⋯ keeps its items after the view item", toCards, tableBar.overflow().menu().getComponent(0));
            toTable.doClick();
            assertTrue(view.tableShown()); assertTrue(table.isVisible());
            assertEquals(List.of(RunFeedView.VIEW_KEY + "=table"), writes);
            toCards.doClick();
            assertFalse(view.tableShown()); assertFalse(table.isVisible());
            assertEquals(RunFeedView.CARDS, prefs.get(RunFeedView.VIEW_KEY));

            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst: the Cards/Table toggle", row.isVisible());
            assertFalse(toTable.isVisible()); assertFalse(toCards.isVisible());
            named(view, "run-feed-view-1", AbstractButton.class).doClick();
            assertTrue(view.tableShown());
            assertEquals(RunFeedView.TABLE, prefs.get(RunFeedView.VIEW_KEY));
            named(view, "run-feed-view-0", AbstractButton.class).doClick();
            assertFalse(view.tableShown());
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertFalse(row.isVisible()); assertTrue(toTable.isVisible());
            return null;
        });
        prefs.put(RunFeedView.VIEW_KEY, RunFeedView.TABLE);
        int before = writes.size();
        RunFeedView restored = view(new JPanel(), () -> null);
        edt(() -> {
            assertTrue("A saved Table view is restored", restored.tableShown());
            assertEquals("Restoring only selects", before, writes.size());
            restored.showCards();
            assertFalse(restored.tableShown());
            restored.showTable();
            assertTrue(restored.tableShown());
            return null;
        });
    }

    @Test public void theSearchOutcomesAndDungeonBecomeTheQueryAndItsChips() throws Exception {
        Counting feed = scenario();
        RunFeedView view = view(feed);
        load(view);
        edt(() -> {
            @SuppressWarnings("unchecked") JComboBox<String> map = named(view, "run-feed-map", JComboBox.class);
            List<String> dungeons = new ArrayList<>();
            for (int i = 0; i < map.getItemCount(); i++) dungeons.add(map.getItemAt(i));
            assertEquals("The dungeons among the runs read", List.of(RunFeedView.ALL_DUNGEONS, "Ice Citadel", "Lost Halls", "Pirate Cave", "Snake Pit"), dungeons);
            JTextField search = named(view, "run-feed-search", JTextField.class);
            search.setText("Lost");
            search.postActionEvent();
            assertEquals(new RunFeedQuery("Lost", Set.of(), null), view.query());
            named(view, "run-feed-outcome-completed", JCheckBox.class).doClick();
            named(view, "run-feed-outcome-app-ended", JCheckBox.class).doClick();
            assertEquals(EnumSet.of(RunOutcome.COMPLETED, RunOutcome.APP_ENDED), view.query().outcomes());
            map.setSelectedItem("Lost Halls");
            assertEquals(new RunFeedQuery("Lost", EnumSet.of(RunOutcome.COMPLETED, RunOutcome.APP_ENDED), "Lost Halls"), view.query());
            assertEquals("Search, outcome and dungeon chips", 3, view.filterBar().activeCount());
            return null;
        });
        await("the filtered read", () -> !view.loading());
        edt(() -> {
            List<VisitRef> refs = new ArrayList<>();
            for (RunCardModel card : view.model().cards()) refs.add(card.ref());
            assertEquals("Lost Halls runs that completed or ended with the app", List.of(RunFixtures.B4, RunFixtures.A1), refs);
            named(view, "run-feed-clear-filters", AbstractButton.class).doClick();
            assertEquals(RunFeedQuery.all(), view.query());
            assertEquals("", named(view, "run-feed-search", JTextField.class).getText());
            assertFalse(named(view, "run-feed-outcome-completed", JCheckBox.class).isSelected());
            assertEquals(RunFeedView.ALL_DUNGEONS, named(view, "run-feed-map", JComboBox.class).getSelectedItem());
            assertEquals(0, view.filterBar().activeCount());
            named(view, "run-feed-search", JTextField.class).setText("Snake");
            return null;
        });
        await("typing applies the search after a pause", () -> view.query().text().equals("Snake") && !view.loading());
    }

    /**
     * P5b: the dungeon choices are canonical dungeons (the Dungeons cards' names), so a raw alias is no separate choice, and
     * {@link RunFeedView#showDungeon} (the Dungeons tab's "Show runs") brings the cards forward with only that dungeon's filter:
     * every run of the card, alias and canonical, under one chip.
     */
    @Test public void dungeonChoicesAreCanonicalAndShowDungeonShowsEveryRunOfThatDungeonOnTheCards() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.writeMixed(root);
        RunFeedView view = view(feed(store(root), RunFixtures.NOW));
        load(view);
        edt(() -> {
            @SuppressWarnings("unchecked") JComboBox<String> map = named(view, "run-feed-map", JComboBox.class);
            List<String> dungeons = new ArrayList<>();
            for (int i = 0; i < map.getItemCount(); i++) dungeons.add(map.getItemAt(i));
            assertEquals("One choice per canonical dungeon; the raw alias is not one", List.of(RunFeedView.ALL_DUNGEONS, "Ice Citadel", "Lost Halls",
                "Pirate Cave", "Snake Pit", RunFixtures.CRONUS), dungeons);
            named(view, "run-feed-search", JTextField.class).setText("Lost");
            named(view, "run-feed-search", JTextField.class).postActionEvent();
            named(view, "run-feed-outcome-completed", JCheckBox.class).doClick();
            view.showTable();
            writes.clear();
            view.showDungeon(RunFixtures.CRONUS);
            assertFalse("Show runs brings the cards forward", view.tableShown());
            assertEquals("…and remembers them, as explicit navigation does", List.of(RunFeedView.VIEW_KEY + "=" + RunFeedView.CARDS), writes);
            assertEquals("Only the dungeon filter: every run of that dungeon", new RunFeedQuery("", Set.of(), RunFixtures.CRONUS), view.query());
            assertEquals(RunFixtures.CRONUS, map.getSelectedItem());
            assertEquals("", named(view, "run-feed-search", JTextField.class).getText());
            assertFalse(named(view, "run-feed-outcome-completed", JCheckBox.class).isSelected());
            assertEquals("One chip, the dungeon's", 1, view.filterBar().activeCount());
            return null;
        });
        await("the dungeon's runs", () -> !view.loading());
        edt(() -> {
            List<VisitRef> refs = new ArrayList<>();
            for (RunCardModel card : view.model().cards()) refs.add(card.ref());
            assertEquals("c5 (saved under the raw alias) and d3", List.of(RunFixtures.C5, RunFixtures.D3), refs);
            @SuppressWarnings("unchecked") JComboBox<String> map = named(view, "run-feed-map", JComboBox.class);
            assertEquals("Filtered reads keep the choices", 6, map.getItemCount());
            view.showDungeon(null);
            assertEquals("No dungeon: every saved run", RunFeedQuery.all(), view.query());
            return null;
        });
        await("every run", () -> !view.loading());
    }

    @Test public void fiftyRunsLoadAtATimeAndARereadKeepsAsManyLoaded() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.writeLarge(root, HomeHistoryFixture.LARGE_SESSIONS, HomeHistoryFixture.LARGE_RUNS);
        Counting feed = feed(store(root), HomeHistoryFixture.NOW);
        RunFeedView view = view(feed);
        long started = System.nanoTime();
        load(view);
        System.out.println("RunFeedView first page over " + HomeHistoryFixture.LARGE_SESSIONS * HomeHistoryFixture.LARGE_RUNS + " runs: "
            + (System.nanoTime() - started) / 1_000_000 + " ms");
        edt(() -> {
            assertEquals(RunFeedSource.PAGE, view.model().cards().size());
            assertEquals(List.of("Today · 50 runs · 25 completed · 2 h 5 m · more below"), titles(view));
            AbstractButton more = named(view, "run-feed-load-more", AbstractButton.class);
            assertTrue(more.isVisible());
            assertEquals("Saved runs · newest first · 50 of 1,200 loaded", named(view, "run-feed-summary", JTextArea.class).getText());
            more.doClick();
            assertFalse("Load more waits for its read", more.isEnabled());
            return null;
        });
        await("the next page", () -> !view.loading());
        edt(() -> {
            assertEquals(2 * RunFeedSource.PAGE, view.model().cards().size());
            assertEquals(1, feed.firsts.get()); assertEquals(1, feed.mores.get());
            return null;
        });
        feed.stamp.set("stamp-2");
        load(view);
        edt(() -> {
            assertEquals("A changed store is read again", 2, feed.firsts.get());
            assertEquals("…keeping as many runs loaded", 2 * RunFeedSource.PAGE, view.model().cards().size());
            return null;
        });
    }

    @Test public void emptyStatesSayWhetherHistoryIsMissingLoadingUnreadableEmptyOrUnmatched() throws Exception {
        JPanel table = new JPanel();
        RunFeedView missing = view(table, () -> null);
        load(missing);
        edt(() -> {
            assertEquals("Saved history is unavailable", emptyTitle(missing));
            named(missing, "run-feed-empty-action", AbstractButton.class).doClick();
            assertTrue("It offers the Table view", missing.tableShown());
            return null;
        });
        prefs.remove(RunFeedView.VIEW_KEY);   // the next feeds open on their cards

        RunFeedView none = view(feed(store(temp.newFolder("empty").toPath()), HomeHistoryFixture.NOW));
        load(none);
        edt(() -> { assertEquals("No saved runs yet", emptyTitle(none)); assertTrue(none.model().cards().isEmpty()); return null; });

        Counting feed = scenario();
        feed.gate = new CountDownLatch(1);
        RunFeedView view = view(feed);
        edt(() -> { view.check(); assertEquals("Loading saved runs", emptyTitle(view)); return null; });
        feed.gate.countDown();
        await("the read", () -> !view.loading());
        edt(() -> {
            assertNull(emptyTitle(view));
            view.setQuery(new RunFeedQuery("no run is called this", Set.of(), null));
            return null;
        });
        await("the empty search", () -> !view.loading());
        edt(() -> {
            assertEquals("No runs match", emptyTitle(view));
            named(view, "run-feed-empty-action", AbstractButton.class).doClick();
            assertEquals(RunFeedQuery.all(), view.query());
            return null;
        });
        await("all runs", () -> !view.loading());

        feed.fail = new IOException("synthetic unreadable history");
        edt(() -> { view.refresh(); return null; });
        await("the failed refresh", () -> !view.loading());
        edt(() -> {
            assertNull("A failed refresh keeps the last cards", emptyTitle(view));
            JComponent warn = named(view, "run-feed-issues", JComponent.class);
            assertTrue(warn.isVisible());
            assertTrue(warn.getAccessibleContext().getAccessibleName(), warn.getAccessibleContext().getAccessibleName()
                .contains("synthetic unreadable history. The cards below are from the last successful read."));
            view.setQuery(new RunFeedQuery("Lost", Set.of(), null));
            return null;
        });
        await("the failed read", () -> !view.loading());
        edt(() -> {
            assertEquals("Saved runs could not be read", emptyTitle(view));
            assertTrue(view.emptyState().getAccessibleContext().getAccessibleDescription().contains("synthetic unreadable history"));
            return null;
        });
    }

    /**
     * Each situation says it once: one empty state (a title and one body sentence, the title, body and action on one left edge)
     * and no summary line above it; the summary returns with the cards.
     */
    @Test public void theEmptyFeedSaysItOnceWithItsTitleAndBodyOnOneEdge() throws Exception {
        RunFeedView missing = view(new JPanel(), () -> null);
        load(missing);
        edt(() -> { onlyTheEmptyState(missing, "Saved history is unavailable"); return null; });

        RunFeedView none = view(feed(store(temp.newFolder("empty").toPath()), HomeHistoryFixture.NOW));
        edt(() -> { evidence.show(none, "Runs feed", 1240, 800, 13); return null; });
        load(none);
        evidence.settle();
        edt(() -> {
            onlyTheEmptyState(none, "No saved runs yet");
            EmptyState empty = none.emptyState();
            JLabel title = VisualEvidence.find(empty, JLabel.class, label -> true);
            JTextArea body = VisualEvidence.find(empty, JTextArea.class, area -> true);
            int text = body.getX() + body.getInsets().left;
            assertEquals("The title starts where the body's text does", text, title.getX() + title.getInsets().left);
            evidence.capture("run-feed-empty-1240-13");
            return null;
        });

        Counting feed = scenario();
        feed.gate = new CountDownLatch(1);
        RunFeedView view = view(feed);
        edt(() -> { view.check(); onlyTheEmptyState(view, "Loading saved runs"); return null; });
        feed.gate.countDown();
        await("the read", () -> !view.loading());
        edt(() -> {
            assertNull(emptyTitle(view));
            assertTrue("The summary shows with the cards", named(view, "run-feed-summary", JTextArea.class).isVisible());
            view.setQuery(new RunFeedQuery("no run is called this", Set.of(), null));
            return null;
        });
        await("the empty search", () -> !view.loading());
        edt(() -> {
            onlyTheEmptyState(view, "No runs match");
            assertTrue("A button beside the body", named(view, "run-feed-empty-action", AbstractButton.class).isVisible());
            return null;
        });
        feed.fail = new IOException("synthetic unreadable history");
        edt(() -> { view.setQuery(new RunFeedQuery("Lost", Set.of(), null)); return null; });
        await("the failed read", () -> !view.loading());
        edt(() -> { onlyTheEmptyState(view, "Saved runs could not be read"); return null; });
    }

    /** {@code view} shows the empty state titled {@code title} with one body sentence, and no summary line. */
    private static void onlyTheEmptyState(RunFeedView view, String title) {
        assertEquals(title, emptyTitle(view));
        String body = view.emptyState().getAccessibleContext().getAccessibleDescription();
        assertTrue(title + ": one body sentence: " + body, body.endsWith(".") && !body.substring(0, body.length() - 1).contains(". "));
        assertFalse(title + ": the body does not repeat the title: " + body, body.toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT)));
        assertFalse(title + ": no summary line above the empty state", named(view, "run-feed-summary", JTextArea.class).isVisible());
    }

    @Test public void showingAgainReadsOnlyWhenTheStoreChangedAndRebuildsNothingWithoutNewData() throws Exception {
        Counting feed = scenario();
        RunFeedView view = view(feed);
        load(view);
        LocalDate today = HomeHistoryFixture.DAY;
        AtomicInteger events = new AtomicInteger();
        edt(() -> {
            assertEquals(1, feed.firsts.get());
            view.list(today).getModel().addListDataListener(new ListDataListener() {
                public void intervalAdded(ListDataEvent e) { events.incrementAndGet(); }
                public void intervalRemoved(ListDataEvent e) { events.incrementAndGet(); }
                public void contentsChanged(ListDataEvent e) { events.incrementAndGet(); }
            });
            return null;
        });
        load(view);
        edt(() -> { assertEquals("An unchanged stamp reads nothing", 1, feed.firsts.get()); return null; });
        feed.stamp.set("stamp-2");
        load(view);
        edt(() -> {
            assertEquals("A changed stamp reads again", 2, feed.firsts.get());
            assertEquals("The same runs rebuild nothing", 0, events.get());
            view.filterBar().overflow().item("Refresh").doClick();
            return null;
        });
        await("the refresh", () -> !view.loading());
        edt(() -> { assertEquals("Refresh always reads", 3, feed.firsts.get()); assertEquals(0, events.get()); return null; });
        edt(() -> { view.showTable(); view.check(); return null; });
        feed.stamp.set("stamp-3");
        edt(() -> { view.check(); assertFalse("The hidden cards never read", view.loading()); return null; });
        assertEquals(3, feed.firsts.get());
    }

    @Test public void aNewerRequestWinsAndAnOlderResultIsDiscarded() throws Exception {
        Counting feed = scenario();
        feed.gate = new CountDownLatch(1);
        RunFeedView view = view(feed);
        edt(() -> { view.check(); view.setQuery(new RunFeedQuery("Snake", Set.of(), null)); return null; });
        feed.gate.countDown();
        await("both reads", () -> !view.loading() && view.model() != null);
        edt(() -> {
            assertEquals(new RunFeedQuery("Snake", Set.of(), null), view.query());
            assertEquals("Only the newest request's runs show", List.of(RunFixtures.A2),
                view.model().cards().stream().map(RunCardModel::ref).collect(java.util.stream.Collectors.toList()));
            return null;
        });
    }

    @Test public void sessionsReadOnlyInPartSayItOnAWarnLine() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        SessionStore store = store(root);
        RunFeedSource source = new RunFeedSource(store, HomeHistoryFixture.ZONE, () -> RunFixtures.NOW);
        source.combatReader((s, catalog, scope, sink) -> {
            if (scope.equals(RunFixtures.A)) throw new IOException("synthetic listing failure");
            CombatFacts.read(s, catalog, scope, sink);
        });
        RunFeedView view = view(RunFeedView.feed(store, source));
        load(view);
        edt(() -> {
            JComponent warn = named(view, "run-feed-issues", JComponent.class);
            assertTrue(warn.isVisible());
            assertEquals(RunFeedView.ISSUES, warn.getAccessibleContext().getAccessibleName());
            assertTrue("The details are in the tooltip", warn.getToolTipText().contains("encounters could not be read"));
            RunCardModel a1 = view.model().cards().stream().filter(card -> card.ref().equals(RunFixtures.A1)).findFirst().orElseThrow();
            assertTrue(RunCardRenderer.accessibleName(a1, HomeHistoryFixture.ZONE, RunFixtures.NOW).contains("combat records for this session could not be read"));
            return null;
        });
    }

    @Test public void arrowsStayWithinADayTabMovesToTheNextAndEnterSpaceOrDoubleClickOpenTheRun() throws Exception {
        Counting feed = twoDays();
        RunFeedView view = view(feed);
        List<VisitRef> opened = new ArrayList<>();
        edt(() -> { view.onOpen(opened::add); evidence.show(view, "Runs feed keyboard", 680, 520, 18); return null; });
        await("the read", () -> !view.loading() && view.model() != null);
        evidence.settle();
        LocalDate today = HomeHistoryFixture.DAY, yesterday = today.minusDays(1);
        edt(() -> {
            assertEquals(List.of("Today · 4 runs · 2 completed · 1 h 55 m", "Yesterday · 3 runs · 2 completed · 1 h 10 m"), titles(view));
            TileList<RunCardModel> first = view.list(today), second = view.list(yesterday);
            assertEquals("run-feed-day-" + today, first.getName());
            assertEquals(titles(view).get(0), first.getAccessibleContext().getAccessibleName());
            Window window = SwingUtilities.getWindowAncestor(view);
            assertSame("Tab moves from one day to the next", second, window.getFocusTraversalPolicy().getComponentAfter(window, first));
            first.dispatchEvent(new FocusEvent(first, FocusEvent.FOCUS_GAINED));
            assertEquals("Tabbing into a day selects its first run", 0, first.getSelectedIndex());
            first.setSelectedIndex(first.getModel().getSize() - 1);
            first.dispatchEvent(new KeyEvent(first, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED));
            assertEquals("Arrows stay within the day", first.getModel().getSize() - 1, first.getSelectedIndex());
            assertTrue(second.isSelectionEmpty());
            first.setSelectedIndex(0);
            first.dispatchEvent(new KeyEvent(first, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED));
            assertEquals(1, first.getSelectedIndex());
            second.setSelectedIndex(0);
            assertTrue("One selected run in the feed", first.isSelectionEmpty());
            second.getActionMap().get(TileList.OPEN).actionPerformed(null);
            assertEquals(TileList.OPEN, second.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)));
            assertEquals(TileList.OPEN, second.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
            Rectangle cell = first.getCellBounds(2, 2);
            first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + cell.width / 2,
                cell.y + cell.height / 2, 2, false, MouseEvent.BUTTON1));
            assertEquals(List.of(new VisitRef(HomeHistoryFixture.ACROSS, "b1"), first.getModel().getElementAt(2).ref()), opened);
            return null;
        });
    }

    @Test public void at680By520AndFont18TheCardsReflowWithoutScrollingSideways() throws Exception {
        Path root = temp.newFolder("history").toPath();
        HomeHistoryFixture.write(root);
        RunFixtures.write(root);   // today's linked runs with your row, fame, party and exalt progress beside yesterday's
        Counting feed = feed(store(root), RunFixtures.NOW);
        RunFeedView view = view(feed);
        edt(() -> { evidence.show(view, "Runs feed", 1240, 800, 13); return null; });
        await("the read", () -> !view.loading() && view.model() != null);
        evidence.settle();
        LocalDate today = HomeHistoryFixture.DAY;
        int wide = edt(() -> columns(view.list(today)));
        assertTrue("Several cards per row when wide: " + wide, wide >= 2);
        edt(() -> { evidence.capture("run-feed-1240-13"); evidence.show(view, "Runs feed", 680, 520, 18); return null; });
        evidence.settle();
        edt(() -> {
            JScrollPane scroll = named(view, "run-feed-scroll", JScrollPane.class);
            JViewport viewport = scroll.getViewport();
            assertFalse("No horizontal scroll bar", scroll.getHorizontalScrollBar().isVisible());
            assertEquals("The page never scrolls sideways", viewport.getWidth(), viewport.getView().getWidth());
            for (RunFeedModel.Day day : view.model().days()) {
                TileList<RunCardModel> list = view.list(day.date());
                Rectangle placed = SwingUtilities.convertRectangle(list.getParent(), list.getBounds(), viewport.getView());
                assertTrue(list.getName() + " fits: " + placed, placed.x >= 0 && placed.x + placed.width <= viewport.getWidth());
                assertTrue("Whole cards: " + list.getFixedCellWidth() + " in " + list.getWidth(), list.getFixedCellWidth() <= list.getWidth());
                assertEquals("One card per row at 680 px, font 18", 1, columns(list));
                JTextArea counts = named(view, "run-feed-counts-" + day.date(), JTextArea.class);
                VisualEvidence.completeText(counts);
            }
            VisualEvidence.completeText(named(view, "run-feed-summary", JTextArea.class));
            VisualEvidence.reachable(named(view, "run-feed-search", JTextField.class));
            evidence.capture("run-feed-680-18");
            return null;
        });
    }

    @Test public void theStoreStampFollowsTheFilesTheFeedReadsAndNotTheJournalsItSkips() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        SessionStore store = store(root);
        List<String> before = RunFeedView.stamp(store, new Cancellation());
        assertEquals("Listing again changes nothing", before, RunFeedView.stamp(store, new Cancellation()));
        Thread.sleep(50);   // past the file-time resolution
        java.nio.file.Files.write(root.resolve(RunFixtures.A).resolve("chat.jsonl"), List.of("{}"), java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(root.resolve(RunFixtures.A).resolve("timeline.jsonl"), List.of("{}"), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Chat and timeline writes are not the feed's", before, RunFeedView.stamp(store, new Cancellation()));
        HomeHistoryFixture.runs(root, RunFixtures.A, HomeHistoryFixture.visit("v9", "Snake Pit", HomeHistoryFixture.at(0, 9, 50), 0, false));
        List<String> saved = RunFeedView.stamp(store, new Cancellation());
        assertNotEquals("A saved visit changes it", before, saved);
        Thread.sleep(50);
        HomeHistoryFixture.fame(root, RunFixtures.B, RunFixtures.fame(null, 20_600, HomeHistoryFixture.at(0, 11, 40)));
        List<String> fame = RunFeedView.stamp(store, new Cancellation());
        assertNotEquals("A fame reading changes it", saved, fame);
        RunFixtures.session(root, HomeHistoryFixture.id("runs-c"), HomeHistoryFixture.at(0, 11, 50), 0);
        assertNotEquals("A new session changes it", fame, RunFeedView.stamp(store, new Cancellation()));
        try { edt(() -> RunFeedView.stamp(store, new Cancellation())); fail("The stamp lists folders off the EDT only"); }
        catch (AssertionError expected) { assertTrue(String.valueOf(expected.getCause()), expected.getCause() instanceof IllegalStateException); }
    }

    private static int columns(JList<?> list) { return Math.max(1, list.getWidth() / Math.max(1, list.getFixedCellWidth())); }

    static <T extends Component> T named(Container root, String name, Class<T> type) {
        T found = find(root, name, type);
        if (found == null) throw new AssertionError("No " + type.getSimpleName() + " named " + name);
        return found;
    }
    private static <T extends Component> T find(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, name, type); if (found != null) return found; }
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
