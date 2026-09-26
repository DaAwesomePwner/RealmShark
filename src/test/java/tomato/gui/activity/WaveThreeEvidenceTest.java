package tomato.gui.activity;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.history.ArchiveNativeSupport.await;
import static ui.WaveThreeEvidence.*;

/**
 * Wave 3 visual evidence for RUN-2, TIME-2 and COMBAT-5 in a real shell with its navigator: the run workbench
 * (linked and "Linked visit unavailable"), the Timeline ±30 s window with a late completion, and the Resources
 * HP/MP plots plus "Selected window" lanes. Synthetic session stores only; no capture.
 */
public class WaveThreeEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);
    private final ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();

    @After public void restore() throws Exception { run(() -> Navigator.install(Navigator.NONE)); }

    private static WorkspaceShell shell(int[] pageNumbers, JComponent... contents) {
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
        for (int i = 0; i < pageNumbers.length; i++) pages[pageNumbers[i]] = contents[i];
        return new WorkspaceShell(pages, () -> fail("Synthetic workspace must not capture"), true);
    }

    private static String detail(JComponent workspace) {
        JTextArea area = ActivityArchiveUiTest.named(workspace, JTextArea.class, "activity-archive-detail");
        return area == null ? "" : area.getText();
    }

    /** The COMBAT-5 fixture (2 s active in 4 s observed of a 10 s window, HEALING plus zero-active BERSERK) at {@code s}. */
    private static ActivityJournal.Visit resourceVisit(long s) {
        ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = "journal:window"; v.map = "Lost Halls"; v.started = s; v.lastSeen = v.ended = s + 20_000;
        v.conditionTimeline.add(ResourceWindowTest.slice(s, s + 2_000, packets.data.enums.ConditionBits.HEALING.value(), null));
        v.conditionTimeline.add(ResourceWindowTest.slice(s + 2_000, s + 4_000, 0, null));
        v.conditions.put(packets.data.enums.ConditionBits.HEALING.name(), 2_000L); v.conditions.put(packets.data.enums.ConditionBits.BERSERK.name(), 0L);
        v.conditionObservedMillis = 4_000;
        v.resourceTimeline.add(ResourceWindowTest.point(s + 100, null, 50)); v.resourceTimeline.add(ResourceWindowTest.point(s + 500, 300, 40));
        v.resourceTimeline.add(ResourceWindowTest.point(s + 9_000, 100, 90)); v.resourceTimeline.add(ResourceWindowTest.point(s + 10_000, 999, 1));
        return v;
    }

    /** Selects [sample 2, sample 3] with the chart's own keyboard actions ([ and ]); a reload may have cleared it. */
    private static CombatTimelineChart selectWindow(JComponent resources) {
        CombatTimelineChart chart = named(resources, "combat-timeline-chart", CombatTimelineChart.class);
        chart.getActionMap().get("first-sample").actionPerformed(null);
        chart.getActionMap().get("next-sample").actionPerformed(null);
        chart.getActionMap().get("window-start").actionPerformed(null);
        chart.getActionMap().get("next-sample").actionPerformed(null);
        chart.getActionMap().get("window-end").actionPerformed(null);
        return chart;
    }

    private static boolean chartLoaded(JComponent resources) {
        CombatTimelineChart chart = ActivityArchiveUiTest.named(resources, CombatTimelineChart.class, "combat-timeline-chart");
        return chart != null && chart.getVisit() != null;
    }

    private static boolean ready(ArchiveWorkspace<?,?,?> workspace) { return !workspace.loading() && workspace.displayedPage() != null; }

    @Test public void runWorkbenchLinkedVisitAndUnavailableVisit() throws Exception {
        Path scratch = temp.newFolder().toPath();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            ActivityJournal.Visit first = ActivityRouteTest.sameName("journal:first", BASE + 100_000), second = ActivityRouteTest.sameName("journal:second", BASE + 160_001);
            second.completionEvidence = "Completion counter"; second.completionObservedAt = second.ended + 4_000; second.status = "Completed";
            second.damageTracked = true; second.totalDamage = 48_200;
            store.put("runs", first.id, first); store.put("runs", second.id, second);
            for (int i = 1; i <= 12; i++) store.put("runs", "other-" + i, ActivityArchiveTest.visit("other-" + i, i));
            store.append("timeline", ActivityRouteTest.at("event-second", second.id, BASE + 170_000));
            store.flush();
            String session = store.currentId();
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.RUNS, scratch, memory.states));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> timeline = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.TIMELINE, scratch, memory.states));
            try {
                WorkspaceShell shell = edt(() -> {
                    WorkspaceShell created = shell(new int[]{10, 11}, runs, timeline);
                    ShellNavigator navigator = created.createNavigator(); Navigator.install(navigator);
                    navigator.register(ActivityRouteTarget.of(Destination.RUNS, runs));
                    navigator.register(ActivityRouteTarget.of(Destination.TIMELINE, timeline));
                    created.select(10); return created;
                });
                await(() -> !runs.loading() && !timeline.loading());
                assertTrue(edt(() -> Navigator.current().open(Route.to(Destination.RUNS).withVisit(new VisitRef(session, second.id)))));
                await(() -> ready(runs) && runs.displayedPage().matches == 1 && runs.state().selected.size() == 1
                    && ActivityArchiveUiTest.named(runs, JPanel.class, "run-workbench") != null && detail(runs).contains("visit " + second.id));
                for (boolean compact : new boolean[]{false, true}) {
                    frame(evidence, shell, "run-workbench-linked", compact,
                        () -> ActivityArchiveUiTest.named(runs, JPanel.class, "run-workbench") != null && detail(runs).contains("visit " + second.id), () -> {
                        JTextArea detail = named(runs, "activity-archive-detail", JTextArea.class);
                        assertTrue(detail.getText(), detail.getText().contains("OUTCOME") && detail.getText().contains("visit " + second.id));
                        assertTrue(detail.getText(), detail.getText().contains(RunWorkbench.OBSERVED_LATER));
                        assertFalse(detail.getText().contains(first.id));
                        JButton open = named(runs, "run-open-timeline", JButton.class);
                        assertTrue(open.getToolTipText() + " / " + named(runs, "run-workbench-unavailable", JTextArea.class).getText(), open.isEnabled());
                    });
                    run(() -> {
                        JComponent actions = named(runs, "run-workbench-actions", JPanel.class);
                        VisualEvidence.reachable(actions);
                        VisualEvidence.reachable(named(runs, "run-workbench-unavailable", JTextArea.class));
                        assertShows(runs, "Timeline around completion evidence (±30 s)", "Open Loot unavailable");
                        capture(SwingUtilities.getWindowAncestor(shell), "run-workbench-linked-actions" + (compact ? "-compact" : "-wide"));
                    });
                    run(() -> {
                        // The grouped evidence (Outcome, Timing / coverage, Progression, Related evidence) scrolled into view.
                        JTextArea detail = named(runs, "activity-archive-detail", JTextArea.class);
                        java.awt.Rectangle outcome = detail.modelToView(detail.getText().indexOf("OUTCOME"));
                        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, detail);
                        java.awt.Rectangle inView = SwingUtilities.convertRectangle(detail, outcome, viewport.getView());
                        viewport.setViewPosition(new java.awt.Point(0, Math.max(0, Math.min(inView.y, viewport.getView().getHeight() - viewport.getExtentSize().height))));
                        assertTrue(detail.getVisibleRect().intersects(outcome));
                        capture(SwingUtilities.getWindowAncestor(shell), "run-workbench-linked-evidence" + (compact ? "-compact" : "-wide"));
                    });
                }
                VisitRef absent = new VisitRef(UUID.randomUUID().toString(), second.id);
                assertTrue(edt(() -> Navigator.current().open(Route.to(Destination.RUNS).withVisit(absent))));
                await(() -> ready(runs) && runs.displayedPage().matches == 0 && runs.state().query.facets().visitSession.equals(absent.sessionId) && detail(runs).contains("Linked visit unavailable"));
                wideAndCompact(evidence, shell, "run-workbench-unavailable", () -> detail(runs).contains("Linked visit unavailable"), () -> {
                    JTextArea detail = named(runs, "activity-archive-detail", JTextArea.class);
                    reveal(detail);
                    String text = detail.getText();
                    assertTrue(text, text.contains("Linked visit unavailable") && text.contains("same dungeon name"));
                    assertShows(shell, "Back to Runs");
                });
            } finally { run(() -> { runs.close(); timeline.close(); evidence.closeWindow(); }); }
        }
    }

    @Test public void timelineThirtySecondWindowWithLateCompletion() throws Exception {
        Path scratch = temp.newFolder().toPath();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            long moment = BASE + 500_000;
            ActivityJournal.Visit visit = ActivityRouteTest.sameName("journal:window", BASE + 400_000);
            visit.completionEvidence = "Completion counter"; visit.ended = BASE + 520_000; visit.completionObservedAt = BASE + 523_500; visit.status = "Completed";
            store.put("runs", visit.id, visit);
            long[] times = {moment - 30_001, moment - 30_000, moment - 12_000, moment - 1, moment, moment + 8_000, moment + 29_999, moment + 30_000};
            for (int i = 0; i < times.length; i++) store.append("timeline", ActivityRouteTest.at("w" + i, visit.id, times[i]));
            store.append("timeline", ActivityRouteTest.at("other-visit", "journal:neighbour", moment));
            store.flush();
            VisitRef ref = new VisitRef(store.currentId(), visit.id);
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.RUNS, scratch, memory.states));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> timeline = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.TIMELINE, scratch, memory.states));
            try {
                WorkspaceShell shell = edt(() -> {
                    WorkspaceShell created = shell(new int[]{10, 11}, runs, timeline);
                    ShellNavigator navigator = created.createNavigator(); Navigator.install(navigator);
                    navigator.register(ActivityRouteTarget.of(Destination.TIMELINE, timeline));
                    created.select(10); return created;
                });
                assertTrue(edt(() -> Navigator.current().open(ActivityRoutes.timelineAround(ref, moment, ActivityRoutes.AROUND_MILLIS))));
                await(() -> ready(timeline) && timeline.displayedPage().matches == 6
                    && ActivityArchiveUiTest.named(timeline, JTextArea.class, "timeline-window") != null
                    && ActivityArchiveUiTest.named(timeline, JTextArea.class, "timeline-window").getText().contains("Linked outcome ·"));
                wideAndCompact(evidence, shell, "timeline-window-observed-later", () -> ready(timeline)
                    && ActivityArchiveUiTest.named(timeline, JTextArea.class, "timeline-window").getText().contains("Linked outcome ·"), () -> {
                    assertEquals(11, shell.getSelectedPage());
                    String banner = named(timeline, "timeline-window", JTextArea.class).getText();
                    assertTrue(banner, banner.contains(RunWorkbench.OBSERVED_LATER) && banner.contains("half-open"));
                    measureText(named(timeline, "timeline-window", JTextArea.class));
                    assertShows(shell, "Back to Runs", "Widen window ±30 s");
                });
                // Unavailable: the same window for a session that is not in this history opens zero events, not a namesake.
                VisitRef absent = new VisitRef(UUID.randomUUID().toString(), visit.id);
                assertTrue(edt(() -> Navigator.current().open(ActivityRoutes.timelineAround(absent, moment, ActivityRoutes.AROUND_MILLIS))));
                await(() -> ready(timeline) && timeline.displayedPage().matches == 0
                    && absent.sessionId.equals(timeline.state().query.facets().visitSession));
                wideAndCompact(evidence, shell, "timeline-window-unavailable", () -> {
                    assertEquals(0, timeline.displayedPage().matches);
                    reveal(named(timeline, "timeline-window", JTextArea.class));
                });
            } finally { run(() -> { runs.close(); timeline.close(); evidence.closeWindow(); }); }
        }
    }

    @Test public void resourcesSelectedWindowPlotsAndZeroActiveLane() throws Exception {
        Path scratch = temp.newFolder().toPath();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            store.put("runs", "journal:window", resourceVisit(BASE)); store.flush();
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> resources = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.COMBAT, scratch, memory.states));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> timeline = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.TIMELINE, scratch, memory.states));
            try {
                WorkspaceShell shell = edt(() -> {
                    WorkspaceShell created = shell(new int[]{7, 11}, resources, timeline);
                    ShellNavigator navigator = created.createNavigator(); Navigator.install(navigator);
                    navigator.register(ActivityRouteTarget.of(Destination.RESOURCES, resources));
                    navigator.register(ActivityRouteTarget.of(Destination.TIMELINE, timeline));
                    created.select(7);
                    assertTrue(navigator.open(Route.to(Destination.RESOURCES).withVisit(new VisitRef(store.currentId(), "journal:window"))));
                    return created;
                });
                await(() -> ready(resources) && ActivityArchiveUiTest.named(resources, ResourceWindowPanel.class, "resource-window") != null
                    && ActivityArchiveUiTest.named(resources, CombatTimelineChart.class, "combat-timeline-chart").getVisit() != null);
                long s = BASE;
                run(() -> selectTab(resources, "Resources & buffs"));
                wideAndCompact(evidence, shell, "resources-hp-mp-plots-selected", () -> chartLoaded(resources), () -> {
                    CombatTimelineChart chart = selectWindow(resources);
                    assertEquals(Long.valueOf(s + 500), chart.getSelectionStart());
                    reveal(chart, 400);
                    assertTrue(chart.isShowing() && chart.getWidth() > 0 && chart.getHeight() > 0);
                });
                run(() -> selectTab(resources, "Selected window"));
                wideAndCompact(evidence, shell, "resources-selected-window", () -> chartLoaded(resources), () -> {
                    selectWindow(resources);
                    ResourceWindowPanel panel = named(resources, "resource-window", ResourceWindowPanel.class);
                    reveal(panel, 400);
                    assertTrue(panel.isShowing());
                    assertTrue(named(panel, "resource-window-summary", JTextArea.class).getText().startsWith("Selected window"));
                    assertEquals(Integer.valueOf(100), panel.analysis().hp.min);
                    JTable lanes = named(panel, "resource-window-lanes", JTable.class);
                    boolean zeroActive = false;
                    for (int r = 0; r < lanes.getRowCount(); r++) zeroActive |= Double.valueOf(0).equals(lanes.getModel().getValueAt(r, 2));
                    assertTrue("A zero-active lane stays listed", zeroActive);
                    assertShows(panel, "zero-active lanes remain listed");
                });
                // Unobserved window: coverage is unknown, not 0% uptime.
                wideAndCompact(evidence, shell, "resources-selected-window-unobserved", () -> chartLoaded(resources), () -> {
                    named(resources, "combat-timeline-chart", CombatTimelineChart.class).setSelection(s + 5_000, s + 7_000);
                    ResourceWindowPanel panel = named(resources, "resource-window", ResourceWindowPanel.class);
                    reveal(panel, 400);
                    for (ResourceWindow.Lane lane : panel.analysis().lanes) assertNull(lane.name, lane.observedUptime());
                });
            } finally { run(() -> { resources.close(); timeline.close(); evidence.closeWindow(); }); }
        }
    }
}
