package tomato.realmshark;

import org.junit.Test;
import packets.incoming.TextPacket;
import util.PropertiesManager;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class NotificationSoundTest {
    @Test public void decodesEveryBundledToneWithoutOpeningAudioHardware() throws Exception {
        for (String tone : Sound.BUILT_INS) {
            Sound.Decoded decoded = Sound.decode(tone);
            assertTrue(tone, decoded.pcm.length > 0);
            assertEquals(16, decoded.format.getSampleSizeInBits()); assertFalse(decoded.format.isBigEndian());
        }
    }
    @Test public void volumesMultiplyAndZeroIsRealSilence() {
        byte[] source = {0, 64, 0, (byte)192};
        assertArrayEquals(new byte[]{0, 16, 0, (byte)240}, Sound.scalePcm(source, 50, 50));
        assertArrayEquals(new byte[4], Sound.scalePcm(source, 0, 100));
        assertArrayEquals(source, Sound.scalePcm(source, 100, 100));
        assertEquals(64, source[1]);
    }
    @Test public void profileChoicesSurviveReconstructionAndReadLegacyKeys() {
        String old = PropertiesManager.getProperty("whiteBagSound");
        Sound first = Sound.realmEvent("profile-test", "Test");
        first.setEnabled(true); first.setAlertVolume(37); first.setTone("whitebag");
        Sound second = Sound.realmEvent("profile-test", "Test");
        assertTrue(second.isEnabled()); assertEquals(37, second.getVolume()); assertEquals("whitebag", second.getTone());
        assertEquals(old, PropertiesManager.getProperty("whiteBagSound"));
        PropertiesManager.setProperties("test.invalid.volume", "bad"); assertEquals(81, Sound.readPercent("test.invalid.volume", 81));
        PropertiesManager.setProperties("test.invalid.volume", "-1"); assertEquals(0, Sound.readPercent("test.invalid.volume", 81));
    }
    @Test public void invalidCustomSoundDoesNotReplaceExistingChoice() throws Exception {
        Sound sound = Sound.realmEvent("file-test", "Test"); sound.setTone("pm");
        File invalid = new File("bad-notification.wav"); Files.write(invalid.toPath(), new byte[]{1,2,3});
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> message = new AtomicReference<>();
        try {
            sound.chooseFile(invalid, result -> { message.set(result); done.countDown(); });
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals("pm", sound.getTone()); assertTrue(message.get().startsWith("Sound unchanged"));
        } finally { Files.deleteIfExists(invalid.toPath()); }
    }
    @Test public void muteIsHonoredForTestsWithoutOpeningHardware() throws Exception {
        boolean muted = Sound.isMuted(); Sound.setMuted(true);
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> message = new AtomicReference<>();
        try {
            Sound.whitebag.preview(result -> { message.set(result); done.countDown(); });
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(message.get().startsWith("Muted:"));
        } finally { Sound.setMuted(muted); }
    }
    private static TextPacket message(String sender, String recipient, String text) {
        TextPacket p = new TextPacket(); p.name = sender; p.recipient = recipient; p.text = text; return p;
    }
    @Test public void realmAnnouncementsRejectPlayerChatDungeonChatAndDefeatMessages() {
        PropertiesManager.setProperties("sound.realm.rules", "invalid");
        RealmEventAlerts alerts = new RealmEventAlerts();
        RealmEventAlerts.Rule cube = alerts.getRules().get(0), legion = alerts.getRules().get(1);
        cube.sound.setEnabled(true); legion.sound.setEnabled(true);
        try {
            assertTrue(alerts.matching(message("Player", "", "A cube god has spawned"), "Realm of the Mad God", 0).isEmpty());
            assertTrue(alerts.matching(message("#Oryx", "*Guild*", "A cube god has spawned"), "Realm of the Mad God", 0).isEmpty());
            assertTrue(alerts.matching(message("#Oryx", "", "A cube god has spawned"), "Nexus", 0).isEmpty());
            assertTrue(alerts.matching(message("#Oryx", "", "My cube god was slain!"), "Realm of the Mad God", 0).isEmpty());
            assertEquals(cube, alerts.matching(message("#Oryx", "", "A CUBE   GOD has spawned!"), "Realm of the Mad God", 0).get(0));
            assertTrue(alerts.matching(message("#Oryx", "", "A cube god has spawned!"), "Realm of the Mad God", 1).isEmpty());
            assertEquals(legion, alerts.matching(message("#Oryx the Mad God", "", "The Legion General is here!"), "Realm of the Mad God", 2).get(0));
            alerts.resetCooldowns(); assertEquals(1, alerts.matching(message("", "", "A cube god has spawned!"), "Realm of the Mad God", 3).size());
            assertTrue(alerts.matching(message("#Oryx", "", "A cube god has spawned!"), "Realm of the Mad God", 4).isEmpty());
            assertEquals(1, alerts.matching(message("#Oryx", "", "A cube god has spawned!"), "Realm of the Mad God", 30_000_000_004L).size());
            cube.sound.setEnabled(false); alerts.resetCooldowns();
            assertTrue(alerts.matching(message("#Oryx", "", "A cube god has spawned!"), "Realm of the Mad God", 5).isEmpty());
        } finally { cube.sound.setEnabled(false); legion.sound.setEnabled(false); PropertiesManager.setProperties("sound.realm.rules", "invalid"); }
    }
    @Test public void customRealmRulesPersistLiteralPhrasesAndCanBeRemoved() {
        PropertiesManager.setProperties("sound.realm.rules", "[]"); RealmEventAlerts alerts = new RealmEventAlerts();
        RealmEventAlerts.Rule rule = alerts.add("Test event", "[event] arrives");
        alerts.setPhrase(rule, "[event] has arrived");
        RealmEventAlerts loaded = new RealmEventAlerts(); assertEquals(1, loaded.getRules().size());
        assertEquals("[event] has arrived", loaded.getRules().get(0).getPhrase());
        assertTrue(RealmEventAlerts.containsPhrase("the [event] has arrived!", "[event] has arrived"));
        assertFalse(RealmEventAlerts.containsPhrase("a cube goddess", "cube god"));
        try { alerts.setPhrase(rule, "  "); fail("Empty rules must be rejected"); } catch (IllegalArgumentException expected) { }
        alerts.remove(rule); assertTrue(new RealmEventAlerts().getRules().isEmpty());
        PropertiesManager.setProperties("sound.realm.rules", "invalid");
    }
}
