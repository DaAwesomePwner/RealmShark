package tomato.realmshark;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DungeonCatalogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path directory() { return temp.getRoot().toPath(); }
    private void write(String name, String xml) throws Exception {
        Files.write(directory().resolve(name), xml.getBytes(StandardCharsets.UTF_8));
    }
    private void baseFiles() throws Exception {
        write("mods.xml", "<DungeonModifiers><DungeonModifier id='FEEBLEMINIONS_1' type='0x62'/></DungeonModifiers>");
        write("portals.xml", "<Objects><Object type='0x1234'><DungeonName>The Shatters</DungeonName></Object></Objects>");
    }
    @Test public void absentMods2PreservesModifiersAndPortalIcons() throws Exception {
        baseFiles();
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertArrayEquals(new int[]{98,-15}, catalog.getModIds("FEEBLEMINIONS_1;|D"));
        assertEquals(0x1234,catalog.getPortalId("The Shatters"));
    }
    @Test public void additionalModifierFileStillLoadsWhenPresent() throws Exception {
        baseFiles(); write("mods2.xml", "<DungeonModifiers><DungeonModifier id='EXTRA' type='0x8001'/></DungeonModifiers>");
        assertArrayEquals(new int[]{98,32769},DungeonCatalog.load(directory()).getModIds("FEEBLEMINIONS_1;EXTRA"));
    }
    @Test public void missingAssetsAndUnknownNamesHaveSafeFallbacks() {
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertArrayEquals(new int[]{-11},catalog.getModIds("UNKNOWN;|S"));
        assertArrayEquals(new int[0],catalog.getModIds(null));
        assertEquals(-1,catalog.getPortalId("Unknown"));
        assertEquals(1796,catalog.getPortalId("Realm of the Mad God"));
    }
    @Test public void malformedOptionalFileCannotDiscardValidFiles() throws Exception {
        baseFiles(); write("mods2.xml", "<broken>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertArrayEquals(new int[]{98},catalog.getModIds("FEEBLEMINIONS_1"));
        assertEquals(0x1234,catalog.getPortalId("The Shatters"));
    }
    @Test public void invalidRowDoesNotPreventLaterValidModifiers() throws Exception {
        baseFiles();
        write("mods2.xml", "<DungeonModifiers><DungeonModifier id='BAD' type='bad'/><DungeonModifier id='GOOD' type='0x123'/></DungeonModifiers>");
        assertArrayEquals(new int[]{291},DungeonCatalog.load(directory()).getModIds("BAD;GOOD"));
    }
}
