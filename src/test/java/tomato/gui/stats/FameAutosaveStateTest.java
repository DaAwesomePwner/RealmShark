package tomato.gui.stats;

import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.FameTracker;
import tomato.backend.data.TomatoData;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSession;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

public class FameAutosaveStateTest {
    @After public void detachBridge() {
        FameTableBridge.getInstance().setFameTrackerGUI(null);
        FameTableBridge.getInstance().setFameTablePanel(null);
    }

    @Test public void staleCompletionsCannotClearNewSamplesOrNewSessionDirtyState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Consumer<Boolean>> completions = new ArrayList<>();
            List<String> names = new ArrayList<>();
            FameTableBridge.getInstance().setFameTrackerGUI(null);
            new FameTablePanel(null);
            FameTrackerGUI panel = new FameTrackerGUI((session, completion) -> {
                names.add(session.getSessionName()); completions.add(completion);
            });
            FameTrackerGUI.updateFame(1, 100, 1000); panel.triggerAutoSave();
            FameTrackerGUI.updateFame(1, 110, 2000); panel.triggerMapChangeAutoSave();
            completions.get(0).accept(true);
            assertTrue(panel.hasFameGainedSinceLastSave());
            completions.get(1).accept(true);
            assertFalse(panel.hasFameGainedSinceLastSave());

            FameTrackerGUI.updateFame(1, 120, 3000); panel.triggerAutoSave();
            panel.startNewSessionFile();
            assertNotEquals(names.get(2), names.get(3));
            FameTrackerGUI.updateFame(2, 500, 4000);
            completions.get(2).accept(true); // Prior session completes after reset.
            assertTrue(panel.hasFameGainedSinceLastSave());
            completions.get(3).accept(true); // Empty new-session snapshot is already stale.
            assertTrue(panel.hasFameGainedSinceLastSave());
            panel.triggerAutoSave(); completions.get(4).accept(true);
            assertFalse(panel.hasFameGainedSinceLastSave());
            assertFalse(panel.getFameData().containsKey(1));
            assertEquals(1, panel.getFameData().get(2).size());
        });
    }

    @Test public void failedSaveStaysDirtyAndReportsRetryStatusInBothFameViews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Consumer<Boolean>> completions = new ArrayList<>();
            FameTablePanel table = new FameTablePanel(null);
            FameTrackerGUI panel = new FameTrackerGUI((session, completion) -> completions.add(completion));
            FameTrackerGUI.updateFame(1, 100, 1000); panel.triggerAutoSave();
            completions.get(0).accept(false);
            assertTrue(panel.hasFameGainedSinceLastSave());
            panel.refreshNow(); table.refreshNow();
            assertTrue(StatisticsExplorerTest.named(panel, "fame-save-status", JLabel.class).getText().contains("failed"));
            assertTrue(StatisticsExplorerTest.named(table, "fame-table-save-status", JLabel.class).getText().contains("failed"));
            panel.triggerAutoSave(); completions.get(1).accept(true);
            assertFalse(panel.hasFameGainedSinceLastSave());
        });
    }

    @Test public void hiddenMapAutosavesKeepOrderedZeroNegativeAndCharacterSwitchVisits() throws Exception {
        Fixture fixture = fixture();
        fixture.table.onMapChange("A", 0);
        fixture.sample(1, 100, 1000, "Wizard");
        fixture.table.updateFame(1, 100, 2000, "Wizard");
        fixture.table.onMapChange("B", 3000);
        fixture.sample(1, 95, 4000, "Wizard");
        fixture.sample(2, 500, 5000, "Priest");
        fixture.table.updateFame(2, 500, 6000, "Priest");
        fixture.table.onMapChange("C", 7000);

        assertEquals(2, fixture.saved.size());
        FameSession first = fixture.saved.get(0), second = fixture.saved.get(1);
        assertEquals(first.getSessionName(), second.getSessionName());
        assertEquals(first.getCreatedTimestamp(), second.getCreatedTimestamp());
        assertEquals(1, first.getCharacterFameData().get(1).size());
        assertEquals(2, second.getCharacterFameData().get(1).size());
        assertEquals(1, second.getCharacterFameData().get(2).size());
        assertEquals("Wizard", second.getCharacterClassNames().get(1));
        assertEquals("Priest", second.getCharacterClassNames().get(2));
        assertEquals(1, first.getCharacterMapFameData().get(1).size());
        assertEquals(2, second.getCharacterMapFameData().get(1).size());
        assertEquals(0, first.getCharacterMapFameData().get(1).get(0).getFameGained(), 0);
        assertEquals(3000, first.getCharacterMapFameData().get(1).get(0).endTime);
        assertEquals(-5, second.getCharacterMapFameData().get(1).get(1).getFameGained(), 0);
        MapFameData priest = second.getCharacterMapFameData().get(2).get(0);
        assertEquals("B", priest.mapName); assertEquals(5000, priest.startTime); assertEquals(7000, priest.endTime);
        assertEquals(0, priest.getFameGained(), 0);
        fixture.completions.get(0).accept(true);
        assertTrue(fixture.graph.hasFameGainedSinceLastSave());
        fixture.completions.get(1).accept(true);
        assertFalse(fixture.graph.hasFameGainedSinceLastSave());
        assertEquals(0, fixture.table.trackingCounts().snapshots);
        assertEquals(0, fixture.graph.trackingCounts().snapshots);
        assertEquals(0, fixture.table.refreshCounts().scheduled);
        assertEquals(0, fixture.graph.refreshCounts().scheduled);
    }

    @Test public void unchangedFameTimestampDuringSaveStaysDirtyAndRetriesLatestOpenVisit() throws Exception {
        Fixture fixture = fixture();
        fixture.table.onMapChange("Realm", 0);
        FameTracker.trackFame(1, 159929, 1000);
        fixture.table.updateFame(1, 100, 1000, "Wizard");
        fixture.graph.triggerAutoSave();
        FameTracker.trackFame(1, 159929, 9000); // Graph keeps its existing change-only history policy.
        fixture.table.updateFame(1, 100, 9000, "Wizard");
        fixture.completions.get(0).accept(true);
        assertTrue(fixture.graph.hasFameGainedSinceLastSave());
        assertEquals(1, fixture.graph.trackingCounts().samples);
        fixture.graph.triggerAutoSave();
        assertEquals(1000, fixture.saved.get(0).getCharacterMapFameData().get(1).get(0).endTime);
        assertEquals(9000, fixture.saved.get(1).getCharacterMapFameData().get(1).get(0).endTime);
        fixture.completions.get(1).accept(false);
        assertTrue(fixture.graph.hasFameGainedSinceLastSave());
        fixture.graph.triggerAutoSave(); fixture.completions.get(2).accept(true);
        assertFalse(fixture.graph.hasFameGainedSinceLastSave());
    }

    @Test public void resetSubmitsOldThenEmptyThenNewHistoryAndKeepsPriorFailureVisible() throws Exception {
        Fixture fixture = fixture();
        fixture.table.onMapChange("Realm", 0);
        FameTracker.trackFame(1, 159929, 1000);
        fixture.table.updateFame(1, 100, 1000, "Wizard");
        fixture.table.updateFame(1, 100, 5000, "Wizard");
        fixture.table.resetSessions(false);
        assertEquals(2, fixture.saved.size());
        FameSession old = fixture.saved.get(0), empty = fixture.saved.get(1);
        assertNotEquals(old.getSessionName(), empty.getSessionName());
        assertEquals(5000, old.getCharacterMapFameData().get(1).get(0).endTime);
        assertTrue(empty.getCharacterMapFameData().isEmpty()); assertTrue(empty.getCharacterFameData().isEmpty());
        FameTracker.trackFame(1, 159929, 9000);
        fixture.table.updateFame(1, 100, 9000, "Wizard");
        fixture.completions.get(1).accept(true); // Cannot mark a newer sample clean.
        assertTrue(fixture.graph.hasFameGainedSinceLastSave());
        SwingUtilities.invokeAndWait(() -> {
            fixture.graph.refreshNow(); fixture.table.refreshNow();
            assertTrue(StatisticsExplorerTest.named(fixture.graph, "fame-save-status", JLabel.class).getText().contains("newer samples"));
        });
        // A previous-session failure arriving last remains visible without changing new-session dirtiness.
        fixture.completions.get(0).accept(false);
        SwingUtilities.invokeAndWait(() -> {
            fixture.graph.refreshNow(); fixture.table.refreshNow();
            assertTrue(StatisticsExplorerTest.named(fixture.table, "fame-table-save-status", JLabel.class).getText().contains("previous fame session"));
        });
        fixture.graph.triggerAutoSave(); fixture.completions.get(2).accept(true);
        FameSession current = fixture.saved.get(2);
        assertEquals(empty.getSessionName(), current.getSessionName());
        assertEquals(1, current.getCharacterFameData().get(1).size());
        assertEquals(9000, current.getCharacterFameData().get(1).get(0).getTime());
        assertEquals(9000, current.getCharacterMapFameData().get(1).get(0).startTime);
        assertEquals(5000, old.getCharacterMapFameData().get(1).get(0).endTime);
        assertFalse(fixture.graph.hasFameGainedSinceLastSave());
    }

    @Test public void namedSaveResetsIdentityOnlyForSuccessfulCurrentRevisionAndKeepsHistory() throws Exception {
        Fixture fixture = fixture();
        fixture.sample(1, 100, 1000, "Wizard");
        fixture.graph.saveCurrentSession("  Named  ");
        fixture.sample(1, 110, 2000, "Wizard");
        fixture.completions.get(0).accept(true);
        fixture.graph.triggerAutoSave();
        assertEquals("Named", fixture.saved.get(1).getSessionName());
        fixture.completions.get(1).accept(false);
        fixture.graph.saveCurrentSession("Named"); fixture.completions.get(2).accept(true);
        fixture.sample(1, 120, 3000, "Wizard"); fixture.graph.triggerAutoSave();
        assertTrue(fixture.saved.get(3).getSessionName().startsWith("Live_"));
        assertEquals(3, fixture.saved.get(3).getCharacterFameData().get(1).size());
        assertEquals(1, fixture.saved.get(0).getCharacterFameData().get(1).size());
    }

    @Test public void entityCapturedObservationCannotBeSplitByConcurrentSessionReset() throws Exception {
        CountDownLatch graphApplied = new CountDownLatch(1), finishObservation = new CountDownLatch(1);
        CountDownLatch resetAttempted = new CountDownLatch(1);
        AtomicBoolean holdsSessionLock = new AtomicBoolean();
        Fixture fixture = fixture(time -> {
            if (time != 2000) return;
            // Pause after the graph mutation returns, before Entity's observation reaches the table.
            // The former two-call Entity path releases this lock here and fails the assertion below.
            holdsSessionLock.set(Thread.holdsLock(FameTableBridge.getInstance()));
            graphApplied.countDown();
            try { assertTrue("Observation released", finishObservation.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        });
        TomatoData data = new TomatoData();
        RealmCharacter character = new RealmCharacter(); character.charId = 1; character.classString = "Wizard";
        data.charMap = new HashMap<>(); data.charMap.put(1, character);
        Entity entity = new Entity(data, 7, 0); entity.setUser(1);
        // Invoke the actual fame ingress without unrelated updateStats UI/capture side effects.
        Method fameIngress = Entity.class.getDeclaredMethod("fame", long.class); fameIngress.setAccessible(true);
        fixture.table.onMapChange("Realm", 0);
        capture(entity, fameIngress, 100, 1000);

        FutureTask<Void> observation = new FutureTask<>(() -> { capture(entity, fameIngress, 110, 2000); return null; });
        FutureTask<Void> reset = new FutureTask<>(() -> {
            resetAttempted.countDown(); fixture.table.resetSessions(false); return null;
        });
        Thread captureThread = new Thread(observation, "fame-observation-test"); captureThread.setDaemon(true);
        Thread resetThread = new Thread(reset, "fame-reset-test"); resetThread.setDaemon(true);
        captureThread.start();
        try {
            assertTrue("Graph phase reached", graphApplied.await(10, TimeUnit.SECONDS));
            assertTrue("Entity must retain the session lock across BOTH model mutations", holdsSessionLock.get());
            assertEquals(Arrays.asList(new Fame(100, 1000), new Fame(110, 2000)), fixture.graph.getFameData().get(1));
            assertEquals(100, fixture.table.getCurrentFame(1), 0);
            resetThread.start();
            assertTrue("Reset attempted", resetAttempted.await(10, TimeUnit.SECONDS));
            assertFalse("Reset cannot cross an unfinished observation", reset.isDone());
        } finally {
            // Never block the EDT on the capture-held monitor: both contenders are workers,
            // and the test thread releases capture before waiting for either to finish.
            finishObservation.countDown();
            captureThread.join(10000);
            if (resetThread.getState() != Thread.State.NEW) resetThread.join(10000);
        }
        observation.get(10, TimeUnit.SECONDS); reset.get(10, TimeUnit.SECONDS);

        assertEquals(2, fixture.saved.size());
        FameSession old = fixture.saved.get(0), empty = fixture.saved.get(1);
        assertEquals(Arrays.asList(new Fame(100, 1000), new Fame(110, 2000)), old.getCharacterData(1));
        MapFameData oldVisit = old.getCharacterMapFameData().get(1).get(0);
        assertEquals(110, oldVisit.endFame, 0); assertEquals(2000, oldVisit.endTime);
        assertEquals("Wizard", old.getCharacterClassNames().get(1));
        assertNotEquals(old.getSessionName(), empty.getSessionName());
        assertTrue(empty.getCharacterFameData().isEmpty()); assertTrue(empty.getCharacterMapFameData().isEmpty());
        assertEquals(0, fixture.graph.trackingCounts().samples); assertNull(fixture.table.getCurrentFame(1));

        // Same-fame first observation after reset must populate BOTH models and survive a map autosave.
        capture(entity, fameIngress, 110, 3000);
        fixture.table.onMapChange("Nexus", 4000);
        assertEquals(3, fixture.saved.size());
        FameSession next = fixture.saved.get(2);
        assertEquals(empty.getSessionName(), next.getSessionName());
        assertEquals(Collections.singletonList(new Fame(110, 3000)), next.getCharacterData(1));
        assertEquals(1, next.getCharacterMapFameData().get(1).size());
        MapFameData nextVisit = next.getCharacterMapFameData().get(1).get(0);
        assertEquals("Realm", nextVisit.mapName); assertEquals(3000, nextVisit.startTime); assertEquals(4000, nextVisit.endTime);
        assertEquals(0, nextVisit.getFameGained(), 0); assertEquals(110, character.fame);
        assertEquals(2000, oldVisit.endTime);
    }

    private static void capture(Entity entity, Method fameIngress, long fame, long time) throws Exception {
        StatData experience = new StatData(); experience.stringStatValue = Long.toString(fame * 2000 - 40071);
        entity.stat.set(StatType.EXP_STAT, experience);
        fameIngress.invoke(entity, time);
    }

    private static Fixture fixture() throws Exception { return fixture(time -> {}); }

    private static Fixture fixture(Consumer<Long> afterCapturedGraphSample) throws Exception {
        Fixture fixture = new Fixture();
        SwingUtilities.invokeAndWait(() -> {
            FameTableBridge bridge = FameTableBridge.getInstance(); bridge.setFameTrackerGUI(null);
            fixture.table = new FameTablePanel(null);
            fixture.graph = new FameTrackerGUI((session, completion) -> {
                // Match the production saver contract: detach synchronously, complete later.
                Gson gson = new Gson(); fixture.saved.add(gson.fromJson(gson.toJson(session), FameSession.class));
                fixture.completions.add(completion);
            }) {
                @Override void trackCapturedFame(int charId, long fame, long time) {
                    super.trackCapturedFame(charId, fame, time);
                    afterCapturedGraphSample.accept(time);
                }
            };
            bridge.setFameTablePanel(fixture.table); bridge.setFameTrackerGUI(fixture.graph);
        });
        return fixture;
    }

    private static final class Fixture {
        FameTablePanel table;
        FameTrackerGUI graph;
        final List<FameSession> saved = new ArrayList<>();
        final List<Consumer<Boolean>> completions = new ArrayList<>();
        void sample(int id, long fame, long time, String className) {
            FameTableBridge.observeFame(id, fame, time, className);
        }
    }
}
