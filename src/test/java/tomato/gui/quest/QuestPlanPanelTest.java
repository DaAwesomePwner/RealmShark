package tomato.gui.quest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import static org.junit.Assert.*;
import tomato.gui.kit.*;
import tomato.planning.PlanData;
import tomato.planning.PlanningStore;
import util.PropertiesManager;
import java.awt.*;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class QuestPlanPanelTest {
    /** The Planner opens on cards; the plan table is the Table view, so the table tests pin it (restoring the saved choice). */
    @Rule public final TableView tableView = new TableView();

    /** Pins the Planner's Table view ({@link QuestPlanPanel#VIEW_KEY}) for tests of the plan table and restores the saved choice. */
    static final class TableView extends ExternalResource {
        private String saved;
        @Override protected void before() { saved = PropertiesManager.getProperty(QuestPlanPanel.VIEW_KEY); PropertiesManager.setProperties(QuestPlanPanel.VIEW_KEY, "table"); }
        @Override protected void after() { PropertiesManager.setProperties(QuestPlanPanel.VIEW_KEY, saved == null ? "" : saved); }
    }

    @Test public void failedSaveRetainsDraftAndRetryPublishesIt() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        CountDownLatch attempted = new CountDownLatch(1);
        Path path = Files.createTempDirectory("quest-plan-failure").resolve("plans.json");
        try (PlanningStore store = new PlanningStore(path, (p, json) -> {
            attempted.countDown(); if (fail.get()) throw new java.io.IOException("synthetic failure");
            Files.write(p, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        })) {
            ready(store); QuestPlanPanel[] panels = new QuestPlanPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = panels[0] = new QuestPlanPanel(store, id -> "Item"); p.knownAccounts(Arrays.asList("a"));
                find(p, "quest-plan-account", JComboBox.class).setSelectedItem("a");
                QuestGUI.Quest q = QuestPlanningTest.quest("quest", 1); p.observations("a", true, Arrays.asList(q), 100, 1); p.importQuest(q, null);
                find(p, "quest-plan-save", JButton.class).doClick();
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            long end = System.currentTimeMillis() + 5000;
            java.util.concurrent.atomic.AtomicBoolean retryEnabled = new java.util.concurrent.atomic.AtomicBoolean();
            while (!retryEnabled.get() && System.currentTimeMillis() < end) {
                SwingUtilities.invokeAndWait(() -> retryEnabled.set(find(panels[0], "quest-plan-save", JButton.class).isEnabled())); Thread.sleep(10);
            }
            assertTrue(retryEnabled.get()); assertTrue(store.snapshot("a").plan().quests.isEmpty()); fail.set(false);
            SwingUtilities.invokeAndWait(() -> { assertEquals(1, find(panels[0], "quest-plan-table", JTable.class).getRowCount()); find(panels[0], "quest-plan-save", JButton.class).doClick(); });
            end = System.currentTimeMillis() + 5000;
            while (store.snapshot("a").revision == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
            assertTrue(store.snapshot("a").plan().quests.containsKey("quest"));
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(path.getParent()); }
    }

    @Test public void scopeChangeBeforeMailboxDeliveryRejectsImport() throws Exception {
        try (PlanningStore store = PlanningStore.memory()) {
            ready(store);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = new QuestPlanPanel(store, id -> "Item"); p.knownAccounts(Arrays.asList("a"));
                find(p, "quest-plan-account", JComboBox.class).setSelectedItem("a");
                QuestGUI.Quest q = QuestPlanningTest.quest("quest", 1); p.observations("a", true, Arrays.asList(q), 100, 1);
                p.verification(() -> false); p.importQuest(q, null);
                assertEquals(0, find(p, "quest-plan-table", JTable.class).getRowCount());
            });
        }
    }
    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container) for (Component c : ((Container) root).getComponents()) { T found = find(c, name, type); if (found != null) return found; }
        return null;
    }
    private static void ready(PlanningStore store) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!store.snapshot("a").ready && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(store.snapshot("a").ready);
    }
    @Test public void accountsAreExplicitAndImportsRequireVerifiedMatchingScope() throws Exception {
        try (PlanningStore store = PlanningStore.memory()) {
            ready(store);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = new QuestPlanPanel(store, id -> "Item " + id);
                p.knownAccounts(Arrays.asList("a", "b"));
                QuestGUI.Quest q = QuestPlanningTest.quest("quest", 1, 1);
                p.observations("a", true, Arrays.asList(q), 123, 1);
                JComboBox<?> account = find(p, "quest-plan-account", JComboBox.class);
                assertEquals(0, account.getSelectedIndex());
                p.importQuest(q, null); assertEquals(0, find(p, "quest-plan-table", JTable.class).getRowCount());
                account.setSelectedItem("b"); p.importQuest(q, null); assertEquals(0, find(p, "quest-plan-table", JTable.class).getRowCount());
                account.setSelectedItem("a"); p.importQuest(q, null); assertEquals(1, find(p, "quest-plan-table", JTable.class).getRowCount());
                assertTrue(find(p, "quest-plan-save", JButton.class).isEnabled());
                account.setSelectedItem("b"); assertEquals(0, find(p, "quest-plan-table", JTable.class).getRowCount());
                account.setSelectedItem("a"); assertEquals(1, find(p, "quest-plan-table", JTable.class).getRowCount());
                assertEquals(0, store.snapshot("a").plan().quests.size());
            });
        }
    }
    @Test public void delayedSaveDoesNotReplaceDifferentAccountDraft() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        Path path = Files.createTempDirectory("quest-plan-test").resolve("plans.json");
        try (PlanningStore store = new PlanningStore(path, (p, json) -> {
            writing.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("timeout"); }
            catch (InterruptedException e) { throw new java.io.IOException(e); }
            Files.write(p, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        })) {
            ready(store); QuestPlanPanel[] panel = new QuestPlanPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = panel[0] = new QuestPlanPanel(store, id -> "Item"); p.knownAccounts(Arrays.asList("a", "b"));
                JComboBox<?> account = find(p, "quest-plan-account", JComboBox.class); account.setSelectedItem("a");
                QuestGUI.Quest q = QuestPlanningTest.quest("quest-a", 1); p.observations("a", true, Arrays.asList(q), 100, 1); p.importQuest(q, null);
                find(p, "quest-plan-save", JButton.class).doClick(); account.setSelectedItem("b");
                QuestGUI.Quest b = QuestPlanningTest.quest("quest-b", 2); p.observations("b", true, Arrays.asList(b), 200, 2); p.importQuest(b, null);
            });
            assertTrue(writing.await(5, TimeUnit.SECONDS)); release.countDown();
            long end = System.currentTimeMillis() + 5000;
            while (store.snapshot("a").revision == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
            SwingUtilities.invokeAndWait(() -> {
                JTable table = find(panel[0], "quest-plan-table", JTable.class);
                assertEquals("quest-b", table.getValueAt(0, 1)); assertTrue(find(panel[0], "quest-plan-save", JButton.class).isEnabled());
                assertTrue(store.snapshot("a").plan().quests.containsKey("quest-a")); assertTrue(store.snapshot("b").plan().quests.isEmpty());
            });
        } finally { release.countDown(); Files.deleteIfExists(path); Files.deleteIfExists(path.getParent()); }
    }

    /** A memory store whose account "acct" holds PlanCardModelTest.plan(): plans a and b, held item 1 = 4, item 2 unconfirmed. */
    private static PlanningStore planned() throws Exception {
        PlanningStore store = PlanningStore.memory(); ready(store);
        assertTrue(store.update("acct", 0, PlanCardModelTest.plan()).get(5, TimeUnit.SECONDS).saved);
        return store;
    }
    /** The Planner in its own display mode and view preference (never the application's), on account "acct". EDT. */
    private static QuestPlanPanel cardsPanel(PlanningStore store, Map<String, String> prefs, DisplayModeModel mode) {
        QuestPlanPanel p = new QuestPlanPanel(store, PlanCardModelTest.NAMES, mode, prefs::get, prefs::put);
        p.knownAccounts(Arrays.asList("acct")); find(p, "quest-plan-account", JComboBox.class).setSelectedItem("acct");
        return p;
    }
    private static List<String> keys(TileList<?> cards) {
        List<String> keys = new ArrayList<>(); for (int i = 0; i < cards.getModel().getSize(); i++) keys.add(((PlanCardModel) cards.getModel().getElementAt(i)).entryId()); return keys;
    }
    private static PlanCardModel card(TileList<?> cards, int index) { return (PlanCardModel) cards.getModel().getElementAt(index); }

    @Test public void cardsViewShowsTheAllPlansSummaryAndOneCardPerPlan() throws Exception {
        try (PlanningStore store = planned()) {
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = new QuestPlanPanel(store, PlanCardModelTest.NAMES, mode, prefs::get, prefs::put);
                TileList<?> cards = find(p, "quest-plan-cards", TileList.class);
                assertEquals("No implicit account: no cards", 0, cards.getModel().getSize());
                assertFalse(find(p, "quest-plan-summary", Card.class).isVisible());
                assertTrue(find(p, "quest-plan-no-account", EmptyState.class).isVisible());
                p.knownAccounts(Arrays.asList("acct")); find(p, "quest-plan-account", JComboBox.class).setSelectedItem("acct");
                assertTrue("The Planner opens on cards", find(p, "quest-plan-cards-view", JComponent.class).isVisible());
                assertFalse(find(p, "quest-plan-table-view", JComponent.class).isVisible());
                assertFalse(find(p, "quest-plan-no-account", EmptyState.class).isVisible());
                assertFalse(find(p, "quest-plan-empty", EmptyState.class).isVisible());
                assertEquals("One card per plan", List.of("a", "b"), keys(cards));

                Card summary = find(p, "quest-plan-summary", Card.class);
                assertTrue(summary.isVisible());
                assertEquals("All plans", summary.header().title());
                QuestPlanning.Totals all = QuestPlanning.totals(PlanCardModelTest.plan(), PlanCardModelTest.plan().quests.keySet());
                assertEquals(all.readiness(), find(p, "quest-plan-summary-readiness", JTextArea.class).getText());
                assertEquals("Mark of the Forgotten King (#1): need 5 · reserved 3 · covered 1 · missing 1", find(p, "quest-plan-summary-1", JTextArea.class).getText());
                assertEquals("Reserved 3, covered 1, missing 1 of 5", find(p, "quest-plan-summary-1-bar", SegmentBar.class).getAccessibleContext().getAccessibleDescription());
                assertEquals("Festival Token (#2): need 1 · Stock unconfirmed", find(p, "quest-plan-summary-2", JTextArea.class).getText());
                assertNull("Unknown stock draws no bar", find(p, "quest-plan-summary-2-bar", SegmentBar.class));

                Collapsible stock = find(p, "quest-plan-stock", Collapsible.class);
                for (String name : new String[] {"quest-plan-item-id", "quest-plan-quantity", "quest-plan-note", "quest-plan-held",
                        "quest-plan-reserve", "quest-plan-release", "quest-plan-release-all", "quest-plan-held-values"})
                    assertTrue(name + " is in the Manual stock drawer", SwingUtilities.isDescendingFrom(find(p, name, JComponent.class), stock));
                assertFalse("The release checkbox also governs edits outside the drawer", SwingUtilities.isDescendingFrom(find(p, "quest-plan-release-affected", JComponent.class), stock));
                assertEquals("collapsible-quest-plan-stock", stock.toggle().getName());
                String held = find(p, "quest-plan-held-values", JTextArea.class).getText();
                assertTrue(held, held.startsWith("Held stock (account-wide, manual):\nMark of the Forgotten King (#1): 4 (manual) · unallocated 1 · confirmed "));
                assertTrue(held, held.endsWith(" · counted"));

                p.knownAccounts(Arrays.asList("empty")); find(p, "quest-plan-account", JComboBox.class).setSelectedItem("empty");
                assertEquals(0, cards.getModel().getSize());
                assertTrue("An account without plans says how to add one", find(p, "quest-plan-empty", EmptyState.class).isVisible());
                assertFalse(summary.isVisible());
            });
        }
    }

    @Test public void theViewSwitchIsAMenuItemInSimpleAndAToggleInAnalystAndIsRemembered() throws Exception {
        try (PlanningStore store = planned()) {
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = cardsPanel(store, prefs, mode);
                OverflowMenu overflow = find(p, "quest-plan-overflow", OverflowMenu.class);
                SegmentedControl view = find(p, "quest-plan-view", SegmentedControl.class);
                assertTrue("Simple: the other view is in the ⋯ menu", overflow.isVisible()); assertFalse(view.isVisible());
                JMenuItem item = overflow.item("Table view"); assertNotNull(item);
                item.doClick();
                assertTrue(find(p, "quest-plan-table-view", JComponent.class).isVisible());
                assertFalse(find(p, "quest-plan-cards-view", JComponent.class).isVisible());
                assertEquals("table", prefs.get(QuestPlanPanel.VIEW_KEY));
                assertEquals("Cards view", item.getText());
                mode.set(DisplayModeModel.Mode.ANALYST);
                assertTrue("Analyst: a Cards/Table toggle", view.isVisible()); assertFalse(overflow.isVisible());
                assertEquals(1, view.selected());
                find(p, "quest-plan-view-0", JToggleButton.class).doClick();
                assertTrue(find(p, "quest-plan-cards-view", JComponent.class).isVisible());
                assertEquals("cards", prefs.get(QuestPlanPanel.VIEW_KEY));
                Map<String, String> table = new HashMap<>(prefs); table.put(QuestPlanPanel.VIEW_KEY, "table");
                QuestPlanPanel reopened = cardsPanel(store, table, mode);
                assertTrue("A saved Table choice reopens the table", find(reopened, "quest-plan-table-view", JComponent.class).isVisible());
            });
        }
    }

    @Test public void selectingACardSelectsThatPlanForTheEditors() throws Exception {
        try (PlanningStore store = planned()) {
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = cardsPanel(store, prefs, mode);
                TileList<?> cards = find(p, "quest-plan-cards", TileList.class); JTable table = find(p, "quest-plan-table", JTable.class);
                assertEquals("The first plan starts selected, as in the table", 0, cards.getSelectedIndex());
                cards.setSelectedIndex(1);
                assertEquals(1, table.getSelectedRowCount()); assertEquals("b", table.getValueAt(table.getSelectedRow(), 1));
                find(p, "quest-plan-repeat-count", JSpinner.class).setValue(2L);
                find(p, "quest-plan-repeats", JButton.class).doClick();
                assertEquals("Set repeats edits the selected card's plan", "Repeats: 2", card(cards, 1).repeats());
                assertEquals(6, card(cards, 1).rows().get(0).need());
                assertEquals("Repeats: 1", card(cards, 0).repeats());
                assertEquals("The edited plan stays selected", 1, cards.getSelectedIndex());
                OverflowMenu overflow = find(p, "quest-plan-overflow", OverflowMenu.class);
                overflow.item("Table view").doClick();
                table.setRowSelectionInterval(0, 1); assertEquals(2, table.getSelectedRowCount());
                overflow.item("Cards view").doClick();
                assertEquals("Cards edit one plan at a time: the table selection follows the selected card", 1, table.getSelectedRowCount());
                assertEquals(0, cards.getSelectedIndex());
                find(p, "quest-plan-remove", JButton.class).doClick();
                assertEquals(List.of("b"), keys(cards));
                assertEquals("Nothing saved until Save plan", 2, store.snapshot("acct").plan().quests.size());
            });
        }
    }

    @Test public void cardsRebuildOnlyWhenThePlansOrTheObservedQuestsChange() throws Exception {
        try (PlanningStore store = planned()) {
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            QuestPlanPanel[] panel = new QuestPlanPanel[1]; JTextArea[] row = new JTextArea[1]; int[] events = {0};
            javax.swing.event.ListDataListener counting = new javax.swing.event.ListDataListener() {
                public void intervalAdded(javax.swing.event.ListDataEvent e) { events[0]++; }
                public void intervalRemoved(javax.swing.event.ListDataEvent e) { events[0]++; }
                public void contentsChanged(javax.swing.event.ListDataEvent e) { events[0]++; }
            };
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = panel[0] = cardsPanel(store, prefs, mode);
                find(p, "quest-plan-cards", TileList.class).getModel().addListDataListener(counting);
                row[0] = find(p, "quest-plan-summary-1", JTextArea.class);
                p.knownAccounts(List.of()); // a poll re-reads the store, whose revision is unchanged
                find(p, "quest-plan-account", JComboBox.class).setSelectedItem("acct"); // a refresh with the same inputs
                assertEquals("Neither rebuilds the cards", 0, events[0]);
                assertSame("…nor the All plans summary", row[0], find(p, "quest-plan-summary-1", JTextArea.class));
            });
            PlanData.AccountPlan recounted = store.snapshot("acct").plan(); QuestPlanning.held(recounted, 1, 6, "recounted", false, 200);
            assertTrue(store.update("acct", store.snapshot("acct").revision, recounted).get(5, TimeUnit.SECONDS).saved);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = panel[0];
                p.knownAccounts(List.of()); // this poll sees the new store revision
                assertTrue("A new revision rebuilds the cards", events[0] > 0);
                JTextArea changed = find(p, "quest-plan-summary-1", JTextArea.class);
                assertNotSame(row[0], changed);
                assertEquals("Mark of the Forgotten King (#1): need 5 · reserved 3 · covered 2 · missing 0", changed.getText());
                TileList<?> cards = find(p, "quest-plan-cards", TileList.class);
                assertEquals("Saved requirements; verify server", card(cards, 1).status());
                p.observations("acct", true, Arrays.asList(QuestPlanningTest.quest("a", 1, 1)), 300, 2); // b is gone from the current list
                assertEquals("Observed quests rebuild the cards", "Changed / removed; reconfirm", card(cards, 1).status());
                assertEquals("Saved requirements; verify server", card(cards, 0).status());
            });
        }
    }

    /**
     * A 64-character account key never pushes the account list past its row: at a narrow width the list and its drop-down arrow stay
     * inside the row (the renderer cuts the key; the tooltip keeps it whole), and the selection and the list's items are unchanged.
     */
    @Test public void aLongAccountKeyKeepsTheAccountListAndItsArrowInsideANarrowRow() throws Exception {
        String key = tomato.backend.data.CharacterJournal.accountKey("quest-plan-narrow-row");
        assertEquals(64, key.length());
        try (PlanningStore store = PlanningStore.memory()) {
            ready(store);
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            JFrame[] frame = new JFrame[1]; QuestPlanPanel[] panel = new QuestPlanPanel[1]; List<Object> items = new ArrayList<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    QuestPlanPanel p = panel[0] = new QuestPlanPanel(store, PlanCardModelTest.NAMES, mode, prefs::get, prefs::put);
                    p.knownAccounts(Arrays.asList("other", key));
                    JComboBox<?> account = find(p, "quest-plan-account", JComboBox.class);
                    account.setSelectedItem(key);
                    for (int i = 0; i < account.getItemCount(); i++) items.add(account.getItemAt(i));
                    frame[0] = new JFrame("Planner account list - synthetic validation");
                    frame[0].setContentPane(p); frame[0].setSize(420, 600); frame[0].setVisible(true);
                });
                QuestBoardTest.settle();
                SwingUtilities.invokeAndWait(() -> frame[0].validate());
                QuestBoardTest.settle();
                SwingUtilities.invokeAndWait(() -> {
                    JComboBox<?> account = find(panel[0], "quest-plan-account", JComboBox.class);
                    Container row = account.getParent();
                    int whole = account.getFontMetrics(account.getFont()).stringWidth(key);
                    assertTrue("The whole key is wider than the row: " + whole + " vs " + row.getWidth(), whole > row.getWidth());
                    assertTrue("The list's right edge is inside its row: " + account.getBounds() + " in " + row.getWidth(),
                        account.getX() + account.getWidth() <= row.getWidth() - row.getInsets().right);
                    assertEquals("…and all of it shows", account.getWidth(), account.getVisibleRect().width);
                    Component arrow = null;
                    for (Component child : account.getComponents()) if (child instanceof AbstractButton) arrow = child;
                    assertNotNull("The drop-down arrow", arrow);
                    assertTrue(arrow.getWidth() > 0);
                    assertEquals("The drop-down arrow shows whole", arrow.getWidth(), ((JComponent) arrow).getVisibleRect().width);
                    assertEquals("The selection is unchanged", key, account.getSelectedItem());
                    List<Object> now = new ArrayList<>(); for (int i = 0; i < account.getItemCount(); i++) now.add(account.getItemAt(i));
                    assertEquals("The list's items are unchanged", items, now);
                    assertEquals("The tooltip keeps the whole key", key, account.getToolTipText());
                    assertEquals(key, account.getAccessibleContext().getAccessibleDescription());
                    assertEquals("Account for manual quest plans", account.getAccessibleContext().getAccessibleName());
                });
            } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
        }
    }

    /**
     * The Planner's view switch keeps keyboard focus on the page: focus inside the view being hidden (the plan cards, the plan table)
     * or on the ⋯ menu that switched it moves into the view being shown; focus elsewhere (the account list) stays where it was.
     */
    @Test public void thePlannerViewSwitchMovesFocusFromTheHiddenViewIntoTheShownViewAndNeverStealsIt() throws Exception {
        try (PlanningStore store = planned()) {
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            JFrame[] frame = new JFrame[1]; QuestPlanPanel[] panel = new QuestPlanPanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    panel[0] = cardsPanel(store, prefs, mode);
                    frame[0] = new JFrame("Planner focus - synthetic validation");
                    frame[0].setContentPane(panel[0]); frame[0].setSize(1000, 800); frame[0].setVisible(true); frame[0].toFront();
                });
                QuestBoardTest.await("the window's focus", () -> frame[0].isFocused());
                QuestPlanPanel p = panel[0];
                TileList<?> cards = find(p, "quest-plan-cards", TileList.class); JTable table = find(p, "quest-plan-table", JTable.class);
                OverflowMenu overflow = find(p, "quest-plan-overflow", OverflowMenu.class);
                JComboBox<?> account = find(p, "quest-plan-account", JComboBox.class);
                SwingUtilities.invokeAndWait(cards::requestFocusInWindow);
                QuestBoardTest.awaitFocus(cards);
                SwingUtilities.invokeAndWait(() -> overflow.item("Table view").doClick());
                QuestBoardTest.awaitFocus(table);
                SwingUtilities.invokeAndWait(() -> { mode.set(DisplayModeModel.Mode.ANALYST); find(p, "quest-plan-view-0", JToggleButton.class).doClick(); });
                QuestBoardTest.awaitFocus(cards);

                SwingUtilities.invokeAndWait(account::requestFocusInWindow);
                QuestBoardTest.awaitFocus(account);
                SwingUtilities.invokeAndWait(() -> find(p, "quest-plan-view-1", JToggleButton.class).doClick());
                QuestBoardTest.settle();
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(find(p, "quest-plan-table-view", JComponent.class).isVisible());
                    assertSame("Focus outside the hidden view is never moved", account, QuestBoardTest.focusOwner());
                });

                SwingUtilities.invokeAndWait(() -> { mode.set(DisplayModeModel.Mode.SIMPLE); overflow.requestFocusInWindow(); });
                QuestBoardTest.awaitFocus(overflow);
                SwingUtilities.invokeAndWait(() -> overflow.item("Cards view").doClick());
                QuestBoardTest.awaitFocus(cards); // from the ⋯ menu that switched it
            } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
        }
    }

    /**
     * An item without an asset name reads "Unknown item #9999" from the Board's lookup: the Planner shows its id once (the All plans
     * row, the held values, a card's details and the Table view's detail and totals); named items keep "Name (#id)".
     */
    @Test public void anUnnamedItemShowsItsIdOnceWhereverThePlannerNamesIt() throws Exception {
        java.util.function.IntFunction<String> board = id -> id == 1 ? "Mark of the Forgotten King" : "Unknown item #" + id;
        PlanData.AccountPlan plan = new PlanData.AccountPlan();
        plan.quests.put("odd", QuestPlanningTest.entry("odd", 1, 9999));
        QuestPlanning.held(plan, 9999, 2, "", false, 100); QuestPlanning.held(plan, 1, 1, "", false, 100);
        PlanData.validate("acct", plan);
        try (PlanningStore store = PlanningStore.memory()) {
            ready(store);
            assertTrue(store.update("acct", 0, plan).get(5, TimeUnit.SECONDS).saved);
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = new QuestPlanPanel(store, board, mode, prefs::get, prefs::put);
                p.knownAccounts(Arrays.asList("acct")); find(p, "quest-plan-account", JComboBox.class).setSelectedItem("acct");
                String row = find(p, "quest-plan-summary-9999", JTextArea.class).getText();
                assertEquals("Unknown item #9999: need 1 · reserved 0 · covered 1 · missing 0", row);
                assertEquals("Named items keep their id", "Mark of the Forgotten King (#1): need 1 · reserved 0 · covered 1 · missing 0",
                    find(p, "quest-plan-summary-1", JTextArea.class).getText());
                String held = find(p, "quest-plan-held-values", JTextArea.class).getText();
                assertTrue(held, held.contains("\nUnknown item #9999: 2 (manual) · unallocated 2 · confirmed "));
                assertTrue(held, held.contains("\nMark of the Forgotten King (#1): 1 (manual) · unallocated 1 · confirmed "));
                String details = PlanCardRenderer.details(card(find(p, "quest-plan-cards", TileList.class), 0));
                assertTrue(details, details.contains(" · Unknown item #9999: need 1 · reserved 0 · covered 1 · missing 0"));
                assertTrue(details, details.contains(" · Mark of the Forgotten King (#1): need 1 · "));
                find(p, "quest-plan-overflow", OverflowMenu.class).item("Table view").doClick();
                String detail = find(p, "quest-plan-detail", JTextArea.class).getText(), totals = find(p, "quest-plan-totals", JTextArea.class).getText();
                assertTrue(detail, detail.contains("\nRewards: ALL 1 × Unknown item #20, 1 × Unknown item #21\n"));
                assertTrue(totals, totals.contains("\nUnknown item #9999: need 1 · reserved here 0 · available to selection 2 · deficit 0"));
                assertTrue(totals, totals.contains("\nMark of the Forgotten King (#1): need 1 · "));
                assertTrue(totals, totals.contains("\nUnknown item #9999: 2 · unallocated 2 · confirmed "));
                for (String text : new String[] {row, held, details, detail, totals})
                    assertFalse("The id once: " + text, text.contains("(#9999)") || text.contains("(#20)") || text.contains("(#21)"));
            });
        }
    }

    /** Visible up to {@code root}: no ancestor hides it (a collapsed Collapsible hides its content). */
    private static boolean shown(Component component, Component root) {
        for (Component c = component; c != null && c != root; c = c.getParent()) if (!c.isVisible()) return false;
        return true;
    }

    @Test public void theReleaseCheckboxStaysVisibleWithTheDrawerCollapsedAndGovernsSetRepeats() throws Exception {
        String key = Collapsible.PREFIX + "quest-plan-stock", saved = PropertiesManager.getProperty(key);
        PropertiesManager.setProperties(key, ""); // the Manual stock drawer at its default: collapsed
        try (PlanningStore store = planned()) {
            Map<String, String> prefs = new HashMap<>(); DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
            SwingUtilities.invokeAndWait(() -> {
                QuestPlanPanel p = cardsPanel(store, prefs, mode);
                Collapsible stock = find(p, "quest-plan-stock", Collapsible.class);
                assertFalse(stock.expanded());
                assertFalse("Stock controls stay in the collapsed drawer", shown(find(p, "quest-plan-held", JButton.class), p));
                JCheckBox release = find(p, "quest-plan-release-affected", JCheckBox.class);
                assertEquals("Release affected reservations with this edit", release.getText());
                assertEquals("Release affected reservations with this edit", release.getAccessibleContext().getAccessibleName());
                assertFalse(SwingUtilities.isDescendingFrom(release, stock));
                assertTrue("The checkbox is shown with the drawer collapsed (Cards view)", shown(release, p));
                assertTrue(release.isEnabled());
                OverflowMenu overflow = find(p, "quest-plan-overflow", OverflowMenu.class);
                overflow.item("Table view").doClick();
                assertTrue("…and in the Table view", shown(release, p));
                overflow.item("Cards view").doClick();

                // It governs Set repeats, outside the drawer: b's reservation (1 × item 1) is released with the new repeat count.
                TileList<?> cards = find(p, "quest-plan-cards", TileList.class);
                cards.setSelectedIndex(1);
                release.doClick(); assertTrue(release.isSelected());
                find(p, "quest-plan-repeat-count", JSpinner.class).setValue(2L);
                find(p, "quest-plan-repeats", JButton.class).doClick();
                assertEquals("Repeats: 2", card(cards, 1).repeats());
                assertEquals("b's reservation was released", 0, card(cards, 1).rows().get(0).reserved());
                assertEquals("Mark of the Forgotten King (#1): need 8 · reserved 2 · covered 2 · missing 4", find(p, "quest-plan-summary-1", JTextArea.class).getText());
                assertFalse("Checking it never opened the drawer", stock.expanded());
            });
        } finally { PropertiesManager.setProperties(key, saved == null ? "" : saved); }
    }
}
