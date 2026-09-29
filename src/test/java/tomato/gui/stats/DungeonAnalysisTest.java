package tomato.gui.stats;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.SessionPanel;
import tomato.gui.history.ViewState;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.runs.DungeonsView;
import tomato.gui.runs.RunFixtures;
import tomato.gui.stats.LootQuery.*;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.history.archive.ArchiveRow;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.stats.LootDrillDownTest.await;
import static tomato.gui.stats.LootDrillDownTest.edt;

/**
 * The Dungeons tab's Analyst analysis: a saved-only archive workspace over the dungeon views of the Statistics workspace (loot
 * profile, session comparison, counters, enemies, sources, A/B cohorts), built on the first Analysis show, never a live view,
 * closed with the tab, and "Analyze" applying the card's exact canonical dungeon as the facet.
 */
public class DungeonAnalysisTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String[] KEYS = {DungeonsView.VIEW_KEY, DisplayModeModel.KEY, "ui.filters.dungeons.open", "ui.filters.dungeon-analysis.open"};
    private final Map<String, String> remembered = new HashMap<>();
    private final List<AutoCloseable> closing = new ArrayList<>();
    private final List<JFrame> frames = new ArrayList<>();
    private DisplayModeModel.Mode mode;

    @Before public void remember() {
        for (String key : KEYS) remembered.put(key, PropertiesManager.getProperty(key));
        mode = DisplayModeModel.application().mode();
    }

    @After public void restore() throws Exception {
        edt(() -> {
            for (AutoCloseable item : closing) item.close();
            for (JFrame frame : frames) frame.dispose();
            DisplayModeModel.application().set(mode);
            return null;
        });
        for (String key : KEYS) PropertiesManager.setProperties(key, remembered.get(key) == null ? "" : remembered.get(key));
    }

    private SessionStore mixed() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.writeMixed(root);
        SessionStore store = new SessionStore(root, false, "fixture");
        closing.add(store);
        return store;
    }
    private ArchiveWorkspace<Row, Facets, Sort> workspace(SessionStore store, Path scratch, ArchiveNativeSupport.Memory memory) throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = edt(() -> DungeonAnalysis.workspace(store, scratch, memory.states));
        closing.add(0, workspace::close);
        return workspace;
    }
    private static boolean ready(ArchiveWorkspace<?, ?, ?> workspace) { return !workspace.loading() && workspace.displayedPage() != null; }
    /** The tabs became a view selector: its rows in order, a view by its title (a header row, which is not a view, as "[header]"). */
    private static List<String> tabs(Container root) {
        JComboBox<?> views = find(root, "loot-archive-view", JComboBox.class);
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < views.getItemCount(); i++) titles.add(views.getItemAt(i) instanceof View ? views.getItemAt(i).toString() : "[header]");
        return titles;
    }

    @Test public void theInitialQueryVariantMatchesTheExistingWorkspaces() {
        assertEquals(LootQuery.initial(false).toJson(), LootQuery.initial(View.OCCURRENCES, ArchiveQuery.CURRENT).toJson());
        assertEquals(LootQuery.initial(true).toJson(), LootQuery.initial(View.SESSIONS, ArchiveQuery.CURRENT).toJson());
        ArchiveQuery<Facets, Sort> initial = DungeonAnalysis.initialQuery();
        assertEquals("Every session, as the cards", SessionStore.ALL, initial.scope());
        assertEquals(View.SESSIONS, initial.facets().view);
        assertEquals(EnumSet.of(View.RATES, View.SESSIONS, View.COHORTS, View.COUNTERS, View.ENEMIES, View.SOURCES), DungeonAnalysis.VIEWS);
        assertEquals(EnumSet.of(View.RATES, View.SESSIONS, View.COHORTS, View.COUNTERS, View.ENEMIES, View.SOURCES),
            new LootArchiveClient(temp.getRoot().toPath(), DungeonAnalysis.VIEWS, initial).views());
        assertEquals("The boolean Loot client keeps its views", EnumSet.complementOf(EnumSet.of(View.FAME, View.COUNTERS, View.ENEMIES, View.SOURCES, View.COHORTS)),
            new LootArchiveClient(temp.getRoot().toPath(), false).views());
        assertEquals("The Loot workspace (Explore) offers the item views and the Analyst views, never counters or fame",
            EnumSet.complementOf(EnumSet.of(View.FAME, View.COUNTERS)), LootExploreModel.views());
        assertEquals("The Statistics workspace keeps every view", EnumSet.allOf(View.class), new LootArchiveClient(temp.getRoot().toPath(), true).views());
        try { new LootArchiveClient(temp.getRoot().toPath(), EnumSet.of(View.RATES), initial); fail("The initial view must be offered"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void theWorkspaceIsSavedOnlyOverTheDungeonViewsWithoutALiveView() throws Exception {
        SessionStore store = mixed();
        Path scratch = temp.newFolder("scratch").toPath();
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        // A remembered state that asks for the live view: a saved-only workspace still reads saved history.
        memory.states.save(DungeonAnalysis.NAME, ViewState.initial(DungeonAnalysis.initialQuery()).withArchive(false));
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store, scratch, memory);
        ArchiveWorkspace<Row, Facets, Sort> loot = edt(() -> HistoricalStatistics.lootWorkspace(store, new LootDashboard(), scratch, memory.states));
        closing.add(0, loot::close);
        await(() -> ready(workspace));
        edt(() -> {
            assertEquals("dungeon-analysis-session-view", workspace.getName());
            assertTrue(workspace.savedOnly());
            assertTrue(workspace.state().archive);
            assertEquals(SessionStore.ALL, workspace.state().query.scope());
            assertEquals(List.of("Dungeon loot profile", "Session comparison", "Dungeon statistics", "Enemy hit events", "Loot by source", "A/B cohorts"),
                tabs(workspace));
            assertNull("No Browse saved / Current live view toggle", button(workspace, "Browse saved"));
            assertNull(button(workspace, "Current live view"));
            assertEquals("Its own filter row, apart from the cards' dungeons bar", "dungeon-analysis-filter-bar", workspace.filterBar().getName());
            workspace.selectSession(store.currentId());
            assertTrue("The current session is read from its saved files", workspace.state().archive);
            assertFalse("Existing workspaces keep their live view", loot.savedOnly());
            assertNotNull(button(loot, "Browse saved"));
            return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            workspace.changeQuery(workspace.state().query.withScope(SessionStore.ALL));
            return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            assertTrue(workspace.state().archive);
            // The counters keep their existing wording: activity-recorded exits, never summed into visits.
            Facets counters = workspace.state().query.facets();
            counters.view = View.COUNTERS;
            workspace.changeQuery(workspace.state().query.withFacets(counters));
            return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.COUNTERS);
        edt(() -> {
            JTable table = find(workspace, "loot-archive-table", JTable.class);
            boolean exits = false;
            for (int i = 0; i < table.getColumnCount(); i++) {
                Object header = table.getColumnModel().getColumn(i).getHeaderValue();
                if ("Visits".equals(header)) exits = true;
            }
            assertTrue("The counters' visits column keeps its label", exits);
            assertEquals("Visits / activity-recorded exits", LootArchiveClient.columns().stream().filter(c -> c.id.equals("runs")).findFirst().get().label);
            return null;
        });
    }

    @Test public void analyzeAppliesTheExactCanonicalDungeonAndKeepsTheView() throws Exception {
        SessionStore store = mixed();
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store, temp.newFolder("scratch").toPath(), memory);
        await(() -> ready(workspace));
        edt(() -> {
            Facets rates = workspace.state().query.facets();
            rates.view = View.RATES;
            rates.variant = "42/null/null";
            workspace.changeQuery(workspace.state().query.withFacets(rates));
            return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.RATES);
        edt(() -> {
            assertTrue(DungeonAnalysis.analyze(workspace, "Lost Halls"));
            Facets applied = workspace.state().query.facets();
            assertEquals(Set.of("Lost Halls"), applied.dungeons);
            assertEquals("The view stays", View.RATES, applied.view);
            assertNull("A drill-down is cleared", applied.variant);
            assertFalse("Only an analysis workspace takes a dungeon", DungeonAnalysis.analyze(new JPanel(), "Lost Halls"));
            return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().dungeons.contains("Lost Halls"));
        edt(() -> {
            List<String> dungeons = new ArrayList<>();
            for (ArchiveRow<Row> row : workspace.displayedPage().rows) dungeons.add(row.value.dungeon);
            assertEquals("Only the card's dungeon's rate", List.of("Lost Halls"), dungeons);
            return null;
        });
    }

    @Test public void theDungeonsTabBuildsItOnTheFirstAnalysisShowAndClosesIt() throws Exception {
        SessionStore store = mixed();
        Path scratch = temp.newFolder("scratch").toPath();
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        AtomicInteger built = new AtomicInteger();
        AtomicReference<ArchiveWorkspace<Row, Facets, Sort>> made = new AtomicReference<>();
        PropertiesManager.setProperties(DungeonsView.VIEW_KEY, DungeonsView.CARDS);
        DungeonsView view = edt(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            return new DungeonsView(() -> store, HomeHistoryFixture.ZONE, () -> RunFixtures.NOW, () -> {
                built.incrementAndGet();
                made.set(DungeonAnalysis.workspace(store, scratch, memory.states));
                return made.get();
            });
        });
        closing.add(0, view::close);
        edt(() -> {
            JFrame frame = new JFrame("Dungeons analysis fixture");
            frame.setContentPane(view);
            frame.setSize(1240, 800);
            frame.setVisible(true);
            frames.add(frame);
            return null;
        });
        Thread.sleep(200);
        assertEquals("The cards show first; nothing is built for Analysis", 0, built.get());
        edt(() -> { find(view, "dungeons-view-mode-1", AbstractButton.class).doClick(); return null; });
        assertEquals(1, built.get());
        ArchiveWorkspace<Row, Facets, Sort> workspace = made.get();
        edt(() -> { assertTrue(SwingUtilities.isDescendingFrom(workspace, view)); assertTrue(workspace.isShowing()); return null; });
        await(() -> ready(workspace));
        edt(() -> { view.analyze(RunFixtures.CRONUS); return null; });
        await(() -> ready(workspace) && workspace.state().query.facets().dungeons.equals(Set.of(RunFixtures.CRONUS)));
        assertEquals("Analyze reuses the built workspace", 1, built.get());
        ArchiveQuery<Facets, Sort> before = edt(() -> workspace.state().query);
        edt(() -> { view.close(); return null; });
        edt(() -> {
            workspace.changeQuery(before.withText("after close"));
            assertEquals("A closed workspace takes no query", before, workspace.state().query);
            return null;
        });
        long until = System.nanoTime() + 20_000_000_000L;
        while (tomato.history.archive.ArchiveFixtures.children(scratch) > 0 && System.nanoTime() < until) Thread.sleep(20);
        assertEquals("Its pinned results are released", 0, tomato.history.archive.ArchiveFixtures.children(scratch));
    }

    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && text.equals(((AbstractButton) child).getText())) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = button((Container) child, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T extends Component> T find(Container root, String name, Class<T> type) {
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
}
