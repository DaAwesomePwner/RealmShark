package tomato.backend.data;

import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DungeonSessionScopeTest {
    @Test public void currentSessionCountersExcludeTheLoadedCumulativeBaseline()throws Exception{
        Path path=Files.createTempDirectory("dungeon-session").resolve("dungeon.stats");
        Entity enemy=new Entity(null,1,0);enemy.objectType=99999;
        DungeonStatData old=new DungeonStatData(path);old.updateEntityDamage("Ice Citadel",enemy);old.updateDungeon("Ice Citadel",60000);
        DungeonStatData current=new DungeonStatData(path);current.load();
        assertEquals(1,current.snapshot().get(0).visits);assertTrue(current.sessionSnapshot().isEmpty());
        current.updateEntityDamage("Ice Citadel",enemy);current.updateDungeon("Ice Citadel",90000);
        assertEquals(2,current.snapshot().get(0).visits);assertEquals(1,current.sessionSnapshot().get(0).visits);
        assertEquals(90000,current.sessionSnapshot().get(0).time);assertEquals(1,current.sessionSnapshot().get(0).hitCount());
    }
}
