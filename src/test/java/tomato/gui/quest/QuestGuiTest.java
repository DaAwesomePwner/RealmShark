package tomato.gui.quest;
import ui.UiTestLayout;

import org.junit.Test;
import static org.junit.Assert.*;
import packets.data.QuestData;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.*;
import java.util.prefs.AbstractPreferences;
import tomato.gui.modern.VioletTheme;

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

    @Test public void renderDesktopAndCompact() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install();
            QuestGUI ui = panel();
            QuestData a = quest("Royal tribute", 5, new int[] {1,1,1},12);
            QuestData b = quest("Cultist tribute",5,new int[]{2,2},13);
            QuestData c = quest("Festival exchange",8,new int[]{3,3,3,3,3},10,12,13);
            c.itemOfChoice = true; c.repeatable = true; c.expiration = "2026-09-15 12:00 UTC (sample)";
            ui.update(new QuestData[]{a,b,c});
            combo(ui,"Quest type").setSelectedItem("Event");
            JFrame frame = new JFrame("Daily Quests — sample data");
            frame.setContentPane(ui);
            try {
                for (int width : new int[]{1100,560}) {
                    frame.setSize(width,820); frame.setVisible(true); frame.validate();
                    UiTestLayout.settle(frame);
                    BufferedImage image = new BufferedImage(width,820,BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
                    try { ImageIO.write(image,"png",new File("quests-" + width + ".png")); }
                    catch(Exception e) { throw new RuntimeException(e); }
                    assertTrue(combo(ui,"Reward").getWidth() > 100);
                }
            } finally { frame.dispose(); }
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
    private static final class MemoryPreferences extends AbstractPreferences {
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
