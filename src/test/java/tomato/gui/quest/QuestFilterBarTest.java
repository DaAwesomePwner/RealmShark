package tomato.gui.quest;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import packets.data.QuestData;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.TomatoData;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.modern.TestPages;
import tomato.gui.modern.WorkspaceShell;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestFixtures.*;

public class QuestFilterBarTest {
    /** Every preference these tests change (the Board's and Planner's views, grouping, drawer, tabs and the display mode). */
    private static final List<String> PREFERENCES = List.of("ui.quests.view", "ui.quests.group", "ui.quests.pinned-first", "ui.quests.plan-view",
        "ui.filters.quests.open", "ui.tabs.quests", DisplayModeModel.KEY);
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    /** A synthetic account in game: 64 hexadecimal characters, as every account key. */
    static final String ACCOUNT = CharacterJournal.accountKey("p6b-quest-filter-row");
    @Rule public final VisualEvidence evidence = new VisualEvidence("p6b-polish-quests");
    private final Map<String, String> saved = new HashMap<>();
    private DisplayModeModel.Mode savedMode;

    @Before public void isolate() throws Exception {
        for (String key : PREFERENCES) { saved.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
        for (String key : PREFERENCES) PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key));
    }

    /**
     * Spec §6.5: category labeling ("Name types…") lives in the Filters drawer; search, reset and the Board's grouping stay in the
     * row, and (P6b) Sort by and Pinned first are the row's ⋯ items.
     */
    @Test public void questFiltersAndNameTypesLiveInTheDrawerWhileSortAndGroupingStayVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences());
            FilterBar bar = find(ui, FilterBar.class, "quests-filter-bar"); JComponent drawer = bar.drawerContent();
            for (String name : new String[]{"quest-type", "quest-reward", "quest-repeat-mode", "quest-requirement-item", "quest-pinned-only", "quest-completed",
                    "quest-name-types"})
                assertTrue(name, SwingUtilities.isDescendingFrom(find(ui, JComponent.class, name), drawer));
            for (String name : new String[]{"quest-search", "quest-reset", "quest-group-by", "quest-view"}) {
                JComponent control = find(ui, JComponent.class, name);
                assertTrue(name, SwingUtilities.isDescendingFrom(control, bar)); assertFalse(name, SwingUtilities.isDescendingFrom(control, drawer));
            }
            for (String name : new String[]{"quest-sort", "quest-pinned-first"}) assertNotNull(name + " is a ⋯ item of the row", menuItem(bar, name));
            find(ui, JComboBox.class, "quest-repeat-mode").setSelectedIndex(1); find(ui, AbstractButton.class, "quest-pinned-only").doClick();
            assertEquals(Arrays.asList("Repeatable", "Pinned only"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Pinned only"); assertFalse(find(ui, AbstractButton.class, "quest-pinned-only").isSelected());
            find(ui, AbstractButton.class, "quest-reset").doClick(); assertEquals(0, bar.activeCount());
        });
    }

    /**
     * P6b polish: "Group by" labels its list inline (the label is the list's accessible name and its labelFor); Analyst's Cards/Table
     * toggle is the bar's trailing control beside ⋯ (Quests has no Scope chip), not part of the search slot. Sort by and Pinned first
     * are ⋯ items with their names and accessible names: a "Sort by ▸" radio group of the five orders (as Party's "Duration unit ▸")
     * that orders the table and the cards, and the cards' "Pinned first" check item, which the Table view hides.
     */
    @Test public void groupByIsInlineSortByAndPinnedFirstAreOverflowItemsAndTheViewToggleIsTrailing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(SIMPLE);
            QuestGUI ui = new QuestGUI(ITEM_NAMES, id -> null, new QuestGuiTest.MemoryPreferences());
            ui.update(quests());
            FilterBar bar = find(ui, FilterBar.class, "quests-filter-bar");
            JComboBox<?> groupBy = find(ui, JComboBox.class, "quest-group-by");
            assertEquals("Group by", groupBy.getAccessibleContext().getAccessibleName());
            JLabel label = label(ui, "Group by");
            assertNotNull("The Group by label", label);
            assertSame(groupBy, label.getLabelFor());
            assertTrue("Group by stays in the row", SwingUtilities.isDescendingFrom(label, bar.searchSlot()));
            assertNull("No Sort by label in the row", label(ui, "Sort by"));
            JComponent view = find(ui, JComponent.class, "quest-view");
            assertFalse("The toggle is not in the search slot", SwingUtilities.isDescendingFrom(view, bar.searchSlot()));
            assertSame("…but beside ⋯, the bar's trailing controls", bar.overflow().getParent(), view.getParent());

            JMenu sort = (JMenu) menuItem(bar, "quest-sort");
            assertEquals("Sort by", sort.getText());
            assertEquals("Sort by", sort.getAccessibleContext().getAccessibleName());
            List<String> orders = new ArrayList<>();
            for (Component item : sort.getMenuComponents()) {
                assertTrue("A radio group", item instanceof JRadioButtonMenuItem);
                orders.add(((JRadioButtonMenuItem) item).getText());
            }
            assertEquals(List.of("Pinned first", "Reward name", "Quest type", "Fewest required items", "Quest name"), orders);
            assertTrue("Pinned first is the default order", menuItem(bar, "quest-sort-pinned").isSelected());
            menuItem(bar, "quest-sort-fewest").doClick();
            assertTrue(menuItem(bar, "quest-sort-fewest").isSelected());
            assertFalse("One order at a time", menuItem(bar, "quest-sort-pinned").isSelected());
            JTable table = find(ui, JTable.class, "quest-table");
            assertEquals("The table follows the order", "Mighty haul", table.getValueAt(0, 1));
            assertEquals("…and so do the cards", "Mighty haul", ui.board().list("mighty").items().get(0).name());
            find(ui, AbstractButton.class, "quest-reset").doClick();
            assertTrue("Reset filters restores the default order", menuItem(bar, "quest-sort-pinned").isSelected());

            JMenuItem pinnedFirst = menuItem(bar, "quest-pinned-first");
            assertTrue("A check item", pinnedFirst instanceof JCheckBoxMenuItem);
            assertEquals("Pinned first", pinnedFirst.getText());
            assertEquals("Pinned first", pinnedFirst.getAccessibleContext().getAccessibleName());
            assertTrue("On by default, and shown with the cards", pinnedFirst.isSelected() && pinnedFirst.isVisible());
            bar.overflow().item("Table view").doClick();
            assertFalse("The Table view hides the cards' item", pinnedFirst.isVisible());
            assertTrue("…and keeps Sort by", sort.isVisible());
            DisplayModeModel.application().set(ANALYST);
            assertTrue("Analyst's ⋯ keeps Sort by", bar.overflow().isVisible());
        });
    }

    /**
     * P6b polish (S6): at 1240×800 font 13 in the real shell (its sidebar and page padding leave the bar about 1,012 px), every
     * Board view is one filter row with the drawer closed, in Simple and Analyst: FilterBarAssert.assertOneRow, the grouping kept
     * visible in the Cards view (P4: a primary control), the Cards/Table toggle shown in Analyst. Captures before and after.
     */
    @Test public void everyBoardViewKeepsOneFilterRowInTheShellInBothModes() throws Exception {
        TomatoData data = new TomatoData();
        QuestGUI ui = edt(() -> boundPage(data));
        JComponent shell = edt(() -> TestPages.shell("quests", ui));
        try {
            for (DisplayModeModel.Mode mode : DisplayModeModel.Mode.values()) for (boolean cards : new boolean[]{true, false}) {
                edt(() -> {
                    DisplayModeModel.application().set(mode);
                    evidence.show(shell, "Quests filter row", 1240, 800, 13);
                    ui.openBoard(); showView(ui, cards);
                    return null;
                });
                evidence.settle();
                edt(() -> {
                    String name = "quests-board-" + (cards ? "cards" : "table") + "-1240-13-" + mode.name().toLowerCase(Locale.ROOT);
                    evidence.capture(name);
                    FilterBar bar = find(ui, FilterBar.class, "quests-filter-bar");
                    System.out.println(name + ": " + FilterBarAssert.describeRow(bar));
                    assertNotNull("Measured in the real shell", SwingUtilities.getAncestorOfClass(WorkspaceShell.class, bar));
                    assertTrue(name + ": the bar has the shell's content width (" + bar.getWidth() + ")", bar.getWidth() > 900 && bar.getWidth() < 1100);
                    assertEquals(name + ": the Board's view", cards, ui.cardsShown());
                    assertFalse(name + ": the drawer is closed", bar.drawerOpen());
                    assertEquals(name + ": the default state has no chips", 0, bar.activeCount());
                    FilterBarAssert.assertOneRow(bar);
                    // ⋯ holds Sort by (and the cards' Pinned first) in both modes.
                    List<String> row = new ArrayList<>(List.of("quest-search", "quest-reset", "quests-filters", "quests-more"));
                    if (cards) row.add("quest-group-by");
                    if (mode == ANALYST) row.add("quest-view");
                    for (String control : row) assertTrue(name + ": " + control + " shows in the row", find(ui, JComponent.class, control).isShowing());
                    assertTrue(name + ": Sort by is in ⋯", menuItem(bar, "quest-sort").isVisible());
                    assertEquals(name + ": Pinned first is in ⋯ with the cards", cards, menuItem(bar, "quest-pinned-first").isVisible());
                    if (cards) inline(ui, "Group by", "quest-group-by");
                    return null;
                });
            }
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
    }

    /**
     * At 680×520 font 18 (Analyst, the Cards view: every control of the row) the row wraps cleanly in the shell: no two controls
     * overlap, each is whole inside the bar, and each label stays on its control's line.
     */
    @Test public void theCompactRowWrapsWithoutOverlapOrClipping() throws Exception {
        TomatoData data = new TomatoData();
        QuestGUI ui = edt(() -> boundPage(data));
        JComponent shell = edt(() -> TestPages.shell("quests", ui));
        try {
            edt(() -> {
                DisplayModeModel.application().set(ANALYST);
                evidence.show(shell, "Quests compact filter row", 680, 520, 18);
                ui.openBoard(); showView(ui, true);
                return null;
            });
            evidence.settle();
            edt(() -> {
                evidence.capture("quests-board-cards-680-18-analyst");
                FilterBar bar = find(ui, FilterBar.class, "quests-filter-bar");
                System.out.println("compact: " + FilterBarAssert.describeRow(bar));
                List<JComponent> controls = new ArrayList<>();
                leaves(bar, controls);
                assertTrue("The row's controls: " + controls.size(), controls.size() >= 8);
                for (JComponent control : controls) {
                    Rectangle at = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), bar);
                    assertTrue(label(control) + " is whole inside the bar: " + at + " in " + bar.getSize(),
                        at.x >= 0 && at.y >= 0 && at.x + at.width <= bar.getWidth() && at.y + at.height <= bar.getHeight());
                    assertEquals(label(control) + " is not clipped by its parents", control.getWidth(), control.getVisibleRect().width);
                    assertEquals(label(control) + " is not clipped by its parents", control.getHeight(), control.getVisibleRect().height);
                }
                for (int i = 0; i < controls.size(); i++) for (int j = i + 1; j < controls.size(); j++) {
                    Rectangle a = SwingUtilities.convertRectangle(controls.get(i).getParent(), controls.get(i).getBounds(), bar);
                    Rectangle b = SwingUtilities.convertRectangle(controls.get(j).getParent(), controls.get(j).getBounds(), bar);
                    assertFalse(label(controls.get(i)) + " " + a + " overlaps " + label(controls.get(j)) + " " + b, a.intersects(b));
                }
                inline(ui, "Group by", "quest-group-by");
                return null;
            });
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
    }

    /**
     * P6b polish: the Planner's account list shows a short account (the key's first six characters, as the Board's header names the
     * account), never the raw 64-character key; Analyst keeps the whole key in the tooltip only. Its row (label, list, then the
     * Cards/Table toggle or ⋯) is one line at 1240×800 font 13 in the shell, in both modes.
     */
    @Test public void thePlannerShowsAShortAccountOnOneRowInTheShellAndTheWholeKeyOnlyInTheAnalystTooltip() throws Exception {
        TomatoData data = new TomatoData();
        QuestGUI ui = edt(() -> boundPage(data));
        JComponent shell = edt(() -> TestPages.shell("quests", ui));
        try {
            for (DisplayModeModel.Mode mode : DisplayModeModel.Mode.values()) {
                edt(() -> {
                    DisplayModeModel.application().set(mode);
                    evidence.show(shell, "Quests planner", 1240, 800, 13);
                    ui.openPlans();
                    find(ui, JComboBox.class, "quest-plan-account").setSelectedItem(ACCOUNT);
                    return null;
                });
                evidence.settle();
                edt(() -> {
                    String name = "quests-planner-1240-13-" + mode.name().toLowerCase(Locale.ROOT);
                    evidence.capture(name);
                    JComboBox<?> account = find(ui, JComboBox.class, "quest-plan-account");
                    assertEquals("The selection is the whole key", ACCOUNT, account.getSelectedItem());
                    assertEquals(name + ": the list shows the short account", ACCOUNT.substring(0, 6) + "…", rendered(account, -1));
                    assertEquals("The placeholder reads whole", "Select a known account…", rendered(account, 0));
                    for (int i = 1; i < account.getItemCount(); i++) { // every account entry of the drop-down, short too
                        String key = String.valueOf(account.getItemAt(i));
                        assertEquals(name + ": entry " + i, key.length() > 6 ? key.substring(0, 6) + "…" : key, rendered(account, i));
                    }
                    if (mode == ANALYST) assertEquals(name + ": Analyst keeps the whole key in the tooltip", ACCOUNT, account.getToolTipText());
                    else assertFalse(name + ": Simple shows no whole key, not even in the tooltip: " + account.getToolTipText(),
                        String.valueOf(account.getToolTipText()).contains(ACCOUNT));
                    assertFalse(name + ": no showing text has the whole key", showsText(find(ui, JComponent.class, "quest-plan-panel"), ACCOUNT));
                    JLabel label = label(ui, "Planning account");
                    List<Component> row = new ArrayList<>(List.of(label, account));
                    row.add(mode == ANALYST ? find(ui, JComponent.class, "quest-plan-view") : find(ui, JComponent.class, "quest-plan-overflow"));
                    int center = middle(account);
                    for (Component part : row) {
                        assertTrue(name + ": " + label(part) + " shows", part.isShowing());
                        assertTrue(name + ": " + label(part) + " is on the account's line", Math.abs(middle(part) - center) < account.getHeight() / 2);
                    }
                    return null;
                });
            }
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
    }

    /** Royal and Cultist tribute (Daily), Festival exchange (Event; a repeatable choice), Mighty haul (unlabeled category 9). */
    static QuestData[] quests() {
        QuestData royal = data("Royal tribute", 5, new int[] {FORGOTTEN_KING, FORGOTTEN_KING}, ROYAL_EPIC_CHEST);
        QuestData cultist = data("Cultist tribute", 5, new int[] {MALUS, MALUS}, CULTISH_EPIC_CHEST);
        QuestData festival = data("Festival exchange", 8, new int[] {FESTIVAL_TOKEN, FESTIVAL_TOKEN, FESTIVAL_TOKEN}, MIGHTY_CHEST, ROYAL_EPIC_CHEST);
        festival.itemOfChoice = true; festival.repeatable = true;
        QuestData haul = data("Mighty haul", 9, new int[] {FORGOTTEN_KING}, MIGHTY_CHEST);
        return new QuestData[] {royal, cultist, festival, haul};
    }

    /** A page bound to {@link #ACCOUNT}'s current quest list, captured 15 minutes ago; categories 5 and 8 labeled Daily and Event. EDT. */
    static QuestGUI boundPage(TomatoData data) {
        ProgressionData source = data.progression();
        source.reset(ACCOUNT, "Synthetic identified account");
        assertTrue(source.quests(source.scope(), quests(), System.currentTimeMillis() - 15 * 60_000L));
        Preferences preferences = new util.InMemoryPreferencesFactory().userRoot().node("quest-filter-row");
        preferences.put("category.5", "Daily"); preferences.put("category.8", "Event");
        return new QuestGUI(data, ITEM_NAMES, id -> null, preferences);
    }

    /** Shows the Board's Cards or Table view as the user would: Simple's ⋯ item, Analyst's toggle. */
    private static void showView(QuestGUI ui, boolean cards) {
        if (ui.cardsShown() == cards) return;
        if (DisplayModeModel.application().analyst()) find(ui, JToggleButton.class, cards ? "quest-view-0" : "quest-view-1").doClick();
        else find(ui, FilterBar.class, "quests-filter-bar").overflow().item(cards ? "Cards view" : "Table view").doClick();
        assertEquals(cards, ui.cardsShown());
    }

    /** The label {@code text} sits left of the control {@code name} on the control's line (inline, not above it). */
    private static void inline(Container root, String text, String name) {
        JLabel label = label(root, text);
        JComponent control = find(root, JComponent.class, name);
        assertTrue(text + " shows", label.isShowing());
        Rectangle l = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), root);
        Rectangle c = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), root);
        assertTrue(text + " is left of its control: " + l + " / " + c, l.x + l.width <= c.x);
        assertTrue(text + " is on its control's line: " + l + " / " + c, Math.abs(l.getCenterY() - c.getCenterY()) < c.height / 2.0);
    }

    /** The showing leaf controls of the bar's row: labels, fields, lists, buttons (not the parts of a list or a field). */
    private static void leaves(Container root, List<JComponent> into) {
        for (Component child : root.getComponents()) {
            if (!child.isShowing()) continue;
            if (child instanceof JLabel || child instanceof JTextComponent || child instanceof JComboBox || child instanceof AbstractButton) into.add((JComponent) child);
            else if (child instanceof Container) leaves((Container) child, into);
        }
    }

    /** The item named {@code name} in the bar's ⋯ menu, inside its submenus too; null when absent. */
    static JMenuItem menuItem(FilterBar bar, String name) { return menuItem(bar.overflow().menu().getComponents(), name); }

    private static JMenuItem menuItem(Component[] items, String name) {
        for (Component item : items) {
            if (item instanceof JMenuItem && name.equals(item.getName())) return (JMenuItem) item;
            if (item instanceof JMenu) { JMenuItem found = menuItem(((JMenu) item).getMenuComponents(), name); if (found != null) return found; }
        }
        return null;
    }

    /** What the list paints for its entry {@code index} (-1: the selected value, as the closed list shows it). */
    private static String rendered(JComboBox<?> combo, int index) {
        @SuppressWarnings({"unchecked", "rawtypes"}) Component cell = ((ListCellRenderer) combo.getRenderer())
            .getListCellRendererComponent(new JList<>(), index < 0 ? combo.getSelectedItem() : combo.getItemAt(index), index, false, false);
        return ((JLabel) cell).getText();
    }

    private static boolean showsText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c.isShowing() && (c instanceof JLabel && String.valueOf(((JLabel) c).getText()).contains(text)
                || c instanceof JTextComponent && ((JTextComponent) c).getText().contains(text))) return true;
            if (c instanceof Container && showsText((Container) c, text)) return true;
        }
        return false;
    }

    private static int middle(Component part) {
        Window window = SwingUtilities.getWindowAncestor(part);
        return SwingUtilities.convertPoint(part, 0, part.getHeight() / 2, window).y;
    }

    private static String label(Component part) {
        if (part.getName() != null) return part.getName();
        if (part instanceof JLabel) return "'" + ((JLabel) part).getText() + "'";
        return part instanceof AbstractButton ? "'" + ((AbstractButton) part).getText() + "'" : part.getClass().getSimpleName();
    }

    private static JLabel label(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel) child).getText())) return (JLabel) child;
            if (child instanceof Container) { JLabel found = label((Container) child, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        Object[] result = new Object[1]; Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> { try { result[0] = task.call(); } catch (Exception e) { failure[0] = e; } });
        if (failure[0] != null) throw failure[0];
        @SuppressWarnings("unchecked") T value = (T) result[0];
        return value;
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
