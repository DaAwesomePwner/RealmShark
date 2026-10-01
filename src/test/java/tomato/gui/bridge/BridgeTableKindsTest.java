package tomato.gui.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import javax.swing.table.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.bridge.*;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;
import static tomato.gui.bridge.BridgeFilterBarTest.edt;
import static tomato.gui.bridge.BridgeFilterBarTest.mode;
import static tomato.gui.bridge.BridgeFilterBarTest.named;
import static tomato.gui.bridge.BridgeFilterBarTest.service;

/**
 * P6b Task 8: the Review, Logs and Saved review tables' column kinds, the Item sprite, the mode-aware Time column over ISO-8601
 * strings (relative in Simple, absolute in Analyst, sorted as instants), exports that ignore the mode, and Saved review's
 * Analyst-only Session and Journal columns. Synthetic service and journals only; nothing is delivered.
 */
public class BridgeTableKindsTest {
    private static final String[] REVIEW = {"Time", "Item", "Rarity", "Shiny", "Character", "Dungeon", "Outcome", "Delivery status"};
    private static final ColumnKind[] REVIEW_KINDS = {ColumnKind.DATE_TIME, ColumnKind.ITEM, ColumnKind.STATUS, ColumnKind.STATUS, ColumnKind.PLAYER, ColumnKind.DUNGEON, ColumnKind.STATUS, ColumnKind.STATUS};
    private static final String[] LOGS = {"Time", "Level", "Message"};
    private static final ColumnKind[] LOG_KINDS = {ColumnKind.DATE_TIME, ColumnKind.STATUS, ColumnKind.TEXT};
    private static final String[] SAVED = {"Time", "Item", "Outcome", "Delivery status", "Character", "Dungeon", "Session", "Journal"};
    private static final ColumnKind[] SAVED_KINDS = {ColumnKind.DATE_TIME, ColumnKind.ITEM, ColumnKind.STATUS, ColumnKind.STATUS, ColumnKind.PLAYER, ColumnKind.DUNGEON, ColumnKind.ID, ColumnKind.ID};
    /** Two synthetic journal times at least 7 days old render as stable dates in Simple; fractions of a second differ in length. */
    private static final String[] TIMES = {"2020-01-15T12:00:00Z", "2020-01-15T12:00:00.5Z", "2020-01-15T12:00:00.25Z", "2020-01-15T11:59:59.999999Z", "2020-01-15T13:00:00.1+02:00"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private TimeZone zone;

    @Before public void berlin() { zone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin")); }
    @After public void restoreZone() { TimeZone.setDefault(zone); }

    @Test public void columnsUseTheirKindsAndTheItemCellShowsTheItemSprite() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.ANALYST)));
            openSaved(panel, journal(TIMES));
            edt(() -> {
                assertKinds(named(panel, "bridge-review-table", JTable.class), REVIEW, REVIEW_KINDS);
                assertKinds(named(panel, "bridge-log-table", JTable.class), LOGS, LOG_KINDS);
                assertKinds(named(panel, "bridge-saved-table", JTable.class), SAVED, SAVED_KINDS);
                JTable review = named(panel, "bridge-review-table", JTable.class);
                for (int row = 0; row < review.getRowCount(); row++) {
                    String name = (String) review.getValueAt(row, 1);
                    assertTrue("The model keeps the item name", name.matches("Test Sword|Unlisted ST|Crystal Wand|Mystic Blade"));
                    JLabel cell = render(review, row, 1);
                    assertEquals(name, cell.getText());
                    int id = name.equals("Test Sword") ? 42 : name.equals("Unlisted ST") ? 43 : name.equals("Crystal Wand") ? 44 : 45;
                    Icon sprite = Sprites.sprite(id, 16);
                    if (name.equals("Test Sword")) {
                        assertNotSame("An enchanted drop's sprite carries its gem", sprite, cell.getIcon());
                        assertTrue(cell.getToolTipText(), cell.getToolTipText().contains("Uncommon · 1 enchant slot"));
                    } else {
                        assertSame("The row's item sprite", sprite, cell.getIcon());
                        assertTrue(cell.getToolTipText(), cell.getToolTipText().contains("Unenchanted"));
                    }
                }
                assertNull("Other columns carry no sprite", render(review, 0, 4).getIcon());
                JTable saved = named(panel, "bridge-saved-table", JTable.class);
                JLabel savedItem = render(saved, 0, 1);
                assertEquals(saved.getValueAt(0, 1), savedItem.getText());
                assertSame(Sprites.sprite(itemId((String) saved.getValueAt(0, 1)), 16), savedItem.getIcon());
                // The Outcome badge takes its tone from Tokens (same colours as before).
                Map<String, Color> tones = new HashMap<>();
                tones.put("Confirmed logged", Tokens.color(Tokens.Role.GOOD)); tones.put("Received—unconfirmed", Tokens.color(Tokens.Role.WARN));
                tones.put("Local/excluded", Tokens.color(Tokens.Role.TEXT_MUTED)); tones.put("Not logged", Tokens.color(Tokens.Role.TEXT_MUTED));
                for (int row = 0; row < review.getRowCount(); row++) {
                    String outcome = (String) review.getValueAt(row, 6);
                    assertEquals(outcome, tones.get(outcome), render(review, row, 6).getForeground());
                }
                assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), render(saved, 0, 2).getForeground());
                return null;
            });
        }
    }

    @Test public void timeIsRelativeInSimpleAndAbsoluteInAnalystWithUtcAndLocalInTheTooltip() throws Exception {
        DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode));
            openSaved(panel, journal("2020-01-15T12:00:00Z", "not a time"));
            edt(() -> {
                for (String name : new String[]{"bridge-review-table", "bridge-log-table"}) {
                    JTable table = named(panel, name, JTable.class);
                    for (int row = 0; row < table.getRowCount(); row++) {
                        String iso = (String) table.getValueAt(row, 0);
                        // Read in the same EDT turn as prepareRenderer: the Simple stamp is undone on a later turn.
                        JLabel cell = render(table, row, 0);
                        assertEquals(name + ": a drop seconds old reads relatively", "just now", cell.getText());
                        assertEquals(name + ": the tooltip gives UTC and local time", tip(iso), cell.getToolTipText());
                        assertTrue("The model keeps the ISO text", iso.endsWith("Z") && iso.contains("T"));
                    }
                }
                JTable saved = named(panel, "bridge-saved-table", JTable.class);
                int old = row(saved, "2020-01-15T12:00:00Z"), unknown = row(saved, "not a time");
                JLabel cell = render(saved, old, 0);
                assertEquals("A time 7+ days old reads as its local date", "2020-01-15", cell.getText());
                assertEquals("2020-01-15 12:00:00 UTC · 2020-01-15 13:00:00 local (Europe/Berlin)", cell.getToolTipText());
                JLabel bad = render(saved, unknown, 0);
                assertEquals("An unreadable time keeps its own text", "not a time", bad.getText());
                assertEquals("…dimmed", Tokens.color(Tokens.Role.TEXT_MUTED), bad.getForeground());
                assertNull("…and no invented tooltip", bad.getToolTipText());
                mode.set(DisplayModeModel.Mode.ANALYST);
                for (String name : new String[]{"bridge-review-table", "bridge-log-table", "bridge-saved-table"}) {
                    JTable table = named(panel, name, JTable.class);
                    for (int row = 0; row < table.getRowCount(); row++) {
                        String iso = (String) table.getValueAt(row, 0);
                        JLabel absolute = render(table, row, 0);
                        Long at = KitTables.epoch(iso);
                        assertEquals(name + ": Analyst shows the absolute local time (an unreadable text as recorded)",
                            at == null ? iso : DisplayFormat.formatTimestamp(at), absolute.getText());
                        assertEquals(name + ": with the same tooltip", KitTables.epoch(iso) == null ? null : tip(iso), absolute.getToolTipText());
                    }
                }
                assertEquals("Analyst does not dim an unreadable time", saved.getForeground(), render(saved, row(saved, "not a time"), 0).getForeground());
                assertEquals("Europe/Berlin: 12:00 UTC is 13:00 local", "2020-01-15 13:00:00", render(saved, row(saved, "2020-01-15T12:00:00Z"), 0).getText());
                return null;
            });
        }
    }

    @Test public void timeSortsAsInstantsIncludingFractionsOfASecondAndOffsets() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.SIMPLE)));
            openSaved(panel, journal(TIMES));
            edt(() -> {
                JTable saved = named(panel, "bridge-saved-table", JTable.class);
                saved.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
                List<String> order = new ArrayList<>();
                for (int row = 0; row < saved.getRowCount(); row++) order.add((String) saved.getValueAt(row, 0));
                assertEquals("Chronological, not lexical", Arrays.asList("2020-01-15T13:00:00.1+02:00", "2020-01-15T11:59:59.999999Z",
                    "2020-01-15T12:00:00Z", "2020-01-15T12:00:00.25Z", "2020-01-15T12:00:00.5Z"), order);
                for (String name : new String[]{"bridge-review-table", "bridge-log-table", "bridge-saved-table"}) {
                    @SuppressWarnings("unchecked") Comparator<Object> time = (Comparator<Object>) ((TableRowSorter<?>) named(panel, name, JTable.class).getRowSorter()).getComparator(0);
                    assertTrue(name + ": a whole second sorts before its fractions", time.compare("2020-01-15T12:00:00Z", "2020-01-15T12:00:00.5Z") < 0);
                    assertTrue(name + ": microseconds count", time.compare("2020-01-15T12:00:00.000002Z", "2020-01-15T12:00:00.000001Z") > 0);
                    assertEquals(name + ": the same instant", 0, time.compare("2020-01-15T12:00:00Z", "2020-01-15T13:00:00+01:00"));
                    assertTrue(name + ": unreadable times sort after readable ones", time.compare("not a time", "2020-01-15T12:00:00Z") > 0);
                }
                return null;
            });
        }
    }

    @Test public void exportsAndModelsAreIdenticalInBothModes() throws Exception {
        DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
        try (BridgeService service = service(temp)) {
            List<String> exported = new ArrayList<>();
            BridgeReviewGUI panel = edt(() -> { BridgeReviewGUI created = new BridgeReviewGUI(service, mode); created.useExporter((name, content) -> exported.add(name + "\n" + content)); return created; });
            openSaved(panel, journal(TIMES));
            String[] simple = edt(() -> exports(panel, exported));
            String[] models = edt(() -> models(panel));
            edt(() -> { mode.set(DisplayModeModel.Mode.ANALYST); return null; });
            assertArrayEquals("Review, logs and saved exports ignore the mode", simple, edt(() -> exports(panel, exported)));
            assertArrayEquals("Model values ignore the mode", models, edt(() -> models(panel)));
            assertTrue("The review export keeps absolute ISO times", simple[0].contains("\"" + service.snapshot().reviews.get(0).time + "\""));
            assertTrue("The saved export keeps Session and Journal while Simple hides them", simple[2].startsWith("Journal,Session,") && simple[2].contains("\"review.jsonl\""));
            assertFalse(simple[0].contains("just now")); assertFalse(simple[1].contains("just now"));
        }
    }

    @Test public void savedSessionAndJournalAreAnalystOnly() throws Exception {
        DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode));
            openSaved(panel, journal(TIMES));
            edt(() -> {
                JTable saved = named(panel, "bridge-saved-table", JTable.class);
                assertEquals(Arrays.asList("Time", "Item", "Outcome", "Delivery status", "Character", "Dungeon"), headers(saved));
                assertEquals(new HashSet<>(Arrays.asList("Session", "Journal")), KitTables.modeHidden(saved));
                int time = saved.getColumnModel().getColumn(0).getPreferredWidth();
                mode.set(DisplayModeModel.Mode.ANALYST);
                assertEquals(Arrays.asList(SAVED), headers(saved));
                assertEquals("session fixture", saved.getValueAt(0, 6)); assertEquals("review.jsonl", saved.getValueAt(0, 7));
                assertEquals("No refit on a mode switch", time, saved.getColumnModel().getColumn(0).getPreferredWidth());
                mode.set(DisplayModeModel.Mode.SIMPLE);
                assertEquals(6, saved.getColumnCount());
                assertEquals("Review has no Analyst-only columns", Arrays.asList(REVIEW), headers(named(panel, "bridge-review-table", JTable.class)));
                assertEquals("Logs has none either", Arrays.asList(LOGS), headers(named(panel, "bridge-log-table", JTable.class)));
                saved.setRowSelectionInterval(0, 0);
                assertTrue("Details keep the qualified identity", named(panel, "bridge-saved-details", JTextArea.class).getText().contains("Identity: review.jsonl · session fixture"));
                return null;
            });
        }
    }

    @Test public void searchMatchesTheShownLocalTimeAndTheRecordedTextButNeverTheRelativeText() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.ANALYST)));
            edt(() -> {
                JTextField search = named(panel, "bridge-search", JTextField.class);
                for (String name : new String[]{"bridge-review-table", "bridge-log-table"}) {
                    JTable table = named(panel, name, JTable.class);
                    int all = table.getRowCount();
                    String iso = (String) table.getValueAt(0, 0), shown = DisplayFormat.formatTimestamp(KitTables.epoch(iso));
                    assertEquals(name + ": the cell shows the local time", shown, render(table, 0, 0).getText());
                    assertFalse("Europe/Berlin: the local text is not part of the recorded UTC text", iso.contains(shown));
                    search.setText(shown);
                    assertTrue(name + ": the shown local time finds the row", rows(table).contains(iso));
                    for (String time : rows(table)) assertEquals(name + ": only rows shown at that time", shown, DisplayFormat.formatTimestamp(KitTables.epoch(time)));
                    search.setText(shown.substring(11, 16));
                    assertTrue(name + ": a local clock time finds the row", rows(table).contains(iso));
                    search.setText(iso);
                    assertTrue(name + ": the recorded ISO text still finds its row", rows(table).contains(iso));
                    for (String time : rows(table)) assertEquals(name + ": …and only rows recorded at that instant", iso, time);
                    for (String relative : new String[]{"just now", "min ago"}) {
                        search.setText(relative);
                        assertEquals(name + ": the relative text '" + relative + "' matches nothing", 0, table.getRowCount());
                    }
                    search.setText("");
                    assertEquals(all, table.getRowCount());
                }
                return null;
            });
        }
    }

    // ---- helpers ----
    private static List<String> rows(JTable table) {
        List<String> times = new ArrayList<>();
        for (int row = 0; row < table.getRowCount(); row++) times.add((String) table.getValueAt(row, 0));
        return times;
    }

    /** The Simple and Analyst tooltip: the instant in UTC, then in the local zone. */
    private static String tip(String iso) {
        Instant at = Instant.ofEpochMilli(KitTables.epoch(iso));
        return DisplayFormat.DATE_TIME.format(at.atZone(ZoneOffset.UTC)) + " UTC · " + DisplayFormat.DATE_TIME.format(at.atZone(ZoneId.systemDefault()))
            + " local (" + ZoneId.systemDefault().getId() + ")";
    }
    private static JLabel render(JTable table, int row, int column) {
        return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
    }
    private static int itemId(String name) { return 100 + Integer.parseInt(name.substring(name.indexOf('#') + 1)); }
    private static int row(JTable table, String time) {
        for (int row = 0; row < table.getRowCount(); row++) if (time.equals(table.getValueAt(row, 0))) return row;
        throw new AssertionError("No row at " + time);
    }
    private static List<String> headers(JTable table) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < table.getColumnCount(); i++) found.add(String.valueOf(table.getColumnModel().getColumn(i).getHeaderValue()));
        return found;
    }
    /** Headers in order, and each column fitted to its kind: the kind's width, or the header text when that is wider. */
    private static void assertKinds(JTable table, String[] headers, ColumnKind[] kinds) {
        assertEquals(Arrays.asList(headers), headers(table));
        for (int i = 0; i < kinds.length; i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            TableCellRenderer header = table.getTableHeader().getDefaultRenderer();
            int title = header.getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, i).getPreferredSize().width;
            assertEquals(table.getName() + " " + headers[i] + " is " + kinds[i], Math.max(column.getMinWidth(), Math.max(title, kinds[i].width(table.getFont()))), column.getPreferredWidth());
        }
    }
    private String[] exports(BridgeReviewGUI panel, List<String> exported) {
        exported.clear();
        tomato.gui.kit.OverflowMenu review = named(panel, "bridge-review-more", tomato.gui.kit.OverflowMenu.class), logs = named(panel, "bridge-logs-more", tomato.gui.kit.OverflowMenu.class);
        review.item("Export review CSV…").doClick(); logs.item("Export logs…").doClick();
        return new String[]{exported.get(0), exported.get(1), panel.savedCsv()};
    }
    private static String[] models(BridgeReviewGUI panel) {
        List<String> values = new ArrayList<>();
        for (String name : new String[]{"bridge-review-table", "bridge-log-table", "bridge-saved-table"}) {
            TableModel model = named(panel, name, JTable.class).getModel();
            for (int row = 0; row < model.getRowCount(); row++) for (int column = 0; column < model.getColumnCount(); column++) values.add(String.valueOf(model.getValueAt(row, column)));
        }
        return values.toArray(new String[0]);
    }
    /** A synthetic version-1 journal ("review.jsonl", service "fixture"): one Local only record per time, items "Saved Sword #i". */
    private Path journal(String... times) throws Exception {
        Path file = temp.getRoot().toPath().resolve("saved").resolve("review.jsonl"); Files.createDirectories(file.getParent());
        Gson gson = new Gson(); StringBuilder lines = new StringBuilder();
        for (int i = 0; i < times.length; i++) {
            JsonObject review = new JsonObject();
            review.addProperty("id", i + 1); review.addProperty("time", times[i]);
            review.add("drop", gson.toJsonTree(new BridgePayload.Drop(new BridgePayload.Item(100 + i, "Saved Sword #" + i, "EQUIPMENT", "UT", ""), 7, "Fixture", "Wizard", "Synthetic Dungeon", false, false, 1, 0)));
            review.addProperty("status", "Local only"); review.addProperty("detail", "Sending was off."); review.addProperty("payload", "");
            JsonObject line = new JsonObject();
            line.addProperty("journal", BridgeJournal.FORMAT); line.addProperty("version", 1); line.addProperty("service", "fixture"); line.add("review", review);
            lines.append(gson.toJson(line)).append('\n');
        }
        Files.write(file, lines.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }
    @Test public void savedEntriesFromBeforeSlotRaritySayLegacyCountAndShowNoGem() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.ANALYST)));
            openSaved(panel, legacyJournal());
            edt(() -> {
                JTable saved = named(panel, "bridge-saved-table", JTable.class);
                assertEquals(2, saved.getRowCount());
                for (int row = 0; row < saved.getRowCount(); row++) {
                    JLabel item = render(saved, row, 1);
                    if ("Legacy Bow".equals(saved.getValueAt(row, 1))) assertSame("A legacy entry kept no enchant data, so no gem", Sprites.sprite(7001, 16), item.getIcon());
                    else assertNotSame("A new entry shows its gem", Sprites.sprite(7002, 16), item.getIcon());
                }
                String csv = panel.savedCsv();
                assertTrue(csv, csv.contains("\"rare (legacy count)\""));
                assertTrue(csv, csv.contains("\"uncommon\""));
                return null;
            });
        }
    }
    /** A journal with one entry saved before slot rarity (rarity from upstream's line count, no enchant data) and one after. */
    private Path legacyJournal() throws Exception {
        Path file = temp.getRoot().toPath().resolve("saved").resolve("legacy-review.jsonl"); Files.createDirectories(file.getParent());
        Gson gson = new Gson(); StringBuilder lines = new StringBuilder();
        BridgePayload.Item[] items = {new BridgePayload.Item(7001, "Legacy Bow", "EQUIPMENT", "UT", "AAIEAQD__w=="), new BridgePayload.Item(7002, "New Bow", "EQUIPMENT", "UT", "AAIE_wU=")};
        for (int i = 0; i < items.length; i++) {
            JsonObject drop = gson.toJsonTree(new BridgePayload.Drop(items[i], 7, "Fixture", "Wizard", "Synthetic Dungeon", false, false, 1, 0)).getAsJsonObject();
            if (i == 0) { JsonObject item = drop.getAsJsonObject("item"); item.remove("enchantData"); item.addProperty("raritySource", "enchant_count"); }
            JsonObject review = new JsonObject();
            review.addProperty("id", i + 1); review.addProperty("time", TIMES[i]); review.add("drop", drop);
            review.addProperty("status", "Local only"); review.addProperty("detail", "Sending was off."); review.addProperty("payload", "");
            JsonObject line = new JsonObject();
            line.addProperty("journal", BridgeJournal.FORMAT); line.addProperty("version", 1); line.addProperty("service", "fixture"); line.add("review", review);
            lines.append(gson.toJson(line)).append('\n');
        }
        Files.write(file, lines.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }
    private static void openSaved(BridgeReviewGUI panel, Path journal) throws Exception {
        edt(() -> { panel.openJournals(Collections.singletonList(journal)); return null; });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) { if (edt(() -> panel.savedResult() != null)) return; Thread.sleep(20); }
        fail("Journal read did not finish");
    }
}
