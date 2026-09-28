package tomato.backend.data;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** pets.xml families from synthetic fixtures (the real file's structure is unverified), rarity and ability names, and the loader. */
public class PetDefinitionsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** Two pet types (hex and decimal), a skin without a family, a blank family, a duplicate and two objects without a usable type. */
    static final String PETS = "<Objects>"
        + "<Object type='0x7a01' id='Fixture Hound'><Class>Pet</Class><Family>Canine</Family><Rarity>Common</Rarity></Object>"
        + "<Object type='30000' id='Fixture Cat'><Family>  Feline  </Family></Object>"
        + "<Object type='0x7a02' id='Fixture Hound Skin'><Class>PetSkin</Class></Object>"
        + "<Object type='0x7a03' id='Fixture Blank'><Family>   </Family></Object>"
        + "<Object type='0x7a01' id='Fixture Duplicate'><Family>Aquatic</Family></Object>"
        + "<Object type='not a number'><Family>Avian</Family></Object>"
        + "<Object type='-5'><Family>Avian</Family></Object>"
        + "<Object><Family>Avian</Family></Object>"
        + "</Objects>";

    private static PetDefinitions parse(String xml) { return PetDefinitions.parse(xml.getBytes(StandardCharsets.UTF_8)); }
    private Path root(String xml) throws Exception {
        Path root = temp.newFolder().toPath(); Files.createDirectories(root.resolve("xml"));
        if (xml != null) Files.write(root.resolve("xml/pets.xml"), xml.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    @Test public void familiesComeFromObjectsWithANonBlankFamily() {
        PetDefinitions defs = parse(PETS);
        assertTrue(defs.available);
        assertEquals("Hex type; the duplicate keeps the first", "Canine", defs.family(0x7a01));
        assertEquals("Decimal type, trimmed", "Feline", defs.family(30000));
        assertNull("An object without a family is ignored", defs.family(0x7a02));
        assertNull("A blank family is unknown", defs.family(0x7a03));
        assertNull(defs.family(12345)); assertNull(defs.family(-5)); assertNull(defs.family(null));
        assertTrue(defs.status, defs.status.contains("2 pet types"));
        assertEquals("Objects may sit anywhere in the document", "Canine",
            parse("<Root><Pets><Object type='0X7A01'><Family>Canine</Family></Object></Pets></Root>").family(0x7a01));
        PetDefinitions odd = parse("<Objects><Object type='1'><Family><![CDATA[Farm]]><!-- note --></Family></Object>"
            + "<Object type='2'><Family>Can<b>ine</b></Family></Object><Object type='3'><Other><Family>Nested</Family></Other></Object>"
            + "<Object type='4'><Family>" + "x".repeat(257) + "</Family></Object></Objects>");
        assertEquals("CDATA is text, a comment is not", "Farm", odd.family(1));
        assertNull("Markup inside a family is not a name", odd.family(2));
        assertNull("Only a direct Family child names the object", odd.family(3));
        assertNull("An implausibly long family is ignored", odd.family(4));
    }

    @Test public void aDocumentWithoutFamiliesIsStillAvailable() {
        PetDefinitions defs = parse("<Objects><Object type='1'><Rarity>Common</Rarity></Object></Objects>");
        assertTrue("The file was read; names are unknown per pet", defs.available);
        assertNull(defs.family(1));
        assertTrue(defs.status, defs.status.contains("no pet families"));
        assertTrue(parse("<Objects/>").available);
    }

    @Test public void malformedOrUnsafeXmlIsUnavailable() {
        for (String xml : new String[]{"<Objects><Object type='1'><Family>Canine</Family>", "", "not xml",
            "<?xml version='1.0'?><!DOCTYPE Objects [<!ENTITY family 'Canine'>]><Objects><Object type='1'><Family>&family;</Family></Object></Objects>"}) {
            PetDefinitions defs = parse(xml);
            assertFalse(xml, defs.available); assertEquals(xml, PetDefinitions.unavailable().status, defs.status); assertNull(defs.family(1));
        }
    }

    @Test public void readUsesPetsXmlUnderTheRootAndRejectsMissingOrOversizedFiles() throws Exception {
        PetDefinitions read = PetDefinitions.read(root(PETS));
        assertTrue(read.available); assertEquals("Canine", read.family(0x7a01));
        assertFalse("No xml/pets.xml", PetDefinitions.read(root(null)).available);
        assertFalse("No xml folder", PetDefinitions.read(temp.newFolder().toPath()).available);
        assertFalse("Malformed file", PetDefinitions.read(root("<Objects>")).available);
        byte[] big = new byte[(int)PetDefinitions.MAX_BYTES + 1];
        Arrays.fill(big, (byte)' ');
        byte[] start = "<Objects><Object type='1'><Family>Canine</Family></Object>".getBytes(StandardCharsets.UTF_8), end = "</Objects>".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(start, 0, big, 0, start.length); System.arraycopy(end, 0, big, big.length - end.length, end.length);
        Path oversized = root(null); Files.write(oversized.resolve("xml/pets.xml"), big);
        PetDefinitions tooLarge = PetDefinitions.read(oversized);
        assertFalse("Over the size limit", tooLarge.available); assertNull(tooLarge.family(1));
        assertFalse("The parse seam applies the same limit", PetDefinitions.parse(big).available);
    }

    @Test public void rarityNamesUseTheGamesZeroBasedOrder() {
        assertEquals(Arrays.asList("Common", "Uncommon", "Rare", "Legendary", "Divine"),
            Arrays.asList(PetDefinitions.rarity(0), PetDefinitions.rarity(1), PetDefinitions.rarity(2), PetDefinitions.rarity(3), PetDefinitions.rarity(4)));
        assertNull("Never guessed", PetDefinitions.rarity(5)); assertNull(PetDefinitions.rarity(-1)); assertNull(PetDefinitions.rarity(null));
    }

    @Test public void abilityNamesMatchThePetsTabText() {
        // Copied from CharacterPetsGUI.abilityName so the later switch changes no visible text.
        assertEquals("Attack close", PetDefinitions.ability(402));
        assertEquals("Attack mid", PetDefinitions.ability(404)); assertEquals("Attack far", PetDefinitions.ability(405));
        assertEquals("Electric", PetDefinitions.ability(406)); assertEquals("Heal", PetDefinitions.ability(407));
        assertEquals("Magic heal", PetDefinitions.ability(408)); assertEquals("Savage", PetDefinitions.ability(409));
        assertEquals("Decoy", PetDefinitions.ability(410)); assertEquals("Rising fury", PetDefinitions.ability(411));
        for (int unknown : new int[]{403, 401, 412, 0, -1}) assertNull(String.valueOf(unknown), PetDefinitions.ability(unknown));
    }

    @Test public void loadingAndUnavailableSayWhy() {
        assertFalse(PetDefinitions.loading().available); assertFalse(PetDefinitions.unavailable().available);
        assertEquals("Pet names load with the selected game assets", PetDefinitions.loading().status);
        assertEquals("Pet names are unavailable in the selected game assets", PetDefinitions.unavailable().status);
        assertNull(PetDefinitions.loading().family(0x7a01));
    }

    @Test public void installPinsCurrentUntilTheHandleIsClosed() throws Exception {
        PetDefinitions outer = parse(PETS), inner = parse("<Objects/>");
        AutoCloseable first = PetDefinitions.install(outer);
        try {
            assertSame(outer, PetDefinitions.current());
            AutoCloseable second = PetDefinitions.install(inner);
            assertSame("The newest pin wins", inner, PetDefinitions.current());
            first.close();
            assertSame("Closing an older pin keeps the newer one", inner, PetDefinitions.current());
            second.close(); second.close();
            assertNotSame(inner, PetDefinitions.current());
        } finally { first.close(); }
        PetDefinitions released = PetDefinitions.current();
        assertNotSame(outer, released); assertNotSame(inner, released);
    }

    @Test public void currentReadsTheSelectedAssetsInTheBackgroundAndRereadsOnARootChange() throws Exception {
        Path withPets = root(PETS), without = root(null);
        Field generation = Class.forName("assets.AssetCache").getDeclaredField("current"); generation.setAccessible(true);
        Constructor<?> make = Class.forName("assets.AssetCache$Generation").getDeclaredConstructor(Path.class, String.class); make.setAccessible(true);
        Object previous = generation.get(null);
        try {
            generation.set(null, make.newInstance(withPets, null));
            PetDefinitions first = PetDefinitions.current();
            assertFalse("Never blocks: a new root reads as loading first", first.available);
            assertEquals(PetDefinitions.loading().status, first.status);
            PetDefinitions loaded = await(defs -> defs.available);
            assertEquals("Canine", loaded.family(0x7a01));
            assertSame("Stable until the root changes", loaded, PetDefinitions.current());

            generation.set(null, make.newInstance(without, null));
            assertEquals(PetDefinitions.loading().status, PetDefinitions.current().status);
            PetDefinitions missing = await(defs -> PetDefinitions.unavailable().status.equals(defs.status));
            assertNull(missing.family(0x7a01));

            generation.set(null, make.newInstance(withPets, null));
            assertEquals("Canine", await(defs -> defs.available).family(0x7a01));
        } finally {
            generation.set(null, previous);
            PetDefinitions.current(); // requests the original root again so later callers see its names
        }
    }

    private static PetDefinitions await(Predicate<PetDefinitions> done) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            PetDefinitions defs = PetDefinitions.current();
            if (done.test(defs)) return defs;
            if (System.nanoTime() > end) fail("The pet definitions reader did not finish: " + defs.status);
            Thread.sleep(10);
        }
    }
}
