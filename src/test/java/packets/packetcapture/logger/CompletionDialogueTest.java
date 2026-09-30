package packets.packetcapture.logger;

import org.junit.Test;
import packets.incoming.TextPacket;

import static org.junit.Assert.*;

public class CompletionDialogueTest {
    private static TextPacket line(String name, String text) {
        TextPacket p = new TextPacket(); p.name = name; p.text = text; return p;
    }

    @Test public void theVoidEntityLineMatchesOnlyInTheVoid() {
        TextPacket p = line("#Void Entity", "You fools... You can never truly defeat me! I am in all of you! I AM all of you!");
        assertEquals("Final boss dialogue: Void Entity", CompletionDialogue.evidence("The Void", p));
        assertNull(CompletionDialogue.evidence("Lost Halls", p));
    }

    @Test public void shattersAndMoonlightLinesMatch() {
        assertEquals("Final boss dialogue: King Azamoth", CompletionDialogue.evidence("The Shatters",
            line("#King Azamoth", "This fate is mine to bear... not hers.")));
        assertEquals("Final boss dialogue: Dancer Miko", CompletionDialogue.evidence("Moonlight Village",
            line("#Dancer Miko", "Thank you all for coming tonight.")));
    }

    @Test public void playerChatAndMissingFieldsNeverMatch() {
        String text = "You fools... You can never truly defeat me! I am in all of you! I AM all of you!";
        assertNull("A player quoting the line is not the boss", CompletionDialogue.evidence("The Void", line("Void Entity", text)));
        assertNull(CompletionDialogue.evidence("The Void", line("#Void Entity", null)));
        assertNull(CompletionDialogue.evidence(null, line("#Void Entity", text)));
        assertNull(CompletionDialogue.evidence("The Void", null));
    }
}
