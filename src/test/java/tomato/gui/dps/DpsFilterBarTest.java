package tomato.gui.dps;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.runs.RunsDpsPage;
import tomato.gui.runs.RunsPage;
import tomato.gui.runs.RunsTab;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P5b Task 9: the Live meter's one filter row ({@code FilterBar("dps-meter")}): player search and Rank by, a drawer with the
 * classes, enemy order, preset, class colors and the Analyst-only view mode, chips, the encounter scope and ⋯; Legacy is an
 * Analyst option; saved full detail is not labeled imported. Synthetic encounters only; no capture, no bridge.
 */
public class DpsFilterBarTest {
    private static final String[] PREFERENCES = {"ui.filters.dps-meter.open", "ui.filters.run-feed.open", "ui.tabs.runs", "ui.tabs.dps",
        "ui.runs.view", "ui.nav.order", "ui.nav.hidden", "ui.nav.pinned", DisplayModeModel.KEY, "filterName", "filters"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("p5b-dps-meter");
    private final Map<String, String> saved = new HashMap<>();
    private final List<AutoCloseable> closing = new ArrayList<>();
    private DisplayModeModel.Mode previousMode;
    private int previousFilter;

    @Before public void isolate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String key : PREFERENCES) { saved.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
            previousMode = DisplayModeModel.application().mode(); previousFilter = Filter.filter;
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE); Filter.selectFilter(null); Filter.disable();
        });
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (AutoCloseable resource : closing) { try { resource.close(); } catch (Exception ignored) { } }
            DisplayModeModel.application().set(previousMode);
            Filter.selectFilter(null); Filter.filter = previousFilter;
            saved.forEach((key, value) -> PropertiesManager.setProperties(key, value == null ? "" : value));
        });
    }

    /**
     * S6 inside Runs & DPS › Live meter (nested under {@code runs-tabs} in the shell): one filter row at 1240×800 font 13 with
     * the drawer closed, the meter's summary and status row below it, no control past the row's right edge at any size, and a
     * usable meter (at least three table rows) at 680×520 font 18.
     */
    @Test public void oneFilterRowInTheLiveMeterTabAndAUsableMeterWhenCompact() throws Exception {
        TomatoData data = new TomatoData(); DpsData fight = encounter(data, "Synthetic Halls"); data.dpsData.add(fight);
        DpsGUI dps = edt(() -> new DpsGUI(data, null, new JPanel()));
        RunsDpsPage page = livePage(dps);
        WorkspaceShell shell = shell(page, dps, fight);
        try {
            for (int font : new int[]{13, 18}) for (int[] size : new int[][]{{1240, 800}, {680, 520}}) for (boolean open : new boolean[]{false, true}) {
                String name = "dps-meter-" + size[0] + "-" + font + (open ? "-filters-open" : "-filters-closed");
                edt(() -> {
                    ArchiveNativeSupport.drawer(bar(dps), open);
                    evidence.show(shell, name, size[0], size[1], font); return null;
                });
                evidence.settle();
                edt(() -> {
                    FilterBar bar = bar(dps);
                    assertEquals(RunsTab.LIVE_METER, page.selectedTab());
                    assertTrue(name + ": the row shows in the Live meter tab", bar.isShowing());
                    assertEquals(open, bar.drawerOpen());
                    assertEquals(name + " drawer", open, bar.drawerContent().isShowing());
                    assertInsideRow(name, bar);
                    if (!open && size[0] == 1240 && font == 13) {
                        assertOneFilterRow(name, bar);
                        int below = SwingUtilities.convertPoint(bar, 0, bar.getHeight(), dps).y;
                        JComponent status = VisualEvidence.named(dps, "dps-status-row", JPanel.class), summary = field(dps.meter(), "summary", JLabel.class);
                        assertTrue(name + ": the status row stays below the filter row", SwingUtilities.convertPoint(status, 0, 0, dps).y >= below);
                        assertTrue(name + ": the meter summary stays below the filter row", summary.isShowing() && SwingUtilities.convertPoint(summary, 0, 0, dps).y >= below);
                    }
                    if (!open && size[0] == 680 && font == 18) assertMeterUsable(dps, name);
                    VisualEvidence.named(dps, "dps-damage-scroll", JScrollPane.class).getViewport().setViewPosition(new Point(0, 0));
                    evidence.capture(name);
                    return null;
                });
            }
            // Analyst adds the view mode to the drawer; the closed row is unchanged.
            for (boolean open : new boolean[]{false, true}) {
                String name = "dps-meter-1240-13-analyst" + (open ? "-filters-open" : "-filters-closed");
                edt(() -> {
                    DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                    ArchiveNativeSupport.drawer(bar(dps), open);
                    evidence.show(shell, name, 1240, 800, 13); return null;
                });
                evidence.settle();
                edt(() -> {
                    FilterBar bar = bar(dps);
                    JComponent view = VisualEvidence.named(dps, "dps-view-mode-field", JComponent.class);
                    assertEquals(name + ": the view mode shows in the open drawer", open, view.isShowing());
                    assertInsideRow(name, bar);
                    if (!open) assertOneFilterRow(name, bar);
                    evidence.capture(name);
                    return null;
                });
            }
        } finally { edt(() -> { ArchiveNativeSupport.drawer(bar(dps), false); evidence.closeWindow(); return null; }); }
    }

    /**
     * Enemy cards are exactly as wide as the visible enemy list (split at its default width, in the Live meter tab): a long
     * boss name and subtitle ellipsize (the tooltip keeps them), the list never scrolls sideways, and the Boss chip lies
     * wholly inside the visible list at 1240×800 font 13 and 680×520 font 18.
     */
    @Test public void enemyCardsFitTheListSoTheBossChipIsNeverClipped() throws Exception {
        String longName = "Synthetic Archdemon of the Endless Overflowing Enemy Card Title";
        TomatoData data = new TomatoData();
        Entity boss = new Entity(data, 6_000_060, 0) {
            @Override public boolean isBossMob() { return true; }
            @Override public String name() { return longName; }
        };
        DpsData fight = encounter(data, "Synthetic Halls", boss);
        data.dpsData.add(fight);
        DpsGUI dps = edt(() -> new DpsGUI(data, null, new JPanel()));
        WorkspaceShell shell = shell(livePage(dps), dps, fight);
        try {
            edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); return null; }); // the subtitle adds the object ID
            for (int[] size : new int[][]{{1240, 800, 13}, {680, 520, 18}}) {
                String name = "dps-meter-enemy-cards-" + size[0] + "-" + size[2];
                edt(() -> { evidence.show(shell, name, size[0], size[1], size[2]); return null; });
                evidence.settle();
                edt(() -> {
                    @SuppressWarnings("unchecked") JList<Entity> list = field(dps.meter(), "enemyList", JList.class);
                    JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
                    ui.WaveThreeEvidence.reveal(scroll, scroll.getHeight());
                    assertFalse(name + ": the enemy list does not scroll sideways (list " + list.getWidth() + ", viewport " + scroll.getViewport().getWidth() + ")",
                        scroll.getHorizontalScrollBar().isShowing());
                    int index = -1;
                    for (int i = 0; i < list.getModel().getSize(); i++) if (list.getModel().getElementAt(i) == boss) index = i;
                    assertTrue("The boss card is listed", index > 0);
                    Rectangle cell = list.getCellBounds(index, index);
                    JPanel card = (JPanel) list.getCellRenderer().getListCellRendererComponent(list, boss, index, false, false);
                    card.setBounds(cell); card.doLayout();   // as the list paints it: the renderer at the cell's bounds
                    BorderLayout layout = (BorderLayout) card.getLayout();
                    Component chip = layout.getLayoutComponent(BorderLayout.EAST);
                    JLabel title = (JLabel) layout.getLayoutComponent(BorderLayout.NORTH), subtitle = (JLabel) layout.getLayoutComponent(BorderLayout.SOUTH);
                    assertTrue(chip.isVisible());
                    assertEquals(name + ": the whole chip is laid out", chip.getPreferredSize().width, chip.getWidth());
                    Rectangle chipInList = new Rectangle(cell.x + chip.getX(), cell.y + chip.getY(), chip.getWidth(), chip.getHeight());
                    assertTrue(name + ": the Boss chip " + chipInList + " lies inside the visible list " + list.getVisibleRect(), list.getVisibleRect().contains(chipInList));
                    assertTrue(name + ": the long name ellipsizes beside the chip", title.getX() + title.getWidth() <= chip.getX() && title.getWidth() < title.getPreferredSize().width);
                    assertTrue(name + ": the subtitle stays beside the chip", subtitle.getX() + subtitle.getWidth() <= chip.getX());
                    assertTrue("The tooltip keeps the full name", card.getToolTipText().contains(longName) && card.getToolTipText().contains(subtitle.getText()));
                    evidence.capture(name);
                    return null;
                });
            }
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
    }

    /**
     * P5b polish (evidence findings 1 and 3): the enemy list takes a generous default share of the meter in the Live meter tab,
     * so "All enemies · 13" is whole and the boss card keeps at least ten characters of its name at 680×520 font 18 (or, where
     * a card is narrower than that, the Boss marker opens its facts instead of the chip); at 1240×800 font 13 a wide card
     * keeps the chip and its whole facts. The table keeps its minimum width and three rows; nothing scrolls sideways. A
     * divider the reader moved stays where they left it.
     */
    @Test public void enemyCardsKeepReadableNamesAndFactsBesideTheBossChip() throws Exception {
        TomatoData data = new TomatoData(); DpsData fight = colossus(data); data.dpsData.add(fight);
        DpsGUI dps = edt(() -> new DpsGUI(data, null, new JPanel()));
        WorkspaceShell shell = shell(livePage(dps), dps, fight);
        try {
            for (int[] size : new int[][]{{1240, 800, 13}, {680, 520, 18}, {680, 520, 13}, {1240, 800, 18}}) {
                String name = "dps-meter-enemy-names-" + size[0] + "-" + size[2];
                edt(() -> { evidence.show(shell, name, size[0], size[1], size[2]); return null; });
                evidence.settle();
                edt(() -> {
                    MeterDpsGUI meter = dps.meter();
                    @SuppressWarnings("unchecked") JList<Entity> list = field(meter, "enemyList", JList.class);
                    JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
                    JSplitPane split = field(meter, "split", JSplitPane.class);
                    JScrollPane page = VisualEvidence.named(dps, "dps-damage-scroll", JScrollPane.class);
                    ui.WaveThreeEvidence.reveal(scroll, scroll.getHeight());
                    assertFalse(name + ": the enemy list does not scroll sideways", scroll.getHorizontalScrollBar().isShowing());
                    Rectangle splitInPage = SwingUtilities.convertRectangle(split.getParent(), split.getBounds(), page.getViewport());
                    assertTrue(name + ": the meter " + splitInPage + " fits the page " + page.getViewport().getSize(), splitInPage.x + splitInPage.width <= page.getViewport().getWidth());
                    Component table = split.getRightComponent();
                    assertTrue(name + ": the table keeps its minimum width (" + table.getWidth() + " of " + table.getMinimumSize().width + ")",
                        table.getWidth() >= table.getMinimumSize().width);
                    String all = shown(slot(laidOut(list, 0), BorderLayout.NORTH));
                    assertEquals(name + ": the first card is whole (list " + list.getWidth() + " px)", "All enemies · 13", all);
                    int index = -1;
                    for (int i = 0; i < list.getModel().getSize(); i++) if (list.getModel().getElementAt(i) != null && list.getModel().getElementAt(i).isBossMob()) index = i;
                    assertEquals("Highest max HP first: the boss", 1, index);
                    JPanel card = laidOut(list, index);
                    JLabel title = slot(card, BorderLayout.NORTH), subtitle = slot(card, BorderLayout.SOUTH);
                    Component chip = ((BorderLayout) card.getLayout()).getLayoutComponent(BorderLayout.EAST);
                    String name1 = shown(title), facts = shown(subtitle);
                    System.out.println(name + ": enemy list " + list.getWidth() + " px, split " + split.getWidth() + " px (divider " + split.getDividerLocation()
                        + "), boss title \"" + name1 + "\", subtitle \"" + facts + "\", chip " + (chip.isVisible() ? "shown" : "hidden"));
                    assertTrue(name + ": the boss name keeps at least ten characters: \"" + name1 + "\" (list " + list.getWidth() + " px)", kept(name1) >= 10);
                    if (!chip.isVisible()) assertTrue(name + ": without the chip the facts open with the Boss marker: " + subtitle.getText(), subtitle.getText().startsWith("Boss · "));
                    if (size[0] == 1240 && size[2] == 13) {
                        assertTrue(name + ": a wide card keeps the chip", chip.isVisible());
                        assertEquals(name + ": the boss facts are whole beside the chip (list " + list.getWidth() + " px)", "400,000 HP · 89.4k dmg · 71.9 s", facts);
                    }
                    String tip = card.getToolTipText();
                    assertTrue("The tooltip keeps the full text: " + tip, tip.contains("Synthetic Colossus") && tip.contains("Boss") && tip.contains("400,000 HP · 89.4k dmg · 71.9 s"));
                    assertEquals(tip, card.getAccessibleContext().getAccessibleName());
                    if (size[0] == 680 && size[2] == 18) assertMeterUsable(dps, name);
                    evidence.capture(name);
                    return null;
                });
            }
            // A divider the reader moved (a drag or the keys end in setDividerLocation) is theirs: a relayout keeps it.
            int moved = edt(() -> {
                JSplitPane split = field(dps.meter(), "split", JSplitPane.class);
                int location = split.getDividerLocation() + 40;
                split.setDividerLocation(location);
                return location;
            });
            evidence.settle();
            edt(() -> {
                JSplitPane split = field(dps.meter(), "split", JSplitPane.class);
                split.revalidate();
                return null;
            });
            evidence.settle();
            assertEquals("The reader's divider stays", moved, (int) edt(() -> field(dps.meter(), "split", JSplitPane.class).getDividerLocation()));
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
    }

    /**
     * P5b polish (evidence finding 7): ‹ · position · › · Go live · Pause are one unit. Beside the search (the scope slot) or
     * wrapped below it, the five controls share one row in that order: alone as the S6 matrix shows the meter (680×520 font 18
     * split them, "‹" staying on the search row), and in the Live meter tab; the one-row check at 1240×800 font 13 still passes.
     */
    @Test public void encounterControlsStayTogetherWhenTheyWrap() throws Exception {
        TomatoData data = new TomatoData(); DpsData fight = encounter(data, "Synthetic Halls"); data.dpsData.add(fight);
        DpsGUI dps = edt(() -> new DpsGUI(data, null, new JPanel()));
        edt(() -> { assertTrue(dps.showEncounter(dps.encounters().find(fight).id)); return null; });
        int[][] sizes = {{680, 520, 18}, {680, 520, 13}, {1240, 800, 18}, {1240, 800, 13}};
        try {
            for (int[] size : sizes) {
                String name = "dps-meter-scope-alone-" + size[0] + "-" + size[2];
                edt(() -> { evidence.show(dps, name, size[0], size[1], size[2]); return null; });
                evidence.settle();
                edt(() -> { assertScopeTogether(name, dps, size[0] == 1240 && size[2] == 13); evidence.capture(name); return null; });
            }
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        WorkspaceShell shell = shell(livePage(dps), dps, fight);
        try {
            for (int[] size : sizes) {
                String name = "dps-meter-scope-" + size[0] + "-" + size[2];
                edt(() -> { evidence.show(shell, name, size[0], size[1], size[2]); return null; });
                evidence.settle();
                edt(() -> {
                    VisualEvidence.named(dps, "dps-damage-scroll", JScrollPane.class).getViewport().setViewPosition(new Point(0, 0));
                    assertScopeTogether(name, dps, size[0] == 1240 && size[2] == 13);
                    evidence.capture(name);
                    return null;
                });
            }
            // 24 pt in a compact window: a line of its own is narrower than the unit, so the controls wrap inside it; every one
            // stays whole inside the row, "‹" beside the position it steps.
            String name = "dps-meter-scope-680-24";
            edt(() -> { evidence.show(shell, name, 680, 520, 24); return null; });
            evidence.settle();
            edt(() -> {
                FilterBar bar = bar(dps);
                assertInsideRow(name, bar);
                Rectangle previous = null;
                for (String control : new String[]{"dps-previous-encounter", "dps-open-library", "dps-next-encounter", "dps-go-live", "dps-pause-view"}) {
                    JComponent component = VisualEvidence.named(bar, control, JComponent.class);
                    Rectangle bounds = component.getBounds();
                    assertTrue(name + ": " + control + " " + bounds + " is whole inside its unit " + component.getParent().getSize(), component.isShowing()
                        && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= component.getParent().getWidth() && bounds.y + bounds.height <= component.getParent().getHeight());
                    if (previous == null) previous = SwingUtilities.convertRectangle(component.getParent(), bounds, bar);
                    else if ("dps-open-library".equals(control)) {
                        Rectangle position = SwingUtilities.convertRectangle(component.getParent(), bounds, bar);
                        assertTrue(name + ": ‹ " + previous + " beside the position " + position, Math.abs(position.getCenterY() - previous.getCenterY()) < previous.height / 2.0);
                    }
                }
                evidence.capture(name);
                return null;
            });
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
    }

    @Test public void searchSlotDrawerAndMoreActionsHoldTheMeterControls() throws Exception {
        edt(() -> {
            TomatoData data = new TomatoData(); data.dpsData.add(encounter(data, "Synthetic Halls"));
            DpsGUI dps = new DpsGUI(data, null, new JPanel());
            MeterDpsGUI meter = dps.meter();
            FilterBar bar = bar(dps);
            Component slot = searchSlot(bar);
            assertTrue("Player search in the search slot", SwingUtilities.isDescendingFrom(meter.searchField(), slot));
            assertEquals("Search players", meter.searchField().getClientProperty("JTextField.placeholderText"));
            assertTrue("Rank by beside it", SwingUtilities.isDescendingFrom(meter.metricChoice(), slot));
            assertNotNull(VisualEvidence.find((Container) slot, JLabel.class, label -> "Rank by".equals(label.getText())));
            JComponent drawer = bar.drawerContent();
            JComboBox<?> presets = field(dps, "filterComboBox", JComboBox.class), view = field(dps, "viewMode", JComboBox.class);
            AbstractButton addPreset = VisualEvidence.find(drawer, AbstractButton.class, b -> "Edit DPS filters".equals(b.getAccessibleContext().getAccessibleName()));
            for (Component control : new Component[]{meter.classChoice(), meter.enemyChoice(), meter.classColors(), presets, addPreset, view})
                assertTrue("In the drawer: " + control, SwingUtilities.isDescendingFrom(control, drawer));
            assertEquals("Meters", view.getItemAt(0)); assertEquals("Legacy", view.getItemAt(1)); assertEquals(2, view.getItemCount());
            assertFalse("Details are not filters: the summary stays in the meter", SwingUtilities.isDescendingFrom(field(meter, "summary", JLabel.class), bar));
            assertFalse(SwingUtilities.isDescendingFrom(VisualEvidence.named(dps, "dps-status-row", JPanel.class), bar));

            OverflowMenu more = bar.overflow();
            for (String label : new String[]{"Saved resources…", "Edit DPS filters…", "Open Recordings", "Load .dps…"}) assertNotNull(label, more.item(label));
            assertFalse("Saved resources need a saved-history workspace", more.item("Saved resources…").isEnabled());
            assertFalse("Nothing to open without the shell's Recordings hook", more.item("Open Recordings").isEnabled());
            int[] opened = {0};
            dps.onOpenLibrary(() -> opened[0]++);
            assertTrue(more.item("Open Recordings").isEnabled());
            more.item("Open Recordings").doClick();
            assertEquals(1, opened[0]);

            // A standalone meter builds no filter row of its own, and still filters with the same controls.
            MeterDpsGUI standalone = new MeterDpsGUI();
            for (JComponent control : new JComponent[]{standalone.searchField(), standalone.metricChoice(), standalone.classChoice(), standalone.enemyChoice(), standalone.classColors()})
                assertFalse("Not in a standalone meter's tree: " + control, SwingUtilities.isDescendingFrom(control, standalone));
            standalone.renderData(map("Synthetic Halls"), new ArrayList<>(encounter(data, "Synthetic Halls").hitList.values()), new ArrayList<>(), 3000, false);
            assertEquals(4, standalone.table().getRowCount());
            standalone.searchField().setText("alp");
            assertEquals(1, standalone.table().getRowCount());
            return null;
        });
    }

    @Test public void chipsNameEachActiveFilterAndClearResetsThem() throws Exception {
        edt(() -> {
            TomatoData data = new TomatoData(); DpsData fight = encounter(data, "Synthetic Halls"); data.dpsData.add(fight);
            DpsGUI dps = new DpsGUI(data, null, new JPanel());
            assertTrue(dps.showEncounter(dps.encounters().find(fight).id));
            MeterDpsGUI meter = dps.meter(); FilterBar bar = bar(dps);
            JComboBox<?> presets = field(dps, "filterComboBox", JComboBox.class);
            assertEquals(Collections.emptyList(), chips(bar));
            meter.enemyChoice().setSelectedIndex(3);
            String chosen = meter.classChoice().getItemAt(1);
            meter.classChoice().setSelectedItem(chosen);
            meter.searchField().setText("alp");
            dps.addComboBox("Synthetic preset", "Synthetic preset,-,H,-,alpha");
            presets.setSelectedItem("Synthetic preset");
            assertEquals(Arrays.asList("Class: " + chosen, "Player: alp", "Preset: Synthetic preset", "Bosses only"), chips(bar));
            assertEquals("Filters · 4", VisualEvidence.named(bar, "dps-meter-filters", AbstractButton.class).getText());

            removeChip(bar, "Player: alp");
            assertEquals("A chip removes only its own filter", "", meter.searchField().getText());
            assertEquals(Arrays.asList("Class: " + chosen, "Preset: Synthetic preset", "Bosses only"), chips(bar));
            removeChip(bar, "Bosses only");
            assertEquals(0, meter.enemyChoice().getSelectedIndex());
            assertEquals(chosen, meter.classChoice().getSelectedItem());

            AbstractButton clear = VisualEvidence.named(bar, "dps-meter-clear-filters", AbstractButton.class);
            assertTrue(clear.isVisible());
            clear.doClick();
            assertEquals(Collections.emptyList(), chips(bar));
            assertEquals("All classes", meter.classChoice().getSelectedItem());
            assertEquals("Default", presets.getSelectedItem());
            assertEquals("The Default preset turns the preset off", 0, Filter.filter);
            assertFalse(clear.isVisible());
            dps.removeComboBox("Synthetic preset");
            return null;
        });
    }

    @Test public void scopeSlotHoldsTheEncounterPositionGoLiveAndPause() throws Exception {
        edt(() -> {
            TomatoData data = new TomatoData();
            for (String name : new String[]{"First halls", "Second halls", "Third halls"}) data.dpsData.add(encounter(data, name));
            DpsGUI dps = new DpsGUI(data, null, new JPanel());
            JComponent scope = VisualEvidence.named(bar(dps), "dps-meter-scope", JComponent.class);
            AbstractButton previous = VisualEvidence.named(scope, "dps-previous-encounter", AbstractButton.class);
            AbstractButton position = VisualEvidence.named(scope, "dps-open-library", AbstractButton.class);
            AbstractButton next = VisualEvidence.named(scope, "dps-next-encounter", AbstractButton.class);
            AbstractButton live = VisualEvidence.named(scope, "dps-go-live", AbstractButton.class);
            JCheckBox paused = VisualEvidence.named(scope, "dps-pause-view", JCheckBox.class);
            assertSame("The pause box is the same field", field(dps, "paused", JCheckBox.class), paused);
            assertEquals("‹", previous.getText()); assertEquals("›", next.getText()); assertEquals("Go live", live.getText());
            assertEquals("Previous encounter", previous.getAccessibleContext().getAccessibleName());
            assertEquals("Next encounter", next.getAccessibleContext().getAccessibleName());
            assertEquals("Live", position.getText());
            previous.doClick();
            assertEquals("3 of 3", position.getText()); assertEquals(2, dps.getIndex());
            previous.doClick();
            assertEquals("2 of 3", position.getText());
            next.doClick();
            assertEquals("3 of 3", position.getText());
            live.doClick();
            assertEquals("Live", position.getText()); assertEquals(-1, dps.getIndex());
            return null;
        });
    }

    @Test public void legacyIsAnAnalystOptionAndSimpleReturnsToMeters() throws Exception {
        int equipment = DpsDisplayOptions.equipmentOption;
        // Legacy shows the text display unless the equipment option asks for icons (3); other tests change this static option.
        DpsDisplayOptions.equipmentOption = 0;
        try { legacyIsAnAnalystOption(); } finally { DpsDisplayOptions.equipmentOption = equipment; }
    }

    private void legacyIsAnAnalystOption() throws Exception {
        edt(() -> {
            TomatoData data = new TomatoData(); DpsData fight = encounter(data, "Synthetic Halls"); data.dpsData.add(fight);
            DpsGUI dps = new DpsGUI(data, null, new JPanel());
            assertTrue(dps.showEncounter(dps.encounters().find(fight).id));
            MeterDpsGUI meter = dps.meter(); FilterBar bar = bar(dps);
            JComboBox<?> view = field(dps, "viewMode", JComboBox.class), presets = field(dps, "filterComboBox", JComboBox.class);
            assertTrue(SwingUtilities.isDescendingFrom(view, bar.drawerContent()));
            assertFalse("Simple hides the view mode", visibleInDrawer(view, bar));

            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst shows it", visibleInDrawer(view, bar));
            meter.searchField().setText("alp");
            assertEquals(Collections.singletonList("Player: alp"), chips(bar));
            view.setSelectedIndex(1);
            assertSame("Legacy shows the text display", field(dps, "displayString", Object.class), field(dps, "centerDisplay", Object.class));
            for (JComponent control : new JComponent[]{meter.searchField(), meter.metricChoice(), meter.classChoice(), meter.enemyChoice(), meter.classColors()}) {
                assertFalse("Meters-only control disabled in Legacy: " + control, control.isEnabled());
                assertNotNull("…with its reason", control.getToolTipText());
            }
            assertTrue("The preset applies to Legacy as well", presets.isEnabled());
            assertEquals("Meters-only filters are not claimed in Legacy", Collections.emptyList(), chips(bar));

            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertFalse(visibleInDrawer(view, bar));
            assertEquals("Leaving Analyst leaves Legacy", 0, view.getSelectedIndex());
            assertSame(meter, field(dps, "centerDisplay", Object.class));
            assertTrue(meter.searchField().isEnabled());
            assertEquals(Collections.singletonList("Player: alp"), chips(bar));
            meter.searchField().setText("");

            // Only that transition switches: the binding also runs whenever the page becomes displayable.
            view.setSelectedIndex(1);
            JFrame frame = new JFrame();
            try {
                frame.setContentPane(dps); frame.pack();
                assertEquals("Becoming displayable in Simple keeps a Legacy choice", 1, view.getSelectedIndex());
            } finally { frame.dispose(); }
            view.setSelectedIndex(0);
            return null;
        });
    }

    /** The two EncounterLink call sites (the meter and Home/Build's projections) label only a user's import as imported. */
    @Test public void savedFullDetailIsNotLabeledImportedButAUserImportIs() throws Exception {
        DpsData linked = serializableEncounter(new EncounterContext(new VisitRef("session-a", "visit-a"), 7, 1_790_000_000_000L));
        Path savedFile = CombatAutosave.fullDetailFile(temp.newFolder("session-a").toPath(), linked.getRecordingId());
        Files.createDirectories(savedFile.getParent()); write(savedFile, linked.getSaveFile(false));
        Path userFile = temp.newFile("synthetic-copy.dps").toPath(); write(userFile, linked.getSaveFile(false));
        EncounterImport savedDetail = EncounterImport.readSaved(savedFile), userImport = EncounterImport.read(userFile);
        edt(() -> {
            DpsGUI dps = new DpsGUI(new TomatoData(), null, new JPanel());
            EncounterCatalog.Entry kept = dps.encounters().addSaved(savedDetail, entry -> false);
            EncounterCatalog.Entry imported = dps.encounters().add(userImport).entry;
            assertEquals(EncounterCatalog.Kind.SAVED, kept.kind()); assertEquals(EncounterCatalog.Kind.IMPORTED, imported.kind());
            assertTrue(dps.showEncounter(kept.id));
            assertEquals(EncounterLink.State.LINKED, dps.shownLink().state);
            assertFalse("Saved full detail is not an import", dps.shownLink().imported);
            assertEquals("Linked", dps.shownLink().label());
            assertTrue(dps.showEncounter(imported.id));
            assertTrue(dps.shownLink().imported);
            assertTrue(dps.shownLink().label(), dps.shownLink().label().contains("imported"));

            Map<String, RecordedEncounter> known = new HashMap<>();
            DpsGUI.recordedEncounters(known);
            assertFalse("Home/Build: saved full detail is not an import", known.get(kept.id).link.imported);
            assertTrue(known.get(imported.id).link.imported);
            return null;
        });
    }

    /** ⋯ › Load .dps… reads through the safe reader off the EDT, adds the file to Recordings and shows it; failures name only the file. */
    @Test public void loadDpsImportsTheFileIntoRecordingsAndShowsItInTheMeter() throws Exception {
        Path file = temp.newFile("synthetic-load.dps").toPath(); write(file, serializableEncounter(null).getSaveFile(false));
        Path broken = temp.newFile("broken.dps").toPath(); Files.write(broken, "not a recording".getBytes(StandardCharsets.UTF_8));
        DpsGUI dps = edt(() -> new DpsGUI(new TomatoData(), null, new JPanel()));
        JTextArea notice = edt(() -> VisualEvidence.named(dps, "dps-load-notice", JTextArea.class));
        edt(() -> dps.importFile(file.toFile())).get(30, TimeUnit.SECONDS);
        await(() -> dps.currentEncounterId() != null && notice.getText().startsWith("Loaded"));
        edt(() -> {
            EncounterCatalog.Entry entry = dps.encounters().find(dps.currentEncounterId());
            assertEquals(1, dps.encounters().entries().size());
            assertTrue("A user's file is an import", entry.imported());
            assertEquals("synthetic-load.dps", entry.origin.fileName);
            assertTrue(dps.shownLink().imported);
            assertEquals("1 of 1", VisualEvidence.named(dps, "dps-open-library", AbstractButton.class).getText());
            assertTrue(notice.getText(), notice.isVisible() && notice.getText().contains("synthetic-load.dps"));
            return null;
        });
        String shown = edt(dps::currentEncounterId);
        SwingWorker<EncounterImport, Void> refused = edt(() -> dps.importFile(broken.toFile()));
        try { refused.get(30, TimeUnit.SECONDS); fail("A file that is not a recording is refused"); }
        catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
        await(() -> notice.getText().startsWith("Could not load"));
        edt(() -> {
            assertTrue(notice.getText(), notice.getText().contains("broken.dps"));
            assertFalse("File names only, never a folder: " + notice.getText(), notice.getText().contains(temp.getRoot().getName()));
            assertEquals("Nothing was added", 1, dps.encounters().entries().size());
            assertEquals("The meter keeps what it showed", shown, dps.currentEncounterId());
            dps.setIndex(-1);
            assertFalse("Navigating dismisses the notice", notice.isVisible());
            return null;
        });
    }

    /** S6 at desktop width, as FilterBarEvidenceTest checks it: with the drawer closed, the search slot and Filters share one row. */
    private static void assertOneFilterRow(String name, FilterBar bar) {
        AbstractButton filters = VisualEvidence.named(bar, bar.getName().replace("-filter-bar", "-filters"), AbstractButton.class);
        Component slot = filters.getParent().getComponent(0);
        int slotY = SwingUtilities.convertPoint(slot, 0, 0, bar).y, filtersY = SwingUtilities.convertPoint(filters, 0, 0, bar).y;
        assertTrue(name + ": the search slot and Filters share one row", Math.abs(slotY - filtersY) < filters.getHeight());
    }

    /**
     * The encounter controls share one row in order (‹, position, ›, Go live, Pause), inside the filter row; at desktop width
     * they sit in the scope slot beside the search on the filter row's single line.
     */
    private static void assertScopeTogether(String name, DpsGUI dps, boolean desktop) {
        FilterBar bar = bar(dps);
        assertInsideRow(name, bar);
        Rectangle first = null;
        int right = Integer.MIN_VALUE;
        StringBuilder placed = new StringBuilder();
        for (String control : new String[]{"dps-previous-encounter", "dps-open-library", "dps-next-encounter", "dps-go-live", "dps-pause-view"}) {
            JComponent component = VisualEvidence.named(bar, control, JComponent.class);
            assertTrue(name + ": " + control + " shows", component.isShowing());
            Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), bar);
            placed.append(control).append(' ').append(bounds).append("; ");
            if (first == null) first = bounds;
            assertTrue(name + ": the encounter controls share one row: " + placed, Math.abs(bounds.getCenterY() - first.getCenterY()) < first.height / 2.0);
            assertTrue(name + ": the encounter controls keep their order: " + placed, bounds.x >= right);
            right = bounds.x + bounds.width;
        }
        assertEquals("1 of 1", VisualEvidence.named(bar, "dps-open-library", AbstractButton.class).getText());
        if (desktop) {
            assertOneFilterRow(name, bar);
            Component search = dps.meter().searchField();
            int searchY = SwingUtilities.convertPoint(search, 0, search.getHeight() / 2, bar).y;
            assertTrue(name + ": beside the search at desktop width: " + placed, Math.abs(first.getCenterY() - searchY) < first.height / 2.0);
        }
    }

    /** The text a label paints at its current size (JLabel's own clipping, "..." included). */
    private static String shown(JLabel label) {
        Insets insets = label.getInsets();
        Rectangle view = new Rectangle(insets.left, insets.top, label.getWidth() - insets.left - insets.right, label.getHeight() - insets.top - insets.bottom);
        return SwingUtilities.layoutCompoundLabel(label, label.getFontMetrics(label.getFont()), label.getText(), label.getIcon(), label.getVerticalAlignment(),
            label.getHorizontalAlignment(), label.getVerticalTextPosition(), label.getHorizontalTextPosition(), view, new Rectangle(), new Rectangle(), label.getIconTextGap());
    }
    /** Characters of the text a clipped label keeps before its "...". */
    private static int kept(String shown) {
        String text = shown.endsWith("...") ? shown.substring(0, shown.length() - 3) : shown;
        return text.codePointCount(0, text.length());
    }
    /** The renderer's card for row {@code index}, laid out at the cell's bounds as the list paints it (one shared panel: read it before the next call). */
    private static JPanel laidOut(JList<Entity> list, int index) {
        JPanel card = (JPanel) list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(index), index, false, false);
        card.setBounds(list.getCellBounds(index, index)); card.doLayout();
        return card;
    }
    private static JLabel slot(JPanel card, String where) { return (JLabel) ((BorderLayout) card.getLayout()).getLayoutComponent(where); }

    /** No sideways overflow: every showing control of the row lies inside the row's width. */
    private static void assertInsideRow(String name, FilterBar bar) {
        List<JComponent> controls = new ArrayList<>();
        collect(bar, controls);
        assertFalse(controls.isEmpty());
        for (JComponent control : controls) {
            Rectangle bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), bar);
            assertTrue(name + ": " + control.getClass().getSimpleName() + " " + control.getName() + " at " + bounds + " inside the row (" + bar.getWidth() + ")",
                bounds.x >= 0 && bounds.x + bounds.width <= bar.getWidth() + 1);
        }
    }
    private static void collect(Container root, List<JComponent> into) {
        for (Component child : root.getComponents()) {
            if (child.isShowing() && (child instanceof AbstractButton || child instanceof JComboBox || child instanceof JTextField)) into.add((JComponent) child);
            else if (child instanceof Container) collect((Container) child, into);
        }
    }

    /** As dps/WaveThreeEvidenceTest: the damage table keeps at least three rows and its whole viewport can be scrolled into view. */
    private static void assertMeterUsable(DpsGUI dps, String name) {
        JScrollPane scroll = dps.meter().tableScroll();
        JTable table = dps.meter().table();
        int rows = scroll.getViewport().getHeight() / table.getRowHeight();
        assertTrue(name + ": damage table viewport " + scroll.getViewport().getSize() + " shows " + rows + " rows of " + table.getRowHeight(), rows >= 3);
        ui.WaveThreeEvidence.reveal(scroll, scroll.getHeight());
        assertEquals(name + ": whole damage table viewport reachable", scroll.getHeight(), scroll.getVisibleRect().height);
    }

    private static boolean visibleInDrawer(Component control, FilterBar bar) {
        for (Component c = control; c != null && c != bar.drawerContent(); c = c.getParent()) if (!c.isVisible()) return false;
        return true;
    }

    private static FilterBar bar(DpsGUI dps) { return VisualEvidence.named(dps, "dps-meter-filter-bar", FilterBar.class); }

    /** The row's first slot (FilterBar puts the search slot before the Filters toggle). */
    private static Component searchSlot(FilterBar bar) {
        return VisualEvidence.named(bar, "dps-meter-filters", AbstractButton.class).getParent().getComponent(0);
    }

    /** Chip labels in row order (Chip.removable names each chip "Filter: <label>"). */
    private static List<String> chips(FilterBar bar) {
        List<String> labels = new ArrayList<>();
        chips(bar, labels);
        return labels;
    }
    private static void chips(Container root, List<String> into) {
        for (Component child : root.getComponents()) {
            String accessible = child instanceof JPanel ? child.getAccessibleContext().getAccessibleName() : null;
            if (accessible != null && accessible.startsWith("Filter: ")) into.add(accessible.substring("Filter: ".length()));
            else if (child instanceof Container) chips((Container) child, into);
        }
    }
    private static void removeChip(FilterBar bar, String label) {
        VisualEvidence.find(bar, AbstractButton.class, b -> ("Remove filter: " + label).equals(b.getAccessibleContext().getAccessibleName())).doClick();
    }

    /** The Live meter tab of a Runs & DPS page around {@code dps}; closed after the test. */
    private RunsDpsPage livePage(DpsGUI dps) throws Exception {
        RunsDpsPage page = edt(() -> new RunsDpsPage(new RunsPage(new JPanel(), () -> null), dps));
        closing.add(page);
        return page;
    }

    /** A synthetic shell with {@code page} as page 10, its Live meter tab in front, showing {@code shown}. */
    private static WorkspaceShell shell(RunsDpsPage page, DpsGUI dps, DpsData shown) throws Exception {
        return edt(() -> {
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
            pages[10] = page;
            WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Synthetic workspace must not capture"), true);
            created.select(10); page.bring(RunsTab.LIVE_METER);
            assertTrue(dps.showEncounter(dps.encounters().find(shown).id));
            return created;
        });
    }

    /**
     * Four players of two classes on a boss and a minion (and {@code more} enemies, with 1,234,567,890 max HP); names do not
     * depend on the asset catalog.
     */
    private static DpsData encounter(TomatoData data, String mapName, Entity... more) {
        Entity[] players = {player(data, 1, "Alpha", 768), player(data, 2, "Bravo", 775), player(data, 3, "Charlie", 768), player(data, 4, "Delta", 775)};
        Entity boss = new Entity(data, 50, 0) { @Override public boolean isBossMob() { return true; } }, minion = new Entity(data, 51, 0);
        List<Entity> enemies = new ArrayList<>(Arrays.asList(boss, minion));
        enemies.addAll(Arrays.asList(more));
        HashMap<Integer, Entity> hits = new HashMap<>();
        for (Entity enemy : enemies) {
            StatData hp = new StatData(); hp.statValue = enemy == boss ? 900_000 : enemy == minion ? 1000 : 1_234_567_890; enemy.stat.set(StatType.MAX_HP_STAT, hp);
            for (Entity player : players) enemy.genericDamageHit(player, new Projectile(100 * player.id + enemy.id % 1000), 1000 + player.id);
            enemy.updateDamageTaken(1000); enemy.updateDamageTaken(3000);
            hits.put(enemy.id, enemy);
        }
        return new DpsData(map(mapName), hits, new ArrayList<>(), 3000, 1000, null);
    }
    /**
     * The evidence fight's enemy list (ui.RunsDpsEvidenceTest): four players on twelve minions (2,000 to 6,000 HP) and the boss
     * "Synthetic Colossus" (400,000 HP, 89.4k recorded damage over a 71.9 s window), whose card reads "400,000 HP · 89.4k dmg
     * · 71.9 s"; names do not depend on the asset catalog.
     */
    private static DpsData colossus(TomatoData data) {
        Entity[] players = {player(data, 1, "Alpha", 768), player(data, 2, "Bravo", 775), player(data, 3, "Charlie", 768), player(data, 4, "Delta", 775)};
        HashMap<Integer, Entity> hits = new HashMap<>();
        Entity boss = new Entity(data, 2000, 0) {
            @Override public boolean isBossMob() { return true; }
            @Override public String name() { return "Synthetic Colossus"; }
        };
        List<Entity> enemies = new ArrayList<>(Collections.singletonList(boss));
        for (int e = 0; e < 12; e++) enemies.add(new Entity(data, 1000 + e, 0) { @Override public String name() { return "Synthetic Warden"; } });
        for (Entity enemy : enemies) {
            StatData hp = new StatData(); hp.statValue = enemy == boss ? 400_000 : 2_000 * (enemy.id % 3 + 1); enemy.stat.set(StatType.MAX_HP_STAT, hp);
            for (Entity player : players) enemy.genericDamageHit(player, new Projectile(enemy == boss ? 22_350 : 100 + player.id), 1000 + player.id);
            enemy.updateDamageTaken(1000); enemy.updateDamageTaken(enemy == boss ? 72_900 : 3000);
            hits.put(enemy.id, enemy);
        }
        return new DpsData(map("Synthetic Halls"), hits, new ArrayList<>(), 72_900, 1000, null);
    }
    private static Entity player(TomatoData data, int id, String name, int type) {
        Entity player = new Entity(data, id, 0) { @Override public String name() { return name; } };
        player.objectType = type;
        return player;
    }
    /** A recording whose graph the safe reader accepts (plain entities only). */
    private static DpsData serializableEncounter(EncounterContext context) {
        Entity self = new Entity(null, 7, 0); self.objectType = 768;
        Entity enemy = new Entity(null, 50, 0); StatData hp = new StatData(); hp.statValue = 1000; enemy.stat.set(StatType.MAX_HP_STAT, hp);
        enemy.genericDamageHit(self, new Projectile(25), 1000); enemy.updateDamageTaken(1000); enemy.updateDamageTaken(2000);
        HashMap<Integer, Entity> hits = new HashMap<>(); hits.put(enemy.id, enemy);
        return new DpsData(map("Synthetic Halls"), hits, new ArrayList<>(), 1000, 1000, null, self, context);
    }
    private static MapInfoPacket map(String name) { MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name; return map; }
    private static void write(Path file, DpsData data) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(data); }
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static <T> T edt(Callable<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(body.call()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    /** Polls {@code condition} on the EDT (SwingWorker.done runs a little after get() returns). */
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!edt(condition::getAsBoolean)) {
            if (System.nanoTime() > end) fail("Timed out waiting on the EDT");
            Thread.sleep(20);
        }
    }
}
