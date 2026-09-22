package tomato.gui.chat;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.junit.Test;
import packets.incoming.TextPacket;
import tomato.backend.data.TomatoData;
import tomato.realmshark.AlertRules;
import tomato.realmshark.Sound;
import util.PreferencesStore;
import static org.junit.Assert.*;

public class TypedChatAlertTest {
    @Test public void keywordRulesCoalesceAndRespectSharedSuppressionChannelPrecedenceAndWhisperDirection() throws Exception {
        Map<String, String> memory = new HashMap<>();
        AlertRules rules = new AlertRules(memory::get, (key, value) -> { memory.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1)); });
        rules.save(rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()), Arrays.asList(
            AlertRules.Rule.of(AlertRules.Mode.TEXT_CONTAINS, "help"), AlertRules.Rule.of(AlertRules.Mode.SPACE_TOKEN, "help")));
        boolean guild = Sound.guild.isEnabled(), pm = Sound.pm.isEnabled();
        try { SwingUtilities.invokeAndWait(() -> {
            Sound.guild.setEnabled(true); Sound.pm.setEnabled(true);
            List<Sound> audio = new ArrayList<>(); ChatFilters filters = new ChatFilters();
            ChatGUI view = new ChatGUI(new TomatoData(), filters, false, rules, audio::add);
            deliver(view, "Ann", "", "help"); assertEquals(Arrays.asList(Sound.keywords), audio);
            audio.clear(); deliver(view, "Ann", "*Guild*", "help"); assertEquals(Arrays.asList(Sound.guild), audio);
            audio.clear(); deliver(view, "Ann", "Me", "help"); assertEquals(Arrays.asList(Sound.pm), audio);
            audio.clear(); deliver(view, "Me", "Ann", "help"); deliver(view, "Ann", "SomeoneElse", "help"); assertTrue(audio.isEmpty());
            ChatFilters.Settings settings = filters.settings(); settings.ignoredPlayers.add("Ann"); filters.apply(settings, false);
            deliver(view, "Ann", "*Guild*", "help"); deliver(view, "Ann", "Me", "help"); deliver(view, "Ann", "", "help"); assertTrue(audio.isEmpty());
            new ChatPingGUI(new TomatoData(), view); assertTrue(audio.isEmpty());
        }); } finally { Sound.guild.setEnabled(guild); Sound.pm.setEnabled(pm); }
    }
    private static void deliver(ChatGUI gui, String sender, String recipient, String text) {
        TextPacket packet = new TextPacket(); packet.name = sender; packet.recipient = recipient; packet.text = text;
        gui.deliver(packet, ChatMessage.from(packet, "Me"));
    }
}
