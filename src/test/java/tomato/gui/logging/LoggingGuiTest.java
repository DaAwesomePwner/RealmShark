package tomato.gui.logging;

import org.junit.Test;
import packets.PacketType;
import packets.incoming.*;
import packets.data.*;
import packets.outgoing.ForReconnectPacket;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.modern.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class LoggingGuiTest {
    @Test public void searchFilteringSelectionRefreshAndResponsiveScreens() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install();
            DiscoveryLog log = new DiscoveryLog(null); log.setSampleMillis(0);
            RealmScoreUpdatePacket score=new RealmScoreUpdatePacket(); score.score=2500;
            log.observe(169,9,score,"decoded",0); log.observe(255,9,null,"unknown-id",0);
            log.observe(255,9,null,"decode-error",0);
            ForReconnectPacket reconnect=new ForReconnectPacket(); reconnect.reconnectInfo=":USSouth:EUWest"; emit(log,reconnect);
            LoggingGUI panel=new LoggingGUI(log,LoggingStateTestSupport.memoryStore());
            JComponent[] pages=new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages,i->new JPanel()); pages[9]=panel;
            WorkspaceShell shell=new WorkspaceShell(pages,()->{},true); shell.select(9);
            JFrame frame=new JFrame("Logging · synthetic validation sample"); frame.setContentPane(shell); frame.setSize(1240,800); frame.setVisible(true);
            try {
                JTabbedPane tabs=find(panel,JTabbedPane.class); assertEquals(6,tabs.getTabCount());
                assertEquals("Re-entry trace",tabs.getTitleAt(1));
                tabs.setSelectedIndex(1); JTable trace=find((Container)tabs.getSelectedComponent(),JTable.class);
                await(()->trace.getRowCount()==1);
                assertEquals(1,trace.getRowCount()); trace.setRowSelectionInterval(0,0);
                JTextArea detail=named(panel,"logging-details",JTextArea.class);
                assertTrue(detail.getText().contains("serverNameCount")); assertFalse(detail.getText().contains("USSouth"));
                tabs.setSelectedIndex(2); JTable table=find((Container)tabs.getSelectedComponent(),JTable.class);
                JCheckBox observed=checkbox(panel,"Observed packets only"); observed.doClick(); assertEquals(3,table.getRowCount());
                JTextField search=named(panel,"logging-search",JTextField.class); search.setText("REALM_SCORE"); assertEquals(1,table.getRowCount());
                table.setRowSelectionInterval(0,0); panel.refresh(); assertEquals(0,table.getSelectedRow());
                assertTrue(detail.getText().contains("2500"));
                search.setText("["); assertEquals(0,table.getRowCount()); search.setText("");
                checkbox(panel,"Packet issues only").doClick(); assertEquals(1,table.getRowCount());
                assertEquals(255, table.getValueAt(0,0));
                checkbox(panel,"Packet issues only").doClick();
                assertNotNull(shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("alt 0")));
                for(int width:new int[]{1240,760}) {
                    frame.setSize(width,width==1240?800:680); frame.validate();
                    shell.dispatchEvent(new java.awt.event.ComponentEvent(shell,java.awt.event.ComponentEvent.COMPONENT_RESIZED)); frame.validate();
                    for(int tab=0;tab<tabs.getTabCount();tab++) {
                        tabs.setSelectedIndex(tab); frame.validate();
                        capture(frame,"logging-"+width+"-"+tab+".png");
                        assertTrue("Details must remain visible at " + width + ": " + detail.getParent().getHeight(),detail.getParent().getHeight()>=60);
                        assertTrue("Collection control must be visible",checkbox(panel,"Gameplay & diagnostics collection").isShowing());
                    }
                }
            } finally { frame.dispose(); log.close(); }
        });
    }
    @Test public void currentExportAndRefreshBlockedOnObserverDoNotBlockEdtAndFreezeStaysStable() throws Exception {
        DiscoveryLog log=new DiscoveryLog(null);LoggingGUI[] panel=new LoggingGUI[1];
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("logging-worker-export");
        java.util.concurrent.atomic.AtomicReference<SwingWorker<java.nio.file.Path,Void>> export=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";emit(log,map);
            SwingUtilities.invokeAndWait(()->{panel[0]=new LoggingGUI(log,LoggingStateTestSupport.memoryStore());find(panel[0],JTabbedPane.class).setSelectedIndex(4);panel[0].refresh();});
            await(()->find((Container)find(panel[0],JTabbedPane.class).getSelectedComponent(),JTable.class).getRowCount()==1);
            SwingUtilities.invokeAndWait(()->{checkbox(panel[0],"Pause this view").setSelected(true);checkbox(panel[0],"Pause this view").setSelected(true);});
            map.name="Ocean Trench";emit(log,map);
            synchronized(log){
                SwingUtilities.invokeAndWait(()->{
                    panel[0].refresh();assertEquals(1,find((Container)find(panel[0],JTabbedPane.class).getSelectedComponent(),JTable.class).getRowCount());
                    export.set(panel[0].exportTo(directory));assertNull(panel[0].exportTo(directory));
                    checkbox(panel[0],"Pause this view").setSelected(false); // Also queue a blocked diagnostic refresh.
                });
                java.util.concurrent.CountDownLatch heartbeat=new java.util.concurrent.CountDownLatch(1);SwingUtilities.invokeLater(heartbeat::countDown);
                assertTrue("export snapshot acquisition must be off EDT",heartbeat.await(2,java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(export.get().isDone());
            }
            java.nio.file.Path file=export.get().get(5,java.util.concurrent.TimeUnit.SECONDS);
            com.google.gson.JsonObject document=new com.google.gson.Gson().fromJson(new String(java.nio.file.Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8),com.google.gson.JsonObject.class);
            assertEquals(2,document.getAsJsonObject("observations").getAsJsonArray("events").size());
            assertTrue(document.getAsJsonObject("observations").get("activity").isJsonNull());
            assertEquals("CURRENT",document.getAsJsonObject("manifest").get("source").getAsString());
            await(()->find((Container)find(panel[0],JTabbedPane.class).getSelectedComponent(),JTable.class).getRowCount()==2);
        } finally {log.close();}
    }
    private static void emit(DiscoveryLog log,packets.Packet p) {
        log.observe(PacketType.byClass(p).getIndex(),30,p,"decoded",0);
    }
    private static void capture(JFrame frame,String name) {
        try {
            BufferedImage image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); frame.paint(g); g.dispose(); File dir=new File("screenshots"); dir.mkdirs(); ImageIO.write(image,"png",new File(dir,name));
        } catch(Exception e) {throw new AssertionError(e);}
    }
    private static JCheckBox checkbox(Container root,String text) {
        for(Component c:root.getComponents()) {if(c instanceof JCheckBox && ((JCheckBox)c).getText().equals(text))return (JCheckBox)c;
            if(c instanceof Container){JCheckBox found=checkbox((Container)c,text);if(found!=null)return found;}}return null;
    }
    private static <T> T find(Container root,Class<T> type) {return named(root,null,type);}
    private static <T> T named(Container root,String name,Class<T> type) {
        for(Component c:root.getComponents()) {if(type.isInstance(c)&&(name==null||name.equals(c.getName())))return type.cast(c);
            if(c instanceof Container){T found=named((Container)c,name,type);if(found!=null)return found;}}return null;
    }
}
