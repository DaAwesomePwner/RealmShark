package tomato.gui.runs;

import java.awt.*;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.CombatTimelineChart;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.runs.RunDamagePanelTest.*;

/**
 * The run recap view over synthetic models (synthetic names only): header, tiles with unknowns as "—" and their reason, the
 * six sections with their default open state and persisted choice, each empty section's one-line reason, Analyst-only Evidence,
 * the back link and route links, loading and unavailable states, and the 680×520 font-18 fit without scrolling sideways.
 */
public class RunRecapViewTest {
    private static final String[] SECTIONS = {"damage", "loot", "players", "resources", "timeline", "evidence"};
    /** The recap's section order (comma-separated section ids); isolated like the collapse keys. */
    private static final String ORDER = "ui.order.run-recap";
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p5a-recap");
    private final Map<String, String> saved = new HashMap<>();
    private final Map<String, String> modeStore = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(modeStore::get, modeStore::put); // never the application's ui.mode
    private Locale previous;

    @Before public void isolate() {
        previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        for (String id : SECTIONS) {
            String key = Collapsible.PREFIX + "run-recap-" + id;
            saved.put(key, PropertiesManager.getProperty(key));
            PropertiesManager.setProperties(key, "");
        }
        saved.put(ORDER, PropertiesManager.getProperty(ORDER));
        PropertiesManager.setProperties(ORDER, "");
    }

    @After public void restore() {
        Locale.setDefault(Locale.Category.FORMAT, previous);
        for (Map.Entry<String, String> entry : saved.entrySet()) PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
    }

    // ---- synthetic models ----

    private static List<RunRecapModel.Tile> tiles() {
        return List.of(
            new RunRecapModel.Tile(RunRecapModel.Tile.DPS, "Your DPS", DisplayValue.known("1.5k", "Your verified row"), "#1 of 3"),
            new RunRecapModel.Tile(RunRecapModel.Tile.SHARE, "Damage share", DisplayValue.known("60%", "Your share"), null),
            new RunRecapModel.Tile(RunRecapModel.Tile.DEATHS, "Deaths", DisplayValue.count(1L, "Death notifications naming you", null), "All players: 2"),
            new RunRecapModel.Tile(RunRecapModel.Tile.FAME, "Fame", DisplayValue.unknown("No fame reading was recorded during this run."), null),
            new RunRecapModel.Tile(RunRecapModel.Tile.LOOT, "Loot", DisplayValue.count(3L, "Items in bags", null), "1 UT · 2 potions"),
            new RunRecapModel.Tile(RunRecapModel.Tile.EXALT, "Exalt progress", DisplayValue.unknown("No exalt progress was observed in this run."), null));
    }

    private static LootFacts.Item ut(int id) { return new LootFacts.Item(id, true, false, false, false); }
    private static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }

    private static ActivityJournal.Visit visit() {
        ActivityJournal.Visit visit = new ActivityJournal.Visit();
        visit.id = REF.visitId; visit.map = "Lost Halls"; visit.started = T0; visit.lastSeen = T0 + 591_000;
        for (int i = 0; i < 3; i++) {
            ActivityJournal.ResourcePoint point = new ActivityJournal.ResourcePoint();
            point.time = T0 + i * 1_000L; point.hp = 500 - 10 * i; point.mp = 200;
            visit.resourceTimeline.add(point);
        }
        return visit;
    }

    static RunRecapModel full(VisitRef ref, RunOutcome outcome, int recordings) {
        RunRecapModel.Header header = new RunRecapModel.Header("Lost Halls", "Lost Halls", 0, outcome, T0, 591_000L, 4, "Wizard #3");
        RunRecapModel.Loot loot = new RunRecapModel.Loot(List.of(new RunRecapModel.Loot.Bag("White", T0 + 300_000, List.of(new LootFacts.Item(101, true, false, false, false, 2, 0))),
            new RunRecapModel.Loot.Bag("Purple", T0 + 400_000, List.of(potion(102), potion(103)))), 3, "1 UT · 2 potions", null);
        RunRecapModel.Players players = new RunRecapModel.Players(List.of(
            new RunRecapModel.Players.Player(1, "Alpha", "Wizard", 782, Arrays.asList(2001, 2002, null, -1), 700L, T0 + 10_000,
                List.of(EnchantInfo.ofSlotCount(2), EnchantInfo.notRecorded(), EnchantInfo.notRecorded(), EnchantInfo.notRecorded())),
            new RunRecapModel.Players.Player(2, null, "Priest", 784, Arrays.asList(null, null, null, null), 0L, T0 + 12_000)), 2, null, null);
        RunRecapModel.Timeline timeline = new RunRecapModel.Timeline(List.of(new RunRecapModel.Timeline.Event(T0, "Area entered", "Entered Lost Halls"),
            new RunRecapModel.Timeline.Event(T0 + 60_000, "Key pop", "Synthetic key popped"),
            new RunRecapModel.Timeline.Event(T0 + 500_000, "Completion", "Server victory")), 812, null);
        return new RunRecapModel(ref, null, T0 + 600_000, header, tiles(), linked(recordings), loot, players,
            new RunRecapModel.Resources(visit(), null), timeline, "OUTCOME\nCompleted: Server victory\n\nTIMING / COVERAGE\nSynthetic evidence line");
    }

    static RunRecapModel empty(VisitRef ref) {
        RunRecapModel.Header header = new RunRecapModel.Header(null, "Unknown area", 0, RunOutcome.LEFT, null, null, null, null);
        List<RunRecapModel.Tile> tiles = new ArrayList<>();
        for (String id : new String[] {"dps", "share", "deaths"}) tiles.add(new RunRecapModel.Tile(id, id, DisplayValue.unknown(RunRecapBuilder.NO_RECORDING), null));
        return new RunRecapModel(ref, null, T0, header, tiles, none(),
            new RunRecapModel.Loot(List.of(), 0, "", "No loot bag was recorded inside this exact run."),
            new RunRecapModel.Players(List.of(), 0, "No player loadouts were recorded in this run.", "Inspect damage was not tracked for this run."),
            new RunRecapModel.Resources(null, "No resource or buff samples were saved for this run."),
            new RunRecapModel.Timeline(List.of(), 0, RunRecapBuilder.NO_EVENTS), "OUTCOME\nLeft");
    }

    private RunRecapView view(RunRecapModel model) {
        RunRecapView view = new RunRecapView(mode);
        view.show(model);
        return view;
    }

    private static <T extends Component> T named(Container root, String name, Class<T> type) { return VisualEvidence.named(root, name, type); }
    private static boolean shows(Container root, String name) { return named(root, name, JComponent.class).isVisible(); }

    private static String text(Container root, String name) {
        Component component = named(root, name, JComponent.class);
        if (component instanceof JTextArea) return ((JTextArea) component).getText();
        if (component instanceof AbstractButton) return ((AbstractButton) component).getText();
        return ((JLabel) component).getText();
    }

    private static Collapsible section(Container root, String id) { return named(root, "run-recap-" + id, Collapsible.class); }

    // ---- tests ----

    @Test public void aLinkedRunShowsItsHeaderTilesAndSections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            assertEquals("run-recap", view.getName());
            assertFalse(view.loading());
            assertEquals(REF, view.ref());
            assertTrue(shows(view, "run-recap-content"));
            assertEquals("Lost Halls", text(view, "run-recap-map"));
            Chip outcome = named(view, "run-recap-outcome", Chip.class);
            assertEquals("Completed", outcome.getText());
            assertEquals(Tokens.Tone.GOOD, outcome.tone());
            assertTrue(text(view, "run-recap-entered"), text(view, "run-recap-entered").startsWith("Entered "));
            assertEquals("The card's observed span", "9 m observed", text(view, "run-recap-duration"));
            assertTrue(named(view, "run-recap-duration", JLabel.class).getToolTipText().contains("not a verified clear time"));
            assertEquals("Party 4", text(view, "run-recap-party"));
            assertEquals("Wizard #3", text(view, "run-recap-character"));
            assertFalse("Only an in-progress run says how old its read is", shows(view, "run-recap-asof"));

            for (RunRecapModel.Tile tile : tiles()) {
                StatTile shown = named(view, "run-recap-tile-" + tile.id(), StatTile.class);
                assertEquals(tile.id(), tile.value(), shown.value());
            }
            assertEquals("Your DPS: 1.5k, #1 of 3", named(view, "run-recap-tile-dps", StatTile.class).getAccessibleContext().getAccessibleName());

            for (String id : SECTIONS) assertNotNull(id, section(view, id));
            assertTrue(section(view, "damage").expanded());
            assertTrue(section(view, "loot").expanded());
            for (String id : new String[] {"players", "resources", "timeline", "evidence"}) assertFalse(id + " starts collapsed", section(view, id).expanded());
            assertFalse("Evidence is Analyst only", section(view, "evidence").isVisible());

            assertEquals("3 items in 2 bags · 1 UT · 2 potions", text(view, "run-recap-loot-summary"));
            List<Integer> loot = new ArrayList<>();
            collect(named(view, "run-recap-loot-bags", JComponent.class), c -> { if (c instanceof ItemSlot) loot.add(((ItemSlot) c).itemId()); });
            assertEquals(List.of(101, 102, 103), loot);
            List<ItemSlot> slots = new ArrayList<>();
            collect(named(view, "run-recap-loot-bags", JComponent.class), c -> { if (c instanceof ItemSlot) slots.add((ItemSlot) c); });
            assertEquals(tomato.realmshark.EnchantInfo.ofSlotCount(2), slots.get(0).enchant());
            assertTrue(slots.get(0).getAccessibleContext().getAccessibleName().endsWith("Rare · 2 enchant slots"));
            assertNull("A potion shows no enchant line", slots.get(1).enchant());
            assertEquals(List.of("White bag", "Purple bag"), texts(view, "run-recap-loot-bag-name"));
            assertEquals(List.of("1 UT", "2 potions"), texts(view, "run-recap-loot-bag-kinds"));

            assertEquals(RunRecapModel.Players.INSPECT_DAMAGE, text(view, "run-recap-players-damage-label"));
            assertEquals(List.of("Alpha", "Unnamed player"), texts(view, "run-recap-player-name"));
            assertEquals(List.of("Inspect damage 700", "Inspect damage 0"), texts(view, "run-recap-player-damage"));
            assertEquals("2 players seen in this run", text(view, "run-recap-players-count"));
            List<ItemSlot> gear = new ArrayList<>();
            collect(named(view, "run-recap-players", JComponent.class), c -> { if (c instanceof ItemSlot) gear.add((ItemSlot) c); });
            assertEquals(EnchantInfo.ofSlotCount(2), gear.get(0).enchant());
            assertNull(gear.get(1).enchant());

            CombatTimelineChart chart = VisualEvidence.find(section(view, "resources"), CombatTimelineChart.class, c -> true);
            assertEquals("The saved visit's own samples", REF.visitId, chart.getVisit().id);
            assertEquals(3, chart.getVisit().resourceTimeline.size());
            JTable events = named(view, "run-recap-timeline-table", JTable.class);
            assertEquals(3, events.getRowCount());
            assertEquals("Entered Lost Halls", events.getValueAt(0, 2));
            assertTrue(shows(view, "run-recap-timeline-note"));
            assertEquals("Showing the first 3 of 812 saved events of this run; Open in Timeline lists them all.", text(view, "run-recap-timeline-note"));
            for (String id : new String[] {"damage", "loot", "players", "resources", "timeline"})
                assertFalse(id + " has no reason to show", shows(view, "run-recap-" + id + "-reason"));
        });
    }

    @Test public void unknownFactsShowDashesWithTheirReasonAndEmptySectionsSayWhy() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapModel model = empty(REF);
            RunRecapView view = view(model);
            assertEquals("Unknown area", text(view, "run-recap-map"));
            assertEquals("Entered —", text(view, "run-recap-entered"));
            assertEquals("Duration —", text(view, "run-recap-duration"));
            assertEquals("Party —", text(view, "run-recap-party"));
            assertEquals("Party not observed", named(view, "run-recap-party", JLabel.class).getToolTipText());
            assertEquals("Character —", text(view, "run-recap-character"));
            assertNotNull(named(view, "run-recap-character", JLabel.class).getToolTipText());

            StatTile dps = named(view, "run-recap-tile-dps", StatTile.class);
            assertEquals("—", dps.value().display());
            assertEquals(RunRecapBuilder.NO_RECORDING, dps.value().tooltip());
            StatTile fame = named(view, "run-recap-tile-fame", StatTile.class);
            assertEquals("A tile the model lacks is unknown, never 0", "—", fame.value().display());
            assertNotNull(fame.value().tooltip());

            assertEquals(RunRecapBuilder.NO_RECORDING, text(view, "run-recap-damage-reason"));
            assertEquals(model.loot().reason(), text(view, "run-recap-loot-reason"));
            assertEquals(model.players().reason(), text(view, "run-recap-players-reason"));
            assertEquals(model.resources().reason(), text(view, "run-recap-resources-reason"));
            assertEquals(model.timeline().reason(), text(view, "run-recap-timeline-reason"));
            for (String id : new String[] {"damage", "loot", "players", "resources", "timeline"}) {
                assertTrue(id + " shows its reason", shows(view, "run-recap-" + id + "-reason"));
                assertTrue(id + " is not hidden", section(view, id).isVisible());
            }
            assertFalse(shows(view, "run-recap-loot-summary"));
            assertFalse(shows(view, "run-recap-timeline-note"));
            assertFalse(VisualEvidence.find(view, JComponent.class, c -> "run-recap-resources-scroll".equals(c.getName())).isVisible());
        });
    }

    @Test public void thePlayersSectionSaysWhenInspectDamageWasNotTracked() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapModel base = full(REF, RunOutcome.COMPLETED, 1);
            RunRecapModel.Players players = new RunRecapModel.Players(List.of(new RunRecapModel.Players.Player(1, "Alpha", "Wizard", 782,
                Arrays.asList(2001, null, null, null), null, T0)), 1, null, "Inspect damage was not tracked for this run.");
            RunRecapView view = view(new RunRecapModel(REF, null, base.capturedAt(), base.header(), base.tiles(), base.damage(), base.loot(),
                players, base.resources(), base.timeline(), base.evidence()));
            assertEquals(List.of("Inspect damage —"), texts(view, "run-recap-player-damage"));
            assertEquals("Inspect damage was not tracked for this run.", text(view, "run-recap-players-damage-reason"));
            assertTrue(shows(view, "run-recap-players-damage-reason"));
            assertEquals("1 player seen in this run", text(view, "run-recap-players-count"));
        });
    }

    @Test public void evidenceIsForAnalystOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            assertFalse(section(view, "evidence").isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(section(view, "evidence").isVisible());
            assertTrue(text(view, "run-recap-evidence-text").startsWith("OUTCOME\nCompleted: Server victory"));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertFalse(section(view, "evidence").isVisible());
        });
    }

    @Test public void sectionsRememberTheUsersChoice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            named(view, "collapsible-run-recap-players", AbstractButton.class).doClick();
            named(view, "collapsible-run-recap-damage", AbstractButton.class).doClick();
            assertEquals("true", PropertiesManager.getProperty("ui.collapse.run-recap-players"));
            assertEquals("false", PropertiesManager.getProperty("ui.collapse.run-recap-damage"));
            RunRecapView again = view(full(REF, RunOutcome.COMPLETED, 1));
            assertTrue(section(again, "players").expanded());
            assertFalse(section(again, "damage").expanded());
            again.expandDamage();
            assertTrue("Opening a recap at its damage breakdown expands it", section(again, "damage").expanded());
        });
    }

    @Test public void backRecordingAndRouteLinksCallTheirCallbacks() throws Exception {
        List<Route> routes = new ArrayList<>();
        List<String> chosen = new ArrayList<>();
        int[] back = {0};
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = new RunRecapView(mode);
            view.onBack(() -> back[0]++);
            view.onOpenRoute(routes::add);
            view.onRecording(chosen::add);
            view.show(full(REF, RunOutcome.COMPLETED, 2));
            AbstractButton link = named(view, "run-recap-back", AbstractButton.class);
            assertEquals("‹ Runs", link.getText());
            link.doClick();
            assertEquals(1, back[0]);
            named(view, "run-recap-open-loot", AbstractButton.class).doClick();
            named(view, "run-recap-open-timeline", AbstractButton.class).doClick();
            named(view, "run-recap-open-table", AbstractButton.class).doClick();
            assertEquals(List.of(Destination.LOOT, Destination.TIMELINE, Destination.RUNS), List.of(routes.get(0).destination,
                routes.get(1).destination, routes.get(2).destination));
            for (Route route : routes) assertEquals("Links carry the exact visit", REF, route.visit);
            assertEquals("Open in Loot", text(view, "run-recap-open-loot"));
            assertEquals("Open in Timeline", text(view, "run-recap-open-timeline"));
            assertEquals("Open in Runs table", text(view, "run-recap-open-table"));
            @SuppressWarnings("unchecked") JComboBox<RunRecapModel.Damage.Recording> picker = named(view, "run-recap-recording", JComboBox.class);
            picker.setSelectedIndex(1);
            assertEquals(List.of("rec-b"), chosen);

            // A reference that is not a saved session visit offers no link, and says why.
            VisitRef imported = new VisitRef("imported", "v1");
            view.show(full(imported, RunOutcome.COMPLETED, 1));
            AbstractButton loot = named(view, "run-recap-open-loot", AbstractButton.class);
            assertFalse(loot.isEnabled());
            assertNotNull(loot.getToolTipText());
            assertEquals(3, routes.size());

            view.show(RunRecapModel.unavailable(REF, "Linked visit unavailable: synthetic reason.", T0));
            named(view, "run-recap-unavailable-back", AbstractButton.class).doClick();
            assertEquals(2, back[0]);
        });
    }

    @Test public void loadingThenUnavailableThenTheRecap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = new RunRecapView(mode);
            view.showLoading(REF);
            assertTrue(view.loading());
            assertEquals(REF, view.ref());
            assertNull(view.model());
            assertTrue(shows(view, "run-recap-status"));
            assertEquals(RunRecapView.LOADING, named(view, "run-recap-status", Banner.class).text());
            assertFalse(shows(view, "run-recap-content"));
            assertTrue("Back works while loading", shows(view, "run-recap-back"));

            RunRecapModel missing = RunRecapModel.unavailable(REF, "Linked visit unavailable: synthetic reason.", T0);
            view.show(missing);
            assertFalse(view.loading());
            assertSame(missing, view.model());
            assertTrue(shows(view, "run-recap-unavailable"));
            EmptyState state = named(view, "run-recap-unavailable", EmptyState.class);
            assertEquals(RunRecapView.UNAVAILABLE_TITLE, state.getAccessibleContext().getAccessibleName());
            assertEquals(missing.unavailable(), state.getAccessibleContext().getAccessibleDescription());
            assertFalse(shows(view, "run-recap-content"));
            assertFalse(shows(view, "run-recap-status"));

            RunRecapModel model = full(REF, RunOutcome.IN_PROGRESS, 1);
            view.show(model);
            assertTrue(shows(view, "run-recap-content"));
            assertFalse(shows(view, "run-recap-unavailable"));
            assertTrue("An in-progress run says when it was read", shows(view, "run-recap-asof"));
            assertEquals("In progress", named(view, "run-recap-outcome", Chip.class).getText());
            Component bag = named(view, "run-recap-loot-bags", JComponent.class).getComponent(0);
            view.show(full(REF, RunOutcome.IN_PROGRESS, 1));
            assertSame("An equal model rebuilds nothing", bag, named(view, "run-recap-loot-bags", JComponent.class).getComponent(0));

            view.showLoading(new VisitRef(REF.sessionId, "v2"));
            assertFalse("Nothing of the previous run stays while another loads", shows(view, "run-recap-content"));
            assertNull(view.model());
        });
    }

    @Test public void lootLinesUseOneWording() {
        assertEquals("1 UT · 2 potions", RunRecapView.LootLine.kinds(List.of(ut(1), potion(2), potion(3))));
        assertEquals("1 ST · 1 potion", RunRecapView.LootLine.kinds(List.of(new LootFacts.Item(4, false, true, false, false), potion(5))));
        assertEquals("", RunRecapView.LootLine.kinds(List.of(new LootFacts.Item(6, false, false, true, false))));
        assertEquals("3 items in 2 bags · 1 UT · 2 potions", RunRecapView.LootLine.section(3, 2, "1 UT · 2 potions"));
        assertEquals("1 item in 1 bag", RunRecapView.LootLine.section(1, 1, ""));
    }

    /** The header writes the entry time and the observed span as the run's card does; the tooltips keep the exact values. */
    @Test public void theHeaderWritesTheEntryTimeAndObservedSpanAsTheCardDoes() throws Exception {
        ZoneId zone = ZoneId.systemDefault();
        long now = T0 + 600_000;
        assertEquals("Entered " + RunCardRenderer.time(T0, zone, now), RunRecapView.entered(T0, zone, now));
        long yesterday = now - 86_400_000L;
        assertEquals("Entered yesterday " + RunCardRenderer.time(yesterday, zone, now).substring("Yesterday ".length()),
            RunRecapView.entered(yesterday, zone, now));
        long older = now - 5 * 86_400_000L;
        assertEquals("Entered " + RunCardRenderer.time(older, zone, now), RunRecapView.entered(older, zone, now));
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            assertEquals("The card's clock", "Entered " + RunCardRenderer.time(T0, zone, now), text(view, "run-recap-entered"));
            String when = named(view, "run-recap-entered", JLabel.class).getToolTipText();
            assertTrue("The full timestamp stays in the tooltip: " + when, when.contains(DisplayFormat.formatTimestamp(T0)));
            assertEquals("The card's duration", RunFeedModel.duration(591_000L) + " observed", text(view, "run-recap-duration"));
            String span = named(view, "run-recap-duration", JLabel.class).getToolTipText();
            assertTrue("The exact span stays in the tooltip: " + span, span.contains(KitFormat.duration(591_000L)));
        });
    }

    /** At any compact width the header's facts wrap between facts, and a "·" never ends (or starts) a line. */
    @Test public void aWrappedHeaderNeverEndsALineWithASeparator() throws Exception {
        RunRecapView[] view = new RunRecapView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = view(full(REF, RunOutcome.COMPLETED, 1)));
        boolean wrapped = false;
        for (int width : new int[] {300, 340, 380, 420, 460, 500, 540, 580, 620, 680}) {
            SwingUtilities.invokeAndWait(() -> evidence.show(view[0], "Run recap", width, 520, 18));
            evidence.settle();
            List<String> lines = RunFeedViewTest.edt(() -> { if (width == 380) evidence.capture("recap-header-380-18"); return view[0].factLines(); });
            for (String line : lines)
                assertFalse(width + " px: a separator ends or starts a line: " + lines, line.endsWith("·") || line.startsWith("·"));
            assertEquals(width + " px: every fact, in order", String.join(" · ", "Entered " + RunCardRenderer.time(T0, ZoneId.systemDefault(), T0 + 600_000),
                "9 m observed", "Party 4", "Wizard #3"), String.join(" · ", lines));
            wrapped |= lines.size() > 1;
        }
        assertTrue("The facts wrap at some width", wrapped);
    }

    /** The Loot line adds only notable kinds to "N items in M bags", never the run summary's "N items" fallback. */
    @Test public void theLootLineAddsOnlyNotableKinds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapModel base = full(REF, RunOutcome.COMPLETED, 1);
            RunRecapModel.Loot plain = new RunRecapModel.Loot(List.of(new RunRecapModel.Loot.Bag("White", T0, List.of(new LootFacts.Item(7, false, false, false, false)))),
                1, "1 item", null);
            RunRecapView view = view(new RunRecapModel(REF, null, base.capturedAt(), base.header(), base.tiles(), base.damage(), plain,
                base.players(), base.resources(), base.timeline(), base.evidence()));
            assertEquals("1 item in 1 bag", text(view, "run-recap-loot-summary"));
            view.show(base);
            assertEquals("3 items in 2 bags · 1 UT · 2 potions", text(view, "run-recap-loot-summary"));
        });
    }

    /** Loot and Players rows give their labels the section's widest label width, so the item slots line up from row to row. */
    @Test public void itemSlotsLineUpFromRowToRow() throws Exception {
        for (String id : new String[] {"loot", "players"}) PropertiesManager.setProperties(Collapsible.PREFIX + "run-recap-" + id, "true");
        RunRecapView[] view = new RunRecapView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = view(full(REF, RunOutcome.COMPLETED, 1)));
        for (int[] size : new int[][] {{1240, 800, 13}, {680, 520, 18}}) {
            SwingUtilities.invokeAndWait(() -> evidence.show(view[0], "Run recap", size[0], size[1], size[2]));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                for (String[] rows : new String[][] {{"run-recap-loot-bags", "run-recap-loot-bag"}, {"run-recap-player-rows", "run-recap-player"}}) {
                    JComponent column = named(view[0], rows[0], JComponent.class);
                    List<Integer> starts = new ArrayList<>();
                    for (Component row : column.getComponents()) {
                        if (!rows[1].equals(row.getName())) continue;
                        ItemSlot first = VisualEvidence.find((Container) row, ItemSlot.class, slot -> true);
                        starts.add(SwingUtilities.convertPoint(first.getParent(), first.getLocation(), column).x);
                    }
                    assertEquals(rows[1] + " rows", 2, starts.size());
                    assertEquals(size[0] + " px: the first slot of each " + rows[1] + " row starts at one x: " + starts, 1, new HashSet<>(starts).size());
                }
                nothingSideways(view[0], "slots-" + size[0]);
            });
        }
    }

    /** A failed read has its own state: "This run could not be read", the reason as its body and the same way back. */
    @Test public void aFailedReadIsNotShownAsAMissingRun() throws Exception {
        String reason = "This run could not be read from saved history: synthetic read failure. Nothing else is shown in its place; open it again to retry.";
        int[] back = {0};
        RunRecapView[] view = new RunRecapView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new RunRecapView(mode);
            view[0].onBack(() -> back[0]++);
            view[0].show(RunRecapModel.unavailable(REF, reason, T0));
            evidence.show(view[0], "Run recap", 1240, 800, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            EmptyState shown = VisualEvidence.find(view[0], EmptyState.class, Component::isVisible);
            assertEquals("This run could not be read", shown.getAccessibleContext().getAccessibleName());
            assertEquals(reason, shown.getAccessibleContext().getAccessibleDescription());
            assertTrue(shown.isShowing());
            assertFalse(shows(view[0], "run-recap-content"));
            evidence.capture("recap-failed-1240-13");
            VisualEvidence.find(shown, AbstractButton.class, button -> true).doClick();
            assertEquals("The same back action", 1, back[0]);

            view[0].show(RunRecapModel.unavailable(REF, "Linked visit unavailable: synthetic reason.", T0));
            shown = VisualEvidence.find(view[0], EmptyState.class, Component::isVisible);
            assertEquals("A run that is not saved keeps its title", RunRecapView.UNAVAILABLE_TITLE, shown.getAccessibleContext().getAccessibleName());
        });
    }

    /**
     * The meter's rank column is narrow and the Player column takes the width beyond the columns' kinds: at 680×520 font 18 the
     * rank, player, damage and DPS show without scrolling the meter's columns; at 1240×800 font 13 every header is whole. The shell's
     * sidebar takes about 88 px of a 680 px window (the evidence's meter viewport is 552 px), so this bare frame is 592 px wide.
     */
    @Test public void theMeterShowsRankPlayerDamageAndDpsAtCompactWidthWithReadableHeaders() throws Exception {
        RunRecapView[] view = new RunRecapView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = view(full(REF, RunOutcome.COMPLETED, 2)));
        for (int[] size : new int[][] {{592, 520, 18}, {1240, 800, 13}}) {
            SwingUtilities.invokeAndWait(() -> evidence.show(view[0], "Run recap", size[0], size[1], size[2]));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                JTable meter = named(view[0], "run-recap-meter", JTable.class);
                JScrollPane scroll = named(view[0], "run-recap-meter-scroll", JScrollPane.class);
                if (size[0] < 600) assertTrue("As narrow as the shell's meter at 680 px: " + scroll.getViewport().getWidth(), scroll.getViewport().getWidth() <= 560);
                Rectangle dps = meter.getCellRect(0, 3, true);
                assertTrue(size[0] + " px: #, Player, Damage and DPS show without scrolling the columns: DPS ends at " + dps.getMaxX() + " in a "
                    + scroll.getViewport().getWidth() + " px viewport", dps.getMaxX() <= scroll.getViewport().getWidth());
                int rank = meter.getColumnModel().getColumn(0).getWidth(), digits = meter.getFontMetrics(meter.getFont()).stringWidth("0000") + 16;
                assertTrue(size[0] + " px: a narrow rank column: " + rank + " > " + digits, rank <= digits);
                for (int c = 0; c < meter.getColumnCount(); c++) {
                    javax.swing.table.TableColumn column = meter.getColumnModel().getColumn(c);
                    int header = meter.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(meter, column.getHeaderValue(), false, false, -1, c)
                        .getPreferredSize().width;
                    assertTrue(size[0] + " px: header " + column.getHeaderValue() + " is whole: " + column.getWidth() + " < " + header, column.getWidth() >= header);
                }
                if (size[0] == 1240) {
                    assertTrue("The meter fills the section", meter.getWidth() == scroll.getViewport().getWidth());
                    int player = meter.getColumnModel().getColumn(1).getWidth();
                    for (int c = 2; c < meter.getColumnCount(); c++)
                        assertTrue("Player takes the extra width: " + player + " vs column " + c, player > meter.getColumnModel().getColumn(c).getWidth());
                }
            });
        }
    }

    @Test public void fitsAt680By520WithFont18WithoutScrollingSideways() throws Exception {
        for (String id : SECTIONS) PropertiesManager.setProperties(Collapsible.PREFIX + "run-recap-" + id, "true");
        RunRecapView[] view = new RunRecapView[1];
        SwingUtilities.invokeAndWait(() -> {
            mode.set(DisplayModeModel.Mode.ANALYST);
            view[0] = view(full(REF, RunOutcome.COMPLETED, 2));
            evidence.show(view[0], "Run recap", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            for (String id : SECTIONS) assertTrue(id + " is open", section(view[0], id).expanded() && section(view[0], id).isShowing());
            nothingSideways(view[0], "recap-680-18");
            JScrollPane page = named(view[0], "run-recap-scroll", JScrollPane.class);
            assertEquals("The page tracks the viewport width", page.getViewport().getWidth(), page.getViewport().getView().getWidth());
            for (String id : new String[] {"dps", "share", "deaths", "fame", "loot", "exalt"}) {
                StatTile tile = named(view[0], "run-recap-tile-" + id, StatTile.class);
                assertTrue(id + " tile is whole: " + tile.getSize() + " < " + tile.getPreferredSize(), tile.getWidth() >= tile.getPreferredSize().width);
            }
            for (String name : new String[] {"run-recap-back", "run-recap-open-loot", "run-recap-open-timeline", "run-recap-open-table"})
                VisualEvidence.completeButton(named(view[0], name, AbstractButton.class));
            VisualEvidence.reachable(named(view[0], "run-recap-recording", JComboBox.class));
            page.getViewport().setViewPosition(new Point(0, 0));
            evidence.capture("recap-analyst-680-18");
            for (String part : new String[] {"run-recap-chart", "run-recap-meter-scroll", "run-recap-sources", "run-recap-loot", "run-recap-players",
                    "run-recap-resources", "run-recap-timeline", "run-recap-evidence"}) {
                scrollTo(page, named(view[0], part, JComponent.class));
                evidence.capture("recap-analyst-680-18-" + part.substring("run-recap-".length()));
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            mode.set(DisplayModeModel.Mode.SIMPLE);
            evidence.show(view[0], "Run recap", 1240, 800, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            nothingSideways(view[0], "recap-1240-13");
            evidence.capture("recap-simple-1240-13");
            view[0].show(empty(REF));
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            nothingSideways(view[0], "recap-empty-1240-13");
            evidence.capture("recap-empty-1240-13");
        });
    }

    // ---- section order ----

    /**
     * Each section header's menu offers Move up, Move down and Reset order; the order persists as {@code ui.order.run-recap} and a
     * new recap reads it. Moving keeps each section (and its open or closed state); Reset returns to the default order.
     */
    @Test public void sectionsMoveFromTheirHeaderMenuPersistAndReset() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            assertEquals("The default order", List.of(SECTIONS), view.sectionOrder());
            assertEquals(List.of(SECTIONS), laidOut(view));
            JPopupMenu first = view.sectionMenu("damage");
            assertEquals("run-recap-section-menu", first.getName());
            assertFalse("The first section cannot move up", item(first, "run-recap-menu-move-up").isEnabled());
            assertTrue(item(first, "run-recap-menu-move-down").isEnabled());
            assertFalse("Nothing to reset in the default order", item(first, "run-recap-menu-reset").isEnabled());
            assertFalse("Evidence is hidden in Simple, so Timeline is the last section shown",
                item(view.sectionMenu("timeline"), "run-recap-menu-move-down").isEnabled());

            Collapsible loot = section(view, "loot");
            boolean lootOpen = loot.expanded(), playersOpen = section(view, "players").expanded();
            JMenuItem up = item(view.sectionMenu("loot"), "run-recap-menu-move-up");
            assertEquals("Move up", up.getText());
            up.doClick();
            List<String> moved = List.of("loot", "damage", "players", "resources", "timeline", "evidence");
            assertEquals(moved, view.sectionOrder());
            assertEquals("The page follows the order", moved, laidOut(view));
            assertEquals("The order persists", String.join(",", moved), PropertiesManager.getProperty(ORDER));
            assertSame("Moving keeps the section itself", loot, section(view, "loot"));
            assertEquals("Moving leaves the open state alone", lootOpen, loot.expanded());
            assertEquals(playersOpen, section(view, "players").expanded());
            for (String id : SECTIONS) {
                String collapse = PropertiesManager.getProperty(Collapsible.PREFIX + "run-recap-" + id);
                assertTrue("Moving writes no collapse state: " + id, collapse == null || collapse.isEmpty());
            }

            RunRecapView again = view(full(REF, RunOutcome.COMPLETED, 1));
            assertEquals("A new recap reads the saved order", moved, again.sectionOrder());
            assertEquals(moved, laidOut(again));

            item(again.sectionMenu("damage"), "run-recap-menu-move-down").doClick();
            assertEquals(List.of("loot", "players", "damage", "resources", "timeline", "evidence"), again.sectionOrder());
            JMenuItem reset = item(again.sectionMenu("players"), "run-recap-menu-reset");
            assertEquals("Reset order", reset.getText());
            assertTrue(reset.isEnabled());
            reset.doClick();
            assertEquals(List.of(SECTIONS), again.sectionOrder());
            assertEquals(List.of(SECTIONS), laidOut(again));
            String cleared = PropertiesManager.getProperty(ORDER);
            assertTrue("Reset forgets the saved order: " + cleared, cleared == null || cleared.isEmpty());
            assertEquals(List.of(SECTIONS), view(full(REF, RunOutcome.COMPLETED, 1)).sectionOrder());
        });
    }

    /** Ctrl+Shift+Up/Down on a focused section header moves it, as the sidebar's rows move; Shift+F10 opens the header's menu. */
    @Test public void ctrlShiftUpAndDownOnAFocusedHeaderMoveItsSection() throws Exception {
        KeyStroke up = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
            down = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            AbstractButton timeline = named(view, "collapsible-run-recap-timeline", AbstractButton.class);
            press(timeline, up);
            assertEquals(List.of("damage", "loot", "players", "timeline", "resources", "evidence"), view.sectionOrder());
            assertEquals("damage,loot,players,timeline,resources,evidence", PropertiesManager.getProperty(ORDER));
            press(named(view, "collapsible-run-recap-damage", AbstractButton.class), down);
            assertEquals(List.of("loot", "damage", "players", "timeline", "resources", "evidence"), view.sectionOrder());
            assertEquals(view.sectionOrder(), laidOut(view));
            press(named(view, "collapsible-run-recap-loot", AbstractButton.class), up);
            assertEquals("The first section stays first", List.of("loot", "damage", "players", "timeline", "resources", "evidence"), view.sectionOrder());
            assertNotNull("Shift+F10 opens the header's menu", timeline.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("shift F10")));
            assertNotNull("The header says how to move it", timeline.getToolTipText());
        });
    }

    /** Saved ids that are not sections are ignored, repeats count once, and sections the saved order lacks follow in the default order. */
    @Test public void aSavedOrderIgnoresUnknownIdsAndAppendsMissingSections() throws Exception {
        PropertiesManager.setProperties(ORDER, "timeline,bogus,Loot,,damage,timeline");
        SwingUtilities.invokeAndWait(() -> {
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            List<String> order = List.of("timeline", "damage", "loot", "players", "resources", "evidence");
            assertEquals(order, view.sectionOrder());
            assertEquals(order, laidOut(view));
            assertEquals("Reading never rewrites the preference", "timeline,bogus,Loot,,damage,timeline", PropertiesManager.getProperty(ORDER));
        });
    }

    /**
     * Evidence stays Analyst-only wherever it is moved; in Simple the hidden Evidence is skipped by Move up and Move down and keeps
     * its place in the saved order.
     */
    @Test public void evidenceStaysAnalystOnlyWhereverItIsMoved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            mode.set(DisplayModeModel.Mode.ANALYST);
            RunRecapView view = view(full(REF, RunOutcome.COMPLETED, 1));
            assertTrue(item(view.sectionMenu("timeline"), "run-recap-menu-move-down").isEnabled());
            for (int i = 0; i < 5; i++) item(view.sectionMenu("evidence"), "run-recap-menu-move-up").doClick();
            assertEquals(List.of("evidence", "damage", "loot", "players", "resources", "timeline"), view.sectionOrder());
            assertEquals(view.sectionOrder(), laidOut(view));
            assertTrue(section(view, "evidence").isVisible());

            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertFalse("Moved first, Evidence is still Analyst only", section(view, "evidence").isVisible());
            assertFalse("The hidden Evidence is not a place to move to", item(view.sectionMenu("damage"), "run-recap-menu-move-up").isEnabled());
            item(view.sectionMenu("damage"), "run-recap-menu-move-down").doClick();
            assertEquals("The hidden Evidence keeps its place", List.of("evidence", "loot", "damage", "players", "resources", "timeline"), view.sectionOrder());
            RunRecapView simple = view(full(REF, RunOutcome.COMPLETED, 1));
            assertEquals(view.sectionOrder(), simple.sectionOrder());
            assertFalse("A new Simple recap hides it too", section(simple, "evidence").isVisible());
        });
    }

    /**
     * The sections in the order the page holds them (the component order, which focus traversal and assistive technology follow),
     * after checking that the sections shown are laid out top to bottom in that order.
     */
    private static List<String> laidOut(RunRecapView view) {
        view.setSize(900, 3000);
        layout(view);
        Container page = section(view, "damage").getParent();
        List<String> order = new ArrayList<>(List.of(SECTIONS));
        order.sort(Comparator.comparingInt(id -> page.getComponentZOrder(section(view, id))));
        int y = Integer.MIN_VALUE;
        for (String id : order) {
            Collapsible section = section(view, id);
            if (!section.isVisible()) continue;
            assertTrue(id + " is laid out below the section before it: " + order, section.getY() > y);
            y = section.getY();
        }
        return order;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container) layout((Container) child);
    }

    private static JMenuItem item(JPopupMenu menu, String name) {
        for (Component child : menu.getComponents()) if (child instanceof JMenuItem && name.equals(child.getName())) return (JMenuItem) child;
        throw new AssertionError("No menu item " + name);
    }

    /** Runs the action {@code stroke} is bound to while {@code target} has focus. */
    private static void press(JComponent target, KeyStroke stroke) {
        Object key = target.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        assertNotNull("Bound on the focused header: " + stroke, key);
        target.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(target, java.awt.event.ActionEvent.ACTION_PERFORMED, null));
    }

    /**
     * No showing scroll pane scrolls or cuts its content sideways: no horizontal scroll bar, and the view no wider than the viewport.
     * A data table scrolls its own columns sideways by design and is listed on standard output instead (as the evidence tests do).
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

    /** Scrolls the page so {@code part} starts at the top of the viewport (as far as the page allows), for a capture. */
    private static void scrollTo(JScrollPane page, JComponent part) {
        JViewport viewport = page.getViewport();
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), viewport.getView());
        int y = Math.max(0, Math.min(placed.y, viewport.getView().getHeight() - viewport.getHeight()));
        viewport.setViewPosition(new Point(0, y));
    }

    private static List<String> texts(Container root, String name) {
        List<String> found = new ArrayList<>();
        collect(root, c -> { if (name.equals(c.getName()) && c instanceof JLabel && c.isVisible()) found.add(((JLabel) c).getText()); });
        return found;
    }
}
