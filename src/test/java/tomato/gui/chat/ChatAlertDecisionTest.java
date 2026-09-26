package tomato.gui.chat;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.incoming.TextPacket;
import tomato.backend.data.TomatoData;
import tomato.realmshark.AlertDecisions;
import tomato.realmshark.AlertRules;
import tomato.realmshark.Sound;
import tomato.realmshark.SoundSeam;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.realmshark.AlertDecisions.Result.*;

/** ALERT-4 chat decision points: ignored gating, channel/keyword precedence, no match, mute and playback outcome. */
public class ChatAlertDecisionTest {
    private final AtomicInteger plays = new AtomicInteger();
    private boolean guild, keywords, muted;
    private int master, guildVolume, keywordVolume;

    @Before public void isolate() {
        guild = Sound.guild.isEnabled(); keywords = Sound.keywords.isEnabled(); muted = Sound.isMuted();
        master = Sound.getMasterVolume(); guildVolume = Sound.guild.getVolume(); keywordVolume = Sound.keywords.getVolume();
        Sound.setVolume(100); Sound.guild.setAlertVolume(100); Sound.keywords.setAlertVolume(100);
        AlertDecisions.INSTANCE.clear(); SoundSeam.device("Synthetic output", plays);
        Sound.setMuted(false); Sound.guild.setEnabled(true); Sound.keywords.setEnabled(true);
    }
    @After public void restore() {
        SoundSeam.clear(); Sound.setVolume(master); Sound.guild.setAlertVolume(guildVolume); Sound.keywords.setAlertVolume(keywordVolume); Sound.guild.setEnabled(guild); Sound.keywords.setEnabled(keywords); Sound.setMuted(muted); AlertDecisions.INSTANCE.clear();
    }
    private static void deliver(ChatGUI gui, String sender, String recipient, String text) {
        TextPacket packet = new TextPacket(); packet.name = sender; packet.recipient = recipient; packet.text = text;
        gui.deliver(packet, ChatMessage.from(packet, "Me"));
    }
    private static AlertDecisions.Decision latest(boolean noMatch) { return AlertDecisions.INSTANCE.snapshot(noMatch).get(0); }

    @Test public void decisionsDistinguishNoMatchIgnoredPrecedenceMutedAndUnavailable() throws Exception {
        Map<String, String> memory = new HashMap<>();
        AlertRules rules = new AlertRules(memory::get, (key, value) -> { memory.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1)); });
        rules.save(rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()), Arrays.asList(
            AlertRules.Rule.of(AlertRules.Mode.SPACE_TOKEN, "priest"), AlertRules.Rule.of(AlertRules.Mode.TEXT_CONTAINS, "help")));
        ChatFilters filters = new ChatFilters();
        ChatGUI[] view = new ChatGUI[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new ChatGUI(new TomatoData(), filters, false, rules, (sound, id) -> sound.play(id), true));

        SwingUtilities.invokeAndWait(() -> deliver(view[0], "Ann", "", "please help"));
        AlertDecisions.Decision keyword = SoundSeam.settled(latest(false).id);
        assertEquals(PLAYED, keyword.result); assertEquals(AlertRules.Mode.TEXT_CONTAINS, keyword.ruleMode); assertEquals("help", keyword.ruleValue);
        assertEquals(1, keyword.ruleIndex); assertEquals("please help", keyword.sample); assertEquals("keywords", keyword.sound);

        SwingUtilities.invokeAndWait(() -> deliver(view[0], "Ann", "", "hello there"));
        assertEquals("no-match results stay out of the main history", keyword.id, latest(false).id);
        assertEquals(NO_MATCH, latest(true).result); assertTrue(latest(true).explanation.startsWith("No match"));

        SwingUtilities.invokeAndWait(() -> deliver(view[0], "Ann", "*Guild*", "help the guild"));
        AlertDecisions.Decision channel = SoundSeam.settled(latest(false).id);
        assertEquals("guild", channel.sound); assertEquals(PLAYED, channel.result);
        assertTrue(channel.explanation.contains("Keyword rule 2 also matched") && channel.explanation.contains("precedence"));

        Sound.setMuted(true);
        SwingUtilities.invokeAndWait(() -> deliver(view[0], "Ann", "", "help again"));
        assertEquals(MUTED, latest(false).result);
        Sound.setMuted(false); SoundSeam.unavailable("Output device disconnected");
        SwingUtilities.invokeAndWait(() -> deliver(view[0], "Ann", "", "help once more"));
        AlertDecisions.Decision unavailable = SoundSeam.settled(latest(false).id);
        assertEquals(UNAVAILABLE, unavailable.result); assertTrue(unavailable.playback.contains("Output device disconnected"));

        ChatFilters.Settings settings = filters.settings(); settings.ignoredPlayers.add("Ann"); filters.apply(settings, false);
        int before = plays.get();
        SwingUtilities.invokeAndWait(() -> { deliver(view[0], "Ann", "", "help from an ignored player"); deliver(view[0], "Ann", "", "nothing to see"); });
        AlertDecisions.Decision ignored = latest(false);
        assertEquals(IGNORED, ignored.result); assertTrue(ignored.explanation.contains("ignored")); assertEquals("help", ignored.ruleValue);
        assertEquals("ignored messages that would not alert are not recorded", ignored.id + 0, latest(true).id);
        SwingUtilities.invokeAndWait(() -> deliver(view[0], "Me", "", "help from myself"));
        assertEquals("own messages are not alert candidates", ignored.id, latest(true).id);
        assertEquals(before, plays.get());
    }
}
