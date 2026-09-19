package tomato.realmshark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Summary checks require no enchantment definitions or asset setup. */
public class ParseEnchantsSummaryTest {

    @Test public void nullIsUnknownButEmptyStringIsKnownZero() {
        assertUnknown(null);
        assertSummary("", 0, 0, "Common");
    }

    @Test public void wellKnownUnusedBlobIsKnownZero() {
        assertSummary("AAIE_f_9__3__f8=", 0, 0, "Common");
        assertSummary("AAIE_f_9__3__f8", 0, 0, "Common");
    }

    @Test public void mapsEverySlotCountToRarityWithoutRequiringATerminator() {
        assertSummary(encode(), 0, 0, "Common");
        assertSummary(encode(-1), 1, 0, "Uncommon");
        assertSummary(encode(-1, 42), 2, 1, "Rare");
        assertSummary(encode(-1, 42, 7), 3, 2, "Legendary");
        assertSummary(encode(-1, 42, 7, 0), 4, 3, "Divine");
    }

    @Test public void allUnlockedEmptySlotsCountButNoneAreApplied() {
        assertSummary(encode(-1, -1, -1, -1), 4, 0, "Divine");
    }

    @Test public void lockedSlotsAreExcludedWithoutStoppingLaterEntries() {
        assertSummary(encode(-2, -2, -2, -2), 0, 0, "Common");
        assertSummary(encode(-2, -1, -2, 42), 2, 1, "Rare");
        assertSummary(encode(42, -2, -1, -2), 2, 1, "Rare");
        assertSummary(encode(-2, -3, -3, -3), 0, 0, "Common");
    }

    @Test public void unusedTerminatorStopsCountingLaterCompleteEntries() {
        assertSummary(encode(-3), 0, 0, "Common");
        assertSummary(encode(-3, 42, -1, -2), 0, 0, "Common");
        assertSummary(encode(42, -3, -1, 7), 1, 1, "Uncommon");
        assertSummary(encode(-1, 42, -3, -3), 2, 1, "Rare");
    }

    @Test public void countsZeroAndUnknownPositiveIdsWithoutDefinitionSetup() {
        assertSummary(encode(0, Short.MAX_VALUE, -1, -2), 3, 2, "Legendary");
        assertSummary(encode(Short.MAX_VALUE, Short.MAX_VALUE, 0, 0), 4, 4, "Divine");
    }

    @Test public void acceptsPaddedAndUnpaddedUrlAlphabet() {
        // Header 00 02 04, then little-endian entries -1 and 32766.
        assertSummary("AAIE___-fw==", 2, 1, "Rare");
        assertSummary("AAIE___-fw", 2, 1, "Rare");
    }

    @Test public void rejectsMalformedBase64InsteadOfTreatingItAsEmpty() {
        String[] invalid = {
            " ", "\n", "=", "A", "!!!!", "AAIE___-fw=",
            "AAIE_f_9__3__f8==", "AAIE///+fw==",
            " AAIE_f_9__3__f8=", "AAIE_f_9__3__f8=\n",
            "AAIE_f_9__3__f8=,AAIE_f_9__3__f8=",
            "AAIE_f_9__3__f8=\u2603"
        };
        for (String code : invalid) {
            assertUnknown(code);
        }
    }

    @Test public void validatesHeaderByteAndLittleEndianType() {
        assertUnknown(encodeBytes(1, 2, 4, 0, 0));
        assertUnknown(encodeBytes(255, 2, 4, 0, 0));
        assertUnknown(encodeBytes(0, 4, 2, 0, 0));
        assertUnknown(encodeBytes(0, 3, 4, 0, 0));
        assertUnknown(encodeBytes(0, 2, 5, 0, 0));
    }

    @Test public void rejectsMissingOrPartialHeaders() {
        assertUnknown(encodeBytes(0));
        assertUnknown(encodeBytes(0, 2));
        assertUnknown(encodeBytes(2, 4));
        assertUnknown(encodeBytes(2, 4, 0, 0));
    }

    @Test public void rejectsEachTruncatedSlotWithoutReturningPartialCounts() {
        byte[] complete = Base64.getUrlDecoder().decode(encode(0, 1, 2, 3));
        for (int length = 4; length < complete.length; length += 2) {
            String code = Base64.getUrlEncoder().encodeToString(
                Arrays.copyOf(complete, length)
            );
            assertUnknown(code);
        }
    }

    @Test public void rejectsPartialSlotEvenAfterUnusedTerminator() {
        assertUnknown(encodeBytes(0, 2, 4, 253, 255, 0));
    }

    @Test public void unexpectedNegativeIdsInvalidateTheWholeSummary() {
        assertUnknown(encode(-4));
        assertUnknown(encode(Short.MIN_VALUE));
        assertUnknown(encode(42, -1, -2, -4));
        assertUnknown(encode(-2, Short.MIN_VALUE, -3, -3));
    }

    @Test public void ignoresDecodedBytesBeyondTheElevenByteWindow() {
        assertSummary(encode(0, -1, -2, 42, -4), 3, 2, "Legendary");
        assertSummary(encode(0, 1, 2, 3, 4), 4, 4, "Divine");

        byte[] fourSlots = Base64.getUrlDecoder().decode(encode(-1, -1, -1, -1));
        String withTrailingByte = Base64.getUrlEncoder().encodeToString(
            Arrays.copyOf(fourSlots, fourSlots.length + 1)
        );
        assertSummary(withTrailingByte, 4, 0, "Divine");
    }

    @Test public void validatesBase64EvenBeyondTheDecodedWindow() {
        assertUnknown(encode(0, 1, 2, 3, 4) + "!");
    }

    private static void assertUnknown(String code) {
        assertSummary(code, -1, -1, "Unknown");
    }

    private static void assertSummary(String code, int slots, int applied, String rarity) {
        ParseEnchants.Summary summary = ParseEnchants.summarize(code);
        assertEquals("slots for " + code, slots, summary.slots);
        assertEquals("applied for " + code, applied, summary.applied);
        assertEquals("rarity for " + code, rarity, summary.rarity());
    }

    private static String encode(int... entries) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + entries.length * 2).order(
            ByteOrder.LITTLE_ENDIAN
        );
        buffer.put((byte) 0);
        buffer.putShort((short) 1026);
        for (int entry : entries) {
            buffer.putShort((short) entry);
        }
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }

    private static String encodeBytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
}
