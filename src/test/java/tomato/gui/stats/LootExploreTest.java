package tomato.gui.stats;

import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.table.TableColumn;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.StatTile;
import tomato.gui.stats.LootQuery.*;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.realmshark.ParseEnchants;
import ui.VisualEvidence;
import util.PreferencesStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.history.ArchiveNativeSupport.await;
import static tomato.gui.history.ArchiveNativeSupport.ready;

/**
 * Loot › Explore's one view selector (P6a Task 8, spec §6.4): Simple lists the nine item views, Analyst adds the six saved-only views;
 * a saved-only view chosen while live opens saved history with it; live and saved keep the chosen view where both have it; the
 * Simple/Analyst mode never changes the query (a current Analyst view stays as "Current view"); the old live index and saved
 * {@code facets.view} states restore; the Loot bars keep one filter row at 1240×800, font 13. Synthetic data, isolated states.
 */
public class LootExploreTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final VisualEvidence evidence = new VisualEvidence("p6a-loot-explore");

    /** Captured from the pre-selector build (base 6c252c7): the live view on UTs (index 6) with the Recent range "Last hour". */
    static final String OLD_LIVE = "{\"version\":1,\"named\":{},\"last\":{\"version\":1,\"query\":{\"version\":1,\"scope\":\"@current\",\"text\":\"\","
        + "\"bounds\":{\"zone\":\"Etc/UTC\",\"mode\":\"ENTRY\",\"includeUnknown\":true},\"facets\":{\"values\":{\"loot-views\":\"6\",\"facets\":"
        + "\"{\\\"view\\\":\\\"UTS\\\",\\\"bags\\\":[],\\\"dungeons\\\":[],\\\"rarities\\\":[],\\\"tiers\\\":[],\\\"kind\\\":\\\"ANY\\\",\\\"slots\\\":{\\\"unknown\\\":\\\"INCLUDE\\\"},"
        + "\\\"applied\\\":{\\\"unknown\\\":\\\"INCLUDE\\\"},\\\"character\\\":\\\"\\\",\\\"enemy\\\":\\\"\\\"}\",\"loot-recent-range\":\"Last hour\"}},"
        + "\"order\":[{\"field\":\"NONE\",\"direction\":\"ASCENDING\"}]},\"archive\":false,\"page\":0,\"tab\":\"\",\"selected\":[],\"anchor\":null,"
        + "\"anchorOffset\":0,\"tables\":{}}}";
    /** Captured from the pre-selector build: saved history on By Bag, and on Session comparison (an Analyst view on Loot now). */
    static final String OLD_SAVED_BAGS = saved("BAGS"), OLD_SAVED_SESSIONS = saved("SESSIONS");
    private static String saved(String view) {
        return "{\"version\":1,\"named\":{},\"last\":{\"version\":1,\"query\":{\"version\":1,\"scope\":\"@current\",\"text\":\"\",\"bounds\":{\"zone\":"
            + "\"Etc/UTC\",\"mode\":\"ENTRY\",\"includeUnknown\":true},\"facets\":{\"view\":\"" + view + "\",\"bags\":[],\"dungeons\":[],\"rarities\":[],"
            + "\"tiers\":[],\"kind\":\"ANY\",\"slots\":{\"unknown\":\"INCLUDE\"},\"applied\":{\"unknown\":\"INCLUDE\"},\"character\":\"\",\"enemy\":\"\"},"
            + "\"order\":[{\"field\":\"TIME\",\"direction\":\"DESCENDING\"}]},\"archive\":true,\"page\":0,\"tab\":\"" + view + "\",\"selected\":[],"
            + "\"anchor\":null,\"anchorOffset\":0,\"tables\":{}}}";
    }

    static final List<String> SIMPLE = List.of("All Items", "Stat Potions", "Whites", "By Bag", "By Dungeon", "UTs", "STs", "Tiered", "Recent Drops");
    static final List<String> ANALYST = List.of("[Analyst]", "Item occurrences", "Dungeon loot profile", "Session comparison", "A/B cohorts",
        "Enemy hit events", "Loot by source");
    private static final String[] PREFERENCES = {DisplayModeModel.KEY, "ui.filters.loot.open", "ui.filters.loot-live.open", "ui.filters.statistics.open"};

    private final Map<String, String> values = new ConcurrentHashMap<>(), remembered = new HashMap<>();
    private final ViewStateStore states = new ViewStateStore(new ViewStateStore.Storage() {
        public String get(String key) { return values.get(key); }
        public CompletionStage<PreferencesStore.SaveResult> put(String key, String value) {
            values.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(values.size()));
        }
    });
    private final List<AutoCloseable> closing = new ArrayList<>();
    private DisplayModeModel.Mode mode;

    @Before public void isolate() throws Exception {
        edt(() -> {
            for (String key : PREFERENCES) { remembered.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
            mode = DisplayModeModel.application().mode();
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            return null;
        });
    }

    @After public void restore() throws Exception {
        for (AutoCloseable resource : closing) edt(() -> { resource.close(); return null; });
        edt(() -> {
            evidence.closeWindow();
            DisplayModeModel.application().set(mode);
            for (String key : PREFERENCES) PropertiesManager.setProperties(key, remembered.get(key) == null ? "" : remembered.get(key));
            return null;
        });
    }

    @Test public void simpleListsTheNineItemViewsAndAnalystAddsTheSixSavedOnlyViews() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> {
            assertEquals("Without saved history the live selector lists the live views only", SIMPLE, rows(named(new LootDashboard(), "loot-views")));
            assertEquals(SIMPLE, rows(live(workspace)));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            List<String> analyst = new ArrayList<>(SIMPLE); analyst.addAll(ANALYST);
            assertEquals(analyst, rows(live(workspace)));
            assertEquals("The live dashboard of a bare page stays live-only in Analyst", SIMPLE, rows(named(new LootDashboard(), "loot-views")));
            assertEquals("A saved-only view says so", LootExploreModel.SAVED_ONLY + ": opens saved loot in this view", tooltip(live(workspace), View.RATES));
            assertEquals("UT weapons, abilities, armor and rings; excludes potions, runes and other consumables", tooltip(live(workspace), View.UTS));
            assertEquals("Only items labeled ST, regardless of bag color", tooltip(live(workspace), View.STS));
            assertEquals("Tiered weapons and armor T13+; abilities T6+", tooltip(live(workspace), View.TIERED));
            workspace.showSaved(); return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            List<String> analyst = new ArrayList<>(SIMPLE); analyst.addAll(ANALYST);
            assertEquals("The saved selector offers the same views", analyst, rows(saved(workspace)));
            assertFalse("Dungeon statistics stays in Dungeons › Analysis", rows(saved(workspace)).contains("Dungeon statistics"));
            assertFalse("Character fame moves to Characters", rows(saved(workspace)).contains("Character fame"));
            assertNull("The view tabs are gone", named(workspace, "loot-archive-tabs"));
            return null;
        });
        // The Statistics workspace keeps its full set until it is removed: every view, in one list, in both modes.
        ArchiveWorkspace<Row, Facets, Sort> statistics = edt(() -> SessionPanel.queried(store(), "statistics", new JLabel("Live"),
            new LootArchiveClient(temp.newFolder().toPath(), true), states));
        closing.add(0, statistics::close);
        edt(() -> { statistics.showSaved(); return null; });
        await(() -> ready(statistics));
        edt(() -> {
            List<String> every = new ArrayList<>(); for (View view : View.values()) every.add(view.toString());
            assertEquals(every, rows(saved(statistics)));
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(every, rows(saved(statistics)));
            return null;
        });
    }

    @Test public void aSavedOnlyViewChosenWhileLiveOpensSavedHistoryWithThatView() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertFalse(workspace.state().archive);
            live(workspace).setSelectedItem(View.RATES);   // a user's choice
            assertTrue("Saved history opens", workspace.state().archive);
            assertEquals(View.RATES, workspace.state().query.facets().view);
            assertEquals("The view's own position", View.RATES.name(), workspace.state().tab);
            assertEquals("The live dashboard keeps its live view", View.ITEMS, live(workspace).getSelectedItem());
            return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            assertEquals(View.RATES, saved(workspace).getSelectedItem());
            JLabel caption = named(workspace, "loot-archive-view-caption");
            assertTrue(caption.isVisible()); assertEquals(LootExploreModel.SAVED_ONLY, caption.getText());
            saved(workspace).setSelectedItem(View.BAGS); return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.BAGS);
        edt(() -> { assertFalse("A view the live dashboard has is not saved-only", named(workspace, "loot-archive-view-caption").isVisible()); return null; });
    }

    @Test public void switchingBetweenLiveAndSavedKeepsTheChosenView() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> {
            evidence.show(workspace, "Loot Explore", 1240, 800, 13);
            live(workspace).setSelectedItem(View.UTS);
            assertFalse("Choosing a live view stays live", workspace.state().archive);
            assertFalse("and reads nothing", workspace.loading());
            assertEquals("Live → saved keeps the view", View.UTS, workspace.state().query.facets().view);
            assertTrue(card(workspace, 6).isShowing());
            button(workspace, "Browse saved").doClick(); return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            assertEquals(View.UTS, saved(workspace).getSelectedItem());
            saved(workspace).setSelectedItem(View.BAGS); return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.BAGS);
        edt(() -> {
            button(workspace, "Current live view").doClick();
            assertEquals("Saved → live keeps a view the live dashboard has", View.BAGS, live(workspace).getSelectedItem());
            assertTrue(card(workspace, 3).isShowing());
            assertTrue("The numeric live index is kept", values.get("ux.archive.loot-live").contains("\"loot-views\":\"3\""));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            button(workspace, "Browse saved").doClick(); return null;
        });
        await(() -> ready(workspace));
        edt(() -> { saved(workspace).setSelectedItem(View.OCCURRENCES); return null; });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.OCCURRENCES);
        edt(() -> {
            assertEquals("A saved-only view shows All Items live", View.ITEMS, live(workspace).getSelectedItem());
            button(workspace, "Current live view").doClick();
            assertTrue(card(workspace, 0).isShowing());
            return null;
        });
    }

    @Test public void theModeNeverChangesTheQueryAndARestoredAnalystViewStaysAsTheCurrentView() throws Exception {
        values.put("ux.archive.loot", OLD_SAVED_SESSIONS);
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        await(() -> ready(workspace));
        edt(() -> {
            ArchiveQuery<Facets, Sort> before = workspace.state().query;
            assertEquals(View.SESSIONS, before.facets().view);
            List<String> current = new ArrayList<>(SIMPLE); current.add("[Current view]"); current.add("Session comparison");
            assertEquals("Simple keeps the restored Analyst view shown", current, rows(saved(workspace)));
            assertEquals(View.SESSIONS, saved(workspace).getSelectedItem());
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            List<String> analyst = new ArrayList<>(SIMPLE); analyst.addAll(ANALYST);
            assertEquals(analyst, rows(saved(workspace)));
            assertEquals(View.SESSIONS, saved(workspace).getSelectedItem());
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(current, rows(saved(workspace)));
            assertEquals(View.SESSIONS, saved(workspace).getSelectedItem());
            assertEquals("The mode never changes the query", before, workspace.state().query);
            assertFalse(workspace.loading());
            return null;
        });
    }

    @Test public void oldLiveAndSavedViewStatesRestore() throws Exception {
        values.put("ux.archive.loot-live", OLD_LIVE);
        LootDashboard live = edt(() -> {
            LootDashboard dashboard = new LootDashboard(); dashboard.bindViewState(states, "loot-live");
            evidence.show(dashboard, "Loot live restore", 1240, 800, 13); return dashboard;
        });
        edt(() -> {
            assertEquals(View.UTS, named(live, "loot-views", JComboBox.class).getSelectedItem());
            assertEquals(View.UTS, facets(live).view);
            assertTrue(card(live, 6).isShowing());
            assertEquals("Last hour", named(live, "loot-recent-range", JComboBox.class).getSelectedItem());
            named(live, "loot-views", JComboBox.class).setSelectedItem(View.BAGS);
            assertEquals(View.BAGS, facets(live).view);
            String stored = values.get("ux.archive.loot-live");
            assertTrue(stored, stored.contains("\"loot-views\":\"3\""));
            assertTrue(stored, stored.contains("\\\"view\\\":\\\"BAGS\\\""));
            TableColumn column = named(live, "loot-view-3", JTable.class).getColumnModel().getColumn(0);
            column.setWidth(column.getWidth() + 17); return null;
        });
        await(() -> values.get("ux.archive.loot-live").contains("\"loot-view-3\":{"));

        values.put("ux.archive.loot", OLD_SAVED_BAGS);
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        await(() -> ready(workspace));
        edt(() -> {
            assertTrue(workspace.state().archive);
            assertEquals(View.BAGS, workspace.state().query.facets().view);
            assertEquals(View.BAGS, saved(workspace).getSelectedItem());
            assertEquals("BAGS", workspace.state().tab);
            TableColumn column = named(workspace, "loot-archive-table", JTable.class).getColumnModel().getColumn(0);
            column.setWidth(column.getWidth() + 23); return null;
        });
        await(() -> workspace.state().tables.containsKey(View.BAGS.name()));
    }

    @Test public void theRecentRangeLivesInTheLiveDrawerAndAppliesToRecentOnly() throws Exception {
        edt(() -> {
            LootDashboard live = new LootDashboard();
            FilterBar bar = named(live, "loot-live-filter-bar");
            JComboBox<?> range = named(live, "loot-recent-range");
            assertTrue(SwingUtilities.isDescendingFrom(range, bar.drawerContent()));
            assertFalse("Hidden for the other views", range.getParent().isVisible());
            named(live, "loot-views", JComboBox.class).setSelectedItem(View.RECENT);
            assertTrue(range.getParent().isVisible());
            assertNotNull("The nine tables stay in one card panel", named(live, "loot-view-cards", JPanel.class));
            for (int i = 0; i < 9; i++) assertTrue(SwingUtilities.isDescendingFrom(named(live, "loot-view-" + i, JTable.class), named(live, "loot-view-cards", JPanel.class)));
            return null;
        });
    }

    @Test public void matchingCountsAreStatTilesAndBagsAreUnknownWhenNotKnown() throws Exception {
        LootDashboard live = edt(() -> {
            LootDashboard dashboard = new LootDashboard();
            List<LootDashboard.Drop> drops = new ArrayList<>();
            for (int i = 0; i <= LootArchiveAdapter.MAX_KEYS; i++)
                drops.add(new LootDashboard.Drop("White", "Ice Citadel", "Boss", 1000 + i,
                    Collections.singletonList(new LootDashboard.Item(900000 + i, "UT " + i, "WEAPON,UT", ParseEnchants.summarize("")))));
            dashboard.acceptAll(drops);
            evidence.show(dashboard, "Loot tiles", 1240, 800, 13); return dashboard;
        });
        edt(() -> {
            assertEquals("25,001", tile(live, "tile-matching-bags").valueText());
            assertEquals("25,001", tile(live, "tile-matching-items").valueText());
            assertEquals("0", tile(live, "tile-matching-stat-potions").valueText());
            assertEquals("25,001", tile(live, "tile-matching-white-bags").valueText());
            Facets ut = new Facets(); ut.kind = Kind.UT_EQUIPMENT; live.applyFacets(ut);
            assertEquals("Bags are unknown past the composition bound, never 0", "—", tile(live, "tile-matching-bags").valueText());
            assertEquals("—", tile(live, "tile-matching-white-bags").valueText());
            assertNotNull(tile(live, "tile-matching-bags").value().tooltip());
            assertEquals("25,001", tile(live, "tile-matching-items").valueText());
            return null;
        });
    }

    @Test public void bothLootBarsKeepOneFilterRowWithTheSelectorAtDesktopWidth() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> { evidence.show(workspace, "Loot Explore S6", 1240, 800, 13); return null; });
        evidence.settle();
        edt(() -> {
            FilterBar bar = named(workspace, "loot-live-filter-bar");
            JComboBox<?> selector = named(bar, "loot-views");
            Component slot = oneRow("loot live", bar);
            assertTrue("The live selector sits in the search slot", SwingUtilities.isDescendingFrom(selector, slot));
            evidence.capture("p6a-loot-live-selector-1240-13");
            Facets f = workspace.state().query.facets(); f.bags.add("White"); f.kind = Kind.UT_EQUIPMENT;
            workspace.changeQuery(workspace.state().query.withFacets(f)); return null;
        });
        await(() -> ready(workspace));
        evidence.settle();
        await(() -> ready(workspace));
        edt(() -> {
            FilterBar bar = workspace.filterBar();
            assertTrue(bar.activeCount() > 0);
            oneRow("loot saved", bar);
            JComboBox<?> selector = saved(workspace);
            assertTrue(selector.isShowing());
            int barBottom = SwingUtilities.convertPoint(bar, 0, bar.getHeight(), workspace).y, top = SwingUtilities.convertPoint(selector, 0, 0, workspace).y;
            assertTrue("The saved selector leads the saved view, right under the filter row", top >= barBottom && top - barBottom < 3 * selector.getHeight());
            evidence.capture("p6a-loot-saved-selector-1240-13");
            return null;
        });
    }

    /** S6: with the drawer closed, the search slot and the Filters toggle share one row. Returns the slot. */
    private static Component oneRow(String name, FilterBar bar) {
        AbstractButton filters = named(bar, bar.getName().replace("-filter-bar", "-filters"));
        Component slot = filters.getParent().getComponent(0);
        int slotY = SwingUtilities.convertPoint(slot, 0, 0, bar).y, filtersY = SwingUtilities.convertPoint(filters, 0, 0, bar).y;
        assertTrue(name + ": the search slot and Filters share one row", Math.abs(slotY - filtersY) < filters.getHeight());
        return slot;
    }

    private SessionStore store() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "explore");
        closing.add(store);
        store.append("loot", new LootDashboard.Drop("White", "Lost Halls", "Synthetic boss", 1000,
            Collections.singletonList(new LootDashboard.Item(910001, "Synthetic blade", "EQUIPMENT,WEAPON,UT", ParseEnchants.summarize(""))), "visit-1"));
        store.flush();
        return store;
    }

    private ArchiveWorkspace<Row, Facets, Sort> workspace(SessionStore store) throws Exception {
        Path scratch = temp.newFolder().toPath();
        ArchiveWorkspace<Row, Facets, Sort> workspace = edt(() -> HistoricalStatistics.lootWorkspace(store, new LootDashboard(), scratch, states));
        closing.add(0, workspace::close);
        return workspace;
    }

    static JComboBox<?> live(Container root) { return named(root, "loot-views"); }
    static JComboBox<?> saved(Container root) { return named(root, "loot-archive-view"); }

    /** The selector's rows as shown: item titles, headers in brackets. */
    static List<String> rows(JComboBox<?> box) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < box.getItemCount(); i++) {
            Object item = box.getItemAt(i);
            @SuppressWarnings({"unchecked", "rawtypes"}) Component shown = ((ListCellRenderer) box.getRenderer()).getListCellRendererComponent(new JList<>(), item, i, false, false);
            String text = ((JLabel) shown).getText();
            rows.add(item instanceof View ? text : "[" + text + "]");
        }
        return rows;
    }

    static String tooltip(JComboBox<?> box, View view) {
        for (int i = 0; i < box.getItemCount(); i++) if (box.getItemAt(i) == view) {
            @SuppressWarnings({"unchecked", "rawtypes"}) Component shown = ((ListCellRenderer) box.getRenderer()).getListCellRendererComponent(new JList<>(), view, i, false, false);
            return ((JComponent) shown).getToolTipText();
        }
        return null;
    }

    /** The live card holding {@code loot-view-<index>}. */
    static Component card(Container root, int index) {
        JTable table = named(root, "loot-view-" + index);
        return SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
    }

    static StatTile tile(Container root, String name) { return named(root, name); }

    static Facets facets(LootDashboard live) throws Exception {
        Field field = LootDashboard.class.getDeclaredField("facets"); field.setAccessible(true); return (Facets) field.get(live);
    }

    static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && text.equals(((AbstractButton) child).getText()) && child.isShowing()) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = button((Container) child, text); if (found != null) return found; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T extends Component> T named(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return (T) child;
            if (child instanceof Container) { T found = named((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
    static <T extends Component> T named(Container root, String name, Class<T> type) { return type.cast(named(root, name)); }

    private interface Checked<T> { T get() throws Exception; }
    static <T> T edt(Checked<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { value.set(action.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }
}
