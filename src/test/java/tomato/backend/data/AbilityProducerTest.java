package tomato.backend.data;

import org.junit.*;
import static org.junit.Assert.*;
import packets.data.*;
import packets.data.enums.StatType;
import tomato.ability.*;

public class AbilityProducerTest {
    @Before public void reset() { AbilityObservationStore.application().reset(); }
    @After public void cleanup() { AbilityObservationStore.application().reset(); }
    @Test public void inferenceFreezesPreviousAndIncomingBeforeMutation() {
        Entity entity = CharacterJournalTest.player("synthetic", 782);
        CharacterJournalTest.put(entity, StatType.MP_STAT, 40);
        CharacterJournalTest.put(entity, StatType.INVENTORY_1_STAT, 123);
        entity.stasisCounter = 2;
        StatData mp = new StatData(); mp.statType = StatType.MP_STAT; mp.statTypeNum = mp.statType.get(); mp.statValue = 45;
        ObjectStatusData update = new ObjectStatusData(); update.objectId = entity.id; update.pos = new WorldPosData(); update.stats = new StatData[]{mp};
        entity.updateStats(update, 1);
        AbilityObservation row = AbilityObservationStore.application().snapshot("", null, null, null).rows.get(0);
        assertEquals(Integer.valueOf(40), row.previousMp); assertEquals(Integer.valueOf(45), row.observedMp);
        assertEquals(45, entity.stat.get(StatType.MP_STAT).statValue);
        assertNull(row.visit); assertTrue(row.explanation.contains("not proof"));
        CharacterJournalTest.put(entity, StatType.MP_STAT, 90);
        assertEquals(Integer.valueOf(45), row.observedMp);
    }
}
