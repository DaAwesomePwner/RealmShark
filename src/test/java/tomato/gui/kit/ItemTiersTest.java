package tomato.gui.kit;

import java.io.StringReader;
import org.junit.Test;
import tomato.backend.data.RosterDefinitions;
import static org.junit.Assert.*;

/** ItemTiers: UT and ST first, then an explicit T label, then the numeric tier; "" while unknown. */
public class ItemTiersTest {
    @Test public void labelsComeFromTheDefinitionOrStayEmpty() throws Exception {
        RosterDefinitions defs = RosterDefinitions.parse(null, new StringReader("<Objects>"
            + "<Object type='10'><Labels>UT,WEAPON</Labels><Tier>12</Tier></Object><Object type='11'><Labels>ST,ARMOR</Labels></Object>"
            + "<Object type='12'><Labels>T13,ARMOR</Labels><Tier>12</Tier></Object><Object type='13'><Tier>7</Tier></Object>"
            + "<Object type='14'><Labels>CONSUMABLE</Labels></Object></Objects>"));
        assertEquals("UT", ItemTiers.label(defs.item(10))); assertEquals("ST", ItemTiers.label(defs.item(11)));
        assertEquals("An explicit tier label wins over the numeric tier", "T13", ItemTiers.label(defs.item(12)));
        assertEquals("T7", ItemTiers.label(defs.item(13))); assertEquals("No tier at all", "", ItemTiers.label(defs.item(14)));
        assertEquals("Unknown definition", "", ItemTiers.label((RosterDefinitions.Item) null));
        assertEquals("Empty and unknown slots have no tier", "", ItemTiers.label(0)); assertEquals("", ItemTiers.label(-1));
    }
}
