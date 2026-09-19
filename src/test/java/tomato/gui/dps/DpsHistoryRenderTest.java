package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import static org.junit.Assert.*;

public class DpsHistoryRenderTest {
    @Test public void savedEncountersKeepDamageRowsWithUnknownEntityAndEmptyEquipmentIcons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int original = DpsDisplayOptions.equipmentOption;
            try {
                DpsDisplayOptions.equipmentOption = 3;
                Filter.disable();
                TomatoData data = new TomatoData();
                Entity owner = new Entity(data,1,0); owner.objectType=768;
                StatData name = new StatData(); name.stringStatValue="Test Player";
                owner.stat.set(StatType.NAME_STAT,name);
                String[] names={"Tomb of the Ancients","Ice Citadel","Deadwater Docks"};
                for (int i=0;i<names.length;i++) {
                    Entity target=new Entity(data,100+i,0); target.objectType=i==0?100:0;
                    StatData hp=new StatData();hp.statValue=10000;target.stat.set(StatType.MAX_HP_STAT,hp);
                    target.genericDamageHit(owner,new Projectile(123+i),1000);
                    target.updateDamageTaken(1000);target.updateDamageTaken(2000);
                    MapInfoPacket map=new MapInfoPacket();map.name=names[i];
                    HashMap<Integer,Entity> hits=new HashMap<>();hits.put(target.id,target);
                    data.dpsData.add(new DpsData(map,hits,new ArrayList<>(),5000,0,null));
                }
                DpsGUI view=new DpsGUI(data);
                selectLegacy(view);
                for(int index:new int[]{0,1,2,0,2}) {
                    view.setIndex(index);
                    assertTrue("Saved damage should remain visible for "+names[index],hasDamage(view));
                    assertEquals(index,view.getIndex());
                }
                data.map=new MapInfoPacket();data.map.name="Current area";
                DpsGUI.updateMapPacket(data);
                view.setIndex(-1);
                assertTrue(hasText(view,"Current area"));
                assertEquals(3,data.dpsData.size());
            } finally { DpsDisplayOptions.equipmentOption=original; }
        });
    }

    private static void selectLegacy(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JComboBox && ((JComboBox<?>)c).getItemCount() == 2 && "Meters".equals(((JComboBox<?>)c).getItemAt(0)))
                ((JComboBox<?>)c).setSelectedIndex(1);
            else if (c instanceof Container) selectLegacy((Container)c);
        }
    }

    private static boolean hasDamage(Container parent) {
        for(Component c:parent.getComponents()) {
            if(c instanceof JLabel && ((JLabel)c).getText()!=null && ((JLabel)c).getText().contains("DMG:"))return true;
            if(c instanceof Container && hasDamage((Container)c))return true;
        }
        return false;
    }
    private static boolean hasText(Container parent,String text) {
        for(Component c:parent.getComponents()) {
            if(c instanceof JTextArea && ((JTextArea)c).getText().contains(text))return true;
            if(c instanceof Container && hasText((Container)c,text))return true;
        }
        return false;
    }
}
