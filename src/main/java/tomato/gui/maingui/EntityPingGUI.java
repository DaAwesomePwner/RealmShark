package tomato.gui.maingui;

import tomato.backend.data.TomatoData;
import tomato.realmshark.Sound;
import tomato.realmshark.AlertRules;

public class EntityPingGUI extends AlertRuleEditor {

    public EntityPingGUI(TomatoData data) {
        super(AlertRules.application(), AlertRules.Domain.ENTITY, data.getEntityIdPings(), "Entity alert rules", () -> Sound.custom.preview(null));
    }
}
