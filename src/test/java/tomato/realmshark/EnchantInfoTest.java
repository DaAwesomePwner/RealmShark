package tomato.realmshark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.realmshark.EnchantInfo.Rarity;
import tomato.realmshark.EnchantInfo.Slot;
import tomato.realmshark.EnchantInfo.State;
import static org.junit.Assert.*;

/** The one decoded form every item surface shows; no enchantment definitions or assets needed except where stated. */
public class EnchantInfoTest {

    @Test public void rarityIsTheUnlockedSlotCount() {
        assertEquals(Rarity.UNENCHANTED, EnchantInfo.of(encode()).rarity());
        assertEquals(Rarity.UNCOMMON, EnchantInfo.of(encode(-1)).rarity());
        assertEquals(Rarity.RARE, EnchantInfo.of(encode(-1, 42)).rarity());
        assertEquals(Rarity.LEGENDARY, EnchantInfo.of(encode(-1, 42, 7)).rarity());
        EnchantInfo divine = EnchantInfo.of(encode(-1, -1, -1, -1));
        assertEquals(Rarity.DIVINE, divine.rarity());
        assertEquals(List.of(new Slot(-1), new Slot(-1), new Slot(-1), new Slot(-1)), divine.slots());
    }

    @Test public void anAppliedEnchantAfterALockedOrEmptySlotIsListed() {
        EnchantInfo info = EnchantInfo.of(encode(-2, -1, 42));
        assertEquals(State.RECORDED, info.state());
        assertEquals(Rarity.RARE, info.rarity());
        assertEquals(List.of(new Slot(-1), new Slot(42)), info.slots());
    }

    @Test public void theTerminatorEndsTheSlots() {
        assertEquals(List.of(new Slot(42)), EnchantInfo.of(encode(42, -3, 7)).slots());
    }

    @Test public void paddedAndUnpaddedSpellingsDecodeAlike() {
        assertEquals(EnchantInfo.of("AAIE_wU="), EnchantInfo.of("AAIE_wU"));
        assertEquals(List.of(new Slot(0x5ff)), EnchantInfo.of("AAIE_wU").slots());
    }

    @Test public void theCommonUnusedBlobAndTheEmptyStringAreUnenchanted() {
        for (String code : new String[] {"AAIE_f_9__3__f8=", "AAIE_f_9__3__f8", "", "AAIE"}) {
            EnchantInfo info = EnchantInfo.of(code);
            assertEquals(code, State.RECORDED, info.state());
            assertEquals(code, Rarity.UNENCHANTED, info.rarity());
            assertFalse(code, info.enchanted());
            assertEquals("Unenchanted", info.summary());
        }
    }

    @Test public void missingDataIsNotRecordedAndBadDataIsUnreadable() {
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.of((String) null));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.fromEvidence(null));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.fromEvidence(ParseEnchants.legacyEvidence()));
        for (String bad : new String[] {"!!!", "AA==", "AAAE", encode(-4), "A".repeat(4097)}) {
            EnchantInfo info = EnchantInfo.of(bad);
            assertSame(bad, EnchantInfo.unreadable(), info);
            assertEquals(Rarity.UNKNOWN, info.rarity());
            assertFalse(info.enchanted());
        }
        assertEquals("Enchants not recorded", EnchantInfo.notRecorded().summary());
        assertEquals("Enchant data unreadable", EnchantInfo.unreadable().summary());
    }

    /** ParseEnchants.getEnchantStrings turns a missing stat into "", so a retained "" cannot claim unenchanted. */
    @Test public void aRetainedEmptyStringIsNotRecorded() {
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(""));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(null));
        assertEquals(Rarity.UNCOMMON, EnchantInfo.ofRetained(encode(-1)).rarity());
    }

    @Test public void summaryAndSlotLinesNameEachSlot() {
        HashMap<Short, ParseEnchants.Definition> saved = ParseEnchants.ENCHANT_DEFINITIONS;
        try {
            HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
            definitions.put((short) 42, new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"));
            definitions.put((short) 9, new ParseEnchants.Definition("Plain", ""));
            ParseEnchants.ENCHANT_DEFINITIONS = definitions;
            EnchantInfo info = EnchantInfo.of(encode(42, -1, 7, 9));
            assertTrue(info.enchanted());
            assertEquals("Divine · 4 enchant slots", info.summary());
            assertEquals(List.of("Attack Bonus I — Increases Attack by 1.4", "(empty slot)", "Unknown enchant (0x7)", "Plain"), info.slotLines());
            assertEquals("Divine · 4 enchant slots\n  Attack Bonus I — Increases Attack by 1.4\n  (empty slot)\n  Unknown enchant (0x7)\n  Plain", info.text());
            assertEquals("Uncommon · 1 enchant slot", EnchantInfo.of(encode(-1)).summary());
        } finally {
            ParseEnchants.ENCHANT_DEFINITIONS = saved;
        }
    }

    @Test public void equippedCaptureInfoFollowsEachSlotsState() {
        Entity player = new Entity(null, 1, 0);
        StatData stat = new StatData();
        stat.stringStatValue = "AAIE_wU,,!!!"; // weapon one applied, ability "", armor malformed, ring missing
        player.stat.set(StatType.UNIQUE_DATA_STRING, stat);
        ParseEnchants.EquippedCapture capture = ParseEnchants.equippedCapture(player);
        assertEquals(Rarity.UNCOMMON, capture.info(0).rarity());
        assertEquals(Rarity.UNENCHANTED, capture.info(1).rarity());
        assertSame(EnchantInfo.unreadable(), capture.info(2));
        assertSame(EnchantInfo.notRecorded(), capture.info(3));
    }

    @Test public void aSlotCountAloneGivesTheRarityWithoutNames() {
        EnchantInfo legendary = EnchantInfo.ofSlotCount(3);
        assertEquals(State.COUNT_ONLY, legendary.state());
        assertEquals(Rarity.LEGENDARY, legendary.rarity());
        assertEquals(List.of(), legendary.slots());
        assertTrue("A known rarity shows its gem", legendary.enchanted());
        assertEquals("Legendary · 3 enchant slots", legendary.summary());
        assertEquals(List.of(EnchantInfo.NAMES_NOT_AVAILABLE), legendary.slotLines());
        assertEquals("Legendary · 3 enchant slots\n  Enchant names not available", legendary.text());
        assertEquals("Uncommon · 1 enchant slot", EnchantInfo.ofSlotCount(1).summary());
        EnchantInfo none = EnchantInfo.ofSlotCount(0);
        assertEquals("Unenchanted", none.summary());
        assertEquals("Nothing to name on an unenchanted item", List.of(), none.slotLines());
        assertFalse(none.enchanted());
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofSlotCount(null));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofSlotCount(-1));
        assertSame(EnchantInfo.unreadable(), EnchantInfo.ofSlotCount(5));
    }

    @Test public void partialEvidenceNeverThrows() throws Exception {
        ParseEnchants.Evidence noState = tomato.history.SessionStore.JSON.fromJson("{\"slots\":2,\"applied\":1}", ParseEnchants.Evidence.class);
        assertSame("No state: not recorded", EnchantInfo.notRecorded(), EnchantInfo.fromEvidence(noState));
        ParseEnchants.Evidence noIds = tomato.history.SessionStore.JSON.fromJson("{\"state\":\"RECORDED\",\"slots\":2,\"applied\":1}", ParseEnchants.Evidence.class);
        EnchantInfo fallback = EnchantInfo.fromEvidence(noIds);
        assertEquals("No ordered ids: the saved slot count still gives the rarity", State.COUNT_ONLY, fallback.state());
        assertEquals(Rarity.RARE, fallback.rarity());
        ParseEnchants.Evidence noCount = tomato.history.SessionStore.JSON.fromJson("{\"state\":\"RECORDED\"}", ParseEnchants.Evidence.class);
        assertSame("No ids and no saved count: not recorded, never a false Unenchanted", EnchantInfo.notRecorded(), EnchantInfo.fromEvidence(noCount));
        ParseEnchants.Evidence nullId = tomato.history.SessionStore.JSON.fromJson("{\"state\":\"RECORDED\",\"orderedSlotIds\":[-1,null],\"slots\":2,\"applied\":0}", ParseEnchants.Evidence.class);
        assertSame("A missing id inside the list: unreadable, never a throw", EnchantInfo.unreadable(), EnchantInfo.fromEvidence(nullId));
    }

    @Test public void aRecordWithTheUnknownRarityClaimsNoCount() {
        assertEquals("Enchant data unreadable", new EnchantInfo(EnchantInfo.State.COUNT_ONLY, EnchantInfo.Rarity.UNKNOWN, List.of()).summary());
        assertEquals("Enchant data unreadable",
            new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNKNOWN, List.of(new EnchantInfo.Slot(-1))).summary());
        assertFalse(new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNKNOWN, List.of()).enchanted());
    }

    private static String encode(int... entries) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + entries.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0);
        buffer.putShort((short) 1026);
        for (int entry : entries) buffer.putShort((short) entry);
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
}
