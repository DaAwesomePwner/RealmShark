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
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.kit.OverflowMenu;
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
 * Loot › Explore's one view selector (P6a Task 8, spec §6.4; one control since P6b Task 14): the live dashboard's {@code loot-views}
 * leads the filter row the page shows, the live bar's search slot while live and the workspace bar's while saved. Simple lists the
 * nine item views, Analyst adds the six saved-only views; a saved-only view chosen while live opens saved history with it; the
 * chosen view is kept across live and saved where both have it; the Simple/Analyst mode never changes the query (a current Analyst
 * view stays as "Current view"); the old live index and saved {@code facets.view} states restore; both Loot bars keep one filter row
 * at 1240×800, font 13. Saved Explore in Simple shows one plain count line, its drill-downs are ⋯ items, the "pinned …" caption is
 * Analyst's, and a nameless bag and an unknown area read "—" and "Unknown area". Synthetic data, isolated states.
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
    private static final String[] PREFERENCES = {DisplayModeModel.KEY, "ui.filters.loot.open", "ui.filters.loot-live.open", "ui.filters.dungeon-analysis.open"};

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
            assertEquals(SIMPLE, rows(selector(workspace)));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            List<String> analyst = new ArrayList<>(SIMPLE); analyst.addAll(ANALYST);
            assertEquals(analyst, rows(selector(workspace)));
            assertEquals("The live dashboard of a bare page stays live-only in Analyst", SIMPLE, rows(named(new LootDashboard(), "loot-views")));
            assertEquals("A saved-only view says so", LootExploreModel.SAVED_ONLY + ": opens saved loot in this view", tooltip(selector(workspace), View.RATES));
            assertEquals("UT weapons, abilities, armor and rings; excludes potions, runes and other consumables", tooltip(selector(workspace), View.UTS));
            assertEquals("Only items labeled ST, regardless of bag color", tooltip(selector(workspace), View.STS));
            assertEquals("Tiered weapons and armor T13+; abilities T6+", tooltip(selector(workspace), View.TIERED));
            workspace.showSaved(); return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            List<String> analyst = new ArrayList<>(SIMPLE); analyst.addAll(ANALYST);
            assertEquals("Saved history offers the same views in the same selector", analyst, rows(selector(workspace)));
            assertFalse("Dungeon statistics stays in Dungeons › Analysis", rows(selector(workspace)).contains("Dungeon statistics"));
            assertFalse("Character fame moves to Characters", rows(selector(workspace)).contains("Character fame"));
            assertNull("The view tabs are gone", named(workspace, "loot-archive-tabs"));
            assertNull("One selector: the saved view builds none of its own", named(workspace, "loot-archive-view"));
            assertNull("…and no view row", named(workspace, "loot-archive-view-row"));
            return null;
        });
        // A workspace without Explore's lists (the Statistics workspace was one, removed in P6a; Dungeons › Analysis is another)
        // offers every view it is given, in one list, in both modes, in its own view row.
        ArchiveWorkspace<Row, Facets, Sort> analysis = edt(() -> DungeonAnalysis.workspace(store(), temp.newFolder().toPath(), states));
        closing.add(0, analysis::close);
        await(() -> ready(analysis));
        edt(() -> {
            List<String> every = new ArrayList<>(); for (View view : View.values()) if (DungeonAnalysis.VIEWS.contains(view)) every.add(view.toString());
            assertEquals(every, rows(archiveSelector(analysis)));
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(every, rows(archiveSelector(analysis)));
            return null;
        });
    }

    @Test public void aFreshSavedLootOpensOnAllItemsTheFirstSimpleView() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> {
            assertEquals("A fresh saved Loot asks for All Items", View.ITEMS, workspace.state().query.facets().view);
            assertEquals(ArchiveQuery.CURRENT, workspace.state().query.scope());
            workspace.showSaved(); return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            assertEquals(View.ITEMS, selector(workspace).getSelectedItem());
            assertEquals("Simple: the nine views, no \"Current view\" row", SIMPLE, rows(selector(workspace)));
            assertFalse(named(workspace, "loot-archive-view-caption").isVisible());
            assertEquals("Live and saved agree from the start", View.ITEMS, dashboard(workspace).shownView());
            return null;
        });
    }

    @Test public void aSavedOnlyViewChosenWhileLiveOpensSavedHistoryWithThatView() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertFalse(workspace.state().archive);
            selector(workspace).setSelectedItem(View.RATES);   // a user's choice
            assertTrue("Saved history opens", workspace.state().archive);
            assertEquals(View.RATES, workspace.state().query.facets().view);
            assertEquals("The view's own position", View.RATES.name(), workspace.state().tab);
            assertEquals("The selector shows the saved view", View.RATES, selector(workspace).getSelectedItem());
            assertEquals("The live dashboard keeps its live view", View.ITEMS, dashboard(workspace).shownView());
            assertTrue("The selector moved into the workspace bar", SwingUtilities.isDescendingFrom(selector(workspace), workspace.filterBar().searchSlot()));
            return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            assertEquals(View.RATES, selector(workspace).getSelectedItem());
            JLabel caption = named(workspace, "loot-archive-view-caption");
            assertTrue(caption.isVisible()); assertEquals(LootExploreModel.SAVED_ONLY, caption.getText());
            assertTrue("The caption leads the saved filter row beside the selector", SwingUtilities.isDescendingFrom(caption, workspace.filterBar().searchSlot()));
            selector(workspace).setSelectedItem(View.BAGS); return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.BAGS);
        edt(() -> {
            assertFalse("A view the live dashboard has is not saved-only", named(workspace, "loot-archive-view-caption").isVisible());
            assertEquals("A live view chosen in saved history is carried to live", View.BAGS, dashboard(workspace).shownView());
            return null;
        });
    }

    /**
     * Polish B1: a legacy saved bag without a name made Dungeon loot profile and Session comparison fail ("History read failed: …
     * "drop.bag" is null"). Both now read it: the bag counts, is never a white bag, and the explanation says so.
     */
    @Test public void aSavedBagWithoutANameReadsInTheSavedOnlyViews() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "explore");
        closing.add(store);
        store.append("loot", new LootDashboard.Drop(null, "Lost Halls", "Synthetic boss", 1000,
            Collections.singletonList(new LootDashboard.Item(910001, "Synthetic blade", "EQUIPMENT,WEAPON,UT", ParseEnchants.summarize(""))), "visit-1"));
        store.flush();
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store);
        edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); selector(workspace).setSelectedItem(View.RATES); return null; });
        await(() -> workspace.displayedPage() != null || footer(workspace).contains("History read failed"));
        edt(() -> {
            assertFalse(footer(workspace), footer(workspace).contains("History read failed"));
            assertEquals(View.RATES, workspace.state().query.facets().view);
            assertTrue("Explore keeps its view selector, in the saved filter row", SwingUtilities.isDescendingFrom(selector(workspace), workspace.filterBar().searchSlot()));
            assertEquals(1, workspace.displayedPage().rows.size());
            Row lostHalls = workspace.displayedPage().rows.get(0).value;
            assertEquals("Lost Halls", lostHalls.dungeon);
            assertEquals((Long) 1L, lostHalls.bags); assertEquals((Long) 1L, lostHalls.items); assertEquals((Long) 1L, lostHalls.uts);
            assertEquals("A bag without a name is never a white bag", (Long) 0L, lostHalls.whites);
            assertTrue(lostHalls.evidence, lostHalls.evidence.contains("1 bag without a saved bag name is not counted as a white bag."));
            selector(workspace).setSelectedItem(View.SESSIONS); return null;
        });
        await(() -> !workspace.loading() && workspace.state().query.facets().view == View.SESSIONS
            && ("session".equals(workspace.displayedPage().rows.isEmpty() ? null : workspace.displayedPage().rows.get(0).value.type) || footer(workspace).contains("History read failed")));
        edt(() -> {
            assertFalse(footer(workspace), footer(workspace).contains("History read failed"));
            Row session = workspace.displayedPage().rows.get(0).value;
            assertEquals((Long) 1L, session.bags); assertEquals((Long) 0L, session.whites);
            assertTrue(session.evidence, session.evidence.contains("1 bag without a saved bag name is not counted as a white bag."));
            return null;
        });
    }

    /** The saved view's status lines (the footer's text areas), where a read failure is reported. */
    private static String footer(Container root) {
        StringBuilder text = new StringBuilder();
        Deque<Component> pending = new ArrayDeque<>(); pending.add(named(root, "loot-archive-footer"));
        while (!pending.isEmpty()) {
            Component next = pending.pop();
            if (next instanceof JTextArea) text.append(((JTextArea) next).getText()).append('\n');
            if (next instanceof Container) pending.addAll(Arrays.asList(((Container) next).getComponents()));
        }
        return text.toString();
    }

    @Test public void switchingBetweenLiveAndSavedKeepsTheChosenView() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> {
            evidence.show(workspace, "Loot Explore", 1240, 800, 13);
            selector(workspace).setSelectedItem(View.UTS);
            assertFalse("Choosing a live view stays live", workspace.state().archive);
            assertFalse("and reads nothing", workspace.loading());
            assertEquals("Live → saved keeps the view", View.UTS, workspace.state().query.facets().view);
            assertTrue(card(workspace, 6).isShowing());
            pickScope(workspace, "current"); return null;
        });
        await(() -> ready(workspace));
        edt(() -> {
            assertEquals(View.UTS, selector(workspace).getSelectedItem());
            selector(workspace).setSelectedItem(View.BAGS); return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.BAGS);
        edt(() -> {
            pickScope(workspace, "live");
            assertEquals("Saved → live keeps a view the live dashboard has", View.BAGS, selector(workspace).getSelectedItem());
            assertTrue(card(workspace, 3).isShowing());
            assertTrue("The numeric live index is kept", values.get("ux.archive.loot-live").contains("\"loot-views\":\"3\""));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            pickScope(workspace, "current"); return null;
        });
        await(() -> ready(workspace));
        edt(() -> { selector(workspace).setSelectedItem(View.OCCURRENCES); return null; });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.OCCURRENCES);
        edt(() -> {
            assertEquals("The selector shows the saved-only view while saved", View.OCCURRENCES, selector(workspace).getSelectedItem());
            assertEquals("A saved-only view shows All Items live", View.ITEMS, dashboard(workspace).shownView());
            pickScope(workspace, "live");
            assertEquals(View.ITEMS, selector(workspace).getSelectedItem());
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
            assertEquals("Simple keeps the restored Analyst view shown", current, rows(selector(workspace)));
            assertEquals(View.SESSIONS, selector(workspace).getSelectedItem());
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            List<String> analyst = new ArrayList<>(SIMPLE); analyst.addAll(ANALYST);
            assertEquals(analyst, rows(selector(workspace)));
            assertEquals(View.SESSIONS, selector(workspace).getSelectedItem());
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(current, rows(selector(workspace)));
            assertEquals(View.SESSIONS, selector(workspace).getSelectedItem());
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
            assertEquals(View.BAGS, selector(workspace).getSelectedItem());
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
            assertSame("The live dashboard hosts the Scope chip", bar, workspace.liveFilterBar());
            JComboBox<?> selector = named(bar, "loot-views");
            Component slot = oneRow("loot live", bar);
            assertTrue("The live selector sits in the search slot", SwingUtilities.isDescendingFrom(selector, slot));
            FilterBarAssert.assertChipInVisibleBar(workspace);
            FilterBarAssert.assertOneRow(bar);
            int barTop = SwingUtilities.convertPoint(bar, 0, 0, workspace).y, tiles = SwingUtilities.convertPoint(named(workspace, "loot-metrics"), 0, 0, workspace).y;
            assertTrue("The live filter row is above the tiles: " + barTop + " < " + tiles, barTop < tiles);
            evidence.capture("p6b-loot-live-selector-1240-13");
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
            JComboBox<?> selector = selector(workspace);
            assertTrue(selector.isShowing());
            assertTrue("The one selector leads the saved filter row's search slot", SwingUtilities.isDescendingFrom(selector, bar.searchSlot()));
            FilterBarAssert.assertChipInVisibleBar(workspace);
            FilterBarAssert.assertOneRow(bar);
            evidence.capture("p6b-loot-saved-selector-1240-13");
            return null;
        });
    }

    /** B4: saved Explore in Simple shows one plain count line (the description and counts in its tooltip); Analyst the full text. */
    @Test public void simpleSavedExploreShowsOnePlainCountLineAndAnalystTheFullDescription() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> { workspace.showSaved(); return null; });
        await(() -> ready(workspace));
        edt(() -> {
            JTextArea counts = named(workspace, "loot-archive-counts");
            assertEquals("One plain line in Simple", "1 bag · 1 item variant · 1 item", counts.getText());
            String tip = counts.getToolTipText();
            assertNotNull("The full description and counts are in the tooltip", tip);
            assertTrue(tip, tip.contains("matching bags: 1 bags [whole query; each qualifying bag once]"));
            assertTrue(tip, tip.contains("All saved occurrences are queried before grouping and paging."));
            String described = counts.getAccessibleContext().getAccessibleDescription();
            assertTrue(described, described.startsWith(LootArchiveClient.description(View.ITEMS)) && described.contains("matching variants: 1 item variants"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst: the description, then every count", counts.getText().startsWith(LootArchiveClient.description(View.ITEMS) + "\n")
                && counts.getText().contains("matching bags: 1 bags [whole query; each qualifying bag once]"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertEquals("It rebinds on a mode change", "1 bag · 1 item variant · 1 item", counts.getText());
            assertFalse("The mode reads nothing", workspace.loading());
            return null;
        });
    }

    /** B4: the "pinned …" status caption (rows, revision, sort) is Analyst detail on every archive page; Simple shows none. */
    @Test public void thePinnedStatusCaptionIsAnalystOnly() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> { workspace.showSaved(); return null; });
        await(() -> ready(workspace));
        edt(() -> {
            assertFalse(footer(workspace), footer(workspace).contains("pinned"));
            assertFalse(footer(workspace), footer(workspace).contains("sorted by"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertTrue(footer(workspace), footer(workspace).contains(" · pinned ") && footer(workspace).contains("sorted by time (descending)"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertFalse(footer(workspace), footer(workspace).contains("pinned"));
            return null;
        });
    }

    /** B4: the drill-downs are items of the workspace ⋯ (after the column tools), not a button row under the table. */
    @Test public void drillDownsAreOverflowItems() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> { workspace.showSaved(); return null; });
        await(() -> ready(workspace));
        edt(() -> {
            assertNull("No drill button row in the view", named(workspace, "loot-drill-occurrences"));
            OverflowMenu more = ArchiveNativeSupport.more(workspace);
            JMenuItem occurrences = more.item("Occurrences of selected variant");
            assertNotNull(occurrences);
            assertEquals("loot-drill-occurrences", occurrences.getName());
            for (String label : new String[]{"Loot from selected run", "Open recorded run", "Dungeon rate calculation"}) assertNotNull(label, more.item(label));
            List<Component> menu = Arrays.asList(more.menu().getComponents());
            assertTrue("After the column tools", menu.indexOf(occurrences) > menu.indexOf(more.item("Row details…")));
            assertFalse("Nothing selected", occurrences.isEnabled());
            named(workspace, "loot-archive-table", JTable.class).setRowSelectionInterval(0, 0);
            assertTrue(occurrences.isEnabled());
            occurrences.doClick(); return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.OCCURRENCES);
        edt(() -> {
            assertNotNull("An exact variant", workspace.state().query.facets().variant);
            assertTrue("The drill-down summary stays in the view", named(workspace, "loot-drill-summary", JTextArea.class).getText().startsWith("Drill-down: exact variant"));
            assertEquals("The section is replaced, not duplicated", 1, count(ArchiveNativeSupport.more(workspace), "loot-drill-occurrences"));
            pickScopeHidden(workspace, "live");
            assertNull("Live: no saved drill-downs in ⋯", ArchiveNativeSupport.more(workspace).item("Occurrences of selected variant"));
            return null;
        });
    }

    /** B3(b), B2: a nameless bag reads "—" and an unknown area "Unknown area" in the saved table; the model keeps the saved values. */
    @Test public void aNamelessBagReadsDashAndAnUnknownAreaReadsUnknownArea() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "explore");
        closing.add(store);
        store.append("loot", new LootDashboard.Drop(null, "Unknown", "Synthetic boss", 1000,
            Collections.singletonList(new LootDashboard.Item(910001, "Synthetic blade", "EQUIPMENT,WEAPON,UT", ParseEnchants.summarize(""))), "visit-1"));
        store.flush();
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store);
        edt(() -> { Facets f = workspace.state().query.facets(); f.view = View.OCCURRENCES; workspace.changeQuery(workspace.state().query.withFacets(f)); return null; });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.OCCURRENCES && workspace.displayedPage().matches == 1);
        edt(() -> {
            JTable table = named(workspace, "loot-archive-table", JTable.class);
            assertEquals("The model keeps the saved blank", "", model(table, 0, "bag"));
            assertEquals("—", cell(table, 0, "bag"));
            assertNotEquals("The model keeps the saved area name", LootFacts.UNKNOWN_AREA, model(table, 0, "dungeon"));
            assertEquals(LootFacts.UNKNOWN_AREA, cell(table, 0, "dungeon"));
            Facets f = workspace.state().query.facets(); f.view = View.BAGS; workspace.changeQuery(workspace.state().query.withFacets(f)); return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.BAGS);
        edt(() -> {
            JTable table = named(workspace, "loot-archive-table", JTable.class);
            assertEquals("A bag-type summary has no single area", "", model(table, 0, "dungeon"));
            // The Dungeon column is not in By Bag's compact layout: ask its renderer directly.
            Component shown = retained(table, "dungeon").getCellRenderer().getTableCellRendererComponent(table, model(table, 0, "dungeon"), false, false, 0, 0);
            assertEquals("Not applicable reads —, not Unknown area", "—", ((JLabel) shown).getText());
            return null;
        });
    }

    /** B4: in Simple the Items column leaves the compact layout when every row shows "—"; Analyst shows it; no saved layout records it. */
    @Test public void theEmptyItemsColumnLeavesSimplesCompactLayout() throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store());
        edt(() -> { workspace.showSaved(); return null; });
        await(() -> ready(workspace));
        edt(() -> {
            JTable table = named(workspace, "loot-archive-table", JTable.class);
            assertFalse("All Items: every Items cell is —, so Simple hides it", shown(table, "items"));
            assertTrue(shown(table, "count"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst keeps the compact layout's Items column", shown(table, "items"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertFalse(shown(table, "items"));
            Facets f = workspace.state().query.facets(); f.view = View.BAGS; workspace.changeQuery(workspace.state().query.withFacets(f)); return null;
        });
        await(() -> ready(workspace) && workspace.state().query.facets().view == View.BAGS);
        edt(() -> {
            assertTrue("By Bag counts items: Simple shows them", shown(named(workspace, "loot-archive-table", JTable.class), "items"));
            assertFalse("No layout was saved for All Items by the mode", workspace.state().tables.containsKey(View.ITEMS.name()));
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

    /** Explore's one view selector ({@code loot-views}), live or saved. */
    static JComboBox<?> selector(Container root) { return named(root, "loot-views"); }
    /** The view selector a workspace without Explore builds in its saved view (Dungeons › Analysis, Characters › Fame history). */
    static JComboBox<?> archiveSelector(Container root) { return named(root, "loot-archive-view"); }
    /** Explore's live dashboard: the workspace's live card. */
    static LootDashboard dashboard(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof LootDashboard) return (LootDashboard) child;
            if (child instanceof Container) { LootDashboard found = dashboard((Container) child); if (found != null) return found; }
        }
        return null;
    }

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

    /** Picks a Scope ▾ item ("current" was Browse saved, "live" Current live view); the chip must show, as those buttons had to. */
    static void pickScope(ArchiveWorkspace<?, ?, ?> workspace, String suffix) {
        assertTrue("The Scope chip shows", ArchiveNativeSupport.scope(workspace).isShowing());
        ArchiveNativeSupport.scopeItem(workspace, suffix).doClick();
    }
    /** {@link #pickScope} for a workspace that is not in a window. */
    static void pickScopeHidden(ArchiveWorkspace<?, ?, ?> workspace, String suffix) { ArchiveNativeSupport.scopeItem(workspace, suffix).doClick(); }

    /** The text a user sees in a cell (the realized renderer), not the model value. */
    static String cell(JTable table, int row, String id) {
        int view = table.convertColumnIndexToView(table.getColumn(id).getModelIndex());
        return ((JLabel) table.prepareRenderer(table.getCellRenderer(row, view), row, view)).getText();
    }
    static Object model(JTable table, int row, String id) {
        for (int column = 0; column < table.getModel().getColumnCount(); column++)
            if (retainedId(table, column).equals(id)) return table.getModel().getValueAt(row, column);
        throw new AssertionError("No column " + id);
    }
    private static String retainedId(JTable table, int model) {
        for (Object column : (Collection<?>) table.getClientProperty("archive.columns"))
            if (((TableColumn) column).getModelIndex() == model) return String.valueOf(((TableColumn) column).getIdentifier());
        return "";
    }
    /** A column HistoryTables knows, shown or not (the table's retained columns). */
    private static TableColumn retained(JTable table, String id) {
        for (Object column : (Collection<?>) table.getClientProperty("archive.columns"))
            if (id.equals(String.valueOf(((TableColumn) column).getIdentifier()))) return (TableColumn) column;
        throw new AssertionError("No column " + id);
    }
    static boolean shown(JTable table, String id) {
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) if (id.equals(String.valueOf(column.getIdentifier()))) return true;
        return false;
    }
    private static int count(OverflowMenu more, String name) {
        int found = 0; for (Component item : more.menu().getComponents()) if (name.equals(item.getName())) found++;
        return found;
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
