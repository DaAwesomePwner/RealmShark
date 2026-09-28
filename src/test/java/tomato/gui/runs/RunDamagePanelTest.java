package tomato.gui.runs;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.DamageChart;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.Tokens;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import static org.junit.Assert.*;

/**
 * The recap's Damage section over synthetic recordings (synthetic names only): the recording picker, the chart's input, the meter
 * table with the verified local row washed and marked "(you)", unknowns as "—" with their reason, and damage by source.
 */
public class RunDamagePanelTest {
    static final VisitRef REF = new VisitRef("00000000-0000-4000-8000-0000000000a1", "v1");
    static final long T0 = 1_790_000_000_000L;
    private Locale previous;
    private final Map<String, String> modeStore = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(modeStore::get, modeStore::put); // never the application's ui.mode

    @Before public void fix() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    // ---- synthetic Damage sections (shared with RunRecapViewTest) ----

    static RunRecapModel.Damage.Source source(String source, String label, long damage, long hits, RunRecapModel.Damage.Item... items) {
        return new RunRecapModel.Damage.Source(source, label, damage, hits, List.of(items));
    }

    /** Alpha (verified local, rank 1), Bravo (taken and deaths unknown), an unnamed third without sources. */
    static List<RunRecapModel.Damage.Row> rows(boolean local) {
        return List.of(
            new RunRecapModel.Damage.Row(1, "Alpha", 782, 60_000, 1_500.0, 60.0, 120, 900, 4_000L, 1, local, 1, List.of(
                source("WEAPON", "Weapon", 50_000, 100, new RunRecapModel.Damage.Item(2001, 45_000, 90), new RunRecapModel.Damage.Item(2002, 5_000, 10)),
                source("ABILITY", "Ability", 10_000, 20, new RunRecapModel.Damage.Item(2003, 10_000, 20)))),
            new RunRecapModel.Damage.Row(2, "Bravo", 775, 30_000, 750.0, 30.0, 80, 700, null, null, false, 2, List.of(
                source("WEAPON", "Weapon", 30_000, 80))),
            new RunRecapModel.Damage.Row(3, null, 768, 10_000, null, null, 40, 400, 0L, 0, false, 3, List.of()));
    }

    static List<RunRecapModel.Damage.Series> series(boolean local) {
        return List.of(new RunRecapModel.Damage.Series(1, "Alpha", 782, local, new int[] {0, 3000, 6000, 1500}),
            new RunRecapModel.Damage.Series(2, "Bravo", 775, false, new int[] {1000, 1000, 0, 0}),
            new RunRecapModel.Damage.Series(3, null, 768, false, new int[] {0, 500, 500, 0}));
    }

    static List<RunRecapModel.Damage.Recording> recordings(int count) {
        List<RunRecapModel.Damage.Recording> recordings = new ArrayList<>();
        recordings.add(new RunRecapModel.Damage.Recording("rec-a", T0, 40.0, 3));
        if (count > 1) recordings.add(new RunRecapModel.Damage.Recording("rec-b", T0 + 60_000, null, 1));
        return recordings;
    }

    /** A linked recording with its verified local row, saved detail and two hits before the first tick. */
    static RunRecapModel.Damage linked(int recordings) {
        return new RunRecapModel.Damage(recordings(recordings), "rec-a", rows(true), series(true), 1, T0, 100_000, 0, 40.0, 2, null, null, null);
    }

    static RunRecapModel.Damage none() {
        return new RunRecapModel.Damage(List.of(), null, List.of(), List.of(), 1, 0, 0, 0, null, 0, RunRecapBuilder.NO_RECORDING, null, null);
    }

    private RunDamagePanel panel(RunRecapModel.Damage damage) {
        RunDamagePanel panel = new RunDamagePanel(mode);
        panel.show(damage);
        return panel;
    }

    private static JTable meter(Container panel) { return VisualEvidence.named(panel, "run-recap-meter", JTable.class); }

    private static JLabel cell(JTable table, int row, int column) {
        return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
    }

    private static boolean shows(Container root, String name) { return VisualEvidence.named(root, name, JComponent.class).isVisible(); }

    private static String text(Container root, String name) {
        Component component = VisualEvidence.named(root, name, JComponent.class);
        return component instanceof JTextArea ? ((JTextArea) component).getText() : ((JLabel) component).getText();
    }

    // ---- tests ----

    @Test public void theMeterListsEachContributorInRankOrderWithUnknownsAsDashes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunDamagePanel panel = panel(linked(1));
            JTable table = meter(panel);
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < table.getColumnCount(); c++) headers.add(table.getColumnName(c));
            assertEquals(List.of("#", "Player", "Damage", "DPS", "Share", "Hits", "Max hit", "Taken", "Deaths"), headers);
            assertEquals(3, table.getRowCount());
            assertEquals("1", cell(table, 0, 0).getText());
            assertEquals("Alpha (you)", cell(table, 0, 1).getText());
            assertNotNull("A class sprite beside the name", cell(table, 0, 1).getIcon());
            assertEquals("60k", cell(table, 0, 2).getText());
            assertEquals("60,000", cell(table, 0, 2).getToolTipText());
            assertEquals("1.5k", cell(table, 0, 3).getText());
            assertEquals("60%", cell(table, 0, 4).getText());
            assertEquals("120", cell(table, 0, 5).getText());
            assertEquals("900", cell(table, 0, 6).getText());
            assertEquals("4,000", cell(table, 0, 7).getText());
            assertEquals("1", cell(table, 0, 8).getText());
            assertEquals("Bravo", cell(table, 1, 1).getText());
            assertEquals("—", cell(table, 1, 7).getText());
            assertEquals("Incoming damage was not observed for this player", cell(table, 1, 7).getToolTipText());
            assertEquals("—", cell(table, 1, 8).getText());
            assertEquals("Deaths are counted only for a unique name in this recording", cell(table, 1, 8).getToolTipText());
            assertEquals("Unnamed player", cell(table, 2, 1).getText());
            assertEquals("DPS without a hit window is unknown, never 0", "—", cell(table, 2, 3).getText());
            assertNotNull(cell(table, 2, 3).getToolTipText());
            assertEquals("—", cell(table, 2, 4).getText());
            assertEquals("A recorded zero stays 0", "0", cell(table, 2, 7).getText());
            assertEquals("0", cell(table, 2, 8).getText());
            assertEquals("Total 100k damage · 40 s first-to-last hit window · 3 players", text(panel, "run-recap-damage-summary"));
            assertFalse("One recording: no picker", shows(panel, "run-recap-recording"));
            assertFalse(shows(panel, "run-recap-damage-reason"));
            assertFalse(shows(panel, "run-recap-damage-local"));
            assertFalse(shows(panel, "run-recap-damage-detail"));
        });
    }

    @Test public void theVerifiedLocalRowIsWashedAndKeepsYouSoColorIsNotTheOnlyCue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunDamagePanel panel = panel(linked(1));
            JTable table = meter(panel);
            assertEquals("No row is selected at first, so the wash shows", -1, table.getSelectedRow());
            Color wash = Tokens.color(Tokens.Role.ACCENT_WASH);
            for (int c = 0; c < table.getColumnCount(); c++) assertEquals("Column " + c, wash, cell(table, 0, c).getBackground());
            for (int r = 1; r < 3; r++) assertNotEquals(wash, cell(table, r, 1).getBackground());
            assertTrue(cell(table, 0, 1).getText().endsWith("(you)"));
            assertEquals("Damage meter", table.getAccessibleContext().getAccessibleName());

            RunRecapModel.Damage unverified = new RunRecapModel.Damage(recordings(1), "rec-a", rows(false), series(false), 1, T0, 100_000, 0,
                40.0, 0, null, RunRecapBuilder.LOCAL_UNVERIFIED, null);
            panel.show(unverified);
            for (int r = 0; r < 3; r++) {
                assertFalse("No row is yours: " + r, cell(table, r, 1).getText().contains("(you)"));
                assertNotEquals(wash, cell(table, r, 1).getBackground());
            }
            assertTrue(shows(panel, "run-recap-damage-local"));
            assertTrue(text(panel, "run-recap-damage-local"), text(panel, "run-recap-damage-local").contains(RunRecapBuilder.LOCAL_UNVERIFIED));
        });
    }

    @Test public void thePickerShowsOnlyWithSeveralRecordingsAndReportsTheUsersChoice() throws Exception {
        List<String> chosen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            RunDamagePanel panel = panel(linked(2));
            panel.onRecording(chosen::add);
            @SuppressWarnings("unchecked") JComboBox<RunRecapModel.Damage.Recording> picker = VisualEvidence.named(panel, "run-recap-recording", JComboBox.class);
            assertTrue(picker.isVisible());
            assertEquals(2, picker.getItemCount());
            assertEquals(0, picker.getSelectedIndex());
            List<RunRecapModel.Damage.Recording> list = recordings(2);
            assertEquals("Recording 1 of 2 · longest · 40 s window · 3 players", RunDamagePanel.recordingLabel(list, 0));
            assertEquals("Recording 2 of 2 · window unknown · 1 player", RunDamagePanel.recordingLabel(list, 1));
            Component shown = picker.getRenderer().getListCellRendererComponent(new JList<>(), picker.getItemAt(0), 0, false, false);
            assertEquals("Recording 1 of 2 · longest · 40 s window · 3 players", ((JLabel) shown).getText());
            assertEquals("Recording", picker.getAccessibleContext().getAccessibleName());
            picker.setSelectedIndex(1);
            assertEquals(List.of("rec-b"), chosen);
            // The rebuilt model arrives: applying it selects without reporting a choice.
            panel.show(new RunRecapModel.Damage(recordings(2), "rec-b", List.of(), List.of(), 1, 0, 0, 0, null, 0,
                "No player damage was recorded in this recording.", null, null));
            assertEquals(1, picker.getSelectedIndex());
            assertEquals(List.of("rec-b"), chosen);
            assertTrue("A recording without damage still offers the other one", picker.isVisible());
            panel.show(linked(1));
            assertFalse(picker.isVisible());
            assertEquals(List.of("rec-b"), chosen);
        });
    }

    @Test public void selectingARowShowsItsDamageBySource() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunDamagePanel panel = panel(linked(1));
            assertEquals("Your row's sources show first", 1, panel.sourcesRow().objectId());
            assertEquals("Damage by source · Alpha (you)", text(panel, "run-recap-sources-title"));
            JComponent sources = VisualEvidence.named(panel, "run-recap-sources", JComponent.class);
            assertEquals(List.of("Weapon", "Ability"), labels(sources, "run-recap-source-name"));
            assertEquals(List.of("50k · 83.3% · 100 hits", "10k · 16.7% · 20 hits"), labels(sources, "run-recap-source-detail"));
            assertEquals("Top items as sprites", List.of(2001, 2002, 2003), slots(sources));
            JTable table = meter(panel);
            table.setRowSelectionInterval(1, 1);
            assertEquals(2, panel.sourcesRow().objectId());
            assertEquals("Damage by source · Bravo", text(panel, "run-recap-sources-title"));
            assertEquals(List.of("Weapon"), labels(sources, "run-recap-source-name"));
            assertEquals(List.of(), slots(sources));
            table.setRowSelectionInterval(2, 2);
            assertEquals("Damage by source · Unnamed player", text(panel, "run-recap-sources-title"));
            assertTrue(shows(panel, "run-recap-sources-reason"));
            assertEquals("No damage by source was saved for this player.", text(panel, "run-recap-sources-reason"));

            RunRecapModel.Damage noLocal = new RunRecapModel.Damage(recordings(1), "rec-a", rows(false), series(false), 1, T0, 100_000, 0,
                40.0, 0, null, RunRecapBuilder.LOCAL_UNVERIFIED, null);
            panel.show(noLocal);
            assertEquals("Without your row, the top row's sources", 1, panel.sourcesRow().objectId());
            assertEquals("Damage by source · Alpha", text(panel, "run-recap-sources-title"));
        });
    }

    @Test public void noRecordingShowsTheReasonAndNeverTotals() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunDamagePanel panel = panel(none());
            assertTrue(shows(panel, "run-recap-damage-reason"));
            assertEquals(RunRecapBuilder.NO_RECORDING, text(panel, "run-recap-damage-reason"));
            for (String hidden : new String[] {"run-recap-damage-summary", "run-recap-meter-scroll", "run-recap-chart", "run-recap-sources",
                    "run-recap-recording", "run-recap-damage-local", "run-recap-damage-detail"})
                assertFalse(hidden + " is hidden without a recording", shows(panel, hidden));
            assertEquals("Nothing reads as a zero total", "", text(panel, "run-recap-damage-summary"));
        });
    }

    @Test public void aRecordingWithoutSavedDetailSaysWhyTheChartAndSourcesAreMissing() throws Exception {
        String why = "The damage over time and by source of this recording were not saved; only its totals are.";
        SwingUtilities.invokeAndWait(() -> {
            List<RunRecapModel.Damage.Row> bare = new ArrayList<>();
            for (RunRecapModel.Damage.Row row : rows(true)) bare.add(new RunRecapModel.Damage.Row(row.objectId(), row.name(), row.classType(),
                row.damage(), row.dps(), row.share(), row.hits(), row.maxHit(), row.taken(), row.deaths(), row.local(), row.rank(), List.of()));
            RunDamagePanel panel = panel(new RunRecapModel.Damage(recordings(1), "rec-a", bare, List.of(), 1, 0, 100_000, 1_234, 40.0, 0,
                null, null, why));
            assertFalse(shows(panel, "run-recap-chart"));
            assertTrue(shows(panel, "run-recap-damage-detail"));
            assertEquals(why, text(panel, "run-recap-damage-detail"));
            assertTrue(shows(panel, "run-recap-meter-scroll"));
            assertEquals(why, text(panel, "run-recap-sources-reason"));
            assertEquals("Total 100k damage · 40 s first-to-last hit window · 3 players · 1,234 unattributed", text(panel, "run-recap-damage-summary"));
        });
    }

    @Test public void theChartGetsTheSeriesAndTheEarlyHits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunDamagePanel panel = panel(linked(1));
            DamageChart chart = VisualEvidence.named(panel, "run-recap-chart", DamageChart.class);
            assertTrue(chart.isVisible());
            List<String> labels = new ArrayList<>();
            for (DamageChart.Line line : chart.lines()) labels.add(line.label());
            assertEquals(List.of(DamageChart.YOU, DamageChart.TOP), labels);
            assertArrayEquals(new long[] {1000, 1500, 500, 0}, chart.lines().get(1).values());
            assertTrue(chart.description(), chart.description().endsWith("2 early hits are in the totals, not the chart."));
            mode.set(DisplayModeModel.Mode.ANALYST);
            labels.clear();
            for (DamageChart.Line line : chart.lines()) labels.add(line.label());
            assertEquals(List.of("Alpha (you)", "Bravo", "Unnamed player"), labels);
        });
    }

    private static List<String> labels(Container root, String name) {
        List<String> found = new ArrayList<>();
        collect(root, component -> {
            if (component instanceof JLabel && name.equals(component.getName()) && component.isVisible()) found.add(((JLabel) component).getText());
        });
        return found;
    }

    private static List<Integer> slots(Container root) {
        List<Integer> found = new ArrayList<>();
        collect(root, component -> { if (component instanceof ItemSlot && component.isVisible()) found.add(((ItemSlot) component).itemId()); });
        return found;
    }

    static void collect(Container root, java.util.function.Consumer<Component> sink) {
        for (Component child : root.getComponents()) {
            sink.accept(child);
            if (child instanceof Container) collect((Container) child, sink);
        }
    }
}
