package tomato.gui.character;

import java.io.StringReader;
import org.junit.Test;
import tomato.backend.data.*;
import static org.junit.Assert.*;
import static tomato.gui.character.CharacterRosterQuery.*;

public class CharacterRosterQueryTest {
    public static RosterDefinitions definitions() throws Exception {
        return RosterDefinitions.parse(new StringReader("<Objects><Object type='782'><MaxHitPoints max='670'/><MaxMagicPoints max='385'/>"
            + "<Attack max='75'/><Defense max='25'/><Speed max='50'/><Dexterity max='75'/><HpRegen max='40'/><MpRegen max='60'/></Object></Objects>"), null);
    }
    private static CharacterJournal.CharacterRecord record() {
        CharacterJournal.CharacterRecord r = new CharacterJournal.CharacterRecord(); r.key = "account-A:7"; r.account = "account-A"; r.characterId = 7; r.classId = 782;
        r.seasonal = true; r.stats = new Integer[]{650,385,75,25,50,75,40,60}; r.equipment[0] = 12345; r.lastSeen = 1000; return r;
    }
    private static CharacterRosterQuery query(String text, String account, NeedLife life, Missing missing, Maxed maxed, int min, int max, Age age) {
        return new CharacterRosterQuery(text, account, 782, false, true, false, life, missing, maxed, min, max, age, 1000);
    }
    @Test public void composedAccountSeasonLifeAndRangeOnlyMatchKnownEvidence() throws Exception {
        Row row = new Row(record(), definitions());
        assertEquals(Long.valueOf(4), row.potions); assertEquals(Integer.valueOf(7), row.maxed);
        CharacterRosterQuery q = query("", "account-A", NeedLife.YES, Missing.COMPLETE_STATS, Maxed.KNOWN_RANGE, 6, 7, Age.ANY);
        assertTrue(q.matches(row, 1000, "Sample", id -> "Item", id -> "Wizard"));
        row.record.account = "account-B"; assertFalse(q.matches(row, 1000, "Sample", id -> "Item", id -> "Wizard"));
        row.record.account = "account-A"; row.record.seasonal = null; assertFalse(q.matches(row, 1000, "Sample", id -> "Item", id -> "Wizard"));
    }
    @Test public void incompleteStatsAndUnknownCapsNeverBecomeNumericZero() throws Exception {
        CharacterJournal.CharacterRecord r = record(); r.stats[0] = null;
        Row row = new Row(r, definitions()); assertNull(row.potions); assertNull(row.maxed); assertEquals(NeedLife.UNKNOWN, row.needsLife);
        assertTrue(query("", null, NeedLife.UNKNOWN, Missing.MISSING_STATS, Maxed.UNKNOWN, 0, 8, Age.ANY).matches(row, 2000, "A", id -> "", id -> ""));
        Row unknownCaps = new Row(record(), RosterDefinitions.empty()); assertNull(unknownCaps.potions); assertFalse(unknownCaps.completeCaps);
        assertEquals(8, unknownCaps.capturedStats);
    }
    @Test public void resolvedEquipmentStillMatchesDecimalAndHexIdLiteralSearch() throws Exception {
        Row row = new Row(record(), definitions());
        for (String search : new String[]{"12345", "0x3039", "Sword"})
            assertTrue(query(search, null, NeedLife.ANY, Missing.ANY, Maxed.ANY, 0, 8, Age.ANY).matches(row, 2000, "A", id -> "Sword", id -> "Wizard"));
        assertFalse(query("[.*]", null, NeedLife.ANY, Missing.ANY, Maxed.ANY, 0, 8, Age.ANY).matches(row, 2000, "A", id -> "Sword", id -> "Wizard"));
    }
    @Test public void ageBoundsUseOneClockInstantAndUnknownIsSeparate() throws Exception {
        Row row = new Row(record(), definitions());
        CharacterRosterQuery recent = query("", null, NeedLife.ANY, Missing.ANY, Maxed.ANY, 0, 8, Age.NEWER_THAN);
        assertTrue(recent.matches(row, 1999, "A", id -> "", id -> "")); assertFalse(recent.matches(row, 2000, "A", id -> "", id -> ""));
        assertTrue(query("", null, NeedLife.ANY, Missing.ANY, Maxed.ANY, 0, 8, Age.AT_LEAST).matches(row, 2000, "A", id -> "", id -> ""));
        row.record.lastSeen = 0; assertFalse(recent.matches(row, 100, "A", id -> "", id -> ""));
        assertTrue(query("", null, NeedLife.ANY, Missing.ANY, Maxed.ANY, 0, 8, Age.UNKNOWN).matches(row, 2000, "A", id -> "", id -> ""));
    }
}
