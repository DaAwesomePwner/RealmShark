package tomato.history.index;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DictionaryProjectionsTest {
    static IndexDictionary dictionary(String version,String enchant) {
        return new IndexDictionary() {
            public String version() { return version; }
            public String objectName(int id) { return Map.of(101,"Moonblade",102,"Sunshield",103,"Starcloak",201,"Ancient Guardian").get(id); }
            public String className(int id) { return id==301?"Spellweaver":null; }
            public String enchantName(int id) { return id==401?enchant:null; }
        };
    }
    private static List<Projections.Row> project(String module,String json,IndexDictionary dictionary) {
        return Projections.project(new Projections.Context(new Locator(ProjectionsTest.SESSION,module,0,null,-1),500,false,"test",dictionary),ProjectionsTest.json(json));
    }
    private static Projections.Row project(String module,String json) {
        List<Projections.Row> rows=project(module,json,dictionary("v1","Astral Blessing"));
        return rows.get(rows.size()-1);
    }
    private static void searchable(Projections.Row row,String... names) {
        for (String name:names) {
            assertTrue(name+" missing from docs",row.body().contains(name));
            assertTrue(name+" missing from names",row.names().contains(name));
        }
    }
    @Test public void enchantIdsAndSavedLegacyTextAreSearchableButCountsAreNotNames() {
        Projections.Row item=project("loot","{items:[{id:101,name:'Saved Blade',enchantEvidence:{state:'RECORDED',orderedSlotIds:[401,-1,-2,-3,401],slots:2,applied:1}}]}");
        searchable(item,"Astral Blessing"); assertEquals("Saved Blade",item.title()); assertFalse(item.names().contains("Moonblade"));
        searchable(project("loot","{items:[{name:'Saved Blade',enchants:'Legacy Blessing'}]}"),"Legacy Blessing");
        Projections.Row counts=project("loot","{items:[{name:'Saved Blade',enchants:{slots:1,applied:1}}]}");
        assertFalse(counts.body().contains("Blessing"));
        Projections.Row exact=project("loot","{items:[{enchantEvidence:{orderedSlotIds:[401]},enchants:'Legacy Blessing'}]}");
        searchable(exact,"Astral Blessing"); assertFalse(exact.body().contains("Legacy Blessing"));
    }
    @Test public void rosterEquipmentRequestedItemsAndClassUseOnlyTheirTypedFields() {
        Projections.Row run=project("runs","{id:'r',requestedItems:{103:2},inspectedPlayers:{'player:alice':{objectType:301,stats:[{statType:'NAME_STAT',stringStatValue:'Alice'},{statType:'INVENTORY_0_STAT',statValue:101},{statTypeNum:11,statValue:102},{statTypeNum:12,statValue:201}]}}}");
        searchable(run,"Spellweaver","Moonblade","Sunshield","Starcloak");
        assertFalse(run.names().contains("Guardian")); assertEquals("Alice",run.players().get("player:alice"));
        Projections.Row saved=project("runs","{id:'r',inspectedPlayers:{'player:alice':{objectType:301,className:'Saved Mage'}}}");
        searchable(saved,"Saved Mage"); assertFalse(saved.body().contains("Spellweaver"));
    }
    @Test public void equipmentTimelineDoesNotResolveSlotOrArbitraryIntegerValues() {
        Projections.Row equipment=project("timeline","{kind:'Equipment changed',values:{before:101,after:102,slot:201}}");
        searchable(equipment,"Moonblade","Sunshield"); assertFalse(equipment.names().contains("Guardian"));
        assertFalse(equipment.body().contains("101")); assertFalse(equipment.body().contains("102"));
        Projections.Row other=project("timeline","{kind:'Health changed',values:{before:101,after:102,amount:201}}");
        assertFalse(other.body().contains("Moonblade")); assertFalse(other.names().contains("Sunshield"));
        assertFalse(other.body().contains("Guardian"));
    }
    @Test public void combatAndDungeonTotalsAddClassEnemyAndItemNames() {
        searchable(project("encounters","{recordingId:'r',players:[{name:'Alice',classType:301}],bosses:[{type:201}]}"),"Spellweaver","Ancient Guardian");
        Projections.Row saved=project("encounters","{recordingId:'r',players:[{classType:301,className:'Saved Mage'}],bosses:[{type:201,name:'Saved Boss'}]}");
        searchable(saved,"Saved Mage","Saved Boss"); assertFalse(saved.body().contains("Guardian")); assertFalse(saved.body().contains("Spellweaver"));
        searchable(project("dungeon-totals","[{name:'Sanctuary',hits:{201:5},loot:{201:{101:2,102:3}}}]"),"Ancient Guardian","Moonblade","Sunshield");
    }
    @Test public void unknownIdsAndUnavailableDictionariesAddNothing() {
        String unknown="987654";
        for (Projections.Row row:List.of(
                project("loot","{items:[{id:987654,enchantEvidence:{orderedSlotIds:[987654,-1,-2]}}]}"),
                project("runs","{id:'r',requestedItems:{987654:1},inspectedPlayers:{'player:alice':{objectType:987654,stats:[{statTypeNum:8,statValue:987654}]}}}"),
                project("timeline","{kind:'Equipment changed',values:{before:987654,after:987654}}"),
                project("encounters","{recordingId:'r',players:[{classType:987654}],bosses:[{type:987654}]}"),
                project("dungeon-totals","[{hits:{987654:1},loot:{987654:{987654:1}}}]"))) {
            assertFalse(row.body().contains(unknown)); assertFalse(row.names().contains(unknown)); assertFalse(row.names().contains("Unknown"));
        }
        Projections.Row none=project("runs","{id:'r',requestedItems:{101:1}}",dictionary("none","Ignored")).get(0);
        assertFalse(none.body().contains("Moonblade")); assertFalse(none.names().contains("Moonblade"));
    }
    @Test public void dictionaryTextUsesTheSameHashRedactionAsSavedText() {
        String hash=ProjectionsTest.HASH;
        Projections.Row row=project("loot","{items:[{name:'Blade',enchantEvidence:{orderedSlotIds:[401]}}]}",dictionary("v1",hash)).get(1);
        assertFalse(row.body().contains(hash)); assertFalse(row.names().contains(hash));
        assertEquals(hash,project("fame","{account:'"+hash+"'}").columns().get("account"));
    }
}
