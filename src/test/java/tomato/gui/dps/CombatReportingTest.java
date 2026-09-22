package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import javax.swing.*;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

/** Headless metric and shared pause behavior; deliberately does not create a window. */
public class CombatReportingTest {
    private static Entity player(TomatoData data,int id,String name){
        Entity p=new Entity(data,id,0);p.objectType=768;p.markPlayerIdentity();StatData stat=new StatData();stat.stringStatValue=name;p.stat.set(StatType.NAME_STAT,stat);return p;
    }
    private static Entity target(TomatoData data,Entity a,Entity b){
        Entity target=new Entity(data,10,0);StatData hp=new StatData();hp.statValue=1000;target.stat.set(StatType.MAX_HP_STAT,hp);
        target.genericDamageHit(a,new Projectile(200),1000);target.genericDamageHit(b,new Projectile(200),3000);
        target.updateDamageTaken(1000);target.updateDamageTaken(3000);return target;
    }
    @SuppressWarnings("unchecked") private static <T>T field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(object);}
    @Test public void shareAndMaxHpAreDistinctAndFilteringCannotChangeDenominators() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try{
                Filter.disable();TomatoData data=new TomatoData();Entity a=player(data,1,"Alice"),b=player(data,2,"Bob"),enemy=target(data,a,b);
                MeterDpsGUI meter=new MeterDpsGUI();meter.renderData(null,Collections.singletonList(enemy),new ArrayList<>(),3000,false);
                JTable table=field(meter,"table");JTextField search=field(meter,"search");search.setText("Alice");
                assertEquals(1,table.getRowCount());assertEquals(50.0,(Double)table.getValueAt(0,4),.001);assertEquals(100.0,(Double)table.getValueAt(0,3),.001);
                String legacy=DpsToString.display(enemy,Collections.emptyMap(),null,null);
                assertTrue(legacy.contains(tomato.gui.modern.DisplayFormat.formatPercentage(20,3)+" of max HP"));assertTrue(legacy.contains("first-to-last hit window"));
                table.setRowSelectionInterval(0,0);JTextArea details=field(meter,"details");assertTrue(details.getText().contains("denominator includes hidden players"));
                assertTrue(details.getText().contains("not a full player roster"));
            }catch(Exception e){throw new AssertionError(e);}finally{Filter.disable();}
        });
    }
    @Test public void inclusiveIncomingBoundsAndZeroVersusUnknownRemainExplicit() throws Exception {
        TomatoData data=new TomatoData();Entity a=player(data,1,"Alice"),b=player(data,2,"Bob"),enemy=target(data,a,b);
        a.getDamageList().add(new Damage(enemy,999,500));a.getDamageList().add(new Damage(enemy,1000,10));a.getDamageList().add(new Damage(enemy,3000,20));a.getDamageList().add(new Damage(enemy,3001,500));
        CombatMeterData fight=new CombatMeterData(Collections.singletonList(enemy),null);
        assertEquals(30,fight.rows.get(0).taken);assertEquals(2,fight.rows.get(0).incomingHits);assertFalse(fight.rows.get(1).incomingAvailable);
        CombatMeterData all=new CombatMeterData(Collections.singletonList(enemy),null,true);assertEquals(1030,all.rows.get(0).taken);
        Field user=Entity.class.getDeclaredField("isUser");user.setAccessible(true);user.setBoolean(b,true);
        CombatMeterData zero=new CombatMeterData(Collections.singletonList(enemy),b);assertTrue(zero.rows.get(1).incomingAvailable);assertEquals(0,zero.rows.get(1).taken);
        CombatMeterData empty=new CombatMeterData(Collections.emptyList(),b);assertNull(empty.dps(empty.rows.get(0)));assertNull(empty.share(empty.rows.get(0)));
    }
    @Test public void damageWithoutAnOwnerRemainsInTheRecordedShareDenominator() {
        TomatoData data=new TomatoData();Entity a=player(data,1,"Alice"),b=player(data,2,"Bob"),enemy=target(data,a,b);
        enemy.getDamageList().add(new Damage(null,2000,400));
        CombatMeterData meter=new CombatMeterData(Collections.singletonList(enemy),null);
        assertEquals(800,meter.total);assertEquals(400,meter.unattributed);assertEquals(2,meter.rows.size());
        assertEquals(25.0,meter.share(meter.rows.get(0)),.001);
    }
    @Test public void sharedPauseSurvivesModeAndMapChangesAndResumeRendersImmediately() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try{
                Filter.disable();TomatoData data=new TomatoData();data.map=new MapInfoPacket();data.map.name="Synthetic first";
                Entity a=player(data,1,"Alice"),b=player(data,2,"Bob"),enemy=target(data,a,b);data.player=a;
                Map<Integer,Entity> targets=field(data,"entityHitList");targets.put(enemy.id,enemy);
                DpsGUI view=new DpsGUI(data);view.setIndex(-1);JCheckBox paused=field(view,"paused");JComboBox<?> modes=field(view,"viewMode");
                paused.doClick();Object frozen=field(view,"displayed");
                data.map=new MapInfoPacket();data.map.name="Synthetic second";enemy.genericDamageHit(a,new Projectile(600),4000);
                DpsGUI.updateMapPacket(data);DpsGUI.update();assertSame(frozen,field(view,"displayed"));
                modes.setSelectedIndex(1);assertSame(frozen,field(view,"displayed"));
                StringDpsGUI legacy=field(view,"displayString");JTextArea text=field(legacy,"textAreaDPS");assertTrue(text.getText().contains("Synthetic first"));assertFalse(text.getText().contains("Synthetic second"));
                JTextArea notice=field(view,"pauseNotice");assertTrue(notice.getText().contains("snapshot displayed at"));
                modes.setSelectedIndex(0);MeterDpsGUI meter=field(view,"displayMeter");CombatMeterData before=field(meter,"snapshot");assertEquals(400,before.total);
                paused.doClick();CombatMeterData after=field(meter,"snapshot");assertEquals(1000,after.total);assertNotSame(frozen,field(view,"displayed"));assertTrue(notice.getText().contains("Synthetic second"));
            }catch(Exception e){throw new AssertionError(e);}finally{Filter.disable();}
        });
    }
}
