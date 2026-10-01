package tomato.gui.dps;

import org.junit.Before;
import org.junit.Test;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class MeterAsyncBuildTest {
    @Before public void resetFilter() throws Exception { edt(Filter::disable); }

    @Test public void liveBuildUsesNamedDaemonAndAppliesOnlyAfterReturningToEdt() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), applied = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Boolean> builtOnEdt = new AtomicReference<>();
        MeterDpsGUI[] view = new MeterDpsGUI[1];
        try {
            edt(() -> {
                view[0] = new MeterDpsGUI(null, (targets, player, whole) -> {
                    worker.set(Thread.currentThread()); builtOnEdt.set(SwingUtilities.isEventDispatchThread()); started.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Build was not released"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    return new CombatMeterData(targets, player, whole);
                });
                view[0].onFiltersChanged(() -> { assertTrue(SwingUtilities.isEventDispatchThread()); applied.countDown(); });
                render(view[0], 100, true);
                assertEquals(0, view[0].table().getRowCount());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, builtOnEdt.get());
            assertTrue(worker.get().isDaemon()); assertEquals("RealmShark meter", worker.get().getName());
            release.countDown();
            assertTrue(applied.await(5, TimeUnit.SECONDS));
            edt(() -> assertEquals(100, total(view[0])));
        } finally {
            release.countDown();
            edt(() -> { if (view[0] != null) view[0].removeNotify(); });
            if (worker.get() != null) { worker.get().join(5000); assertFalse("Disposed meter worker stopped", worker.get().isAlive()); }
        }
    }

    @Test public void slowBuildAppliesCompletedResultsAndKeepsOnlyTheLatestPendingLiveScope() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        List<Long> built = new ArrayList<>();
        edt(() -> {
            view[0] = new MeterDpsGUI(executor, (targets, player, whole) -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                CombatMeterData data = new CombatMeterData(targets, player, whole); built.add(data.total); return data;
            });
            render(view[0], 100, true);
            render(view[0], 200, true); render(view[0], 300, true); render(view[0], 400, true);
            assertEquals(1, executor.tasks.size());
        });
        executor.runNext();
        edt(() -> { assertEquals(100, total(view[0])); assertEquals(1, executor.tasks.size()); });
        executor.runNext();
        edt(() -> { assertEquals(400, total(view[0])); assertEquals(0, executor.tasks.size()); });
        assertEquals(Arrays.asList(100L, 400L), built);
    }

    @Test public void savedRebuildDropsBothRunningAndPendingLiveResults() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        edt(() -> {
            view[0] = new MeterDpsGUI(executor);
            render(view[0], 100, true); render(view[0], 200, true);
            render(view[0], 900, false);
            assertEquals(900, total(view[0]));
        });
        executor.runNext();
        edt(() -> { assertEquals(900, total(view[0])); assertEquals(0, executor.tasks.size()); });
    }

    @Test public void enemyChangesSynchronouslySupersedeLiveBuilds() throws Exception {
        for (String control : Arrays.asList("enemy", "sort")) {
            QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
            edt(() -> {
                view[0] = new MeterDpsGUI(executor);
                render(view[0], 50, false);
                render(view[0], 100, true); render(view[0], 200, true);
                switch (control) {
                    case "enemy" -> enemies(view[0]).setSelectedIndex(1);
                    case "sort" -> view[0].enemyChoice().setSelectedIndex(1);
                }
                assertEquals(control, 200, total(view[0]));
                assertEquals(1, view[0].table().getRowCount());
            });
            executor.runNext();
            edt(() -> { assertEquals(control, 200, total(view[0])); assertEquals(0, executor.tasks.size()); });
        }
    }

    @Test public void playerFiltersAndMetricDoNotRebuildAndRemainInEffectForLiveResults() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        List<Boolean> builtOnEdt = new ArrayList<>();
        edt(() -> {
            view[0] = new MeterDpsGUI(executor, (targets, player, whole) -> {
                builtOnEdt.add(SwingUtilities.isEventDispatchThread());
                return new CombatMeterData(targets, player, whole);
            });
            renderWithBob(view[0], 50, false);
            assertEquals(2, view[0].table().getRowCount());
            renderWithBob(view[0], 100, true); renderWithBob(view[0], 200, true);
            view[0].classChoice().setSelectedIndex(1);
            view[0].searchField().setText("A"); view[0].searchField().setText("Al"); view[0].searchField().setText("Ali");
            view[0].metricChoice().setSelectedIndex(2);
            assertEquals("Only the initial saved render built on the EDT", Collections.singletonList(true), builtOnEdt);
            assertEquals(75, total(view[0]));
            assertEquals(1, view[0].table().getRowCount());
            assertEquals(50L, view[0].table().getValueAt(0, 2));
            assertEquals(1, executor.tasks.size());
        });
        executor.runNext();
        edt(() -> {
            assertEquals(125, total(view[0]));
            assertEquals(1, view[0].table().getRowCount());
            assertEquals("Alice", view[0].table().getValueAt(0, 0));
            assertEquals(100L, view[0].table().getValueAt(0, 2));
            assertEquals(1, executor.tasks.size());
        });
        executor.runNext();
        edt(() -> {
            assertEquals(225, total(view[0]));
            assertEquals(1, view[0].table().getRowCount());
            assertEquals("Alice", view[0].table().getValueAt(0, 0));
            assertEquals(200L, view[0].table().getValueAt(0, 2));
            assertEquals("Ali", view[0].searchField().getText());
            assertEquals(1, view[0].classChoice().getSelectedIndex());
            assertEquals(2, view[0].metricChoice().getSelectedIndex());
            assertEquals(0, executor.tasks.size());
        });
        assertEquals(Arrays.asList(true, false, false), builtOnEdt);
    }

    @Test public void encounterKeyChangeDropsOldBuildAndClearsPendingScope() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        edt(() -> {
            view[0] = new MeterDpsGUI(executor); view[0].setContext(new Object(), null);
            render(view[0], 50, false);
            render(view[0], 100, true); render(view[0], 200, true);
            view[0].setContext(new Object(), null);
        });
        executor.runNext();
        edt(() -> {
            assertEquals("Previous encounter's build did not apply", 50, total(view[0]));
            assertEquals("Previous encounter's pending scope was discarded", 0, executor.tasks.size());
            assertFalse(view[0].loading());
            render(view[0], 300, true);
        });
        executor.runNext();
        edt(() -> { assertEquals(300, total(view[0])); assertFalse(view[0].loading()); });
    }

    @Test public void routedFocusSelectsNewestPlayerSynchronouslyAndCannotBeOverwritten() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        edt(() -> {
            view[0] = new MeterDpsGUI(executor);
            render(view[0], 100, true); render(view[0], 200, true);
            assertTrue(view[0].focusPlayer(1, "Recorded route"));
            assertEquals(Integer.valueOf(1), view[0].selectedObjectId());
            assertEquals(200, total(view[0]));
        });
        executor.runNext();
        edt(() -> {
            assertEquals(200, total(view[0])); assertEquals(Integer.valueOf(1), view[0].selectedObjectId());
            assertEquals("Recorded route", view[0].routeNoticeText()); assertEquals(0, executor.tasks.size());
        });
    }

    @Test public void asyncApplyPreservesEnemyAndPlayerSelectionAcrossDetachedSnapshots() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        Entity replacement = enemy(200);
        edt(() -> {
            view[0] = new MeterDpsGUI(executor); render(view[0], 100, false);
            enemies(view[0]).setSelectedIndex(1); view[0].table().setRowSelectionInterval(0, 0);
            view[0].renderData(null, Collections.singletonList(replacement), new ArrayList<>(), 0, true);
            assertSame(replacement, enemies(view[0]).getSelectedValue());
            assertEquals(Integer.valueOf(1), view[0].selectedObjectId());
        });
        executor.runNext();
        edt(() -> {
            assertSame(replacement, enemies(view[0]).getSelectedValue());
            assertEquals(Integer.valueOf(1), view[0].selectedObjectId()); assertEquals(200, total(view[0]));
        });
    }

    @Test public void removalDropsCompletedResultsAndReattachmentAcceptsNewBuilds() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        edt(() -> { view[0] = new MeterDpsGUI(executor); render(view[0], 100, true); render(view[0], 200, true); });
        // Finish off-EDT while the EDT is occupied, so removal wins before the apply callback can run.
        edt(() -> {
            Thread worker = new Thread(executor::runNext);
            worker.start();
            try { worker.join(5000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(worker.isAlive());
            view[0].removeNotify(); render(view[0], 900, true);
        });
        edt(() -> {
            assertEquals(900, total(view[0])); assertEquals(0, executor.tasks.size());
            view[0].addNotify(); render(view[0], 300, true);
        });
        executor.runNext();
        edt(() -> { assertEquals(300, total(view[0])); view[0].removeNotify(); });
    }

    @Test public void queuedBuildDoesNotStartAfterRemovalEvenIfReattached() throws Exception {
        QueuedExecutor executor = new QueuedExecutor(); MeterDpsGUI[] view = new MeterDpsGUI[1];
        List<Long> built = new ArrayList<>();
        edt(() -> {
            view[0] = new MeterDpsGUI(executor, (targets, player, whole) -> {
                CombatMeterData data = new CombatMeterData(targets, player, whole); built.add(data.total); return data;
            });
            render(view[0], 100, true); view[0].removeNotify(); view[0].addNotify(); render(view[0], 300, true);
        });
        executor.runNext(); executor.runNext();
        edt(() -> { assertEquals(300, total(view[0])); view[0].removeNotify(); });
        assertEquals(Collections.singletonList(300L), built);
    }

    @Test public void savedRenderWhileDetachedSurvivesReparentingWithoutAnotherRender() throws Exception {
        edt(() -> {
            JPanel parent = new JPanel(); MeterDpsGUI meter = new MeterDpsGUI();
            parent.add(meter); parent.addNotify();
            try {
                render(meter, 100, false);
                assertEquals(100L, meter.table().getValueAt(0, 2));
                parent.remove(meter);
                assertFalse(meter.isDisplayable());
                render(meter, 900, false);
                assertEquals(900L, meter.table().getValueAt(0, 2));
                meter.metricChoice().setSelectedIndex(2);
                assertEquals("Detached metric changes still update the meter", 1d, (Double) field(meter, "meterMaximum"), 0d);
                parent.add(meter);
                assertTrue(meter.isDisplayable());
                assertEquals(900, total(meter));
                assertEquals(900L, meter.table().getValueAt(0, 2));
                assertFalse(meter.loading());
            } finally { parent.removeNotify(); }
        });
    }

    private static void render(MeterDpsGUI meter, long amount, boolean live) {
        meter.renderData(null, Collections.singletonList(enemy(amount)), new ArrayList<>(), 0, live);
    }
    private static void renderWithBob(MeterDpsGUI meter, long amount, boolean live) {
        Entity target = enemy(amount);
        target.getDamageList().add(new Damage(EncounterOutcomesTest.named(2, "Bob", 768), 1500, 25));
        meter.renderData(null, Collections.singletonList(target), new ArrayList<>(), 0, live);
    }
    private static Entity enemy(long amount) {
        Entity player = EncounterOutcomesTest.named(1, "Alice", 768);
        Entity enemy = new Entity(null, 20, 0);
        enemy.getDamageList().add(new Damage(player, 1000, (int) amount));
        enemy.updateDamageTaken(1000); enemy.updateDamageTaken(2000); return enemy;
    }
    private static long total(MeterDpsGUI meter) { return ((CombatMeterData) field(meter, "snapshot")).total; }
    private static JList<?> enemies(MeterDpsGUI meter) { return (JList<?>) field(meter, "enemyList"); }
    private static Object field(Object target, String name) {
        try { Field field = MeterDpsGUI.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static final class QueuedExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runNext() { assertFalse(SwingUtilities.isEventDispatchThread()); assertFalse(tasks.isEmpty()); tasks.remove().run(); }
    }
}
