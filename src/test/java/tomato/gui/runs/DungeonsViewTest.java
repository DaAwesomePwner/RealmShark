package tomato.gui.runs;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.TileList;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.runs.RunFeedViewTest.await;
import static tomato.gui.runs.RunFeedViewTest.edt;
import static tomato.gui.runs.RunFeedViewTest.named;

/**
 * The Dungeons tab: reads on first show and when the store's stamp changes (never a rebuild without new data), the search and
 * sort over the loaded cards, one empty state per situation, the card actions' callbacks, and the Analyst-only Analysis view
 * (built on first use, hidden in Simple, closed with the view).
 */
public class DungeonsViewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public ui.VisualEvidence evidence = new ui.VisualEvidence("p5b");
    private static final String DRAWER = "ui.filters.dungeons.open";
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
    private final List<DungeonsView> views = new ArrayList<>();
    private final List<SessionStore> stores = new ArrayList<>();
    private final List<JFrame> frames = new ArrayList<>();
    private String drawer;

    @Before public void remember() { drawer = PropertiesManager.getProperty(DRAWER); }

    @After public void release() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (DungeonsView view : views) view.close(); for (JFrame frame : frames) frame.dispose(); });
        for (SessionStore store : stores) store.close();
        PropertiesManager.setProperties(DRAWER, drawer == null ? "" : drawer);
    }

    /** A reader that counts reads, can fail or block, and whose stamp and model the test sets. */
    static final class Counting implements DungeonsView.Cards {
        final AtomicInteger reads = new AtomicInteger(), stamps = new AtomicInteger();
        final AtomicReference<Object> stamp = new AtomicReference<>("stamp-1");
        final AtomicReference<DungeonsModel> model;
        volatile IOException fail;
        volatile CountDownLatch gate;
        Counting(DungeonsModel model) { this.model = new AtomicReference<>(model); }
        @Override public Object stamp(Cancellation cancel) { stamps.incrementAndGet(); return stamp.get(); }
        @Override public DungeonsModel read(Cancellation cancel) throws IOException {
            reads.incrementAndGet();
            CountDownLatch wait = gate;
            if (wait != null) try { wait.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (fail != null) throw fail;
            return model.get();
        }
    }

    /** A stub Analysis view that counts how often it is built and whether it was closed. */
    static final class Stub extends JPanel implements AutoCloseable {
        static final AtomicInteger built = new AtomicInteger();
        boolean closed;
        Stub() { built.incrementAndGet(); setName("dungeons-analysis-stub"); }
        @Override public void close() { closed = true; }
    }

    private static DungeonCardModel card(String name, int visits, long last, VisitRef best) {
        return new DungeonCardModel(name, name, 0, visits, visits, 0, 0, 0, 0, 1.0, 600_000L, visits, 2.0, visits, 0,
            best == null ? null : 100.0, best, best == null ? null : "r-" + name, null, null, null, best == null ? DungeonCardModel.NO_RECORDING : null, last);
    }
    private static final DungeonCardModel HALLS = card("Lost Halls", 7, 3_000, RunFixtures.C1), SNAKE = card("Snake Pit", 2, 5_000, null),
        CAVE = card("Pirate Cave", 3, 1_000, null);
    private static DungeonsModel three() { return new DungeonsModel(List.of(HALLS, CAVE, SNAKE), 12, 0, List.of(), 9_000); }

    private DungeonsView view(Supplier<DungeonsView.Cards> cards, Supplier<JComponent> analysis) throws Exception {
        DungeonsView view = edt(() -> new DungeonsView(cards, analysis, mode, prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        views.add(view);
        return view;
    }
    private DungeonsView view(DungeonsView.Cards cards) throws Exception { return view(() -> cards, Stub::new); }
    private static void load(DungeonsView view) throws Exception {
        edt(() -> { view.check(); return null; });
        await("the dungeons read", () -> !view.loading());
    }
    private static List<String> shown(DungeonsView view) { return view.shownCards().stream().map(DungeonCardModel::canonical).collect(Collectors.toList()); }
    private static String emptyTitle(DungeonsView view) {
        EmptyState empty = view.emptyState();
        return empty == null ? null : empty.getAccessibleContext().getAccessibleName();
    }
    private JFrame frame(JComponent content) throws Exception {
        return edt(() -> {
            JFrame frame = new JFrame("Dungeons fixture");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setContentPane(content);
            frame.setSize(1240, 800);
            frame.setVisible(true);
            frames.add(frame);
            return frame;
        });
    }

    @Test public void readsOnFirstShowOnlyAndRebuildsNothingWithoutNewData() throws Exception {
        Counting cards = new Counting(three());
        DungeonsView view = view(cards);
        Thread.sleep(100);
        assertEquals("Building the tab reads nothing", 0, cards.reads.get() + cards.stamps.get());
        frame(view);
        await("the first show's read", () -> view.model() != null && !view.loading());
        assertEquals(1, cards.reads.get());
        AtomicInteger events = new AtomicInteger();
        edt(() -> {
            assertEquals("Named dungeons-view", "dungeons-view", view.getName());
            assertEquals(List.of("Lost Halls", "Pirate Cave", "Snake Pit"), shown(view));
            assertEquals("Saved runs · all sessions · 3 dungeons · 12 runs · most visits first", named(view, "dungeons-summary", JTextArea.class).getText());
            view.cardList().getModel().addListDataListener(new ListDataListener() {
                public void intervalAdded(ListDataEvent e) { events.incrementAndGet(); }
                public void intervalRemoved(ListDataEvent e) { events.incrementAndGet(); }
                public void contentsChanged(ListDataEvent e) { events.incrementAndGet(); }
            });
            return null;
        });
        load(view);
        assertEquals("An unchanged stamp reads nothing", 1, cards.reads.get());
        cards.stamp.set("stamp-2");
        cards.model.set(new DungeonsModel(List.of(HALLS, CAVE, SNAKE), 12, 0, List.of(), 19_000));
        load(view);
        assertEquals("A changed stamp reads again", 2, cards.reads.get());
        assertEquals("The same cards rebuild nothing", 0, events.get());
        edt(() -> { view.filterBar().overflow().item("Refresh").doClick(); return null; });
        await("the refresh", () -> !view.loading());
        assertEquals("Refresh always reads", 3, cards.reads.get());
        assertEquals(0, events.get());
        edt(() -> { frames.get(0).setVisible(false); return null; });
        cards.stamp.set("stamp-3");
        edt(() -> { frames.get(0).setVisible(true); return null; });
        await("the second show's check", () -> cards.reads.get() == 4 && !view.loading());
    }

    @Test public void searchAndSortApplyToTheLoadedCardsWithChipsAndWithoutReading() throws Exception {
        Counting cards = new Counting(three());
        DungeonsView view = view(cards);
        load(view);
        edt(() -> {
            JTextField search = named(view, "dungeons-search", JTextField.class);
            search.setText("  CAVE ");
            assertEquals(new DungeonsQuery("CAVE", DungeonsQuery.Sort.MOST_VISITS), view.query());
            assertEquals(List.of("Pirate Cave"), shown(view));
            assertEquals("Saved runs · all sessions · 1 of 3 dungeons · 12 runs · most visits first", named(view, "dungeons-summary", JTextArea.class).getText());
            assertEquals(1, view.filterBar().activeCount());
            search.setText("");
            @SuppressWarnings("unchecked") JComboBox<DungeonsQuery.Sort> sort = named(view, "dungeons-sort", JComboBox.class);
            sort.setSelectedItem(DungeonsQuery.Sort.RECENT);
            assertEquals(List.of("Snake Pit", "Lost Halls", "Pirate Cave"), shown(view));
            sort.setSelectedItem(DungeonsQuery.Sort.NAME);
            assertEquals(List.of("Lost Halls", "Pirate Cave", "Snake Pit"), shown(view));
            assertEquals("Sorting is not a filter", 0, view.filterBar().activeCount());
            view.setQuery(new DungeonsQuery("snake", DungeonsQuery.Sort.NAME));
            assertEquals("snake", search.getText());
            named(view, "dungeons-clear-filters", AbstractButton.class).doClick();
            assertEquals("Clear keeps the order", new DungeonsQuery("", DungeonsQuery.Sort.NAME), view.query());
            assertEquals(3, view.shownCards().size());
            return null;
        });
        assertEquals("Filters and order never read again", 1, cards.reads.get());
    }

    @Test public void emptyStatesSayWhetherHistoryIsMissingLoadingUnreadableEmptyOrUnmatched() throws Exception {
        DungeonsView missing = view(() -> null, Stub::new);
        load(missing);
        edt(() -> { assertEquals("Saved history is unavailable", emptyTitle(missing)); return null; });

        DungeonsView none = view(new Counting(new DungeonsModel(List.of(), 0, 0, List.of(), 1)));
        load(none);
        edt(() -> {
            assertEquals("No saved dungeon runs yet", emptyTitle(none));
            assertFalse(named(none, "dungeons-cards", TileList.class).isVisible());
            return null;
        });

        Counting cards = new Counting(three());
        cards.gate = new CountDownLatch(1);
        DungeonsView view = view(cards);
        edt(() -> { view.check(); assertEquals("Loading saved runs", emptyTitle(view)); return null; });
        cards.gate.countDown();
        await("the read", () -> !view.loading());
        edt(() -> {
            assertNull(emptyTitle(view));
            view.setQuery(new DungeonsQuery("no dungeon is called this", null));
            assertEquals("No dungeons match", emptyTitle(view));
            named(view, "dungeons-empty-action", AbstractButton.class).doClick();
            assertEquals(DungeonsQuery.all(), view.query());
            assertNull(emptyTitle(view));
            return null;
        });

        cards.fail = new IOException("synthetic unreadable history");
        edt(() -> { view.refresh(); return null; });
        await("the failed refresh", () -> !view.loading());
        edt(() -> {
            assertNull("A failed refresh keeps the last cards", emptyTitle(view));
            JComponent warn = named(view, "dungeons-issues", JComponent.class);
            assertTrue(warn.isVisible());
            assertTrue(warn.getAccessibleContext().getAccessibleName(), warn.getAccessibleContext().getAccessibleName()
                .contains("synthetic unreadable history. The cards below are from the last successful read."));
            return null;
        });
        Counting denied = new Counting(three());
        denied.fail = new java.nio.file.AccessDeniedException("/home/synthetic-user/history/abc/runs");
        DungeonsView deniedView = view(denied);
        load(deniedView);
        edt(() -> {
            // A failure naming a path shows its kind instead (Codex review): no user-data path reaches the page.
            String body = deniedView.emptyState().getAccessibleContext().getAccessibleDescription();
            assertFalse(body, body.contains("/home") || body.contains("synthetic-user"));
            assertTrue(body, body.contains("AccessDeniedException"));
            return null;
        });
        Counting failing = new Counting(three());
        failing.fail = new IOException("synthetic unreadable history");
        DungeonsView failed = view(failing);
        load(failed);
        edt(() -> {
            assertEquals("Saved runs could not be read", emptyTitle(failed));
            failing.fail = null;
            named(failed, "dungeons-empty-action", AbstractButton.class).doClick();
            return null;
        });
        await("the retry", () -> !failed.loading() && failed.model() != null);

        Counting partial = new Counting(new DungeonsModel(List.of(HALLS), 7, 1, List.of("abc: runs could not be read (IOException)"), 1));
        DungeonsView issues = view(partial);
        load(issues);
        edt(() -> {
            JComponent warn = named(issues, "dungeons-issues", JComponent.class);
            assertTrue(warn.isVisible());
            assertEquals(DungeonsView.ISSUES, warn.getAccessibleContext().getAccessibleName());
            assertTrue(warn.getToolTipText().contains("abc: runs could not be read (IOException)"));
            return null;
        });
    }

    @Test public void cardActionsCallBackWithTheDungeonAndTheExactBestRun() throws Exception {
        Counting cards = new Counting(three());
        DungeonsView view = view(cards);
        List<String> runs = new ArrayList<>();
        List<String> recaps = new ArrayList<>();
        edt(() -> { view.onOpenRuns(runs::add); view.onOpenRecap((ref, recording) -> recaps.add(ref + "|" + recording)); return null; });
        frame(view);
        await("the read", () -> view.model() != null && !view.loading());
        edt(() -> {
            TileList<DungeonCardModel> list = view.cardList();
            list.setSelectedIndex(0);
            list.getActionMap().get(TileList.OPEN).actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "open"));
            assertEquals("Enter shows the dungeon's runs (primary)", List.of("Lost Halls"), runs);
            view.run(DungeonCardRenderer.Action.BEST, HALLS);
            assertEquals(List.of(RunFixtures.C1 + "|r-Lost Halls"), recaps);
            view.run(DungeonCardRenderer.Action.BEST, SNAKE);
            assertEquals("No best run: nothing opens", 1, recaps.size());
            view.run(DungeonCardRenderer.Action.ANALYZE, HALLS);
            assertFalse("Simple has no Analyze", view.analysisShown());
            return null;
        });
        // A click on a painted action runs that action only (real mouse events through the event queue).
        TileList<DungeonCardModel> list = view.cardList();
        Rectangle cell = edt(() -> list.getCellBounds(0, 0));
        Rectangle best = edt(() -> ((DungeonCardRenderer) list.getCellRenderer()).actionBounds(HALLS, cell.width, cell.height)
            .get(DungeonCardRenderer.Action.BEST));
        int x = cell.x + best.x + best.width / 2, y = cell.y + best.y + best.height / 2;
        click(list, x, y, 1);
        assertEquals(2, recaps.size());
        click(list, x, y, 2);
        assertEquals("The double-click's first click opens the best run once more", 3, recaps.size());
        assertEquals("A double-click on an action does not also show the runs", List.of("Lost Halls"), runs);
        Rectangle title = new Rectangle(cell.x + cell.width / 2, cell.y + DungeonCardRenderer.GAP, 1, 1);
        click(list, title.x, title.y, 2);
        assertEquals("A double-click elsewhere on the card shows its runs", List.of("Lost Halls", "Lost Halls"), runs);
        edt(() -> {
            JPopupMenu menu = view.cardMenu(SNAKE);
            assertEquals("dungeons-card-menu", menu.getName());
            assertFalse("No best run to open", named(menu, "dungeons-card-best", JMenuItem.class).isEnabled());
            assertFalse("Simple: no Analyze item", named(menu, "dungeons-card-analyze", JMenuItem.class).isVisible());
            named(menu, "dungeons-card-runs", JMenuItem.class).doClick();
            assertEquals(List.of("Lost Halls", "Lost Halls", "Snake Pit"), runs);
            return null;
        });
    }

    @Test public void analysisIsAnalystOnlyBuiltOnFirstUseAndSimpleReturnsToCards() throws Exception {
        Stub.built.set(0);
        Counting cards = new Counting(three());
        AtomicReference<Stub> made = new AtomicReference<>();
        DungeonsView view = view(() -> cards, () -> { Stub stub = new Stub(); made.set(stub); return stub; });
        frame(view);
        await("the read", () -> view.model() != null && !view.loading());
        edt(() -> {
            JComponent row = named(view, "dungeons-view-mode-row", JComponent.class);
            assertFalse("Simple: no Cards · Analysis switch", row.isVisible());
            assertEquals(0, Stub.built.get());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(row.isVisible());
            SegmentedControl modes = named(view, "dungeons-view-mode", SegmentedControl.class);
            assertEquals(0, Stub.built.get());
            named(modes, "dungeons-view-mode-1", AbstractButton.class).doClick();
            assertTrue(view.analysisShown());
            assertEquals("Built on first Analysis show", 1, Stub.built.get());
            assertEquals(List.of(DungeonsView.VIEW_KEY + "=" + DungeonsView.ANALYSIS), writes);
            assertTrue(named(view, "dungeons-statistics-banner", JComponent.class).isShowing());
            named(modes, "dungeons-view-mode-0", AbstractButton.class).doClick();
            named(modes, "dungeons-view-mode-1", AbstractButton.class).doClick();
            assertEquals("Built once", 1, Stub.built.get());
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertFalse("Simple returns to Cards", view.analysisShown());
            assertFalse(row.isVisible());
            assertEquals("The mode's switch is not the user's choice", DungeonsView.VIEW_KEY + "=" + DungeonsView.ANALYSIS, writes.get(writes.size() - 1));
            mode.set(DisplayModeModel.Mode.ANALYST);
            view.run(DungeonCardRenderer.Action.ANALYZE, HALLS);
            assertTrue("Analyze shows the Analysis view", view.analysisShown());
            assertEquals(1, Stub.built.get());
            return null;
        });
        edt(() -> { view.close(); return null; });
        assertTrue("Closing the tab closes its Analysis view", made.get().closed);
        int written = writes.size();

        // A remembered Analysis only selects: nothing is built until it shows.
        prefs.put(DungeonsView.VIEW_KEY, DungeonsView.ANALYSIS);
        DungeonsView restored = view(() -> cards, Stub::new);
        edt(() -> { assertTrue(restored.analysisShown()); assertEquals(1, Stub.built.get()); return null; });
        frame(restored);
        await("the restored Analysis to build", () -> Stub.built.get() == 2);
        assertEquals("Restoring the view writes nothing", written, writes.size());
    }

    @Test public void closingBeforeAnyAnalysisBuildsNothingAndStopsTheWorker() throws Exception {
        Stub.built.set(0);
        Counting cards = new Counting(three());
        cards.gate = new CountDownLatch(1);
        DungeonsView view = view(cards);
        edt(() -> { view.check(); view.close(); return null; });
        cards.gate.countDown();
        Thread.sleep(100);
        edt(() -> { assertNull("A closed tab applies nothing", view.model()); assertEquals(0, Stub.built.get()); view.close(); return null; });
    }

    @Test public void theProductionReaderShowsTheSourcesCards() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.writeMixed(root);
        SessionStore store = new SessionStore(root, false, "fixture");
        stores.add(store);
        DungeonsSource source = new DungeonsSource(store, HomeHistoryFixture.ZONE, () -> RunFixtures.NOW);
        DungeonsView view = view(() -> DungeonsView.cards(store, source), Stub::new);
        load(view);
        edt(() -> {
            assertEquals(List.of("Lost Halls", "Pirate Cave", RunFixtures.CRONUS, "Ice Citadel", "Snake Pit"), shown(view));
            assertEquals(13, view.model().runs());
            return null;
        });
    }

    /**
     * P5b Task 15b (evidence finding 8): in the tab itself at 1240×800 font 13 and 680×520 font 18, every card is the same fixed
     * cell and no painted caption is cut: a long reason paints a shorter form that still says why, whole in its tooltip and in the
     * card's accessible name.
     */
    @Test public void noCardCaptionIsCutAtTheDesktopOrCompactSize() throws Exception {
        List<DungeonCardModel> cards = new ArrayList<>(List.of(HALLS, SNAKE, CAVE));
        cards.addAll(DungeonCardRendererTest.longestReasons());
        DungeonsView view = view(new Counting(new DungeonsModel(cards, 40, 0, List.of(), 9_000)));
        try {
            for (int[] size : new int[][]{{1240, 800, 13}, {680, 520, 18}}) {
                edt(() -> { evidence.show(view, "dungeons-reasons", size[0], size[1], size[2]); return null; });
                await("the cards", () -> view.model() != null && !view.loading());
                evidence.settle();
                edt(() -> {
                    evidence.capture("dungeons-reasons-" + size[0] + "-" + size[2]);
                    TileList<DungeonCardModel> list = view.cardList();
                    DungeonCardRenderer renderer = (DungeonCardRenderer) list.getCellRenderer();
                    FontMetrics caption = renderer.getFontMetrics(tomato.gui.kit.Type.caption());
                    Dimension first = list.getCellBounds(0, 0).getSize();
                    for (int i = 0; i < list.getModel().getSize(); i++) {
                        Rectangle cell = list.getCellBounds(i, i);
                        assertEquals("Every card is the same cell", first, cell.getSize());
                        renderer.getListCellRendererComponent(list, list.getModel().getElementAt(i), i, false, false);
                        renderer.setSize(cell.getSize());
                        for (int fact = 0; fact < 4; fact++) {
                            String painted = renderer.paintedCaption(fact, cell.width, cell.height);
                            String where = size[0] + "/" + size[2] + " " + list.getModel().getElementAt(i).canonical() + " fact " + fact + ": " + painted;
                            assertTrue("Fits: " + where, caption.stringWidth(painted) <= renderer.factBounds(fact, cell.width, cell.height).width);
                            assertFalse("Not cut: " + where, painted.endsWith("…"));
                        }
                    }
                    return null;
                });
            }
        } finally {
            edt(() -> { evidence.closeWindow(); return null; });
        }
    }

    /** Posts real mouse events (press, release, click; {@code count} times) to the event queue and waits until they ran. */
    private static void click(JComponent target, int x, int y, int count) throws Exception {
        java.awt.EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        for (int i = 1; i <= count; i++) {
            long when = System.currentTimeMillis();
            queue.postEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, when, MouseEvent.BUTTON1_DOWN_MASK, x, y, i, false, MouseEvent.BUTTON1));
            queue.postEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, when, 0, x, y, i, false, MouseEvent.BUTTON1));
            queue.postEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, when, 0, x, y, i, false, MouseEvent.BUTTON1));
        }
        edt(() -> null);   // posted before this, so they have run
    }
}
