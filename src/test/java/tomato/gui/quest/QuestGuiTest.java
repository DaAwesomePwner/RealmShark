package tomato.gui.quest;

import org.junit.Test;
import static org.junit.Assert.*;
import packets.data.QuestData;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.prefs.AbstractPreferences;

public class QuestGuiTest {
    private static final Map<Integer, String> NAMES = new HashMap<>();
    static {
        NAMES.put(1, "Mark of the Forgotten King");
        NAMES.put(2, "Mark of Malus");
        NAMES.put(3, "Festival Token");
        NAMES.put(10, "Mighty Quest Chest");
        NAMES.put(11, "Standard Quest Chest");
        NAMES.put(12, "Royal Epic Quest Chest");
        NAMES.put(13, "Cultish Epic Quest Chest");
        NAMES.put(14, "Beginner Quest Chest");
    }

    private static QuestData quest(String name, int category, int[] needed, int... rewards) {
        QuestData q = new QuestData();
        q.id = name; q.name = name; q.category = category; q.requirements = needed; q.rewards = rewards;
        q.description = "Bring the listed items to the Tinkerer to claim your reward.";
        return q;
    }

    private static QuestGUI panel() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.put("category.5", "Daily"); prefs.put("category.8", "Event");
        return new QuestGUI(NAMES::get, id -> null, prefs);
    }

    @Test public void countsChoicesFiltersAndSortsRemainFaithful() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            QuestData a = quest("Royal tribute", 5, new int[] {1,1,1,1,1,1,1,1,1,1}, 12);
            QuestData b = quest("Cultist tribute", 5, new int[] {2,2}, 13);
            QuestData c = quest("Festival exchange", 8, new int[] {3,3,3}, 10,12);
            c.itemOfChoice = true; c.repeatable = true; c.completed = true; c.expiration = "As supplied by the server";
            QuestData d = quest("Finished", 5, new int[] {1}, 11); d.completed = true;
            QuestData unknown = quest("Unknown items", 99, new int[] {9876}, 9999);
            ui.update(new QuestData[] {a,b,c,d,unknown});
            JTable table = find(ui, JTable.class);
            assertEquals(4, table.getRowCount());
            assertEquals(Integer.valueOf(10), QuestGUI.quantities(a.requirements).get(1));
            combo(ui, "Reward").setSelectedItem("Epic quest chests");
            assertEquals(3, table.getRowCount()); // Includes the Epic option of a choice quest.
            combo(ui, "Quest type").setSelectedItem("Daily");
            assertEquals(2, table.getRowCount());
            combo(ui, "Sort by").setSelectedItem("Fewest required items");
            assertEquals("Cultist tribute", table.getValueAt(0, 1));
            assertEquals(2, table.getValueAt(0, 4));
            table.getRowSorter().toggleSortOrder(4);
            table.getRowSorter().toggleSortOrder(4);
            assertEquals(10, table.getValueAt(0, 4));
            combo(ui, "Quest type").setSelectedItem("All types");
            combo(ui, "Reward").setSelectedItem("Mighty quest chests");
            assertEquals(1, table.getRowCount());
            assertTrue(allText(ui).contains("CHOOSE ONE"));
            assertTrue(allText(ui).contains("3 × Festival Token"));
            button(ui, "Pin quest").doClick();
            checkbox(ui, "Pinned only").doClick();
            combo(ui, "Reward").setSelectedItem("All rewards");
            assertEquals(1, table.getRowCount());
            checkbox(ui, "Pinned only").doClick();
            checkbox(ui, "Show completed").doClick();
            assertEquals(5, table.getRowCount());
            find(ui, JTextField.class).setText("9876");
            assertEquals(1, table.getRowCount());
            assertTrue(allText(ui).contains("Unknown item #9876"));
            find(ui, JTextField.class).setText("[");
            assertEquals(0, table.getRowCount()); // Literal, never a regex.
        });
    }

    @Test public void packetArraysAreCopiedBeforeEdtDispatch() throws Exception {
        final QuestGUI[] ui = new QuestGUI[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = panel();
            QuestData q = quest("Copied", 5, new int[] {1,1}, 10);
            Thread thread = new Thread(() -> ui[0].update(new QuestData[] {q}));
            thread.start();
            try { thread.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            q.requirements[0] = 999; q.rewards[0] = 999; q.name = "Mutated";
        });
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(ui[0], JTable.class);
            assertEquals("Copied", table.getValueAt(0,1));
            assertTrue(table.getValueAt(0,3).toString().contains("Mighty"));
            assertTrue(allText(ui[0]).contains("2 × Mark of the Forgotten King"));
            ui[0].update(new QuestData[0]);
            assertEquals(0, table.getRowCount());
        });
    }

    @Test public void packetRefreshKeepsSortedSelectionAndPinsByStableId() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MemoryPreferences prefs = new MemoryPreferences();
            QuestGUI ui = new QuestGUI(NAMES::get, id -> null, prefs);
            QuestData a = quest("Alpha", 5, new int[]{1}, 10);
            QuestData b = quest("Beta", 5, new int[]{2,2}, 13);
            QuestData c = quest("Gamma", 8, new int[]{3,3,3}, 12);
            ui.update(new QuestData[]{c,a,b});
            JTable table = find(ui, JTable.class);
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4, SortOrder.DESCENDING)));
            table.setRowSelectionInterval(1,1);
            assertEquals("Beta", table.getValueAt(table.getSelectedRow(),1));
            b.name = "Renamed Beta"; b.description = "Updated selected quest"; b.requirements = new int[]{2,2,2,2};
            ui.update(new QuestData[]{b,c,a});
            assertEquals("Renamed Beta", table.getValueAt(table.getSelectedRow(),1));
            assertEquals(0, table.getSelectedRow());
            assertTrue(allText(ui).contains("Updated selected quest"));
            button(ui,"Pin quest").doClick();
            checkbox(ui,"Pinned only").doClick();
            assertEquals(1, table.getRowCount()); assertEquals("Renamed Beta",table.getValueAt(0,1));
            QuestGUI reopened = new QuestGUI(NAMES::get, id -> null, prefs);
            reopened.update(new QuestData[]{a,b,c}); checkbox(reopened,"Pinned only").doClick();
            assertEquals(1,find(reopened,JTable.class).getRowCount());
            assertEquals("Renamed Beta",find(reopened,JTable.class).getValueAt(0,1));
            button(ui,"Unpin quest").doClick();
            assertEquals(0,table.getRowCount()); assertFalse(button(ui,"Pin quest").isEnabled());
            assertFalse(allText(ui).contains("Updated selected quest"));
            button(ui,"Reset filters").doClick();
            assertEquals(3,table.getRowCount()); assertTrue(button(ui,"Pin quest").isEnabled());
        });
    }

    @Test public void emptyUpdatesAndFilterResetClearObsoleteDetailsAndPinState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel(); JTable table = find(ui,JTable.class);
            assertTrue(allText(ui).contains("Plan your next turn-in"));
            assertFalse(button(ui,"Pin quest").isEnabled());
            ui.update(new QuestData[0]);
            assertEquals(0,table.getRowCount()); assertTrue(allText(ui).contains("0 quests captured"));
            QuestData q = quest("Prior selected title",5,new int[]{1},10);
            q.description = "Obsolete detail sentinel";
            ui.update(new QuestData[]{q}); button(ui,"Pin quest").doClick();
            find(ui,JTextField.class).setText("no matching quest");
            assertEquals(0,table.getRowCount()); assertFalse(button(ui,"Pin quest").isEnabled());
            assertFalse(allText(ui).contains("Obsolete detail sentinel"));
            assertTrue(allText(ui).contains("No matching quests"));
            button(ui,"Reset filters").doClick();
            assertEquals(1,table.getRowCount()); assertTrue(button(ui,"Unpin quest").isEnabled());
            assertTrue(allText(ui).contains("Obsolete detail sentinel"));
            ui.update((QuestData[])null);
            assertEquals(0,table.getRowCount()); assertFalse(button(ui,"Pin quest").isEnabled());
            assertFalse(allText(ui).contains("Obsolete detail sentinel"));
        });
    }

    private static JComboBox<?> combo(Container c, String name) {
        for(Component child:c.getComponents()) {
            if(child instanceof JComboBox && name.equals(child.getAccessibleContext().getAccessibleName())) return (JComboBox<?>)child;
            if(child instanceof Container) { JComboBox<?> result=combo((Container)child,name); if(result!=null)return result; }
        }
        return null;
    }
    private static AbstractButton button(Container c,String name) {
        for(Component child:c.getComponents()) {
            if(child instanceof AbstractButton && name.equals(((AbstractButton)child).getText()))return (AbstractButton)child;
            if(child instanceof Container){ AbstractButton result=button((Container)child,name);if(result!=null)return result; }
        }
        return null;
    }
    private static AbstractButton checkbox(Container c,String name){return button(c,name);}
    private static String allText(Container c) {
        StringBuilder s=new StringBuilder();
        for(Component child:c.getComponents()) {
            if(child instanceof JLabel)s.append(((JLabel)child).getText());
            if(child instanceof JTextArea)s.append(((JTextArea)child).getText());
            if(child instanceof Container)s.append(allText((Container)child));
        }
        return s.toString();
    }
    private static <T>T find(Container c,Class<T> type){
        for(Component child:c.getComponents()){
            if(type.isInstance(child))return type.cast(child);
            if(child instanceof Container){T result=find((Container)child,type);if(result!=null)return result;}
        }
        return null;
    }
    static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String,String> values=new HashMap<>();
        MemoryPreferences(){super(null,"");}
        protected void putSpi(String k,String v){values.put(k,v);}
        protected String getSpi(String k){return values.get(k);}
        protected void removeSpi(String k){values.remove(k);}
        protected void removeNodeSpi(){}
        protected String[] keysSpi(){return values.keySet().toArray(new String[0]);}
        protected String[] childrenNamesSpi(){return new String[0];}
        protected AbstractPreferences childSpi(String n){throw new UnsupportedOperationException();}
        protected void syncSpi(){}
        protected void flushSpi(){}
    }
}
