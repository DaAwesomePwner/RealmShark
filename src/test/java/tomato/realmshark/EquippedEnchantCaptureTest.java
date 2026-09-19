package tomato.realmshark;

import java.util.Arrays;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import static org.junit.Assert.*;
import static tomato.realmshark.ParseEnchants.CaptureState.*;

public class EquippedEnchantCaptureTest {
    @Test public void missingStatAndNullPayloadAreUnknownButExplicitEmptyIsKnown() {
        Entity player = new Entity(null, 1, 0);
        assertNull(ParseEnchants.equippedCapture(player).completeCodes());
        assertNull(ParseEnchants.equippedCapture(null).completeCodes());
        for (String value : new String[] {null, "", ",,,"}) {
            ParseEnchants.EquippedCapture capture = capture(value);
            for (int slot = 0; slot < 4; slot++) assertEquals(value == null ? MISSING : KNOWN, capture.state(slot));
            if (value == null) assertNull(capture.completeCodes());
            else assertArrayEquals(new String[] {"", "", "", ""}, capture.completeCodes());
        }
    }

    @Test public void trailingEmptyFieldsAreEvidenceButOmittedSlotsAreNot() {
        for (int length = 1; length <= 4; length++) {
            String[] codes = new String[length]; Arrays.fill(codes, ""); codes[0] = "AAIE";
            ParseEnchants.EquippedCapture capture = capture(String.join(",", codes));
            for (int slot = 0; slot < 4; slot++) assertEquals(slot < length ? KNOWN : MISSING, capture.state(slot));
            if (length < 4) assertNull(capture.completeCodes()); else assertNotNull(capture.completeCodes());
        }
    }

    @Test public void malformedDataInAnyEquippedSlotInvalidatesTotalButKeepsIndividualEvidence() {
        for (int slot = 0; slot < 4; slot++) {
            String[] codes = {"", "", "", ""}; codes[slot] = "!!!";
            ParseEnchants.EquippedCapture capture = capture(String.join(",", codes));
            assertNull(capture.completeCodes());
            for (int i = 0; i < 4; i++) assertEquals(i == slot ? MALFORMED : KNOWN, capture.state(i));
        }
    }

    @Test public void strictCaptureIsDetachedAndDoesNotChangeLegacyExtractionOrSummarySemantics() {
        Entity player = new Entity(null, 1, 0);
        assertArrayEquals(new String[] {"", "", "", ""}, ParseEnchants.getEnchantStrings(player));
        StatData stat = new StatData(); stat.stringStatValue = "AAIE"; player.stat.set(StatType.UNIQUE_DATA_STRING, stat);
        ParseEnchants.EquippedCapture partial = ParseEnchants.equippedCapture(player);
        assertArrayEquals(new String[] {"AAIE", "", "", ""}, ParseEnchants.getEnchantStrings(player));
        assertNull(partial.completeCodes());
        stat.stringStatValue = "AAIE,,,,!!!";
        ParseEnchants.EquippedCapture complete = ParseEnchants.equippedCapture(player);
        String[] copy = complete.completeCodes(); copy[0] = "!!!";
        assertEquals("AAIE", complete.completeCodes()[0]);
        assertNull(partial.completeCodes());
        assertEquals(0, ParseEnchants.summarize("AAIE").slots);
    }

    @Test public void validUnpaddedCodesAreNormalizedForTheLegacyEffectDecoder() {
        ParseEnchants.EquippedCapture capture = capture("AAIE_wU,,,"); // Flat Mana Regeneration I (0x5ff).
        assertEquals(KNOWN, capture.state(0));
        assertFalse(capture.description(0).isEmpty());
        assertEquals(2f, ParseEnchants.getManaRegenPerSecondFromEnchants(capture.completeCodes(), 400, false), .001f);
    }

    private static ParseEnchants.EquippedCapture capture(String value) {
        Entity player = new Entity(null, 1, 0);
        StatData stat = new StatData(); stat.stringStatValue = value; player.stat.set(StatType.UNIQUE_DATA_STRING, stat);
        return ParseEnchants.equippedCapture(player);
    }
}
