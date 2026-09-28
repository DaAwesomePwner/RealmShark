package tomato.gui.runs;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.CombatTimelineChart;
import tomato.gui.kit.*;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
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
        RunRecapModel.Loot loot = new RunRecapModel.Loot(List.of(new RunRecapModel.Loot.Bag("White", T0 + 300_000, List.of(ut(101))),
            new RunRecapModel.Loot.Bag("Purple", T0 + 400_000, List.of(potion(102), potion(103)))), 3, "1 UT · 2 potions", null);
        RunRecapModel.Players players = new RunRecapModel.Players(List.of(
            new RunRecapModel.Players.Player(1, "Alpha", "Wizard", 782, Arrays.asList(2001, 2002, null, -1), 700L, T0 + 10_000),
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
            assertEquals("Observed 9m 51s", text(view, "run-recap-duration"));
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
            assertEquals(List.of("White bag", "Purple bag"), texts(view, "run-recap-loot-bag-name"));
            assertEquals(List.of("1 UT", "2 potions"), texts(view, "run-recap-loot-bag-kinds"));

            assertEquals(RunRecapModel.Players.INSPECT_DAMAGE, text(view, "run-recap-players-damage-label"));
            assertEquals(List.of("Alpha", "Unnamed player"), texts(view, "run-recap-player-name"));
            assertEquals(List.of("Inspect damage 700", "Inspect damage 0"), texts(view, "run-recap-player-damage"));
            assertEquals("2 players seen in this run", text(view, "run-recap-players-count"));

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
