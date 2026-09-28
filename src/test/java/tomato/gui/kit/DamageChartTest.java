package tomato.gui.kit;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import ui.VisualEvidence;
import static org.junit.Assert.*;

/**
 * The run recap's damage-over-time chart: Simple sums the other series into one line beside yours ("Top contributors", or
 * "Others (top 12)" when the saved series do not cover every contributor), Analyst draws one named line per series, fewer than
 * two buckets draw a note, and the accessible description states each line's peak and total plus the early hits left out.
 */
public class DamageChartTest {
    private Locale previous;
    private final Map<String, String> modeStore = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(modeStore::get, modeStore::put); // never the application's ui.mode

    @Before public void fix() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    private static DamageChart.Series you(int... values) { return new DamageChart.Series("Alpha", true, values); }
    private static DamageChart.Series other(String name, int... values) { return new DamageChart.Series(name, false, values); }

    private static List<String> labels(List<DamageChart.Line> lines) {
        List<String> labels = new ArrayList<>();
        for (DamageChart.Line line : lines) labels.add(line.label());
        return labels;
    }

    @Test public void simpleShowsYouBesideTheSumOfTheOtherSeries() {
        List<DamageChart.Series> series = List.of(other("Bravo", 1000, 1000, 0, 0), you(0, 3000, 6000, 1500), other("Charlie", 0, 500, 500, 0));
        List<DamageChart.Line> lines = DamageChart.lines(series, 3, false);
        assertEquals(List.of(DamageChart.YOU, DamageChart.TOP), labels(lines));
        assertEquals(DamageChart.Kind.LOCAL, lines.get(0).kind());
        assertArrayEquals(new long[] {0, 3000, 6000, 1500}, lines.get(0).values());
        assertEquals(DamageChart.Kind.OTHERS, lines.get(1).kind());
        assertArrayEquals("The other series summed per bucket", new long[] {1000, 1500, 500, 0}, lines.get(1).values());
        assertEquals(10_500, lines.get(0).total());
        assertEquals(1, lines.get(1).peakBucket());
        assertEquals(1500, lines.get(1).peak());
        assertEquals("Solo: only your line", List.of(DamageChart.YOU), labels(DamageChart.lines(List.of(you(1, 2)), 1, false)));
    }

    @Test public void theOthersLineSaysTopTwelveWhenTheSeriesDoNotCoverEveryone() {
        List<DamageChart.Series> thirteen = new ArrayList<>();
        thirteen.add(you(5, 5));
        for (int i = 0; i < 12; i++) thirteen.add(other("P" + i, 1, 2));
        assertEquals("13 series for 13 contributors cover everyone", List.of(DamageChart.YOU, DamageChart.TOP), labels(DamageChart.lines(thirteen, 13, false)));
        assertEquals("Others (top 12)", DamageChart.OTHERS_TOP);
        assertEquals("20 contributors: the other line is only the saved top 12", List.of(DamageChart.YOU, DamageChart.OTHERS_TOP),
            labels(DamageChart.lines(thirteen, 20, false)));
        List<DamageChart.Line> lines = DamageChart.lines(thirteen, 20, false);
        assertArrayEquals(new long[] {12, 24}, lines.get(1).values());
        // Your row inside the top 12: 12 series for 13 contributors leave one out.
        assertEquals(DamageChart.OTHERS_TOP, DamageChart.lines(thirteen.subList(0, 12), 13, false).get(1).label());
    }

    @Test public void withoutAVerifiedLocalRowSimpleDrawsOneTopContributorsLine() {
        List<DamageChart.Series> series = List.of(other("Bravo", 1, 2, 3), other(null, 10, 20, 30));
        List<DamageChart.Line> lines = DamageChart.lines(series, 2, false);
        assertEquals(List.of(DamageChart.TOP), labels(lines));
        assertEquals(DamageChart.Kind.OTHERS, lines.get(0).kind());
        assertArrayEquals(new long[] {11, 22, 33}, lines.get(0).values());
        assertEquals("Never labeled as yours", List.of(DamageChart.TOP), labels(DamageChart.lines(series, 30, false)));
    }

    @Test public void analystDrawsOneNamedLinePerSeries() {
        List<DamageChart.Series> series = List.of(other("Bravo", 1, 2), you(3, 4), other(null, 5, 6));
        List<DamageChart.Line> lines = DamageChart.lines(series, 3, true);
        assertEquals(List.of("Bravo", "Alpha (you)", "Unnamed player"), labels(lines));
        assertEquals(List.of(DamageChart.Kind.PLAYER, DamageChart.Kind.LOCAL, DamageChart.Kind.PLAYER),
            List.of(lines.get(0).kind(), lines.get(1).kind(), lines.get(2).kind()));
        assertArrayEquals(new long[] {5, 6}, lines.get(2).values());
        assertEquals("An unnamed local row is still yours", "You",
            DamageChart.lines(List.of(new DamageChart.Series(null, true, new int[] {1, 2})), 1, true).get(0).label());
    }

    @Test public void theDisplayModeChoosesTheLinesAndTheLegend() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DamageChart chart = new DamageChart(mode);
            assertEquals("damage-chart", chart.getName());
            chart.setData(List.of(you(1, 2, 3), other("Bravo", 4, 5, 6), other("Charlie", 7, 8, 9)), 1, 1_000L, 3, 0);
            assertEquals(List.of(DamageChart.YOU, DamageChart.TOP), labels(chart.lines()));
            assertEquals("A legend entry per line", List.of(DamageChart.YOU, DamageChart.TOP), legend(chart));
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(List.of("Alpha (you)", "Bravo", "Charlie"), labels(chart.lines()));
            assertEquals(List.of("Alpha (you)", "Bravo", "Charlie"), legend(chart));
            chart.setData(List.of(other("Bravo", 4, 5, 6)), 1, 1_000L, 1, 0);
            assertFalse("One line needs no legend; the caption names it", VisualEvidence.named(chart, "damage-chart-legend", JComponent.class).isVisible());
            assertEquals("Bravo · damage per second · 1 s buckets", caption(chart));
        });
    }

    @Test public void fewerThanTwoBucketsDrawANoteInsteadOfAChart() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DamageChart chart = new DamageChart(mode);
            chart.setData(List.of(you(1200)), 1, 0L, 1, 0);
            assertFalse(chart.enoughData());
            assertEquals("Not enough data for a chart: fewer than two time buckets were recorded.", chart.description());
            assertEquals(chart.description(), chart.getAccessibleContext().getAccessibleDescription());
            chart.setData(List.of(), 1, 0L, 0, 0);
            assertFalse(chart.enoughData());
            assertTrue(chart.lines().isEmpty());
            assertEquals("No damage over time was recorded.", chart.description());
            paint(chart);   // the note paints without a plot
        });
    }

    @Test public void theDescriptionStatesEachLinesPeakAndTotalAndTheEarlyHits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DamageChart chart = new DamageChart(mode);
            chart.setData(List.of(other("Bravo", 1000, 1000, 0, 0), you(0, 3000, 6000, 1500), other("Charlie", 0, 500, 500, 0)), 1, 5_000L, 3, 2);
            assertTrue(chart.enoughData());
            assertEquals("Damage per second over 4s in 1 s buckets. You: peak 6k at 2s, 10.5k in total. "
                + "Top contributors: peak 1.5k at 1s, 3k in total. 2 early hits are in the totals, not the chart.", chart.description());
            assertEquals("Damage over time", chart.getAccessibleContext().getAccessibleName());
            JTextArea note = VisualEvidence.named(chart, "damage-chart-note", JTextArea.class);
            assertTrue(note.isVisible());
            assertEquals("2 early hits are in the totals, not the chart.", note.getText());
            chart.setData(List.of(you(2000, 4000)), 2, 5_000L, 1, 1);
            assertEquals("Per second in wider buckets", "Damage per second over 4s in 2 s buckets. You: peak 2k at 2s, 6k in total. "
                + "1 early hit is in the totals, not the chart.", chart.description());
            assertEquals("You · damage per second · 2 s buckets", caption(chart));
            chart.setData(List.of(you(2000, 4000)), 1, 5_000L, 1, 0);
            assertFalse("No early hits: no note", note.isVisible());
            assertFalse(chart.description().contains("early"));
        });
    }

    @Test public void hoveringABucketNamesItsTimeAndEachLinesDamage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DamageChart chart = new DamageChart(mode);
            chart.setData(List.of(other("Bravo", 1000, 1000, 0, 0), you(0, 3000, 6000, 1500)), 1, 5_000L, 2, 0);
            assertEquals("1s–2s: You 3,000 · Top contributors 1,000", chart.tooltip(1));
            JComponent plot = VisualEvidence.named(chart, "damage-chart-plot", JComponent.class);
            plot.setSize(400, 200);
            String first = plot.getToolTipText(new MouseEvent(plot, MouseEvent.MOUSE_MOVED, 0, 0, 0, 100, 0, false));
            assertTrue("The left edge is the first bucket: " + first, first.startsWith("0s–1s"));
            mode.set(DisplayModeModel.Mode.ANALYST);
            paint(chart);
        });
    }

    @Test public void paintsInBothModesAtTheFontSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DamageChart chart = new DamageChart(mode);
            List<DamageChart.Series> series = new ArrayList<>();
            series.add(you(0, 3000, 6000, 1500, 200));
            for (int i = 0; i < 12; i++) series.add(other("P" + i, i, 2 * i, 3 * i, i, 0));
            chart.setData(series, 1, 0L, 20, 0);
            assertTrue("The plot's height follows the font", chart.getPreferredSize().height > 100);
            assertEquals("It never asks for width", 0, chart.getMinimumSize().width);
            paint(chart);
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(13, chart.lines().size());
            paint(chart);
        });
    }

    private static void paint(DamageChart chart) {
        chart.setSize(420, chart.getPreferredSize().height);
        chart.doLayout();
        for (Component child : chart.getComponents()) if (child instanceof Container) ((Container) child).doLayout();
        BufferedImage image = new BufferedImage(420, Math.max(1, chart.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        chart.paint(g);
        g.dispose();
    }

    private static String caption(DamageChart chart) { return VisualEvidence.named(chart, "damage-chart-caption", JLabel.class).getText(); }

    private static List<String> legend(DamageChart chart) {
        List<String> entries = new ArrayList<>();
        JComponent legend = VisualEvidence.named(chart, "damage-chart-legend", JComponent.class);
        if (!legend.isVisible()) return entries;
        for (Component child : legend.getComponents()) if (child instanceof JLabel && child.isVisible()) entries.add(((JLabel) child).getText());
        return entries;
    }
}
