package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import javax.swing.*;
import java.lang.reflect.Field;
import java.io.ObjectStreamClass;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class DpsRefreshTest {
    private static MapInfoPacket map(String name) { MapInfoPacket m=new MapInfoPacket(); m.name=m.displayName=name; return m; }
    private static Entity player(TomatoData data) {
        Entity player=new Entity(data,1,0); player.objectType=768;
        StatData name=new StatData(); name.stringStatValue="Local test"; player.stat.set(StatType.NAME_STAT,name);
        data.player=player; return player;
    }
    @SuppressWarnings("unchecked") private static HashMap<Integer,Entity> hits(TomatoData data) throws Exception {
        Field f=TomatoData.class.getDeclaredField("entityHitList"); f.setAccessible(true); return (HashMap<Integer,Entity>)f.get(data);
    }
    private static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    @Test public void blockedUiDoesNotDelayHitsOrDungeonArchiving() throws Exception {
        TomatoData data=new TomatoData(); data.map=map("The Nest"); Entity player=player(data);
        Entity boss=new Entity(data,20,0); boss.updateDamageTaken(1000); hits(data).put(boss.id,boss);
        DpsGUI[] view=new DpsGUI[1]; SwingUtilities.invokeAndWait(()->view[0]=new DpsGUI(data));
        CountDownLatch uiBlocked=new CountDownLatch(1), releaseUi=new CountDownLatch(1);
        SwingUtilities.invokeLater(()->{uiBlocked.countDown();try{releaseUi.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        ExecutorService producer=Executors.newSingleThreadExecutor();
        try {
            assertTrue(uiBlocked.await(2,TimeUnit.SECONDS));
            producer.submit(()->{
                for(int i=0;i<50;i++) { boss.genericDamageHit(player,new Projectile(10),1000+i); DpsGUI.updateNewTickPacket(data); }
                // The model closes the encounter without waiting for display work or another tick.
                data.clear(); data.map=map("Nexus"); DpsGUI.updateMapPacket(data);
            }).get(2,TimeUnit.SECONDS);
            assertEquals(1,data.dpsData.size());
            assertEquals(50,data.dpsData.get(0).hitList.get(20).getDamageList().size());
            DpsSnapshot latest=(DpsSnapshot)field(view[0],"latest");
            assertEquals("Nexus",latest.map.name); assertEquals(0,latest.targets.length);
            assertNull("Hidden UI did not render or queue a stale tick",field(view[0],"rendered"));
        } finally { releaseUi.countDown(); producer.shutdownNow(); }
        SwingUtilities.invokeAndWait(()->{
            view[0].setIndex(0);
            try {
                MeterDpsGUI meter=(MeterDpsGUI)field(view[0],"displayMeter");
                CombatMeterData snapshot=(CombatMeterData)field(meter,"snapshot");
                assertEquals(500,snapshot.total);
            } catch(Exception e){throw new AssertionError(e);}
        });
    }
    @Test public void displaySnapshotsDetachHitsStatsAndProjectilesAndKeepTotals() throws Exception {
        TomatoData data=new TomatoData(); data.map=map("The Nest"); Entity player=player(data);
        Entity boss=new Entity(data,20,0); hits(data).put(20,boss);
        Projectile projectile=new Projectile(100); boss.genericDamageHit(player,projectile,1000); boss.updateDamageTaken(1000);
        player.getDamageList().add(new Damage(boss,1100,25)); // Cyclic owner links must be copied once.
        StatData guild=new StatData(); guild.stringStatValue="Snapshot guild"; player.stat.set(StatType.GUILD_NAME_STAT,guild);
        DpsSnapshot snapshot=DpsSnapshot.capture(data);
        assertSame(snapshot.player,snapshot.targets[0].getDamageList().get(0).owner);
        assertSame(snapshot.targets[0],snapshot.player.getDamageList().get(0).owner);
        player.stat.get(StatType.NAME_STAT).stringStatValue="Changed";
        guild.stringStatValue="Changed guild";
        boss.genericDamageHit(player,new Projectile(900),2000); boss.updateDamageTaken(2000); projectile.clear();
        assertEquals("Local test",snapshot.player.getStatName());
        assertEquals("Snapshot guild",snapshot.localPlayerContext.guild);
        assertEquals(1,snapshot.targets[0].getDamageList().size());
        assertEquals(100,snapshot.targets[0].getDamageList().get(0).projectile.getDamage());
        assertEquals(100,snapshot.targets[0].getPlayerDamageList().get(0).damage);
        CombatMeterData meter=new CombatMeterData(Arrays.asList(snapshot.targets),snapshot.player,true);
        assertEquals(100,meter.total); assertEquals(25,meter.rows.get(0).taken);
        assertEquals(-4686692941424283051L,ObjectStreamClass.lookup(Entity.class).getSerialVersionUID());
        assertEquals(5852326746681358741L,ObjectStreamClass.lookup(Projectile.class).getSerialVersionUID());
    }
    @Test public void archiveKeepsLocalContextWithoutLocalDamageAfterCaptureIsCleared() {
        TomatoData data = new TomatoData(); data.map = map("The Nest");
        Entity local = player(data);
        StatData guild = new StatData(); guild.stringStatValue = "Recorded guild";
        local.stat.set(StatType.GUILD_NAME_STAT, guild);
        data.clear();
        assertNull(data.player);
        assertEquals(1, data.dpsData.size());
        DpsData saved = data.dpsData.get(0);
        assertTrue(saved.hitList.isEmpty());
        local.objectType = 775; guild.stringStatValue = "Next guild";
        assertEquals(768, saved.getLocalPlayerContext().classType);
        assertEquals("Recorded guild", saved.getLocalPlayerContext().guild);
        assertEquals("Recorded guild", saved.getSaveFile(false).getLocalPlayerContext().guild);
    }

    @Test public void selectedEnemySurvivesReplacementSnapshots() throws Exception {
        TomatoData data=new TomatoData(); data.map=map("The Nest"); Entity player=player(data);
        Entity boss=new Entity(data,20,0); hits(data).put(20,boss); boss.genericDamageHit(player,new Projectile(100),1000);
        SwingUtilities.invokeAndWait(()->{
            try {
                MeterDpsGUI meter=new MeterDpsGUI(); meter.setContext(data.map,player);
                DpsSnapshot first=DpsSnapshot.capture(data);
                meter.renderData(first.map,Arrays.asList(first.targets),first.notifications,0,true);
                JList<?> list=(JList<?>)field(meter,"enemyList"); list.setSelectedIndex(1);
                DpsSnapshot second=DpsSnapshot.capture(data);
                meter.renderData(second.map,Arrays.asList(second.targets),second.notifications,0,true);
                assertEquals(20,((Entity)list.getSelectedValue()).id);
                assertSame(second.targets[0],list.getSelectedValue());
            } catch(Exception e){throw new AssertionError(e);}
        });
    }
}
