package tomato.gui.history;

import com.google.gson.Gson;
import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.DpsData;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.bridge.BridgeConfig;
import tomato.bridge.BridgePayload;
import tomato.bridge.BridgeService;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.bridge.BridgeReviewGUI;
import tomato.gui.character.CharacterJournalGUI;
import tomato.gui.character.TableViewRule;
import tomato.gui.chat.ChatArchiveClient;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.DungeonListGUI;
import tomato.gui.dps.Filter;
import tomato.gui.keypop.KeyPopArchiveClient;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.logging.LoggingGUI;
import tomato.gui.modern.FormattingTestSupport;
import tomato.gui.quest.QuestGUI;
import tomato.gui.runs.DungeonsView;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.security.SecurityGUI;
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
 *
 * <p>P6b Task 15 (strict S6): every archive page is captured live too, its own row hosting the Scope chip ({@code -live}: Runs'
 * table, Timeline, Resources, Chat, Key-pops and Party's three tabs, with SecurityGUI in its workspace), beside saved history, which
 * now includes saved Party; Logging (every tab) and Bridge review (Review and Logs) join the matrix. At 1240×800 font 13 with the
 * drawer closed, every filter row a page shows passes {@link FilterBarAssert#assertOneRow} (every control of the top row on its
 * first line, the row under 1.6 control heights). On archive pages, in every capture, the chip is in the row the page shows and the
 * workspace's row is not showing while live. Pages are shown in Simple, and Party's Analyst-only Ability Use in Analyst.
 */
public class FilterBarEvidenceTest {
    @Rule public final TableViewRule tableView = new TableViewRule();
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("redesign-p1c");
    /** One-row failures are collected, so a run captures and names every page whose row wraps; the test still fails for each. */
    @Rule public ErrorCollector rows = new ErrorCollector();
    private final Map<String, String> savedPreferences = new LinkedHashMap<>();
    private DisplayModeModel.Mode applicationMode;

    @Before public void isolatePreferences() throws Exception {
        edt(() -> {
            applicationMode = DisplayModeModel.application().mode();
            for (String key : new String[]{"ux.archive.characters-live-roster", "ui.tabs.character", "ui.tabs.quests",
                    "ui.filters.runs.open", "ui.filters.loot.open", "ui.filters.chat.open", "ui.filters.keypops.open",
                    "ui.filters.characters.open", "ui.filters.quests.open",
                    // Timeline, Resources and Party: their drawer keys, and the tab layouts the Resources page reads.
                    "ui.filters.timeline.open", "ui.filters.combat.open", "ui.filters.inspect-roster.open", "ui.tabs.activity-combat", "ui.tabs.saved-resources",
                    // P5b: the Live meter, Recordings and Dungeons drawers, the meter's nested tabs and the Dungeons view.
                    "ui.filters.dps-meter.open", "ui.filters.encounter-library.open", "ui.filters.dungeons.open", "ui.tabs.dps", "ui.dungeons.view",
                    // P6a: Loot's live filter row.
                    "ui.filters.loot-live.open",
                    // P6b Task 15: the live rows, saved Party, Logging and Bridge review; their tab layouts; the live Party roster's
                    // application view state (the live owner binds it); and the display mode, which the pages set.
                    "ui.filters.activity-runs.open", "ui.filters.activity-timeline.open", "ui.filters.activity-combat.open", "ui.filters.chat-live.open",
                    "ui.filters.keypops-live.open", "ui.filters.inspect-runs.open", "ui.filters.ability.open", "ui.filters.inspect.open",
                    "ui.filters.logging.open", "ui.filters.bridge-review.open", "ui.filters.bridge-logs.open",
                    "ui.tabs.inspect", "ui.tabs.logging", "ui.tabs.bridge", "ui.tabs.keypops-live", "ui.tabs.keypops-saved",
                    "ux.archive.inspect-live-roster", DisplayModeModel.KEY}) {
                savedPreferences.put(key, util.PropertiesManager.getProperty(key));
                util.PropertiesManager.setProperties(key, "");
            }
            return null;
        });
    }

    @After public void restorePreferences() throws Exception {
        // Disposal can queue a final view-state save; restore only after that EDT work drains.
        edt(() -> null);
        edt(() -> {
            DisplayModeModel.application().set(applicationMode);
            savedPreferences.forEach((key, value) -> util.PropertiesManager.setProperties(key, value == null ? "" : value));
            return null;
        });
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
        /** Runs once on the EDT before the page's first capture: the tab it shows and its chips, when {@code root} hosts several pages. */
        Runnable prepare;
        /** Simple, or Analyst for an Analyst-only tab. */
        DisplayModeModel.Mode mode = DisplayModeModel.Mode.SIMPLE;
        /** Whether the page applies filters shown as chips; a row without a drawer has none to show (Party › Runs, Bridge › Logs). */
        boolean chips = true;
        Page(String name, JComponent root, FilterBar bar, BooleanSupplier ready) {
            this.name = name; this.root = root; this.bar = bar; this.ready = ready;
            framed = new JPanel(new BorderLayout());
            framed.setBorder(BorderFactory.createEmptyBorder(BAND, EDGE, EDGE, EDGE));
        }
        Page prepare(Runnable value) { prepare = value; return this; }
        Page analyst() { mode = DisplayModeModel.Mode.ANALYST; return this; }
        Page withoutChips() { chips = false; return this; }
        /**
         * Pads {@code root} into this page's frame content (pages of one component take it in turn) and sets the mode. A component
         * leaving the displayed frame stops following the mode until it is shown again, so {@link #prepare} runs once it shows.
         */
        void mount() {
            if (root.getParent() != framed) framed.add(root, BorderLayout.CENTER);
            DisplayModeModel.application().set(mode);
        }
    }

    @Test @SuppressWarnings("unchecked") public void adoptedPagesShowOneFilterRowUntilTheDrawerOpens() throws Exception {
        Path root = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        Path runsScratch = temp.newFolder().toPath(), lootScratch = temp.newFolder().toPath(), chatScratch = temp.newFolder().toPath(), popsScratch = temp.newFolder().toPath();
        Path timelineScratch = temp.newFolder().toPath(), resourcesScratch = temp.newFolder().toPath(), exploreScratch = temp.newFolder().toPath();
        // Loot › Explore live keeps its own view states: the saved Loot page's state (archive mode) must not restore into it.
        ArchiveNativeSupport.Memory exploreStates = new ArchiveNativeSupport.Memory();
        // P6b Task 15: the live pages' workspaces keep their own view states too, so none restores the saved pages' archive mode.
        ArchiveNativeSupport.Memory liveStates = new ArchiveNativeSupport.Memory();
        Path liveJournal = temp.newFolder().toPath(), liveScratch = temp.newFolder().toPath(), partySavedScratch = temp.newFolder().toPath();
        Files.write(liveJournal.resolve("activity-history.json"), new Gson().toJson(liveJournal()).getBytes(StandardCharsets.UTF_8));
        try (SessionStore store = new SessionStore(root, true, "p1c-evidence"); DiscoveryLog log = new DiscoveryLog(null);
             DiscoveryLog retained = new DiscoveryLog(liveJournal); DiscoveryLog diagnostics = new DiscoveryLog(null); BridgeService bridge = bridge()) {
            retained.setSaving(false); diagnostics.setSaving(false);
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
                built.add(new Page("loot-explore-live", explore, VisualEvidence.named(exploreLive, "loot-live-filter-bar", FilterBar.class), live(explore)));
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
            // P6b Task 15: the live host rows, each with filters applied as chips, over a retained journal of two visits.
            ActivityPanel runsLive = edt(() -> new ActivityPanel(retained, ActivityPanel.Mode.RUNS)), timelineLive = edt(() -> new ActivityPanel(retained, ActivityPanel.Mode.TIMELINE)),
                resourcesLive = edt(() -> new ActivityPanel(retained, ActivityPanel.Mode.COMBAT));
            edt(() -> { timelineLive.refresh(); resourcesLive.refresh(); return null; });
            ArchiveNativeSupport.await(() -> visits(timelineLive) == 3 && visits(resourcesLive) == 2);   // "All visits" and both, or both
            pages.addAll(edt(() -> {
                List<Page> built = new ArrayList<>();
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs =
                    ActivityPanel.workspace(store, runsLive, ActivityPanel.Mode.RUNS, liveScratch.resolve("runs"), liveStates.states);
                ActivityQueries.Filters run = new ActivityQueries.Filters(); run.outcomes.add(ActivityQueries.Outcome.COMPLETED); run.maximumDurationMillis = 600_000L;
                runsLive.setRunFilters(run);
                built.add(new Page("runs-live", runs, runs.liveFilterBar(), live(runs)));
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> timeline =
                    ActivityPanel.workspace(store, timelineLive, ActivityPanel.Mode.TIMELINE, liveScratch.resolve("timeline"), liveStates.states);
                timelineLive.selectVisit("live-visit-0"); VisualEvidence.named(timelineLive, "activity-kind", JComboBox.class).setSelectedItem("Party");
                built.add(new Page("timeline-live", timeline, timeline.liveFilterBar(), live(timeline)));
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> resources =
                    ActivityPanel.workspace(store, resourcesLive, ActivityPanel.Mode.COMBAT, liveScratch.resolve("combat"), liveStates.states);
                resourcesLive.selectVisit("live-visit-0");   // the older visit: a chip (the newest is the default)
                built.add(new Page("resources-live", resources, resources.liveFilterBar(), live(resources)));
                ChatGUI chatLive = new ChatGUI(new TomatoData());
                ArchiveWorkspace<?, ?, ?> chat = (ArchiveWorkspace<?, ?, ?>) chatLive.workspace(store, liveScratch.resolve("chat"), liveStates.states);
                VisualEvidence.named(chatLive, "chat-player", JTextField.class).setText("Wren"); VisualEvidence.button(chatLive, "Starred").doClick();
                built.add(new Page("chat-live", chat, chat.liveFilterBar(), live(chat)));
                KeypopGUI keypopsLive = new KeypopGUI();
                ArchiveWorkspace<?, ?, ?> keypops = (ArchiveWorkspace<?, ?, ?>) keypopsLive.workspace(store, liveScratch.resolve("keypops"), liveStates.states);
                VisualEvidence.named(keypopsLive, "keypop-type", JComboBox.class).setSelectedItem("Key");
                VisualEvidence.named(keypopsLive, "keypop-period", JComboBox.class).setSelectedIndex(1);
                built.add(new Page("keypops-live", keypops, keypops.liveFilterBar(), live(keypops)));
                // Party: one page in its workspace, a page per tab; the roster's facet and Ability Use's heuristic and window as chips.
                SecurityGUI partyLive = securityPage(retained);
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> party = SecurityGUI.workspace(store, partyLive, liveScratch.resolve("inspect"), liveStates.states);
                VisualEvidence.named(partyLive, "inspect-facet-2", JComboBox.class).setSelectedIndex(1);
                FilterBar abilityBar = FormattingTestSupport.field(FormattingTestSupport.field(partyLive, "abilityUse", JComponent.class), "filterBar", FilterBar.class);
                String[][] partyTabs = {{"area", "inspect-roster"}, {"runs", "inspect-runs"}, {"ability", "ability"}};
                for (String[] tab : partyTabs) {
                    // Ability Use is Analyst-only: its tab (and so its row) is in the page only while Analyst shows it.
                    FilterBar bar = "ability".equals(tab[0]) ? abilityBar : VisualEvidence.named(partyLive, tab[1] + "-filter-bar", FilterBar.class);
                    Page page = new Page("party-" + tab[0] + "-live", party, bar, live(party)).prepare(() -> {
                        tabs(partyLive, "inspect").select(tab[0]);
                        if ("ability".equals(tab[0]) && abilityBar.activeCount() == 0) {
                            VisualEvidence.named(partyLive, "ability-kind", JComboBox.class).setSelectedIndex(1);
                            VisualEvidence.named(partyLive, "ability-range", JComboBox.class).setSelectedIndex(1);
                        }
                    });
                    if ("runs".equals(tab[0])) page.withoutChips();   // search only: no drawer
                    if ("ability".equals(tab[0])) page.analyst();
                    built.add(page);
                }
                // Saved Party: its workspace's row over the saved runs, with run facets as chips (the saved pages' view states).
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> partySaved = SecurityGUI.workspace(store, securityPage(log), partySavedScratch, memory.states);
                ActivityQueries.Filters partyRuns = partySaved.state().query.facets(); partyRuns.outcomes.add(ActivityQueries.Outcome.LEFT); partyRuns.minimumDurationMillis = 60_000L;
                partySaved.changeQuery(partySaved.state().query.withFacets(partyRuns)); built.add(archive("party-saved", partySaved));
                // Logging: one page, a capture set per tab, each with its own search as a chip (and Packets' and Event samples' check).
                LoggingGUI logging = loggingPage(diagnostics, new ArchiveNativeSupport.Memory().states);
                JTextField loggingSearch = VisualEvidence.named(logging, "logging-search", JTextField.class);
                for (String tab : new String[]{"discovery", "reentry", "packets", "stats", "events", "fields"})
                    built.add(new Page("logging-" + tab, logging, VisualEvidence.named(logging, "logging-filter-bar", FilterBar.class),
                        () -> FormattingTestSupport.field(logging, "snapshot", Object.class) != null).prepare(() -> {
                            tabs(logging, "logging").select(tab);
                            loggingSearch.setText("800");
                            if ("packets".equals(tab)) VisualEvidence.button(logging, "Observed packets only").doClick();
                            if ("events".equals(tab)) VisualEvidence.named(logging, "logging-changed", AbstractButton.class).doClick();
                        }));
                // Bridge review over a local-review-only bridge: Review with an outcome and a status as chips; Logs has no drawer.
                BridgeReviewGUI review = new BridgeReviewGUI(bridge);
                built.add(new Page("bridge-review", review, VisualEvidence.named(review, "bridge-review-filter-bar", FilterBar.class), () -> true).prepare(() -> {
                    tabs(review, "bridge").select("review");
                    VisualEvidence.named(review, "bridge-outcome-filter", JComboBox.class).setSelectedItem(BridgeService.Outcome.LOCAL);
                    VisualEvidence.named(review, "bridge-status-filter", JComboBox.class).setSelectedItem("Local only");
                }));
                built.add(new Page("bridge-logs", review, VisualEvidence.named(review, "bridge-logs-filter-bar", FilterBar.class), () -> true)
                    .prepare(() -> tabs(review, "bridge").select("logs")).withoutChips());
                return built;
            }));
            try {
                for (Page page : pages) {
                    edt(() -> { page.mount(); evidence.show(page.framed, page.name, 1240 + 2 * EDGE, 800 + BAND + EDGE, 13); return null; });
                    // Once shown in its mode, the page's tab and chips, with every other row's drawer closed (Party › Runs also shows
                    // the roster's row, whose drawer the Current Area captures left open); a row without a drawer (Discovery,
                    // Party › Runs, Bridge › Logs) has no open state to capture.
                    boolean drawer = edt(() -> {
                        if (page.prepare != null) page.prepare.run();
                        List<FilterBar> all = new ArrayList<>(); bars(page.root, all, false);
                        for (FilterBar other : all) if (other != page.bar) ArchiveNativeSupport.drawer(other, false);
                        return page.bar.drawerContent() != null;
                    });
                    for (int font : new int[]{13, 18}) for (int[] size : new int[][]{{1240, 800}, {680, 520}}) for (boolean open : drawer ? new boolean[]{false, true} : new boolean[]{false}) {
                        edt(() -> { ArchiveNativeSupport.drawer(page.bar, open); evidence.show(page.framed, page.name, size[0] + 2 * EDGE, size[1] + BAND + EDGE, font); return null; });
                        ArchiveNativeSupport.await(page.ready); evidence.settle(); ArchiveNativeSupport.await(page.ready);
                        edt(() -> {
                            evidence.capture("p1c-" + page.name + "-" + size[0] + "-" + font + (open ? "-filters-open" : "-filters-closed"));
                            assertEquals(page.name + " keeps the matrix size (padded clear of the title band)", new Dimension(size[0], size[1]), page.root.getSize());
                            assertEquals(page.name + " shows the mode it was set to", page.mode, DisplayModeModel.application().mode());
                            assertEquals(open, page.bar.drawerOpen());
                            if (drawer) assertEquals(page.name + " drawer visibility", open, page.bar.drawerContent().isShowing());
                            if (page.chips) assertTrue(page.name + " shows its active filters as chips", page.bar.activeCount() > 0);
                            else assertNull(page.name + ": no drawer, so no filters to show as chips", page.bar.drawerContent());
                            if (page.root instanceof ArchiveWorkspace) assertScope(page);
                            if (!open && size[0] == 1240 && font == 13) rows.checkSucceeds(() -> { assertEveryRowIsOneRow(page); return null; });
                            if (!open && size[0] == 1240 && font == 13 && page.name.startsWith("loot")) assertLootViewSelector(page);
                            return null;
                        });
                    }
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
    /** A workspace showing its live component: nothing is read from saved history. */
    private static BooleanSupplier live(ArchiveWorkspace<?, ?, ?> workspace) { return () -> !workspace.state().archive && !workspace.loading(); }

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

    /** The Party page over {@code log} (its package-private constructor; the app's reads the application's log and view states). */
    private static SecurityGUI securityPage(DiscoveryLog log) throws ReflectiveOperationException {
        Constructor<SecurityGUI> page = SecurityGUI.class.getDeclaredConstructor(DiscoveryLog.class); page.setAccessible(true);
        return page.newInstance(log);
    }

    /** The Logging page over {@code log} with in-memory view states (its package-private constructor), reading its first snapshot. */
    private static LoggingGUI loggingPage(DiscoveryLog log, ViewStateStore states) throws ReflectiveOperationException {
        Constructor<LoggingGUI> page = LoggingGUI.class.getDeclaredConstructor(DiscoveryLog.class, ViewStateStore.class); page.setAccessible(true);
        LoggingGUI logging = page.newInstance(log, states); logging.refresh(); return logging;
    }

    /** The customizable tab group {@code group} inside {@code root}, through its pane ({@code <group>-tabs}). */
    private static CustomizableTabs tabs(Container root, String group) {
        return (CustomizableTabs) VisualEvidence.named(root, group + "-tabs", JTabbedPane.class).getClientProperty(CustomizableTabs.class);
    }

    private static int visits(ActivityPanel panel) { return VisualEvidence.named(panel, "activity-visit", JComboBox.class).getItemCount(); }

    /** The live pages' retained journal: two dungeon visits with resource conditions and an entry of each of three kinds. */
    private static ActivityJournal.State liveJournal() {
        ActivityJournal.State state = new ActivityJournal.State();
        String[] maps = {"Lost Halls", "Ice Citadel"}, kinds = {"Equipment changed", "Resources", "Party roster"};
        for (int v = 0; v < maps.length; v++) {
            ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "live-visit-" + v; visit.map = maps[v];
            visit.started = 1_790_100_000_000L + v * 1_200_000L; visit.lastSeen = visit.ended = visit.started + 360_000L;
            visit.conditionObservedMillis = 300_000; visit.conditions.put("Damaging", 120_000L);
            state.visits.add(visit);
            for (int e = 0; e < kinds.length; e++) {
                ActivityJournal.Entry entry = new ActivityJournal.Entry(); entry.id = visit.id + "-event-" + e; entry.visitId = visit.id; entry.map = maps[v];
                entry.time = visit.started + e * 60_000L; entry.kind = kinds[e]; entry.detail = "Synthetic observation";
                entry.values = new LinkedHashMap<>(); entry.values.put("hp", 700); entry.values.put("mp", 150);
                state.entries.add(entry);
            }
        }
        return state;
    }

    /**
     * A local-review-only bridge (sending off): four synthetic drops are reviewed against a synthetic CSV and nothing is posted; the
     * transport refuses every call.
     */
    private BridgeService bridge() throws Exception {
        Path csv = temp.newFile("bridge-items.csv").toPath();
        Files.write(csv, "Item Name\nTest Sword\nCrystal Wand\nMystic Blade\n".getBytes(StandardCharsets.UTF_8));
        Properties settings = new Properties(); String x = BridgeConfig.PREFIX;
        settings.setProperty(x + "enabled", "true"); settings.setProperty(x + "send", "false"); settings.setProperty(x + "csv_path", csv.toString());
        BridgeService service = new BridgeService(temp.newFolder().toPath().resolve("bridge-fixture.properties"), false,
            (endpoint, json) -> { throw new java.io.IOException("S6 evidence never posts"); }, 20);
        service.configure(new BridgeConfig(settings), false, false);
        String[][] drops = {{"Test Sword", "The Shatters"}, {"Unlisted ST", "Lost Halls"}, {"Crystal Wand", "Ice Citadel"}, {"Mystic Blade", "Lost Halls"}};
        List<BridgePayload.Drop> received = new ArrayList<>();
        for (int i = 0; i < drops.length; i++)
            received.add(new BridgePayload.Drop(new BridgePayload.Item(42 + i, drops[i][0], "EQUIPMENT", "UT", "", false), 7 + i % 2, i % 2 == 0 ? "Example" : "Fixture", "Wizard", drops[i][1], false, false, 9, 0));
        service.receive(received);
        service.awaitIdle(3000);
        return service;
    }

    /**
     * An archive page's Scope chip and rows: live, the page's own row hosts the chip and the workspace's row is not showing; saved,
     * the chip is in the workspace's row. The chip is inside the workspace and in the row the page shows.
     */
    private static void assertScope(Page page) {
        ArchiveWorkspace<?, ?, ?> workspace = (ArchiveWorkspace<?, ?, ?>) page.root;
        boolean live = page.name.endsWith("-live");
        assertEquals(page.name + ": live or saved history", !live, workspace.state().archive);
        assertSame(page.name + ": the row the page shows", live ? workspace.liveFilterBar() : workspace.filterBar(), page.bar);
        FilterBarAssert.assertChipInVisibleBar(workspace);
        assertTrue(page.name + ": the Scope chip is in the row the page shows", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(workspace), page.bar));
        if (live) assertFalse(page.name + ": the workspace's row is not showing while live", workspace.filterBar().isShowing());
    }

    /** S6 at 1240×800 font 13 with the drawer closed: every filter row the page shows, its own among them, is one row. */
    private static void assertEveryRowIsOneRow(Page page) {
        List<FilterBar> shown = new ArrayList<>(); bars(page.root, shown, true);
        assertTrue(page.name + ": its filter row shows", shown.contains(page.bar));
        for (FilterBar bar : shown) {
            System.out.println("S6 " + page.name + ": " + FilterBarAssert.describeRow(bar));
            assertFalse(page.name + ": " + bar.getName() + "'s drawer is closed", bar.drawerOpen());
            try { FilterBarAssert.assertOneRow(bar); }
            catch (AssertionError failure) { throw new AssertionError(page.name + ": " + failure.getMessage(), failure); }
        }
    }
    /** The filter rows inside {@code root}, or only those showing. */
    private static void bars(Container root, List<FilterBar> found, boolean showingOnly) {
        for (Component child : root.getComponents()) {
            if (child instanceof FilterBar && (!showingOnly || child.isShowing())) found.add((FilterBar) child);
            if (child instanceof Container) bars((Container) child, found, showingOnly);
        }
    }

    /**
     * Loot's one view selector (P6a; one control since P6b Task 14): it leads the search slot of the row the page shows. Live, that
     * is the dashboard's row, which inside the Loot workspace also hosts the Scope chip while the workspace's row (saved search,
     * Filters) is hidden; saved, it is the workspace's row, and the saved view builds no selector of its own.
     */
    private static void assertLootViewSelector(Page page) {
        JComboBox<?> selector = VisualEvidence.named(page.root, "loot-views", JComboBox.class);
        assertTrue(page.name + ": the view selector sits in the search slot", selector.isShowing() && SwingUtilities.isDescendingFrom(selector, page.bar.searchSlot()));
        if (!(page.root instanceof ArchiveWorkspace)) return;
        ArchiveWorkspace<?, ?, ?> workspace = (ArchiveWorkspace<?, ?, ?>) page.root;
        assertEquals(page.name + ": live or saved history", !page.name.endsWith("-live"), workspace.state().archive);
        FilterBarAssert.assertChipInVisibleBar(workspace);
        if (page.name.endsWith("-live")) {
            assertFalse(page.name + ": the saved search is hidden while live", VisualEvidence.named(workspace, "loot-history-search", JComponent.class).isShowing());
            assertFalse(page.name + ": the saved Filters toggle is hidden while live",
                VisualEvidence.named(workspace.filterBar(), "loot-filters", AbstractButton.class).isShowing());
        } else {
            assertTrue("loot: the selector is in the workspace's search slot", SwingUtilities.isDescendingFrom(selector, workspace.filterBar().searchSlot()));
            assertNull("loot: the saved view builds no selector of its own", search(workspace, "loot-archive-view"));
        }
    }
    private static Component search(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container) { Component found = search((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
