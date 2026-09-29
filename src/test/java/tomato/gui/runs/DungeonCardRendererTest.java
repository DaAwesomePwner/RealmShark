package tomato.gui.runs;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.Test;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
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

    /**
     * P5b Task 15b (evidence finding 8): no painted caption is cut. A reason too long for its line paints a shorter form that still
     * says why (a short form of the model's fixed reasons, its first sentence or the words before its first clause), never "—" or
     * "…"; the whole reason stays in the line's tooltip and in the accessible name. Checked at the body fonts of 1240×800 font 13
     * and 680×520 font 18 (the cell is fixed and follows the font) for every state and the longest reasons the model writes.
     */
    @Test public void noPaintedCaptionIsCutAtEitherFont() throws Exception {
        List<DungeonCardModel> cards = new ArrayList<>(List.of(full(), partialLoot(), noFinishedRun(), noVerifiedDps(), allUnknown()));
        cards.addAll(longestReasons());
        SwingUtilities.invokeAndWait(() -> {
            Font previous = ContentStyle.body();
            try {
                for (int size : new int[]{13, 18}) {
                    ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size));
                    DungeonCardRenderer renderer = new DungeonCardRenderer(() -> true);
                    Dimension cell = renderer.cellSize();
                    FontMetrics caption = renderer.getFontMetrics(Type.caption());
                    int shortened = 0;
                    for (DungeonCardModel card : cards) {
                        renderer.getListCellRendererComponent(new JList<>(), card, 0, false, false);
                        renderer.setSize(cell);
                        DungeonCardRenderer.Lines lines = renderer.shown();
                        String name = renderer.getAccessibleContext().getAccessibleName();
                        for (int i = 0; i < 4; i++) {
                            DungeonCardRenderer.Fact fact = lines.facts().get(i);
                            String painted = renderer.paintedCaption(i, cell.width, cell.height);
                            int width = renderer.factBounds(i, cell.width, cell.height).width;
                            String where = size + " pt, " + card.canonical() + ", fact " + i + ": '" + painted + "' for '" + fact.note() + "'";
                            assertTrue("Fits its line: " + where, caption.stringWidth(painted) <= width);
                            assertFalse("Never cut with an ellipsis: " + where, painted.endsWith("…"));
                            assertTrue("The whole caption or a shorter form of it: " + where,
                                painted.equals(fact.note()) || DungeonCardRenderer.shorter(fact.note()).contains(painted));
                            if (!painted.equals(fact.note())) shortened++;
                            if (fact.value().endsWith("—")) {
                                assertFalse("An unknown always says why in words: " + where, painted.isBlank() || painted.equals("—"));
                                assertTrue("The whole reason stays in the tooltip: " + where, fact.tip().contains(fact.note()));
                                assertTrue("…and in the accessible name: " + where, name.contains(lower(fact.note())));
                            }
                        }
                    }
                    assertTrue("The longest reasons are shortened at " + size + " pt", shortened > 0);
                }
            } finally {
                ContentStyle.setBodyFont(previous);
            }
        });
        assertEquals("A fixed reason's short form still says why", List.of("Your row was not verified in the recordings"),
            DungeonCardRenderer.shorter(DungeonCardModel.UNVERIFIED_LOCAL));
        assertEquals(List.of("No finished run yet · not counted: 1 unknown", "No finished run yet (Completed, Left or App ended)", "No finished run yet"),
            DungeonCardRenderer.shorter(allUnknown().completionReason()));
        assertEquals(List.of("Best of 2 of 5 completed runs"), DungeonCardRenderer.shorter(LONG_BEST));
    }

    private static final String LONG_BEST = "Best of 2 of 5 completed runs; the others: 1 without a linked recording, 1 whose session's combat"
        + " records could not be read, 1 without your verified row.";

    /** Cards carrying the longest reasons {@link DungeonCardModel} writes, fixed and composed, on every line. */
    static List<DungeonCardModel> longestReasons() {
        List<DungeonCardModel> cards = new ArrayList<>();
        String completion = DungeonCardModel.NO_FINISHED_RUN + " Not counted: 12 in progress, 3 unknown.";
        String[] durations = {DungeonCardModel.NO_OBSERVED_SPAN, DungeonCardModel.NO_COMPLETED_RUN};
        String[] loot = {DungeonCardModel.LOOT_NOT_SAVED, DungeonCardModel.LOOT_UNREADABLE,
            "Loot is unknown for every completed run: 3 in sessions that saved no loot bag, 2 whose sessions' loot could not be read."};
        String[] dps = {DungeonCardModel.NO_RECORDING, DungeonCardModel.COMBAT_UNREADABLE, DungeonCardModel.UNVERIFIED_LOCAL,
            DungeonCardModel.NO_WINDOW, DungeonCardModel.NO_DAMAGE, "No completed run has your verified DPS: 12 without a linked recording,"
            + " 3 whose sessions' combat records could not be read, 2 without your verified row, 1 whose recording has no timed window,"
            + " 4 where your verified row recorded no damage."};
        for (int i = 0; i < dps.length; i++)
            cards.add(new DungeonCardModel("Unknown " + i, "Unknown " + i, 0, 20, 0, 0, 0, 12, 3, null, null, 0, null, 0, 0, null, null, null,
                completion, durations[i % durations.length], loot[i % loot.length], dps[i], 0));
        // Known values with partial reasons: the composed forms.
        cards.add(new DungeonCardModel("Partial", "Partial", 0, 20, 5, 0, 0, 12, 3, 1.0, 600_000L, 3, 2.0, 2, 3, 1_234.0, BEST, "r-partial",
            "Not counted: 12 in progress, 3 unknown.", "2 of 5 completed runs left out (no observed span).",
            "3 of 5 completed runs left out (loot unknown): 2 in sessions that saved no loot bag, 1 whose session's loot could not be read.",
            LONG_BEST, 0));
        return cards;
    }

    /** The renderer's clause rule: first letter lower case, no final period. */
    private static String lower(String sentence) {
        String text = sentence.endsWith(".") ? sentence.substring(0, sentence.length() - 1) : sentence;
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /** No painted value reads as a zero count or rate. */
    private static void noZero(DungeonCardRenderer.Lines lines) {
        for (DungeonCardRenderer.Fact fact : List.of(lines.completion(), lines.duration(), lines.loot(), lines.best()))
            assertFalse("Unknown is never 0: " + fact.value(), fact.value().matches(".*\\b0(\\.0)?\\b.*"));
    }
}
