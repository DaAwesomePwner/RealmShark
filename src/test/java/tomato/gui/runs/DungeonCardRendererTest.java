package tomato.gui.runs;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.Test;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/**
 * The painted dungeon card: the lines each state paints (full, partial loot, no finished run, no verified DPS, all unknown),
 * every unknown as "—" with its reason and never 0, the actions each card offers, and the accessible name that says every fact
 * and every reason in words.
 */
public class DungeonCardRendererTest {
    private static final VisitRef BEST = new VisitRef(RunFixtures.C, "c1");

    /**
     * Every fact known: 7 visits, 4 of 6 finished runs completed (1 in progress not counted), a 17 m 30 s average over the 4
     * completed runs, 6.0 items per completed run and a best of 12.3k DPS.
     */
    static DungeonCardModel full() {
        return new DungeonCardModel("Lost Halls", "Lost Halls", 0, 7, 4, 1, 1, 1, 0, 4.0 / 6, 17 * 60_000L + 30_000, 4,
            6.0, 4, 0, 12_345.0, BEST, "r-c1", "Not counted: 1 in progress.", null, null, null, 1_000);
    }

    private static DungeonCardModel partialLoot() {
        DungeonCardModel f = full();
        return new DungeonCardModel(f.canonical(), f.displayName(), 0, f.visits(), f.completed(), f.left(), f.appEnded(), f.inProgress(),
            f.unknown(), f.completionRate(), f.averageDurationMs(), f.durationRuns(), 6.0, 1, 3, f.bestLocalDps(), f.bestRun(), f.bestRecordingId(),
            f.completionReason(), null, "3 of 4 completed runs left out (loot unknown): 3 in a session that saved no loot bag.", null, f.lastVisit());
    }

    /** Two runs in progress, nothing finished: every value but the visits is unknown. */
    private static DungeonCardModel noFinishedRun() {
        return new DungeonCardModel("Snake Pit", "Snake Pit", 0, 2, 0, 0, 0, 2, 0, null, null, 0, null, 0, 0, null, null, null,
            DungeonCardModel.NO_FINISHED_RUN + " Not counted: 2 in progress.", DungeonCardModel.NO_COMPLETED_RUN,
            DungeonCardModel.NO_COMPLETED_RUN, DungeonCardModel.NO_COMPLETED_RUN, 1_000);
    }

    /** Two completed runs whose recordings never verified your row: no best DPS and no best run to open. */
    private static DungeonCardModel noVerifiedDps() {
        return new DungeonCardModel("Ice Citadel", "Ice Citadel", 0, 2, 2, 0, 0, 0, 0, 1.0, 20 * 60_000L, 2, 1.5, 2, 0, null, null, null,
            null, null, null, DungeonCardModel.UNVERIFIED_LOCAL, 1_000);
    }

    /** One run whose outcome is unknown: nothing finished, nothing completed, every value unknown. */
    private static DungeonCardModel allUnknown() {
        return new DungeonCardModel("Pirate Cave", "Pirate Cave", 0, 1, 0, 0, 0, 0, 1, null, null, 0, null, 0, 0, null, null, null,
            DungeonCardModel.NO_FINISHED_RUN + " Not counted: 1 unknown.", DungeonCardModel.NO_COMPLETED_RUN,
            DungeonCardModel.NO_COMPLETED_RUN, DungeonCardModel.NO_COMPLETED_RUN, 0);
    }

    private static void contains(String text, String... facts) {
        for (String fact : facts) assertTrue("'" + fact + "' in: " + text, text.contains(fact));
    }

    @Test public void aFullCardShowsEveryFactObservedWithItsCaptionAndActions() {
        DungeonCardRenderer.Lines lines = DungeonCardRenderer.lines(full(), false);
        assertEquals("Lost Halls", lines.title());
        assertEquals("7 visits", lines.visits());
        assertEquals("Completion 67% observed", lines.completion().value());
        assertEquals("The runs not counted are named under the rate", "Not counted: 1 in progress.", lines.completion().note());
        contains(lines.completion().tip(), "Completed 4 · Left 1 · App ended 1 of 6 finished runs", "Not counted: 1 in progress.",
            DungeonCardModel.OBSERVED);
        assertEquals("Avg 17 m 30 s observed", lines.duration().value());
        assertEquals("", lines.duration().note());
        contains(lines.duration().tip(), "4 completed runs", "not a verified clear time");
        assertEquals("Loot 6.0 per completed run", lines.loot().value());
        assertEquals(DungeonCardModel.LOOT_CAPTION, lines.loot().note());
        assertEquals("Best DPS 12.3k", lines.best().value());
        contains(lines.best().tip(), "Open best run");
        assertEquals("Simple: no Analyze", List.of(DungeonCardRenderer.Action.RUNS, DungeonCardRenderer.Action.BEST), lines.actions());
        assertEquals("Analyst adds Analyze", List.of(DungeonCardRenderer.Action.RUNS, DungeonCardRenderer.Action.BEST,
            DungeonCardRenderer.Action.ANALYZE), DungeonCardRenderer.lines(full(), true).actions());

        String name = DungeonCardRenderer.accessibleName(full(), false);
        contains(name, "Lost Halls", "7 visits", "completion 67% observed", "4 completed, 1 left, 1 app ended of 6 finished runs",
            "not counted: 1 in progress", "left and App ended runs may include clears the app did not see",
            "average duration 17 m 30 s observed over 4 completed runs", "loot 6.0 per completed run, bags linked to the exact run",
            "best DPS 12.3k");
    }

    @Test public void partialLootShowsTheHalfMarkTheExcludedCountAndTheCaption() {
        DungeonCardRenderer.Lines lines = DungeonCardRenderer.lines(partialLoot(), false);
        assertEquals("Loot 6.0 per completed run ◐ 3 excluded", lines.loot().value());
        assertEquals("The caption says which bags count", DungeonCardModel.LOOT_CAPTION, lines.loot().note());
        contains(lines.loot().tip(), "3 of 4 completed runs left out (loot unknown)", DungeonCardModel.LOOT_CAPTION);
        contains(DungeonCardRenderer.accessibleName(partialLoot(), false), "partial, 3 of 4 completed runs left out (loot unknown): 3 in a session that saved no loot bag");
    }

    @Test public void noFinishedRunReadsDashWithReasonsAndNeverZero() {
        DungeonCardModel card = noFinishedRun();
        DungeonCardRenderer.Lines lines = DungeonCardRenderer.lines(card, true);
        assertEquals("2 visits", lines.visits());
        assertEquals("Completion —", lines.completion().value());
        assertEquals(card.completionReason(), lines.completion().note());
        assertEquals("Avg —", lines.duration().value());
        assertEquals(DungeonCardModel.NO_COMPLETED_RUN, lines.duration().note());
        assertEquals("Loot —", lines.loot().value());
        assertEquals(DungeonCardModel.NO_COMPLETED_RUN, lines.loot().note());
        assertEquals("Best DPS —", lines.best().value());
        assertEquals("No best run to open", List.of(DungeonCardRenderer.Action.RUNS, DungeonCardRenderer.Action.ANALYZE), lines.actions());
        noZero(lines);
        String name = DungeonCardRenderer.accessibleName(card, true);
        contains(name, "completion unknown: no finished run yet (Completed, Left or App ended). Not counted: 2 in progress",
            "average duration unknown: no completed run yet", "loot per completed run unknown: no completed run yet",
            "best DPS unknown: no completed run yet");
    }

    @Test public void noVerifiedDpsNamesTheReasonAndOffersNoBestRun() {
        DungeonCardRenderer.Lines lines = DungeonCardRenderer.lines(noVerifiedDps(), false);
        assertEquals("Completion 100% observed", lines.completion().value());
        assertEquals("Avg 20 m observed", lines.duration().value());
        assertEquals("Loot 1.5 per completed run", lines.loot().value());
        assertEquals("Best DPS —", lines.best().value());
        assertEquals(DungeonCardModel.UNVERIFIED_LOCAL, lines.best().note());
        assertEquals(List.of(DungeonCardRenderer.Action.RUNS), lines.actions());
        contains(DungeonCardRenderer.accessibleName(noVerifiedDps(), false),
            "best DPS unknown: the local player's row was not verified in these completed runs' recordings");
    }

    @Test public void anAllUnknownCardShowsOnlyDashesAndReasons() {
        DungeonCardModel card = allUnknown();
        DungeonCardRenderer.Lines lines = DungeonCardRenderer.lines(card, false);
        assertEquals("1 visit", lines.visits());
        for (DungeonCardRenderer.Fact fact : List.of(lines.completion(), lines.duration(), lines.loot(), lines.best())) {
            assertTrue(fact.value(), fact.value().endsWith("—"));
            assertFalse("An unknown always says why: " + fact.value(), fact.note().isEmpty());
        }
        assertEquals(card.completionReason(), lines.completion().note());
        noZero(lines);
        contains(DungeonCardRenderer.accessibleName(card, false), "completion unknown: no finished run yet (Completed, Left or App ended). Not counted: 1 unknown");
    }

    @Test public void theCellIsFixedPaintsEveryStateAndActionsAndTipsFollowTheirRegions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicBoolean analyst = new AtomicBoolean(true);
            DungeonCardRenderer renderer = new DungeonCardRenderer(analyst::get);
            JList<DungeonCardModel> list = new JList<>();
            Dimension cell = renderer.cellSize();
            assertTrue("A card wide enough for its lines: " + cell, cell.width >= 200 && cell.height >= DungeonCardRenderer.PORTAL * 3);
            for (DungeonCardModel card : List.of(full(), partialLoot(), noFinishedRun(), noVerifiedDps(), allUnknown())) {
                Component painted = renderer.getListCellRendererComponent(list, card, 0, true, true);
                assertSame(renderer, painted);
                assertEquals("Every card is the same fixed cell", cell, renderer.getPreferredSize());
                renderer.setSize(cell);
                BufferedImage image = new BufferedImage(cell.width, cell.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try { renderer.paint(g); } finally { g.dispose(); }
                assertEquals(DungeonCardRenderer.accessibleName(card, true), renderer.getAccessibleContext().getAccessibleName());
                Map<DungeonCardRenderer.Action, Rectangle> actions = renderer.actionBounds(card, cell.width, cell.height);
                assertEquals(DungeonCardRenderer.lines(card, true).actions(), List.copyOf(actions.keySet()));
                for (Map.Entry<DungeonCardRenderer.Action, Rectangle> action : actions.entrySet()) {
                    Rectangle r = action.getValue();
                    assertTrue("Inside the card: " + r, new Rectangle(cell).contains(r));
                    assertEquals(action.getKey(), renderer.actionAt(card, r.x + r.width / 2, r.y + r.height / 2, cell.width, cell.height));
                }
                assertNull("The title is no action", renderer.actionAt(card, cell.width / 2, DungeonCardRenderer.GAP, cell.width, cell.height));
            }
            renderer.getListCellRendererComponent(list, full(), 0, false, false);
            renderer.setSize(cell);
            Rectangle completion = renderer.factBounds(0, cell.width, cell.height);
            String tip = renderer.getToolTipText(new MouseEvent(renderer, MouseEvent.MOUSE_MOVED, 0, 0,
                completion.x + 4, completion.y + completion.height / 2, 0, false));
            assertTrue("The completion line's tooltip has its counts and caveat: " + tip, tip.contains(DungeonCardModel.OBSERVED));
            analyst.set(false);
            renderer.getListCellRendererComponent(list, full(), 0, false, false);
            assertFalse("Simple paints no Analyze", renderer.actionBounds(full(), cell.width, cell.height).containsKey(DungeonCardRenderer.Action.ANALYZE));
        });
    }

    /** {@link #full()} with its best run entered at {@code entered}. */
    private static DungeonCardModel dated(Long entered) {
        DungeonCardModel f = full();
        return new DungeonCardModel(f.canonical(), f.displayName(), 0, f.visits(), f.completed(), f.left(), f.appEnded(), f.inProgress(),
            f.unknown(), f.completionRate(), f.averageDurationMs(), f.durationRuns(), f.lootPerCompletedRun(), f.lootRuns(), f.lootExcluded(),
            f.bestLocalDps(), f.bestRun(), f.bestRecordingId(), entered, f.completionReason(), f.durationReason(), f.lootReason(), f.dpsReason(),
            f.lastVisit());
    }

    /**
     * P5b Task 12: the Best DPS line dates the run it names as the run cards date theirs ("14:32" today, "Yesterday 22:10", else
     * "14 Jan 14:32"), and the accessible name says when it was entered; an unknown entry time adds nothing (never a made-up date).
     */
    @Test public void theBestDpsLineDatesItsRunAsTheRunCardsDo() throws Exception {
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        long entered = java.time.ZonedDateTime.of(2026, 1, 14, 14, 32, 0, 0, zone).toInstant().toEpochMilli();
        long later = java.time.ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli();
        long sameDay = java.time.ZonedDateTime.of(2026, 1, 14, 18, 0, 0, 0, zone).toInstant().toEpochMilli();
        DungeonCardModel card = dated(entered);
        assertEquals(Long.valueOf(entered), card.bestEntered());
        assertEquals("Best DPS 12.3k · 14 Jan 14:32", DungeonCardRenderer.lines(card, false, zone, later).best().value());
        assertEquals("Best DPS 12.3k · 14:32", DungeonCardRenderer.lines(card, false, zone, sameDay).best().value());
        assertEquals("The caption is unchanged", DungeonCardRenderer.BEST_NOTE, DungeonCardRenderer.lines(card, false, zone, later).best().note());
        contains(DungeonCardRenderer.lines(card, false, zone, later).best().tip(), "That run was entered 14 Jan 14:32.");
        contains(DungeonCardRenderer.accessibleName(card, false, zone, later), "best DPS 12.3k from your best completed run, entered 14 Jan 14:32");
        contains(DungeonCardRenderer.accessibleName(card, false, zone, sameDay), "entered today at 14:32");

        DungeonCardModel unknown = dated(null);
        assertEquals("An unknown entry time adds no date", "Best DPS 12.3k", DungeonCardRenderer.lines(unknown, false, zone, later).best().value());
        assertFalse(DungeonCardRenderer.accessibleName(unknown, false, zone, later).contains("entered"));

        SwingUtilities.invokeAndWait(() -> {
            DungeonCardRenderer renderer = new DungeonCardRenderer(() -> false, zone, () -> later);
            renderer.getListCellRendererComponent(new JList<>(), card, 0, false, false);
            assertEquals("The painted line", "Best DPS 12.3k · 14 Jan 14:32", renderer.shown().best().value());
            contains(renderer.getAccessibleContext().getAccessibleName(), "entered 14 Jan 14:32");
        });
    }

    /** No painted value reads as a zero count or rate. */
    private static void noZero(DungeonCardRenderer.Lines lines) {
        for (DungeonCardRenderer.Fact fact : List.of(lines.completion(), lines.duration(), lines.loot(), lines.best()))
            assertFalse("Unknown is never 0: " + fact.value(), fact.value().matches(".*\\b0(\\.0)?\\b.*"));
    }
}
