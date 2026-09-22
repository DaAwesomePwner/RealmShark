package tomato.gui.chat;

import tomato.backend.data.TomatoData;
import tomato.gui.maingui.AlertRuleEditor;
import tomato.realmshark.AlertRules;
import tomato.realmshark.Sound;

public class ChatPingGUI extends AlertRuleEditor {

    public ChatPingGUI(TomatoData data, ChatGUI main) {
        super(AlertRules.application(), AlertRules.Domain.CHAT, main.getPingMessages(), "Chat alert rules", () -> Sound.keywords.preview(null));
    }
}
