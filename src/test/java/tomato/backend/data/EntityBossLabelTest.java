package tomato.backend.data;

import org.junit.Test;

import static org.junit.Assert.*;

/** Only a BOSS label ends a dungeon in the party outcomes; a MINIBOSS never does. */
public class EntityBossLabelTest {
    @Test public void aBossLabelCountsAndAMinibossLabelDoesNot() {
        assertTrue(Entity.isBossLabel("BOSS"));
        assertTrue(Entity.isBossLabel("ENEMY,BOSS"));
        assertTrue(Entity.isBossLabel("BOSS,GOD"));
        assertFalse(Entity.isBossLabel("MINIBOSS"));
        assertFalse(Entity.isBossLabel("ENEMY,MINIBOSS"));
        assertFalse(Entity.isBossLabel("BOSSY,SUBBOSS"));
        assertFalse(Entity.isBossLabel(""));
        assertFalse(Entity.isBossLabel(null));
    }
}
