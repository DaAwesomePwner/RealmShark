package tomato.gui.stats;

import org.junit.Test;
import packets.incoming.MapInfoPacket;
import tomato.realmshark.ParseEnchants;
import tomato.history.SessionStore;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

public class LootEnrichmentTest {
    @Test public void capturedZeroDiffersFromAbsentBoostAndPlayerChangesCannotRewriteContext(){
        tomato.backend.data.Entity player=new tomato.backend.data.Entity(null,1,1);
        assertNull(DropContext.capture(null,player,100,null).lootDropSeconds);
        packets.data.StatData boost=new packets.data.StatData();boost.statType=packets.data.enums.StatType.LD_TIMER_STAT;boost.statTypeNum=boost.statType.get();boost.statValue=0;
        packets.data.StatData seasonal=new packets.data.StatData();seasonal.statType=packets.data.enums.StatType.SEASONAL;seasonal.statTypeNum=seasonal.statType.get();seasonal.statValue=1;
        player.stat.setStats(new packets.data.StatData[]{boost,seasonal});
        DropContext captured=DropContext.capture(null,player,100,null);boost.statValue=30;seasonal.statValue=0;
        assertEquals(Integer.valueOf(0),captured.lootDropSeconds);assertEquals(Boolean.TRUE,captured.seasonal);assertNull(captured.lootDropCapturedAt);
        assertTrue(captured.describe().contains("remaining at drop unknown"));assertNull(captured.crucible);
    }
    private String encoded(int... ids){ByteBuffer b=ByteBuffer.allocate(3+ids.length*2).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)0).putShort((short)1026);for(int i:ids)b.putShort((short)i);return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());}
    @Test public void exactVariantsRoundTripWithoutSplittingAggregateKey(){
        LootDashboard.Item a=new LootDashboard.Item(42,"item","WEAPON",ParseEnchants.evidence(encoded(13,-2,-1,-3)));
        LootDashboard.Item b=new LootDashboard.Item(42,"item","WEAPON",ParseEnchants.evidence(encoded(32767,-2,-1,-3)));
        assertEquals(a.key,b.key);assertNotEquals(a.enchantEvidence.orderedSlotIds,b.enchantEvidence.orderedSlotIds);
        LootDashboard.Drop d=new LootDashboard.Drop("White","Dungeon","Enemy",100,Arrays.asList(a,b));
        LootDashboard.Drop restored=SessionStore.JSON.fromJson(SessionStore.JSON.toJson(d),LootDashboard.Drop.class);
        assertEquals(Arrays.asList(13,-2,-1,-3),restored.items.get(0).enchantEvidence.orderedSlotIds);
        assertTrue(LootQuery.Row.item("session",restored,restored.items.get(1),"Dungeon").enchantEvidence.contains("32767"));
    }
    @Test public void legacyAndMalformedStayDistinctFromRecordedEmpty(){
        assertEquals(ParseEnchants.EvidenceState.MISSING,ParseEnchants.evidence(null).state);
        assertEquals(ParseEnchants.EvidenceState.INVALID,ParseEnchants.evidence("bad!").state);
        assertEquals(ParseEnchants.EvidenceState.RECORDED_EMPTY,ParseEnchants.evidence("").state);
        assertEquals(ParseEnchants.EvidenceState.INVALID,ParseEnchants.evidence(encoded(-4)).state);
        assertEquals(Arrays.asList(-2,-1,-3),ParseEnchants.evidence(encoded(-2,-1,-3,42)).orderedSlotIds);
        LootDashboard.Item old=new LootDashboard.Item(1,"Old",false);
        LootQuery.Row row=LootQuery.Row.item("s",new LootDashboard.Drop("White","D","E",1,Arrays.asList(old)),old,"D");
        assertTrue(row.enchantEvidence.contains("LEGACY_NOT_RECORDED"));assertTrue(row.dropContext.contains("Not recorded"));
    }
    @Test public void contextIsFrozenAndDoesNotStoreUnmappedServerStrings(){
        MapInfoPacket map=new MapInfoPacket();map.dungeonGrade="S";map.difficulty=2.5f;map.dungeonModifiers="private-unmapped-modifier";
        DropContext c=DropContext.capture(map,123,null);map.dungeonGrade="B";map.difficulty=0;map.dungeonModifiers="";
        assertEquals("S",c.grade);assertEquals(Float.valueOf(2.5f),c.difficulty);assertEquals(1,c.unresolvedModifiers);
        String json=SessionStore.JSON.toJson(c);assertFalse(json.contains("private-unmapped"));
        assertTrue(SessionStore.JSON.fromJson(json,DropContext.class).describe().contains("Not recorded"));
        assertNull(DropContext.capture(null,1,null).difficulty);
    }
}
