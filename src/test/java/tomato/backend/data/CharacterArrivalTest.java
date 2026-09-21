package tomato.backend.data;

import org.junit.*;
import packets.data.*;
import packets.data.enums.StatType;
import packets.incoming.*;
import tomato.backend.SecurityAbilityUseCheck;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/** First spawns and partial updates must not depend on optional ability-check baselines. */
public class CharacterArrivalTest {
    @BeforeClass public static void initializeSwing() throws Exception { javax.swing.SwingUtilities.invokeAndWait(() -> {}); }
    private Field counter;
    private int savedCounter;
    @Before public void setup() throws Exception {
        counter=SecurityAbilityUseCheck.class.getDeclaredField("decoyCounter");counter.setAccessible(true);
        savedCounter=counter.getInt(null);counter.setInt(null,0);
    }
    @After public void restore() throws Exception { counter.setInt(null,savedCounter); }

    @Test public void firstLocalSpawnAfterCreateSurvivesAnOpenDecoyCheckWindow() {
        TomatoData data=new TomatoData();MapInfoPacket map=new MapInfoPacket();map.name=map.displayName="The Void";
        data.setNewRealm(map);data.setUserId(21,7,"AAAAAA==");
        // A decoy in the current area leaves the next tick's comparison window open.
        Entity decoy=new Entity(null,99,0);decoy.objectType=1813;
        SecurityAbilityUseCheck.decoy(decoy);SecurityAbilityUseCheck.decreaseDecoyCounter();
        UpdatePacket spawn=new UpdatePacket();spawn.tiles=new GroundTileData[0];spawn.drops=new int[0];
        ObjectData object=new ObjectData();object.objectType=782;object.status=status(21,
                stat(StatType.MP_STAT,200),stat(StatType.MAX_MP_STAT,300),stat(StatType.MAX_MP_BOOST_STAT,0),
                stat(StatType.MAX_HP_STAT,555),stat(StatType.MAX_HP_BOOST_STAT,0),stat(StatType.LEVEL_STAT,20));
        spawn.newObjects=new ObjectData[]{object};
        data.update(spawn);
        assertNotNull("The first decoded player UPDATE must bind the local character",data.player);
        assertEquals(21,data.player.id);assertEquals(782,data.player.objectType);
        assertEquals(200,data.player.stat.get(StatType.MP_STAT).statValue);
        NewTickPacket tick=new NewTickPacket();tick.serverRealTimeMS=1000;tick.status=new ObjectStatusData[]{status(21,stat(StatType.MP_STAT,210))};
        data.updateNewTick(tick);assertEquals(210,data.player.stat.get(StatType.MP_STAT).statValue);
    }

    @Test public void stasisManaComparisonAllowsAnUnknownPreviousManaValue() {
        Entity partial=new Entity(null,900001,0);partial.stasisCounter=1;
        partial.updateStats(status(partial.id,stat(StatType.MP_STAT,50)),1000);
        assertEquals(50,partial.stat.get(StatType.MP_STAT).statValue);
    }

    @Test public void stasisNotificationSkipsPlayersWithoutCapturedAbilityEquipment() {
        TomatoData data=new TomatoData();data.time=1000;
        Entity partial=new Entity(null,900002,0);data.playerListUpdated.put(partial.id,partial);
        StasisPacket notification=new StasisPacket();notification.unknownByteArray=new byte[12];notification.unknownByteArray[1]=22;notification.stasisDuration=3;
        SecurityAbilityUseCheck.stasis(notification,data);
        assertEquals(0,partial.stasisCounter);
    }

    @Test public void mapChangesResetDecoyWindowsAndIdleTicksStayIdle() throws Exception {
        Entity decoy=new Entity(null,99,0);decoy.objectType=1813;
        SecurityAbilityUseCheck.decoy(decoy);SecurityAbilityUseCheck.decreaseDecoyCounter();assertEquals(0,counter.getInt(null));
        TomatoData data=new TomatoData();MapInfoPacket map=new MapInfoPacket();map.name=map.displayName="Lost Halls";data.setNewRealm(map);
        assertEquals(-1,counter.getInt(null));
        for(int i=0;i<100;i++)SecurityAbilityUseCheck.decreaseDecoyCounter();
        assertEquals(-1,counter.getInt(null));
    }

    @Test @SuppressWarnings("unchecked") public void partialTricksterStatsDoNotRequireManaOrAbilityBaselines() throws Exception {
        Class<?> type=tomato.realmshark.enums.CharacterClass.class;
        Field namesField=type.getDeclaredField("CLASS_NAME"),idsField=type.getDeclaredField("CHARACTER_IDS");
        namesField.setAccessible(true);idsField.setAccessible(true);
        Map<Integer,String> names=(Map<Integer,String>)namesField.get(null);Set<Integer> ids=(Set<Integer>)idsField.get(null);
        String oldName=names.put(65000,"Trickster");boolean hadId=!ids.add(65000);
        try{
            Entity partial=new Entity(null,900003,0);partial.objectType=65000;
            partial.updateStats(status(partial.id,stat(StatType.MP_STAT,50)),1000);
            partial.updateStats(status(partial.id,stat(StatType.MP_STAT,60)),1100);
            assertEquals(60,partial.stat.get(StatType.MP_STAT).statValue);
            partial.stat.set(StatType.INVENTORY_1_STAT,stat(StatType.INVENTORY_1_STAT,123));
            partial.updateStats(status(partial.id,stat(StatType.MP_STAT,70)),1200);
            assertEquals(70,partial.stat.get(StatType.MP_STAT).statValue);
        }finally{if(oldName==null)names.remove(65000);else names.put(65000,oldName);if(!hadId)ids.remove(65000);}
    }

    private static ObjectStatusData status(int id,StatData... stats) {
        ObjectStatusData status=new ObjectStatusData();status.objectId=id;status.pos=new WorldPosData();status.stats=stats;return status;
    }
    private static StatData stat(StatType type,int value) {
        StatData stat=new StatData();stat.statType=type;stat.statTypeNum=type.get();stat.statValue=value;return stat;
    }
}
