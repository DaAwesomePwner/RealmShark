package tomato.gui.runs;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.kit.Tokens;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;

/** One outcome rule for the feed, the recap and Home: the archive's evidence plus App ended for runs the app never closed. */
public class RunOutcomeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static ActivityJournal.Visit open(long started) {
        ActivityJournal.Visit visit = new ActivityJournal.Visit();
        visit.id = "v"; visit.map = "Lost Halls"; visit.started = started; visit.lastSeen = started + MINUTE;
        return visit;
    }

    @Test public void labelsAndTones() {
        assertEquals("Completed", RunOutcome.COMPLETED.label()); assertEquals(Tokens.Tone.GOOD, RunOutcome.COMPLETED.tone());
        assertEquals("Left", RunOutcome.LEFT.label()); assertEquals(Tokens.Tone.NEUTRAL, RunOutcome.LEFT.tone());
        assertEquals("In progress", RunOutcome.IN_PROGRESS.label()); assertEquals(Tokens.Tone.ACCENT, RunOutcome.IN_PROGRESS.tone());
        assertEquals("App ended", RunOutcome.APP_ENDED.label()); assertEquals(Tokens.Tone.NEUTRAL, RunOutcome.APP_ENDED.tone());
        assertEquals("Unknown", RunOutcome.UNKNOWN.label()); assertEquals(Tokens.Tone.NEUTRAL, RunOutcome.UNKNOWN.tone());
    }

    @Test public void completionEvidenceWinsInEverySessionState() {
        ActivityJournal.Visit evidence = open(1_000); evidence.completionEvidence = "Server victory";
        ActivityJournal.Visit status = open(1_000); status.status = "Completed"; status.ended = 5_000;
        for (boolean ended : new boolean[] {false, true}) for (boolean current : new boolean[] {false, true}) {
            assertEquals(RunOutcome.COMPLETED, RunOutcome.of(evidence, ended, current));
            assertEquals(RunOutcome.COMPLETED, RunOutcome.of(status, ended, current));
        }
    }

    @Test public void aVisitTheJournalClosedIsLeft() {
        ActivityJournal.Visit left = open(1_000); left.ended = 5_000; left.endReason = "Area left; completion unknown";
        for (boolean ended : new boolean[] {false, true}) for (boolean current : new boolean[] {false, true})
            assertEquals(RunOutcome.LEFT, RunOutcome.of(left, ended, current));
    }

    @Test public void theCurrentSessionsOpenVisitIsInProgressAndACrashedSessionsIsAppEnded() {
        ActivityJournal.Visit visit = open(1_000);
        assertEquals("This app run is still recording", RunOutcome.IN_PROGRESS, RunOutcome.of(visit, false, true));
        assertEquals("An earlier launch that never saved its end (a crash): never in progress, as Home shows it",
            RunOutcome.APP_ENDED, RunOutcome.of(visit, false, false));
        assertEquals("A session that saved its end without closing the visit", RunOutcome.APP_ENDED, RunOutcome.of(visit, true, false));
        assertEquals("A saved end wins, as the store closes such a visit", RunOutcome.APP_ENDED, RunOutcome.of(visit, true, true));
        ActivityJournal.Visit closedByHome = open(1_000); closedByHome.ended = 2_000; closedByHome.endReason = "App ended";
        assertEquals("Home's crash rule and the store close such visits with the App ended marker", RunOutcome.APP_ENDED,
            RunOutcome.of(closedByHome, false, true));
    }

    @Test public void unknownWithoutAVisitOrAStartTime() {
        assertEquals(RunOutcome.UNKNOWN, RunOutcome.of(null, false, true));
        assertEquals("No start time: the archive's Unknown outcome", RunOutcome.UNKNOWN, RunOutcome.of(open(0), false, true));
        ActivityJournal.Visit left = open(0); left.ended = 5_000;
        assertEquals("A closed visit is left even without a start time, as the archive reads it", RunOutcome.LEFT, RunOutcome.of(left, false, true));
    }

    @Test public void outsideAppEndedTheRuleIsTheArchivesEvidence() {
        ActivityJournal.Visit completed = open(1_000); completed.completionEvidence = "Final-boss dialogue";
        ActivityJournal.Visit left = open(1_000); left.ended = 3_000; left.endReason = "Connection boundary; completion unknown";
        Map<RunOutcome, ActivityQueries.Outcome> archive = new HashMap<>();
        archive.put(RunOutcome.COMPLETED, ActivityQueries.Outcome.COMPLETED); archive.put(RunOutcome.LEFT, ActivityQueries.Outcome.LEFT);
        archive.put(RunOutcome.IN_PROGRESS, ActivityQueries.Outcome.IN_PROGRESS); archive.put(RunOutcome.UNKNOWN, ActivityQueries.Outcome.UNKNOWN);
        for (ActivityJournal.Visit visit : new ActivityJournal.Visit[] {completed, left, open(1_000), open(0)})
            assertEquals(ActivityQueries.visit(visit).outcome, archive.get(RunOutcome.of(visit, false, true)));
    }

    @Test public void savedVisitsReadThroughTheStoreKeepTheirOutcome() throws Exception {
        Path root = temp.newFolder().toPath();
        write(root);   // Home's fixture: MORNING's c3 was never closed; its session saved its end
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            Map<String, RunOutcome> outcomes = new HashMap<>();
            for (SessionStore.SessionEntry entry : store.catalog()) {
                SessionStore.Session session = entry.session();
                store.read(session.id, "runs", ActivityJournal.Visit.class, (s, visit) ->
                    outcomes.put(visit.id, RunOutcome.of(visit, s.ended > 0, s.id.equals(store.currentId()))));
            }
            assertEquals(RunOutcome.COMPLETED, outcomes.get("c1"));
            assertEquals(RunOutcome.LEFT, outcomes.get("c2"));
            assertEquals("The store closed it when its session ended: App ended, not Left", RunOutcome.APP_ENDED, outcomes.get("c3"));
        }
    }
}
