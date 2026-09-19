package tomato.bridge;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.gui.bridge.BridgeReviewGUI;
import static org.junit.Assert.*;

public class BridgeTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String SECRET="fixture-private-token";
    private Path csv()throws Exception {Path p=temp.newFile().toPath();Files.write(p,"Loot Type,Item Name,Points,Dungeon\nUT,Test Sword,1,Test\n".getBytes(StandardCharsets.UTF_8));return p;}
    private BridgeConfig config(Path csv,String endpoint){Properties p=new Properties();p.setProperty(BridgeConfig.PREFIX+"enabled","true");p.setProperty(BridgeConfig.PREFIX+"endpoint",endpoint);p.setProperty(BridgeConfig.PREFIX+"guild_id","123456789012345678");p.setProperty(BridgeConfig.PREFIX+"link_token",SECRET);p.setProperty(BridgeConfig.PREFIX+"csv_path",csv.toString());p.setProperty(BridgeConfig.PREFIX+"debug","true");return new BridgeConfig(p);}
    private BridgePayload.Drop drop(String name,String enchants){return new BridgePayload.Drop(new BridgePayload.Item(42,name,"EQUIPMENT","UT",enchants,false),7,"Example","Wizard","Test Dungeon",true,false,9,0);}
    private BridgeService service(BridgeService.Transport transport,int capacity){return new BridgeService(temp.getRoot().toPath().resolve("bridge.properties"),false,transport,capacity);}

    @Test public void goldenWireFormatMatchesPublicBridgeFieldsAndTypes()throws Exception {
        BridgeConfig c=config(csv(),"https://example.invalid/realmshark/ingest");
        BridgePayload.Drop d=drop("Test Sword (Shiny)","one\ntwo\nthree\nfour");
        JsonObject expected=JsonParser.parseString("{\"guild_id\":123456789012345678,\"link_token\":\"fixture-private-token\",\"item_name\":\"Test Sword\",\"shiny\":true,\"item_id\":42,\"character_id\":7,\"character_name\":\"Example\",\"character_class\":\"Wizard\",\"item_group\":\"EQUIPMENT\",\"item_label\":\"UT\",\"item_rarity\":\"divine\",\"divine\":true,\"dungeon\":\"Test Dungeon\",\"is_seasonal\":true,\"loot_drop_bonus\":false,\"source\":\"tomato\"}").getAsJsonObject();
        assertEquals(expected,BridgePayload.loot(c,d));
        assertEquals(JsonParser.parseString("{\"guild_id\":123456789012345678,\"link_token\":\"fixture-private-token\",\"event_type\":\"bridge_settings_test\",\"source\":\"tomato\"}"),BridgePayload.settingsPing(c));
        assertFalse(BridgePayload.redacted(expected).toString().contains(SECRET));assertEquals(SECRET,expected.get("link_token").getAsString());
        BridgePayload.Drop unknown=new BridgePayload.Drop(d.item,-1,null,"","",false,false,1,0);
        JsonObject p=BridgePayload.loot(c,unknown);assertFalse(p.has("character_id"));assertFalse(p.has("character_name"));assertFalse(p.has("dungeon"));
    }
    @Test public void rarityAndShinyRulesMatchUpstreamIncludingMetadataPrecedence(){
        String[] rarities={"common","uncommon","rare","legendary","divine"};
        for(int i=0;i<5;i++)assertEquals(rarities[i],new BridgePayload.Item(1,"Sword","","",String.join("\n",Collections.nCopies(i,"enchant")),false).rarity);
        assertEquals("common",drop("Sword","[locked]").item.rarity);assertEquals("common",drop("Sword","empty").item.rarity);
        BridgePayload.Item metadata=new BridgePayload.Item(1,"Sword","SHINY EQUIPMENT","UT RARE","four\nthree\ntwo\none",false);
        assertEquals("rare",metadata.rarity);assertTrue(metadata.shiny);assertFalse(metadata.divine);
        assertEquals("unknown",new BridgePayload.Item(1,"Sword","","","malformed",true).rarity);
        assertTrue(new BridgePayload.Item(1,"Divine Sword","","","",false).divine);
    }
    @Test public void snapshotDetachesBagAndPreservesEnchantSlotAlignment(){
        Entity bag=new Entity(null,9,0),player=new Entity(null,8,0);
        StatData item=new StatData();item.statValue=42;bag.stat.set(StatType.INVENTORY_0_STAT,item);
        ByteBuffer bytes=ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);bytes.put((byte)0).putShort((short)1026).putShort((short)1).putShort((short)2).putShort((short)3).putShort((short)4);
        StatData u=new StatData();u.stringStatValue=Base64.getUrlEncoder().encodeToString(bytes.array())+",bad";bag.stat.set(StatType.UNIQUE_DATA_STRING,u);
        StatData second=new StatData();second.statValue=43;bag.stat.set(StatType.INVENTORY_1_STAT,second);
        List<BridgePayload.Drop> d=BridgePayload.snapshot(null,null,bag,player,0);item.statValue=-1;u.stringStatValue="";
        assertEquals(2,d.size());assertEquals(42,d.get(0).item.id);assertEquals("divine",d.get(0).item.rarity);assertEquals(-1,d.get(1).item.enchantCount);assertEquals(1,d.get(1).slot);
    }
    @Test public void csvHandlesBomQuotesMultilineUnicodeAndShinyBaseMatching()throws Exception {
        Path p=temp.newFile().toPath();Files.write(p,("\uFEFF\"Item Name\",Points\r\n\"Oryx’s  Blade – Gold\",1\r\n\"Sword, \"\"Great\"\"\",2\r\n\"Test\nSword\",1\r\n").getBytes(StandardCharsets.UTF_8));
        BridgeCatalog c=BridgeCatalog.load(p);assertEquals(3,c.size());
        assertTrue(c.contains(drop("Oryx's Blade-Gold (Shiny)","").item));assertTrue(c.contains(drop("Sword, \"Great\"","").item));assertTrue(c.contains(drop("Test Sword","").item));
        Files.write(p,"Item Name\n\"unclosed".getBytes(StandardCharsets.UTF_8));try{BridgeCatalog.load(p);fail();}catch(IOException expected){}
        Files.write(p,"Name\nWrong".getBytes(StandardCharsets.UTF_8));try{BridgeCatalog.load(p);fail();}catch(IOException expected){}
    }
    @Test public void configRoundTripsWithoutTouchingGeneralPreferencesAndValidatesBeforeSaving()throws Exception {
        Path root=temp.getRoot().toPath(),settings=root.resolve("bridge.properties"),original=root.resolve("realmShark.properties");Files.write(original,"fontSize=16\n".getBytes(StandardCharsets.UTF_8));
        BridgeConfig c=config(csv(),"https://example.invalid/realmshark/ingest");c.validate();c.save(settings);assertEquals(c.properties(),BridgeConfig.load(settings).properties());assertEquals("fontSize=16\n",new String(Files.readAllBytes(original),StandardCharsets.UTF_8));
        for(String endpoint:new String[]{"http://example.com/ingest","https://user:pass@example.com/ingest","file:///tmp/a","https://example.com/?token=secret"}){try{config(Paths.get(c.csvPath),endpoint).validate();fail(endpoint);}catch(IllegalArgumentException expected){}}
        Properties props=c.properties();props.setProperty(BridgeConfig.PREFIX+"local_review_log",c.csvPath);try{new BridgeConfig(props).validate();fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void actualHttpHasExactUtf8BodyNoRedirectAndReportsBotOutcome()throws Exception {
        List<String> bodies=new CopyOnWriteArrayList<>(),types=new CopyOnWriteArrayList<>();AtomicInteger redirects=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ingest",ex->{types.add(ex.getRequestHeaders().getFirst("Content-Type"));bodies.add(new String(read(ex.getRequestBody()),StandardCharsets.UTF_8));byte[] out="{\"ok\":true,\"result\":{\"logged\":false,\"reason\":\"item_not_in_rotmg_loot_drops_updated\"}}".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,out.length);ex.getResponseBody().write(out);ex.close();});
        server.createContext("/redirect",ex->{ex.getResponseHeaders().add("Location","/sink");ex.sendResponseHeaders(307,-1);ex.close();});
        server.createContext("/sink",ex->{redirects.incrementAndGet();ex.sendResponseHeaders(200,-1);ex.close();});server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        try(BridgeService s=service(new BridgeHttp(),10)) {
            BridgeConfig c=config(csv(),base+"/ingest");s.configure(c,false,true);s.receive(Collections.singletonList(drop("Test Sword","")));s.awaitIdle(5000);
            assertEquals(2,bodies.size());assertEquals("application/json; charset=UTF-8",types.get(0));assertEquals(BridgePayload.loot(c,drop("Test Sword","")),JsonParser.parseString(bodies.get(1)));
            assertEquals("Not logged",s.snapshot().reviews.get(0).status);assertTrue(s.snapshot().reviews.get(0).detail.contains("item_not_in"));
            assertEquals(307,new BridgeHttp().post(base+"/redirect","{}").status);assertEquals(0,redirects.get());
        }finally{server.stop(0);}
    }
    @Test public void disabledPreviewLocalOnlyCsvAndCategoryGatesNeverSendUnexpectedLoot()throws Exception {
        AtomicInteger calls=new AtomicInteger();BridgeService.Transport transport=(url,json)->{calls.incrementAndGet();return new BridgeService.Response(200,"{}");};
        try(BridgeService s=service(transport,20)) {
            s.receive(Collections.singletonList(drop("Test Sword","")));assertEquals(0,s.snapshot().observed);
            Properties p=config(csv(),"https://example.invalid/ingest").properties();p.setProperty(BridgeConfig.PREFIX+"send","false");s.configure(new BridgeConfig(p),false,false);
            s.receive(Arrays.asList(drop("Test Sword",""),drop("Unlisted UT","")));s.awaitIdle(3000);assertEquals("Local only",s.snapshot().reviews.get(0).status);assertEquals("Not in CSV",s.snapshot().reviews.get(1).status);
            p.setProperty(BridgeConfig.PREFIX+"send","true");p.setProperty(BridgeConfig.PREFIX+"filter.ut","false");s.configure(new BridgeConfig(p),false,false);s.receive(Collections.singletonList(drop("Test Sword","")));s.awaitIdle(3000);assertEquals("Filtered",s.snapshot().reviews.get(2).status);assertEquals(0,calls.get());
        }
        try(BridgeService preview=new BridgeService(temp.getRoot().toPath().resolve("preview.properties"),true,transport,10)){preview.receive(Collections.singletonList(drop("Test Sword","")));assertEquals(0,preview.snapshot().observed);try{preview.configure(config(csv(),"https://example.invalid"),true,true);fail();}catch(IOException expected){}assertEquals(0,calls.get());}
    }
    @Test public void changingSettingsCancelsOldQueueWithoutRetargetingItAndDisableStopsPendingSends()throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);List<String> destinations=new CopyOnWriteArrayList<>();
        try(BridgeService s=service((url,json)->{destinations.add(url);if(destinations.size()==1){started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}}return new BridgeService.Response(200,"{}");},10)) {
            BridgeConfig c=config(csv(),"https://first.invalid/ingest");s.configure(c,false,false);s.receive(Collections.singletonList(drop("Test Sword","")));assertTrue(started.await(2,TimeUnit.SECONDS));s.receive(Collections.singletonList(drop("Test Sword","")));
            s.configure(config(Paths.get(c.csvPath),"https://second.invalid/ingest"),false,false);release.countDown();s.awaitIdle(5000);assertEquals(Collections.singletonList(c.endpoint),destinations);assertEquals("Cancelled",s.snapshot().reviews.get(1).status);
            s.configure(new BridgeConfig(new Properties()),false,false);s.receive(Collections.singletonList(drop("Test Sword","")));assertEquals(2,s.snapshot().observed);
        } finally {release.countDown();}
    }
    @Test public void queueOverflowAndUncertainDeliveryAreVisibleWithoutAutomaticRetry()throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        try(BridgeService s=service((url,json)->{calls.incrementAndGet();started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}throw new SocketTimeoutException(SECRET);},1)) {
            s.configure(config(csv(),"https://example.invalid/ingest"),false,false);s.receive(Collections.singletonList(drop("Test Sword","")));assertTrue(started.await(2,TimeUnit.SECONDS));s.receive(Arrays.asList(drop("Test Sword",""),drop("Test Sword","")));assertEquals("Queue full",s.snapshot().reviews.get(2).status);release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(s.snapshot().failed<3&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(2,calls.get());assertEquals("Uncertain",s.snapshot().reviews.get(0).status);assertEquals(3,s.snapshot().failed);for(BridgeService.Log log:s.snapshot().logs)assertFalse(log.message.contains(SECRET));
        }finally{release.countDown();}
    }
    @Test public void failedCsvReloadKeepsPreviousConfigAndSecondInstanceCannotEnable()throws Exception {
        BridgeConfig c=config(csv(),"https://example.invalid/ingest");
        try(BridgeService s=service((u,j)->new BridgeService.Response(200,"{}"),10);BridgeService second=service((u,j)->new BridgeService.Response(200,"{}"),10)) {
            s.configure(c,false,false);try{second.configure(c,false,false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Another instance"));}
            try{s.configure(config(temp.getRoot().toPath().resolve("missing.csv"),c.endpoint),false,false);fail();}catch(IOException expected){}assertSame(c,s.config());
            s.configure(new BridgeConfig(new Properties()),false,false);second.configure(c,false,false);
        }
    }
    @Test public void reviewAndDiagnosticsExportsKeepEnchantDetailButNeverLinkToken()throws Exception {
        Path journal=temp.getRoot().toPath().resolve("review.jsonl");Properties p=config(csv(),"https://example.invalid/ingest").properties();p.setProperty(BridgeConfig.PREFIX+"local_review_log",journal.toString());
        try(BridgeService s=service((u,j)->new BridgeService.Response(403,"{\"ok\":false,\"error\":\"invalid_link_token\",\"message\":\""+SECRET+"\"}"),10)) {
            s.configure(new BridgeConfig(p),false,false);s.receive(Collections.singletonList(drop("Test Sword","First\nSecond")));s.awaitIdle(3000);assertEquals("Rejected",s.snapshot().reviews.get(0).status);
            String export=BridgeReviewGUI.reviewCsv(s.snapshot().reviews);assertTrue(export.contains("First\nSecond"));assertFalse(export.contains(SECRET));String disk=new String(Files.readAllBytes(journal),StandardCharsets.UTF_8);assertFalse(disk.contains(SECRET));assertTrue(disk.contains("[redacted]"));for(BridgeService.Log log:s.snapshot().logs)assertFalse(log.message.contains(SECRET));
        }
    }
    private static byte[] read(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
}
