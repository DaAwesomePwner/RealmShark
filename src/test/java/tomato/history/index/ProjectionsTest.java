package tomato.history.index;

import com.google.gson.*;
import java.util.*;
import org.junit.Test;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

public class ProjectionsTest {
    static final String SESSION="00000000-0000-0000-0000-000000000001";
    static final String HASH="a".repeat(64);
    static JsonElement json(String value) { return SessionStore.JSON.fromJson(value,JsonElement.class); }
    static List<Projections.Row> project(String module,String value) {
        return Projections.project(new Projections.Context(new Locator(SESSION,module,0,null,-1),500,false,"test"),json(value));
    }
    @Test public void projectsEveryModuleAndPreservesUnknowns() {
        assertEquals("Launch",project("session","{label:'Launch',version:'v',started:1,ended:5}").get(0).title());
        Projections.Row run=project("runs","{id:'r',map:'Oryx Sanctuary',started:1,lastSeen:4,inspectedPlayers:{'player:alice':{stats:[{statType:'NAME_STAT',stringStatValue:'Alice'}],className:'Wizard'}},playerDamage:{'player:bob':9,'object:42':2}}").get(0);
        assertEquals("App ended",run.columns().get("outcome")); assertEquals(3L,run.columns().get("duration"));
        assertNull(run.columns().get("damage")); assertEquals(Set.of("player:alice","player:bob"),run.players().keySet());
        assertTrue(run.body().contains("Wizard")); assertFalse(run.body().contains("object:42"));
        assertEquals("Completed",project("runs","{id:'r',completionEvidence:'Server victory'}").get(0).columns().get("outcome"));
        List<Projections.Row> loot=project("loot","{bag:'B.White',dungeon:'Unknown',items:[{id:1,name:'Ancient Stone Sword',tier:'UT',ut:true,enchants:{slots:-1,applied:-1}}]}");
        assertEquals(2,loot.size()); assertEquals(true,loot.get(0).columns().get("white")); assertNull(loot.get(0).columns().get("dungeon"));
        assertNull(loot.get(1).columns().get("enchant_slots")); assertEquals(0,loot.get(1).locator().itemPosition());
        assertEquals("Unknown",loot.get(1).columns().get("rarity"));
        assertEquals("Area entered",project("timeline","{kind:'Area entered',map:'Oryx',visitId:'r'}").get(0).title());
        Projections.Row chat=project("chat","{id:'c',received:'2026-01-01T12:30:00',sender:' Alice, decorated ',recipient:'Bob',text:'hello',channel:'PM'}").get(0);
        assertEquals("2026-01-01T12:30:00",chat.columns().get("received")); assertEquals(Set.of("player:alice"),chat.players().keySet());
        assertEquals(1,project("chat-stars","{id:'c',changed:2,starred:false}").size());
        assertEquals(Set.of("player:bob"),project("keypops","{id:'k',time:'2026-01-01T00:00:00Z',player:'Bob',item:'Oryx',kind:'KEY'}").get(0).players().keySet());
        for (String module:List.of("fame","fame-latest")) {
            Projections.Row fame=project(module,"{account:'"+HASH+"',character:5,className:'Wizard',fame:12,time:3}").get(0);
            assertEquals(HASH,fame.columns().get("account")); assertTrue(fame.players().isEmpty()); assertFalse(fame.body().contains(HASH));
            assertNull(project(module,"{account:'INVALID',character:5}").get(0).columns().get("account"));
        }
        assertEquals(2,project("fame-snapshots","{characterFameData:{'5':[{fame:4,time:1},{fame:8,time:2}]},characterClassNames:{'5':'Wizard'}}").size());
        Projections.Row combat=project("encounters","{recordingId:'combat',map:'Oryx',players:[{name:'Cara'}],bosses:[{name:'Oryx the Mad God'}]}").get(0);
        assertEquals(Set.of("player:cara"),combat.players().keySet()); assertTrue(combat.names().contains("Mad God"));
        assertEquals(7L,project("dungeon-totals","[{name:'Oryx',visits:2,time:10,hits:{'1':7},loot:{'1':{'2':3}}}]").get(0).columns().get("hits"));
        assertEquals(0,project("unknown","{}").size());
    }
    @Test public void exactVisitOnlyAndDiagnosticExclusion() {
        for (String kind:List.of("Resources","Capture issue","Ownership check")) assertTrue(project("timeline","{kind:'"+kind+"',detail:'secret'}").isEmpty());
        String linked="{items:[],visitId:'legacy',context:{visit:{sessionId:'"+SESSION+"',visitId:'exact'}}}";
        assertEquals("exact",project("loot",linked).get(0).visitId());
        assertNull(project("loot","{items:[],visitId:'legacy'}").get(0).visitId());
        assertNull(project("encounters","{recordingId:'x',visitSession:'other',visitId:'r'}").get(0).visitId());
        assertEquals("r",project("fame","{visitSession:'"+SESSION+"',visitId:'r'}").get(0).visitId());
        Locator checkpoint=new Locator(SESSION,"timeline",-1,SessionStore.checkpointName("legacy"),-1);
        assertEquals(checkpoint,Projections.project(new Projections.Context(checkpoint,1,false,"Imported"),json("{kind:'Area entered'}")).get(0).locator());
    }
    @Test public void hashNeverBecomesSearchTextOrPlayer() {
        Projections.Row fame=project("fame","{account:'"+HASH+"',className:'"+HASH+"',map:'"+HASH+"'}").get(0);
        assertEquals(HASH,fame.columns().get("account")); assertFalse(fame.title().contains(HASH)); assertFalse(fame.names().contains(HASH));
        assertFalse(fame.columns().get("class_name").toString().contains(HASH));
        assertTrue(project("keypops","{player:'"+HASH+"',item:'Key'}").get(0).players().isEmpty());
        assertNull(project("fame","{account:'"+HASH.toUpperCase(Locale.ROOT)+"'}").get(0).columns().get("account"));
    }
    @Test public void missingOptionalFieldsStayUsable() {
        for (String module:List.of("chat","chat-stars","keypops","fame","fame-latest","timeline")) assertEquals(1,project(module,"{}").size());
        assertEquals(1,project("loot","{items:[]}").size());
        assertEquals(1,project("runs","{id:'r',inspectedPlayers:null,playerDamage:null}").size());
        assertEquals(1,project("encounters","{recordingId:'r',players:null,bosses:null}").size());
    }
}
