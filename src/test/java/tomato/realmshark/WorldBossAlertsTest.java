package tomato.realmshark;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import util.PropertiesManager;
import java.util.*;
import static org.junit.Assert.*;

public class WorldBossAlertsTest {
    private static final int CUBE = 1, SPHINX = 2, LICH = 3, TOWER = 4;
    private static final long LATER = 30_000_000_001L;
    private final Map<Integer, List<String>> assets = new HashMap<>();
    private WorldBossAlerts alerts;

    @Before public void setUp() {
        PropertiesManager.setProperties(WorldBossAlerts.KEY, "invalid");
        assets.put(CUBE, Arrays.asList("Cube God"));
        assets.put(SPHINX, Arrays.asList("Grand Sphinx"));
        assets.put(LICH, Arrays.asList("Lich", "Haunted Lich"));
        assets.put(TOWER, Arrays.asList("Pentaract Tower"));
        alerts = new WorldBossAlerts(type -> assets.get(type), Sound.worldBoss);
        Sound.worldBoss.setEnabled(true);
        AlertDecisions.INSTANCE.clear();
    }
    @After public void tearDown() { Sound.worldBoss.setEnabled(false); PropertiesManager.setProperties(WorldBossAlerts.KEY, "invalid"); }

    @Test public void onlyTheRealmIsWatched() {
        alerts.enterMap("Nexus");
        assertNull(alerts.onQuest(10, CUBE, 0));
        assertNull(alerts.onAppeared(11, CUBE, 0));
        alerts.enterMap(WorldBossAlerts.REALM);
        assertNotNull(alerts.onAppeared(11, CUBE, 0));
    }
    @Test public void questMoveToAListedBossAlertsOncePerObject() {
        alerts.enterMap(WorldBossAlerts.REALM);
        WorldBossAlerts.Hit hit = alerts.onQuest(10, SPHINX, 0);
        assertEquals("Grand Sphinx", hit.name); assertEquals(WorldBossAlerts.Signal.QUEST, hit.signal);
        assertNull(alerts.onQuest(10, SPHINX, LATER));
        assertNull(alerts.onAppeared(10, SPHINX, LATER));
    }
    @Test public void anUnseenQuestTargetResolvesWhenItFirstAppears() {
        alerts.enterMap(WorldBossAlerts.REALM);
        assertNull(alerts.onQuest(20, null, 0));
        WorldBossAlerts.Hit hit = alerts.onAppeared(20, CUBE, 1);
        assertEquals(WorldBossAlerts.Signal.QUEST, hit.signal);
        // A newer quest target replaces the pending one.
        assertNull(alerts.onQuest(21, null, 2)); assertNull(alerts.onQuest(22, null, 3));
        assertEquals(WorldBossAlerts.Signal.VIEW, alerts.onAppeared(21, SPHINX, 4).signal);
        assertNull(alerts.onQuest(-1, null, 5));
        assertNull("A cleared quest target is no longer pending", alerts.onAppeared(22, LICH, 6));
    }
    @Test public void unlistedQuestTargetsAreRecordedWithTheirNameButSilent() {
        alerts.enterMap(WorldBossAlerts.REALM);
        assertNull(alerts.onQuest(30, LICH, 0));
        AlertDecisions.Decision d = AlertDecisions.INSTANCE.snapshot(true).get(0);
        assertEquals(AlertDecisions.Source.WORLD_BOSS, d.source); assertEquals(AlertDecisions.Result.NO_MATCH, d.result);
        assertTrue(d.subject, d.subject.startsWith("Lich"));
        assertNull(alerts.onQuest(30, LICH, 1));
        assertNull("An unlisted object in view is not even recorded", alerts.onAppeared(31, LICH, 0));
        assertEquals(1, AlertDecisions.INSTANCE.snapshot(true).size());
    }
    @Test public void theSameBossNamePausesForThirtySecondsAndMapsReset() {
        alerts.enterMap(WorldBossAlerts.REALM);
        assertNotNull(alerts.onAppeared(40, TOWER, 0));
        assertNull(alerts.onAppeared(41, TOWER, 1));
        assertEquals(AlertDecisions.Result.COOLDOWN, AlertDecisions.INSTANCE.snapshot(false).get(0).result);
        assertNotNull(alerts.onAppeared(42, TOWER, LATER));
        alerts.enterMap(WorldBossAlerts.REALM);
        assertNotNull("A new visit forgets alerted objects", alerts.onAppeared(40, TOWER, LATER + 1));
    }
    @Test public void aDisabledAlertRecordsButDoesNotPlay() {
        Sound.worldBoss.setEnabled(false); alerts.enterMap(WorldBossAlerts.REALM);
        assertNull(alerts.onQuest(50, CUBE, 0));
        assertEquals(AlertDecisions.Result.SOUND_OFF, AlertDecisions.INSTANCE.snapshot(false).get(0).result);
    }
    @Test public void namesMatchAnyAssetNameIgnoringCaseAndSpacing() {
        alerts.setNames(Arrays.asList("  haunted   LICH ", "", "Haunted Lich", "Cube God"));
        assertEquals(Arrays.asList("haunted LICH", "Cube God"), alerts.getNames());
        alerts.enterMap(WorldBossAlerts.REALM);
        assertEquals("Haunted Lich", alerts.onQuest(60, LICH, 0).name);
        assertNull("Grand Sphinx is no longer listed", alerts.onAppeared(61, SPHINX, 0));
    }
    @Test public void namesPersistAndInvalidSavesFallBackToDefaults() {
        assertEquals(WorldBossAlerts.DEFAULT_NAMES, alerts.getNames());
        alerts.setNames(Arrays.asList("Cube God", "Ghost Ship"));
        assertEquals(Arrays.asList("Cube God", "Ghost Ship"), new WorldBossAlerts(type -> assets.get(type), Sound.worldBoss).getNames());
        char[] longName = new char[81]; Arrays.fill(longName, 'a');
        try { alerts.setNames(Collections.singletonList(new String(longName))); fail("Overlong names must be rejected"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(Arrays.asList("Cube God", "Ghost Ship"), alerts.getNames());
    }
}
