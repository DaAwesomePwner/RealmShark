package tomato.gui.activity;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.history.link.VisitRef;
import util.PreferencesStore;

import javax.swing.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;
import static tomato.gui.activity.ActivityQueries.*;

/** RUN-2 / TIME-2 headless journeys over real typed workspaces and the shell navigator; synthetic history only. */
public class ActivityRouteTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private PreferencesStore preferences;
    private int[] page = {0};

    @After public void uninstall() throws Exception {
        edt(() -> { Navigator.install(null); return null; });
        if (preferences != null) preferences.shutdown(5, TimeUnit.SECONDS, m -> {});
    }

    private ViewStateStore states() throws Exception {
        preferences = new PreferencesStore(temp.getRoot().toPath().resolve("routes-" + UUID.randomUUID() + ".properties")); preferences.preload();
        return ViewStateStore.preferences(preferences);
    }
    private ShellNavigator navigator() throws Exception {
        return edt(() -> {
            ShellNavigator navigator = new ShellNavigator(() -> page[0], value -> page[0] = value, WorkspaceShell::pageOf, 20);
            Navigator.install(navigator); return navigator;
        });
    }
    static ActivityJournal.Visit sameName(String id, long start) {
        ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = id; v.map = "Lost Halls";
        v.started = start; v.lastSeen = v.ended = start + 60_000; v.status = "Left"; v.endReason = "Left area";
        return v;
    }
    static ActivityJournal.Entry at(String id, String visit, long time) {
        ActivityJournal.Entry e = new ActivityJournal.Entry(); e.id = id; e.visitId = visit; e.map = "Lost Halls"; e.time = time;
        e.kind = "Equipment changed"; e.detail = "Synthetic"; e.values = new LinkedHashMap<>(); return e;
    }

    @Test public void consecutiveSameNameVisitsOpenOnlyTheExactVisitAndBackRestoresTheReviewQueue() throws Exception {
        Path scratch = temp.newFolder().toPath(); ViewStateStore states = states();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            ActivityJournal.Visit first = sameName("journal:first", 100_000), second = sameName("journal:second", 160_001);
            second.completionEvidence = "Completion counter"; second.completionObservedAt = second.ended + 4_000; second.status = "Completed";
            store.put("runs", first.id, first); store.put("runs", second.id, second);
            for (int i = 1; i <= 30; i++) store.put("runs", "other-" + i, ActivityArchiveTest.visit("other-" + i, i));
            store.append("timeline", at("event-first", first.id, 110_000)); store.append("timeline", at("event-second", second.id, 170_000));
            store.flush();
            String session = store.currentId();
            ArchiveWorkspace<Row, Filters, Sort> runs = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.RUNS, scratch, states));
            ArchiveWorkspace<Row, Filters, Sort> timeline = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.TIMELINE, scratch, states));
            try {
                ShellNavigator navigator = navigator();
                edt(() -> {
                    navigator.register(ActivityRouteTarget.of(Destination.RUNS, runs));
                    navigator.register(ActivityRouteTarget.of(Destination.TIMELINE, timeline));
                    assertNull("A live-only component cannot resolve saved visits", ActivityRouteTarget.of(Destination.RUNS, new JPanel()));
                    page[0] = 10;
                    Filters queue = new Filters(); queue.outcomes.add(Outcome.LEFT);
                    runs.changeQuery(runs.state().query.withFacets(queue)); return null;
                });
                await(() -> !runs.loading() && runs.displayedPage() != null && runs.displayedPage().matches > 2);
                edt(() -> { table(runs).setRowSelectionInterval(2, 2); return null; });
                await(() -> runs.state().selected.size() == 1);
                ViewState<Filters, Sort> queue = edt(runs::state);

                VisitRef exact = new VisitRef(session, second.id);
                assertTrue(edt(() -> navigator.open(Route.to(Destination.RUNS).withVisit(exact))));
                await(() -> !runs.loading() && runs.displayedPage().matches == 1 && named(runs, JPanel.class, "run-workbench") != null);
                await(() -> runs.state().selected.size() == 1);
                edt(() -> {
                    assertEquals(second.id, runs.displayedPage().rows.get(0).value.visitId);
                    assertEquals("Exact facet over all sessions; a missing session is unavailable, not a read failure", SessionStore.ALL, runs.state().query.scope());
                    assertEquals(session, runs.state().query.facets().visitSession);
                    assertEquals(runs.displayedPage().rows.get(0).ref, runs.state().selected.get(0));
                    String text = named(runs, JTextArea.class, "activity-archive-detail").getText();
                    for (String group : new String[]{"OUTCOME", "TIMING / COVERAGE", "PROGRESSION", "RELATED EVIDENCE"}) assertTrue(group, text.contains(group));
                    assertTrue(text, text.contains("visit " + second.id)); assertFalse(text, text.contains(first.id));
                    assertTrue("Delayed counter stays at its observation time", text.contains(RunWorkbench.OBSERVED_LATER));
                    assertTrue(named(runs, JButton.class, "run-open-timeline").isEnabled());
                    JButton loot = named(runs, JButton.class, "run-open-loot");
                    assertFalse("Unregistered destinations are explained, not offered", loot.isEnabled());
                    assertTrue(named(runs, JTextArea.class, "run-workbench-unavailable").getText().contains("Open Loot unavailable"));
                    named(runs, JButton.class, "run-open-timeline").doClick(); return null;
                });
                assertEquals(11, page[0]);
                await(() -> !timeline.loading() && timeline.displayedPage() != null && timeline.displayedPage().matches == 1);
                assertEquals("event-second", edt(() -> timeline.displayedPage().rows.get(0).value.recordId));

                assertTrue(edt(navigator::back));
                assertEquals(10, page[0]);
                await(() -> !runs.loading() && runs.displayedPage().matches == 1);
                // Unresolved references (another/deleted session) show an explicit unavailable state.
                VisitRef deleted = new VisitRef(UUID.randomUUID().toString(), second.id);
                assertTrue(edt(() -> navigator.open(Route.to(Destination.RUNS).withVisit(deleted))));
                await(() -> !runs.loading() && runs.displayedPage() != null && runs.displayedPage().matches == 0);
                String missing = edt(() -> named(runs, JTextArea.class, "activity-archive-detail").getText());
                assertTrue(missing, missing.contains("Linked visit unavailable") && missing.contains("same dungeon name"));
                assertTrue(edt(navigator::back)); assertTrue(edt(navigator::back));
                await(() -> !runs.loading() && runs.displayedPage() != null && runs.state().query.equals(queue.query));
                edt(() -> {
                    assertEquals("Back restores the review queue", queue.toJson(), runs.state().toJson());
                    assertEquals(queue.selected.get(0), runs.displayedPage().rows.get(table(runs).getSelectedRow()).ref);
                    assertFalse(navigator.canGoBack()); return null;
                });
                // Non-queryable or ambiguous routes are rejected, not approximated.
                assertFalse(edt(() -> navigator.canOpen(Route.to(Destination.RUNS).withVisit(new VisitRef("not-a-session", "x")))));
                assertFalse(edt(() -> navigator.canOpen(Route.to(Destination.RUNS).withVisit(exact).withBounds(1L, 2L))));
            } finally { edt(() -> { runs.close(); timeline.close(); return null; }); }
        }
    }

    @Test public void thirtySecondWindowShowsAndExportsIdenticalEventsAndLateCompletionStaysLate() throws Exception {
        Path scratch = temp.newFolder().toPath(), output = temp.newFolder().toPath(); ViewStateStore states = states();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            long moment = 500_000;
            ActivityJournal.Visit visit = sameName("journal:window", 400_000);
            visit.completionEvidence = "Completion counter"; visit.ended = 520_000; visit.completionObservedAt = 523_500; visit.status = "Completed";
            store.put("runs", visit.id, visit);
            long[] times = {moment - 30_001, moment - 30_000, moment - 1, moment, moment + 29_999, moment + 30_000};
            for (int i = 0; i < times.length; i++) store.append("timeline", at("w" + i, visit.id, times[i]));
            store.append("timeline", at("other-visit", "journal:neighbour", moment));
            store.append("timeline", at("unassigned", "", moment + 5));
            store.flush();
            VisitRef ref = new VisitRef(store.currentId(), visit.id);
            ArchiveWorkspace<Row, Filters, Sort> timeline = edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.TIMELINE, scratch, states));
            try {
                ShellNavigator navigator = navigator();
                edt(() -> { navigator.register(ActivityRouteTarget.of(Destination.TIMELINE, timeline)); page[0] = 10; return null; });
                Route route = ActivityRoutes.timelineAround(ref, moment, ActivityRoutes.AROUND_MILLIS);
                assertEquals(Long.valueOf(moment - 30_000), route.from); assertEquals(Long.valueOf(moment + 30_000), route.until);
                assertTrue(edt(() -> navigator.open(route)));
                await(() -> !timeline.loading() && timeline.displayedPage() != null);
                await(() -> named(timeline, JTextArea.class, "timeline-window").getText().contains("Linked outcome ·"));
                List<String> displayed = edt(() -> { List<String> ids = new ArrayList<>(); for (ArchiveRow<Row> row : timeline.displayedPage().rows) ids.add(row.value.recordId); return ids; });
                assertEquals("Half-open bounds, exact visit only", Arrays.asList("w1", "w2", "w3", "w4"), displayed);
                ArchiveQuery.Bounds bounds = edt(() -> timeline.state().query.bounds());
                assertEquals(route.from, bounds.from); assertEquals(route.until, bounds.until); assertFalse(bounds.includeUnknown);
                Path exported = edt(() -> timeline.exportTo(output, "window", ExportSelection.all(), ArchiveExport.Format.JSON)).get(20, TimeUnit.SECONDS);
                com.google.gson.JsonObject document = ActivityArchiveTest.json(exported);
                List<String> written = new ArrayList<>();
                for (com.google.gson.JsonElement row : document.getAsJsonArray("rows")) written.add(row.getAsJsonObject().getAsJsonObject("value").get("recordId").getAsString());
                assertEquals("The same query yields identical displayed and exported events", displayed, written);
                assertEquals(edt(() -> timeline.displayedPage().revision), document.getAsJsonObject("manifest").get("revision").getAsString());
                String banner = edt(() -> named(timeline, JTextArea.class, "timeline-window").getText());
                assertTrue(banner, banner.contains(RunWorkbench.OBSERVED_LATER) && banner.contains("3.5 s after the visit ended"));
                assertTrue(banner, banner.contains("half-open"));
                // The window can be adjusted without changing display/export agreement.
                edt(() -> { named(timeline, JButton.class, "timeline-widen-window").doClick(); return null; });
                await(() -> !timeline.loading() && timeline.displayedPage() != null && timeline.displayedPage().matches == 6);
            } finally { edt(() -> { timeline.close(); return null; }); }
        }
    }

    @Test public void workbenchModelKeepsUnknownsAndLeftUnconfirmedHonest() {
        ActivityJournal.Visit visit = sameName("journal:m", 1_000);
        visit.completionEvidence = ""; visit.damageTracked = false;
        Row row = ActivityQueries.visit(visit);
        List<RunWorkbench.Section> sections = RunWorkbench.sections(null, row, visit, java.time.ZoneId.of("UTC"));
        assertEquals(Arrays.asList(RunWorkbench.OUTCOME, RunWorkbench.TIMING, RunWorkbench.PROGRESSION, RunWorkbench.RELATED),
            Arrays.asList(sections.get(0).title, sections.get(1).title, sections.get(2).title, sections.get(3).title));
        String text = RunWorkbench.text(sections);
        assertTrue(text, text.contains("not a recorded failure"));
        assertTrue(text, text.contains("Unlinked"));
        assertTrue(text, text.contains("Recorded damage: —"));
        assertFalse(RunWorkbench.observedLater(visit));
        visit.completionEvidence = "Server victory"; visit.completionObservedAt = visit.ended - 1;
        assertFalse("Evidence before leaving is not late", RunWorkbench.observedLater(visit));
    }
}
