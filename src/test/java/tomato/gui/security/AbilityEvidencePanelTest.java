package tomato.gui.security;

import org.junit.Test;
import tomato.ability.*;
import javax.swing.*;
import java.awt.*;
import static org.junit.Assert.*;

public class AbilityEvidencePanelTest {
    @Test public void searchesBeyondPageAndResetClearsDetails()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            AbilityObservationStore store=new AbilityObservationStore(500);
            for(int i=0;i<250;i++)store.add(new AbilityObservation("session",null,i,1,i==0?"earliest target":"other","Mystic",1,"Orb","stasis",5,6,"Ambiguous candidate"));
            AbilityEvidencePanel panel=new AbilityEvidencePanel(store);JTable table=find(panel,JTable.class);JTextField query=find(panel,JTextField.class);
            assertEquals(100,table.getRowCount());query.setText("earliest target");assertEquals(1,table.getRowCount());assertEquals("earliest target",table.getValueAt(0,1));
            table.setRowSelectionInterval(0,0);store.reset();panel.refresh();assertEquals(0,table.getRowCount());
        });
    }
    static <T>T find(Container c,Class<T> type){for(Component child:c.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container){T found=find((Container)child,type);if(found!=null)return found;}}return null;}
}
