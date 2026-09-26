package tomato.realmshark;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.incoming.TextPacket;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.realmshark.AlertDecisions.Result.*;

/** ALERT-4 decision points record detached outcomes; asynchronous playback is correlated by decision ID. */
public class AlertDecisionsTest {
    private final AtomicInteger plays = new AtomicInteger();
    private boolean muted, customEnabled;
    private int master, customVolume;
    private final Map<String, String> memory = new HashMap<>();
    private final AlertRules rules = new AlertRules(memory::get, (key, value) -> { throw new AssertionError("Decisions never save rules"); });

    @Before public void isolate() {
        muted = Sound.isMuted(); master = Sound.getMasterVolume(); customEnabled = Sound.custom.isEnabled(); customVolume = Sound.custom.getVolume();
        AlertDecisions.INSTANCE.clear(); SoundSeam.device("Synthetic output", plays);
        Sound.setMuted(false); Sound.setVolume(100); Sound.custom.setEnabled(true); Sound.custom.setAlertVolume(100);
    }
    @After public void restore() {
        SoundSeam.clear(); Sound.setMuted(muted); Sound.setVolume(master); Sound.custom.setEnabled(customEnabled); Sound.custom.setAlertVolume(customVolume);
        AlertDecisions.INSTANCE.clear();
    }
    private static long matched(Sound sound) {
        return AlertDecisions.INSTANCE.record(new AlertDecisions.Entry(AlertDecisions.Source.ITEM).sound(sound).subject("Synthetic").explain("Synthetic match."));
    }

    @Test public void soundGateDistinguishesOffMutedVolumePlayedAndUnavailable() throws Exception {
        long played = matched(Sound.custom); Sound.custom.play(played);
        assertEquals(PLAYED, SoundSeam.settled(played).result); assertEquals("Played on Synthetic output.", SoundSeam.settled(played).playback);
        Sound.setMuted(true);
        long mutedDecision = matched(Sound.custom); Sound.custom.play(mutedDecision);
        assertEquals(MUTED, AlertDecisions.INSTANCE.find(mutedDecision).result);
        Sound.setMuted(false); Sound.custom.setAlertVolume(0);
        long silent = matched(Sound.custom); Sound.custom.play(silent);
        assertEquals(SILENT_VOLUME, AlertDecisions.INSTANCE.find(silent).result);
        Sound.custom.setAlertVolume(100); Sound.custom.setEnabled(false);
        long off = matched(Sound.custom); Sound.custom.play(off);
        assertEquals(SOUND_OFF, AlertDecisions.INSTANCE.find(off).result);
        Sound.custom.setEnabled(true); SoundSeam.unavailable("No output device");
        long unavailable = matched(Sound.custom); Sound.custom.play(unavailable);
        AlertDecisions.Decision failed = SoundSeam.settled(unavailable);
        assertEquals(UNAVAILABLE, failed.result); assertTrue(failed.playback.contains("No output device"));
        assertEquals(1, plays.get());
        // Test (preview) is not an event decision and never alters recorded outcomes.
        int before = AlertDecisions.INSTANCE.snapshot(true).size(); Sound.custom.preview(null); Thread.sleep(50);
        assertEquals(before, AlertDecisions.INSTANCE.snapshot(true).size());
        // Direct callers without their own decision still record suppression rather than returning silently.
        Sound.setMuted(true); Sound.custom.play();
        assertEquals(MUTED, AlertDecisions.INSTANCE.snapshot(false).get(0).result); assertEquals(AlertDecisions.Source.DIRECT, AlertDecisions.INSTANCE.snapshot(false).get(0).source);
    }

    @Test public void realmEventsRecordCooldownDisabledDefeatAndNoMatchWithoutPlayingThem() {
        String saved = PropertiesManager.getProperty("sound.realm.rules");
        PropertiesManager.setProperties("sound.realm.rules", "[]");
        RealmEventAlerts alerts = new RealmEventAlerts();
        RealmEventAlerts.Rule rule = alerts.add("Synthetic god", "synthetic god");
        try {
            rule.sound.setEnabled(true);
            TextPacket spawn = new TextPacket(); spawn.name = "#Oryx"; spawn.recipient = ""; spawn.text = "A Synthetic God has spawned!";
            assertEquals(1, alerts.matching(spawn, "Realm of the Mad God", 0).size());
            assertTrue(alerts.matching(spawn, "Realm of the Mad God", 5_000_000_000L).isEmpty());
            List<AlertDecisions.Decision> recorded = AlertDecisions.INSTANCE.snapshot(false);
            assertEquals(COOLDOWN, recorded.get(0).result); assertTrue(recorded.get(0).explanation.contains("5 s after"));
            assertEquals(SUBMITTED, recorded.get(1).result); assertEquals(rule.id, recorded.get(1).ruleRef);
            rule.sound.setEnabled(false); alerts.resetCooldowns();
            assertTrue(alerts.matching(spawn, "Realm of the Mad God", 0).isEmpty());
            assertEquals(SOUND_OFF, AlertDecisions.INSTANCE.snapshot(false).get(0).result);
            TextPacket defeat = new TextPacket(); defeat.name = "#Oryx"; defeat.recipient = ""; defeat.text = "The Synthetic God has been slain";
            TextPacket other = new TextPacket(); other.name = "#Oryx"; other.recipient = ""; other.text = "Something else happened";
            alerts.matching(defeat, "Realm of the Mad God", 0); alerts.matching(other, "Realm of the Mad God", 0);
            List<AlertDecisions.Decision> quiet = AlertDecisions.INSTANCE.snapshot(true);
            assertEquals(NO_MATCH, quiet.get(0).result); assertTrue(quiet.get(0).explanation.contains("No realm event phrase"));
            assertEquals(NO_MATCH, quiet.get(1).result); assertTrue(quiet.get(1).explanation.contains("defeat"));
            assertEquals(0, plays.get());
        } finally { alerts.remove(rule); PropertiesManager.setProperties("sound.realm.rules", saved == null ? "invalid" : saved); }
    }

    @Test public void lootAndEntityHooksRecordExactRuleIdentityAndBoundedHistory() {
        memory.put(AlertRules.Domain.ITEM.key(), "{\"version\":1,\"rules\":[{\"mode\":\"ITEM_ID\",\"value\":\"7\"},{\"mode\":\"ITEM_ID\",\"value\":\"42\"}]}");
        long match = AlertDecisions.lootItem(rules, Collections.emptyList(), 42, "Synthetic Blade", "", false);
        AlertDecisions.Decision d = AlertDecisions.INSTANCE.find(match);
        assertEquals(AlertRules.Mode.ITEM_ID, d.ruleMode); assertEquals("42", d.ruleValue); assertEquals(1, d.ruleIndex); assertEquals(Integer.valueOf(42), d.sampleId);
        assertEquals(0, AlertDecisions.lootItem(rules, Collections.emptyList(), 142, "Other", "Damage(3)\n", false));
        assertEquals(NO_MATCH, AlertDecisions.INSTANCE.snapshot(true).get(0).result);
        long enchant = AlertDecisions.lootItem(rules, Collections.emptyList(), 142, "Other", "Damage(3)\nSpeed(4)\n", true);
        assertEquals(AlertDecisions.ENCHANT_REF, AlertDecisions.INSTANCE.find(enchant).ruleRef);
        assertTrue(AlertDecisions.INSTANCE.find(enchant).explanation.contains("Damage(3) (+1 more)"));
        assertEquals(0, AlertDecisions.entityAlert(rules, Collections.singletonList("99"), 12));
        for (int i = 0; i < AlertDecisions.CAPACITY + 20; i++) matched(Sound.custom);
        for (int i = 0; i < AlertDecisions.NO_MATCH_CAPACITY + 5; i++) AlertDecisions.lootItem(rules, Collections.emptyList(), 5000 + i, null, "", false);
        assertEquals(AlertDecisions.CAPACITY, AlertDecisions.INSTANCE.snapshot(false).size());
        assertEquals(AlertDecisions.CAPACITY + AlertDecisions.NO_MATCH_CAPACITY, AlertDecisions.INSTANCE.snapshot(true).size());
        assertNull("evicted decisions are gone, not replayed", AlertDecisions.INSTANCE.find(match));
        AlertDecisions.INSTANCE.complete(match, PLAYED, "late"); // Late completion of an evicted decision is ignored.
        assertEquals(0, plays.get());
    }
}
