package tomato.gui.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;
import tomato.backend.data.*;
import tomato.gui.modern.VioletTheme;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.enums.CharacterClass;

public class CharacterJournalGuiTest {
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) { if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type); if (found != null) return found; } }
        return null;
    }
    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) { if (c instanceof JButton && ((JButton)c).getText().equals(text)) return (JButton)c;
            if (c instanceof Container) { JButton found = button((Container)c,text); if (found != null) return found; } } return null;
    }
    private static JTextArea notes(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextArea && "character-notes".equals(c.getName())) return (JTextArea)c;
            if (c instanceof Container) { JTextArea found = notes((Container)c); if (found != null) return found; }
        }
        return null;
    }
    private static void render(JFrame frame, String filename, int width) {
        frame.setSize(width, 780); frame.setVisible(true); frame.validate();
        BufferedImage img = new BufferedImage(width, 780, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics(); frame.paint(g); g.dispose();
        try { ImageIO.write(img,"png",new File(filename)); } catch (Exception e) { throw new RuntimeException(e); }
    }
    @Test public void rosterSearchSortDeathNotesAndResponsiveScreens() throws Exception {
        CharacterJournal j = new CharacterJournal(Files.createTempDirectory("character-ui-").resolve("journal.json"));
        tomato.backend.data.RosterDefinitions definitions = CharacterRosterQueryTest.definitions();
        String account = j.observe(CharacterJournalTest.player("sample-account",782), 101);
        ArrayList<RealmCharacter> chars = new ArrayList<>();
        int[] classes = {782,768,775,784,800,801};
        for (int i = 0; i < classes.length; i++) {
            RealmCharacter c = new RealmCharacter(); c.classNum = (short)classes[i]; c.charId = 101+i;
            c.level = 20; c.seasonal = i % 2 == 0; c.fame = i == 0 ? 900 : 10000 + i;
            for (String field : new String[]{"class", "level", "seasonal", "fame"}) c.supplied(field);
            c.receivedAt = System.currentTimeMillis();
            int[] cap = new int[]{670,385,75,25,50,75,40,60};
            c.hp = cap[0]; c.mp = cap[1]; c.atk = cap[2]; c.def = cap[3]; c.spd = cap[4]; c.dex = cap[5]; c.vit = cap[6]; c.wis = cap[7];
            if (i == 0) { c.hp -= 20; c.wis -= 10; }
            c.capturedStatMask = 255; c.equipment = new int[]{12345, -1, 9999, -1, -1}; chars.add(c);
        }
        j.mergeRoster(account, chars); j.markDead(account+":104",true);
        Map<Integer,int[]> ex = new HashMap<>(); ex.put(782,new int[]{75,50,30,15,5,1,0,74}); j.exalts(account,ex);
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install(); CharacterJournalGUI panel = new CharacterJournalGUI(j, System::currentTimeMillis, () -> definitions);
            JTable roster = find(panel,JTable.class); assertEquals(6,roster.getRowCount());
            JTextField search = find(panel,JTextField.class);
            search.setText("["); assertEquals(0,roster.getRowCount());
            search.setText("12345"); assertEquals(6,roster.getRowCount()); search.setText("101"); assertEquals(1,roster.getRowCount());
            assertEquals(Integer.valueOf(6), roster.getValueAt(0,5));
            button(panel,"Mark dead").doClick(); assertEquals("Marked dead manually",roster.getValueAt(0,2));
            button(panel,"Restore alive").doClick(); assertEquals("Last observed alive",roster.getValueAt(0,2));
            JTextArea notes = notes(panel); notes.setText("Finish Life and Wisdom"); button(panel,"Save notes").doClick();
            assertEquals("Finish Life and Wisdom",j.characters().stream().filter(r -> r.characterId == 101).findFirst().get().notes);
            search.setText(""); roster.getRowSorter().toggleSortOrder(6);
            assertEquals(900L, roster.getValueAt(0,6));
            JComboBox<?> filter = find(panel,JComboBox.class); filter.setSelectedItem("Marked dead manually"); assertEquals(1,roster.getRowCount());
            filter.setSelectedItem("Not marked dead"); assertEquals(5,roster.getRowCount()); filter.setSelectedItem("All characters");
            search.setText("");
            JFrame frame = new JFrame("Characters — sample data"); frame.setContentPane(panel);
            try {
                render(frame,"characters-desktop.png",1080);
                render(frame,"characters-compact.png",680);
                assertTrue(button(panel,"Mark dead").getX() >= 0);
                JTabbedPane tabs = find(panel,JTabbedPane.class); tabs.setSelectedIndex(1); render(frame,"characters-equipment.png",1080);
                tabs.setSelectedIndex(2); render(frame,"characters-class-exalts.png",1080);
                frame.setContentPane(panel.exaltPanel()); render(frame,"characters-account-exalts.png",1080);
            } finally { frame.dispose(); }
        });
        j.close();
    }
}
