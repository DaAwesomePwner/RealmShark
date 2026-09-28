package tomato.gui.quest;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.QuestData;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.TileList;
import tomato.gui.kit.Tokens;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestFixtures.*;

/**
 * The quest Board's Cards view (spec §6.5): one section per group with its count, pinned quests first when asked, the summary line
 * (its age re-read each minute, stale in the warn tone), the detail drawer (full lists, the pin, the raw expiration only in
 * Analyst), the Cards/Table switch in both modes, remembered choices, and card models rebuilt only when their inputs change.
 */
public class QuestBoardTest {
    private static final long NOW = 1_790_000_000_000L, MINUTE = 60_000L;
    private static final List<String> PREFERENCES = List.of("ui.quests.view", "ui.quests.group", "ui.quests.pinned-first",
        "ui.filters.quests.open", "ui.tabs.quests", "ui.mode");
    private final Map<String, String> saved = new HashMap<>();
    private DisplayModeModel.Mode savedMode;

    @Before public void isolatePreferences() throws Exception {
        for (String key : PREFERENCES) saved.put(key, PropertiesManager.getProperty(key));
        SwingUtilities.invokeAndWait(() -> {
            savedMode = DisplayModeModel.application().mode();
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
        });
        for (String key : List.of("ui.quests.view", "ui.quests.group", "ui.quests.pinned-first")) PropertiesManager.setProperties(key, "");
    }

    @After public void restorePreferences() throws Exception {
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
        for (String key : PREFERENCES) PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key));
    }

    /**
     * Epic: Royal tribute, Cultist tribute (Daily). Mighty: Festival exchange (Event; a repeatable choice of Mighty or Royal Epic),
     * Mighty haul (unlabeled category 9). No quest chest: Token swap (Daily). Rewards not captured: Unknown loot (Event).
     */
    private static QuestData[] quests() {
        QuestData royal = data("Royal tribute", 5, new int[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, ROYAL_EPIC_CHEST);
        QuestData cultist = data("Cultist tribute", 5, new int[] {MALUS, MALUS}, CULTISH_EPIC_CHEST);
        QuestData festival = data("Festival exchange", 8, new int[] {FESTIVAL_TOKEN, FESTIVAL_TOKEN, FESTIVAL_TOKEN}, MIGHTY_CHEST, ROYAL_EPIC_CHEST);
        festival.itemOfChoice = true; festival.repeatable = true;
        QuestData haul = data("Mighty haul", 9, new int[] {FORGOTTEN_KING}, MIGHTY_CHEST);
        QuestData swap = data("Token swap", 5, new int[] {MALUS}, FESTIVAL_TOKEN);
        QuestData unknown = data("Unknown loot", 8, null); unknown.rewards = null;
        return new QuestData[] {royal, cultist, festival, haul, swap, unknown};
    }

    private static QuestGuiTest.MemoryPreferences labels() {
        QuestGuiTest.MemoryPreferences prefs = new QuestGuiTest.MemoryPreferences();
        prefs.put("category.5", "Daily"); prefs.put("category.8", "Event");
        return prefs;
    }

    private static QuestGUI panel() { return new QuestGUI(ITEM_NAMES, id -> null, labels()); }

    @Test public void cardsAreTheDefaultViewWithOneSectionPerGroupAndItsCount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            ui.update(quests());
            assertTrue("Cards are the Board's default view", ui.cardsShown());
            assertEquals("quest-board", ui.board().getName());
            assertEquals(List.of("mighty", "epic", "no-chest", "not-captured"), keys(ui));
            assertEquals(List.of("Festival exchange", "Mighty haul"), names(ui.board().list("mighty").items()));
            assertEquals(List.of("Cultist tribute", "Royal tribute"), names(ui.board().list("epic").items()));
            assertEquals("quest-cards-epic", ui.board().list("epic").getName());
            assertEquals(List.of("Mighty quest chests", "2"), labels(ui.board().header("mighty")));
            assertEquals(List.of("Rewards not captured", "1"), labels(ui.board().header("not-captured")));
            assertEquals("Epic quest chests", ui.board().list("epic").getAccessibleContext().getAccessibleName());

            JComboBox<?> groupBy = named(ui, "quest-group-by", JComboBox.class);
            assertEquals("Group by", groupBy.getAccessibleContext().getAccessibleName());
            groupBy.setSelectedItem("Type label");
            assertEquals("type", PropertiesManager.getProperty("ui.quests.group"));
            assertEquals(List.of("type-5", "type-8", "no-type"), keys(ui));
            assertEquals(List.of("Daily", "3"), labels(ui.board().header("type-5")));
            assertEquals(List.of("Event", "2"), labels(ui.board().header("type-8")));
            assertEquals(List.of("No type label", "1"), labels(ui.board().header("no-type")));
            assertEquals(List.of("Cultist tribute", "Royal tribute", "Token swap"), names(ui.board().list("type-5").items()));
            assertNull("A group that is gone leaves no list behind", ui.board().list("mighty"));
            groupBy.setSelectedItem("None");
            assertEquals("none", PropertiesManager.getProperty("ui.quests.group"));
            assertEquals(List.of("all"), keys(ui));
            assertEquals(List.of("All quests", "6"), labels(ui.board().header("all")));

            QuestGUI again = panel();
            again.update(quests());
            assertEquals("A new Board restores the grouping", "None", named(again, "quest-group-by", JComboBox.class).getSelectedItem());
            assertEquals(List.of("all"), keys(again));
        });
    }

    @Test public void pinnedFirstOrdersPinnedCardsFirstWithinEachGroupAndIsRemembered() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            ui.update(quests());
            named(ui, "quest-group-by", JComboBox.class).setSelectedItem("None");
            named(ui, "quest-sort", JComboBox.class).setSelectedItem("Quest name");
            JCheckBox pinnedFirst = named(ui, "quest-pinned-first", JCheckBox.class);
            assertTrue("Pinned first is on by default", pinnedFirst.isSelected());
            open(ui, "all", "Token swap");
            named(ui.detail(), "quest-detail-pin", AbstractButton.class).doClick();
            assertEquals(List.of("Token swap", "Cultist tribute", "Festival exchange", "Mighty haul", "Royal tribute", "Unknown loot"),
                names(ui.board().list("all").items()));
            assertTrue(ui.board().list("all").items().get(0).pinned());
            assertEquals("The pinned card keeps its selection", "Token swap", ui.board().list("all").getSelectedValue().name());
            pinnedFirst.doClick();
            assertEquals("false", PropertiesManager.getProperty("ui.quests.pinned-first"));
            assertEquals("Off: the sort's order alone", List.of("Cultist tribute", "Festival exchange", "Mighty haul", "Royal tribute", "Token swap",
                "Unknown loot"), names(ui.board().list("all").items()));
            assertFalse("A new Board restores the toggle", named(panel(), "quest-pinned-first", JCheckBox.class).isSelected());
        });
    }

    @Test public void summaryCountsTheListReReadsItsAgeEachMinuteAndReadsStaleInTheWarnTone() throws Exception {
        TomatoData data = new TomatoData();
        ProgressionData source = data.progression();
        source.reset(CharacterJournal.accountKey("quest-board-summary"), "identified");
        QuestData[] rows = quests();
        rows[3].completed = true; // a done one-time quest: hidden from the cards, still in the captured list
        assertTrue(source.quests(source.scope(), rows, NOW - 14 * MINUTE));
        long[] now = {NOW};
        QuestGUI[] ui = new QuestGUI[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new QuestGUI(data, ITEM_NAMES, id -> null, new util.InMemoryPreferencesFactory().userRoot().node("quest-board-summary"));
            ui[0].clock(() -> now[0]);
            JTextArea summary = named(ui[0], "quest-summary", JTextArea.class);
            assertEquals("6 quests · 0 pinned · captured 14 min ago", summary.getText());
            assertNotEquals(Tokens.tone(Tokens.Tone.WARN), summary.getForeground());
            assertEquals(5, ui[0].board().cards().size());
            javax.swing.Timer minute = ui[0].summaryTimer();
            assertEquals(60_000, minute.getDelay());
            int builds = ui[0].boardBuilds();
            now[0] = NOW + 60 * MINUTE;
            for (ActionListener listener : minute.getActionListeners()) listener.actionPerformed(new ActionEvent(minute, ActionEvent.ACTION_PERFORMED, null));
            assertEquals("6 quests · 0 pinned · captured 1 h ago", summary.getText());
            assertEquals("The minute tick re-reads the age, never the cards", builds, ui[0].boardBuilds());
        });
        data.captureStopped();
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            JTextArea summary = named(ui[0], "quest-summary", JTextArea.class);
            assertEquals("A list that no longer matches the capture reads stale", "6 quests · 0 pinned · captured 1 h ago · stale", summary.getText());
            assertEquals(Tokens.tone(Tokens.Tone.WARN), summary.getForeground());
            ui[0].removeNotify();
        });
    }

    @Test public void cardsRebuildOnlyWhenTheirInputsChange() throws Exception {
        TomatoData data = new TomatoData();
        ProgressionData source = data.progression();
        source.reset(CharacterJournal.accountKey("quest-board-builds"), "identified");
        assertTrue(source.quests(source.scope(), quests(), NOW));
        QuestGUI[] ui = new QuestGUI[1];
        int[] builds = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new QuestGUI(data, ITEM_NAMES, id -> null, new util.InMemoryPreferencesFactory().userRoot().node("quest-board-builds"));
            builds[0] = ui[0].boardBuilds();
            assertEquals(6, ui[0].board().cards().size());
        });
        source.clearPets(); // a publication without a new list or scope, as a live poll
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("A poll without a new list rebuilds nothing", builds[0], ui[0].boardBuilds());
            named(ui[0], "quest-group-by", JComboBox.class).setSelectedItem("Type label");
            assertEquals("Group by", builds[0] + 1, ui[0].boardBuilds());
            named(ui[0], "quest-pinned-first", JCheckBox.class).doClick();
            assertEquals("Pinned first", builds[0] + 2, ui[0].boardBuilds());
            named(ui[0], "quest-search", JTextField.class).setText("Royal tribute");
            assertTrue("A filter", ui[0].boardBuilds() > builds[0] + 2);
            assertEquals(List.of("Royal tribute"), names(ui[0].board().cards()));
            named(ui[0], "quest-search", JTextField.class).setText("");
            named(ui[0], "quests-filter-bar", FilterBar.class).overflow().item("Table view").doClick();
            builds[0] = ui[0].boardBuilds();
        });
        QuestData added = data("Added later", 5, new int[] {MALUS}, STANDARD_CHEST);
        assertTrue(source.quests(source.scope(), new QuestData[] {added}, NOW + MINUTE));
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("No cards are built while the table shows", builds[0], ui[0].boardBuilds());
            named(ui[0], "quests-filter-bar", FilterBar.class).overflow().item("Cards view").doClick();
            assertEquals(builds[0] + 1, ui[0].boardBuilds());
            assertEquals("The switch shows the newest list", List.of("Added later"), names(ui[0].board().cards()));
            ui[0].removeNotify();
        });
    }

    @Test public void enterOpensTheDetailDrawerWithFullListsAndThePinAndCloseReturnsToTheCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            ui.update(quests());
            QuestDetail detail = ui.detail();
            assertEquals("quest-detail", detail.getName());
            assertFalse("The drawer opens from a card", detail.isVisible());
            TileList<QuestCardModel> mighty = ui.board().list("mighty");
            for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE})
                assertEquals(TileList.OPEN, mighty.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, 0)));
            open(ui, "mighty", "Festival exchange");
            assertTrue(detail.isVisible());
            assertEquals("Festival exchange", detail.card().name());
            String text = allText(detail);
            for (String part : new String[] {"Festival exchange", "↻ Repeatable", "Event", "Bring the listed items to the Tinkerer to claim your reward.",
                    "Pick 1 of 2", "1 × Mighty Quest Chest", "1 × Royal Epic Quest Chest", "Bring", "3 × Festival Token"})
                assertTrue(part, text.contains(part));
            AbstractButton pin = named(detail, "quest-detail-pin", AbstractButton.class);
            assertEquals("Pin quest", pin.getText());
            pin.doClick();
            assertEquals("Unpin quest", named(detail, "quest-detail-pin", AbstractButton.class).getText());
            assertTrue("The card shows the pin", card(ui, "mighty", "Festival exchange").pinned());
            assertTrue(allText(detail).contains("Pinned"));
            named(detail, "quest-detail-close", AbstractButton.class).doClick();
            assertFalse(detail.isVisible());
            assertNull(detail.card());
            assertFalse("A closed drawer keeps no quest text", allText(detail).contains("Festival exchange"));
            assertEquals("Close returns to the card", "Festival exchange", mighty.getSelectedValue().name());

            open(ui, "not-captured", "Unknown loot");
            text = allText(detail);
            assertTrue(text.contains("Rewards not captured"));
            assertTrue(text.contains("Requirements not captured"));
            assertFalse("Unknown is never none", text.contains("None listed"));
            Object escape = detail.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
            assertNotNull("Escape closes the drawer", escape);
            detail.getActionMap().get(escape).actionPerformed(new ActionEvent(detail, ActionEvent.ACTION_PERFORMED, null));
            assertFalse(detail.isVisible());

            open(ui, "no-chest", "Token swap");
            assertTrue(detail.isVisible());
            ui.update(new QuestData[] {quests()[0]});
            assertFalse("A quest that leaves the list closes its drawer", detail.isVisible());
            assertFalse(allText(detail).contains("Token swap"));
        });
    }

    @Test public void rawExpirationShowsOnlyInTheAnalystDetail() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            QuestData[] rows = quests();
            rows[0].expiration = "raw-unparsed-expiration";
            ui.update(rows);
            open(ui, "epic", "Royal tribute");
            QuestDetail detail = ui.detail();
            JTextArea expiration = find(detail, "quest-detail-expiration", JTextArea.class);
            assertTrue("Simple shows no expiration", expiration == null || !expiration.isVisible());
            assertFalse(allText(detail).contains("raw-unparsed-expiration"));
            assertFalse("Simple hides IDs", allText(detail).contains("Stable quest ID"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            expiration = named(detail, "quest-detail-expiration", JTextArea.class);
            assertTrue(expiration.isVisible());
            assertEquals("Expiration (raw server value): raw-unparsed-expiration", expiration.getText());
            assertTrue(allText(detail).contains("Stable quest ID: Royal tribute"));
            assertTrue(allText(detail).contains("Server category: 5"));
            open(ui, "epic", "Cultist tribute");
            assertEquals("Expiration (raw server value): Not supplied", named(detail, "quest-detail-expiration", JTextArea.class).getText());
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            expiration = find(detail, "quest-detail-expiration", JTextArea.class);
            assertTrue(expiration == null || !expiration.isVisible());
            assertFalse(allText(detail).contains("Expiration"));
        });
    }

    @Test public void simpleOffersTheTableViewInTheOverflowMenuAndAnalystAToggleAndBothShareTheFilters() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            ui.update(quests());
            FilterBar bar = named(ui, "quests-filter-bar", FilterBar.class);
            JTable table = named(ui, "quest-table", JTable.class);
            JSplitPane split = named(ui, "quest-list-detail-split", JSplitPane.class);
            JComboBox<?> groupBy = named(ui, "quest-group-by", JComboBox.class);
            AbstractButton footerPin = named(ui, "quest-pin", AbstractButton.class);
            assertTrue(ui.cardsShown());
            assertTrue(shown(ui.board(), ui));
            assertFalse("The table stays in the tree, hidden", shown(split, ui));
            assertTrue("Group by arranges the cards", shown(groupBy, ui));
            assertFalse("Cards: the pin toggles from the detail", shown(footerPin, ui));
            SegmentedControl toggle = named(ui, "quest-view", SegmentedControl.class);
            assertFalse("Simple: no toggle in the filter row", toggle.isVisible());
            JMenuItem item = bar.overflow().item("Table view");
            assertNotNull("Simple: the Table view is in the ⋯ menu", item);
            assertTrue(item.isVisible());
            assertTrue(bar.overflow().isVisible());

            named(ui, "quest-search", JTextField.class).setText("Royal tribute");
            assertEquals("One search serves the cards…", List.of("Royal tribute"), names(ui.board().cards()));
            assertEquals("…and the table", 1, table.getRowCount());
            item.doClick();
            assertFalse(ui.cardsShown());
            assertEquals("table", PropertiesManager.getProperty("ui.quests.view"));
            assertTrue(shown(split, ui));
            assertFalse(shown(ui.board(), ui));
            assertEquals("Cards view", item.getText());
            assertFalse("Group by arranges cards only", shown(groupBy, ui));
            assertTrue("The Table view keeps its pin actions", shown(footerPin, ui));
            assertEquals("Royal tribute", table.getValueAt(0, 1));

            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst: a Cards/Table toggle in the filter row", toggle.isVisible());
            assertEquals(1, toggle.selected());
            assertFalse(item.isVisible());
            assertFalse("A ⋯ menu with nothing to show is hidden", bar.overflow().isVisible());
            named(toggle, "quest-view-0", JToggleButton.class).doClick();
            assertTrue(ui.cardsShown());
            assertEquals("cards", PropertiesManager.getProperty("ui.quests.view"));
            assertEquals(List.of("Royal tribute"), names(ui.board().cards()));
            PropertiesManager.setProperties("ui.quests.view", "table");
            assertFalse("A new Board restores the saved view", panel().cardsShown());
        });
    }

    @Test public void emptyAndNoMatchStatesInviteTheNextStep() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            assertEquals("No quest list captured yet", named(ui, "quest-summary", JTextArea.class).getText());
            assertTrue(allText(ui.board()).contains("Enter the Daily Quest Room during capture"));
            assertTrue(ui.board().cards().isEmpty());
            ui.update(quests());
            named(ui, "quest-search", JTextField.class).setText("no such quest");
            assertTrue(ui.board().cards().isEmpty());
            assertTrue(allText(ui.board()).contains("No matching quests"));
            assertEquals("The summary counts the captured list, not the matches", "6 quests · 0 pinned · captured just now",
                named(ui, "quest-summary", JTextArea.class).getText());
        });
    }

    /**
     * Grouped by type label, each section's header already names the type, so its cards paint no type chip; grouped by chest tier or
     * not at all, they do. The card's accessible name and the detail drawer keep the type label either way.
     */
    @Test public void typeGroupsLeaveOutTheChipTheirHeaderNamesAndOtherGroupingsKeepIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            ui.update(quests());
            assertEquals("Chest tier: the card names its type", "Event", painted(ui, "mighty", "Festival exchange").chip());
            JComboBox<?> groupBy = named(ui, "quest-group-by", JComboBox.class);
            groupBy.setSelectedItem("Type label");
            assertEquals(List.of("Event", "2"), labels(ui.board().header("type-8")));
            assertEquals("The header names the type; the card does not repeat it", "", painted(ui, "type-8", "Festival exchange").chip());
            assertEquals("", painted(ui, "type-5", "Royal tribute").chip());
            assertEquals("No type label: no chip anyway", "", painted(ui, "no-type", "Mighty haul").chip());
            TileList<QuestCardModel> event = ui.board().list("type-8");
            Component cell = event.getCellRenderer().getListCellRendererComponent(event, card(ui, "type-8", "Festival exchange"), 0, false, false);
            assertEquals("The accessible name still says the type", QuestCardRenderer.accessibleName(card(ui, "type-8", "Festival exchange")),
                cell.getAccessibleContext().getAccessibleName());
            assertTrue(cell.getAccessibleContext().getAccessibleName().contains(", Event;"));
            open(ui, "type-8", "Festival exchange");
            assertTrue("The drawer still states the type label", named(ui.detail(), "quest-detail-meta", JTextArea.class).getText().endsWith(" · Event"));
            named(ui.detail(), "quest-detail-close", AbstractButton.class).doClick();
            groupBy.setSelectedItem("None");
            assertEquals("None: the card names its type again", "Event", painted(ui, "all", "Festival exchange").chip());
            groupBy.setSelectedItem("Chest tier");
            assertEquals("Event", painted(ui, "mighty", "Festival exchange").chip());
        });
    }

    /** What the group's own renderer paints for the card {@code name} (the painted text is not in the component tree). */
    private static QuestCardRenderer.Lines painted(QuestGUI ui, String group, String name) {
        TileList<QuestCardModel> list = ui.board().list(group);
        assertNotNull("Missing group " + group, list);
        Component cell = list.getCellRenderer().getListCellRendererComponent(list, card(ui, group, name), 0, false, false);
        return ((QuestCardRenderer) cell).shown();
    }

    /**
     * The Cards view says "nothing to show" once: while the Board shows its empty state (nothing captured, or no match), the summary
     * and the empty state say it and the footer's count line is gone. The Table view keeps its footer as it was.
     */
    @Test public void theCardsViewDropsTheFooterCountWhileTheBoardShowsItsEmptyState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTextArea count = named(ui, "quest-count", JTextArea.class);
            FilterBar bar = named(ui, "quests-filter-bar", FilterBar.class);
            assertTrue(allText(ui.board()).contains("Enter the Daily Quest Room during capture"));
            assertFalse("Nothing captured: the footer does not repeat the summary and the empty state", shown(count, ui));
            bar.overflow().item("Table view").doClick();
            assertTrue("The Table view keeps its footer", shown(count, ui));
            assertEquals("No quests captured", count.getText());
            bar.overflow().item("Cards view").doClick();
            assertFalse(shown(count, ui));
            ui.update(quests());
            assertTrue("Cards shown: the count line is back", shown(count, ui));
            assertEquals("6 shown • Requirements shown; owned items not checked.", count.getText());
            named(ui, "quest-search", JTextField.class).setText("no such quest");
            assertTrue(allText(ui.board()).contains("No matching quests"));
            assertFalse("No match: the empty state says it", shown(count, ui));
            bar.overflow().item("Table view").doClick();
            assertTrue(shown(count, ui));
            assertEquals("0 shown • Requirements shown; owned items not checked.", count.getText());
        });
    }

    /**
     * A view switch keeps keyboard focus on the page: focus inside the view being hidden (the card that Close returned it to, or the
     * table) or on the ⋯ menu that switched it moves into the view being shown (the table; the selected card's list); focus elsewhere
     * (the search field) stays where it was.
     */
    @Test public void aViewSwitchMovesFocusFromTheHiddenViewIntoTheShownViewAndNeverStealsIt() throws Exception {
        JFrame[] window = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> window[0] = new JFrame("Quest Board focus - synthetic validation"));
        JFrame frame = window[0];
        QuestGUI[] ui = new QuestGUI[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                ui[0] = panel();
                ui[0].update(quests());
                frame.setContentPane(ui[0]);
                frame.setSize(1100, 800);
                frame.setVisible(true);
                frame.toFront();
            });
            await("the window's focus", frame::isFocused);
            SwingUtilities.invokeAndWait(() -> open(ui[0], "mighty", "Festival exchange"));
            awaitFocus(ui[0].detail().closeButton());
            SwingUtilities.invokeAndWait(() -> ui[0].detail().closeButton().doClick());
            TileList<QuestCardModel> mighty = ui[0].board().list("mighty");
            awaitFocus(mighty);
            JTable table = named(ui[0], "quest-table", JTable.class);
            FilterBar bar = named(ui[0], "quests-filter-bar", FilterBar.class);
            SwingUtilities.invokeAndWait(() -> bar.overflow().item("Table view").doClick());
            awaitFocus(table);

            SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST));
            SwingUtilities.invokeAndWait(() -> named(ui[0], "quest-view-0", JToggleButton.class).doClick()); // the Analyst toggle
            awaitFocus(mighty);
            SwingUtilities.invokeAndWait(() -> assertEquals("The selected card's list", "Festival exchange", mighty.getSelectedValue().name()));

            JTextField search = named(ui[0], "quest-search", JTextField.class);
            SwingUtilities.invokeAndWait(search::requestFocusInWindow);
            awaitFocus(search);
            SwingUtilities.invokeAndWait(() -> named(ui[0], "quest-view-1", JToggleButton.class).doClick());
            settle();
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(ui[0].cardsShown());
                assertSame("Focus outside the hidden view is never moved", search, focusOwner());
            });

            SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE));
            SwingUtilities.invokeAndWait(() -> bar.overflow().requestFocusInWindow());
            awaitFocus(bar.overflow());
            SwingUtilities.invokeAndWait(() -> bar.overflow().item("Cards view").doClick());
            awaitFocus(mighty); // from the ⋯ menu that switched it
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    static Component focusOwner() { return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); }

    /** Waits up to 5 s for {@code target} to own the keyboard focus; the failure names the actual owner. */
    static void awaitFocus(Component target) throws Exception {
        Component[] owner = new Component[1];
        long end = System.nanoTime() + 5_000_000_000L;
        while (true) {
            SwingUtilities.invokeAndWait(() -> owner[0] = focusOwner());
            if (owner[0] == target) return;
            if (System.nanoTime() > end) fail("Focus on " + describe(owner[0]) + ", expected " + describe(target));
            Thread.sleep(20);
        }
    }

    static void await(String what, java.util.function.BooleanSupplier condition) throws Exception {
        boolean[] met = new boolean[1];
        long end = System.nanoTime() + 5_000_000_000L;
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > end) fail(what + " did not settle within 5 s");
            Thread.sleep(20);
        }
    }

    /** Lets posted focus changes land. */
    static void settle() throws Exception {
        for (int i = 0; i < 5; i++) { SwingUtilities.invokeAndWait(() -> { }); Thread.sleep(40); }
    }

    private static String describe(Component c) {
        return c == null ? "nothing" : c.getClass().getSimpleName() + (c.getName() == null ? "" : " '" + c.getName() + "'")
            + (c instanceof AbstractButton ? " \"" + ((AbstractButton) c).getText() + "\"" : "");
    }

    private static void open(QuestGUI ui, String group, String name) {
        TileList<QuestCardModel> list = ui.board().list(group);
        assertNotNull("Missing group " + group, list);
        for (int i = 0; i < list.getModel().getSize(); i++) if (list.getModel().getElementAt(i).name().equals(name)) list.setSelectedIndex(i);
        assertEquals(name, list.getSelectedValue().name());
        list.getActionMap().get(TileList.OPEN).actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, TileList.OPEN));
    }

    private static QuestCardModel card(QuestGUI ui, String group, String name) {
        for (QuestCardModel card : ui.board().list(group).items()) if (card.name().equals(name)) return card;
        throw new AssertionError("No card " + name);
    }

    private static List<String> keys(QuestGUI ui) {
        List<String> keys = new ArrayList<>();
        for (QuestBoardModel.Group group : ui.board().groups()) {
            keys.add(group.key());
            assertNotNull("Each group has its own list", ui.board().list(group.key()));
            assertEquals(group.cards(), ui.board().list(group.key()).items());
        }
        return keys;
    }

    private static List<String> names(List<QuestCardModel> cards) {
        List<String> names = new ArrayList<>();
        for (QuestCardModel card : cards) names.add(card.name());
        return names;
    }

    /** The visible label texts of a section header: its title and its count. */
    private static List<String> labels(SectionHeader header) {
        assertNotNull(header);
        List<String> texts = new ArrayList<>();
        collect(header, texts);
        return texts;
    }

    private static void collect(Container root, List<String> texts) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && child.isVisible()) texts.add(((JLabel) child).getText());
            if (child instanceof Container) collect((Container) child, texts);
        }
    }

    /** Whether the component and every parent up to {@code root} are visible. */
    private static boolean shown(Component component, Container root) {
        for (Component c = component; c != null; c = c.getParent()) {
            if (!c.isVisible()) return false;
            if (c == root) return true;
        }
        return false;
    }

    /** Every JTextArea, JLabel and button text under {@code root}, shown or not. */
    private static String allText(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea) text.append(((JTextArea) child).getText()).append('\n');
            if (child instanceof JLabel) text.append(((JLabel) child).getText()).append('\n');
            if (child instanceof AbstractButton) text.append(((AbstractButton) child).getText()).append('\n');
            if (child instanceof Container) text.append(allText((Container) child));
        }
        return text.toString();
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        T found = find(root, name, type);
        assertNotNull("Missing " + name, found);
        return found;
    }

    private static <T> T find(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
