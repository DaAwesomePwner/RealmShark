package tomato.backend.data;

import java.io.StringReader;
import org.junit.Test;
import static org.junit.Assert.*;

public class RosterDefinitionsTest {
    @Test public void missingCapsAndTiersDifferFromExplicitZero() throws Exception {
        RosterDefinitions definitions = RosterDefinitions.parse(new StringReader("<Objects><Object type='1'><Defense max='0'/><Attack max='bad'/><Speed max='08'/></Object></Objects>"),
            new StringReader("<Objects><Object type='10'><Tier>0</Tier><Labels>T0,TIERED</Labels></Object><Object type='11'><Labels>TIERED</Labels></Object></Objects>"));
        assertEquals(Integer.valueOf(0), definitions.cap(1, 3)); assertNull(definitions.cap(1, 0)); assertNull(definitions.cap(1, 2)); assertNull(definitions.cap(2, 3));
        assertEquals("Cap integers are decimal, not implicit octal", Integer.valueOf(8), definitions.cap(1, 4));
        assertEquals(Integer.valueOf(0), definitions.item(10).tier); assertTrue(definitions.item(10).parsable());
        assertNull(definitions.item(11).tier); assertNull(definitions.item(11).parsable()); assertNull(definitions.item(12));
    }
    @Test public void labelsAreExactTokensAndSpecialGearDoesNotNeedTier() throws Exception {
        RosterDefinitions definitions = RosterDefinitions.parse(null, new StringReader("<Objects><Object type='10'><Labels>UT,WEAPON</Labels></Object>"
            + "<Object type='11'><Labels>CONSUMABLE</Labels></Object><Object type='12'/></Objects>"));
        assertTrue(definitions.item(10).special()); assertTrue(definitions.item(10).parsable()); assertFalse(definitions.item(11).parsable()); assertNull(definitions.item(12).parsable());
    }
}
