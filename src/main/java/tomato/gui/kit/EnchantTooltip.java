package tomato.gui.kit;

import java.awt.Color;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;

/**
 * The shared item tooltip: the item heading, the rarity line in its gem color, then each unlocked slot's display name with its
 * effect beneath, and "(empty slot)" for empty ones. Colors come from the current theme, so build it when the tooltip is asked
 * for (or with a row that is rebuilt on theme changes), never while painting.
 */
public final class EnchantTooltip {
    private EnchantTooltip() {}

    public static String html(String heading, EnchantInfo info) {
        StringBuilder html = new StringBuilder("<html><b>").append(escape(heading)).append("</b><br>");
        Color ink = EnchantGem.ink(info);
        if (ink == null) html.append(escape(info.summary()));
        else html.append("<span style='color:").append(hex(ink)).append("'>").append(escape(info.summary())).append("</span>");
        String muted = hex(Tokens.color(Tokens.Role.TEXT_MUTED));
        for (EnchantInfo.Slot slot : info.slots()) {
            html.append("<br>");
            if (slot.empty()) {
                html.append("<span style='color:").append(muted).append("'>(empty slot)</span>");
                continue;
            }
            ParseEnchants.Definition definition = ParseEnchants.definition(slot.typeId());
            html.append(escape(definition.displayName()));
            if (!definition.description().isEmpty())
                html.append("<br>&nbsp;&nbsp;&nbsp;<span style='color:").append(muted).append("'>").append(escape(definition.description())).append("</span>");
        }
        if (info.state() == EnchantInfo.State.COUNT_ONLY && info.rarity() != EnchantInfo.Rarity.UNENCHANTED)
            html.append("<br><span style='color:").append(muted).append("'>").append(EnchantInfo.NAMES_NOT_AVAILABLE).append("</span>");
        return html.append("</html>").toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static String hex(Color color) { return String.format("#%06x", color.getRGB() & 0xFFFFFF); }
}
