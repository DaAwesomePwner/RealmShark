package tomato.gui.dps;

import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class StringDpsGUI extends DisplayDpsGUI {

    private static JTextArea textAreaDPS;
    private final TomatoData data;
    private final JButton button;
    private final JPanel actions = ContentStyle.controls();
    private boolean freeze;
    private Entity playerContext;
    void setPlayerContext(Entity player) { playerContext=player; }

    public StringDpsGUI(TomatoData data) {
        this.data = data;

        setLayout(new BorderLayout());
        textAreaDPS = new tomato.gui.modern.EmptyLogArea("Every encounter tells a story", "Start capture and enter combat to see damage here.");
        add(TomatoGUI.createTextArea(textAreaDPS, true), BorderLayout.CENTER);
        textAreaDPS.setEditable(false);
        textAreaDPS.setMargin(new Insets(6, 8, 6, 8));
        ContentStyle.font(textAreaDPS, ContentStyle.report(ContentStyle.body()));

        button = new JButton("Freeze");
        button.addActionListener(e -> clicked());
        actions.add(button);
        add(actions, BorderLayout.SOUTH);
    }

    private void clicked() {
        if (freeze) {
            button.setText("Freeze");
            freeze = false;
        } else {
            button.setText("Unfreeze");
            freeze = true;
        }
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
        if (freeze && isLive && button.isVisible()) {
            return;
        }
        button.setVisible(isLive);
        actions.setVisible(isLive);
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
