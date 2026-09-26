package tomato.backend.data;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.nio.file.*;
import tomato.history.link.VisitRef;
import packets.data.enums.StatType;

public class DeathAnnotationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void annotationSurvivesFrozenObservationRestoreAndRestart() throws Exception {
        Path path = temp.getRoot().toPath().resolve("journal.json"); CharacterJournal j = new CharacterJournal(path);
        Entity player = CharacterJournalTest.player("A", 782); j.observe(player, 1);
        String key = CharacterJournal.accountKey("A") + ":1"; j.markDead(key, true);
        CharacterJournal.DeathAnnotation a = new CharacterJournal.DeathAnnotation(); a.occurredAt = 123L;
        a.notes = "Manual event"; a.visit = new VisitRef("session", "visit"); j.annotateDeath(key, a);
        a.notes = "Mutated caller";
        packets.data.StatData item = new packets.data.StatData(); item.statType = StatType.INVENTORY_0_STAT;
        item.statTypeNum = item.statType.get(); item.statValue = 321;
        packets.data.ObjectStatusData update = new packets.data.ObjectStatusData(); update.objectId = player.id;
        update.stats = new packets.data.StatData[]{item}; update.pos = new packets.data.WorldPosData();
        player.updateStats(update, 1); j.observe(player, 1);
        assertNull(j.characters().get(0).equipment[0]); j.markDead(key, false); j.save();
        CharacterJournal.CharacterRecord restored = new CharacterJournal(path).characters().get(0);
        assertFalse(restored.dead); assertEquals(Integer.valueOf(321), restored.equipment[0]);
        assertEquals("Manual event", restored.deathAnnotation.notes); assertEquals(Long.valueOf(123), restored.deathAnnotation.occurredAt);
        assertTrue(restored.deathAnnotation.markedAt > 0); assertEquals("visit", restored.deathAnnotation.visit.visitId);
    }
}
