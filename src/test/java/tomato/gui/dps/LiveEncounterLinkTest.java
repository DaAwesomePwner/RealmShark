package tomato.gui.dps;

import org.junit.Test;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

import static org.junit.Assert.*;

/** A live encounter shows its entry-frozen link; unknown and unlinked live encounters stay honest. */
public class LiveEncounterLinkTest {
    @Test public void liveLinkUsesTheEntryContextAndNeverInventsOne() {
        VisitRef visit = new VisitRef("01234567-89ab-cdef-0123-456789abcdef", "journal:2");
        EncounterLink linked = EncounterLink.live(new EncounterContext(visit, 21, 5_000L));
        assertTrue(linked.linked()); assertTrue(linked.inProgress);
        assertEquals(visit, linked.visit); assertEquals(Integer.valueOf(21), linked.localObjectId);
        assertEquals("Linked (live encounter)", linked.label());
        assertTrue(linked.description().contains("visit journal:2"));
        assertTrue(linked.description().contains("saved periodically"));

        EncounterLink unlinked = EncounterLink.live(new EncounterContext(null, null, 5_000L));
        assertFalse(unlinked.linked()); assertEquals(EncounterLink.State.UNLINKED, unlinked.state);
        assertTrue(unlinked.label().contains("live encounter"));
        assertTrue(unlinked.description().contains("no visit is matched by name or time"));

        EncounterLink unknown = EncounterLink.live(null);
        assertEquals(EncounterLink.State.LIVE, unknown.state); assertFalse(unknown.linked());
        assertTrue(unknown.description().contains("shown once the encounter is saved"));
    }
}
