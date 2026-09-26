package tomato.ability;
import org.junit.Test;
import static org.junit.Assert.*;
public class AbilityObservationStoreTest {
    private AbilityObservation row(long at,String name){return new AbilityObservation("session",null,at,1,name,"Mystic",42,"Orb","stasis",10,12,"Non-decreasing MP near candidate stasis");}
    @Test public void searchesBeforePagingAndSeparatesEvictionFromOmissions(){
        AbilityObservationStore store=new AbilityObservationStore(250);store.add(row(1,"target"));
        for(int i=0;i<200;i++)store.add(row(i+2,"other"));
        assertEquals(1,store.snapshot("target",null,null,null).rows.size());
        for(int i=0;i<100;i++)store.add(row(i+203,"other"));store.omit();
        AbilityObservationStore.Snapshot s=store.snapshot("",null,null,null);
        assertEquals(250,s.retained);assertEquals(51,s.evicted);assertEquals(1,s.omitted);
        assertEquals(0,store.snapshot("target",null,null,null).rows.size());
        store.reset();assertEquals(0,store.snapshot("",null,null,null).retained);assertEquals(250,s.rows.size());
    }
    @Test public void timeBoundsAreHalfOpenAndInferenceRetainsActualMana(){
        AbilityObservationStore store=new AbilityObservationStore(5);store.add(row(10,"A"));store.add(row(20,"B"));
        AbilityObservation r=store.snapshot("orb","stasis",10L,20L).rows.get(0);
        assertEquals("A",r.player);assertEquals(Integer.valueOf(10),r.previousMp);assertEquals(Integer.valueOf(12),r.observedMp);
        assertTrue(r.details().contains("not a confirmed cast"));assertEquals(0,store.snapshot("","decoy",null,null).rows.size());
    }
}
