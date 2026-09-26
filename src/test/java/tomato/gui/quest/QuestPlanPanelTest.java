package tomato.gui.quest;

import org.junit.Test;
import static org.junit.Assert.*;
import tomato.planning.PlanningStore;
import java.awt.*;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class QuestPlanPanelTest {
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
}
