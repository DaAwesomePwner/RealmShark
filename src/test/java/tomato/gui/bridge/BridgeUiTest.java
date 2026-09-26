package tomato.gui.bridge;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.bridge.*;
import tomato.gui.modern.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import static org.junit.Assert.*;

public class BridgeUiTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void reviewFiltersDrilldownMaskedSettingsAndLogsWorkInVioletWorkspace()throws Exception {
        Path catalog=temp.newFile("items.csv").toPath();Files.write(catalog,"Item Name\nTest Sword\n".getBytes(StandardCharsets.UTF_8));
        Properties p=new Properties();p.setProperty(BridgeConfig.PREFIX+"enabled","true");p.setProperty(BridgeConfig.PREFIX+"endpoint","https://example.invalid/ingest");p.setProperty(BridgeConfig.PREFIX+"guild_id","123456789012345678");p.setProperty(BridgeConfig.PREFIX+"link_token","test-secret");p.setProperty(BridgeConfig.PREFIX+"csv_path",catalog.toString());p.setProperty(BridgeConfig.PREFIX+"debug","true");
        try(BridgeService service=new BridgeService(temp.getRoot().toPath().resolve("bridge.properties"),false,(url,json)->new BridgeService.Response(200,"{\"ok\":true,\"result\":{\"logged\":true}}"),20)){
            service.configure(new BridgeConfig(p),false,false);
            service.receive(Arrays.asList(drop("Test Sword (Shiny)","Damage Boost(1)\nLoot Bonus(2)"),drop("Unlisted ST","")));service.awaitIdle(3000);
            SwingUtilities.invokeAndWait(()->{
                VioletTheme.install();BridgeReviewGUI panel=new BridgeReviewGUI(service);JComponent[] pages=new JComponent[WorkspaceShell.TITLES.length];Arrays.fill(pages,new JPanel());pages[pages.length-1]=panel;
                // Each CardLayout page must own a different component.
                for(int i=0;i<pages.length-1;i++)pages[i]=new JPanel();
                WorkspaceShell shell=new WorkspaceShell(pages,()->{},true);JFrame frame=new JFrame("Bridge preview");frame.setContentPane(shell);
                try {
                    frame.setSize(1240,800);frame.setVisible(true);shell.select(pages.length-1);frame.validate();
                    JTable table=(JTable)find(panel,"bridge-review-table");assertEquals(2,table.getRowCount());
                    JTextField search=(JTextField)find(panel,"bridge-search");search.setText("Shiny");assertEquals(1,table.getRowCount());table.setRowSelectionInterval(0,0);
                    JTextArea details=(JTextArea)find(panel,"bridge-details");assertTrue(details.getText().contains("Damage Boost"));assertTrue(details.getText().contains("[redacted]"));assertFalse(details.getText().contains("test-secret"));
                    search.setText("[");assertEquals(0,table.getRowCount());search.setText("");
                    JComboBox<?> filter=(JComboBox<?>)find(panel,"bridge-status-filter");filter.setSelectedItem("Not in CSV");assertEquals(1,table.getRowCount());filter.setSelectedIndex(0);
                    JPasswordField token=(JPasswordField)find(panel,"bridge-token");assertTrue(token.echoCharIsSet());
                    JTabbedPane tabs=findType(panel,JTabbedPane.class);assertEquals(4,tabs.getTabCount());
                    JTable logs=(JTable)find(panel,"bridge-log-table");logs.setRowSelectionInterval(2,2);JTextArea logDetail=(JTextArea)find(panel,"bridge-log-details");assertTrue(logDetail.getText().contains("POST"));assertTrue(logDetail.getText().contains("[redacted]"));assertFalse(logDetail.getText().contains("test-secret"));
                    for(int width:new int[]{1240,680}){
                        frame.setSize(width,width==680?620:800);frame.validate();shell.dispatchEvent(new java.awt.event.ComponentEvent(shell,java.awt.event.ComponentEvent.COMPONENT_RESIZED));frame.validate();shell.select(pages.length-1);
                        // Native scaled runs clamp/scale the realized client, so compact mode follows the shell's actual width.
                        assertEquals("Native compact mode follows the realized client",shell.getWidth()<1000,shell.isCompact());if(width==680)assertTrue("The compact layout is exercised",shell.isCompact());
                        for(int tab=0;tab<tabs.getTabCount();tab++){
                            tabs.setSelectedIndex(tab);frame.validate();
                            if(tab==1){JTextField field=(JTextField)find(panel,"bridge-endpoint");assertTrue(field.getWidth()>140);}
                            BufferedImage image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();frame.printAll(g);g.dispose();
                            Path dir=Paths.get("screenshots");Files.createDirectories(dir);ImageIO.write(image,"png",dir.resolve("bridge-"+width+"-"+tab+".png").toFile());
                        }
                    }
                } catch(Exception ex){throw new AssertionError(ex);} finally{frame.dispose();}
            });
        }
    }
    private static BridgePayload.Drop drop(String name,String enchant){return new BridgePayload.Drop(new BridgePayload.Item(42,name,"EQUIPMENT","UT",enchant,false),12,"Example","Wizard","The Shatters",true,false,1,0);}
    private static Component find(Container root,String name){for(Component c:root.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container){Component found=find((Container)c,name);if(found!=null)return found;}}return null;}
    private static <T> T findType(Container root,Class<T> type){for(Component c:root.getComponents()){if(type.isInstance(c))return type.cast(c);if(c instanceof Container){T found=findType((Container)c,type);if(found!=null)return found;}}return null;}
}
