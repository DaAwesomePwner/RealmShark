package ui;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.DamageSource;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.character.CharacterPetsGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.CombatSummaries;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.kit.*;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import tomato.gui.runs.RunOutcome;
import tomato.gui.runs.RunRecapBuilder;
import tomato.gui.runs.RunRecapModel;
import tomato.gui.runs.RunRecapView;
import tomato.gui.runs.RunsPage;
import tomato.gui.settings.SettingsPage;
import tomato.gui.stats.LootTestDrops;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFixtures;
import tomato.history.encounter.CombatSettings;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.item;

/**
 * P5a Runs evidence (spec §6.3, §6.7, §8.4, §11) in the real workspace over synthetic saved history in the test's own history
 * folder (the app's store is pointed there and restored after): four sessions of synthetic runs with loot bags, visit-tagged fame
 * readings of a synthetic account, timeline events, inspected players and combat summaries built by {@link CombatSummaries} from
 * synthetic fights (players Alpha…Hotel; the capture's own character is Bravo, a Wizard). Preview mode; no capture, no
 * personal data. The JVM's default time zone is set, for the test only, to a fixed offset at which it is mid-afternoon, so
 * "Today" and "Yesterday" hold the fixture's runs whenever the test runs; preferences (the Runs view, the feed's drawer, the
 * recap's sections, Combat history, the display mode and the archive workspaces' keys), the zone and the format locale are
 * restored after each test. Task 9 (the recap route) is not on this base: the recap is built with {@link RunRecapBuilder} off
 * the EDT and shown in the Runs page's recap slot ({@link RunsPage#setRecap}, {@link RunRecapView#show}, {@link RunsPage#showRecap}).
 * 19 screenshots in {@code redesign-p5a-runs}:
 * - Feed: Today (1240×800 font 13 Simple; 680×520 font 18 Analyst), Yesterday and an older day, the Table view (all sessions),
 *   the empty feed.
 * - Recap of a linked run with two recordings: the top (header, tiles, chart) at both sizes, the meter and damage by source at both
 *   sizes, Analyst's chart with one line per player, the recording picker open (its list drawn over the window), the second
 *   recording chosen, Loot and Players, Resources and Timeline, Analyst Evidence; a run without a recording; a run whose local
 *   row was not verified.
 * - Settings › General › Combat history at both sizes.
 * Every capture asserts that nothing scrolls or is cut off sideways (a data table that scrolls its own columns is listed on
 * standard output instead, as in P3b/P4) and the content it is evidence of, so a screenshot is never a pass merely because it
 * was written.
 */
public class RunsEvidenceTest {
    private static final String[] SECTIONS = {RunRecapView.DAMAGE, RunRecapView.LOOT, RunRecapView.PLAYERS, RunRecapView.RESOURCES,
        RunRecapView.TIMELINE, RunRecapView.EVIDENCE};
    private static final Map<String, String> DEFAULTS = defaults();
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    /** Synthetic player names by object ID 1…8; the capture's own character is always Bravo (object ID 2, a Wizard). */
    private static final String[] NAMES = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel"};
    private static final int WIZARD = 782, LOCAL = 1;
    private static final String TODAY = HomeHistoryFixture.id("p5a-evidence-today"), CRASHED = HomeHistoryFixture.id("p5a-evidence-crashed"),
        YESTERDAY = HomeHistoryFixture.id("p5a-evidence-yesterday"), OLDER = HomeHistoryFixture.id("p5a-evidence-older");
    /** Today: a1 linked (two recordings, #2 of 6), a2 unverified local row, a3 no recording, a4 linked (#1 of 4); b1 App ended. */
    private static final VisitRef A1 = new VisitRef(TODAY, "a1"), A2 = new VisitRef(TODAY, "a2"), A3 = new VisitRef(TODAY, "a3"),
        A4 = new VisitRef(TODAY, "a4"), B1 = new VisitRef(CRASHED, "b1");
    private static final long MINUTE = 60_000L;
    private static final Pattern LINKED = Pattern.compile(".*; your DPS [0-9.]+k?, rank 2 of 6, [0-9.]+% of the damage, from the longest of 2 recordings; .*");
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p5a-runs");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> saved = new HashMap<>(), archive = new HashMap<>();
    /** The Characters roster's saved view goes here, not to the shared realmShark.properties (P3a finding 10). */
    private final tomato.gui.roster.RosterStateTestSupport.Memory views = new tomato.gui.roster.RosterStateTestSupport.Memory();
    /** The recording id the recap's picker asked for (the test rebuilds the model with it, as the route will). */
    private final AtomicReference<String> requested = new AtomicReference<>();
    private DisplayModeModel.Mode savedMode;
    private TimeZone savedZone;
    private Locale savedLocale;
    private String temporaryDirectory;
    private SessionStore store;
    private CharacterJournal journal;
    private TomatoData data;
    private TomatoGUI gui;
    private WorkspaceShell shell;
    private RunRecapView recap;

    private static Map<String, String> defaults() {
        Map<String, String> keys = new LinkedHashMap<>(Map.of("chat.filters", "{}", "chat.showIgnoredPlayers", "false", RunFeedView.VIEW_KEY, "",
            "ui.filters.run-feed.open", "", CombatSettings.KEEP_FULL_DETAIL, "", CombatSettings.FULL_DETAIL_DAYS, "", CombatSettings.SUMMARY_RETENTION, ""));
        for (String id : SECTIONS) keys.put(Collapsible.PREFIX + "run-recap-" + id, "");
        return Collections.unmodifiableMap(keys);
    }

    @Before public void open() throws Exception {
        PropertiesManager.preload(); // merge the disk file first, so the archive keys snapshot below sees every saved key
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            saved.put(entry.getKey(), PropertiesManager.getProperty(entry.getKey()));
            PropertiesManager.setProperties(entry.getKey(), entry.getValue());
        }
        for (String key : archiveKeys()) { archive.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        savedZone = TimeZone.getDefault();
        savedLocale = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone.setDefault(TimeZone.getTimeZone(afternoon(System.currentTimeMillis())));
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        remember(Tomato.class, "preview", true);
        remember(TomatoGUI.class, "characterViewStates", views.store);
        for (Class<?> type : new Class<?>[] {TomatoGUI.class, ChatGUI.class, CharacterPetsGUI.class})
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && !statics.containsKey(field)) {
                    field.setAccessible(true); statics.put(field, field.get(null));
                }
    }

    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            evidence.closeWindow();
            if (gui != null) gui.closeWorkspace();
            DisplayModeModel.application().set(savedMode);
            try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        if (journal != null) journal.close();
        for (Map.Entry<String, String> entry : saved.entrySet())
            PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? DEFAULTS.get(entry.getKey()) : entry.getValue());
        for (String key : archiveKeys()) { String value = archive.get(key); PropertiesManager.setProperties(key, value == null ? "" : value); }
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (savedZone != null) TimeZone.setDefault(savedZone);
        if (savedLocale != null) Locale.setDefault(Locale.Category.FORMAT, savedLocale);
        if (store != null) store.close();
    }

    /**
     * 4 captures of the feed: Today at both sizes (App ended, a linked run with your DPS, rank and share from the longest of two
     * recordings, an unverified local row, a run without a recording, loot strips, fame, deaths and exalt progress), Yesterday and
     * an older day below it, and the Table view over all sessions.
     */
    @Test public void theFeedGroupsSavedRunsByDayWithTheirFactsAndKeepsTheTableView() throws Exception {
        Path history = temp.newFolder("history").toPath();
        write(history);
        build(history);
        LocalDate today = LocalDate.now(), yesterday = today.minusDays(1), older = today.minusDays(3);
        Runnable todayCards = () -> {
            assertEquals("Today", header(today));
            assertTrue(counts(today), counts(today).startsWith("5 runs · 3 completed · "));
            assertEquals("Every saved dungeon run of every session, newest first", "Saved runs · newest first · 11 runs", text("run-feed-summary"));
            List<String> cards = names(day(today));
            assertEquals(cards.toString(), 5, cards.size());
            assertTrue(cards.get(0), cards.get(0).startsWith("Lost Halls, App ended; entered today at 14:25; 20 m observed; party not observed; "
                + "no combat recording is linked to this run; 2 loot items"));
            assertTrue("A crashed session's run keeps its exactly linked fame: " + cards.get(0), cards.get(0).contains("; 110 fame gained;"));
            assertTrue(cards.get(1), cards.get(1).startsWith("Lost Halls, Completed; entered today at 13:20; 26 m observed; party of 6; your DPS "));
            assertTrue("Your DPS, rank and share from the longest recording: " + cards.get(1), LINKED.matcher(cards.get(1)).matches());
            assertTrue(cards.get(1), cards.get(1).endsWith("; 10 loot items: 1 UT · 1 ST · 3 potions; 510 fame gained; 1 death; exalt progress +1"));
            assertTrue(cards.get(2), cards.get(2).startsWith("Snake Pit, Left; entered today at 12:50; 12 m observed; party of 3; "
                + "the local player's row was not verified for this encounter; another player's row is never substituted; 1 loot item"));
            assertTrue("Unknown fame is said, never 0: " + cards.get(2), cards.get(2).contains("; fame gained unknown;"));
            assertTrue(cards.get(3), cards.get(3).startsWith("Pirate Cave, Completed; entered today at 12:35; 9 m observed; party not observed; "
                + "no combat recording is linked to this run; 3 loot items"));
            assertTrue(cards.get(3), cards.get(3).contains("; 120 fame gained;"));
            assertTrue(cards.get(4), cards.get(4).startsWith("Ice Citadel, Completed; entered today at 12:00; 28 m observed; party of 4; your DPS "));
            assertTrue(cards.get(4), cards.get(4).contains(", rank 1 of 4, ") && cards.get(4).contains("; 310 fame gained; 0 deaths;"));
            assertTrue("The capture shows Today's cards", inView(day(today)));
        };
        feed("feed", 1240, 800, 13, SIMPLE, null, () -> {
            todayCards.run();
            assertFalse("Simple: no Cards/Table toggle", shown(shell, "run-feed-view-row") != null);
            assertEquals("Table view", VisualEvidence.named(shell, "run-feed-filter-bar", FilterBar.class).overflow().item("Table view").getText());
        });
        feed("feed", 680, 520, 18, ANALYST, today, () -> {
            todayCards.run();
            assertNotNull("Analyst: the Cards/Table toggle", shown(shell, "run-feed-view-row"));
        });
        feed("feed-yesterday", 1240, 800, 13, SIMPLE, yesterday, () -> {
            assertEquals("Yesterday", header(yesterday));
            assertTrue(counts(yesterday), counts(yesterday).startsWith("4 runs · 3 completed · "));
            List<String> cards = names(day(yesterday));
            assertEquals(cards.toString(), 4, cards.size());
            assertTrue(cards.get(0), cards.get(0).startsWith("Ice Citadel, Completed; entered yesterday at 22:10;") && cards.get(0).contains(", rank 2 of 5, "));
            assertTrue("A known none says so: " + cards.get(1), cards.get(1).startsWith("Pirate Cave, Left; entered yesterday at 21:00;")
                && cards.get(1).contains("; no loot recorded in this run; fame gained unknown;"));
            assertTrue(cards.get(3), cards.get(3).startsWith("Lost Halls, Completed; entered yesterday at 19:40;")
                && cards.get(3).contains(", rank 3 of 8, ") && cards.get(3).endsWith("; 900 fame gained; 0 deaths; exalt progress +2"));
            String title = (older.getYear() == today.getYear() ? DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
                : DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)).format(older);
            assertEquals("An older day is titled by its weekday and date", title, header(older));
            assertEquals(2, day(older).getModel().getSize());
            assertTrue("The capture shows Yesterday's cards", inView(day(yesterday)));
        });
        SwingUtilities.invokeAndWait(() -> {
            VisualEvidence.named(shell, "run-feed-filter-bar", FilterBar.class).overflow().item("Table view").doClick();
            workspace().selectSession(SessionStore.ALL);   // the scope picker's All Sessions
        });
        await("the Runs table", () -> !workspace().loading() && workspace().displayedPage() != null && workspace().displayedPage().matches == 11);
        feed("feed-table", 1240, 800, 13, SIMPLE, null, () -> {
            assertTrue("The Table view shows", page().feed().tableShown());
            assertNull("…and the cards are hidden", shown(shell, "run-feed-scroll"));
            JTable table = VisualEvidence.find(workspace(), JTable.class, JTable::isShowing);
            assertEquals("Every saved dungeon run is a row", 11, table.getRowCount());
            assertEquals("Simple: the table's ⋯ offers the cards back", "Cards view", workspace().filterBar().overflow().item("Cards view").getText());
        });
        SwingUtilities.invokeAndWait(() -> workspace().filterBar().overflow().item("Cards view").doClick());
    }

    /** 1 capture: no saved runs yet (the current session has none): one empty state says so, in place of the summary line. */
    @Test public void anEmptyFeedSaysRunsAppearOnceSaved() throws Exception {
        build(temp.newFolder("history").toPath());
        feed("feed-empty", 1240, 800, 13, SIMPLE, null, () -> {
            assertNull("The empty state says it once: no summary line above it", shown(shell, "run-feed-summary"));
            EmptyState empty = VisualEvidence.named(shell, "run-feed-empty", EmptyState.class);
            assertTrue("The feed's empty state shows", empty.isShowing());
            assertTrue(showsText(empty, "No saved runs yet"));
            assertTrue(showsText(empty, "about every 10 s while capture is on"));
            assertNull("No day sections", shown(shell, "run-feed-days"));
            assertNull("No Load more", shown(shell, "run-feed-load-more"));
        });
    }

    /**
     * 12 captures of the recap: the linked run with two recordings (the top, the meter and damage by source, the picker open, the
     * second recording, Loot and Players, Resources and Timeline; compact Analyst: the top, the chart's lines, the meter, Evidence),
     * a run without a recording and a run whose local row was not verified.
     */
    @Test public void theRecapShowsTilesDamageLootPlayersResourcesTimelineAndEvidence() throws Exception {
        Path history = temp.newFolder("history").toPath();
        write(history);
        build(history);
        RunRecapModel linked = model(A1, null);
        String longest = linked.damage().selected(), shorter = linked.damage().recordings().get(1).id();
        Runnable top = () -> {
            assertEquals("Lost Halls", text("run-recap-map"));
            assertEquals("Completed", VisualEvidence.named(shell, "run-recap-outcome", Chip.class).getText());
            assertEquals("The card's observed span", "26 m observed", text("run-recap-duration"));
            assertEquals("Party 6", text("run-recap-party"));
            assertEquals("From the fame readings tagged with this run", "Wizard #1", text("run-recap-character"));
            DisplayValue dps = tile(RunRecapModel.Tile.DPS);
            assertEquals(DisplayValue.State.KNOWN, dps.state);
            assertEquals("Share, deaths, fame, loot and exalt progress from exact links", List.of("#2 of 6", "1", "+510", "10", "+1"),
                List.of(sub(RunRecapModel.Tile.DPS), tile(RunRecapModel.Tile.DEATHS).text(), tile(RunRecapModel.Tile.FAME).text(),
                    tile(RunRecapModel.Tile.LOOT).text(), tile(RunRecapModel.Tile.EXALT).text()));
            assertTrue(tile(RunRecapModel.Tile.SHARE).text(), tile(RunRecapModel.Tile.SHARE).text().endsWith("%"));
            assertTrue("Damage opens expanded", section(RunRecapView.DAMAGE).expanded());
            assertTrue("The chart draws the recording", chart().isShowing() && chart().enoughData());
            assertTrue("Two recordings: the picker shows", picker().isShowing());
            assertEquals(2, picker().getItemCount());
        };
        recap("recap", 1240, 800, 13, SIMPLE, linked, null, () -> {
            top.run();
            assertTrue("The capture shows the tiles", inView(VisualEvidence.named(shell, "run-recap-tiles", JComponent.class)));
            assertTrue("…and the chart", inView(chart()));
            assertNull("Evidence is Analyst-only", shown(shell, "run-recap-evidence"));
        });
        Runnable meter = () -> {
            JTable table = VisualEvidence.named(shell, "run-recap-meter", JTable.class);
            assertEquals("Every contributor of the longest recording", 6, table.getRowCount());
            assertEquals("Your row's damage by source shows first", "Damage by source · Bravo (you)", text("run-recap-sources-title"));
            assertTrue("Sources are listed", count(shell, "run-recap-source") >= 2);
            assertTrue("The capture shows the meter", inView(table));
        };
        recap("recap-damage", 1240, 800, 13, SIMPLE, linked, "run-recap-chart", meter);
        SwingUtilities.invokeAndWait(() -> scrollToTop(VisualEvidence.named(shell, "run-recap-scroll", JScrollPane.class), section(RunRecapView.DAMAGE)));
        pause();
        SwingUtilities.invokeAndWait(() -> picker().showPopup());
        pause();
        // The open list is a popup of its own (not printed with the window), so it is drawn over the capture where it shows.
        capture("recap-picker", 1240, 13, SIMPLE, (JComponent) picker().getUI().getAccessibleChild(picker(), 0), () -> {
            assertTrue("The capture shows the picker", inView(picker()));
            assertTrue("The picker's list is open", picker().isPopupVisible());
            List<RunRecapModel.Damage.Recording> recordings = linked.damage().recordings();
            assertEquals("Longest first, each with its hit window and contributors",
                List.of("Recording 1 of 2 · longest · " + seconds(recordings.get(0).windowSeconds()) + " s window · 6 players",
                    "Recording 2 of 2 · " + seconds(recordings.get(1).windowSeconds()) + " s window · 4 players"), pickerLabels());
        });
        SwingUtilities.invokeAndWait(() -> { picker().hidePopup(); picker().setSelectedIndex(1); });
        await("the picker's request", () -> requested.get() != null);
        assertEquals("Choosing the second recording asks for it", shorter, requested.get());
        RunRecapModel second = model(A1, requested.getAndSet(null));
        recap("recap-recording-2", 1240, 800, 13, SIMPLE, second, "run-recap-damage", () -> {
            assertEquals(shorter, second.damage().selected());
            assertEquals(1, picker().getSelectedIndex());
            assertEquals("The Damage section shows the chosen recording", 4, VisualEvidence.named(shell, "run-recap-meter", JTable.class).getRowCount());
            assertEquals("Tiles keep the longest recording", "#2 of 6", sub(RunRecapModel.Tile.DPS));
            assertTrue(text("run-recap-damage-summary"), text("run-recap-damage-summary").contains(" · " + seconds(second.damage().windowSeconds())
                + " s first-to-last hit window · 4 players"));
        });
        assertNotEquals(longest, shorter);
        SwingUtilities.invokeAndWait(() -> {
            for (String id : new String[] {RunRecapView.PLAYERS, RunRecapView.RESOURCES, RunRecapView.TIMELINE})
                VisualEvidence.named(shell, "collapsible-run-recap-" + id, AbstractButton.class).doClick();   // as the user opens them
        });
        Thread.sleep(Motion.MAX_MILLIS + 50);
        recap("recap-loot-players", 1240, 800, 13, SIMPLE, linked, "run-recap-loot", () -> {
            assertTrue(section(RunRecapView.LOOT).expanded() && section(RunRecapView.PLAYERS).expanded());
            assertEquals("Loot · 10 items", section(RunRecapView.LOOT).toggle().getText());
            tomato.gui.loot.haul.HaulView haul = VisualEvidence.named(VisualEvidence.named(shell, "run-recap-loot-bags", JComponent.class),
                "loot-haul", tomato.gui.loot.haul.HaulView.class);
            assertEquals("Three bags of this exact run", 3, haul.model().shelf().stream().mapToInt(shelf -> shelf.bags().size()).sum());
            assertEquals("Players · 6", section(RunRecapView.PLAYERS).toggle().getText());
            assertEquals("6 players seen in this run", text("run-recap-players-count"));
            assertEquals(6, count(shell, "run-recap-player"));
            assertTrue("The capture shows the bags", inView(VisualEvidence.named(shell, "run-recap-loot-bags", JComponent.class)));
        });
        recap("recap-resources-timeline", 1240, 800, 13, SIMPLE, linked, "run-recap-resources", () -> {
            assertTrue(section(RunRecapView.RESOURCES).expanded() && section(RunRecapView.TIMELINE).expanded());
            assertTrue("The resources chart shows", inView(VisualEvidence.named(shell, "run-recap-resources-chart", JComponent.class)));
            assertNull("…without a reason", shown(shell, "run-recap-resources-reason"));
            assertEquals("This run's events only", 5, VisualEvidence.named(shell, "run-recap-timeline-table", JTable.class).getRowCount());
        });
        recap("recap", 680, 520, 18, ANALYST, linked, null, () -> {
            top.run();
            assertTrue("The capture shows the tiles", inView(VisualEvidence.named(shell, "run-recap-tiles", JComponent.class)));
        });
        recap("recap-damage", 680, 520, 18, ANALYST, linked, "run-recap-chart", () -> {
            assertTrue("Analyst: one line per saved series", chart().lines().size() == 6 && inView(chart()));
            String described = chart().description();
            assertTrue("Each line's peak and total are described: " + described, described.startsWith("Damage per second over ")
                && described.contains(" Bravo (you): peak ") && described.contains(" Charlie: peak "));
        });
        recap("recap-meter", 680, 520, 18, ANALYST, linked, "run-recap-meter-scroll", meter);
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "collapsible-run-recap-evidence", AbstractButton.class).doClick());
        Thread.sleep(Motion.MAX_MILLIS + 50);
        recap("recap-evidence", 680, 520, 18, ANALYST, linked, "run-recap-evidence", () -> {
            assertTrue("Analyst: Evidence shows, opened", section(RunRecapView.EVIDENCE).isShowing() && section(RunRecapView.EVIDENCE).expanded());
            String workbench = text("run-recap-evidence-text");
            assertEquals("The workbench's sections", linked.evidence(), workbench);
            assertTrue(workbench, workbench.contains("Server victory"));
            assertTrue("The capture shows the evidence text", inView(VisualEvidence.named(shell, "run-recap-evidence-text", JComponent.class)));
        });
        recap("recap-no-recording", 1240, 800, 13, SIMPLE, model(A3, null), null, () -> {
            assertEquals("Pirate Cave", text("run-recap-map"));
            for (String id : new String[] {RunRecapModel.Tile.DPS, RunRecapModel.Tile.SHARE, RunRecapModel.Tile.DEATHS}) {
                assertEquals(id + " is unknown, never 0", "—", tile(id).text());
                assertEquals(RunRecapBuilder.NO_RECORDING, tile(id).tooltip());
            }
            assertEquals("+120", tile(RunRecapModel.Tile.FAME).text());
            assertEquals("Party —", text("run-recap-party"));
            assertEquals(RunRecapBuilder.NO_RECORDING, text("run-recap-damage-reason"));
            assertNull("No chart", shown(shell, "run-recap-chart"));
            assertNull("No meter", shown(shell, "run-recap-meter-scroll"));
            assertNull("No picker", shown(shell, "run-recap-recording-row"));
        });
        recap("recap-unverified", 1240, 800, 13, SIMPLE, model(A2, null), "run-recap-damage", () -> {
            assertEquals("Snake Pit", text("run-recap-map"));
            assertEquals("Left", VisualEvidence.named(shell, "run-recap-outcome", Chip.class).getText());
            for (String id : new String[] {RunRecapModel.Tile.DPS, RunRecapModel.Tile.SHARE, RunRecapModel.Tile.DEATHS}) {
                assertEquals(id + " is unknown", "—", tile(id).text());
                assertEquals(RunRecapBuilder.LOCAL_UNVERIFIED, tile(id).tooltip());
            }
            assertEquals("No row is marked as yours. " + RunRecapBuilder.LOCAL_UNVERIFIED, text("run-recap-damage-local"));
            JTable table = VisualEvidence.named(shell, "run-recap-meter", JTable.class);
            assertEquals("Every contributor, none marked as yours", 3, table.getRowCount());
            assertTrue("No (you) anywhere", !text("run-recap-sources-title").contains("(you)"));
        });
    }

    /** 2 captures: Settings › General › Combat history with its defaults (full detail off, 30 days disabled, summaries Forever). */
    @Test public void settingsGeneralShowsTheCombatHistorySettings() throws Exception {
        build(temp.newFolder("history").toPath());
        for (Object[] size : new Object[][] {{1240, 800, 13, SIMPLE}, {680, 520, 18, ANALYST}}) {
            int width = (Integer) size[0], height = (Integer) size[1], font = (Integer) size[2];
            DisplayModeModel.Mode mode = (DisplayModeModel.Mode) size[3];
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(mode);
                evidence.show(shell, "Settings general", width, height, font);
                TomatoGUI.openSettings(SettingsPage.GENERAL);
            });
            pause();
            capture("settings-general", width, font, mode, () -> {
                assertTrue("Settings › General shows", VisualEvidence.named(shell, "settings-general", JComponent.class).isShowing());
                assertTrue(VisualEvidence.named(shell, "settings-section-general", JToggleButton.class).isSelected());
                JCheckBox full = VisualEvidence.named(shell, "settings-combat-full-detail", JCheckBox.class);
                assertEquals("Keep full combat detail", full.getText());
                assertFalse("Off by default", full.isSelected());
                JComboBox<?> days = VisualEvidence.named(shell, "settings-combat-full-days", JComboBox.class);
                assertEquals("30 days", days.getSelectedItem());
                assertFalse("The day count applies only with full detail on", days.isEnabled());
                assertEquals("Forever", VisualEvidence.named(shell, "settings-combat-summaries", JComboBox.class).getSelectedItem());
                assertTrue(text("settings-combat-full-detail-help").contains("The debug packet log is never saved."));
                String help = text("settings-combat-summaries-help");
                assertTrue(help, help.contains("about 12–30 KB for a dungeon run, more for long Realm visits") && help.contains("the current session is never touched"));
                for (String name : new String[] {"settings-combat-full-detail-help", "settings-combat-summaries-help", "settings-combat-summaries"})
                    assertTrue("The capture shows " + name, inView(VisualEvidence.named(shell, name, JComponent.class)));
            });
        }
    }

    // ---- synthetic saved history ----

    /** Local wall-clock time on today + {@code dayOffset} in the test's zone. */
    private static long at(int dayOffset, int hour, int minute) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).plusDays(dayOffset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli();
    }

    /** A fixed offset at which {@code now} is between 15:00 and 16:00, so the fixture's day times are all in the past today. */
    static ZoneOffset afternoon(long now) {
        int hours = 15 - Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).getHour();
        return ZoneOffset.ofHours(hours > 14 ? hours - 24 : hours);
    }

    /**
     * Four sessions (synthetic names only):
     * - Today (ended 14:20, one capture interval): a4 Ice Citadel completed (party 4, your row #1 of 4, 3 items, +310 fame); a3
     *   Pirate Cave completed (party not observed, no recording, 3 items, +120); a2 Snake Pit left (party 3, a recording without
     *   a verified local row, 1 item, no fame reading); a1 Lost Halls completed (party 6, exalt progress 1, two recordings: 240 s
     *   with your row #2 of 6 and a death, and 45 s; 10 items in three bags; +510 fame; six inspected players; resource and
     *   condition samples; five timeline events). a4 has one more event, which a1's recap must not show.
     * - Crashed (never saved its end): b1 Lost Halls left open (App ended), 2 items, +110 fame, no recording.
     * - Yesterday: y1 Lost Halls completed (#3 of 8, exalt progress 2, +900), y2 Snake Pit completed (#1 of 3, +150), y3 Pirate
     *   Cave left (no loot in a session with loot: a known none; no fame reading), y4 Ice Citadel completed (#2 of 5, +420).
     * - Three days ago: o1 Snake Pit completed (#1 of 2), o2 Pirate Cave left; no fame readings.
     */
    private static void write(Path root) throws Exception {
        RunFixtures.session(root, TODAY, at(0, 11, 50), at(0, 14, 20), new SessionStore.Interval(at(0, 11, 50), at(0, 14, 20), "Capture stopped"));
        ActivityJournal.Visit a4 = left(HomeHistoryFixture.visit("a4", "Ice Citadel", at(0, 12, 0), at(0, 12, 28), true));
        a4.rosterSize = 4;
        ActivityJournal.Visit a3 = left(HomeHistoryFixture.visit("a3", "Pirate Cave", at(0, 12, 35), at(0, 12, 44), true));
        ActivityJournal.Visit a2 = left(HomeHistoryFixture.visit("a2", "Snake Pit", at(0, 12, 50), at(0, 13, 2), false));
        a2.rosterSize = 3;
        ActivityJournal.Visit a1 = left(HomeHistoryFixture.visit("a1", "Lost Halls", at(0, 13, 20), at(0, 13, 46), true));
        a1.rosterSize = 6; a1.partyId = 12; a1.exaltIncrease = 1;
        players(a1, new int[] {797, WIZARD, 784, 768, 775, 798}, 186_000, 151_000, 52_000, 120_000, 104_000, 83_000);
        resources(a1);
        HomeHistoryFixture.runs(root, TODAY, a4, a3, a2, a1);
        HomeHistoryFixture.loot(root, TODAY,
            LootTestDrops.drop("White", "Ice Citadel", at(0, 12, 20), A4, item(9401, UT), item(9402, POTION)),
            LootTestDrops.drop("Cyan", "Ice Citadel", at(0, 12, 26), A4, item(9403, TIERED)),
            LootTestDrops.drop("Brown", "Pirate Cave", at(0, 12, 40), A3, item(9301, PLAIN)),
            LootTestDrops.drop("Blue", "Pirate Cave", at(0, 12, 42), A3, item(9302, POTION), item(9303, POTION)),
            LootTestDrops.drop("Brown", "Snake Pit", at(0, 12, 58), A2, item(9201, PLAIN)),
            LootTestDrops.drop("White", "Lost Halls", at(0, 13, 30), A1, item(9101, UT), item(9102, POTION)),
            LootTestDrops.drop("Orange", "Lost Halls", at(0, 13, 38), A1, item(9103, ST), item(9104, TIERED), item(9105, POTION), item(9106, PLAIN)),
            LootTestDrops.drop("Purple", "Lost Halls", at(0, 13, 44), A1, item(9107, TIERED), item(9108, PLAIN), item(9109, POTION), item(9110, PLAIN)));
        HomeHistoryFixture.fame(root, TODAY, fame(null, 20_000, at(0, 11, 55)), fame(A4, 20_310, at(0, 12, 25)), fame(A3, 20_430, at(0, 12, 42)),
            fame(A1, 20_700, at(0, 13, 35)), fame(A1, 20_940, at(0, 13, 44)));
        lines(root.resolve(TODAY).resolve("timeline.jsonl"),
            event("e-4", "a4", at(0, 12, 0), "Area entered", "Ice Citadel", Map.of()),
            event("e-1", "a1", at(0, 13, 20), "Area entered", "Lost Halls", Map.of()),
            event("e-2", "a1", at(0, 13, 20) + 30_000, "Party roster", "Lost Halls", Map.of("partyId", 12, "memberCount", 6)),
            event("e-3", "a1", at(0, 13, 28), "Equipment changed", "Lost Halls", Map.of("slot", 0, "before", 2101, "after", 2111)),
            event("e-5", "a1", at(0, 13, 33), "Item / ability request", "Lost Halls", Map.of("slotObject.objectType", 2201, "slotLabel", "Ability")),
            event("e-6", "a1", at(0, 13, 46), "Exalt change", "Lost Halls", Map.of("classId", WIZARD, "stat", "Attack", "before", 3, "after", 4)));
        combat(root, TODAY, fight(A1, "Lost Halls", at(0, 13, 20), at(0, 13, 21), 240, new int[] {797, WIZARD, 784, 768, 775, 798},
            new double[] {1.25, 1.0, 0.35, 0.8, 0.7, 0.55}, true, "Bravo", "Delta"));
        combat(root, TODAY, fight(A1, "Lost Halls", at(0, 13, 39), at(0, 13, 40), 45, new int[] {797, WIZARD, 784, 768},
            new double[] {1.1, 1.0, 0.4, 0.9}, true));
        combat(root, TODAY, fight(A2, "Snake Pit", at(0, 12, 50), at(0, 12, 52), 90, new int[] {775, WIZARD, 798},
            new double[] {0.9, 1.0, 0.6}, false));
        combat(root, TODAY, fight(A4, "Ice Citadel", at(0, 12, 0), at(0, 12, 2), 150, new int[] {797, WIZARD, 784, 799},
            new double[] {0.9, 1.2, 0.5, 0.7}, true));

        RunFixtures.session(root, CRASHED, at(0, 14, 20), 0, new SessionStore.Interval(at(0, 14, 20), at(0, 14, 50), "Capture interrupted"));
        HomeHistoryFixture.runs(root, CRASHED, HomeHistoryFixture.visit("b1", "Lost Halls", at(0, 14, 25), 0, false));
        HomeHistoryFixture.loot(root, CRASHED, LootTestDrops.drop("Purple", "Lost Halls", at(0, 14, 35), B1, item(9501, TIERED), item(9502, POTION)));
        HomeHistoryFixture.fame(root, CRASHED, fame(null, 20_940, at(0, 14, 22)), fame(B1, 21_050, at(0, 14, 40)));

        VisitRef y1 = new VisitRef(YESTERDAY, "y1"), y2 = new VisitRef(YESTERDAY, "y2"), y4 = new VisitRef(YESTERDAY, "y4");
        RunFixtures.session(root, YESTERDAY, at(-1, 19, 30), at(-1, 23, 10), new SessionStore.Interval(at(-1, 19, 30), at(-1, 23, 10), "Capture stopped"));
        ActivityJournal.Visit yl = left(HomeHistoryFixture.visit("y1", "Lost Halls", at(-1, 19, 40), at(-1, 20, 15), true));
        yl.rosterSize = 8; yl.exaltIncrease = 2;
        ActivityJournal.Visit ys = left(HomeHistoryFixture.visit("y2", "Snake Pit", at(-1, 20, 30), at(-1, 20, 44), true));
        ys.rosterSize = 3;
        ActivityJournal.Visit yi = left(HomeHistoryFixture.visit("y4", "Ice Citadel", at(-1, 22, 10), at(-1, 22, 40), true));
        yi.rosterSize = 5;
        HomeHistoryFixture.runs(root, YESTERDAY, yl, ys, left(HomeHistoryFixture.visit("y3", "Pirate Cave", at(-1, 21, 0), at(-1, 21, 6), false)), yi);
        HomeHistoryFixture.loot(root, YESTERDAY,
            LootTestDrops.drop("White", "Lost Halls", at(-1, 20, 5), y1, item(9601, UT)),
            LootTestDrops.drop("Orange", "Lost Halls", at(-1, 20, 12), y1, item(9602, ST), item(9603, POTION)),
            LootTestDrops.drop("Blue", "Snake Pit", at(-1, 20, 41), y2, item(9701, POTION)),
            LootTestDrops.drop("Purple", "Ice Citadel", at(-1, 22, 30), y4, item(9801, TIERED), item(9802, PLAIN)));
        HomeHistoryFixture.fame(root, YESTERDAY, fame(null, 18_000, at(-1, 19, 35)), fame(y1, 18_900, at(-1, 20, 10)), fame(y2, 19_050, at(-1, 20, 40)),
            fame(y4, 19_470, at(-1, 22, 35)));
        combat(root, YESTERDAY, fight(y1, "Lost Halls", at(-1, 19, 40), at(-1, 19, 42), 300, new int[] {797, WIZARD, 784, 768, 775, 798, 799, 800},
            new double[] {1.3, 1.0, 0.4, 1.1, 0.8, 0.6, 0.7, 0.9}, true, "Charlie"));
        combat(root, YESTERDAY, fight(y2, "Snake Pit", at(-1, 20, 30), at(-1, 20, 31), 80, new int[] {797, WIZARD, 784}, new double[] {0.8, 1.1, 0.4}, true));
        combat(root, YESTERDAY, fight(y4, "Ice Citadel", at(-1, 22, 10), at(-1, 22, 12), 200, new int[] {797, WIZARD, 784, 768, 775},
            new double[] {1.2, 1.0, 0.4, 0.9, 0.7}, true));

        VisitRef o1 = new VisitRef(OLDER, "o1"), o2 = new VisitRef(OLDER, "o2");
        RunFixtures.session(root, OLDER, at(-3, 18, 0), at(-3, 19, 0), new SessionStore.Interval(at(-3, 18, 0), at(-3, 19, 0), "Capture stopped"));
        HomeHistoryFixture.runs(root, OLDER, left(HomeHistoryFixture.visit("o1", "Snake Pit", at(-3, 18, 10), at(-3, 18, 22), true)),
            left(HomeHistoryFixture.visit("o2", "Pirate Cave", at(-3, 18, 30), at(-3, 18, 38), false)));
        HomeHistoryFixture.loot(root, OLDER, LootTestDrops.drop("Cyan", "Snake Pit", at(-3, 18, 20), o1, item(9901, POTION)),
            LootTestDrops.drop("Brown", "Pirate Cave", at(-3, 18, 36), o2, item(9902, PLAIN)));
        combat(root, OLDER, fight(o1, "Snake Pit", at(-3, 18, 10), at(-3, 18, 11), 60, new int[] {797, WIZARD}, new double[] {0.7, 1.0}, true));
    }

    /** As the journal closes a visit on leaving its area: the end reason, and a status that says whether a completion was seen. */
    private static ActivityJournal.Visit left(ActivityJournal.Visit visit) {
        visit.endReason = "Area left; completion unknown";
        visit.status = visit.completionEvidence.isEmpty() ? visit.endReason : "Completed";
        return visit;
    }

    /** A fame reading of the synthetic account's Wizard #1, tagged with {@code visit} (null = untagged). */
    private static tomato.history.AppHistory.FameSample fame(VisitRef visit, long fame, long time) {
        return new tomato.history.AppHistory.FameSample(1, RunFixtures.ACCOUNT, fame, time, "Wizard", visit, visit == null ? null : "Lost Halls");
    }

    /**
     * A synthetic fight of {@code seconds} s from its first tick {@code start}: players Alpha… with {@code classes} and relative
     * {@code strength} hit twelve minions of three types (one without max HP), then the boss for the last 40 %, in waves; each
     * player takes a hit every 4 s; {@code deaths} name the dead. Bravo is the capture's own character, verified as the local row
     * in the entry-frozen context unless {@code verified} is false.
     */
    private static CombatSummaries.Result fight(VisitRef visit, String map, long entered, long start, int seconds, int[] classes, double[] strength,
                                                boolean verified, String... deaths) {
        CombatFixtures.Fight fight = CombatFixtures.fight(map);
        List<Entity> party = new ArrayList<>();
        for (int p = 0; p < classes.length; p++) party.add(p == LOCAL ? fight.user(p + 1, classes[p], NAMES[p]) : fight.player(p + 1, classes[p], NAMES[p]));
        String[] kinds = {"Synthetic Crawler", "Synthetic Spitter", "Synthetic Warden"};
        List<Entity> minions = new ArrayList<>();
        for (int e = 0; e < 12; e++) minions.add(fight.enemy(1000 + e, 6000 + e % 3, kinds[e % 3], e % 3 == 1 ? null : 2_000 * (e % 3 + 1), false));
        Entity boss = fight.enemy(2000, 6100, "Synthetic Colossus", 400_000, true);
        int bossFrom = seconds * 3 / 5;
        for (int s = 0; s < seconds; s++) for (int p = 0; p < party.size(); p++) for (int k = 0; k < 2; k++) {
            double wave = 0.65 + 0.35 * Math.sin(2 * Math.PI * s / 45.0 + p);
            int damage = (int) Math.max(1, Math.round(320 * strength[p] * wave * (s >= bossFrom ? 1.3 : 1)));
            DamageSource source = k == 0 ? DamageSource.WEAPON : s % 3 == 0 ? DamageSource.ABILITY : s % 7 == 0 ? DamageSource.ITEM_EFFECT : DamageSource.WEAPON;
            int itemId = source == DamageSource.WEAPON ? 2101 + p : source == DamageSource.ABILITY ? 2201 + p : 2301 + p;
            fight.hit(s >= bossFrom ? boss : minions.get((s + p + k) % minions.size()), party.get(p), damage, start + s * 1000L + p * 40L + k * 500L, source, itemId);
        }
        for (int s = 0; s < seconds; s += 4) for (int p = 0; p < party.size(); p++)
            fight.taken(party.get(p), boss, 30 + p * 6 + s % 3 * 10, start + s * 1000L + 300);
        for (String name : deaths) fight.death(name, 7);
        return CombatSummaries.build(fight.ticks(start, seconds * 1000L).context(visit, verified ? LOCAL + 1 : null, entered).captured(party.get(LOCAL)).build());
    }

    private static void combat(Path root, String session, CombatSummaries.Result result) throws Exception {
        CombatFixtures.writeRecord(root, session, result.record());
        CombatFixtures.writeDetail(root, session, result.detail());
    }

    /** Inspected players Alpha… with {@code classes}, four equipped items each and their Inspect damage. */
    private static void players(ActivityJournal.Visit visit, int[] classes, long... damage) {
        for (int p = 0; p < classes.length; p++) {
            Entity player = new Entity(null, p + 1, 0); player.objectType = classes[p]; player.markPlayerIdentity();
            StatData name = new StatData(); name.stringStatValue = NAMES[p]; player.stat.set(StatType.NAME_STAT, name);
            StatType[] slots = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};
            for (int slot = 0; slot < slots.length; slot++) { StatData item = new StatData(); item.statValue = 2101 + slot * 100 + p; player.stat.set(slots[slot], item); }
            InspectSnapshot snapshot = new InspectSnapshot(player, visit.started + 30_000 + p * 1_000L);
            visit.inspectedPlayers.put(snapshot.key(), snapshot);
            visit.playerDamage.put(snapshot.key(), damage[p]);
            visit.totalDamage += damage[p];
        }
        visit.inspectedPlayerCount = classes.length;
        visit.damageTracked = true;
        visit.firstDamageAt = visit.started + 60_000; visit.lastDamageAt = visit.ended - 60_000;
    }

    /** HP/MP every 5 s over the visit, and 20 s condition slices cycling through a few effects. */
    private static void resources(ActivityJournal.Visit visit) {
        visit.maxHp = 1_650; visit.maxMp = 1_250;
        for (long t = visit.started; t <= visit.ended; t += 5_000) {
            double phase = (t - visit.started) / 60_000.0;
            ActivityJournal.ResourcePoint point = new ActivityJournal.ResourcePoint();
            point.time = t;
            point.hp = (int) Math.round(1_350 + 280 * Math.sin(phase * 1.7) - (phase > 16 && phase < 21 ? 420 : 0));
            point.mp = (int) Math.round(820 + 360 * Math.cos(phase * 1.1));
            visit.resourceTimeline.add(point);
            visit.hpSamples++; visit.mpSamples++;
        }
        int[] masks = {0, 0x40000, 0x40000 | 0x80000, 0x4000 | 0x20000, 0, 0x8};
        String[] names = {null, "DAMAGING", "BERSERK", "SPEEDY", null, "SLOWED"};
        int i = 0;
        for (long t = visit.started; t < visit.ended; t += 20_000, i++) {
            ActivityJournal.ConditionSlice slice = new ActivityJournal.ConditionSlice();
            slice.start = t; slice.end = Math.min(visit.ended, t + 20_000); slice.primary = masks[i % masks.length]; slice.secondary = 0;
            visit.conditionTimeline.add(slice);
            visit.conditionObservedMillis += slice.end - slice.start;
            if (names[i % names.length] != null) visit.conditions.merge(names[i % names.length], slice.end - slice.start, Long::sum);
            if (masks[i % masks.length] == (0x4000 | 0x20000)) visit.conditions.merge("HEALING", slice.end - slice.start, Long::sum);
            if (masks[i % masks.length] == (0x40000 | 0x80000)) visit.conditions.merge("DAMAGING", slice.end - slice.start, Long::sum);
        }
    }

    private static ActivityJournal.Entry event(String id, String visitId, long time, String kind, String map, Map<String, Object> values) {
        ActivityJournal.Entry entry = new ActivityJournal.Entry();
        entry.id = id; entry.visitId = visitId; entry.time = time; entry.kind = kind; entry.map = map; entry.detail = "Synthetic " + id;
        entry.values = new LinkedHashMap<>(values);
        return entry;
    }

    private static void lines(Path file, Object... values) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Object value : values) lines.add(SessionStore.JSON.toJson(value));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    // ---- the workspace ----

    private void build(Path history) throws Exception {
        store = new SessionStore(history, true, "p5a-runs");
        remember(AppHistory.class, "store", store);
        journal = new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            data = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
        });
    }

    /** The recap of {@code ref} (null recording: the longest), read off the EDT as the route's worker will. */
    private RunRecapModel model(VisitRef ref, String recording) throws Exception {
        assertFalse(SwingUtilities.isEventDispatchThread());
        return new RunRecapBuilder(store, ZoneId.systemDefault(), System::currentTimeMillis).build(ref, recording, new Cancellation());
    }

    /**
     * One capture of the feed after the Runs route: from the top, or with the day {@code day}'s header scrolled to the top of the
     * page. Waits for the feed's read (it reads when the Cards view first shows).
     */
    private void feed(String state, int width, int height, int font, DisplayModeModel.Mode mode, LocalDate day, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Runs " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.RUNS)));
            page().showFeed();
        });
        // The summary line after a read, or the empty state that replaces it (once the read has finished).
        await("the feed", () -> page().feed().tableShown() || shown(shell, "run-feed-summary") != null && text("run-feed-summary").startsWith("Saved runs · newest first · ")
            || shown(shell, "run-feed-empty") instanceof EmptyState empty && !"Loading saved runs".equals(empty.getAccessibleContext().getAccessibleName()));
        pause();
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane page = (JScrollPane) shown(shell, "run-feed-scroll");
            if (page == null) return;
            if (day == null) page.getVerticalScrollBar().setValue(0);
            else scrollToTop(page, VisualEvidence.named(shell, "run-feed-header-" + day, JComponent.class));
        });
        pause();
        capture(state, width, font, mode, check);
    }

    /**
     * One capture of the recap of {@code model} in the Runs page's recap slot with its Damage section expanded (as the route
     * will), from the top or with {@code part} scrolled to the top of the recap.
     */
    private void recap(String state, int width, int height, int font, DisplayModeModel.Mode mode, RunRecapModel model, String part, Runnable check)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            RunsPage runs = page();
            if (recap == null) {
                recap = new RunRecapView();
                recap.onBack(runs::showFeed);
                recap.onRecording(requested::set);
                runs.setRecap(recap);
            }
            evidence.show(shell, "Runs " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.RUNS)));
            recap.show(model);
            runs.showRecap();
            recap.expandDamage();
        });
        pause();
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane scroll = VisualEvidence.named(shell, "run-recap-scroll", JScrollPane.class);
            if (part == null) scroll.getVerticalScrollBar().setValue(0);
            else scrollToTop(scroll, VisualEvidence.named(shell, part, JComponent.class));
        });
        pause();
        capture(state, width, font, mode, () -> {
            assertTrue("The recap shows in the Runs page", page().recapShown() && recap.isShowing());
            assertEquals(model.ref(), recap.ref());
            check.run();
        });
    }

    private void capture(String state, int width, int font, DisplayModeModel.Mode mode, Runnable check) throws Exception {
        capture(state, width, font, mode, null, check);
    }

    /** As above; a showing {@code popup} (an open drop-down list) is drawn over the window where it shows on screen. */
    private void capture(String state, int width, int font, DisplayModeModel.Mode mode, JComponent popup, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String name = "p5a-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT);
            if (popup == null) evidence.capture(name);
            else withPopup(name, popup);
            errors.checkSucceeds(() -> { nothingSideways(shell, name); return null; });
            errors.checkSucceeds(() -> { check.run(); return null; });
        });
    }

    /** The window as {@link VisualEvidence#capture} prints it, with {@code popup} printed over it at its place on screen. */
    private void withPopup(String name, JComponent popup) {
        Window window = SwingUtilities.getWindowAncestor(shell);
        assertTrue("The popup shows", popup.isShowing());
        UiTestLayout.settle(window);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        window.printAll(graphics);
        Point at = popup.getLocationOnScreen(), origin = window.getLocationOnScreen();
        Graphics2D over = (Graphics2D) graphics.create(at.x - origin.x, at.y - origin.y, popup.getWidth(), popup.getHeight());
        popup.printAll(over);
        over.dispose();
        graphics.dispose();
        try {
            java.io.File directory = new java.io.File("screenshots", "redesign-p5a-runs");
            assertTrue("Evidence directory", directory.isDirectory() || directory.mkdirs());
            assertTrue("PNG writer", javax.imageio.ImageIO.write(image, "png", new java.io.File(directory, name + ".png")));
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private RunsPage page() { return VisualEvidence.find(shell, RunsPage.class, p -> true); }
    private ArchiveWorkspace<?, ?, ?> workspace() { return (ArchiveWorkspace<?, ?, ?>) page().workspace(); }
    @SuppressWarnings("unchecked") private TileList<Object> day(LocalDate date) { return (TileList<Object>) shown(shell, "run-feed-day-" + date); }
    private String header(LocalDate date) { return VisualEvidence.named(shell, "run-feed-header-" + date, SectionHeader.class).title(); }
    private String counts(LocalDate date) { return text("run-feed-counts-" + date); }
    private Collapsible section(String id) { return VisualEvidence.named(shell, "run-recap-" + id, Collapsible.class); }
    private DamageChart chart() { return VisualEvidence.named(shell, "run-recap-chart", DamageChart.class); }
    @SuppressWarnings("unchecked") private JComboBox<Object> picker() { return VisualEvidence.named(shell, "run-recap-recording", JComboBox.class); }
    private DisplayValue tile(String id) { return VisualEvidence.named(shell, "run-recap-tile-" + id, StatTile.class).value(); }

    /** A tile's subline, as painted under its value. */
    private String sub(String id) {
        for (RunRecapModel.Tile tile : recap.model().tiles()) if (tile.id().equals(id)) return tile.subline();
        throw new AssertionError("No tile " + id);
    }

    /** A hit window as the recap writes it ("44.6"). */
    private static String seconds(Double window) { return tomato.gui.modern.DisplayFormat.formatNumber(window, 0, 1); }

    /** The picker's rows as its renderer writes them. */
    private List<String> pickerLabels() {
        List<String> labels = new ArrayList<>();
        JList<Object> list = new JList<>();
        for (int i = 0; i < picker().getItemCount(); i++)
            labels.add(((JLabel) picker().getRenderer().getListCellRendererComponent(list, picker().getItemAt(i), i, false, false)).getText());
        return labels;
    }

    /** The text of the component named {@code name}: a text area, label or button. */
    private String text(String name) {
        Component component = VisualEvidence.named(shell, name, JComponent.class);
        if (component instanceof javax.swing.text.JTextComponent) return ((javax.swing.text.JTextComponent) component).getText();
        if (component instanceof AbstractButton) return ((AbstractButton) component).getText();
        return ((JLabel) component).getText();
    }

    /** Showing components named {@code name}. */
    private static int count(Container root, String name) {
        int found = 0;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && child.isShowing()) found++;
            if (child instanceof Container) found += count((Container) child, name);
        }
        return found;
    }

    /** Scrolls {@code page} so that {@code part} starts at the top of its viewport (as far as the page scrolls). */
    private static void scrollToTop(JScrollPane page, JComponent part) {
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), page.getViewport().getView());
        page.getVerticalScrollBar().setValue(placed.y);
    }

    /** Whether some of {@code part} is inside the visible part of its scroll pane (the capture shows it). */
    private static boolean inView(Component part) { return part != null && part.isShowing() && ((JComponent) part).getVisibleRect().height > 0; }

    /** What a screen reader announces for each card (the painted text is not in the component tree). */
    private static List<String> names(JList<?> tiles) {
        assertNotNull("The day's cards show", tiles);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tiles.getModel().getSize(); i++)
            names.add(tiles.getAccessibleContext().getAccessibleChild(i).getAccessibleContext().getAccessibleName());
        return names;
    }

    /** Polls {@code condition} on the EDT (the page applies its reads on later EDT turns) for up to 15 s. */
    private static void await(String what, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > end) fail(what + " did not settle within 15 s");
            Thread.sleep(50);
        }
    }

    /**
     * No showing scroll pane scrolls or cuts its content sideways: no horizontal scroll bar, and the view no wider than the viewport.
     * A data table scrolls its own columns sideways by design and is listed on standard output instead (as in P3b/P4).
     */
    private static void nothingSideways(Container root, String capture) {
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane && child.isShowing()) {
                JScrollPane scroll = (JScrollPane) child;
                Component view = scroll.getViewport().getView();
                String where = scroll.getName() != null ? scroll.getName() : view == null ? "an empty scroll pane" : view.getClass().getSimpleName();
                if (view instanceof JTable) System.out.println(capture + ": table " + where + " " + view.getWidth() + " px in a " + scroll.getViewport().getWidth()
                    + " px viewport, horizontal bar " + (scroll.getHorizontalScrollBar().isShowing() ? "shown" : "hidden"));
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

    private static boolean showsText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c.isShowing() && (c instanceof JLabel && String.valueOf(((JLabel) c).getText()).contains(text)
                || c instanceof javax.swing.text.JTextComponent && ((javax.swing.text.JTextComponent) c).getText().contains(text))) return true;
            if (c instanceof Container && showsText((Container) c, text)) return true;
        }
        return false;
    }

    /** The page applies reads on later EDT turns and Collapsible motion takes at most Motion.MAX_MILLIS: settle, wait, settle. */
    private void pause() throws Exception { evidence.settle(); Thread.sleep(400); evidence.settle(); }

    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); statics.put(field, field.get(null)); field.set(null, next);
    }

    private static Set<String> archiveKeys() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Set<String> keys = new HashSet<>(((Properties) field.get(null)).stringPropertyNames());
        keys.removeIf(key -> !key.startsWith("ux.archive."));
        return keys;
    }
}
