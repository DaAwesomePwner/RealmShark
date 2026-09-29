package ui;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.logger.ActivityJournal;
import assets.IdToAsset;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.DamageSource;
import tomato.backend.data.DpsData;
import tomato.backend.data.Entity;
import tomato.backend.data.Projectile;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.character.CharacterPetsGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.CombatAutosave;
import tomato.gui.dps.CombatSummaries;
import tomato.gui.dps.DpsDisplayOptions;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.DungeonListGUI;
import tomato.gui.dps.EncounterImport;
import tomato.gui.dps.Filter;
import tomato.gui.dps.MeterDpsGUI;
import tomato.gui.dps.RecordingSummaryPanel;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.kit.Banner;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.TileList;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.NavEntry;
import tomato.gui.modern.NavLayout;
import tomato.gui.modern.TestPages;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.runs.DungeonCardModel;
import tomato.gui.runs.DungeonsView;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import tomato.gui.runs.RunsDpsPage;
import tomato.gui.runs.RunsFocus;
import tomato.gui.runs.RunsTab;
import tomato.gui.stats.DungeonAnalysis;
import tomato.gui.stats.LootTestDrops;
import tomato.gui.stats.StatisticsGUI;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatFixtures;
import tomato.history.encounter.CombatRetention;
import tomato.history.encounter.CombatSettings;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.item;

/**
 * P5b Runs &amp; DPS evidence (spec §4.1, §6.3, §8.4, S6, S7) in the real workspace ({@code TomatoGUI.createWorkspace} in preview
 * mode; the app's history store is pointed at the test's own history folder and restored after), synthetic names only:
 * - the sidebar: six core rows (Home, Characters, Runs &amp; DPS, Loot, Quests, Chat) and Advanced (5), Statistics out of it and no
 *   DPS Logger row (P6a removed that pointer page; Alt+8 opens the Live meter); the Statistics banner, with its way to Runs &amp; DPS;
 * - Runs &amp; DPS › Feed with the tab strip (Feed · Dungeons · Live meter · Recordings);
 * - the Live meter over a live synthetic fight (six players, the capture's own character Bravo, twelve minions and the boss
 *   "Synthetic Colossus"): Simple 1240×800 font 13 and Analyst 680×520 font 18, each with the details drawer closed and open;
 *   the true rank, your row, the boss card with its chip, "HP —", one filter row, a usable meter with its nested tabs;
 * - Recordings over this app run's memory (a captured recording and an imported copy of it), saved summaries (linked and
 *   unlinked), kept full detail and pruned full detail, the live row first and selected; a summary-only recording's summary
 *   panel; an empty Recordings tab with its empty state;
 * - Dungeons: a full card (Lost Halls), a partial-loot card (Ice Citadel: one completed run in a session that saved no loot),
 *   an unverified-DPS card (Snake Pit) and an all-unknown card (Pirate Cave: a run without an entry time), and the Analyst
 *   Analysis view.
 * The JVM's default time zone is set for the test to a fixed offset at which it is mid-afternoon (as {@link RunsEvidenceTest}
 * does), so the saved records fall inside Recordings' default "Last 30 days" and the feed's Today and Yesterday whenever it
 * runs. Preferences (tabs, sidebar layout, filter drawers, views, Combat history, the DPS preset name), the display mode, the
 * archive workspaces' keys, the zone, the format locale and the DPS statics are restored after each test.
 * Every capture asserts that no page scrolls sideways (a data table that scrolls its own columns, and the meter's unwrapped
 * hit report, are listed on standard output instead; Recordings' Simple columns are asserted to fit at 1240 px, and its
 * Analyst table to show three rows at 680 px) and the content it is evidence of. The test has no game assets: the enemies' names and the boss's
 * BOSS label are synthetic asset entries (restored after), player classes read "Unknown" and sprites are placeholders.
 * 16 screenshots in {@code redesign-p5b-runs-dps}.
 */
public class RunsDpsEvidenceTest {
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    /** Synthetic players by object ID 1…6; the capture's own character is Bravo (object ID 2, a Wizard). */
    private static final String[] NAMES = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"};
    private static final int[] CLASSES = {797, 782, 784, 768, 775, 798};
    private static final int LOCAL = 1, BOSS = 6100;
    /** Synthetic object names: three minion types and the boss (labeled BOSS). */
    private static final Map<Integer, String> ASSETS = Map.of(6000, "Synthetic Crawler", 6001, "Synthetic Spitter", 6002, "Synthetic Warden",
        BOSS, "Synthetic Colossus");
    private static final String TODAY = HomeHistoryFixture.id("p5b-evidence-today"), YESTERDAY = HomeHistoryFixture.id("p5b-evidence-yesterday");
    private static final VisitRef A1 = new VisitRef(TODAY, "a1"), A2 = new VisitRef(TODAY, "a2"), A3 = new VisitRef(TODAY, "a3"),
        Y1 = new VisitRef(YESTERDAY, "y1"), Y2 = new VisitRef(YESTERDAY, "y2");
    /** Recordings table columns (DungeonListGUI's model). */
    private static final int DUNGEON = 2, SOURCE = 7, RUN = 9, SAVED = 10;
    private static final Map<String, String> DEFAULTS = defaults();
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p5b-runs-dps");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> saved = new HashMap<>(), archive = new HashMap<>();
    private final List<Set<?>> filterSets = new ArrayList<>();
    private final List<Set<?>> filterCopies = new ArrayList<>();
    private final tomato.gui.roster.RosterStateTestSupport.Memory views = new tomato.gui.roster.RosterStateTestSupport.Memory();
    private DisplayModeModel.Mode savedMode;
    private String savedModeKey;
    private TimeZone savedZone;
    private Locale savedLocale;
    private String temporaryDirectory;
    private SessionStore store;
    private CharacterJournal journal;
    private TomatoData data;
    private TomatoGUI gui;
    private WorkspaceShell shell;

    private static Map<String, String> defaults() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("chat.filters", "{}"); keys.put("chat.showIgnoredPlayers", "false");
        for (String key : new String[] {RunFeedView.VIEW_KEY, DungeonsView.VIEW_KEY, "ui.order.run-recap", NavLayout.ORDER_KEY, NavLayout.HIDDEN_KEY,
                NavLayout.PINNED_KEY, NavLayout.ADVANCED_KEY, "ui.tabs.runs", "ui.tabs.dps", "ui.tabs.saved-resources", "ui.tabs.statistics",
                "ui.filters.run-feed.open", "ui.filters.dps-meter.open", "ui.filters.encounter-library.open", "ui.filters.dungeons.open",
                "ui.filters.dungeon-analysis.open", CombatSettings.KEEP_FULL_DETAIL, CombatSettings.FULL_DETAIL_DAYS, CombatSettings.SUMMARY_RETENTION})
            keys.put(key, "");
        keys.put("filterName", "Default");   // the DPS filter preset: none (DpsGUI.loadFilterPreset reads it when the workspace is built)
        return Collections.unmodifiableMap(keys);
    }

    @Before public void open() throws Exception {
        PropertiesManager.preload(); // merge the disk file first, so the archive keys snapshot below sees every saved key
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            saved.put(entry.getKey(), PropertiesManager.getProperty(entry.getKey()));
            PropertiesManager.setProperties(entry.getKey(), entry.getValue());
        }
        for (String key : archiveKeys()) { archive.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        savedModeKey = PropertiesManager.getProperty(DisplayModeModel.KEY);
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        savedZone = TimeZone.getDefault();
        savedLocale = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone.setDefault(TimeZone.getTimeZone(RunsEvidenceTest.afternoon(System.currentTimeMillis())));
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        remember(Tomato.class, "preview", true);
        remember(TomatoGUI.class, "characterViewStates", views.store);
        // The test has no game assets: synthetic enemy names and the boss label (the live meter's snapshot copies read names and
        // the Boss label from the asset catalog), restored after the test. Player classes stay "Unknown" (another catalog).
        Field objects = IdToAsset.class.getDeclaredField("objectID"); objects.setAccessible(true);
        @SuppressWarnings("unchecked") HashMap<Integer, IdToAsset> assets = new HashMap<>((Map<Integer, IdToAsset>) objects.get(null));
        for (Map.Entry<Integer, String> type : ASSETS.entrySet())
            assets.put(type.getKey(), new IdToAsset("", type.getKey(), type.getValue(), type.getValue(), "", null, "", type.getKey() == BOSS ? "BOSS" : "", ""));
        remember(IdToAsset.class, "objectID", assets);
        for (Class<?> type : new Class<?>[] {TomatoGUI.class, ChatGUI.class, CharacterPetsGUI.class, DpsGUI.class, Filter.class, DpsDisplayOptions.class})
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && !statics.containsKey(field)) {
                    field.setAccessible(true); statics.put(field, field.get(null));
                }
        for (Set<?> set : Arrays.asList(Filter.filterNames, Filter.filterGuilds, Filter.filterClasses)) { filterSets.add(set); filterCopies.add(new HashSet<>(set)); }
    }

    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            evidence.closeWindow();
            if (gui != null) gui.closeWorkspace();
            DisplayModeModel.application().set(savedMode);
            try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            for (int i = 0; i < filterSets.size(); i++) restore(filterSets.get(i), filterCopies.get(i));
        });
        if (journal != null) journal.close();
        for (Map.Entry<String, String> entry : saved.entrySet())
            PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? DEFAULTS.get(entry.getKey()) : entry.getValue());
        for (String key : archiveKeys()) { String value = archive.get(key); PropertiesManager.setProperties(key, value == null ? "" : value); }
        if (savedModeKey != null) PropertiesManager.setProperties(DisplayModeModel.KEY, savedModeKey);
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (savedZone != null) TimeZone.setDefault(savedZone);
        if (savedLocale != null) Locale.setDefault(Locale.Category.FORMAT, savedLocale);
        if (store != null) store.close();
    }

    @SuppressWarnings("unchecked") private static <T> void restore(Set<T> set, Set<?> copy) { set.clear(); set.addAll((Set<T>) copy); }

    /**
     * 3 captures (S7): the sidebar's six core rows with Advanced (5) collapsed and expanded, Statistics out of it and no DPS Logger
     * row; the Statistics page's banner, whose Open Dungeons opens Runs &amp; DPS › Dungeons. Alt+8 (no page since P6a removed the
     * DPS Logger pointer) opens Runs &amp; DPS › Live meter with a Back entry.
     */
    @Test public void theSidebarListsSixCoreRowsAndTheMovedPagesPointToRunsAndDps() throws Exception {
        build(temp.newFolder("history").toPath(), false);
        show("Sidebar", 1240, 800, 13, SIMPLE, () -> { });
        capture("sidebar", 1240, 13, SIMPLE, () -> {
            assertEquals("The app opens on Home", "home", shell.selectedPage());
            assertEquals("Six core rows: Home, Characters, Runs & DPS, Loot, Quests, Chat", List.of("home", "characters", "runs", "loot", "quests", "chat"), listed());
            assertEquals("Runs & DPS", navRow("runs").getText());
            AbstractButton advanced = VisualEvidence.named(shell, "nav-advanced", AbstractButton.class);
            assertEquals("Advanced (5)", advanced.getText());
            assertEquals("Collapsed", advanced.getAccessibleContext().getAccessibleDescription());
            assertFalse(TestPages.title("statistics") + " is out of the sidebar", navRow("statistics").isVisible());
            assertNull("The DPS Logger pointer page has no row", search(shell, AbstractButton.class, b -> "nav-dps-logger".equals(b.getName())));
            assertTrue("Settings stays below the list", navRow("settings").isShowing());
        });
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "nav-advanced", AbstractButton.class).doClick());
        pause();
        capture("sidebar-advanced", 1240, 13, SIMPLE, () -> {
            assertEquals("Advanced (5): Party, Key-pops, Timeline, Logging, Bridge Review", List.of("home", "characters", "runs", "loot", "quests", "chat", "party", "key-pops", "timeline", "logging", "bridge-review"), listed());
            assertEquals("Expanded", VisualEvidence.named(shell, "nav-advanced", AbstractButton.class).getAccessibleContext().getAccessibleDescription());
            assertFalse(TestPages.title("statistics") + " is not an Advanced row either", navRow("statistics").isVisible());
        });

        SwingUtilities.invokeAndWait(() -> {
            String before = shell.selectedPage();
            Object altEight = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_8, java.awt.event.InputEvent.ALT_DOWN_MASK));
            assertEquals("Alt+8 is bound to the Live meter route", "open-live-meter", altEight);
            shell.getActionMap().get(altEight).actionPerformed(null);
            assertEquals("Alt+8 opens Runs & DPS", "runs", shell.selectedPage());
            assertEquals("…on the Live meter tab", RunsTab.LIVE_METER, page().selectedTab());
            assertTrue("Back returns to the page Alt+8 left", Navigator.current().back());
            assertEquals(before, shell.selectedPage());
        });

        show("Statistics banner", 1240, 800, 13, SIMPLE, () -> shell.select("statistics"));
        capture("statistics-banner", 1240, 13, SIMPLE, () -> {
            Banner banner = VisualEvidence.named(shell, "statistics-dungeons-banner", Banner.class);
            assertTrue("The Statistics page shows its banner", banner.isShowing());
            assertEquals(StatisticsGUI.DUNGEONS_MOVED, banner.text());
            AbstractButton open = VisualEvidence.named(shell, "statistics-open-dungeons", AbstractButton.class);
            assertTrue("…with Open Dungeons", open.isShowing() && "Open Dungeons".equals(open.getText()));
            assertTrue("The Statistics tabs stay below it", VisualEvidence.named(shell, "statistics-tabs", JTabbedPane.class).isShowing());
            assertFalse("Statistics stays out of the sidebar while it shows", navRow("statistics").isVisible());
        });
        SwingUtilities.invokeAndWait(() -> {
            VisualEvidence.named(shell, "statistics-open-dungeons", AbstractButton.class).doClick();
            assertEquals("runs", shell.selectedPage());
            assertEquals("Open Dungeons opens the Dungeons tab", RunsTab.DUNGEONS, page().selectedTab());
        });
    }

    /**
     * 5 captures: Runs &amp; DPS › Feed with the tab strip, and the Live meter over a live fight in Simple 1240×800 font 13 and
     * Analyst 680×520 font 18, each with the details drawer closed and open.
     */
    @Test public void runsAndDpsOpensOnTheFeedAndTheLiveMeterShowsRankYourRowAndBossCards() throws Exception {
        Path history = temp.newFolder("history").toPath();
        write(history);
        build(history, true);
        LocalDate today = LocalDate.now();
        show("Runs feed", 1240, 800, 13, SIMPLE, () -> assertTrue(Navigator.current().open(Route.to(Destination.RUNS))));
        await("the feed", () -> shown(shell, "run-feed-summary") != null && text("run-feed-summary").startsWith("Saved runs · newest first · ")
            && shown(shell, "run-feed-day-" + today) != null);
        pause();
        capture("feed", 1240, 13, SIMPLE, () -> {
            assertEquals("runs", shell.selectedPage());
            assertEquals("Runs & DPS opens on its first tab", RunsTab.FEED, page().selectedTab());
            JTabbedPane tabs = VisualEvidence.named(shell, "runs-tabs", JTabbedPane.class);
            assertEquals("The tab strip", List.of("Feed", "Dungeons", "Live meter", "Recordings"), titles(tabs));
            assertTrue("The capture shows the strip", inView(tabs));
            assertEquals("Today", VisualEvidence.named(shell, "run-feed-header-" + today, tomato.gui.kit.SectionHeader.class).title());
            String counts = text("run-feed-counts-" + today);
            assertTrue(counts, counts.startsWith("4 runs · 3 completed · "));
            List<String> cards = names(day(today));
            assertEquals(cards.toString(), 4, cards.size());
            assertTrue(cards.get(0), cards.get(0).startsWith("Lost Halls, Completed; entered today at 13:20; 26 m observed; party of 6; your DPS "));
            assertTrue(cards.get(3), cards.get(3).startsWith("Lost Halls, Left; entered today at 11:45; 7 m observed; party of 5; "));
            assertTrue("The capture shows Today's cards", inView(day(today)));
        });

        // The Live meter: Home's Now card and Alt+8 route here (a plain ENCOUNTER route).
        show("Live meter", 1240, 800, 13, SIMPLE, () -> assertTrue(Navigator.current().open(Route.to(Destination.ENCOUNTER))));
        await("the live meter", () -> meterSummary().contains("LIVE") && meterSummary().contains("13 enemies"));
        pause();
        capture("live-meter", 1240, 13, SIMPLE, () -> {
            liveMeter(true);
            assertFalse("The details drawer is closed without a selection", VisualEvidence.named(shell, "dps-details-drawer", JComponent.class).isVisible());
            JLabel reason = VisualEvidence.named(shell, "dps-explore-reason", JLabel.class);
            assertTrue("Explore's reason shows under the table: " + reason.getText(), reason.isShowing() && reason.getText().contains("Select a player"));
            FilterBar bar = VisualEvidence.named(shell, "dps-meter-filter-bar", FilterBar.class);
            assertFalse(bar.drawerOpen());
            assertOneFilterRow("dps-meter", bar);
            assertEquals("No preset or filter is active", 0, bar.activeCount());
        });
        SwingUtilities.invokeAndWait(() -> { JTable table = meterTable(); int bravo = row(table, "Bravo"); table.setRowSelectionInterval(bravo, bravo); });
        pause();
        capture("live-meter-drawer", 1240, 13, SIMPLE, () -> {
            JComponent drawer = VisualEvidence.named(shell, "dps-details-drawer", JComponent.class);
            assertTrue("A selected player opens the drawer", drawer.isShowing());
            String title = VisualEvidence.named(shell, "dps-details-title", JLabel.class).getText();
            assertTrue(title, title.startsWith("Details · Bravo · "));
            assertTrue("The drawer shows the hit details", inView(VisualEvidence.named(shell, "dps-hit-details-scroll", JScrollPane.class)));
            assertMeterUsable("live-meter-drawer-1240");
        });
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "dps-details-close", AbstractButton.class).doClick());

        // Compact: the filter row wraps above the meter, so the page scrolls; the capture starts at the meter's summary line.
        show("Live meter compact", 680, 520, 18, ANALYST, () -> { });
        SwingUtilities.invokeAndWait(() -> scrollToTop(VisualEvidence.named(shell, "dps-damage-scroll", JScrollPane.class), field(meter(), "summary", JLabel.class)));
        pause();
        capture("live-meter", 680, 18, ANALYST, () -> {
            liveMeter(false);
            JTabbedPane nested = VisualEvidence.named(shell, "dps-tabs", JTabbedPane.class);
            assertTrue("The meter keeps its nested tabs", nested.isShowing() && inView(nested));
            assertEquals(List.of("Damage meters", "Resources & buffs"), titles(nested));
            assertFalse("Analyst's View choice is in the closed drawer", VisualEvidence.named(shell, "dps-view-mode-field", JComponent.class).isShowing());
            assertMeterUsable("live-meter-680");
        });
        SwingUtilities.invokeAndWait(() -> {
            JTable table = meterTable(); int bravo = row(table, "Bravo"); table.setRowSelectionInterval(bravo, bravo);
        });
        pause();
        SwingUtilities.invokeAndWait(() -> WaveThreeEvidence.reveal(VisualEvidence.named(shell, "dps-details-drawer", JComponent.class), 200));
        pause();
        capture("live-meter-drawer", 680, 18, ANALYST, () -> {
            assertTrue("The drawer opens at the compact size too", VisualEvidence.named(shell, "dps-details-drawer", JComponent.class).isShowing());
            assertTrue(inView(VisualEvidence.named(shell, "dps-details-title", JLabel.class)));
            assertMeterUsable("live-meter-drawer-680");
        });
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "dps-details-close", AbstractButton.class).doClick());
    }

    /**
     * 3 captures: Recordings over this app run's memory, saved history and an import (Simple 1240×800 font 13 and Analyst
     * 680×520 font 18), and a summary-only recording's summary panel.
     */
    @Test public void recordingsListEveryKindAndASummaryOnlyRecordingOpensItsSummary() throws Exception {
        Path history = temp.newFolder("history").toPath();
        write(history);
        build(history, true);
        show("Recordings", 1240, 800, 13, SIMPLE, () -> assertTrue(Navigator.current().open(Route.to(Destination.RUNS).withPayload(RunsFocus.of(RunsTab.RECORDINGS)))));
        await("the recordings", () -> recordingsSummary().startsWith("8 of 8 recordings shown · last 30 days"));
        pause();
        Runnable rows = () -> {
            assertEquals(RunsTab.RECORDINGS, page().selectedTab());
            JTable table = recordingsTable();
            assertEquals("Live, this app run's capture, its imported copy and six saved recordings", 9, table.getRowCount());
            assertEquals("The live row is the first row under the default Recorded start order", 0, liveRow(table));
            assertEquals("…and selected: the primary action opens the live meter", 0, table.getSelectedRow());
            assertEquals("Open live meter", VisualEvidence.named(shell, "encounter-open", AbstractButton.class).getText());
            int captured = rowOf(table, "Mad Lab", "Captured", null);
            assertEquals("Preview saves nothing: this app run's recording has no saved summary", "No saved summary (yet)", value(table, captured, SAVED));
            assertEquals("Unlinked", value(table, captured, RUN));
            assertEquals("An imported copy is its own row and names the row with the same recording", "Imported file · same recording as Captured · Mad Lab",
                value(table, rowOf(table, "Mad Lab", "synthetic-copy.dps", null), SAVED));
            int halls = 0;
            for (int row = 0; row < table.getRowCount(); row++) if ("Lost Halls".equals(value(table, row, DUNGEON))) {
                halls++;
                assertEquals("A saved Lost Halls run keeps its summary", "Summary saved", value(table, row, SAVED));
                assertTrue("…linked to its run: " + value(table, row, RUN), value(table, row, RUN).startsWith("Linked · Lost Halls · "));
            }
            assertEquals("Both saved Lost Halls recordings", 2, halls);
            assertEquals("Unlinked", value(table, rowOf(table, "Sprite World", "Saved history", "Summary saved"), RUN));
            assertTrue("Kept full detail with its size", value(table, rowOf(table, "Ice Citadel", "Saved history", "Full detail · "), SAVED).matches("Full detail · [0-9.,]+ (KB|MB)"));
            assertTrue(rowOf(table, "Ice Citadel", "Saved history", "Summary saved") >= 0);
            assertEquals("Full detail pruned (kept 30 days)", value(table, rowOf(table, "Snake Pit", "Saved history", "Full detail pruned"), SAVED));
            assertNoPath();
            assertTrue("The capture shows the table", inView(table));
        };
        capture("recordings", 1240, 13, SIMPLE, () -> {
            rows.run();
            FilterBar bar = VisualEvidence.named(shell, "encounter-library-filter-bar", FilterBar.class);
            assertOneFilterRow("encounter-library", bar);
            assertEquals("Last 30 days", selectedSegment("encounter-scope"));
            // Finding 2, fixed: Simple's columns (Export, Dungeon, Recorded start, Run, Saved, Damage) fit the table at desktop width,
            // so Run and Saved are in view and nothing scrolls sideways.
            JTable table = recordingsTable();
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
            assertEquals(List.of("Export", "Dungeon", "Recorded start", "Run", "Saved", "Damage"), headers(table));
            assertTrue("The table (" + table.getWidth() + " px) fits its viewport (" + scroll.getViewport().getWidth() + " px)",
                table.getWidth() <= scroll.getViewport().getWidth() && !scroll.getHorizontalScrollBar().isShowing());
            for (int column : new int[] {RUN, SAVED}) {
                Rectangle cell = table.getCellRect(0, table.convertColumnIndexToView(column), true);
                assertTrue(table.getModel().getColumnName(column) + " is in view: " + cell, table.getVisibleRect().contains(cell.x, cell.y, cell.width, 1));
            }
            // Their cells are whole (Saved's longest label, the imported copy's "same recording as …", is whole in its tooltip).
            for (int column : new int[] {DUNGEON, 3, RUN, 6}) for (int row = 0; row < table.getRowCount(); row++) {
                int view = table.convertColumnIndexToView(column);
                JComponent cell = (JComponent) table.prepareRenderer(table.getCellRenderer(row, view), row, view);
                int needed = cell.getPreferredSize().width, width = table.getColumnModel().getColumn(view).getWidth();
                assertTrue(table.getColumnName(view) + " of row " + row + " is whole: " + needed + " of " + width + " px", needed <= width);
            }
            int imported = rowOf(table, "Mad Lab", "synthetic-copy.dps", null), savedView = table.convertColumnIndexToView(SAVED);
            String tip = ((JComponent) table.prepareRenderer(table.getCellRenderer(imported, savedView), imported, savedView)).getToolTipText();
            assertTrue(tip, tip.startsWith("Imported file · same recording as Captured · Mad Lab. "));
        });
        show("Recordings compact", 680, 520, 18, ANALYST, () -> { });
        capture("recordings", 680, 18, ANALYST, () -> {
            rows.run();
            JTable table = recordingsTable();
            assertEquals("Analyst shows every column (the table scrolls them sideways)", 11, table.getColumnCount());
            int rowsInView = table.getVisibleRect().height / table.getRowHeight();
            assertTrue("Finding 6, fixed: at least three rows show before the page scrolls: " + rowsInView, rowsInView >= 3);
        });

        show("Recording summary", 1240, 800, 13, SIMPLE, () -> {
            JTable table = recordingsTable();
            int unlinked = rowOf(table, "Sprite World", "Saved history", "Summary saved");
            table.setRowSelectionInterval(unlinked, unlinked);
            AbstractButton open = VisualEvidence.named(shell, "encounter-open", AbstractButton.class);
            assertEquals("A summary-only recording without a run link opens its summary here", "Show summary", open.getText());
            open.doClick();
        });
        RecordingSummaryPanel summary = edt(() -> VisualEvidence.named(shell, "recording-summary", RecordingSummaryPanel.class));
        await("the saved summary", () -> summary.isShowing() && search(summary, JTable.class, t -> "run-recap-meter".equals(t.getName())) != null
            && search(summary, JTable.class, t -> "run-recap-meter".equals(t.getName())).getRowCount() == 4);
        pause();
        SwingUtilities.invokeAndWait(() -> WaveThreeEvidence.reveal(summary, 520));
        pause();
        capture("recording-summary", 1240, 13, SIMPLE, () -> {
            assertTrue("The summary panel shows under the table", summary.isShowing() && inView(summary));
            assertEquals(RecordingSummaryPanel.NOT_KEPT, VisualEvidence.named(summary, "recording-summary-caption", JLabel.class).getText());
            String title = VisualEvidence.named(summary, "recording-summary-title", JLabel.class).getText();
            assertTrue(title, title.startsWith("Summary · Sprite World · "));
            JTable meter = VisualEvidence.named(summary, "run-recap-meter", JTable.class);
            assertEquals("Every contributor of the saved summary", 4, meter.getRowCount());
            assertTrue("The capture shows the meter", inView(meter));
            assertEquals("Opening a summary switched nothing", RunsTab.RECORDINGS, page().selectedTab());
        });
    }

    /**
     * 1 capture: Recordings with nothing recorded, saved or imported: an empty state says what fills the tab (finding 5, fixed),
     * and the live row stays selected, so Open live meter is one click away.
     */
    @Test public void anEmptyRecordingsTabSaysWhatFillsItAndStillOpensTheLiveMeter() throws Exception {
        build(temp.newFolder("history").toPath(), false);
        show("Recordings empty", 1240, 800, 13, SIMPLE, () -> assertTrue(Navigator.current().open(Route.to(Destination.RUNS).withPayload(RunsFocus.of(RunsTab.RECORDINGS)))));
        await("the recordings", () -> recordingsSummary().startsWith("0 of 0 recordings shown · last 30 days"));
        pause();
        capture("recordings-empty", 1240, 13, SIMPLE, () -> {
            JTable table = recordingsTable();
            assertEquals("Only the live row", 1, table.getRowCount());
            assertEquals(0, liveRow(table));
            assertEquals("…selected", 0, table.getSelectedRow());
            assertTrue(recordingsSummary(), recordingsSummary().startsWith("0 of 0 recordings shown · last 30 days · 0 checked for export"));
            assertEquals("Last 30 days", selectedSegment("encounter-scope"));
            EmptyState empty = VisualEvidence.named(shell, "encounter-empty", EmptyState.class);
            assertTrue("The empty state shows", empty.isShowing() && inView(empty));
            assertEquals("No recordings yet", empty.getAccessibleContext().getAccessibleName());
            assertTrue(empty.getAccessibleContext().getAccessibleDescription(), empty.getAccessibleContext().getAccessibleDescription().contains("when a fight closes"));
            AbstractButton open = VisualEvidence.named(shell, "encounter-open", AbstractButton.class);
            assertEquals("Open live meter", open.getText());
            assertTrue("Open live meter is in view and enabled", open.isEnabled() && inView(open));
        });
    }

    /**
     * 3 captures: the Dungeons cards (a full card, a partial-loot card, a card without a verified row and an all-unknown card) in
     * Simple 1240×800 font 13 and Analyst 680×520 font 18, and the Analyst Analysis view.
     */
    @Test public void dungeonsShowsOneCardPerDungeonAndTheAnalystAnalysis() throws Exception {
        Path history = temp.newFolder("history").toPath();
        write(history);
        build(history, true);
        show("Dungeons", 1240, 800, 13, SIMPLE, () -> assertTrue(Navigator.current().open(Route.to(Destination.RUNS).withPayload(RunsFocus.of(RunsTab.DUNGEONS)))));
        await("the dungeon cards", () -> cards().getModel().getSize() == 4);
        pause();
        Runnable facts = () -> {
            assertEquals(RunsTab.DUNGEONS, page().selectedTab());
            List<DungeonCardModel> cards = cards().items();
            assertEquals("Most visits first, then by name", List.of("Lost Halls", "Ice Citadel", "Pirate Cave", "Snake Pit"),
                cards.stream().map(DungeonCardModel::canonical).collect(java.util.stream.Collectors.toList()));
            DungeonCardModel halls = cards.get(0), ice = cards.get(1), pirate = cards.get(2), snake = cards.get(3);
            assertEquals("Full: 2 completed of 3 finished runs", 2.0 / 3, halls.completionRate(), 1e-9);
            assertEquals("Observed average of the two completed runs (26 and 22 min)", Long.valueOf(24 * 60_000L), halls.averageDurationMs());
            assertEquals("Loot per completed run: 6 items of a1 and 2 of a2", 4.0, halls.lootPerCompletedRun(), 1e-9);
            assertEquals(0, halls.lootExcluded());
            assertNotNull("Best DPS from a completed run's verified row", halls.bestLocalDps());
            assertTrue("…of a completed run: " + halls.bestRun(), A1.equals(halls.bestRun()) || A2.equals(halls.bestRun()));
            assertEquals("Partial: yesterday's session saved no loot", 1, ice.lootExcluded());
            assertEquals(3.0, ice.lootPerCompletedRun(), 1e-9);
            assertTrue(ice.lootPartial());
            assertNull("All unknown: no finished run", pirate.completionRate());
            assertEquals(1, pirate.unknown());
            assertNull(pirate.averageDurationMs()); assertNull(pirate.lootPerCompletedRun()); assertNull(pirate.bestLocalDps());
            assertEquals(1.0, snake.completionRate(), 1e-9);
            assertEquals(DungeonCardModel.LOOT_NOT_SAVED, snake.lootReason());
            assertEquals(DungeonCardModel.UNVERIFIED_LOCAL, snake.dpsReason());
            List<String> names = names(cards());
            assertTrue(names.get(0), names.get(0).contains("completion 67% observed") && names.get(0).contains("bags linked to the exact run"));
            assertTrue(names.get(1), names.get(1).contains("partial, 1 of 2 completed runs left out"));
            assertTrue(names.get(2), names.get(2).contains("completion unknown") && names.get(2).contains("best DPS unknown"));
            assertTrue("The capture shows the cards", inView(cards()));
        };
        capture("dungeons", 1240, 13, SIMPLE, () -> {
            facts.run();
            assertFalse("Simple: no Cards · Analysis switch", VisualEvidence.named(shell, "dungeons-view-mode-row", JComponent.class).isShowing());
            assertOneFilterRow("dungeons", VisualEvidence.named(shell, "dungeons-filter-bar", FilterBar.class));
        });
        show("Dungeons compact", 680, 520, 18, ANALYST, () -> { });
        capture("dungeons", 680, 18, ANALYST, () -> {
            facts.run();
            assertTrue("Analyst: the Cards · Analysis switch", VisualEvidence.named(shell, "dungeons-view-mode-row", JComponent.class).isShowing());
        });

        show("Dungeons analysis", 1240, 800, 13, ANALYST, () -> VisualEvidence.named(shell, "dungeons-view", DungeonsView.class).analyze("Lost Halls"));
        await("the analysis", () -> analysis() != null && !analysis().loading() && analysis().displayedPage() != null);
        pause();
        capture("dungeons-analysis", 1240, 13, ANALYST, () -> {
            ArchiveWorkspace<?, ?, ?> workspace = analysis();
            assertTrue("A saved-only workspace", workspace.savedOnly() && workspace.isShowing());
            assertEquals(DungeonAnalysis.NAME + "-session-view", workspace.getName());
            assertTrue("Analyze set the dungeon facet", workspace.filterBar().activeCount() > 0);
            Banner banner = VisualEvidence.named(shell, "dungeons-statistics-banner", Banner.class);
            assertEquals("The Fame Table and the live loot log stay on the Statistics page.", banner.text());
            assertTrue(VisualEvidence.named(shell, "dungeons-open-statistics", AbstractButton.class).isShowing());
            assertEquals("Analysis", selectedSegment("dungeons-view-mode"));
        });
    }

    // ---- synthetic saved history ----

    /** Local wall-clock time on today + {@code dayOffset} in the test's zone. */
    private static long at(int dayOffset, int hour, int minute) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).plusDays(dayOffset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli();
    }

    /**
     * Two saved sessions (synthetic names only):
     * - Today (ended 14:20, loot saved): a4 Lost Halls left (party 5, no recording); a3 Ice Citadel completed (party 4, a verified
     *   recording, 3 items in two bags); a2 Lost Halls completed (22 m, party 4, a verified recording, 2 items); a1 Lost Halls
     *   completed (26 m, party 6, a verified recording, 6 items in three bags); and a saved summary without a run link (Sprite
     *   World, unlinked).
     * - Yesterday (no loot saved at all): y1 Ice Citadel completed (a verified recording with its full detail kept), y2 Snake Pit
     *   completed (a recording without a verified local row, its full detail pruned).
     * The current session (this app run's) gets c1, a Pirate Cave run without an entry time (Unknown), in {@link #build}.
     */
    private static void write(Path root) throws Exception {
        RunFixtures.session(root, TODAY, at(0, 11, 40), at(0, 14, 20), new SessionStore.Interval(at(0, 11, 40), at(0, 14, 20), "Capture stopped"));
        ActivityJournal.Visit a4 = left(HomeHistoryFixture.visit("a4", "Lost Halls", at(0, 11, 45), at(0, 11, 52), false));
        a4.rosterSize = 5;
        ActivityJournal.Visit a3 = left(HomeHistoryFixture.visit("a3", "Ice Citadel", at(0, 12, 0), at(0, 12, 28), true));
        a3.rosterSize = 4;
        ActivityJournal.Visit a2 = left(HomeHistoryFixture.visit("a2", "Lost Halls", at(0, 12, 40), at(0, 13, 2), true));
        a2.rosterSize = 4;
        ActivityJournal.Visit a1 = left(HomeHistoryFixture.visit("a1", "Lost Halls", at(0, 13, 20), at(0, 13, 46), true));
        a1.rosterSize = 6;
        HomeHistoryFixture.runs(root, TODAY, a4, a3, a2, a1);
        HomeHistoryFixture.loot(root, TODAY,
            LootTestDrops.drop("White", "Ice Citadel", at(0, 12, 20), A3, item(9301, UT), item(9302, POTION)),
            LootTestDrops.drop("Cyan", "Ice Citadel", at(0, 12, 26), A3, item(9303, TIERED)),
            LootTestDrops.drop("Blue", "Lost Halls", at(0, 12, 55), A2, item(9201, POTION), item(9202, POTION)),
            LootTestDrops.drop("White", "Lost Halls", at(0, 13, 30), A1, item(9101, UT), item(9102, POTION)),
            LootTestDrops.drop("Orange", "Lost Halls", at(0, 13, 38), A1, item(9103, ST), item(9104, TIERED)),
            LootTestDrops.drop("Purple", "Lost Halls", at(0, 13, 44), A1, item(9105, TIERED), item(9106, PLAIN)));
        save(root, TODAY, plain("Ice Citadel", A3, at(0, 12, 0), 150, 4, true), false, false);
        save(root, TODAY, plain("Lost Halls", A2, at(0, 12, 40), 180, 4, true), false, false);
        save(root, TODAY, plain("Lost Halls", A1, at(0, 13, 20), 240, 6, true), false, false);
        save(root, TODAY, plain("Sprite World", null, at(0, 14, 0), 90, 4, true), false, false);

        RunFixtures.session(root, YESTERDAY, at(-1, 19, 30), at(-1, 23, 10), new SessionStore.Interval(at(-1, 19, 30), at(-1, 23, 10), "Capture stopped"));
        ActivityJournal.Visit y1 = left(HomeHistoryFixture.visit("y1", "Ice Citadel", at(-1, 20, 0), at(-1, 20, 30), true));
        y1.rosterSize = 5;
        ActivityJournal.Visit y2 = left(HomeHistoryFixture.visit("y2", "Snake Pit", at(-1, 21, 0), at(-1, 21, 14), true));
        y2.rosterSize = 3;
        HomeHistoryFixture.runs(root, YESTERDAY, y1, y2);
        save(root, YESTERDAY, plain("Ice Citadel", Y1, at(-1, 20, 0), 200, 5, true), true, true);
        save(root, YESTERDAY, plain("Snake Pit", Y2, at(-1, 21, 0), 80, 3, false), true, false);
    }

    /** As the journal closes a visit on leaving its area: the end reason, and a status that says whether a completion was seen. */
    private static ActivityJournal.Visit left(ActivityJournal.Visit visit) {
        visit.endReason = "Area left; completion unknown";
        visit.status = visit.completionEvidence.isEmpty() ? visit.endReason : "Completed";
        return visit;
    }

    /**
     * Saves {@code recording}'s summary (record and detail) where the combat autosave would, marked as keeping full detail or
     * not, and writes its full-detail file only when {@code fileThere} (kept without the file = pruned).
     */
    private static void save(Path root, String session, DpsData recording, boolean keptFull, boolean fileThere) throws IOException {
        CombatSummaries.Result summary = CombatSummaries.build(recording);
        summary.record().fullDetail = keptFull;
        CombatFixtures.writeRecord(root, session, summary.record());
        CombatFixtures.writeDetail(root, session, summary.detail());
        if (fileThere) writeDps(CombatAutosave.fullDetailFile(root.resolve(session), recording.getRecordingId()), recording.getSaveFile(false));
    }

    private static Path writeDps(Path file, DpsData recording) throws IOException {
        Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(recording); }
        return file;
    }

    /**
     * A recording of plain RealmShark objects (so its .dps copy passes the safe reader): {@code players} players Alpha… (Bravo is
     * the capture's own character, the verified local row unless {@code verified} is false) hitting six enemies of three types
     * once a second each for {@code seconds} s, weapon and ability hits; the first tick 5 s after {@code entered}.
     */
    private static DpsData plain(String map, VisitRef visit, long entered, int seconds, int players, boolean verified) {
        MapInfoPacket info = new MapInfoPacket(); info.name = info.displayName = map;
        long start = entered + 5_000;
        List<Entity> party = new ArrayList<>();
        for (int p = 0; p < players; p++) {
            Entity player = new Entity(null, p + 1, start); player.objectType = CLASSES[p]; player.markPlayerIdentity();
            StatData name = new StatData(); name.stringStatValue = NAMES[p]; player.stat.set(StatType.NAME_STAT, name);
            if (p == LOCAL) player.setUser(7);
            party.add(player);
        }
        HashMap<Integer, Entity> hits = new HashMap<>();
        List<Entity> enemies = new ArrayList<>();
        for (int e = 0; e < 6; e++) {
            Entity enemy = new Entity(null, 1000 + e, start); enemy.objectType = 6000 + e % 3; named(enemy);
            StatData hp = new StatData(); hp.statValue = 2_000 * (e % 3 + 1); enemy.stat.set(StatType.MAX_HP_STAT, hp);
            hits.put(enemy.id, enemy); enemies.add(enemy);
        }
        for (int s = 0; s < seconds; s++) for (int p = 0; p < party.size(); p++) {
            Entity enemy = enemies.get((s + p) % enemies.size());
            long time = start + s * 1000L + p * 40L;
            Projectile shot = new Projectile(180 + 45 * (players - p) + s % 7 * 10);
            shot.setSource(s % 3 == 0 ? DamageSource.ABILITY : DamageSource.WEAPON, 2101 + p);
            enemy.genericDamageHit(party.get(p), shot, time);
            enemy.updateDamageTaken(time);
        }
        return new DpsData(info, hits, new ArrayList<>(), seconds * 1000L, start, null, party.get(LOCAL),
            new EncounterContext(visit, verified ? LOCAL + 1 : null, entered));
    }

    /**
     * Makes the live fight the encounter in progress in {@code data} ({@link CombatFixtures#installLive}), with Bravo as the
     * resolved local character: Alpha…Foxtrot hit twelve minions of three types (Spitters without max HP), then the boss
     * "Synthetic Colossus" (400,000 HP, labeled BOSS) for the last 40 %, for {@code seconds} s from {@code start}; each player
     * takes a hit every 4 s. Enemies are named as capture names them (their asset names), since the snapshot copies them.
     */
    private static void installFight(TomatoData data, long start, int seconds) {
        CombatFixtures.Fight fight = CombatFixtures.fight("Lost Halls");
        double[] strength = {1.25, 1.0, 0.35, 0.8, 0.7, 0.55};
        List<Entity> party = new ArrayList<>();
        for (int p = 0; p < NAMES.length; p++) party.add(p == LOCAL ? fight.user(p + 1, CLASSES[p], NAMES[p]) : fight.player(p + 1, CLASSES[p], NAMES[p]));
        List<Entity> minions = new ArrayList<>();
        for (int e = 0; e < 12; e++) minions.add(named(fight.enemy(1000 + e, 6000 + e % 3, ASSETS.get(6000 + e % 3), e % 3 == 1 ? null : 2_000 * (e % 3 + 1), false)));
        Entity boss = named(fight.enemy(2000, BOSS, ASSETS.get(BOSS), 400_000, true));
        int bossFrom = seconds * 3 / 5;
        for (int s = 0; s < seconds; s++) for (int p = 0; p < party.size(); p++) {
            double wave = 0.65 + 0.35 * Math.sin(2 * Math.PI * s / 45.0 + p);
            int damage = (int) Math.max(1, Math.round(320 * strength[p] * wave * (s >= bossFrom ? 1.3 : 1)));
            fight.hit(s >= bossFrom ? boss : minions.get((s + p) % minions.size()), party.get(p), damage, start + s * 1000L + p * 40L,
                s % 3 == 0 ? DamageSource.ABILITY : DamageSource.WEAPON, 2101 + p);
        }
        for (int s = 0; s < seconds; s += 4) for (Entity player : party) fight.taken(player, boss, 30 + s % 3 * 10, start + s * 1000L + 300);
        CombatFixtures.installLive(data, fight.ticks(start, seconds * 1000L).build());
        data.player = party.get(LOCAL);
    }

    /** As capture names an object when it first sees it ({@code Entity.entityUpdate}): its asset name. */
    private static Entity named(Entity entity) {
        try { Field field = Entity.class.getDeclaredField("name"); field.setAccessible(true); field.set(entity, IdToAsset.objectName(entity.objectType)); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        return entity;
    }

    // ---- the workspace ----

    /**
     * The real workspace over {@code history}. Populated: this app run's current session gets a Pirate Cave run without an entry
     * time; the capture holds a live fight (published off the EDT, as capture does) and one closed recording of this app run
     * (Mad Lab, unlinked), and an imported copy of that recording ({@code synthetic-copy.dps}) is added to Recordings.
     */
    private void build(Path history, boolean populated) throws Exception {
        store = new SessionStore(history, true, "p5b-runs-dps");
        remember(AppHistory.class, "store", store);
        DpsData captured = null;
        if (populated) {
            ActivityJournal.Visit unknown = HomeHistoryFixture.visit("c1", "Pirate Cave", 0, 0, false);
            store.put("runs", unknown.id, unknown);
            store.flush();
            captured = plain("Mad Lab", null, at(0, 14, 50), 60, 3, true);
        }
        journal = new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"));
        TomatoData made = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
        if (populated) {
            made.dpsData.add(captured);
            installFight(made, System.currentTimeMillis() - 185_000L, 180);
        }
        data = made;
        SwingUtilities.invokeAndWait(() -> {
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
        });
        if (!populated) return;
        DpsGUI.updateMapPacket(data);   // off the EDT, as the capture thread publishes a snapshot
        Path copy = writeDps(temp.newFolder("imports").toPath().resolve("synthetic-copy.dps"), captured.getSaveFile(false));
        EncounterImport imported = EncounterImport.read(copy);   // the safe reader, off the EDT
        SwingUtilities.invokeAndWait(() -> dps().encounters().add(imported));
    }

    /** Shows the shell at the size, font and mode, runs {@code navigate} on the EDT, then settles. */
    private void show(String title, int width, int height, int font, DisplayModeModel.Mode mode, Runnable navigate) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, title, width, height, font);
            navigate.run();
        });
        pause();
    }

    private void capture(String state, int width, int font, DisplayModeModel.Mode mode, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String name = "p5b-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT);
            evidence.capture(name);
            errors.checkSucceeds(() -> { nothingSideways(shell, name); return null; });
            errors.checkSucceeds(() -> { check.run(); return null; });
        });
    }

    /**
     * The Live meter's facts in either mode: the live fight in the Live meter tab, the true rank by damage (Alpha #1, Bravo #2),
     * your row washed and named "(you)", the enemy cards (All enemies first, the boss card with its chip, "HP —" for an enemy
     * without max HP) and, at the desktop size, the whole Boss chip inside the visible list; at 680×520 font 18 a card too narrow
     * for the chip opens its facts with "Boss · " instead, so the name keeps its width.
     */
    private void liveMeter(boolean desktop) {
        assertEquals("runs", shell.selectedPage());
        assertEquals("The live meter is a tab of Runs & DPS", RunsTab.LIVE_METER, page().selectedTab());
        String summary = meterSummary();
        assertTrue(summary, summary.startsWith("Lost Halls") && summary.contains("LIVE") && summary.contains("13 enemies") && summary.contains("6/6 players"));
        JTable table = meterTable();
        assertEquals(6, table.getRowCount());
        assertEquals("The true rank by damage", "#1", rank(table, "Alpha"));
        assertEquals("#2", rank(table, "Bravo"));
        JLabel mine = bar(table, row(table, "Bravo"));
        assertEquals("Your row says so", "Bravo (you)", mine.getText());
        assertEquals("…and is washed in the accent", Tokens.color(Tokens.Role.ACCENT_WASH), mine.getBackground());
        assertNotEquals(Tokens.color(Tokens.Role.ACCENT_WASH), bar(table, row(table, "Alpha")).getBackground());
        assertTrue("The capture shows the table", inView(table));
        @SuppressWarnings("unchecked") JList<Entity> enemies = field(meter(), "enemyList", JList.class);
        assertEquals("All enemies · 13", slot(card(enemies, 0), BorderLayout.NORTH).getText());
        JPanel boss = card(enemies, 1);
        assertEquals("Highest max HP first: the boss", "Synthetic Colossus", slot(boss, BorderLayout.NORTH).getText());
        Chip chip = (Chip) ((BorderLayout) boss.getLayout()).getLayoutComponent(BorderLayout.EAST);
        String hp = slot(boss, BorderLayout.SOUTH).getText();
        // A card too narrow for ten characters of the name beside the chip folds the Boss marker into its facts (Task 15a).
        boolean folded = !chip.isVisible();
        assertTrue("The desktop boss card keeps its chip", !desktop || !folded);
        if (folded) assertTrue("A narrow boss card opens its facts with Boss: " + hp, hp.startsWith("Boss · 400,000 HP · "));
        else {
            assertEquals("The boss card's chip", "Boss", chip.getText());
            assertTrue(hp, hp.startsWith("400,000 HP · "));
        }
        boolean unknownHp = false;
        for (int i = 1; i < enemies.getModel().getSize(); i++) {
            String facts = slot(card(enemies, i), BorderLayout.SOUTH).getText();
            unknownHp |= facts.startsWith("HP — · ") || facts.startsWith("Boss · HP — · ");
        }
        assertTrue("An enemy without max HP reads \"HP —\", never 0", unknownHp);
        Rectangle cell = enemies.getCellBounds(1, 1);
        boss.setBounds(cell); boss.doLayout();
        JLabel title = slot(boss, BorderLayout.NORTH);
        if (!folded) {
            Rectangle chipInList = new Rectangle(cell.x + chip.getX(), cell.y + chip.getY(), chip.getWidth(), chip.getHeight());
            System.out.println("p5b live meter " + (desktop ? "1240/13" : "680/18") + ": enemy list " + enemies.getVisibleRect().width + " px, boss title "
                + title.getWidth() + " of " + title.getPreferredSize().width + " px, chip " + chipInList);
            assertTrue("The Boss chip lies inside the visible list", enemies.getVisibleRect().contains(chipInList));
        } else System.out.println("p5b live meter 680/18: enemy list " + enemies.getVisibleRect().width + " px, boss title "
            + title.getWidth() + " of " + title.getPreferredSize().width + " px, Boss folded into the facts");
        assertTrue("The capture shows the enemy cards", inView(enemies));
    }

    /** As dps/WaveThreeEvidenceTest: the damage table keeps at least three rows and its whole viewport can be scrolled into view. */
    private void assertMeterUsable(String name) {
        JTable table = meterTable();
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        int rows = scroll.getViewport().getHeight() / table.getRowHeight();
        assertTrue(name + ": damage table viewport " + scroll.getViewport().getSize() + " shows " + rows + " rows of " + table.getRowHeight(), rows >= 3);
        WaveThreeEvidence.reveal(scroll, scroll.getHeight());
        assertEquals(name + ": whole damage table viewport reachable", scroll.getHeight(), scroll.getVisibleRect().height);
    }

    private RunsDpsPage page() { return VisualEvidence.named(shell, "runs-dps-page", RunsDpsPage.class); }
    private DpsGUI dps() { return VisualEvidence.find(shell, DpsGUI.class, d -> true); }
    private MeterDpsGUI meter() { return field(dps(), "displayMeter", MeterDpsGUI.class); }
    private JTable meterTable() { return VisualEvidence.named(shell, "dps-player-table", JTable.class); }
    private String meterSummary() { return String.valueOf(field(meter(), "summary", JLabel.class).getText()); }
    private JTable recordingsTable() { return VisualEvidence.named(shell, "saved-encounters", JTable.class); }
    private String recordingsSummary() { return String.valueOf(VisualEvidence.named(shell, "encounter-summary", JTextComponent.class).getText()); }
    @SuppressWarnings("unchecked") private TileList<DungeonCardModel> cards() { return VisualEvidence.named(shell, "dungeons-cards", TileList.class); }
    @SuppressWarnings("unchecked") private TileList<Object> day(LocalDate date) { return (TileList<Object>) shown(shell, "run-feed-day-" + date); }
    private AbstractButton navRow(String page) { return VisualEvidence.named(shell, "nav-" + page, AbstractButton.class); }

    /** The built Dungeons analysis workspace, or null. */
    private ArchiveWorkspace<?, ?, ?> analysis() {
        return search(VisualEvidence.named(shell, "dungeons-view", DungeonsView.class), ArchiveWorkspace.class, w -> true);
    }

    /** The visible sidebar rows (Settings aside), top to bottom. */
    private List<String> listed() {
        TreeMap<Integer, String> byY = new TreeMap<>();
        for (NavEntry entry : NavEntry.defaults()) {
            String page = entry.id();
            AbstractButton row = navRow(page);
            if (page.equals("settings") || !row.isVisible() || !row.getParent().isVisible()) continue;
            byY.put(SwingUtilities.convertPoint(row, 0, 0, shell).y, page);
        }
        return new ArrayList<>(byY.values());
    }

    /** The selected option of the SegmentedControl named {@code name} (its buttons are {@code name-0}, {@code name-1}, …). */
    private String selectedSegment(String name) {
        SegmentedControl control = VisualEvidence.named(shell, name, SegmentedControl.class);
        return VisualEvidence.named(control, name + "-" + control.selected(), AbstractButton.class).getText();
    }

    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    /** The first view row whose Dungeon and Source file match and whose Saved column starts with {@code saved} (null: any), or -1. */
    private static int rowOf(JTable table, String dungeon, String source, String saved) {
        for (int row = 0; row < table.getRowCount(); row++)
            if (dungeon.equals(value(table, row, DUNGEON)) && source.equals(value(table, row, SOURCE))
                && (saved == null || value(table, row, SAVED).startsWith(saved))) return row;
        throw new AssertionError("No row " + dungeon + " / " + source + " / " + saved);
    }
    /** The model's value at view row {@code row}, model column {@code column} (the view orders the columns; Simple hides some). */
    private static String value(JTable table, int row, int column) { return String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(row), column)); }
    /** The live row's view index (its Dungeon cell reads "Live"). */
    private static int liveRow(JTable table) {
        for (int row = 0; row < table.getRowCount(); row++) if ("Live".equals(value(table, row, DUNGEON))) return row;
        throw new AssertionError("No live row");
    }
    /** The view's column headers, left to right. */
    private static List<String> headers(JTable table) {
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < table.getColumnCount(); column++) headers.add(table.getColumnName(column));
        return headers;
    }

    /** No text, tooltip or table cell of the Recordings tab names a folder: file names only. */
    private void assertNoPath() {
        JComponent library = VisualEvidence.find(shell, DungeonListGUI.class, l -> true);
        List<String> texts = new ArrayList<>();
        collect(library, texts);
        JTable table = recordingsTable();
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int column = 0; column < table.getModel().getColumnCount(); column++) texts.add(value(table, row, column));
            for (int column = 0; column < table.getColumnCount(); column++) {
                Component cell = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                if (cell instanceof JComponent) texts.add(String.valueOf(((JComponent) cell).getToolTipText()));
            }
        }
        for (String text : texts) {
            assertFalse("No path: " + text, text.contains(temp.getRoot().getAbsolutePath()));
            assertFalse("No full-detail folder: " + text, text.contains(CombatRetention.FULL_DETAIL));
        }
    }

    private static void collect(Container root, List<String> texts) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextComponent) texts.add(((JTextComponent) child).getText());
            if (child instanceof JLabel) texts.add(((JLabel) child).getText());
            if (child instanceof AbstractButton) texts.add(((AbstractButton) child).getText());
            if (child instanceof JComponent && ((JComponent) child).getToolTipText() != null) texts.add(((JComponent) child).getToolTipText());
            if (child instanceof Container) collect((Container) child, texts);
        }
    }

    private static int row(JTable table, String name) {
        for (int r = 0; r < table.getRowCount(); r++) if (name.equals(table.getValueAt(r, 0))) return r;
        throw new AssertionError("No row " + name);
    }
    private static JLabel bar(JTable table, int row) { return (JLabel) table.prepareRenderer(table.getCellRenderer(row, 0), row, 0); }

    /** The rank the player cell shows, read from its tooltip ("… · Rank #2 …"). */
    private static String rank(JTable table, String name) {
        String tip = bar(table, row(table, name)).getToolTipText();
        int at = tip == null ? -1 : tip.indexOf("Rank ");
        assertTrue("Rank in the tooltip: " + tip, at >= 0);
        String rest = tip.substring(at + 5);
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static JPanel card(JList<Entity> list, int index) {
        return (JPanel) list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(index), index, false, false);
    }
    private static JLabel slot(JPanel card, String where) { return (JLabel) ((BorderLayout) card.getLayout()).getLayoutComponent(where); }

    /** S6 at desktop width, as FilterBarEvidenceTest checks it: with the drawer closed, the search slot and Filters share one row. */
    private static void assertOneFilterRow(String name, FilterBar bar) {
        AbstractButton filters = VisualEvidence.named(bar, bar.getName().replace("-filter-bar", "-filters"), AbstractButton.class);
        Component slot = filters.getParent().getComponent(0);
        int slotY = SwingUtilities.convertPoint(slot, 0, 0, bar).y, filtersY = SwingUtilities.convertPoint(filters, 0, 0, bar).y;
        assertTrue(name + ": the search slot and Filters share one row", Math.abs(slotY - filtersY) < filters.getHeight());
    }

    /** The text of the component named {@code name}: a text area, label or button. */
    private String text(String name) {
        Component component = VisualEvidence.named(shell, name, JComponent.class);
        if (component instanceof JTextComponent) return ((JTextComponent) component).getText();
        if (component instanceof AbstractButton) return ((AbstractButton) component).getText();
        return ((JLabel) component).getText();
    }

    /** Scrolls {@code page} so that {@code part} starts at the top of its viewport (as far as the page scrolls). */
    private static void scrollToTop(JScrollPane page, JComponent part) {
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), page.getViewport().getView());
        page.getVerticalScrollBar().setValue(placed.y);
    }

    /** Whether some of {@code part} is inside the visible part of its scroll pane (the capture shows it). */
    private static boolean inView(Component part) { return part != null && part.isShowing() && ((JComponent) part).getVisibleRect().height > 0; }

    /** What a screen reader announces for each tile (the painted text is not in the component tree). */
    private static List<String> names(JList<?> tiles) {
        assertNotNull("The tiles show", tiles);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tiles.getModel().getSize(); i++)
            names.add(tiles.getAccessibleContext().getAccessibleChild(i).getAccessibleContext().getAccessibleName());
        return names;
    }

    /** Polls {@code condition} on the EDT (pages apply their reads on later EDT turns) for up to 20 s. */
    private static void await(String what, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > end) fail(what + " did not settle within 20 s");
            Thread.sleep(50);
        }
    }

    private static <T> T edt(java.util.concurrent.Callable<T> body) throws Exception {
        Object[] result = new Object[1]; Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> { try { result[0] = body.call(); } catch (Throwable t) { failure[0] = t; } });
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] != null) throw new AssertionError(failure[0]);
        @SuppressWarnings("unchecked") T value = (T) result[0];
        return value;
    }

    /**
     * No page scrolls or cuts its content sideways: every showing scroll pane has no horizontal scroll bar and a view no wider
     * than its viewport. A data table scrolls its own columns sideways by design, and the meter's hit report does not wrap its
     * lines: both are listed on standard output instead (as in P3b–P5a).
     */
    private static void nothingSideways(Container root, String capture) {
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane && child.isShowing()) {
                JScrollPane scroll = (JScrollPane) child;
                Component view = scroll.getViewport().getView();
                String where = scroll.getName() != null ? scroll.getName() : view == null ? "an empty scroll pane" : view.getClass().getSimpleName();
                boolean report = view instanceof JTextArea && !((JTextArea) view).getLineWrap();
                if (view instanceof JTable || report) System.out.println(capture + ": " + (report ? "report " : "table ") + where + " " + view.getWidth() + " px in a "
                    + scroll.getViewport().getWidth() + " px viewport, horizontal bar " + (scroll.getHorizontalScrollBar().isShowing() ? "shown" : "hidden"));
                else {
                    assertFalse(capture + ": a horizontal scroll bar in " + where, scroll.getHorizontalScrollBar().isShowing());
                    if (view != null) assertTrue(capture + ": " + where + " is " + view.getWidth() + " px wide in a " + scroll.getViewport().getWidth() + " px viewport",
                        view.getWidth() <= scroll.getViewport().getWidth());
                }
            }
            if (child instanceof Container) nothingSideways((Container) child, capture);
        }
    }

    /** The first showing component named {@code name} under {@code root}, or null. */
    private static Component shown(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && child.isShowing()) return child;
            if (child instanceof Container) { Component found = shown((Container) child, name); if (found != null) return found; }
        }
        return null;
    }

    /** The first component of {@code type} under {@code root} that {@code predicate} accepts, or null. */
    private static <T extends Component> T search(Container root, Class<T> type, Predicate<T> predicate) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && predicate.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container) { T found = search((Container) child, type, predicate); if (found != null) return found; }
        }
        return null;
    }

    /** Pages apply reads on later EDT turns and motion takes at most Motion.MAX_MILLIS: settle, wait, settle. */
    private void pause() throws Exception { evidence.settle(); Thread.sleep(400); evidence.settle(); }

    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); statics.put(field, field.get(null)); field.set(null, next);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Set<String> archiveKeys() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Set<String> keys = new HashSet<>(((Properties) field.get(null)).stringPropertyNames());
        keys.removeIf(key -> !key.startsWith("ux.archive."));
        return keys;
    }
}
