package tomato.gui.bridge;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.bridge.*;
import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;

/** Synthetic in-process transport only; no endpoint is contacted. */
public class BridgeReportingTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private BridgeConfig config(boolean sending)throws Exception{
        Path csv=temp.newFile().toPath();Files.write(csv,"Item Name\nSynthetic Sword\n".getBytes(StandardCharsets.UTF_8));
        Properties p=new Properties();String prefix=BridgeConfig.PREFIX;
        p.setProperty(prefix+"enabled","true");p.setProperty(prefix+"send",String.valueOf(sending));p.setProperty(prefix+"endpoint","https://example.invalid/ingest");
        p.setProperty(prefix+"guild_id","123456789012345678");p.setProperty(prefix+"link_token","synthetic-token");p.setProperty(prefix+"csv_path",csv.toString());return new BridgeConfig(p);
    }
    private static BridgePayload.Drop drop(int id,int character,String name,String map,String enchants){return new BridgePayload.Drop(new BridgePayload.Item(id,name,"EQUIPMENT","UT",enchants,false),character,"Example","Wizard",map,false,false,9,0);}
    private BridgeService service(BridgeService.Transport transport,int capacity){return new BridgeService(temp.getRoot().toPath().resolve("bridge.properties"),false,transport,capacity);}
    @Test public void strictResponsesSplitAllOutcomesAndLifetimeCountsSurviveEviction()throws Exception{
        String[] replies={"{\"result\":{\"logged\":true}}","{\"result\":{\"logged\":false,\"reason\":\"unmapped_character\"}}","{}","{\"logged\":\"false\"}","{\"logged\":null}","{\"ok\":false,\"logged\":true}"};AtomicInteger next=new AtomicInteger();
        try(BridgeService s=service((url,json)->new BridgeService.Response(200,replies[next.getAndIncrement()]),2048)){
            s.configure(config(true),false,false);
            for(int i=0;i<replies.length;i++)s.receive(Collections.singletonList(drop(42,7,"Synthetic Sword","Ice Citadel","")));
            s.awaitIdle(5000);BridgeService.Snapshot snapshot=s.snapshot();
            assertEquals(1,snapshot.count(BridgeService.Outcome.LOGGED));assertEquals(1,snapshot.count(BridgeService.Outcome.NOT_LOGGED));assertEquals(3,snapshot.count(BridgeService.Outcome.RECEIVED));assertEquals(1,snapshot.count(BridgeService.Outcome.FAILED));assertEquals(0,snapshot.count(BridgeService.Outcome.PENDING));
            assertTrue(snapshot.reviews.get(1).matches("unmapped_character"));assertTrue(snapshot.reviews.get(1).nextStep().contains("/mysniffer → Configure Character"));
            assertTrue(snapshot.reviews.get(2).detail.contains("does not confirm loot logging"));
            s.configure(config(false),false,false);
            for(int i=0;i<1010;i++)s.receive(Collections.singletonList(drop(42,7,"Synthetic Sword","Ice Citadel","")));
            s.awaitIdle(5000);snapshot=s.snapshot();assertEquals(1000,snapshot.reviews.size());assertEquals(1016,snapshot.observed);
            assertEquals(1010,snapshot.count(BridgeService.Outcome.LOCAL));assertEquals(1,snapshot.count(BridgeService.Outcome.LOGGED));
            assertEquals(snapshot.observed,snapshot.outcomes.values().stream().mapToLong(Long::longValue).sum());assertEquals(replies.length,next.get());
        }
    }
    @Test public void queueOverflowMovesOneBucketAndCancellationIsLocal()throws Exception{
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try(BridgeService s=service((u,j)->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new BridgeService.Response(200,"{\"logged\":true}");},1)){
            s.configure(config(true),false,false);s.receive(Collections.singletonList(drop(1,1,"Synthetic Sword","Ice Citadel","")));assertTrue(started.await(2,TimeUnit.SECONDS));
            s.receive(Arrays.asList(drop(2,1,"Synthetic Sword","Ice Citadel",""),drop(3,1,"Synthetic Sword","Ice Citadel","")));
            BridgeService.Snapshot queued=s.snapshot();assertEquals(2,queued.count(BridgeService.Outcome.PENDING));assertEquals(1,queued.count(BridgeService.Outcome.FAILED));
            s.configure(config(false),false,false);
            s.receive(Collections.singletonList(drop(4,1,"Synthetic Sword","Ice Citadel","")));
            assertEquals("Local only",s.snapshot().reviews.get(3).status);assertTrue(s.snapshot().reviews.get(3).detail.contains("audit queue full"));
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(s.snapshot().count(BridgeService.Outcome.PENDING)>0&&System.nanoTime()<deadline)Thread.sleep(10);
            BridgeService.Snapshot done=s.snapshot();assertEquals(1,done.count(BridgeService.Outcome.LOGGED));assertEquals(2,done.count(BridgeService.Outcome.LOCAL));assertEquals(1,done.count(BridgeService.Outcome.FAILED));assertEquals(0,done.count(BridgeService.Outcome.PENDING));
        }finally{release.countDown();}
    }
    @Test public void retainedSearchFacetsDetailsAndShownCountsUseTheSameRows()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        try(BridgeService s=service((u,j)->{calls.incrementAndGet();return new BridgeService.Response(200,"{\"logged\":false,\"reason\":\"unmapped_character\"}");},16)){
            s.configure(config(true),false,false);s.receive(Arrays.asList(drop(42,7,"Synthetic Sword","Ice Citadel","Frost +5"),drop(142,8,"Synthetic Sword","Lost Halls","")));
            BridgePayload.Item malformed=new BridgePayload.Item(242,"Synthetic Sword","EQUIPMENT","UT","Malformed",true);
            s.receive(Collections.singletonList(new BridgePayload.Drop(malformed,9,"Example","Wizard","Ice Citadel",false,false,10,0)));s.awaitIdle(3000);
            SwingUtilities.invokeAndWait(()->{
                BridgeReviewGUI view=new BridgeReviewGUI(s);JTable table=named(view,"bridge-review-table",JTable.class);JTextField search=named(view,"bridge-search",JTextField.class);
                search.setText("unmapped_character");assertEquals(3,table.getRowCount());search.setText("Frost +5");assertEquals(1,table.getRowCount());
                table.setRowSelectionInterval(0,0);String details=named(view,"bridge-details",JTextArea.class).getText();assertTrue(details.contains("Observation:"));assertTrue(details.contains("Local choice at observation: Queued"));assertTrue(details.contains("Configure Character"));assertFalse(details.contains("synthetic-token"));
                search.setText("142");assertEquals(1,table.getRowCount());search.setText("");
                JComboBox<?> character=named(view,"bridge-character-filter",JComboBox.class);character.setSelectedItem("Example #7");assertEquals(1,table.getRowCount());
                JComboBox<?> dungeon=named(view,"bridge-dungeon-filter",JComboBox.class);dungeon.setSelectedItem("Lost Halls");assertEquals(0,table.getRowCount());dungeon.setSelectedIndex(0);
                JComboBox<?> enchants=named(view,"bridge-enchant-filter",JComboBox.class);enchants.setSelectedIndex(2);assertEquals(0,table.getRowCount());enchants.setSelectedIndex(1);assertEquals(1,table.getRowCount());
                JComboBox<?> outcome=named(view,"bridge-outcome-filter",JComboBox.class);outcome.setSelectedItem(BridgeService.Outcome.LOGGED);assertEquals(0,table.getRowCount());outcome.setSelectedItem(BridgeService.Outcome.NOT_LOGGED);assertEquals(1,table.getRowCount());
                assertTrue(named(view,"bridge-totals",JTextArea.class).getText().contains("Shown: 1 / 3 retained items"));
                character.setSelectedIndex(0);enchants.setSelectedIndex(3);assertEquals(1,table.getRowCount());table.setRowSelectionInterval(0,0);
                assertTrue(named(view,"bridge-details",JTextArea.class).getText().contains("Enchant count: unknown"));
                enchants.setSelectedIndex(2);assertEquals(1,table.getRowCount());assertEquals("Example #8",table.getValueAt(0,4));
            });assertEquals(3,calls.get());
        }
    }
}
