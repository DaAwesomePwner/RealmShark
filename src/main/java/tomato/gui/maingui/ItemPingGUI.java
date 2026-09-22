package tomato.gui.maingui;

import tomato.backend.data.TomatoData;
import tomato.realmshark.Sound;
import tomato.realmshark.AlertRules;

public class ItemPingGUI extends AlertRuleEditor {

    public ItemPingGUI(TomatoData data) {
        super(AlertRules.application(), AlertRules.Domain.ITEM, data.getItemPings(), "Item alert rules", () -> Sound.custom.preview(null));
    }
}
