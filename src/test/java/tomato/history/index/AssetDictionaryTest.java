package tomato.history.index;

import java.util.*;
import org.junit.Test;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

public class AssetDictionaryTest {
    @Test public void loadedCountsDescribeTheSameSnapshotUsedForProjection() {
        Map<Integer,String> objects=Map.of(101,"Blade",102,"Shield"), classes=Map.of(301,"Mage");
        Map<Short,ParseEnchants.Definition> enchants=Map.of((short)401,new ParseEnchants.Definition("Blessing",""));
        AssetDictionary.Loaded loaded=AssetDictionary.load("generation-a",objects,classes,enchants);
        assertEquals(2,loaded.objectNames()); assertEquals(1,loaded.enchantNames()); assertEquals(1,loaded.classNames());
        assertEquals(AssetDictionary.snapshot("generation-a",objects,classes,enchants).version(),loaded.dictionary().version());
        assertEquals("Blade",loaded.dictionary().objectName(101));
        assertEquals("Blessing",loaded.dictionary().enchantName(401)); assertEquals("Mage",loaded.dictionary().className(301));
        AssetDictionary.Loaded partial=AssetDictionary.load("generation-a",objects,classes,Map.of());
        assertSame(IndexDictionary.NONE,partial.dictionary()); assertEquals(2,partial.objectNames());
        assertEquals(0,partial.enchantNames()); assertEquals(1,partial.classNames());
    }
    private static IndexDictionary snapshot(String stamp,Map<Short,ParseEnchants.Definition> enchants) {
        return AssetDictionary.snapshot(stamp,Map.of(101,"Blade"),Map.of(301,"Mage"),enchants);
    }
    @Test public void versionsAreStableAndIncludeStampNamesAndEnchantDescriptions() {
        Map<Short,ParseEnchants.Definition> definitions=new LinkedHashMap<>();
        definitions.put((short)401,new ParseEnchants.Definition("Blessing","More health"));
        definitions.put((short)402,new ParseEnchants.Definition("Fortune","More loot"));
        IndexDictionary first=snapshot("generation-a",definitions);
        Map<Short,ParseEnchants.Definition> reversed=new LinkedHashMap<>();
        reversed.put((short)402,definitions.get((short)402)); reversed.put((short)401,definitions.get((short)401));
        assertEquals(first.version(),snapshot("generation-a",reversed).version());
        assertNotEquals(first.version(),snapshot("generation-b",definitions).version());
        definitions.put((short)401,new ParseEnchants.Definition("Blessing","Even more health"));
        assertNotEquals(first.version(),snapshot("generation-a",definitions).version());
        definitions.put((short)401,new ParseEnchants.Definition("New Blessing","More health"));
        assertNotEquals(first.version(),snapshot("generation-a",definitions).version());
        assertEquals("Blessing",first.enchantName(401));
        assertNull(first.enchantName(999)); assertNull(first.enchantName(65937)); assertNull(first.objectName(999)); assertNull(first.className(999));
        assertNotEquals(first.version(),AssetDictionary.snapshot("generation-a",Map.of(101,"New Blade"),Map.of(301,"Mage"),reversed).version());
    }
    @Test public void missingGenerationOrAnyEmptyDictionaryIsNone() {
        Map<Short,ParseEnchants.Definition> enchants=Map.of((short)401,new ParseEnchants.Definition("Blessing",""));
        assertSame(IndexDictionary.NONE,snapshot(null,enchants));
        assertSame(IndexDictionary.NONE,snapshot("",enchants));
        assertSame(IndexDictionary.NONE,snapshot("a",Map.of()));
        assertSame(IndexDictionary.NONE,AssetDictionary.snapshot("a",Map.of(),Map.of(301,"Mage"),enchants));
        assertSame(IndexDictionary.NONE,AssetDictionary.snapshot("a",Map.of(101,"Blade"),Map.of(),enchants));
    }
}
