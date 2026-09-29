package tomato.gui.history;

import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.DpsData;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.character.CharacterJournalGUI;
import tomato.gui.character.TableViewRule;
import tomato.gui.chat.ChatArchiveClient;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.DungeonListGUI;
import tomato.gui.dps.Filter;
import tomato.gui.keypop.KeyPopArchiveClient;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.quest.QuestGUI;
import tomato.gui.runs.DungeonsView;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.stats.HistoricalStatistics;
import tomato.gui.stats.LootDashboard;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatFixtures;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;

/**
 * S6 evidence: adopted pages with filters collapsed and open, 1240×800 and 680×520, fonts 13 and 18. Synthetic data only; no capture.
 * P5b adds Runs &amp; DPS's three filter rows: {@code dps-meter} (the Live meter), {@code encounter-library} (Recordings) and
 * {@code dungeons}. P6a adds Loot's live row ({@code loot-live}: the live dashboard alone, as Loot › Explore shows it without saved
 * history) and Loot › Explore live beside saved history ({@code loot-explore-live}: the same dashboard inside the Loot workspace, in
 * live mode), and checks Loot's view selector, live and saved. The retired Statistics page and its sub-pages, exempt until P6, are
 * gone (P6a): every page with a filter row is in this matrix. P6b Task 14: Loot › Explore has one view selector, leading the row it
 * shows, and live its dashboard's row hosts the Scope chip (the workspace's row is hidden).
 */
public class FilterBarEvidenceTest {
    @Rule public final TableViewRule tableView = new TableViewRule();
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("redesign-p1c");
    private final Map<String, String> savedPreferences = new LinkedHashMap<>();

    @Before public void isolatePreferences() throws Exception {
        edt(() -> {
            for (String key : new String[]{"ux.archive.characters-live-roster", "ui.tabs.character", "ui.tabs.quests",
                    "ui.filters.runs.open", "ui.filters.loot.open", "ui.filters.chat.open", "ui.filters.keypops.open",
                    "ui.filters.characters.open", "ui.filters.quests.open",
                    // Timeline, Resources and Party: their drawer keys, and the tab layouts the Resources page reads.
                    "ui.filters.timeline.open", "ui.filters.combat.open", "ui.filters.inspect-roster.open", "ui.tabs.activity-combat", "ui.tabs.saved-resources",
                    // P5b: the Live meter, Recordings and Dungeons drawers, the meter's nested tabs and the Dungeons view.
                    "ui.filters.dps-meter.open", "ui.filters.encounter-library.open", "ui.filters.dungeons.open", "ui.tabs.dps", "ui.dungeons.view",
                    // P6a: Loot's live filter row.
                    "ui.filters.loot-live.open"}) {
                savedPreferences.put(key, util.PropertiesManager.getProperty(key));
                util.PropertiesManager.setProperties(key, "");
            }
            return null;
        });
    }

    @After public void restorePreferences() throws Exception {
        // Disposal can queue a final view-state save; restore only after that EDT work drains.
        edt(() -> null);
        edt(() -> { savedPreferences.forEach((key, value) -> util.PropertiesManager.setProperties(key, value == null ? "" : value)); return null; });
    }

    /** The Party roster is not the live owner, but clear any roster a live owner left so no capture state outlives the test. */
    @After public void clearInspectRoster() { ParsePanelGUI.clear(); }

    /** The Live meter page replaces the DPS page instance and reads the static DPS preset: both are put back after the test. */
    private final Map<Field, Object> dpsStatics = new LinkedHashMap<>();
    private final List<Set<String>> presetSets = new ArrayList<>();
    private final Set<Integer> presetClasses = new HashSet<>();
    @Before public void isolateDpsStatics() throws Exception {
        for (Class<?> type : new Class<?>[]{DpsGUI.class, Filter.class})
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) { field.setAccessible(true); dpsStatics.put(field, field.get(null)); }
        presetSets.add(new HashSet<>(Filter.filterNames)); presetSets.add(new HashSet<>(Filter.filterGuilds)); presetClasses.addAll(Filter.filterClasses);
        edt(() -> { Filter.selectFilter(null); Filter.disable(); return null; });   // no preset hides a player of the synthetic fight
    }
    @After public void restoreDpsStatics() throws Exception {
        edt(() -> {
            for (Map.Entry<Field, Object> entry : dpsStatics.entrySet()) entry.getKey().set(null, entry.getValue());
            Filter.filterNames.clear(); Filter.filterGuilds.clear(); Filter.filterClasses.clear();
            Filter.filterNames.addAll(presetSets.get(0)); Filter.filterGuilds.addAll(presetSets.get(1)); Filter.filterClasses.addAll(presetClasses);
            return null;
        });
    }

    /**
     * The capture harness's frame paints a 23 px title band and 6 px edges over its content (P6a): each page is shown padded by
     * {@value #BAND} px at the top and {@value #EDGE} px at the sides and bottom, in a frame enlarged by as much, so the page itself
     * keeps the matrix size and its filter row is never hidden in the captures.
     */
    private static final int BAND = 24, EDGE = 6;

    private static final class Page {
        final String name; final JComponent root; final FilterBar bar; final BooleanSupplier ready;
        /** The frame's content: {@code root} padded clear of the harness's title band and edges. */
        final JComponent framed;
        Page(String name, JComponent root, FilterBar bar, BooleanSupplier ready) {
            this.name = name; this.root = root; this.bar = bar; this.ready = ready;
            JPanel padded = new JPanel(new BorderLayout());
            padded.setBorder(BorderFactory.createEmptyBorder(BAND, EDGE, EDGE, EDGE));
            padded.add(root, BorderLayout.CENTER);
            framed = padded;
        }
    }

    @Test @SuppressWarnings("unchecked") public void adoptedPagesShowOneFilterRowUntilTheDrawerOpens() throws Exception {
        Path root = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        Path runsScratch = temp.newFolder().toPath(), lootScratch = temp.newFolder().toPath(), chatScratch = temp.newFolder().toPath(), popsScratch = temp.newFolder().toPath();
        Path timelineScratch = temp.newFolder().toPath(), resourcesScratch = temp.newFolder().toPath(), exploreScratch = temp.newFolder().toPath();
        // Loot › Explore live keeps its own view states: the saved Loot page's state (archive mode) must not restore into it.
        ArchiveNativeSupport.Memory exploreStates = new ArchiveNativeSupport.Memory();
        try (SessionStore store = new SessionStore(root, true, "p1c-evidence"); DiscoveryLog log = new DiscoveryLog(null)) {
            for (int i = 0; i < 6; i++) {
                ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "visit-" + i; visit.map = i % 2 == 0 ? "Lost Halls" : "Ice Citadel";
                visit.started = 1_790_000_000_000L + i * 600_000L; visit.lastSeen = visit.ended = visit.started + 420_000L; store.put("runs", visit.id, visit);
                // Timeline events linked to each saved run: an entry and an equipment change, so a Types facet narrows the list.
                for (String kind : new String[]{"Area entered", "Equipment changed"}) {
                    ActivityJournal.Entry event = new ActivityJournal.Entry(); event.id = visit.id + "-" + kind.charAt(0); event.visitId = visit.id; event.map = visit.map;
                    event.kind = kind; event.time = visit.started + (kind.startsWith("Area") ? 1_000L : 60_000L); event.detail = "Synthetic observation";
                    event.values = new LinkedHashMap<>(); if (!kind.startsWith("Area")) { event.values.put("slot", 0); event.values.put("before", -1); event.values.put("after", 123); }
                    store.append("timeline", event);
                }
            }
            store.flush();
            List<Page> pages = edt(() -> {
                List<Page> built = new ArrayList<>();
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs =
                    ActivityPanel.workspace(store, new ActivityPanel(log, ActivityPanel.Mode.RUNS), ActivityPanel.Mode.RUNS, runsScratch, memory.states);
                ActivityQueries.Filters run = runs.state().query.facets(); run.outcomes.add(ActivityQueries.Outcome.LEFT); run.minimumDurationMillis = 60_000L;
                runs.changeQuery(runs.state().query.withFacets(run)); built.add(archive("runs", runs));
                ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> loot = HistoricalStatistics.lootWorkspace(store, new LootDashboard(), lootScratch, memory.states);
                LootQuery.Facets items = loot.state().query.facets(); items.bags.add("White"); items.kind = LootQuery.Kind.UT_EQUIPMENT;
                loot.changeQuery(loot.state().query.withFacets(items)); built.add(archive("loot", loot));
                // P6a: Loot's live bar, its view selector in the search slot, a UT facet applied through the drawer's controls.
                LootDashboard lootLive = new LootDashboard();
                VisualEvidence.named(lootLive, "loot-kind", JComboBox.class).setSelectedItem(LootQuery.Kind.UT_EQUIPMENT);
                VisualEvidence.named(lootLive, "loot-apply-facets", AbstractButton.class).doClick();
                built.add(new Page("loot-live", lootLive, VisualEvidence.named(lootLive, "loot-live-filter-bar", FilterBar.class), () -> true));
                // P6a: Loot › Explore live beside saved history, as the Loot page builds it (the live dashboard inside the Loot
                // workspace, live mode): the same row, hosting the Scope chip since P6b; a UT facet through the drawer.
                LootDashboard exploreLive = new LootDashboard();
                ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> explore = HistoricalStatistics.lootWorkspace(store, exploreLive, exploreScratch, exploreStates.states);
                VisualEvidence.named(exploreLive, "loot-kind", JComboBox.class).setSelectedItem(LootQuery.Kind.UT_EQUIPMENT);
                VisualEvidence.named(exploreLive, "loot-apply-facets", AbstractButton.class).doClick();
                built.add(new Page("loot-explore-live", explore, VisualEvidence.named(exploreLive, "loot-live-filter-bar", FilterBar.class),
                    () -> !explore.state().archive && !explore.loading()));
                ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort> chat =
                    (ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort>) new ChatGUI(new TomatoData()).workspace(store, chatScratch, memory.states);
                ChatArchiveClient.Facets channel = chat.state().query.facets(); channel.channel = "GUILD"; channel.starredOnly = true;
                chat.changeQuery(chat.state().query.withFacets(channel)); built.add(archive("chat", chat));
                ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> keypops =
                    (ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort>) new KeypopGUI().workspace(store, popsScratch, memory.states);
                KeyPopArchiveClient.Facets pops = keypops.state().query.facets(); pops.exactPlayer = "Ann"; pops.kinds.add("KEY");
                keypops.changeQuery(keypops.state().query.withFacets(pops)); built.add(archive("keypops", keypops));
                CharacterJournalGUI characters = new CharacterJournalGUI(new TomatoData().characterJournal());
                VisualEvidence.named(characters, "character-facet-2", JComboBox.class).setSelectedIndex(1);
                VisualEvidence.named(characters, "character-facet-4", JComboBox.class).setSelectedIndex(2);
                built.add(new Page("characters", characters, VisualEvidence.named(characters, "characters-filter-bar", FilterBar.class), () -> true));
                QuestGUI quests = new QuestGUI();
                VisualEvidence.named(quests, "quest-repeat-mode", JComboBox.class).setSelectedIndex(1);
                VisualEvidence.named(quests, "quest-pinned-only", AbstractButton.class).doClick();
                built.add(new Page("quests", quests, VisualEvidence.named(quests, "quests-filter-bar", FilterBar.class), () -> true));
                // P1c pages without S6 captures until P5b: saved Timeline and Resources archives, and Party's Inspect roster.
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> timeline =
                    ActivityPanel.workspace(store, new ActivityPanel(log, ActivityPanel.Mode.TIMELINE), ActivityPanel.Mode.TIMELINE, timelineScratch, memory.states);
                ActivityQueries.Filters types = timeline.state().query.facets(); types.kinds.add("Area entered");
                timeline.changeQuery(timeline.state().query.withFacets(types)); built.add(archive("timeline", timeline));
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> resources =
                    ActivityPanel.workspace(store, new ActivityPanel(log, ActivityPanel.Mode.COMBAT), ActivityPanel.Mode.COMBAT, resourcesScratch, memory.states);
                ActivityQueries.Filters outcome = resources.state().query.facets(); outcome.outcomes.add(ActivityQueries.Outcome.LEFT);
                resources.changeQuery(resources.state().query.withFacets(outcome)); built.add(archive("resources", resources));
                ParsePanelGUI party = inspectRoster(); VisualEvidence.named(party, "inspect-facet-2", JComboBox.class).setSelectedIndex(1);
                built.add(new Page("party", party, VisualEvidence.named(party, "inspect-roster-filter-bar", FilterBar.class), () -> true));
                // P5b: Runs & DPS › Live meter over a synthetic encounter with a player search ("Player: alp"), Recordings over this
                // app run's recording and the saved runs with a source facet, and Dungeons over the saved runs with a search.
                TomatoData fight = new TomatoData(); fight.dpsData.add(meterEncounter());
                DpsGUI meter = new DpsGUI(fight, log);
                assertTrue(meter.showEncounter(meter.encounters().entries().get(0).id));
                FilterBar meterBar = VisualEvidence.named(meter, "dps-meter-filter-bar", FilterBar.class);
                VisualEvidence.find(meterBar, JTextField.class, field -> "Search players".equals(field.getClientProperty("JTextField.placeholderText"))).setText("alp");
                built.add(new Page("dps-meter", meter, meterBar, () -> true));
                DungeonListGUI recordings = recordings(meter, fight, store, memory);
                VisualEvidence.named(recordings, "encounter-source", JComboBox.class).setSelectedIndex(1);   // This app run
                JTextArea read = VisualEvidence.named(recordings, "encounter-summary", JTextArea.class);
                built.add(new Page("encounter-library", recordings, VisualEvidence.named(recordings, "encounter-library-filter-bar", FilterBar.class),
                    () -> read.getText().matches("\\d+ of \\d+ recordings shown · .*") && !read.getText().contains("reading…")));
                DungeonsView dungeons = new DungeonsView(() -> store, java.time.ZoneId.systemDefault(), System::currentTimeMillis, JPanel::new);
                VisualEvidence.named(dungeons, "dungeons-search", JTextField.class).setText("Lost");
                JList<?> cards = VisualEvidence.named(dungeons, "dungeons-cards", JList.class);
                built.add(new Page("dungeons", dungeons, VisualEvidence.named(dungeons, "dungeons-filter-bar", FilterBar.class), () -> cards.getModel().getSize() == 1));
                return built;
            });
            try {
                for (Page page : pages) for (int font : new int[]{13, 18}) for (int[] size : new int[][]{{1240, 800}, {680, 520}}) for (boolean open : new boolean[]{false, true}) {
                    edt(() -> { ArchiveNativeSupport.drawer(page.bar, open); evidence.show(page.framed, page.name, size[0] + 2 * EDGE, size[1] + BAND + EDGE, font); return null; });
                    ArchiveNativeSupport.await(page.ready); evidence.settle(); ArchiveNativeSupport.await(page.ready);
                    edt(() -> {
                        evidence.capture("p1c-" + page.name + "-" + size[0] + "-" + font + (open ? "-filters-open" : "-filters-closed"));
                        assertEquals(page.name + " keeps the matrix size (padded clear of the title band)", new Dimension(size[0], size[1]), page.root.getSize());
                        assertEquals(open, page.bar.drawerOpen());
                        assertEquals(page.name + " drawer visibility", open, page.bar.drawerContent().isShowing());
                        assertTrue(page.name + " shows its active filters as chips", page.bar.activeCount() > 0);
                        if (!open && size[0] == 1240 && font == 13) assertOneFilterRow(page.name, page.bar);
                        if (!open && size[0] == 1240 && font == 13 && page.name.startsWith("loot")) assertLootViewSelector(page);
                        return null;
                    });
                }
            } finally {
                edt(() -> {
                    for (Page page : pages) { ArchiveNativeSupport.drawer(page.bar, false); if (page.root instanceof ArchiveWorkspace) ((ArchiveWorkspace<?, ?, ?>) page.root).close(); }
                    for (Page page : pages) {
                        if (page.root instanceof DungeonListGUI) ((DungeonListGUI) page.root).close();
                        if (page.root instanceof DungeonsView) ((DungeonsView) page.root).close();
                    }
                    evidence.closeWindow(); return null;
                });
            }
        }
    }

    private static Page archive(String name, ArchiveWorkspace<?, ?, ?> workspace) {
        return new Page(name, workspace, workspace.filterBar(), () -> ArchiveNativeSupport.ready(workspace) && workspace.state().archive);
    }

    /**
     * Runs &amp; DPS › Recordings over {@code meter}'s recordings and {@code store}'s saved history, with the test's view states
     * (its package-private constructor; the app's reads the application's states and history).
     */
    private static DungeonListGUI recordings(DpsGUI meter, TomatoData data, SessionStore store, ArchiveNativeSupport.Memory memory) throws ReflectiveOperationException {
        Constructor<DungeonListGUI> library = DungeonListGUI.class.getDeclaredConstructor(DpsGUI.class, TomatoData.class, ViewStateStore.class,
            java.util.function.Supplier.class, java.util.function.LongSupplier.class);
        library.setAccessible(true);
        java.util.function.Supplier<SessionStore> history = () -> store;
        java.util.function.LongSupplier clock = System::currentTimeMillis;
        return library.newInstance(meter, data, memory.states, history, clock);
    }

    /** A closed synthetic encounter: four players on a boss and a minion (synthetic names; no capture). */
    private static DpsData meterEncounter() {
        CombatFixtures.Fight fight = CombatFixtures.fight("Synthetic Halls");
        String[] names = {"Alpha", "Bravo", "Charlie", "Delta"};
        List<Entity> party = new ArrayList<>();
        for (int p = 0; p < names.length; p++) party.add(fight.player(p + 1, CombatFixtures.CLASSES[p], names[p]));
        Entity boss = fight.enemy(50, 6100, "Synthetic boss", 900_000, true), minion = fight.enemy(51, 6000, "Synthetic minion", 1_000, false);
        for (int s = 0; s < 20; s++) for (int p = 0; p < party.size(); p++) fight.hit(s % 4 == 0 ? minion : boss, party.get(p), 100 + 25 * p, 1_000 + s * 500L + p * 20L);
        return fight.ticks(1_000, 10_000).build();
    }

    /** Party's player table: the non-owner Inspect roster (as a saved run shows it). Its constructor is package-private. */
    private static ParsePanelGUI inspectRoster() throws ReflectiveOperationException {
        Constructor<ParsePanelGUI> roster = ParsePanelGUI.class.getDeclaredConstructor(boolean.class); roster.setAccessible(true);
        return roster.newInstance(false);
    }

    /**
     * Loot's one view selector (P6a; one control since P6b Task 14): it leads the search slot of the row the page shows. Live, that
     * is the dashboard's row, which inside the Loot workspace also hosts the Scope chip while the workspace's row (saved search,
     * Filters) is hidden; saved, it is the workspace's row, and the saved view builds no selector of its own.
     */
    private static void assertLootViewSelector(Page page) {
        AbstractButton filters = VisualEvidence.named(page.bar, page.bar.getName().replace("-filter-bar", "-filters"), AbstractButton.class);
        Component slot = filters.getParent().getComponent(0);
        JComboBox<?> selector = VisualEvidence.named(page.root, "loot-views", JComboBox.class);
        assertTrue(page.name + ": the view selector sits in the search slot", selector.isShowing() && SwingUtilities.isDescendingFrom(selector, slot));
        if (!(page.root instanceof ArchiveWorkspace)) return;
        ArchiveWorkspace<?, ?, ?> workspace = (ArchiveWorkspace<?, ?, ?>) page.root;
        assertEquals(page.name + ": live or saved history", !page.name.endsWith("-live"), workspace.state().archive);
        FilterBarAssert.assertChipInVisibleBar(workspace);
        if (page.name.endsWith("-live")) {
            assertFalse(page.name + ": the saved search is hidden while live", VisualEvidence.named(workspace, "loot-history-search", JComponent.class).isShowing());
            assertFalse(page.name + ": the saved Filters toggle is hidden while live",
                VisualEvidence.named(workspace.filterBar(), "loot-filters", AbstractButton.class).isShowing());
        } else assertNull("loot: the saved view builds no selector of its own", search(workspace, "loot-archive-view"));
    }
    private static Component search(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container) { Component found = search((Container) child, name); if (found != null) return found; }
        }
        return null;
    }

    /** S6 at desktop width: with the drawer closed, the search slot and the Filters toggle share one row. */
    private static void assertOneFilterRow(String name, FilterBar bar) {
        AbstractButton filters = VisualEvidence.named(bar, bar.getName().replace("-filter-bar", "-filters"), AbstractButton.class);
        Component slot = filters.getParent().getComponent(0);
        int slotY = SwingUtilities.convertPoint(slot, 0, 0, bar).y, filtersY = SwingUtilities.convertPoint(filters, 0, 0, bar).y;
        assertTrue(name + ": the search slot and Filters share one row", Math.abs(slotY - filtersY) < filters.getHeight());
    }
}
