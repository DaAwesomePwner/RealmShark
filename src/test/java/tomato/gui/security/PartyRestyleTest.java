package tomato.gui.security;

import assets.ImageBuffer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.TableModel;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.Entity;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.activity.RunDurationUnit;
import tomato.gui.history.HistoryTables;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.CollectionControl;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.roster.RosterStateTestSupport.Memory;
import tomato.ability.AbilityObservation;
import tomato.ability.AbilityObservationStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;
import static tomato.gui.activity.ActivityLiveStateTest.*;

/** P6b Task 7: Party's roster cells, gear wells and requirement tones, its view-state items in ⋯, Runs times and Ability Use. */
public class PartyRestyleTest {
    private static final String[] KEYS = {"ui.filters.inspect-roster.open", "ui.filters.inspect-runs.open", "ui.filters.ability.open",
        DisplayModeModel.KEY, "ux.archive.inspect-live-roster", "securityFilterName", "securityFilters"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> saved = new HashMap<>();
    private DisplayModeModel.Mode mode;
    private RosterDefinitions definitions;

    @Before public void isolate() throws Exception {
        for (String key : KEYS) saved.put(key, PropertiesManager.getProperty(key));
        for (String key : KEYS) if (key.startsWith("ui.filters.")) PropertiesManager.setProperties(key, "false");
        mode = edt(() -> DisplayModeModel.application().mode());
        definitions = RequirementResultTest.definitions();
    }
    @After public void restore() throws Exception {
        edt(() -> {
            DisplayModeModel.application().set(mode); ParsePanelGUI.clear();
            new ParsePanelGUI(false).getFilters().remove(RequirementResultTest.rules().name); ParsePanelGUI.currentFilter = null;
            return null;
        });
        for (String key : KEYS) PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key));
    }

    @Test public void theClassCellCarriesTheClassSpriteAndThePlayerCellIsText() throws Exception {
        edt(() -> {
            ParsePanelGUI panel = new ParsePanelGUI(true, () -> definitions);
            Entity plain = RequirementResultTest.player(1).playerEntity, skinned = RequirementResultTest.player(2).playerEntity;
            RequirementResultTest.put(skinned, StatType.SKIN_ID.get(), 900);
            ParsePanelGUI.addPlayer(1, plain); ParsePanelGUI.addPlayer(2, skinned); panel.refreshRoster();
            JTable table = panel.rosterTable(); assertEquals(2, table.getRowCount());
            for (int row = 0; row < 2; row++) {
                boolean skin = table.getValueAt(row, 0).toString().startsWith("Player2");
                JLabel player = render(table, row, 0);
                assertNull("The skin moved off the Player cell", player.getIcon());
                assertEquals(table.getValueAt(row, 0), player.getText());
                JLabel clazz = render(table, row, 2);
                assertEquals("The model value stays the class name", table.getValueAt(row, 2), clazz.getText());
                assertEquals("Class: " + table.getValueAt(row, 2), clazz.getAccessibleContext().getAccessibleName());
                assertSame(skin ? "The captured skin" : "Without a captured skin, the class sprite",
                    skin ? ImageBuffer.getOutlinedIcon(900, 20) : Sprites.sprite(782, 16), clazz.getIcon());
            }
            assertTrue("A text cell after the Class cell has no icon", render(table, 0, 1).getIcon() == null);
            return null;
        });
    }

    @Test public void gearWellsShowItemEmptyAndNotCapturedWithTheEnchantGlowInsideTheWell() throws Exception {
        edt(() -> {
            ParsePanelGUI panel = new ParsePanelGUI(true, () -> definitions);
            Entity e = RequirementResultTest.player(1).playerEntity;
            RequirementResultTest.put(e, StatType.INVENTORY_0_STAT.get(), 42);    // T5 in the fixture definitions, one enchant
            e.stat.set(StatType.INVENTORY_2_STAT, null);                            // armor not captured
            RequirementResultTest.put(e, StatType.INVENTORY_3_STAT.get(), 44);    // UT, no enchant
            StatData enchants = new StatData(); enchants.statType = StatType.UNIQUE_DATA_STRING; enchants.statTypeNum = StatType.UNIQUE_DATA_STRING.get();
            enchants.stringStatValue = Base64.getUrlEncoder().encodeToString(new byte[]{0, 2, 4, 1, 0}) + ",,,"; e.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
            ParsePanelGUI.addPlayer(1, e); panel.refreshRoster();
            JTable table = panel.rosterTable();
            Icon weapon = cellIcon(table, 3), ability = cellIcon(table, 4), armor = cellIcon(table, 5), ring = cellIcon(table, 6);
            for (Icon icon : new Icon[]{weapon, ability, armor, ring}) {
                assertEquals("A 20 px sprite in its well", 20 + Sprites.WELL, icon.getIconWidth()); assertEquals(20 + Sprites.WELL, icon.getIconHeight());
            }
            assertArrayEquals("Empty is the kit's empty well", paint(ItemSlot.icon(null, "", ItemSlot.State.EMPTY, 20), table), paint(ability, table));
            assertArrayEquals("Not captured is the kit's not-captured well", paint(ItemSlot.icon(null, "", ItemSlot.State.UNKNOWN, 20), table), paint(armor, table));
            assertFalse("Empty and not captured differ", Arrays.equals(paint(ability, table), paint(armor, table)));
            assertArrayEquals("An item well has its sprite and tier edge", paint(ItemSlot.icon(Sprites.sprite(44, 20), "UT", ItemSlot.State.ITEM, 20), table), paint(ring, table));
            assertGlowInside(weapon, ItemSlot.icon(Sprites.sprite(42, 20), "T5", ItemSlot.State.ITEM, 20), ContentStyle.color("mint"), table);
            // The cells keep their empty text and accessible names.
            assertEquals("", render(table, 0, 3).getText());
            assertEquals("Weapon: Unrecognized item (ID 42)", render(table, 0, 3).getAccessibleContext().getAccessibleName());
            assertEquals("Ability: Empty (ID -1)", render(table, 0, 4).getAccessibleContext().getAccessibleName());
            assertEquals("Armor: Not captured", render(table, 0, 5).getAccessibleContext().getAccessibleName());
            return null;
        });
    }

    @Test public void requirementsReadAsToneBadgesWithTheirTooltipAndName() throws Exception {
        edt(() -> {
            ParsePanelGUI panel = new ParsePanelGUI(true, () -> definitions);
            Entity pass = RequirementResultTest.player(1).playerEntity, below = RequirementResultTest.player(2).playerEntity, unknown = RequirementResultTest.player(3).playerEntity;
            RequirementResultTest.put(pass, StatType.INVENTORY_0_STAT.get(), 42); unknown.stat.set(StatType.INVENTORY_0_STAT, null);
            ParsePanelGUI.addPlayer(1, pass); ParsePanelGUI.addPlayer(2, below); ParsePanelGUI.addPlayer(3, unknown); panel.refreshRoster();
            JTable table = panel.rosterTable();
            assertEquals("Without rules every row is not evaluated, neutral", Tokens.tone(Tokens.Tone.NEUTRAL), render(table, 0, table.convertColumnIndexToView(11)).getForeground());
            SecurityFilter rules = RequirementResultTest.rules(); panel.getFilters().put(rules.name, rules); ParsePanelGUI.currentFilter = rules; panel.filterUpdate(); panel.refreshRoster();
            Map<String, Tokens.Tone> tones = new HashMap<>();
            tones.put("Pass", Tokens.Tone.GOOD); tones.put("Below requirements", Tokens.Tone.BAD); tones.put("Unknown", Tokens.Tone.WARN);
            Set<String> seen = new HashSet<>();
            for (int row = 0; row < table.getRowCount(); row++) {
                String verdict = table.getModel().getValueAt(table.convertRowIndexToModel(row), 11).toString(); seen.add(verdict);
                JLabel badge = render(table, row, table.convertColumnIndexToView(11));
                assertEquals(verdict, badge.getText());
                assertEquals(verdict, Tokens.tone(tones.get(verdict)), badge.getForeground());
                assertEquals("Requirements: " + verdict, badge.getAccessibleContext().getAccessibleName());
                assertTrue(verdict + " keeps the reasons tooltip", badge.getToolTipText() != null && badge.getToolTipText().startsWith("<html>"));
            }
            assertEquals(tones.keySet(), seen);
            table.setRowSelectionInterval(0, 0);
            assertEquals("Selected rows keep the selection ink", table.getSelectionForeground(), render(table, 0, table.convertColumnIndexToView(11)).getForeground());
            return null;
        });
    }

    @Test public void viewStateActionsAreOverflowItemsDisabledWhileARecordedRunOwnsTheRosterAndFailuresShow() throws Exception {
        Memory memory = new Memory();
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SecurityGUI panel = edt(() -> { SecurityGUI p = new SecurityGUI(log); p.bindViewState(memory.store); return p; });
            edt(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                ParsePanelGUI roster = named(panel, ParsePanelGUI.class, null);
                FilterBar rosterBar = named(panel, FilterBar.class, "inspect-roster-filter-bar"), runsBar = named(panel, FilterBar.class, "inspect-runs-filter-bar"),
                    abilityBar = named(panel, FilterBar.class, "ability-filter-bar");
                JMenuItem rosterSave = item(rosterBar, "inspect-live-roster-save-state"), rosterReset = item(rosterBar, "inspect-live-roster-reset-state");
                JMenuItem runsSave = item(runsBar, "inspect-live-runs-save-state"), runsReset = item(runsBar, "inspect-live-runs-reset-state");
                JMenuItem pageSave = item(abilityBar, "inspect-live-container-save-state"), pageReset = item(abilityBar, "inspect-live-container-reset-state");
                for (JMenuItem save : new JMenuItem[]{rosterSave, runsSave, pageSave}) assertEquals("Save view state", save.getText());
                for (JMenuItem reset : new JMenuItem[]{rosterReset, runsReset, pageReset}) assertEquals("Reset saved view state", reset.getText());
                assertTrue("The runs ⋯ also holds the duration unit", item(runsBar, "inspect-run-duration-unit") instanceof JMenu);
                JMenuItem window = item(abilityBar, "ability-reset-window");
                assertEquals("Reset evidence window…", window.getText());
                assertEquals("Danger ink", Tokens.color(Tokens.Role.BAD), window.getForeground());
                // No state row on the page: the banners show only for a failure.
                for (String banner : new String[]{"inspect-live-roster-view-state", "inspect-live-runs-view-state", "inspect-live-container-view-state"})
                    assertFalse(banner + " hidden without a failure", named(panel, JComponent.class, banner).isVisible());
                assertNull("No Save view state buttons remain on the page", button(panel, "Save view state"));
                assertNull("No Reset saved view state buttons remain on the page", button(panel, "Reset saved view state"));
                // Current Area's Save writes the roster (its application store) and the page's tab; Runs' writes the runs and the tab.
                String rosterKey = "ux.archive.inspect-live-roster"; PropertiesManager.setProperties(rosterKey, "");
                int writes = memory.writes; rosterSave.doClick(0);
                assertFalse("The roster is saved", PropertiesManager.getProperty(rosterKey).isEmpty());
                assertTrue(memory.values.containsKey("ux.archive.inspect-live-container"));
                assertEquals("…with the page's tab", writes + 1, memory.writes);
                runsSave.doClick(0); assertTrue(memory.values.containsKey("ux.archive.inspect-live-runs")); assertEquals(writes + 3, memory.writes);
                pageSave.doClick(0); assertEquals(writes + 4, memory.writes);
                // A recorded run owns the roster: its items are disabled and inert, the page's tab included.
                roster.showRun("recorded-run", Collections.singleton(new tomato.backend.data.InspectSnapshot(RequirementResultTest.player(7).playerEntity, 1234)));
                assertFalse(rosterSave.isEnabled()); assertFalse(rosterReset.isEnabled());
                assertTrue("The page's own items stay available", runsSave.isEnabled() && pageSave.isEnabled());
                String liveRoster = PropertiesManager.getProperty(rosterKey); int recorded = memory.writes;
                rosterSave.doClick(0); rosterReset.doClick(0);
                for (JMenuItem item : new JMenuItem[]{rosterSave, rosterReset})
                    for (java.awt.event.ActionListener l : item.getActionListeners()) l.actionPerformed(new java.awt.event.ActionEvent(item, 0, "queued"));
                assertEquals("Nothing is written while recorded", recorded, memory.writes);
                assertEquals(liveRoster, PropertiesManager.getProperty(rosterKey));
                roster.showCurrentArea(); assertTrue(rosterSave.isEnabled()); assertTrue(rosterReset.isEnabled());
                memory.fail = true; pageSave.doClick(0);
                return null;
            });
            edt(() -> null);
            edt(() -> {
                tomato.gui.kit.Banner banner = named(panel, tomato.gui.kit.Banner.class, "inspect-live-container-view-state");
                assertTrue("A failed save shows its status", banner.isVisible()); assertTrue(banner.warns());
                assertTrue(banner.text(), banner.text().contains("failed"));
                assertFalse("The other states did not fail", named(panel, JComponent.class, "inspect-live-runs-view-state").isVisible());
                return null;
            });
        }
    }

    @Test public void runsTimesAreRelativeInSimpleAndAbsoluteInAnalystWithCopyAndSearchUnchanged() throws Exception {
        long now = System.currentTimeMillis(), recent = now - 12 * 60_000L, old = now - 20L * 24 * 3600_000L;
        ActivityJournal.State history = twoVisits(); history.visits.get(0).started = old; history.visits.get(1).started = recent;
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history)) {
            InspectRunsPanel panel = edt(() -> { InspectRunsPanel p = new InspectRunsPanel(log, new ParsePanelGUI(false)); p.showRoster(); return p; });
            JTable table = edt(() -> named(panel, JTable.class, "inspect-runs-table"));
            await(() -> table.getRowCount() == 2);
            String zone = java.time.ZoneId.systemDefault().getId();
            List<Object> values = new ArrayList<>(); List<String> copies = new ArrayList<>(), searches = new ArrayList<>();
            for (DisplayModeModel.Mode m : DisplayModeModel.Mode.values()) edt(() -> {
                DisplayModeModel.application().set(m);
                int row = rowStarted(table, recent);
                JLabel cell = render(table, row, 0);   // the tooltip is read in the renderer's own turn
                String absolute = DisplayFormat.formatTimestamp(Instant.ofEpochMilli(recent));
                if (m == DisplayModeModel.Mode.SIMPLE) {
                    assertEquals(KitFormat.relative(recent), cell.getText());
                    assertEquals(absolute + " (" + zone + ")", cell.getToolTipText());
                    assertEquals("A 20-day-old run reads as a date", KitFormat.relative(old), render(table, rowStarted(table, old), 0).getText());
                } else assertEquals(absolute, cell.getText());
                values.add(table.getValueAt(row, 0));
                table.setRowSelectionInterval(row, row); copies.add(HistoryTables.selectedText(table));
                TableModel model = table.getModel();
                searches.add(((javax.swing.table.TableRowSorter<?>) table.getRowSorter()).getStringConverter().toString(model, table.convertRowIndexToModel(row), 0));
                assertEquals("The column keeps its width", tomato.gui.kit.ColumnKind.DATE_TIME.width(table.getFont()), table.getColumnModel().getColumn(0).getPreferredWidth());
                return null;
            });
            assertEquals("The model value is the same in both modes", values.get(0), values.get(1));
            assertEquals("Copy is the same in both modes", copies.get(0), copies.get(1));
            assertEquals("Search reads the absolute text in both modes", searches.get(0), searches.get(1));
            edt(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                JTextField search = named(panel, JTextField.class, "inspect-runs-search");
                search.setText("min ago"); assertEquals("Relative words match nothing", 0, table.getRowCount());
                search.setText(DisplayFormat.formatTimestamp(Instant.ofEpochMilli(recent))); assertEquals("The absolute text matches", 1, table.getRowCount());
                return null;
            });
        }
    }

    @Test public void runsHasOneFilterRowWithTheCollectionStatusLineUnderItAndTheUnitInItsOverflow() throws Exception {
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), twoVisits())) {
            edt(() -> {
                InspectRunsPanel panel = new InspectRunsPanel(log, new ParsePanelGUI(false));
                FilterBar bar = named(panel, FilterBar.class, "inspect-runs-filter-bar");
                JTextField search = named(panel, JTextField.class, "inspect-runs-search");
                assertTrue("Search is in the bar's search slot", SwingUtilities.isDescendingFrom(search, bar.searchSlot()));
                assertNull("No drawer: nothing to filter beyond the search", bar.drawerContent());
                CollectionControl collection = named(panel, CollectionControl.class, null);
                assertNotNull(collection); assertFalse("Collection is not in the filter row", SwingUtilities.isDescendingFrom(collection, bar));
                assertNull("The unit is not a combo in the row", named(panel, JComboBox.class, "inspect-run-duration-unit"));
                JFrame frame = new JFrame(); frame.setContentPane(panel); frame.pack(); frame.setSize(1000, 600); frame.validate();
                try {
                    int barBottom = SwingUtilities.convertPoint(bar, 0, bar.getHeight(), panel).y, line = SwingUtilities.convertPoint(collection, 0, 0, panel).y;
                    int tableTop = SwingUtilities.convertPoint(named(panel, JTable.class, "inspect-runs-table"), 0, 0, panel).y;
                    assertTrue("The status line sits under the filter row (" + line + " ≥ " + barBottom + ")", line >= barBottom);
                    assertTrue("…and above the table", line < tableTop);
                } finally { frame.dispose(); }
                JMenu unit = (JMenu) item(bar, "inspect-run-duration-unit");
                assertEquals("Duration unit", unit.getText());
                JTable runs = named(panel, JTable.class, "inspect-runs-table");
                assertEquals("Observed minutes", runs.getColumnName(6));
                JRadioButtonMenuItem minutes = (JRadioButtonMenuItem) item(bar, "inspect-run-duration-unit-minutes"), seconds = (JRadioButtonMenuItem) item(bar, "inspect-run-duration-unit-seconds");
                assertTrue(minutes.isSelected()); assertFalse(seconds.isSelected());
                seconds.doClick(0);
                assertEquals("Observed seconds", runs.getColumnName(6)); assertEquals(RunDurationUnit.SECONDS, panel.durationUnit());
                assertTrue(seconds.isSelected()); assertFalse(minutes.isSelected());
                panel.readOnly(); assertFalse("A saved roster has no collection line", collection.isVisible());
                return null;
            });
        }
    }

    @Test public void abilityUseHasOneRowWithADrawerChipsAFooterAndAConfirmedResetInItsOverflow() throws Exception {
        AbilityObservationStore store = new AbilityObservationStore(500);
        long at = System.currentTimeMillis() - 30 * 60_000L;
        for (int i = 0; i < 3; i++) store.add(new AbilityObservation("synthetic", null, at + i, 1, "Fixture " + i, "Mystic", 1, "Orb", i == 0 ? "decoy" : "stasis", 5, 6, "Synthetic candidate"));
        edt(() -> {
            AbilityEvidencePanel panel = new AbilityEvidencePanel(store);
            FilterBar bar = named(panel, FilterBar.class, "ability-filter-bar");
            JTextField search = named(panel, JTextField.class, "ability-search");
            assertTrue(SwingUtilities.isDescendingFrom(search, bar.searchSlot()));
            JComboBox<?> kind = named(panel, JComboBox.class, "ability-kind"), range = named(panel, JComboBox.class, "ability-range"), order = named(panel, JComboBox.class, "ability-order");
            for (JComboBox<?> facet : Arrays.asList(kind, range, order)) assertTrue(facet.getName() + " is in the drawer", SwingUtilities.isDescendingFrom(facet, bar.drawerContent()));
            assertEquals(0, bar.activeCount());
            kind.setSelectedItem("stasis");
            assertEquals(Collections.singletonList("Heuristic: stasis"), tomato.gui.history.ArchiveNativeSupport.chipLabels(bar));
            JTable table = named(panel, JTable.class, "ability-table"); assertEquals(2, table.getRowCount());
            tomato.gui.history.ArchiveNativeSupport.removeChip(bar, "Heuristic: stasis");
            assertEquals(0, kind.getSelectedIndex()); assertEquals(3, table.getRowCount());
            // Observed is an absolute date-time, not Date.toString().
            JLabel observed = render(table, 0, 0);
            assertEquals(DisplayFormat.formatTimestamp(Instant.ofEpochMilli(at + 2)), observed.getText());
            // Previous and Next are a footer below the evidence, not in the filter row.
            AbstractButton previous = button(panel, "Previous"), next = button(panel, "Next");
            assertNotNull(previous); assertNotNull(next);
            assertFalse(SwingUtilities.isDescendingFrom(previous, bar)); assertFalse(SwingUtilities.isDescendingFrom(next, bar));
            assertNull("Reset evidence window left the row", button(panel, "Reset evidence window"));
            assertNull("Clear filters is the bar's own Clear", button(panel, "Clear filters"));
            JMenuItem reset = item(bar, "ability-reset-window");
            List<String> asked = new ArrayList<>();
            panel.confirm = message -> { asked.add(message); return false; };
            reset.doClick(0); assertEquals(1, asked.size()); assertEquals("Declined: nothing is reset", 3, store.snapshot("", null, null, null).rows.size());
            panel.confirm = message -> true;
            reset.doClick(0); assertEquals("Confirmed: the window is reset", 0, store.snapshot("", null, null, null).rows.size());
            assertEquals(0, table.getRowCount());
            return null;
        });
    }

    // ---- helpers ----

    static JLabel render(JTable table, int row, int column) { return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column); }
    /** A button by text anywhere in the tree; null when absent. */
    static AbstractButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton) c).getText())) return (AbstractButton) c;
            if (c instanceof Container) { AbstractButton found = button((Container) c, text); if (found != null) return found; }
        }
        return null;
    }
    private static Icon cellIcon(JTable table, int column) { return render(table, 0, column).getIcon(); }
    private static int rowStarted(JTable table, long started) {
        for (int row = 0; row < table.getRowCount(); row++) if (Instant.ofEpochMilli(started).equals(table.getValueAt(row, 0))) return row;
        throw new AssertionError("No run started at " + started);
    }
    /** The Runs bar's "Duration unit ▸" radio item for {@code unit}, found from any container holding the Runs tab. */
    static JRadioButtonMenuItem unitItem(Container page, RunDurationUnit unit) {
        FilterBar bar = named(page, FilterBar.class, "inspect-runs-filter-bar"); assertNotNull("The Runs filter row", bar);
        return (JRadioButtonMenuItem) item(bar, "inspect-run-duration-unit-" + unit.name().toLowerCase(Locale.ROOT));
    }
    /** A ⋯ item (or submenu) by name, including inside submenus. */
    static JMenuItem item(FilterBar bar, String name) {
        JMenuItem found = item(bar.overflow().menu().getComponents(), name);
        assertNotNull("⋯ item " + name + " in " + bar.getName(), found); return found;
    }
    private static JMenuItem item(Component[] components, String name) {
        for (Component c : components) {
            if (c instanceof JMenuItem && name.equals(c.getName())) return (JMenuItem) c;
            if (c instanceof JMenu) { JMenuItem nested = item(((JMenu) c).getMenuComponents(), name); if (nested != null) return nested; }
        }
        return null;
    }
    static int[] paint(Icon icon, Component owner) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); icon.paintIcon(owner, g, 0, 0); g.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
    /**
     * The glowing well paints like the plain well except inside it: its outer frame (the tier edge) is untouched, and every pixel it
     * changes moves toward the glow color.
     */
    static void assertGlowInside(Icon glowing, Icon plain, Color glow, Component owner) {
        int w = glowing.getIconWidth(), h = glowing.getIconHeight();
        assertEquals(plain.getIconWidth(), w);
        int[] a = paint(glowing, owner), b = paint(plain, owner); int changed = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x; if (a[i] == b[i]) continue;
            assertFalse("The glow leaves the well's edge alone at " + x + "," + y, x == 0 || y == 0 || x == w - 1 || y == h - 1);
            assertTrue("A changed pixel moves toward the glow at " + x + "," + y, distance(a[i], glow) < distance(b[i], glow));
            changed++;
        }
        assertTrue("The well paints a glow", changed > 8);
    }
    private static double distance(int argb, Color c) {
        double alpha = (argb >>> 24) / 255.0;
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, bl = argb & 255;
        return Math.abs(r * alpha - c.getRed()) + Math.abs(g * alpha - c.getGreen()) + Math.abs(bl * alpha - c.getBlue());
    }
}
