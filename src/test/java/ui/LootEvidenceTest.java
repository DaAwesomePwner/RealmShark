package ui;

import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
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
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import assets.IdToAsset;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.character.CharacterPanelGUI;
import tomato.gui.character.CharacterPetsGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.DpsDisplayOptions;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.Filter;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.kit.Banner;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.StatTile;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.loot.LootFocus;
import tomato.gui.loot.LootHighlights;
import tomato.gui.loot.LootPage;
import tomato.gui.loot.LootTab;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.TestPages;
import tomato.gui.modern.Themes;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.settings.SettingsPage;
import tomato.gui.stats.CharacterFameHistory;
import tomato.gui.stats.DropContext;
import tomato.gui.stats.LootCapture;
import tomato.gui.stats.LootDashboard;
import tomato.gui.stats.LootFilters;
import tomato.gui.stats.LootQuery;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseEnchants;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P6a Loot evidence (spec §4.1, §6.4, §6.7, S6, S7) in the real workspace ({@code TomatoGUI.createWorkspace} in preview mode; the
 * app's history store is pointed at the test's own history folder, or at none, and restored after), synthetic names only:
 * - Loot › Highlights from saved history (Today): populated (tiles, notable drops, bags by dungeon, the Highlights · Explore
 *   strip, the sidebar's six core rows without Statistics), empty ("No notable drops yet", unknown tiles), the live fallback
 *   without a history store ("This app run · not saved") and partial (◐, one of today's sessions unreadable); Simple 1240×800
 *   font 13 and Analyst 680×520 font 18 in the dark theme, and 1240×800 font 13 in the light theme;
 * - Loot › Explore live and saved behind one view selector, Simple and Analyst, and a saved-only view chosen from live ("Saved
 *   history only");
 * - Settings › Loot filters, Chat and About; Characters › Fame history (an Analyst tab; hidden in Simple).
 * The JVM's default time zone is set for the test to a fixed offset at which it is mid-afternoon (as {@link RunsEvidenceTest}
 * does), so the saved bags fall on the local day whenever it runs. The test has no game assets: item names are synthetic asset
 * entries (restored after) and sprites are placeholders. Preferences (tabs, sidebar layout, filter drawers, the Highlights window,
 * the Filter Loot keys, Save chat, the display mode), the archive workspaces' keys, the loot capture, the zone, the format locale
 * and the DPS statics are restored after each test.
 * <p>The capture harness's undecorated frame paints a 23 px title band and 6 px edges over its content, so each capture pads the
 * shell by {@value #BAND} px at the top and {@value #EDGE} px at the sides and bottom and enlarges the frame by the same amount:
 * the shell itself is exactly 1240×800 or 680×520 (asserted), and nothing of it is hidden. Every capture asserts that no page
 * scrolls sideways (a data table that scrolls its own columns is listed on standard output instead) and the content it is
 * evidence of. Captures in {@code redesign-p6a-loot}.
 */
public class LootEvidenceTest {
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    /** The harness's title band and frame edges, padded around the shell so no content is hidden. */
    private static final int BAND = 24, EDGE = 6;
    /** Synthetic item names by ID (no game assets); the stat potions use the game's IDs, so Highlights knows their stats. */
    private static final Map<Integer, String> ASSETS = assets();
    private static final String LIFE = "Life potion", GREATER_LIFE = "Greater life potion";
    private static final String EARLIER = HomeHistoryFixture.id("p6a-loot-evidence-today"), YESTERDAY = HomeHistoryFixture.id("p6a-loot-evidence-yesterday"),
        BROKEN = HomeHistoryFixture.id("p6a-loot-evidence-broken");
    private static final VisitRef A1 = new VisitRef(EARLIER, "a1"), A2 = new VisitRef(EARLIER, "a2"), A3 = new VisitRef(EARLIER, "a3"), A4 = new VisitRef(EARLIER, "a4");
    /** Preference prefixes cleared, so the workspace opens on its defaults; every key the test changes is restored afterwards. */
    private static final String[] CLEARED = {"ux.archive.", CustomizableTabs.PREFIX, "ui.nav.", "ui.filters.", "ui.loot.", "ui.collapse.", "ui.order.",
        "ui.runs.", "ui.dungeons.", "filter", TomatoMenuBar.SAVE_CHAT, DisplayModeModel.KEY};
    private static final List<String> CORE = List.of("home", "characters", "runs", "loot", "quests", "chat");
    private static final List<String> SIMPLE_VIEWS = List.of("All Items", "Stat Potions", "Whites", "By Bag", "By Dungeon", "UTs", "STs", "Tiered", "Recent Drops");
    private static final List<String> ANALYST_VIEWS = List.of("Item occurrences", "Dungeon loot profile", "Session comparison", "A/B cohorts", "Enemy hit events", "Loot by source");

    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p6a-loot");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> savedPrefs = new LinkedHashMap<>();
    private final List<Set<?>> filterSets = new ArrayList<>();
    private final List<Set<?>> filterCopies = new ArrayList<>();
    private final tomato.gui.roster.RosterStateTestSupport.Memory views = new tomato.gui.roster.RosterStateTestSupport.Memory();
    private DisplayModeModel.Mode savedMode;
    private TimeZone savedZone;
    private Locale savedLocale;
    private String temporaryDirectory;
    private SessionStore store;
    private CharacterJournal journal;
    private TomatoGUI gui;
    private WorkspaceShell shell;
    /** The frame's content: the shell padded clear of the harness's title band and edges. */
    private JPanel padded;

    private static Map<Integer, String> assets() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(9101, "Synthetic Voidblade"); names.put(9102, "Synthetic Aegis Robe"); names.put(9103, "Synthetic Tier Sword");
        names.put(9201, "Synthetic Frost Staff"); names.put(9301, "Synthetic Tidal Dagger"); names.put(9401, "Synthetic Viper Bow");
        names.put(9501, "Synthetic Crystal Mail"); names.put(9601, "Synthetic Plain Ring"); names.put(9701, "Synthetic Old Relic");
        names.put(2793, LIFE); names.put(9070, GREATER_LIFE); names.put(2794, "Mana potion"); names.put(2592, "Defense potion");
        names.put(2591, "Attack potion"); names.put(2613, "Wisdom potion"); names.put(12_345, "Synthetic tonic");
        return Collections.unmodifiableMap(names);
    }

    @Before public void open() throws Exception {
        PropertiesManager.preload(); // merge the disk file first, so the snapshot below sees every saved key
        Properties props = properties();
        props.stringPropertyNames().forEach(key -> savedPrefs.put(key, props.getProperty(key)));
        for (String key : props.stringPropertyNames())
            for (String prefix : CLEARED) if (key.startsWith(prefix)) { PropertiesManager.setProperties(key, ""); break; }
        PropertiesManager.setProperties("chat.filters", "{}");
        PropertiesManager.setProperties("chat.showIgnoredPlayers", "false");
        PropertiesManager.setProperties("filterName", "Default");   // the DPS filter preset: none (read when the workspace is built)
        SwingUtilities.invokeAndWait(() -> { savedMode = DisplayModeModel.application().mode(); DisplayModeModel.application().set(SIMPLE); });
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        savedZone = TimeZone.getDefault();
        savedLocale = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone.setDefault(TimeZone.getTimeZone(RunsEvidenceTest.afternoon(System.currentTimeMillis())));
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        remember(Tomato.class, "preview", true);
        remember(TomatoGUI.class, "characterViewStates", views.store);
        // A fresh loot capture: the live feed holds only this test's bags (the previous capture is put back after).
        remember(LootCapture.class, "instance", null);
        // No game assets: synthetic item names (and labels) for the notable cards and the tables, restored after the test.
        Field objects = IdToAsset.class.getDeclaredField("objectID"); objects.setAccessible(true);
        @SuppressWarnings("unchecked") HashMap<Integer, IdToAsset> assets = new HashMap<>((Map<Integer, IdToAsset>) objects.get(null));
        for (Map.Entry<Integer, String> item : ASSETS.entrySet())
            assets.put(item.getKey(), new IdToAsset("", item.getKey(), item.getValue(), item.getValue(), "", null, "", label(item.getKey()), ""));
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
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            DisplayModeModel.application().set(savedMode);
            try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            for (int i = 0; i < filterSets.size(); i++) restore(filterSets.get(i), filterCopies.get(i));
        });
        SwingUtilities.invokeAndWait(() -> { });   // a final view-state save queued by closing drains before the restore
        if (journal != null) journal.close();
        Properties props = properties();
        Set<String> keys = new HashSet<>(props.stringPropertyNames());
        keys.addAll(savedPrefs.keySet());
        for (String key : keys)
            if (!savedPrefs.getOrDefault(key, "").equals(props.getProperty(key, ""))) PropertiesManager.setProperties(key, savedPrefs.getOrDefault(key, ""));
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (savedZone != null) TimeZone.setDefault(savedZone);
        if (savedLocale != null) Locale.setDefault(Locale.Category.FORMAT, savedLocale);
        if (store != null) store.close();
    }

    @SuppressWarnings("unchecked") private static <T> void restore(Set<T> set, Set<?> copy) { set.clear(); set.addAll((Set<T>) copy); }

    // ---- Highlights ----

    /**
     * 4 captures: Loot opens on Highlights (the strip Highlights · Explore) over today's saved loot: UT 2, ST 2, potions 7 (by stat)
     * and white bags 2 of 9 (one bag without a saved name); twelve notable drops newest first; five areas by bags with Unknown area
     * last. Simple 1240×800 font 13 dark, Analyst 680×520 font 18 dark (the top, and scrolled to the notable drops), and Simple
     * 1240×800 font 13 light (the theme is switched live, last, as Settings › Appearance does).
     */
    @Test public void highlightsShowTodaysTilesNotableDropsAndDungeonsFromSavedHistory() throws Exception {
        Path history = temp.newFolder("history").toPath();
        writeToday(history, true);
        writeYesterday(history);
        build(history, true, true);
        openHighlights();
        Runnable facts = () -> {
            highlightsPage();
            assertEquals(HighlightsModel.Source.SAVED + " source", "Saved history · Today", text("loot-highlights-source"));
            assertEquals("Today", selectedSegment("loot-highlights-window"));
            assertTile("loot-tile-ut", "2", DisplayValue.State.KNOWN);
            assertTile("loot-tile-st", "2", DisplayValue.State.KNOWN);
            assertTile("loot-tile-potions", "7", DisplayValue.State.KNOWN);
            assertTile("loot-tile-whites", "2", DisplayValue.State.KNOWN);
            List<String> notable = names(list("loot-notable-grid"));
            assertEquals(notable.toString(), 12, notable.size());
            assertTrue("Newest first: the ST of this app run's session: " + notable.get(0), notable.get(0).startsWith("Synthetic Crystal Mail, ST (Uncommon · 1 enchant slot); Ice Citadel, "));
            assertTrue("Enchanted = rare or better (2+ slots): " + notable, notable.stream().anyMatch(n -> n.startsWith("Synthetic Frost Staff, Gear (Rare · 2 enchant slots); Ice Citadel, ")));
            assertTrue(notable.stream().anyMatch(n -> n.startsWith("Synthetic Tier Sword, Gear (Legendary · 3 enchant slots); Lost Halls, ")));
            assertFalse("An item without recorded enchant slots is never listed as enchanted", notable.stream().anyMatch(n -> n.startsWith("Synthetic Viper Bow")));
            assertTrue("A 1-slot item is not rare or better: the UT dagger is listed as UT only: " + notable, notable.stream().anyMatch(n -> n.startsWith("Synthetic Tidal Dagger, UT (Uncommon · 1 enchant slot); "))
                && notable.stream().noneMatch(n -> n.startsWith("Synthetic Tidal Dagger, Gear")));
            assertTrue("Other potions are not notable", notable.stream().noneMatch(n -> n.startsWith("Synthetic tonic")));
            assertTrue("A drop without a run says so", notable.stream().anyMatch(n -> n.startsWith("Mana potion") && n.endsWith("not linked to a run")));
            String notes = text("loot-notable-notes");
            assertTrue(notes, notes.contains("1 item without recorded enchant slots is not listed as enchanted"));
            List<String> strip = names(list("loot-dungeon-strip"));
            assertEquals(strip.toString(), 5, strip.size());
            assertTrue("Most bags first: " + strip.get(0), strip.get(0).startsWith("Lost Halls; 4 bags"));
            assertTrue("Unknown area is kept, last: " + strip.get(4), strip.get(4).startsWith(HighlightsModel.UNKNOWN_AREA + "; 1 bag"));
            assertFalse("No partial banner", VisualEvidence.named(shell, "loot-highlights-partial", Banner.class).isVisible());
            assertFalse("No Filter Loot line: every bag color shows", VisualEvidence.named(shell, "loot-notable-filtered", JComponent.class).isVisible());
        };
        capture("highlights", 1240, 13, SIMPLE, "", () -> {
            facts.run();
            assertSidebar();
            String potions = subline("loot-tile-potions");
            assertTrue("The potions sub-line lists stats: " + potions, potions.startsWith("2 Life · 1 Mana · 1 Att · 1 Def"));
            assertEquals("of 9 bags · 1 without a bag name", subline("loot-tile-whites"));
            assertTilesWhole("p6a-highlights-1240-13-simple");
            assertTilesInOneRow();
            assertTrue("The capture shows the notable drops", inView(list("loot-notable-grid")));
        });
        show("Loot highlights compact", 680, 520, 18, ANALYST, () -> { });
        capture("highlights", 680, 18, ANALYST, "", () -> { facts.run(); assertTilesWrapped("p6a-highlights-680-18-analyst"); });
        SwingUtilities.invokeAndWait(() -> scrollTo(list("loot-notable-grid"), 3 * ContentStyle.body().getSize()));
        pause();
        capture("highlights-notable", 680, 18, ANALYST, "", () -> {
            assertTrue("Scrolled to the notable drops", inView(list("loot-notable-grid")));
            assertEquals(12, list("loot-notable-grid").getModel().getSize());
        });
        light(1240, 800, 13);
        capture("highlights", 1240, 13, SIMPLE, "-light", facts);
    }

    /**
     * 3 captures: nothing was saved today (yesterday's loot does not count, and this app run's session saved no bag): every tile is
     * unknown ("—" with the reason, never 0), the notable grid says "No notable drops yet" with what fills it, and the strip says no
     * bags. Dark at 1240 and 680, then light at 1240.
     */
    @Test public void highlightsWithNothingSavedTodaySayNoNotableDropsYet() throws Exception {
        Path history = temp.newFolder("history").toPath();
        writeYesterday(history);
        build(history, true, false);
        openHighlights();
        Runnable facts = () -> {
            highlightsPage();
            assertEquals("Saved history · Today", text("loot-highlights-source"));
            for (String tile : new String[] {"loot-tile-ut", "loot-tile-st", "loot-tile-potions", "loot-tile-whites"}) {
                StatTile shown = VisualEvidence.named(shell, tile, StatTile.class);
                assertEquals(tile + " is unknown, never 0", DisplayValue.State.UNKNOWN, shown.value().state);
                assertEquals("—", shown.valueText());
                assertEquals(HighlightsModel.NO_LOOT, shown.value().detail);
            }
            EmptyState empty = VisualEvidence.named(shell, "loot-notable-empty", EmptyState.class);
            assertTrue(empty.isShowing());
            assertEquals("No notable drops yet", empty.getAccessibleContext().getAccessibleName());
            assertTrue(empty.getAccessibleContext().getAccessibleDescription(), empty.getAccessibleContext().getAccessibleDescription().startsWith(HighlightsModel.NO_LOOT + ". Start capture"));
            assertEquals("No bags in this period.", text("loot-dungeon-strip-note"));
            assertFalse(list("loot-notable-grid").isVisible());
        };
        capture("highlights-empty", 1240, 13, SIMPLE, "", () -> { facts.run(); assertSidebar(); });
        show("Loot highlights empty compact", 680, 520, 18, ANALYST, () -> { });
        capture("highlights-empty", 680, 18, ANALYST, "", facts);
        light(1240, 800, 13);
        capture("highlights-empty", 1240, 13, SIMPLE, "-light", facts);
    }

    /**
     * 3 captures: no history store is open, so Highlights shows this app run's live capture, labeled "This app run · not saved",
     * and Explore is the live dashboard alone. Dark at 1240 and 680, then light at 1240.
     */
    @Test public void highlightsWithoutSavedHistoryShowTheLiveCaptureLabeledNotSaved() throws Exception {
        build(null, false, false);
        feed(liveDrops());
        openHighlights();
        Runnable facts = () -> {
            highlightsPage();
            assertEquals(HighlightsModel.LIVE, text("loot-highlights-source"));
            assertTile("loot-tile-ut", "2", DisplayValue.State.KNOWN);
            assertTile("loot-tile-st", "2", DisplayValue.State.KNOWN);
            assertTile("loot-tile-potions", "7", DisplayValue.State.KNOWN);
            assertTile("loot-tile-whites", "2", DisplayValue.State.KNOWN);
            assertEquals("of 8 bags", subline("loot-tile-whites"));
            assertEquals(12, list("loot-notable-grid").getModel().getSize());
            assertEquals(5, list("loot-dungeon-strip").getModel().getSize());
            JComponent explore = exploreContent();
            assertTrue("Without saved history Explore is the live dashboard alone: " + explore.getClass().getSimpleName(), explore instanceof LootDashboard);
        };
        capture("highlights-live", 1240, 13, SIMPLE, "", () -> { facts.run(); assertSidebar(); assertTilesInOneRow(); });
        show("Loot highlights live compact", 680, 520, 18, ANALYST, () -> { });
        capture("highlights-live", 680, 18, ANALYST, "", () -> { facts.run(); assertTilesWrapped("p6a-highlights-live-680-18-analyst"); });
        light(1240, 800, 13);
        capture("highlights-live", 1240, 13, SIMPLE, "-light", facts);
    }

    /**
     * 3 captures: one of today's saved sessions cannot be read (its loot journal is damaged): the counts cover the others and say
     * so (◐ on every tile and a warn line with the count). Dark at 1240 and 680, then light at 1240.
     */
    @Test public void highlightsMarkAnUnreadableSessionPartial() throws Exception {
        Path history = temp.newFolder("history").toPath();
        writeToday(history, true);
        writeYesterday(history);
        HomeHistoryFixture.session(history, BROKEN, at(0, 9, 0), at(0, 10, 0));
        HomeHistoryFixture.loot(history, BROKEN, drop("White", "Lost Halls", at(0, 9, 30), null, equipment(9701, "EQUIPMENT,WEAPON,UT", 0)));
        Path damaged = history.resolve(BROKEN).resolve("loot.jsonl");
        Files.writeString(damaged, "{broken\n" + Files.readString(damaged, StandardCharsets.UTF_8), StandardCharsets.UTF_8);   // not the last line
        build(history, true, true);
        openHighlights();
        Runnable facts = () -> {
            highlightsPage();
            Banner partial = VisualEvidence.named(shell, "loot-highlights-partial", Banner.class);
            assertTrue(partial.isVisible());
            assertEquals("◐ 1 saved session could not be read; their loot is missing from these counts.", partial.text());
            assertTile("loot-tile-ut", "2", DisplayValue.State.PARTIAL);
            assertTile("loot-tile-whites", "2", DisplayValue.State.PARTIAL);
            assertTrue(VisualEvidence.named(shell, "loot-tile-ut", StatTile.class).value().detail.startsWith("1 saved session could not be read"));
            assertEquals("The readable sessions still list their drops", 12, list("loot-notable-grid").getModel().getSize());
            assertTrue("The capture shows the warn line", inView(partial));
        };
        capture("highlights-partial", 1240, 13, SIMPLE, "", () -> { facts.run(); assertSidebar(); assertTilesInOneRow(); });
        show("Loot highlights partial compact", 680, 520, 18, ANALYST, () -> { });
        capture("highlights-partial", 680, 18, ANALYST, "", () -> { facts.run(); assertTilesWrapped("p6a-highlights-partial-680-18-analyst"); });
        light(1240, 800, 13);
        capture("highlights-partial", 1240, 13, SIMPLE, "-light", facts);
    }

    // ---- Explore ----

    /**
     * 6 captures: Loot › Explore live (this app run's bags) and saved (every saved session) behind one view selector, Simple
     * 1240×800 font 13 and Analyst 680×520 font 18 (compact captures scrolled to the view selector); then a saved-only Analyst view
     * chosen from the selector while live opens saved history with that view, captioned "Saved history only" (Analyst at 1240 and
     * 680). Since P6b the one selector leads the filter row the page shows (the live row, which hosts the Scope chip, or the saved
     * row), and saved Simple shows one plain count line without the "pinned …" caption.
     * The saved sessions hold no legacy bag without a saved name: Dungeon loot profile cannot read one (finding 2).
     */
    @Test public void exploreShowsLiveAndSavedLootBehindOneViewSelector() throws Exception {
        Path history = temp.newFolder("history").toPath();
        writeToday(history, false);
        writeYesterday(history);
        build(history, true, true);
        feed(liveDrops());
        show("Loot explore live", 1240, 800, 13, SIMPLE, () -> assertTrue(Navigator.current().open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.EXPLORE)))));
        await("the live loot", () -> liveTable(0).getRowCount() > 0);
        pause();
        capture("explore-live", 1240, 13, SIMPLE, "", () -> {
            assertExplore(false);
            assertEquals("Simple lists the nine item views", SIMPLE_VIEWS, rows(selector()));
            assertEquals(LootQuery.View.ITEMS, selector().getSelectedItem());
            FilterBar bar = VisualEvidence.named(shell, "loot-live-filter-bar", FilterBar.class);
            assertFalse(bar.drawerOpen());
            FilterBarAssert.assertOneRow(bar);
            assertTrue("The view selector sits in the live filter row's search slot", SwingUtilities.isDescendingFrom(selector(), searchSlot(bar)));
            FilterBarAssert.assertChipInVisibleBar(workspace());
            assertTrue("The live filter row is above the tiles", SwingUtilities.convertPoint(bar, 0, 0, shell).y
                < SwingUtilities.convertPoint(VisualEvidence.named(shell, "loot-metrics", JComponent.class), 0, 0, shell).y);
            assertTrue("The capture shows the live table", inView(liveTable(0)));
            assertSidebar();
        });
        // Compact: the capture starts at the filter row, the page's first row since P6b.
        show("Loot explore live compact", 680, 520, 18, ANALYST, () -> { });
        SwingUtilities.invokeAndWait(() -> scrollTo(VisualEvidence.named(shell, "loot-live-filter-bar", FilterBar.class), 4));
        pause();
        capture("explore-live", 680, 18, ANALYST, "", () -> {
            assertExplore(false);
            List<String> analyst = new ArrayList<>(SIMPLE_VIEWS); analyst.add("[Analyst]"); analyst.addAll(ANALYST_VIEWS);
            assertEquals("Analyst adds the six saved-only views", analyst, rows(selector()));
            FilterBarAssert.assertChipInVisibleBar(workspace());
        });

        // Saved history over every saved session, then the selector's Analyst list at the compact size.
        SwingUtilities.invokeAndWait(() -> workspace().selectSession(SessionStore.ALL));
        await("the saved loot", () -> ArchiveNativeSupport.ready(workspace()) && workspace().state().archive && savedTable().getRowCount() > 0);
        pause();
        SwingUtilities.invokeAndWait(() -> scrollTo(selector(), 4));
        pause();
        capture("explore-saved", 680, 18, ANALYST, "", () -> {
            assertExplore(true);
            assertEquals(LootQuery.View.ITEMS, selector().getSelectedItem());
            List<String> analyst = new ArrayList<>(SIMPLE_VIEWS); analyst.add("[Analyst]"); analyst.addAll(ANALYST_VIEWS);
            assertEquals(analyst, rows(selector()));
            FilterBarAssert.assertChipInVisibleBar(workspace());
        });
        show("Loot explore saved", 1240, 800, 13, SIMPLE, () -> { });
        capture("explore-saved", 1240, 13, SIMPLE, "", () -> {
            assertExplore(true);
            assertEquals(SIMPLE_VIEWS, rows(selector()));
            assertEquals(LootQuery.View.ITEMS, selector().getSelectedItem());
            assertFalse("All Items is not saved-only", caption().isVisible());
            FilterBar bar = workspace().filterBar();
            FilterBarAssert.assertOneRow(bar);
            assertTrue("The one view selector leads the saved filter row's search slot", SwingUtilities.isDescendingFrom(selector(), bar.searchSlot()));
            FilterBarAssert.assertChipInVisibleBar(workspace());
            // Polish B4: Simple shows one plain count line, and the "pinned …" caption is Analyst detail.
            JTextArea counts = VisualEvidence.named(workspace(), "loot-archive-counts", JTextArea.class);
            assertTrue("One plain count line: " + counts.getText(), counts.getText().matches("[0-9,]+ bags? · [0-9,]+ item variants? · [0-9,]+ items?"));
            String status = field(workspace(), "status", JTextArea.class).getText();
            assertFalse("No pinned caption in Simple: " + status, status.contains("pinned"));
            assertNull("The drill-downs are ⋯ items", search(workspace(), JButton.class, b -> "loot-drill-occurrences".equals(b.getName())));
            assertTrue("The capture shows the saved table", inView(savedTable()));
        });

        // Back to live, Analyst: a saved-only view chosen from the selector while live opens saved history with it.
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(ANALYST);
            ArchiveNativeSupport.scopeItem(workspace(), "live").doClick();
        });
        pause();
        SwingUtilities.invokeAndWait(() -> {
            assertFalse("Live again", workspace().state().archive);
            selector().setSelectedItem(LootQuery.View.RATES);   // a user's choice
            assertTrue("A saved-only view opens saved history", workspace().state().archive);
        });
        await("the saved-only view", () -> ArchiveNativeSupport.ready(workspace()) && workspace().state().query.facets().view == LootQuery.View.RATES);
        pause();
        Runnable savedOnly = () -> {
            assertExplore(true);
            assertEquals(LootQuery.View.RATES, selector().getSelectedItem());
            assertTrue("The caption says the view is saved-only", caption().isVisible());
            assertEquals("Saved history only", caption().getText());
            assertTrue("The caption follows the selector in the saved filter row", SwingUtilities.isDescendingFrom(caption(), workspace().filterBar()));
            assertTrue(inView(caption()));
        };
        capture("explore-saved-only", 1240, 13, ANALYST, "", () -> {
            savedOnly.run();
            assertTrue("The Dungeon loot profile rows show", savedTable().getRowCount() > 0 && inView(savedTable()));
            String status = field(workspace(), "status", JTextArea.class).getText();
            assertFalse("The read succeeded: " + status, status.contains("read failed"));
            assertTrue("Analyst keeps the pinned caption: " + status, status.contains(" · pinned "));
        });
        show("Loot explore saved-only compact", 680, 520, 18, ANALYST, () -> { });
        SwingUtilities.invokeAndWait(() -> scrollTo(selector(), 4));
        pause();
        capture("explore-saved-only", 680, 18, ANALYST, "", savedOnly);
    }

    // ---- Settings and Characters ----

    /** 6 captures: Settings › Loot filters, Chat and About, Simple 1240×800 font 13 and Analyst 680×520 font 18. */
    @Test public void settingsHostLootFiltersChatAndAbout() throws Exception {
        build(temp.newFolder("history").toPath(), true, false);
        for (int[] size : new int[][] {{1240, 800, 13}, {680, 520, 18}}) {
            DisplayModeModel.Mode mode = size[0] == 1240 ? SIMPLE : ANALYST;
            show("Settings loot filters", size[0], size[1], size[2], mode, () -> TomatoGUI.openSettings(SettingsPage.LOOT_FILTERS));
            capture("settings-loot-filters", size[0], size[2], mode, "", () -> {
                assertSettings(SettingsPage.LOOT_FILTERS, "settings-loot-filters");
                for (LootFilters.Kind kind : LootFilters.Kind.values()) {
                    JCheckBox box = VisualEvidence.named(shell, "settings-loot-filter-" + kind.name().toLowerCase(Locale.ROOT), JCheckBox.class);
                    assertEquals(kind.label(), box.getText());
                    assertTrue(kind + " shows (keys cleared)", box.isSelected());
                    VisualEvidence.completeButton(box);
                }
                assertTrue(text("settings-loot-filters-help").contains("Tiles and counts always include every observed drop"));
            });
            show("Settings chat", size[0], size[1], size[2], mode, () -> TomatoGUI.openSettings(SettingsPage.CHAT));
            capture("settings-chat", size[0], size[2], mode, "", () -> {
                assertSettings(SettingsPage.CHAT, "settings-chat");
                // Polish B2 (finding 9), kept by P6b: Saving and the editor scroll together in one page over a pinned footer (Save
                // filters, Cancel and the save status), so the footer is in view as the section opens, before anything is scrolled.
                assertInViewWithoutScrolling("chat-save-filters", "chat-cancel-filters", "chat-filter-save-status");
                AbstractButton save = VisualEvidence.named(shell, "settings-chat-save", AbstractButton.class);
                assertFalse("Save chat is off (key cleared)", save.isSelected());
                VisualEvidence.completeButton(save);
            });
            show("Settings about", size[0], size[1], size[2], mode, () -> TomatoGUI.openSettings(SettingsPage.ABOUT));
            capture("settings-about", size[0], size[2], mode, "", () -> {
                assertSettings(SettingsPage.ABOUT, "settings-about");
                for (String name : new String[] {"settings-about-java-version", "settings-about-net-traffic"})
                    VisualEvidence.completeButton(VisualEvidence.named(shell, name, AbstractButton.class));
                List<String> texts = new ArrayList<>();
                collect(VisualEvidence.named(shell, "settings-about", JComponent.class), texts);
                for (String shown : texts) {
                    assertFalse("No path: " + shown, shown != null && shown.contains(temp.getRoot().getAbsolutePath()));
                    assertFalse("No home folder: " + shown, shown != null && shown.contains(System.getProperty("user.home") + java.io.File.separator));
                }
            });
        }
    }

    /**
     * 2 captures: Characters › Fame history, an Analyst tab (hidden in Simple) over saved character fame, with "Open fame session
     * file…" beside it; Analyst 1240×800 font 13 and 680×520 font 18.
     */
    @Test public void charactersFameHistoryIsAnAnalystTabOverSavedFame() throws Exception {
        Path history = temp.newFolder("history").toPath();
        writeToday(history, true);
        writeYesterday(history);
        build(history, true, false);
        show("Characters", 1240, 800, 13, SIMPLE, () -> shell.select("characters"));
        SwingUtilities.invokeAndWait(() -> {
            assertFalse("Simple hides Fame history", characterTabs().visibleIds().contains(CharacterPanelGUI.FAME_HISTORY));
            assertFalse("…and the page cannot bring it forward", characters().showFameHistory());
        });
        show("Characters fame history", 1240, 800, 13, ANALYST, () -> assertTrue(characters().showFameHistory()));
        await("the saved fame", () -> fameWorkspace() != null && ArchiveNativeSupport.ready(fameWorkspace()) && fameWorkspace().displayedPage() != null
            && VisualEvidence.named(fameWorkspace(), "loot-archive-table", JTable.class).getRowCount() > 0);
        pause();
        Runnable facts = () -> {
            assertEquals("characters", shell.selectedPage());
            assertEquals(CharacterPanelGUI.FAME_HISTORY, characterTabs().selectedId());
            assertTrue("Analyst shows Fame history after Pets", characterTabs().visibleIds().indexOf(CharacterPanelGUI.FAME_HISTORY) > characterTabs().visibleIds().indexOf("pets"));
            ArchiveWorkspace<?, ?, ?> fame = fameWorkspace();
            assertTrue("A saved-only workspace", fame.savedOnly() && fame.isShowing());
            assertEquals(CharacterFameHistory.HEADER, ((JTextComponent) VisualEvidence.named(shell, CharacterFameHistory.NAME + "-header", JComponent.class)).getText());
            AbstractButton file = VisualEvidence.named(shell, "character-fame-open-file", AbstractButton.class);
            assertEquals(CharacterFameHistory.OPEN_FILE, file.getText());
            assertTrue(inView(file));
            JTable table = VisualEvidence.named(fame, "loot-archive-table", JTable.class);
            assertEquals("Two characters today and one yesterday", 3, table.getRowCount());
        };
        capture("characters-fame-history", 1240, 13, ANALYST, "", () -> {
            facts.run();
            JTable table = VisualEvidence.named(fameWorkspace(), "loot-archive-table", JTable.class);
            assertTrue("The capture shows the table", inView(table));
            // Polish B7: the four compact columns fit, so Name takes the spare width instead of ending the table at about 60 %.
            assertEquals("The columns fill the table's viewport", table.getParent().getWidth(), table.getWidth());
        });
        show("Characters fame history compact", 680, 520, 18, ANALYST, () -> { });
        capture("characters-fame-history", 680, 18, ANALYST, "", () -> {
            facts.run();
            // At 680×520 font 18 the header, the filter row and the saved view's description and counts push the table below the
            // fold (a one-view workspace shows no view row): reachable by scrolling (checked after the capture).
            JTable table = VisualEvidence.named(fameWorkspace(), "loot-archive-table", JTable.class);
            VisualEvidence.reachable(table, table.getCellRect(0, 0, true));
        });
    }

    // ---- synthetic saved history ----

    /** Local wall-clock time on today + {@code dayOffset} in the test's zone. */
    private static long at(int dayOffset, int hour, int minute) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).plusDays(dayOffset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli();
    }

    /**
     * Today's closed session (11:40–14:20, runs a1–a4, fame of two characters) with eight bags:
     * - 12:05 White, Lost Halls (a1): the UT Voidblade (2 slots) and a Life potion;
     * - 12:10 Orange, Lost Halls (a1): the ST Aegis Robe (no slots) and the Tier Sword (3 slots: legendary, so enchanted);
     * - 12:40 Cyan, Ice Citadel (a2): the Frost Staff (2 slots: rare, enchanted), a Defense and a greater Life potion;
     * - 13:10 boosted white, Pirate Cave (a3): the UT Tidal Dagger (1 slot: uncommon);
     * - 13:30 Purple, Snake Pit (no run): the Viper Bow (no enchant data: never counted as enchanted or not) and a Mana potion;
     * - 13:50 Brown, no recorded area ("Unknown"; no run): a tonic (an other potion: counted, not notable);
     * - 14:00 Blue, Lost Halls (a4): an Attack and a Wisdom potion;
     * - 14:10 (with {@code legacy}) a legacy bag without a saved bag name, Lost Halls (a4): a plain ring (not a white bag; noted).
     */
    private static void writeToday(Path root, boolean legacy) throws Exception {
        HomeHistoryFixture.session(root, EARLIER, at(0, 11, 40), at(0, 14, 20));
        HomeHistoryFixture.runs(root, EARLIER, HomeHistoryFixture.visit("a1", "Lost Halls", at(0, 11, 45), at(0, 12, 15), true),
            HomeHistoryFixture.visit("a2", "Ice Citadel", at(0, 12, 20), at(0, 12, 50), true),
            HomeHistoryFixture.visit("a3", "Pirate Cave", at(0, 13, 0), at(0, 13, 15), true),
            HomeHistoryFixture.visit("a4", "Lost Halls", at(0, 13, 40), at(0, 14, 15), true));
        HomeHistoryFixture.loot(root, EARLIER, todayDrops(legacy).toArray());
        HomeHistoryFixture.fame(root, EARLIER, new AppHistory.FameSample(1, 12_000, at(0, 11, 45), "Wizard"), new AppHistory.FameSample(1, 12_450, at(0, 14, 15), "Wizard"),
            new AppHistory.FameSample(2, 3_000, at(0, 12, 20), "Knight"), new AppHistory.FameSample(2, 3_120, at(0, 13, 15), "Knight"));
    }

    /** Yesterday's closed session: a White bag with a UT (never in Today's counts) and the first character's fame. */
    private static void writeYesterday(Path root) throws Exception {
        HomeHistoryFixture.session(root, YESTERDAY, at(-1, 19, 30), at(-1, 22, 0));
        HomeHistoryFixture.loot(root, YESTERDAY, drop("White", "Lost Halls", at(-1, 20, 15), new VisitRef(YESTERDAY, "y1"), equipment(9701, "EQUIPMENT,WEAPON,UT", 0)));
        HomeHistoryFixture.fame(root, YESTERDAY, new AppHistory.FameSample(1, 11_500, at(-1, 19, 40), "Wizard"), new AppHistory.FameSample(1, 12_000, at(-1, 21, 50), "Wizard"));
    }

    /** Today's eight bags ({@link #writeToday}); {@code legacy} adds the bag without a saved name (the live feed always names its bags). */
    private static List<Object> todayDrops(boolean legacy) throws Exception {
        List<Object> drops = new ArrayList<>(List.of(
            drop("White", "Lost Halls", at(0, 12, 5), A1, equipment(9101, "EQUIPMENT,WEAPON,UT", 2), potion(2793)),
            drop("Orange", "Lost Halls", at(0, 12, 10), A1, equipment(9102, "EQUIPMENT,ARMOR,ST", 0), equipment(9103, "EQUIPMENT,WEAPON,T13", 3)),
            drop("Cyan", "Ice Citadel", at(0, 12, 40), A2, equipment(9201, "EQUIPMENT,WEAPON,T13", 2), potion(2592), potion(9070)),
            drop("B.White", "Pirate Cave", at(0, 13, 10), A3, equipment(9301, "EQUIPMENT,WEAPON,UT", 1)),
            drop("Purple", "Snake Pit", at(0, 13, 30), null, equipment(9401, "EQUIPMENT,WEAPON,T13", null), potion(2794)),
            drop("Brown", "Unknown", at(0, 13, 50), null, potion(12_345)),
            drop("Blue", "Lost Halls", at(0, 14, 0), A4, potion(2591), potion(2613))));
        if (legacy) drops.add(drop(null, "Lost Halls", at(0, 14, 10), A4, equipment(9601, "EQUIPMENT,RING,T4", 0)));
        return drops;
    }

    /** This app run's live bags: today's seven named bags and this session's Orange bag (the ST Crystal Mail, 1 slot). */
    private static List<Object> liveDrops() throws Exception {
        List<Object> drops = todayDrops(false);
        drops.add(currentDrop());
        return drops;
    }

    /** The bag this app run's saved session holds: ten minutes ago, Ice Citadel, the ST Crystal Mail (1 slot); no run recorded. */
    private static Object currentDrop() throws Exception {
        return drop("Orange", "Ice Citadel", System.currentTimeMillis() - 10 * 60_000L, null, equipment(9501, "EQUIPMENT,ARMOR,ST", 1));
    }

    /** The asset label a synthetic item carries (potions are stat potions, the tonic too; equipment as its saved labels). */
    private static String label(int id) {
        if (id == 2793 || id == 9070 || id == 2794 || id == 2592 || id == 2591 || id == 2613 || id == 12_345) return "STATPOTION";
        if (id == 9101 || id == 9301 || id == 9701) return "EQUIPMENT,WEAPON,UT";
        if (id == 9102 || id == 9501) return "EQUIPMENT,ARMOR,ST";
        return id == 9601 ? "EQUIPMENT,RING,T4" : "EQUIPMENT,WEAPON,T13";
    }

    /** A saved item as capture builds it (the package-private {@code LootDashboard.Item}): {@code slots} null = no enchant data. */
    private static Object equipment(int id, String labels, Integer slots) throws Exception {
        Constructor<?> summary = ParseEnchants.Summary.class.getDeclaredConstructor(int.class, int.class);
        summary.setAccessible(true);
        ParseEnchants.Summary enchants = slots == null ? ParseEnchants.summarize(null) : (ParseEnchants.Summary) summary.newInstance(slots, 0);
        Constructor<?> item = Class.forName("tomato.gui.stats.LootDashboard$Item").getDeclaredConstructor(int.class, String.class, String.class, ParseEnchants.Summary.class);
        item.setAccessible(true);
        return item.newInstance(id, ASSETS.get(id), labels, enchants);
    }

    /** A potion as capture builds it (stat potions by their game IDs; the tonic has no stat). */
    private static Object potion(int id) throws Exception {
        Constructor<?> item = Class.forName("tomato.gui.stats.LootDashboard$Item").getDeclaredConstructor(int.class, String.class, boolean.class);
        item.setAccessible(true);
        return item.newInstance(id, ASSETS.get(id), true);
    }

    /** A bag as capture saves it (the package-private {@code LootDashboard.Drop}); {@code visit} its exact run, or null. */
    private static Object drop(String bag, String dungeon, long time, VisitRef visit, Object... items) throws Exception {
        Constructor<?> drop = Class.forName("tomato.gui.stats.LootDashboard$Drop").getDeclaredConstructor(String.class, String.class, String.class,
            long.class, List.class, String.class, DropContext.class);
        drop.setAccessible(true);
        return drop.newInstance(bag, dungeon, "Synthetic boss", time, Arrays.asList(items), visit == null ? "" : visit.visitId,
            visit == null ? null : DropContext.capture(null, time, visit));
    }

    /** Hands {@code drops} to the app's live feed off the EDT, as the capture thread does, then lets its views refresh. */
    private void feed(List<Object> drops) throws Exception {
        Method accept = LootDashboard.Feed.class.getDeclaredMethod("accept", Class.forName("tomato.gui.stats.LootDashboard$Drop"));
        accept.setAccessible(true);
        for (Object drop : drops) accept.invoke(LootCapture.get().feed(), drop);
        SwingUtilities.invokeAndWait(() -> { });
    }

    // ---- the workspace ----

    /**
     * The real workspace over {@code history} ({@code withStore}: a history store there, whose own current session saves this app
     * run's bag when {@code saveCurrent}; otherwise no store, so Highlights falls back to the live capture and Explore is the live
     * dashboard alone).
     */
    private void build(Path history, boolean withStore, boolean saveCurrent) throws Exception {
        store = withStore ? new SessionStore(history, true, "p6a-loot") : null;
        if (store != null && saveCurrent) { store.append("loot", currentDrop()); store.flush(); }
        remember(AppHistory.class, "store", store);
        journal = new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"));
        TomatoData data = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
        SwingUtilities.invokeAndWait(() -> {
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
            padded = new JPanel(new BorderLayout());
            padded.setBorder(new EmptyBorder(BAND, EDGE, EDGE, EDGE));
            padded.add(shell, BorderLayout.CENTER);
        });
    }

    /** Loot › Highlights through the navigator (as Home's Notable loot tile opens it), settled on its read. */
    private void openHighlights() throws Exception {
        show("Loot highlights", 1240, 800, 13, SIMPLE, () -> assertTrue(Navigator.current().open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS)))));
        await("the highlights", () -> "content".equals(call(highlights(), "state")) && !(Boolean) call(highlights(), "loading"));
        pause();
    }

    /** Shows the padded shell so the shell itself is {@code width}×{@code height}, at the font and mode; runs {@code navigate}; settles. */
    private void show(String title, int width, int height, int font, DisplayModeModel.Mode mode, Runnable navigate) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(padded, title, width + 2 * EDGE, height + BAND + EDGE, font);
            navigate.run();
        });
        pause();
    }

    /**
     * The light theme on the shown workspace, switched live as Settings › Appearance does (install, then every window's tree), fonts
     * reapplied at the same size; Simple. Each test takes its light capture last ({@link #close} installs the dark theme again).
     */
    private void light(int width, int height, int font) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(SIMPLE);
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(SwingUtilities.getWindowAncestor(shell));
            evidence.show(padded, "Light theme", width + 2 * EDGE, height + BAND + EDGE, font);
        });
        pause();
    }

    /** Scrolls the page that holds {@code part} so that it starts {@code above} px below the top of the viewport (as far as it scrolls). */
    private static void scrollTo(JComponent part, int above) {
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, part);
        assertNotNull("A scrolling page holds " + part.getName(), viewport);
        Component view = viewport.getView();
        int y = SwingUtilities.convertPoint(part.getParent(), part.getLocation(), view).y - above;
        viewport.setViewPosition(new Point(0, Math.max(0, Math.min(y, view.getHeight() - viewport.getHeight()))));
    }

    private void capture(String state, int width, int font, DisplayModeModel.Mode mode, String suffix, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String name = "p6a-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT) + suffix;
            evidence.capture(name);
            int height = width == 1240 ? 800 : 520;
            errors.checkSucceeds(() -> {
                assertEquals(name + ": the shell is exactly " + width + "×" + height + " (padded clear of the title band)", new Dimension(width, height), shell.getSize());
                return null;
            });
            errors.checkSucceeds(() -> { nothingSideways(shell, name); return null; });
            errors.checkSucceeds(() -> { check.run(); return null; });
        });
    }

    // ---- assertions ----

    /** Loot, on Highlights, with the strip Highlights · Explore in view. */
    private void highlightsPage() {
        assertEquals("loot", shell.selectedPage());
        LootPage page = VisualEvidence.named(shell, "loot-page", LootPage.class);
        assertEquals(LootTab.HIGHLIGHTS, page.selectedTab());
        JTabbedPane tabs = VisualEvidence.named(shell, "loot-tabs", JTabbedPane.class);
        assertEquals("The Loot tab strip", List.of("Highlights", "Explore"), titles(tabs));
        assertTrue("The capture shows the strip", inView(tabs));
        assertTrue("The capture shows the tiles", inView(VisualEvidence.named(shell, "loot-tile-ut", StatTile.class)));
    }

    /** Explore in front, live or saved. */
    private void assertExplore(boolean saved) {
        assertEquals("loot", shell.selectedPage());
        assertEquals(LootTab.EXPLORE, VisualEvidence.named(shell, "loot-page", LootPage.class).selectedTab());
        assertEquals(saved ? "Saved history" : "Live", saved, workspace().state().archive);
        assertTrue(workspace().isShowing());
        assertTrue(saved ? "The view selector is in view in the saved row" : "The view selector is in view in the live row", inView(selector())
            && SwingUtilities.isDescendingFrom(selector(), saved ? workspace().filterBar() : VisualEvidence.named(shell, "loot-live-filter-bar", FilterBar.class)));
    }

    /** S7 in the real shell at desktop width: the six core rows, and no Statistics, DPS Logger or Build page row. */
    private void assertSidebar() {
        assertEquals("Six core rows: Home, Characters, Runs & DPS, Loot, Quests, Chat", CORE, TestPages.listedRows(shell));
        for (String retired : new String[] {"nav-statistics", "nav-dps-logger", "nav-my-info"})
            assertNull(retired + " is gone", search(shell, AbstractButton.class, b -> retired.equals(b.getName())));
        assertTrue("Loot's row is selected", VisualEvidence.named(shell, "nav-" + shell.selectedPage(), AbstractButton.class).isShowing());
    }

    /** Settings shows {@code section}: six sections in the spec's order, the section's content in view. */
    private void assertSettings(String section, String content) {
        assertEquals("settings", shell.selectedPage());
        SettingsPage page = VisualEvidence.named(shell, "settings-page", SettingsPage.class);
        assertEquals(section, page.currentSection());
        List<String> titles = new ArrayList<>();
        for (String id : new String[] {SettingsPage.NOTIFICATIONS, SettingsPage.GENERAL, SettingsPage.APPEARANCE, SettingsPage.LOOT_FILTERS, SettingsPage.CHAT, SettingsPage.ABOUT}) {
            AbstractButton button = VisualEvidence.named(shell, "settings-section-" + id, AbstractButton.class);
            titles.add(button.getText());
            assertTrue("Every section stays reachable: " + id, button.isShowing());
        }
        assertEquals(List.of("Notifications", "General", "Appearance", "Loot filters", "Chat", "About"), titles);
        assertTrue(VisualEvidence.named(shell, "settings-section-" + section, AbstractButton.class).isSelected());
        assertTrue("The capture shows " + content, inView(VisualEvidence.named(shell, content, JComponent.class)));
    }

    private void assertTile(String name, String text, DisplayValue.State state) {
        StatTile tile = VisualEvidence.named(shell, name, StatTile.class);
        assertEquals(name, text, tile.value().text());
        assertTrue(name + " shows " + tile.valueText(), tile.valueText().startsWith(text));   // "2 (partial)" for a partial count
        assertEquals(name + " state", state, tile.value().state);
        assertTrue(name + ": " + tile.value().tooltip(), String.valueOf(tile.value().tooltip()).contains(HighlightsModel.OBSERVED) || state != DisplayValue.State.KNOWN);
    }

    /**
     * The five tiles are whole (inside the page, never clipped) in at most three rows; the row count is printed. Since Polish B2 a
     * long potion or white-bag sub-line wraps inside its tile instead of widening it, so at 1240×800 font 13 in the real shell the
     * tiles share one row ({@link #assertTilesInOneRow}; finding 1).
     */
    private void assertTilesWhole(String capture) {
        Set<Integer> rows = new HashSet<>();
        JScrollPane page = VisualEvidence.named(shell, "loot-highlights-scroll", JScrollPane.class);
        for (String name : new String[] {"loot-tile-ut", "loot-tile-st", "loot-tile-potions", "loot-tile-whites", "loot-tile-enchanted"}) {
            StatTile tile = VisualEvidence.named(shell, name, StatTile.class);
            Rectangle bounds = SwingUtilities.convertRectangle(tile.getParent(), tile.getBounds(), page);
            assertTrue(name + " lies inside the page: " + bounds, bounds.x >= 0 && bounds.x + bounds.width <= page.getWidth());
            rows.add(bounds.y);
        }
        System.out.println(capture + ": Loot highlights tiles in " + rows.size() + " row(s)");
        assertTrue("Five tiles fit in at most three rows: " + rows.size(), rows.size() <= 3);
    }

    /**
     * Polish B2 (finding 1): at 1240×800 font 13 in the real shell the five tiles share one row and one height, even with the long
     * potion and white-bag sub-lines ("2 Life · 1 Mana · 1 Att · 1 Def · +2 more", "of 9 bags · 1 without a bag name").
     */
    private void assertTilesInOneRow() {
        List<StatTile> tiles = tiles();
        assertEquals(5, tiles.size());
        assertEquals("Five tiles in one row: " + tileBounds(tiles), 1, tiles.stream().map(Component::getY).distinct().count());
        assertEquals("One height for the row: " + tileBounds(tiles), 1, tiles.stream().map(Component::getHeight).distinct().count());
        for (StatTile tile : tiles) assertSubLineWhole(tile);
    }

    /** At 680×520 font 18 the tiles wrap (two by two, or one per row), each whole inside the page; the row count is printed. */
    private void assertTilesWrapped(String capture) {
        assertTilesWhole(capture);
        List<StatTile> tiles = tiles();
        assertTrue("The tiles wrap at 680×520 font 18: " + tileBounds(tiles), tiles.stream().map(Component::getY).distinct().count() > 1);
        for (StatTile tile : tiles) assertSubLineWhole(tile);
    }

    /**
     * A tile's sub-line (its child 2, under the value; hidden when the tile has none) is whole: inside the tile, and the capture
     * painted every word of it (its lines rejoin to the text, none cut with "…"), or, only if a word was cut, its tooltip is the
     * whole text. The line count is printed.
     */
    private static void assertSubLineWhole(StatTile tile) {
        JLabel sub = (JLabel) tile.getComponent(2);
        if (!sub.isVisible()) return;
        @SuppressWarnings("unchecked") List<String> painted = (List<String>) call(sub, "painted");
        String text = sub.getText();
        System.out.println(tile.getName() + " sub-line in " + painted.size() + " line(s) at " + sub.getWidth() + " px: " + painted);
        assertTrue(tile.getName() + ": the sub-line lies inside the tile: " + sub.getBounds() + " in " + tile.getSize(), new Rectangle(tile.getSize()).contains(sub.getBounds()));
        boolean whole = painted.stream().noneMatch(line -> line.endsWith("…")) && words(String.join(" ", painted)).equals(words(text));
        assertTrue(tile.getName() + ": painted " + painted + " for '" + text + "', tooltip " + sub.getToolTipText(), whole || text.equals(sub.getToolTipText()));
    }

    private static List<String> words(String text) { return Arrays.asList(text.replace("·", " ").trim().split("\\s+")); }

    private List<StatTile> tiles() {
        List<StatTile> tiles = new ArrayList<>();
        for (String name : new String[] {"loot-tile-ut", "loot-tile-st", "loot-tile-potions", "loot-tile-whites", "loot-tile-enchanted"}) tiles.add(VisualEvidence.named(shell, name, StatTile.class));
        return tiles;
    }

    private static String tileBounds(List<StatTile> tiles) {
        StringBuilder text = new StringBuilder();
        for (StatTile tile : tiles) text.append(tile.getName()).append('@').append(tile.getX()).append(',').append(tile.getY())
            .append(' ').append(tile.getWidth()).append('x').append(tile.getHeight()).append("; ");
        return text.toString();
    }

    /**
     * Each named component is whole in view as the page opened (nothing scrolled first): its visible rectangle is its whole bounds,
     * through every enclosing scroll pane and the shell, and it lies inside the Settings page.
     */
    private void assertInViewWithoutScrolling(String... names) {
        SettingsPage page = VisualEvidence.named(shell, "settings-page", SettingsPage.class);
        for (String name : names) {
            JComponent part = VisualEvidence.named(shell, name, JComponent.class);
            Rectangle visible = part.getVisibleRect(), inPage = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), page);
            System.out.println("Settings chat: " + name + " at " + inPage + " in a " + page.getSize() + " settings page, visible " + visible);
            assertTrue(name + " is showing", part.isShowing() && part.getWidth() > 0 && part.getHeight() > 0);
            assertEquals(name + " is whole in view without scrolling (the visible part of " + part.getSize() + ")",
                new Rectangle(0, 0, part.getWidth(), part.getHeight()), visible);
            assertTrue(name + " lies inside the Settings page: " + inPage, new Rectangle(page.getSize()).contains(inPage));
        }
    }

    /** The sub-line of the StatTile named {@code name} (its second text line). */
    private String subline(String name) {
        StatTile tile = VisualEvidence.named(shell, name, StatTile.class);
        List<String> texts = new ArrayList<>();
        collect(tile, texts);
        texts.removeIf(t -> t == null || t.isEmpty() || t.equals(tile.valueText()));
        return texts.isEmpty() ? "" : texts.get(texts.size() - 1);
    }

    private static Component searchSlot(FilterBar bar) {
        return VisualEvidence.named(bar, bar.getName().replace("-filter-bar", "-filters"), AbstractButton.class).getParent().getComponent(0);
    }

    // ---- lookups ----

    private LootHighlights highlights() { return VisualEvidence.named(shell, "loot-highlights", LootHighlights.class); }
    @SuppressWarnings("unchecked") private ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> workspace() {
        return VisualEvidence.named(shell, "loot-session-view", ArchiveWorkspace.class);
    }
    /** Explore's one view selector ({@code loot-views}), leading the filter row shown, live or saved (P6b). */
    private JComboBox<?> selector() { return VisualEvidence.named(shell, "loot-views", JComboBox.class); }
    private JLabel caption() { return VisualEvidence.named(shell, "loot-archive-view-caption", JLabel.class); }
    private JTable liveTable(int index) { return VisualEvidence.named(shell, "loot-view-" + index, JTable.class); }
    private JTable savedTable() { return VisualEvidence.named(workspace(), "loot-archive-table", JTable.class); }
    private JList<?> list(String name) { return VisualEvidence.named(shell, name, JList.class); }
    private CharacterPanelGUI characters() { return VisualEvidence.find(shell, CharacterPanelGUI.class, c -> true); }
    private CustomizableTabs characterTabs() { return field(characters(), "tabs", CustomizableTabs.class); }
    private ArchiveWorkspace<?, ?, ?> fameWorkspace() {
        return search(shell, ArchiveWorkspace.class, w -> (CharacterFameHistory.NAME + "-session-view").equals(w.getName()));
    }
    /** Explore's content: the Loot workspace, or the live dashboard alone without saved history. */
    private JComponent exploreContent() {
        for (Component content : VisualEvidence.named(shell, "loot-page", LootPage.class).tabs().contents())
            if (!(content instanceof LootHighlights)) return (JComponent) content;
        throw new AssertionError("No Explore content");
    }

    /** The selector's rows as its renderer shows them: header rows in brackets. */
    private static List<String> rows(JComboBox<?> selector) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < selector.getItemCount(); i++) {
            Object item = selector.getItemAt(i);
            @SuppressWarnings({"unchecked", "rawtypes"}) Component shown = ((ListCellRenderer) selector.getRenderer()).getListCellRendererComponent(new JList<>(), item, i, false, false);
            String text = ((JLabel) shown).getText();
            rows.add(item instanceof LootQuery.View ? text : "[" + text + "]");
        }
        return rows;
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

    /** The text of the component named {@code name}: a text area, label or button. */
    private String text(String name) {
        Component component = VisualEvidence.named(shell, name, JComponent.class);
        if (component instanceof JTextComponent) return ((JTextComponent) component).getText();
        if (component instanceof AbstractButton) return ((AbstractButton) component).getText();
        return ((JLabel) component).getText();
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

    /** What a screen reader announces for each tile (the painted text is not in the component tree). */
    private static List<String> names(JList<?> tiles) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tiles.getModel().getSize(); i++)
            names.add(tiles.getAccessibleContext().getAccessibleChild(i).getAccessibleContext().getAccessibleName());
        return names;
    }

    /** Whether some of {@code part} is inside the visible part of its scroll pane (the capture shows it). */
    private static boolean inView(Component part) { return part != null && part.isShowing() && ((JComponent) part).getVisibleRect().height > 0; }

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

    /**
     * No page scrolls or cuts its content sideways: every showing scroll pane has no horizontal scroll bar and a view no wider
     * than its viewport. A data table scrolls its own columns sideways by design: it is listed on standard output instead.
     */
    private static void nothingSideways(Container root, String capture) {
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane && child.isShowing()) {
                JScrollPane scroll = (JScrollPane) child;
                Component view = scroll.getViewport().getView();
                String where = scroll.getName() != null ? scroll.getName() : view == null ? "an empty scroll pane" : view.getClass().getSimpleName();
                if (view instanceof JTable) System.out.println(capture + ": table " + where + " " + view.getWidth() + " px in a "
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
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        if (!statics.containsKey(field)) statics.put(field, field.get(null));
        field.set(null, next);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** The owner's own package-private no-argument method (a view's test accessor, such as Loot highlights' {@code state()}). */
    private static Object call(Object owner, String name) {
        try { Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Properties properties() {
        try {
            Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
            Properties copy = new Properties(); copy.putAll((Properties) field.get(null)); return copy;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
