package tomato.gui.dps;

import org.junit.Test;
import tomato.backend.data.PresenceTimeline;

import javax.swing.*;
import java.util.List;

import static org.junit.Assert.*;

/** The legacy icon view tags players with the dungeon-level outcome, exactly as the text view does. */
public class IconOutcomeTest {
    private static EncounterOutcomes saved(PresenceTimeline t) { return EncounterOutcomes.of(t, 1_000, false, List.of(), List.of()); }

    @Test public void aPlayerWhoLeftViewAndReturnedIsNotTaggedAndANexusedPlayerIs() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Wanderer", 768, 1_000, false); t.recordSeen(2, "Quitter", 775, 1_000, false);
        t.recordLeft(1, 700, 700, 20_000); t.recordSeen(1, "Wanderer", 768, 30_000, false);
        t.recordLeft(2, 126, 700, 161_000);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        EncounterOutcomes outcomes = saved(t);
        assertNull("Out of view and back is not a nexus", IconDpsGUI.outcomeLabel(outcomes.outcome(1), 16));
        JLabel nexus = IconDpsGUI.outcomeLabel(outcomes.outcome(2), 16);
        assertNotNull(nexus);
        assertEquals("Nexused 2:40 · 18% HP", nexus.getText());
        assertEquals(outcomes.outcome(2).reason, nexus.getToolTipText());
    }

    @Test public void aDeathWithoutAGravestoneIconShowsItsLabelAndOthersGetNoTag() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 1_000, true); t.recordSeen(2, "Waiting", 775, 1_000, false);
        t.recordLocalDeath("Synthetic foe", 40_000);
        EncounterOutcomes outcomes = saved(t);
        JLabel died = IconDpsGUI.outcomeLabel(outcomes.outcome(1), 16);
        assertNotNull(died);
        assertEquals("Died 0:39", died.getText());
        assertTrue(died.getToolTipText(), died.getToolTipText().contains("killed by Synthetic foe"));
        assertNull("The end was never seen: unknown, no tag", IconDpsGUI.outcomeLabel(outcomes.outcome(2), 16));
        assertNull(IconDpsGUI.outcomeLabel(null, 16));
        assertNull(IconDpsGUI.outcomeLabel(EncounterOutcomes.untracked().outcome(1), 16));
    }
}
