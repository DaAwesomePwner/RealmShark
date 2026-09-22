package tomato.gui.dps;

import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.DpsData.LocalPlayerContext;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class StringDpsGUI extends DisplayDpsGUI {

    private static JTextArea textAreaDPS;
    private LocalPlayerContext playerContext;
    void setPlayerContext(LocalPlayerContext player) { playerContext=player; }

    public StringDpsGUI(TomatoData data) {

        setLayout(new BorderLayout());
        textAreaDPS = new tomato.gui.modern.EmptyLogArea("Every encounter tells a story", "Start capture and enter combat to see damage here.");
        add(TomatoGUI.createTextArea(textAreaDPS, true), BorderLayout.CENTER);
        textAreaDPS.setEditable(false);
        textAreaDPS.setMargin(new Insets(6, 8, 6, 8));
        ContentStyle.font(textAreaDPS, ContentStyle.report(ContentStyle.body()));

    }

    /**
     * Sets the text of DPS logger text area.
     *
     * @param text       Sets the text of text area.
     */
    private void setTextAreaAndLabelDPS(String text) {
        if (textAreaDPS != null && text != null) textAreaDPS.setText(text);
    }

    @Override
    protected void renderData(MapInfoPacket map, List<Entity> sortedEntityHitList, ArrayList<NotificationPacket> notifications, long totalDungeonPcTime, boolean isLive) {
        setTextAreaAndLabelDPS(DpsToString.stringDmgRealtime(map, sortedEntityHitList, notifications, playerContext, totalDungeonPcTime));
    }

    /**
     * Set font size or name of text area.
     */
    @Override
    protected void editFont(Font font) {
        ContentStyle.font(textAreaDPS, ContentStyle.FONT_FAMILY.equals(font.getName())
            ? ContentStyle.report(font) : font);
    }
}
