package tomato.gui.dps;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.*;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.roster.RosterStateTestSupport;
import tomato.history.SessionStore;
import tomato.history.encounter.*;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.await;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;

/**
 * Runs & DPS › Recordings (P5b Task 10) over synthetic saved history, this app run's memory and imports (synthetic names and
 * isolated folders only): one row per recording with its run link and saved state, the 30-day scope and All sessions, explicit
 * opening per kind (selection never switches the meter), loading saved full detail (confirmed with its size, read on the reader
 * thread, never evicting the recording on screen, never a second copy), the summary-only panel, one filter row, no path shown,
 * reads on first show and on new data only (S8), and a view state written only when it changed.
 */
public class RecordingsViewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("p5b");
    private static final long NOW = HomeHistoryFixture.NOW, HOUR = HomeHistoryFixture.HOUR, DAY = 24 * HOUR;
    private static final String S1 = HomeHistoryFixture.id("recordings-view-one"), OLD = HomeHistoryFixture.id("recordings-view-old");
    private static final int ENTRY = 1, DUNGEON = 2, SOURCE = 7, CONTEXT = 8, RUN = 9, SAVED = 10;
    private static final String[] PREFERENCES = {"ui.filters.encounter-library.open", CombatSettings.FULL_DETAIL_DAYS};
    private final Map<String, String> preferences = new HashMap<>();
    private final List<SessionStore> stores = new ArrayList<>();
    private final List<DungeonListGUI> libraries = new ArrayList<>();
    private final List<String> calls = new CopyOnWriteArrayList<>(), confirms = new CopyOnWriteArrayList<>();
    private volatile boolean confirmAnswer = true;
    private Path root;
    private DpsData linked, unlinked, full, pruned, old, captured, unsaved;
    private TomatoData data;
    private DpsGUI dps;
    private EncounterCatalog.Entry copy, legacy;

    @Before public void isolate() {
        for (String key : PREFERENCES) { preferences.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
    }

    @After public void restore() throws Exception {
        edt(() -> { for (DungeonListGUI library : libraries) library.close(); evidence.closeWindow(); return null; });
        for (SessionStore store : stores) store.close();
        EncounterImport.beforeRead = () -> { };
        for (Map.Entry<String, String> entry : preferences.entrySet()) PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
    }

    @Test public void oneRowPerRecordingWithItsRunAndSavedStateAndNoPathShown() throws Exception {
        history(); memory();
        DungeonListGUI library = library(null);
        first(library);
        edt(() -> {
            JTable table = table(library);
            assertEquals("Live, 2 of this app run, 2 imports and 4 saved in the last 30 days", 9, table.getRowCount());
            int mine = row(table, "Sprite World", "Captured");
            assertEquals("The recording in memory and its saved summary are one row", "Summary saved", value(table, mine, SAVED));
            assertEquals("Linked · Sprite World · " + DisplayFormat.formatTimestamp(NOW - HOUR), value(table, mine, RUN));
            assertEquals(copy.id.substring(0, 8), value(table, row(table, "Sprite World", "copy.dps"), ENTRY));
            assertEquals("An imported copy is its own row and says so", "Imported file · same recording as Captured · Sprite World",
                value(table, row(table, "Sprite World", "copy.dps"), SAVED));
            int waiting = row(table, "Cave of a Thousand Treasures", "Captured");
            assertEquals(DungeonListGUI.NO_SUMMARY, value(table, waiting, SAVED));
            assertTrue(tip(table, waiting, SAVED), tip(table, waiting, SAVED).contains("in progress"));
            assertEquals("Unlinked", value(table, waiting, RUN));
            int gone = row(table, "Pirate Cave", "Saved history");
            assertEquals("Full detail pruned (kept 30 days)", value(table, gone, SAVED));
            assertEquals("Not loaded", value(table, gone, ENTRY));
            assertEquals("—", rendered(table, gone, CONTEXT));
            String kept = value(table, row(table, "Snake Pit", "Saved history"), SAVED);
            assertTrue(kept, kept.matches("Full detail · [0-9.,]+ (KB|MB)"));
            assertEquals("Summary saved", value(table, row(table, "Ice Citadel", "Saved history"), SAVED));
            assertEquals("Linked · Lost Halls · " + DisplayFormat.formatTimestamp(NOW - 5 * HOUR), value(table, row(table, "Lost Halls", "Saved history"), RUN));
            int oldFile = row(table, null, "legacy.dps");
            assertEquals("Legacy · unlinked", value(table, oldFile, RUN));
            assertEquals("Imported file", value(table, oldFile, SAVED));
            assertEquals("Older than 30 days: listed under All sessions", -1, find(table, "Abyss of Demons", "Saved history"));
            assertNoPath(library);
            return null;
        });
    }

    @Test public void theScopeSwitchesBetweenTheLast30DaysAndAllSessions() throws Exception {
        history(); memory();
        DungeonListGUI library = library(null);
        first(library);
        edt(() -> { assertTrue(summary(library), summary(library).contains("last 30 days")); named(library, "encounter-scope-1", JToggleButton.class).doClick(); return null; });
        read(library, 2);
        edt(() -> {
            assertNotEquals("All sessions lists the older recording", -1, find(table(library), "Abyss of Demons", "Saved history"));
            assertEquals(10, table(library).getRowCount());
            assertTrue(summary(library), summary(library).contains("all sessions"));
            named(library, "encounter-scope-0", JToggleButton.class).doClick(); return null;
        });
        read(library, 3);
        edt(() -> { assertEquals(-1, find(table(library), "Abyss of Demons", "Saved history")); return null; });
    }

    @Test public void openingIsExplicitAndEachKindOpensWhereItLives() throws Exception {
        history(); memory();
        DungeonListGUI library = library(null);
        first(library);
        String shown = edt(dps::currentEncounterId);
        edt(() -> {
            JTable table = table(library);
            for (int row = 0; row < table.getRowCount(); row++) table.setRowSelectionInterval(row, row);
            assertEquals("Recordings is its own tab: selecting never opens or switches the meter", List.of(), calls);
            assertEquals(shown, dps.currentEncounterId());

            open(library, find(table, "Live", null));
            open(library, row(table, "Cave of a Thousand Treasures", "Captured"));
            open(library, row(table, "Sprite World", "Captured"));
            open(library, row(table, "Sprite World", "copy.dps"));
            open(library, row(table, null, "legacy.dps"));
            open(library, row(table, "Lost Halls", "Saved history"));
            EncounterCatalog.Entry mine = entry(captured);
            assertEquals(List.of("live", "encounter " + unsaved.getRecordingId(), "entry " + mine.id, "entry " + copy.id, "entry " + legacy.id,
                "recap " + S1 + "/v-linked " + linked.getRecordingId()), calls);
            assertEquals("Open named what it does", "Open run recap", named(library, "encounter-open", JButton.class).getText());

            table.setRowSelectionInterval(row(table, "Cave of a Thousand Treasures", "Captured"), row(table, "Cave of a Thousand Treasures", "Captured"));
            Action enter = table.getActionMap().get(table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("ENTER")));
            enter.actionPerformed(new java.awt.event.ActionEvent(table, 0, "ENTER"));
            int twice = row(table, "Sprite World", "copy.dps");
            Rectangle cell = table.getCellRect(twice, view(table, DUNGEON), true);
            table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 2, false, MouseEvent.BUTTON1));
            assertEquals("Enter and a double-click open too", List.of("encounter " + unsaved.getRecordingId(), "entry " + copy.id), calls.subList(6, 8));
            return null;
        });

        calls.clear();
        edt(() -> { open(library, row(table(library), "Ice Citadel", "Saved history")); return null; });
        RecordingSummaryPanel summary = edt(() -> named(library, "recording-summary", RecordingSummaryPanel.class));
        await(() -> summary.isVisible() && named(summary, "run-recap-meter", JTable.class).getRowCount() == 2);
        edt(() -> {
            assertEquals("An unlinked summary opens here, read-only", List.of(), calls);
            assertEquals(RecordingSummaryPanel.NOT_KEPT, named(summary, "recording-summary-caption", JLabel.class).getText());
            assertEquals("Summary saved", value(table(library), row(table(library), "Ice Citadel", "Saved history"), SAVED));
            int gone = row(table(library), "Pirate Cave", "Saved history");
            table(library).setRowSelectionInterval(gone, gone);
            assertFalse("Another row closes the summary", summary.isVisible());
            assertEquals("Pruned full detail is never offered", "Show summary", named(library, "encounter-open", JButton.class).getText());
            library.open();
            return null;
        });
        await(() -> summary.isVisible() && named(summary, "run-recap-meter", JTable.class).getRowCount() == 2);
        edt(() -> {
            assertEquals("Summary only: full detail was pruned (kept 30 days)", named(summary, "recording-summary-caption", JLabel.class).getText());
            assertEquals(List.of(), confirms); assertEquals(List.of(), calls);
            named(summary, "recording-summary-close", AbstractButton.class).doClick();
            assertFalse(summary.isVisible());
            assertNoPath(library);
            return null;
        });
    }

    @Test public void savedFullDetailLoadsOnlyWhenConfirmedOnTheReaderThread() throws Exception {
        history(); memory();
        DungeonListGUI library = library(null);
        first(library);
        List<String> readers = new CopyOnWriteArrayList<>();
        EncounterImport.beforeRead = () -> readers.add((SwingUtilities.isEventDispatchThread() ? "EDT " : "") + Thread.currentThread().getName());
        long bytes = Files.size(CombatAutosave.fullDetailFile(root.resolve(S1), full.getRecordingId()));
        confirmAnswer = false;
        edt(() -> {
            int row = row(table(library), "Snake Pit", "Saved history");
            table(library).setRowSelectionInterval(row, row);
            assertEquals("Load full detail (" + DungeonListGUI.size(bytes) + ")…", named(library, "encounter-open", JButton.class).getText());
            library.open(); return null;
        });
        assertEquals(1, confirms.size());
        assertTrue(confirms.get(0), confirms.get(0).startsWith("Snake Pit · " + DungeonListGUI.size(bytes) + " · loads into memory"));
        assertEquals("Declined: nothing is read", List.of(), readers);
        assertTrue(edt(() -> dps.encounters().entries().stream().noneMatch(e -> e.kind() == EncounterCatalog.Kind.SAVED)));

        confirmAnswer = true;
        edt(() -> { library.open(); return null; });
        await(() -> calls.size() == 1);
        assertEquals("Asked again, and confirmed", 2, confirms.size());
        assertEquals(List.of("encounter " + full.getRecordingId()), calls);
        assertEquals("Read on the recording reader thread", List.of(EncounterImport.THREAD), readers);
        EncounterCatalog.Entry loaded = edt(() -> entry(full));
        assertEquals(EncounterCatalog.Kind.SAVED, loaded.kind());
        await(() -> !library.loading() && find(table(library), "Snake Pit", "Saved full detail") >= 0);
        edt(() -> {
            JTable table = table(library);
            assertEquals("The loaded copy joins its row", 9, table.getRowCount());
            int row = row(table, "Snake Pit", "Saved full detail");
            table.setRowSelectionInterval(row, row);
            assertEquals("Open in Live meter", named(library, "encounter-open", JButton.class).getText());
            library.open(); return null;
        });
        assertEquals("In memory now: opened without asking again", 2, confirms.size());
        assertEquals(List.of("encounter " + full.getRecordingId(), "encounter " + full.getRecordingId()), calls);
        assertEquals(1, readers.size());
    }

    @Test public void loadingNeverEvictsTheRecordingOnScreenNorLoadsASecondCopy() throws Exception {
        root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, S1, NOW - 6 * HOUR, NOW - HOUR / 2);
        DpsData[] kept = new DpsData[3];
        for (int i = 0; i < kept.length; i++) { kept[i] = RecordingsSourceTest.plain("Kept " + i, null, NOW - (5 - i) * HOUR, 1, 100 + i); save(S1, kept[i], true, true, false); }
        captured = RecordingsSourceTest.plain("Sprite World", null, NOW - HOUR, 1, 90);
        save(S1, captured, true, true, false);   // this app run's recording whose full detail is saved too
        edt(() -> { data = new TomatoData(); data.dpsData.add(captured); dps = new DpsGUI(data); return null; });
        DungeonListGUI library = library(null);
        edt(() -> {   // as the ENCOUNTER route: the meter shows the recording opened
            library.onOpenEncounter(id -> { calls.add("encounter " + id); EncounterCatalog.Entry shown = entry(id); if (shown != null) dps.showEncounter(shown.id); });
            return null;
        });
        first(library);
        for (int i = 0; i < 2; i++) {
            int index = i;
            edt(() -> { open(library, row(table(library), "Kept " + index, "Saved history")); return null; });
            await(() -> calls.size() == index + 1);
        }
        edt(() -> { assertTrue(dps.showEncounter(entry(kept[0]).id)); return null; });   // back to the first one
        edt(() -> { open(library, row(table(library), "Kept 2", null)); return null; });
        await(() -> calls.size() == 3);
        edt(() -> {
            List<String> saved = new ArrayList<>();
            for (EncounterCatalog.Entry entry : dps.encounters().entries()) if (entry.kind() == EncounterCatalog.Kind.SAVED) saved.add(entry.data.getRecordingId());
            assertEquals("At most two stay; the one on screen is never evicted", List.of(kept[0].getRecordingId(), kept[2].getRecordingId()), saved);
            return null;
        });
        await(() -> !library.loading() && find(table(library), "Kept 1", "Saved history") >= 0 && find(table(library), "Kept 2", "Saved full detail") >= 0);
        int confirmed = confirms.size();
        edt(() -> { open(library, row(table(library), "Sprite World", "Captured")); return null; });
        assertEquals("Its copy in memory opens: no second copy is read", List.of("encounter " + captured.getRecordingId()), calls.subList(3, 4));
        assertEquals(confirmed, confirms.size());

        Files.delete(CombatAutosave.fullDetailFile(root.resolve(S1), kept[1].getRecordingId()));   // pruned after the list was read
        edt(() -> { open(library, row(table(library), "Kept 1", "Saved history")); return null; });
        await(() -> named(library, "encounter-status", JTextArea.class).getText().equals(DungeonListGUI.PRUNED_SINCE));
        assertEquals(4, calls.size());
        edt(() -> { assertNoPath(library); return null; });
    }

    @Test public void oneFilterRowAndClearResetsEveryFilter() throws Exception {
        history(); memory();
        DungeonListGUI library = library(null);
        edt(() -> { evidence.show(library, "recordings", 1240, 800, 13); return null; });
        read(library, 1);
        evidence.settle();
        edt(() -> {
            FilterBar bar = named(library, "encounter-library-filter-bar", FilterBar.class);
            assertFalse(bar.drawerOpen());
            AbstractButton filters = named(bar, "encounter-library-filters", AbstractButton.class);
            Component slot = filters.getParent().getComponent(0);
            int slotY = SwingUtilities.convertPoint(slot, 0, 0, bar).y, filtersY = SwingUtilities.convertPoint(filters, 0, 0, bar).y;
            assertTrue("S6: the search slot and Filters share one row at 1240 × 800, font 13", Math.abs(slotY - filtersY) < filters.getHeight());
            for (String label : new String[]{"Load", "Save checked"}) assertTrue(label, VisualEvidence.button(library, label).isShowing());
            assertTrue(named(library, "encounter-open", JButton.class).isShowing());
            assertTrue(named(library, "encounter-scope", JComponent.class).isShowing());
            evidence.capture("recordings-1240-13");

            named(library, "encounter-search", JTextField.class).setText("Sprite");
            for (String combo : new String[]{"encounter-source", "encounter-link", "encounter-context"}) named(library, combo, JComboBox.class).setSelectedIndex(1);
            assertEquals(4, bar.activeCount());
            AbstractButton clear = named(bar, "encounter-library-clear-filters", AbstractButton.class);
            assertTrue("Reset filters is the bar's Clear", clear.isVisible());
            clear.doClick();
            assertEquals("", named(library, "encounter-search", JTextField.class).getText());
            for (String combo : new String[]{"encounter-source", "encounter-link", "encounter-context"}) assertEquals(combo, 0, named(library, combo, JComboBox.class).getSelectedIndex());
            assertEquals(0, bar.activeCount());
            assertEquals(9, table(library).getRowCount());
            return null;
        });
    }

    @Test public void readsOnFirstShowAndOnNewDataButNotOnEveryShow() throws Exception {
        history(); memory();
        DungeonListGUI library = library(null);
        JTabbedPane tabs = edt(() -> { JTabbedPane pane = new JTabbedPane(); pane.addTab("Recordings", library); pane.addTab("Other", new JPanel()); return pane; });
        edt(() -> null);
        assertEquals("Built, never shown: nothing is read", 0, (int) edt(library::checks));
        edt(() -> { evidence.show(tabs, "recordings-tabs", 1240, 800, 13); return null; });
        read(library, 1);
        edt(() -> { tabs.setSelectedIndex(1); return null; });
        edt(() -> { tabs.setSelectedIndex(0); return null; });
        await(() -> library.checks() >= 2);
        assertEquals("Shown again without new data: the store's stamp is compared, nothing is read", 1, (int) edt(library::reads));

        save(S1, RecordingsSourceTest.plain("Tomb of the Ancients", null, NOW - HOUR, 1, 70), false, false, false);
        edt(() -> { tabs.setSelectedIndex(1); return null; });
        edt(() -> { tabs.setSelectedIndex(0); return null; });
        read(library, 2);
        edt(() -> { assertNotEquals(-1, find(table(library), "Tomb of the Ancients", "Saved history")); return null; });

        edt(() -> { data.dpsData.add(RecordingsSourceTest.plain("Mad Lab", null, NOW - 10 * 60_000L, 1, 60)); DpsGUI.updateMapPacket(data); return null; });
        read(library, 3);   // the catalog's revision changed while the tab shows
        edt(() -> { assertNotEquals(-1, find(table(library), "Mad Lab", "Captured")); return null; });
    }

    @Test public void theViewStateIsWrittenOnlyWhenItChanged() throws Exception {
        history(); memory();
        RosterStateTestSupport.Memory states = new RosterStateTestSupport.Memory();
        DungeonListGUI library = library(states);
        edt(() -> { evidence.show(library, "recordings-state", 1240, 800, 13); return null; });
        read(library, 1);
        evidence.settle();
        edt(() -> { evidence.closeWindow(); return null; });
        assertEquals("Shown, laid out and closed untouched: nothing is written", 0, (int) edt(() -> states.writes));
        edt(() -> { evidence.show(library, "recordings-state", 1240, 800, 13); return null; });
        evidence.settle();
        edt(() -> { named(library, "encounter-search", JTextField.class).setText("Sprite"); evidence.closeWindow(); return null; });
        assertEquals("Changed: written once, when it closes", 1, (int) edt(() -> states.writes));
        assertTrue(states.values.get("ux.archive.encounter-library-live").contains("Sprite"));
    }

    /**
     * P5b Task 15b (evidence finding 2): the view shows Run and Saved right after Dungeon and Recorded start. Simple hides Entry (a
     * library hash), Elapsed, Contributors, Source file and Local context, so its columns fit the Runs &amp; DPS table at 1240×800
     * font 13 (a 1,012 px viewport in the shell) with room for a vertical scroll bar; Analyst shows every column. The model keeps
     * its eleven columns and indices; a mode change re-applies the view and writes nothing; every column's width, shown or hidden,
     * restores through the view state, while widths saved by the older column layout are not applied to this one.
     */
    @Test public void runAndSavedFollowTheDungeonAndSimpleFitsTheDesktopTable() throws Exception {
        history(); memory();
        DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
        RosterStateTestSupport.Memory states = new RosterStateTestSupport.Memory();
        DungeonListGUI library = library(states, mode);
        first(library);
        edt(() -> {
            JTable table = table(library);
            assertEquals(List.of("Export", "Dungeon", "Recorded start", "Run", "Saved", "Damage"), headers(table));
            int width = 0;
            for (int column = 0; column < table.getColumnCount(); column++) width += table.getColumnModel().getColumn(column).getWidth();
            assertTrue("Simple's columns fit the desktop table with room for a vertical scroll bar: " + width + " px", width <= 980);
            assertEquals("The model keeps its eleven columns and indices", 11, table.getModel().getColumnCount());
            assertEquals("Run", table.getModel().getColumnName(RUN));
            assertEquals("Saved", table.getModel().getColumnName(SAVED));
            assertEquals("Sprite World", value(table, row(table, "Sprite World", "Captured"), DUNGEON));

            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals("Analyst shows every column", List.of("Export", "Dungeon", "Recorded start", "Run", "Saved", "Elapsed (s)", "Damage",
                "Contributors", "Source file", "Local context", "Entry"), headers(table));
            table.getColumnModel().getColumn(view(table, ENTRY)).setWidth(133);   // hidden in Simple
            table.getColumnModel().getColumn(view(table, RUN)).setWidth(311);
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals("A mode change re-applies the view", List.of("Export", "Dungeon", "Recorded start", "Run", "Saved", "Damage"), headers(table));
            library.saveViewState();
            return null;
        });
        int written = edt(() -> states.writes);
        edt(() -> { mode.set(DisplayModeModel.Mode.ANALYST); mode.set(DisplayModeModel.Mode.SIMPLE); return null; });
        edt(() -> null);   // a queued view-state change would run here
        assertEquals("Switching modes writes no view state", written, (int) edt(() -> states.writes));

        DungeonListGUI reopened = library(states, mode(DisplayModeModel.Mode.ANALYST));
        edt(() -> {
            JTable table = table(reopened);
            assertEquals("A column hidden in Simple keeps its width", 133, table.getColumnModel().getColumn(view(table, ENTRY)).getWidth());
            assertEquals(311, table.getColumnModel().getColumn(view(table, RUN)).getWidth());
            return null;
        });

        // A view state saved by the older layout (no "columns" marker): its filters restore, its widths do not.
        String saved = states.values.get("ux.archive.encounter-library-live");
        String older = saved.replaceAll(",?\"columns\":\"2\"", "").replace("\"text\":\"\"", "\"text\":\"Snake\"");
        assertTrue("The fixture changed the saved text: " + older, older.contains("Snake") && !older.contains("columns") && older.contains("\"width.1\":\"133\""));
        states.values.put("ux.archive.encounter-library-live", older);
        DungeonListGUI legacy = library(states, mode(DisplayModeModel.Mode.ANALYST));
        edt(() -> {
            JTable table = table(legacy);
            assertEquals("Snake", named(legacy, "encounter-search", JTextField.class).getText());
            assertNotEquals("An older layout's widths are not applied", 133, table.getColumnModel().getColumn(view(table, ENTRY)).getWidth());
            return null;
        });
    }

    /**
     * P5b Task 15b (evidence finding 4): the live row is the first row under every sort and is selected until a recording is
     * chosen, so the tab's primary action reads "Open live meter"; a chosen recording hidden by a filter leaves the live row
     * selected meanwhile and is selected again once it is listed.
     */
    @Test public void theLiveRowIsFirstUnderEverySortAndSelectedUntilARecordingIsChosen() throws Exception {
        history(); memory();
        DungeonListGUI library = library(null);
        first(library);
        edt(() -> {
            JTable table = table(library);
            JButton open = named(library, "encounter-open", JButton.class);
            assertEquals("Under the default Recorded start ↓ order the live row is first", "Live", value(table, 0, DUNGEON));
            assertEquals("…and selected", 0, table.getSelectedRow());
            assertEquals("Open live meter", open.getText());
            for (int column = 0; column < table.getModel().getColumnCount(); column++)
                for (SortOrder order : new SortOrder[]{SortOrder.ASCENDING, SortOrder.DESCENDING}) {
                    table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(column, order)));
                    assertEquals("Sorted by column " + column + " " + order + ": the live row stays first", "Live", value(table, 0, DUNGEON));
                }
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(DUNGEON, SortOrder.ASCENDING)));
            List<String> names = new ArrayList<>();
            for (int row = 1; row < table.getRowCount(); row++) names.add(value(table, row, DUNGEON));
            List<String> sorted = new ArrayList<>(names);
            sorted.sort(java.text.Collator.getInstance());
            assertEquals("The recordings keep their order below it", sorted, names);

            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
            int mine = row(table, "Sprite World", "Captured");
            table.setRowSelectionInterval(mine, mine);
            assertEquals("Open in Live meter", open.getText());
            named(library, "encounter-search", JTextField.class).setText("Snake");
            assertEquals("The chosen recording is filtered out: the live row is selected meanwhile", 0, table.getSelectedRow());
            assertEquals("Open live meter", open.getText());
            named(library, "encounter-search", JTextField.class).setText("");
            assertEquals("…and the chosen recording is selected again once listed", "Sprite World", value(table, table.getSelectedRow(), DUNGEON));
            assertEquals("Open in Live meter", open.getText());
            return null;
        });
    }

    /**
     * P5b Task 15b (evidence finding 5): with nothing recorded, saved or imported, an empty state says what fills the tab (and,
     * without saved history, why saved recordings are missing); with filters hiding every recording it says so and offers Clear
     * filters. Either way the live row stays selected, so Open live meter stays one click away.
     */
    @Test public void anEmptyTabSaysWhyAndStillOpensTheLiveMeter() throws Exception {
        root = temp.newFolder("empty-history").toPath();
        edt(() -> { data = new TomatoData(); dps = new DpsGUI(data); return null; });
        DungeonListGUI library = library(null);
        first(library);
        edt(() -> {
            EmptyState empty = library.emptyState();
            assertNotNull("Only the live row: an empty state says what fills the tab", empty);
            assertEquals("No recordings yet", empty.getAccessibleContext().getAccessibleName());
            String body = empty.getAccessibleContext().getAccessibleDescription();
            assertTrue(body, body.contains("when a fight closes") && body.contains("All sessions") && body.contains("Load"));
            assertFalse("The lone live row gives way to it", scroll(table(library)).isVisible());
            JButton open = named(library, "encounter-open", JButton.class);
            assertEquals("Open live meter", open.getText());
            assertTrue(open.isEnabled());
            open.doClick();
            assertEquals(List.of("live"), calls);
            assertTrue(summary(library), summary(library).startsWith("0 of 0 recordings shown · last 30 days · 0 checked for export"));
            return null;
        });

        DungeonListGUI memoryOnly = edt(() -> {
            DungeonListGUI made = new DungeonListGUI(dps, data, null, () -> null, () -> NOW, mode(DisplayModeModel.Mode.SIMPLE));
            libraries.add(made);
            return made;
        });
        first(memoryOnly);
        edt(() -> {
            String body = memoryOnly.emptyState().getAccessibleContext().getAccessibleDescription();
            assertTrue("Without saved history the reason says so: " + body, body.contains("Saved history is not open in this app run"));
            return null;
        });

        history(); memory();
        DungeonListGUI full = library(null);
        first(full);
        edt(() -> {
            assertNull("Recordings listed: no empty state", full.emptyState());
            assertTrue(scroll(table(full)).isVisible());
            named(full, "encounter-search", JTextField.class).setText("no such recording");
            EmptyState empty = full.emptyState();
            assertNotNull(empty);
            assertEquals("No recordings match", empty.getAccessibleContext().getAccessibleName());
            assertEquals("Open live meter", named(full, "encounter-open", JButton.class).getText());
            AbstractButton clear = named(empty, "encounter-empty-action", AbstractButton.class);
            assertEquals("Clear filters", clear.getText());
            clear.doClick();
            assertEquals("", named(full, "encounter-search", JTextField.class).getText());
            assertNull(full.emptyState());
            assertTrue(scroll(table(full)).isVisible());
            assertEquals(9, table(full).getRowCount());
            return null;
        });
    }

    /**
     * P5b Task 15b (evidence finding 6): at 680×520 font 18 at least three table rows show before the page scrolls. Save view
     * state and Reset saved view state live in the filter bar's ⋯ menu (their status shows only when it is a failure), and Load
     * and Save checked stay visible buttons.
     */
    @Test public void compactShowsThreeRowsAndTheViewStateActionsLiveInTheMoreMenu() throws Exception {
        history(); memory();
        RosterStateTestSupport.Memory states = new RosterStateTestSupport.Memory();
        DungeonListGUI library = library(states);
        edt(() -> { evidence.show(library, "recordings-compact", 680, 520, 18); return null; });
        read(library, 1);
        evidence.settle();
        edt(() -> {
            evidence.capture("recordings-680-18");
            JTable table = table(library);
            int rows = table.getVisibleRect().height / table.getRowHeight();
            assertTrue("At 680 × 520, font 18, at least three rows show before the page scrolls: " + rows, rows >= 3);
            OverflowMenu more = named(library, "encounter-library-more", OverflowMenu.class);
            assertNotNull(more.item("Save view state"));
            assertNotNull(more.item("Reset saved view state"));
            for (String label : new String[]{"Save view state", "Reset saved view state"})
                assertNull("Not a button on the page: " + label, button(library, label));
            for (String label : new String[]{"Load", "Save checked"}) assertTrue(label, VisualEvidence.button(library, label).isShowing());
            assertFalse("The view state's status shows only when it is a failure", named(library, "encounter-view-state", Banner.class).isVisible());
            states.fail = true;
            more.item("Save view state").doClick();
            return null;
        });
        await(() -> named(library, "encounter-view-state", Banner.class).isVisible());
        edt(() -> {
            assertTrue(named(library, "encounter-view-state", Banner.class).text().startsWith("View state save failed"));
            return null;
        });
    }

    // ---- fixtures ----

    /** Saved history: S1 (recent) with linked, unlinked, full-detail, pruned and this app run's records; OLD (40 days ago). */
    private void history() throws Exception {
        root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, S1, NOW - 6 * HOUR, NOW - HOUR / 2);
        HomeHistoryFixture.session(root, OLD, NOW - 41 * DAY, NOW - 39 * DAY);
        linked = RecordingsSourceTest.plain("Lost Halls", new VisitRef(S1, "v-linked"), NOW - 5 * HOUR, 1, 300);
        save(S1, linked, false, false, true);
        unlinked = RecordingsSourceTest.plain("Ice Citadel", null, NOW - 4 * HOUR, 1, 200);
        save(S1, unlinked, false, false, true);
        full = RecordingsSourceTest.plain("Snake Pit", null, NOW - 3 * HOUR, 1, 150);
        save(S1, full, true, true, false);
        pruned = RecordingsSourceTest.plain("Pirate Cave", null, NOW - 2 * HOUR, 1, 120);
        save(S1, pruned, true, false, true);
        old = RecordingsSourceTest.plain("Abyss of Demons", null, NOW - 40 * DAY, 1, 100);
        save(OLD, old, false, false, false);
        captured = RecordingsSourceTest.plain("Sprite World", new VisitRef(S1, "v-now"), NOW - HOUR, 1, 90);
        save(S1, captured, false, false, false);
        unsaved = RecordingsSourceTest.plain("Cave of a Thousand Treasures", null, NOW - HOUR / 2, 1, 80);
    }

    /** This app run: two captured recordings, an imported copy of one of them and a legacy import. */
    private void memory() throws Exception {
        edt(() -> { data = new TomatoData(); data.dpsData.add(captured); data.dpsData.add(unsaved); dps = new DpsGUI(data); return null; });
        Path copyFile = writeDps(temp.getRoot().toPath().resolve("copy.dps"), captured.getSaveFile(false));
        Path legacyFile = temp.getRoot().toPath().resolve("legacy.dps"); Files.write(legacyFile, EncounterImportFilterTest.baseline());
        EncounterImport copied = EncounterImport.read(copyFile), old = EncounterImport.read(legacyFile);
        edt(() -> { copy = dps.encounters().add(copied).entry; legacy = dps.encounters().add(old).entry; return null; });
    }

    /** A library in Analyst mode (every column in view, so a cell of any column can be rendered). */
    private DungeonListGUI library(RosterStateTestSupport.Memory states) throws Exception { return library(states, mode(DisplayModeModel.Mode.ANALYST)); }

    private DungeonListGUI library(RosterStateTestSupport.Memory states, DisplayModeModel mode) throws Exception {
        SessionStore store = new SessionStore(root, false, "test");
        stores.add(store);
        return edt(() -> {
            DungeonListGUI made = new DungeonListGUI(dps, data, states == null ? null : states.store, () -> store, () -> NOW, mode);
            made.onOpenEncounter(id -> calls.add("encounter " + id));
            made.onShowEntry(id -> calls.add("entry " + id));
            made.onOpenRecap((ref, id) -> calls.add("recap " + ref.sessionId + "/" + ref.visitId + " " + id));
            made.onOpenLive(() -> calls.add("live"));
            made.confirmLoad(message -> { confirms.add(message); return confirmAnswer; });
            libraries.add(made);
            return made;
        });
    }

    /** Saves {@code recording}'s summary (with its detail) in {@code session}, marked as keeping full detail, with the file or without. */
    private void save(String session, DpsData recording, boolean keptFull, boolean fileThere, boolean detail) throws IOException {
        CombatSummaries.Result summary = CombatSummaries.build(recording);
        summary.record().fullDetail = keptFull;
        CombatFixtures.writeRecord(root, session, summary.record());
        if (detail) CombatFixtures.writeDetail(root, session, summary.detail());
        if (fileThere) writeDps(CombatAutosave.fullDetailFile(root.resolve(session), recording.getRecordingId()), recording.getSaveFile(false));
    }

    private static Path writeDps(Path file, DpsData data) throws IOException {
        Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(data); }
        return file;
    }

    /** A library that is not shown reads when asked (the tab itself reads on first show). */
    private static void first(DungeonListGUI library) throws Exception {
        edt(() -> { library.refreshEncounters(); return null; });
        read(library, 1);
    }

    private static void read(DungeonListGUI library, int reads) throws Exception {
        await(() -> library.reads() >= reads && !library.loading());
    }

    private EncounterCatalog.Entry entry(DpsData recording) { return entry(recording.getRecordingId()); }
    private EncounterCatalog.Entry entry(String recordingId) {
        for (EncounterCatalog.Entry entry : dps.encounters().entries()) if (recordingId.equals(entry.data.getRecordingId()) && !entry.imported()) return entry;
        return null;
    }

    private static void open(DungeonListGUI library, int row) {
        JTable table = table(library);
        table.setRowSelectionInterval(row, row);
        named(library, "encounter-open", JButton.class).doClick();
    }

    private static JTable table(DungeonListGUI library) { return named(library, "saved-encounters", JTable.class); }
    private static JScrollPane scroll(JTable table) { return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table); }

    /** The button reading {@code text} under {@code root}, or null. */
    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && text.equals(((AbstractButton) child).getText())) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = button((Container) child, text); if (found != null) return found; }
        }
        return null;
    }
    private static String summary(DungeonListGUI library) { return named(library, "encounter-summary", JTextArea.class).getText(); }
    /** A display mode of the test's own (no preference is read or written). */
    private static DisplayModeModel mode(DisplayModeModel.Mode mode) {
        return new DisplayModeModel(key -> mode == DisplayModeModel.Mode.ANALYST ? "analyst" : "simple", (key, value) -> { });
    }

    /** The model's value at view row {@code row}, model column {@code column} (the view orders the columns and Simple hides some). */
    private static String value(JTable table, int row, int column) { return String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(row), column)); }

    /** The view column showing model column {@code column}. */
    private static int view(JTable table, int column) {
        int view = table.convertColumnIndexToView(column);
        if (view < 0) throw new AssertionError("Model column " + column + " is not in view");
        return view;
    }

    private static String rendered(JTable table, int row, int column) {
        Component cell = table.prepareRenderer(table.getCellRenderer(row, view(table, column)), row, view(table, column));
        return cell instanceof JLabel ? ((JLabel) cell).getText() : value(table, row, column);
    }

    private static String tip(JTable table, int row, int column) {
        Component cell = table.prepareRenderer(table.getCellRenderer(row, view(table, column)), row, view(table, column));
        return cell instanceof JComponent ? String.valueOf(((JComponent) cell).getToolTipText()) : "";
    }

    /** The view's column headers, left to right. */
    private static List<String> headers(JTable table) {
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < table.getColumnCount(); column++) headers.add(table.getColumnName(column));
        return headers;
    }

    /** The view row whose Dungeon and Source file columns match (null matches any), or -1. */
    private static int find(JTable table, String dungeon, String source) {
        for (int row = 0; row < table.getRowCount(); row++)
            if ((dungeon == null || dungeon.equals(value(table, row, DUNGEON))) && (source == null || source.equals(value(table, row, SOURCE)))) return row;
        return -1;
    }

    private static int row(JTable table, String dungeon, String source) {
        int row = find(table, dungeon, source);
        if (row < 0) throw new AssertionError("No row " + dungeon + " / " + source);
        return row;
    }

    /** No text, tooltip or table cell of the tab names a folder: file names only. */
    private void assertNoPath(DungeonListGUI library) {
        List<String> texts = new ArrayList<>();
        collect(library, texts);
        JTable table = table(library);
        for (int row = 0; row < table.getRowCount(); row++) for (int column = 0; column < table.getModel().getColumnCount(); column++) {
            texts.add(value(table, row, column));
            if (table.convertColumnIndexToView(column) >= 0) { texts.add(rendered(table, row, column)); texts.add(tip(table, row, column)); }
        }
        for (String text : texts) {
            assertFalse("No path: " + text, text.contains(temp.getRoot().getAbsolutePath()));
            assertFalse("No full-detail folder: " + text, text.contains(CombatRetention.FULL_DETAIL));
            assertFalse("No separator-joined file name: " + text, text.contains(File.separator + "copy.dps"));
        }
    }

    private static void collect(Container root, List<String> texts) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextComponent) texts.add(((JTextComponent) child).getText());
            if (child instanceof JLabel) texts.add(((JLabel) child).getText());
            if (child instanceof AbstractButton) texts.add(((AbstractButton) child).getText());
            if (child instanceof JComponent && ((JComponent) child).getToolTipText() != null) texts.add(((JComponent) child).getToolTipText());
            if (child instanceof Container) collect((Container) child, texts);
        }
    }

    private static <T extends Component> T named(Container root, String name, Class<T> type) { return VisualEvidence.named(root, name, type); }

}
