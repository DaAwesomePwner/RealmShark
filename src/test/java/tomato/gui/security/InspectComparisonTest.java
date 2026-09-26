package tomato.gui.security;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.*;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.activity.ActivityRouteTarget;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import util.PreferencesStore;

import javax.swing.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;

/** INS-2 provenance and INS-3 detached build comparison over synthetic recorded runs; no capture. */
public class InspectComparisonTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String SESSION = "11111111-2222-3333-4444-555555555555";

    @After public void reset() throws Exception { edt(() -> { ParsePanelGUI.clearPinnedBaseline(); Navigator.install(null); return null; }); }

    static BuildComparison.Build build(int classId, int[] stats, Integer weapon, Long damage, Long start, Long end, VisitRef source) {
        return new BuildComparison.Build("Player7", classId == 782 ? "Wizard" : "Priest", classId, 20, stats, new Integer[]{weapon, 11, -1, 13},
            new String[]{"Staff", "Spell", null, "Ring"}, 5_555, source, "fixture", "Lost Halls", "Completed · Server victory", damage, start, end);
    }

    @Test public void comparisonKeepsMissingFieldsClassChangeOutcomesAndBothDpsWindowsWithoutGearAttribution() {
        BuildComparison.Build baseline = build(782, new int[]{100, 100, 10, 0, 10, 10, 10, 10}, 101, 60_000L, 10_000L, 70_000L, new VisitRef(SESSION, "journal:1"));
        BuildComparison.Build candidate = build(784, new int[]{120, 100, 12, 0, 10, 10, 10, -1}, null, 90_000L, 200_000L, 245_000L, new VisitRef(SESSION, "journal:2"));
        BuildComparison comparison = new BuildComparison(baseline, candidate);
        assertTrue(comparison.classChanged);
        assertEquals(Double.valueOf(1000.0), baseline.dps()); assertEquals(Double.valueOf(2000.0), candidate.dps());
        BuildComparison.Line wisdom = line(comparison, "Base Wisdom");
        assertEquals("Not captured", wisdom.candidate);
        BuildComparison.Line weapon = line(comparison, "Weapon");
        assertEquals("Not captured", weapon.candidate); assertTrue(weapon.note.contains("not compared"));
        assertTrue(line(comparison, "Class").note.contains("not like-for-like"));
        assertEquals("Completed · Server victory", line(comparison, "Outcome").baseline);
        String dps = line(comparison, "Recorded DPS").note;
        assertTrue(dps, dps.contains("different windows") && dps.contains("not attributed to gear"));
        String summary = comparison.summary();
        assertTrue(summary, summary.contains(DisplayFormat.formatDurationSeconds(60_000, 1) + " s") && summary.contains(DisplayFormat.formatDurationSeconds(45_000, 1) + " s"));
        assertTrue(summary.contains(BuildComparison.NO_GEAR_ATTRIBUTION));
        assertTrue(summary.contains("session " + SESSION + " · visit journal:1") && summary.contains("visit journal:2"));
        assertFalse("No causal gear claim", summary.toLowerCase(java.util.Locale.ROOT).contains("because of"));
        BuildComparison.Build unknownWindow = build(782, null, 101, 1_000L, null, null, null);
        assertEquals("Unavailable", line(new BuildComparison(baseline, unknownWindow), "Recorded DPS").candidate);
        assertEquals("Not captured", line(new BuildComparison(baseline, unknownWindow), "Base HP").candidate);
    }
    private static BuildComparison.Line line(BuildComparison comparison, String field) {
        for (BuildComparison.Line line : comparison.lines) if (line.field.equals(field)) return line;
        throw new AssertionError(field);
    }

    static ActivityJournal.Visit run(String id, int classId, long observed, long first, long last, long damage, boolean missingWisdom) {
        ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = id; v.map = "Lost Halls"; v.started = first - 1000; v.lastSeen = v.ended = last + 1000;
        v.completionEvidence = "Server victory"; v.status = "Completed";
        Player player = RequirementResultTest.player(7); player.playerEntity.objectType = classId;
        if (missingWisdom) player.playerEntity.baseStats[7] = -1;
        InspectSnapshot snapshot = new InspectSnapshot(player.playerEntity, observed);
        v.inspectedPlayers.put(snapshot.key(), snapshot); v.inspectedPlayerCount = 1;
        v.damageTracked = true; v.totalDamage = damage; v.playerDamage.put(snapshot.key(), damage); v.firstDamageAt = first; v.lastDamageAt = last;
        return v;
    }

    @Test public void pinnedRecordedBuildKeepsItsSourceVisitAndProducerTimeAcrossRuns() throws Exception {
        RosterDefinitions definitions = RequirementResultTest.definitions();
        ParsePanelGUI roster = edt(() -> new ParsePanelGUI(false, () -> definitions));
        VisitRef first = new VisitRef(SESSION, "journal:1"), second = new VisitRef(SESSION, "journal:2");
        edt(() -> {
            roster.showRun(first, run(first.visitId, 782, 5_555, 10_000, 70_000, 60_000, false)); roster.refreshRoster();
            JTable table = roster.rosterTable(); assertEquals(1, table.getRowCount()); table.setRowSelectionInterval(0, 0);
            assertTrue(roster.selectedOrigin(), roster.selectedOrigin().contains("session " + SESSION + " · visit journal:1"));
            roster.pinSelection();
            assertNotNull(ParsePanelGUI.pinnedBaseline());
            roster.showRun(second, run(second.visitId, 784, 9_999, 200_000, 245_000, 90_000, true)); roster.refreshRoster();
            table.setRowSelectionInterval(0, 0);
            BuildComparison comparison = roster.comparisonForSelection();
            assertEquals("The pinned copy keeps its own source", first, comparison.baseline.source);
            assertEquals(second, comparison.candidate.source);
            assertEquals("Producer build/change time, not the pin or dialog time", 5_555, comparison.baseline.recordedAt);
            assertEquals(9_999, comparison.candidate.recordedAt);
            assertTrue(comparison.classChanged);
            assertEquals(Double.valueOf(1000.0), comparison.baseline.dps()); assertEquals(Double.valueOf(2000.0), comparison.candidate.dps());
            assertTrue(comparison.summary().contains(BuildComparison.NO_GEAR_ATTRIBUTION));
            return null;
        });
    }

    @Test public void routedInspectVisitShowsExactSessionProvenanceInTheSavedRoster() throws Exception {
        Path scratch = temp.newFolder().toPath();
        PreferencesStore preferences = new PreferencesStore(temp.getRoot().toPath().resolve("inspect-route.properties")); preferences.preload();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            // Two consecutive same-name visits; the route must open only the second.
            store.put("runs", "journal:1", run("journal:1", 782, 1_111, 10_000, 70_000, 1, false));
            store.put("runs", "journal:2", run("journal:2", 782, 2_222, 80_000, 90_000, 1, false)); store.flush();
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> inspect =
                edt(() -> SecurityGUI.workspace(store, new JLabel("Live Inspect"), scratch, ViewStateStore.preferences(preferences)));
            int[] page = {0};
            try {
                ShellNavigator navigator = edt(() -> {
                    ShellNavigator created = new ShellNavigator(() -> page[0], value -> page[0] = value, WorkspaceShell::pageOf, 20);
                    Navigator.install(created); created.register(ActivityRouteTarget.of(Destination.INSPECT, inspect)); return created;
                });
                VisitRef ref = new VisitRef(store.currentId(), "journal:2");
                assertTrue(edt(() -> navigator.open(Route.to(Destination.INSPECT).withVisit(ref))));
                assertEquals(2, page[0]);
                await(() -> !inspect.loading() && inspect.displayedPage() != null && named(inspect, ParsePanelGUI.class, null) != null);
                ParsePanelGUI saved = edt(() -> named(inspect, ParsePanelGUI.class, null));
                edt(() -> {
                    assertEquals(1, inspect.displayedPage().matches);
                    saved.refreshRoster(); JTable table = saved.rosterTable(); table.setRowSelectionInterval(0, 0);
                    assertTrue(saved.selectedOrigin(), saved.selectedOrigin().contains("session " + store.currentId() + " · visit journal:2"));
                    saved.pinSelection();
                    assertEquals(2_222, ParsePanelGUI.pinnedBaseline().recordedAt);
                    return null;
                });
            } finally { edt(() -> { inspect.close(); ParsePanelGUI.clear(); return null; }); preferences.shutdown(5, TimeUnit.SECONDS, m -> {}); }
        }
    }
}
