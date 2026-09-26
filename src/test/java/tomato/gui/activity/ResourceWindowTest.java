package tomato.gui.activity;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.enums.ConditionBits;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import util.PreferencesStore;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;

/** COMBAT-5 selected-window resource analysis, chart selection and Timeline handoff; synthetic samples only. */
public class ResourceWindowTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long S = 1_000_000;

    @After public void uninstall() throws Exception { edt(() -> { Navigator.install(null); return null; }); }

    static ActivityJournal.ConditionSlice slice(long start, long end, Integer primary, Integer secondary) {
        ActivityJournal.ConditionSlice s = new ActivityJournal.ConditionSlice(); s.start = start; s.end = end; s.primary = primary; s.secondary = secondary; return s;
    }
    static ActivityJournal.ResourcePoint point(long time, Integer hp, Integer mp) {
        ActivityJournal.ResourcePoint p = new ActivityJournal.ResourcePoint(); p.time = time; p.hp = hp; p.mp = mp; return p;
    }
    static ActivityJournal.Visit visit() {
        ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = "journal:window"; v.map = "Lost Halls"; v.started = S; v.lastSeen = v.ended = S + 20_000;
        v.conditionTimeline.add(slice(S, S + 2_000, ConditionBits.HEALING.value(), null));
        v.conditionTimeline.add(slice(S + 2_000, S + 4_000, 0, null));
        v.conditions.put(ConditionBits.HEALING.name(), 2_000L); v.conditions.put(ConditionBits.BERSERK.name(), 0L);
        v.conditionObservedMillis = 4_000;
        v.resourceTimeline.add(point(S + 100, null, 50)); v.resourceTimeline.add(point(S + 500, 300, 40));
        v.resourceTimeline.add(point(S + 9_000, 100, 90)); v.resourceTimeline.add(point(S + 10_000, 999, 1));
        return v;
    }

    @Test public void twoActiveInFourObservedOfTenSecondsIsFiftyPercentWithSixUnknown() {
        ResourceWindow window = ResourceWindow.of(visit(), S, S + 10_000, false);
        ResourceWindow.Lane healing = lane(window, "HEALING");
        assertEquals(2_000, healing.active); assertEquals(4_000, healing.observed); assertEquals(6_000, healing.unknown);
        assertEquals(50.0, healing.observedUptime(), 1e-9);
        ResourceWindow.Lane berserk = lane(window, "BERSERK");
        assertEquals("Zero-active lanes stay available", 0, berserk.active); assertEquals(0.0, berserk.observedUptime(), 1e-9);
        assertEquals(0, window.extraObserved);
        assertEquals("Raw extrema from samples inside [from, until) only", Integer.valueOf(100), window.hp.min);
        assertEquals(Integer.valueOf(300), window.hp.max); assertEquals(2, window.hp.samples); assertEquals(1, window.hp.missing);
        assertEquals(Integer.valueOf(40), window.mp.min); assertEquals(Integer.valueOf(90), window.mp.max);
        assertTrue(window.summary("Fixture").contains("per-sample maxima are not recorded"));
        ResourceWindow all = ResourceWindow.of(visit(), S, S + 10_000, true);
        assertEquals(ConditionBits.values().length, all.lanes.size());
        ResourceWindow unobserved = ResourceWindow.of(visit(), S + 5_000, S + 7_000, false);
        assertNull("Unknown coverage is not zero uptime", lane(unobserved, "HEALING").observedUptime());
        assertEquals(2_000, lane(unobserved, "HEALING").unknown);
    }

    @Test public void overlappingIntervalsAreClippedAndUnionedWithinHalfOpenBounds() {
        List<ActivityJournal.ConditionSlice> slices = Arrays.asList(slice(0, 3_000, 1, null), slice(1_000, 5_000, 1, null), slice(4_000, 12_000, 3, null), slice(6_000, 7_000, null, null));
        assertEquals(8_000, ResourceWindow.union(slices, 2_000, 10_000, false, 1));
        assertEquals(6_000, ResourceWindow.union(slices, 2_000, 10_000, false, 2));
        assertEquals(0, ResourceWindow.union(slices, 12_000, 13_000, false, 0));
    }

    @Test public void chartSelectsTimestampWindowsByKeyboardAndMouse() throws Exception {
        edt(() -> {
            CombatTimelineChart chart = new CombatTimelineChart(); chart.setSize(900, 420);
            ResourceWindowPanel panel = new ResourceWindowPanel(chart, () -> null);
            chart.setVisit(visit());
            assertEquals("Whole visit without a selection", S, panel.analysis().from);
            chart.getActionMap().get("next-sample").actionPerformed(null); // sample 1
            chart.getActionMap().get("next-sample").actionPerformed(null); // sample 2 at S + 500
            chart.getActionMap().get("window-start").actionPerformed(null);
            chart.getActionMap().get("next-sample").actionPerformed(null); // S + 9 000
            chart.getActionMap().get("window-end").actionPerformed(null);
            assertEquals(Long.valueOf(S + 500), chart.getSelectionStart()); assertEquals(Long.valueOf(S + 9_001), chart.getSelectionEnd());
            assertEquals(S + 500, panel.analysis().from); assertEquals(S + 9_001, panel.analysis().until);
            assertEquals(Integer.valueOf(100), panel.analysis().hp.min);
            assertFalse("Live visits have no saved reference; the handoff is explained", tomato.gui.activity.ActivityArchiveUiTest.named(panel, JButton.class, "resource-timeline-around").isEnabled());
            chart.getActionMap().get("window-clear").actionPerformed(null); assertNull(chart.getSelectionStart());
            chart.dispatchEvent(new MouseEvent(chart, MouseEvent.MOUSE_PRESSED, 0, 0, 300, 60, 1, false, MouseEvent.BUTTON1));
            chart.dispatchEvent(new MouseEvent(chart, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 600, 150, 1, false, MouseEvent.BUTTON1));
            chart.dispatchEvent(new MouseEvent(chart, MouseEvent.MOUSE_RELEASED, 0, 0, 600, 150, 1, false, MouseEvent.BUTTON1));
            assertNotNull("Dragging across the plots selects a window", chart.getSelectionStart());
            assertTrue(chart.getSelectionEnd() > chart.getSelectionStart());
            chart.getActionMap().get("window-all").actionPerformed(null);
            assertEquals(Long.valueOf(S), chart.getSelectionStart()); assertEquals(Long.valueOf(S + 20_000), chart.getSelectionEnd());
            return null;
        });
    }

    @Test public void savedResourceSampleOpensTheExactVisitTimelineAroundIt() throws Exception {
        java.nio.file.Path scratch = temp.newFolder().toPath();
        PreferencesStore preferences = new PreferencesStore(temp.getRoot().toPath().resolve("window.properties")); preferences.preload();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            store.put("runs", "journal:window", visit()); store.flush();
            ViewStateStore states = ViewStateStore.preferences(preferences);
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> resources = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.COMBAT, scratch, states));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> timeline = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.TIMELINE, scratch, states));
            int[] page = {7};
            try {
                edt(() -> {
                    ShellNavigator navigator = new ShellNavigator(() -> page[0], value -> page[0] = value, WorkspaceShell::pageOf, 20);
                    Navigator.install(navigator);
                    navigator.register(ActivityRouteTarget.of(Destination.RESOURCES, resources)); navigator.register(ActivityRouteTarget.of(Destination.TIMELINE, timeline));
                    assertTrue(navigator.open(Route.to(Destination.RESOURCES).withVisit(new VisitRef(store.currentId(), "journal:window"))));
                    return null;
                });
                await(() -> !resources.loading() && resources.displayedPage() != null && tomato.gui.activity.ActivityArchiveUiTest.named(resources, ResourceWindowPanel.class, "resource-window") != null
                    && tomato.gui.activity.ActivityArchiveUiTest.named(resources, CombatTimelineChart.class, "combat-timeline-chart").getVisit() != null);
                edt(() -> {
                    CombatTimelineChart chart = tomato.gui.activity.ActivityArchiveUiTest.named(resources, CombatTimelineChart.class, "combat-timeline-chart");
                    chart.getActionMap().get("first-sample").actionPerformed(null);
                    JButton around = tomato.gui.activity.ActivityArchiveUiTest.named(resources, JButton.class, "resource-timeline-around");
                    assertTrue(around.getToolTipText(), around.isEnabled());
                    around.doClick(); return null;
                });
                assertEquals(11, page[0]);
                ActivityQueries.Filters f = edt(() -> timeline.state().query.facets());
                assertEquals("journal:window", f.visitId);
                assertEquals(Long.valueOf(S + 100 - 30_000), edt(() -> timeline.state().query.bounds().from));
                assertEquals(Long.valueOf(S + 100 + 30_000), edt(() -> timeline.state().query.bounds().until));
            } finally { edt(() -> { resources.close(); timeline.close(); return null; }); preferences.shutdown(5, TimeUnit.SECONDS, m -> {}); }
        }
    }

    private static ResourceWindow.Lane lane(ResourceWindow window, String name) {
        for (ResourceWindow.Lane lane : window.lanes) if (lane.name.equals(name)) return lane;
        throw new AssertionError("Missing lane " + name);
    }
}
