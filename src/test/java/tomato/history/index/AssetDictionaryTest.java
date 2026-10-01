package tomato.history.index;

import java.util.*;
import org.junit.Test;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

public class AssetDictionaryTest {
    @Test public void nullAndBlankObjectAndClassNamesAreSkipped() {
        Map<Integer,String> objects=new HashMap<>(), classes=new HashMap<>();
        objects.put(101,"Blade"); objects.put(102,null); objects.put(103," \t");
        classes.put(301,"Mage"); classes.put(302,null); classes.put(303,"");
        Map<Short,ParseEnchants.Definition> enchants=Map.of((short)401,new ParseEnchants.Definition("Blessing",""));
        IndexDictionary dictionary=AssetDictionary.snapshot("a",objects,classes,enchants);
        assertEquals("Blade",dictionary.objectName(101)); assertEquals("Mage",dictionary.className(301));
        assertNull(dictionary.objectName(102)); assertNull(dictionary.objectName(103));
        assertNull(dictionary.className(302)); assertNull(dictionary.className(303));
        assertEquals(AssetDictionary.snapshot("a",Map.of(101,"Blade"),Map.of(301,"Mage"),enchants).version(),dictionary.version());
    }
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
    @Test public void legacyLayoutWithoutStampProducesCountsVersionAndEnrichment() {
        Map<Short,ParseEnchants.Definition> enchants=Map.of((short)401,new ParseEnchants.Definition("Blessing",""));
        AssetDictionary.Loaded loaded=AssetDictionary.load(null,Map.of(101,"Blade"),Map.of(301,"Mage"),enchants);
        IndexDictionary dictionary=loaded.dictionary();
        assertNotEquals("none",dictionary.version());
        assertEquals(1,loaded.objectNames()); assertEquals(1,loaded.enchantNames()); assertEquals(1,loaded.classNames());
        assertEquals(dictionary.version(),snapshot("",enchants).version());
        assertEquals(dictionary.version(),snapshot("  \t",enchants).version());
        assertNotEquals(dictionary.version(),snapshot("generation-a",enchants).version());
        assertNotEquals(dictionary.version(),snapshot(null,Map.of((short)401,new ParseEnchants.Definition("New Blessing",""))).version());
        Projections.Context runContext=new Projections.Context(new Locator(ProjectionsTest.SESSION,"runs",0,null,-1),500,false,"test",dictionary);
        Projections.Row run=Projections.project(runContext,ProjectionsTest.json("{id:'r',requestedItems:{101:1},inspectedPlayers:{'player:alice':{objectType:301}}}")).get(0);
        assertTrue(run.body().contains("Blade")); assertTrue(run.names().contains("Blade"));
        assertTrue(run.body().contains("Mage")); assertTrue(run.names().contains("Mage"));
        Projections.Context lootContext=new Projections.Context(new Locator(ProjectionsTest.SESSION,"loot",0,null,-1),500,false,"test",dictionary);
        Projections.Row loot=Projections.project(lootContext,ProjectionsTest.json("{items:[{name:'Blade',enchantEvidence:{orderedSlotIds:[401]}}]}")).get(1);
        assertTrue(loot.body().contains("Blessing")); assertTrue(loot.names().contains("Blessing"));
    }
    @Test public void anyEmptyDictionaryIsNoneWithOrWithoutStamp() {
        Map<Short,ParseEnchants.Definition> enchants=Map.of((short)401,new ParseEnchants.Definition("Blessing",""));
        for (String stamp:Arrays.asList(null,"","a")) {
            assertSame(IndexDictionary.NONE,snapshot(stamp,Map.of()));
            assertSame(IndexDictionary.NONE,AssetDictionary.snapshot(stamp,Map.of(),Map.of(301,"Mage"),enchants));
            assertSame(IndexDictionary.NONE,AssetDictionary.snapshot(stamp,Map.of(101,"Blade"),Map.of(),enchants));
            AssetDictionary.Loaded empty=AssetDictionary.load(stamp,Map.of(),Map.of(),Map.of());
            assertSame(IndexDictionary.NONE,empty.dictionary());
            assertEquals(0,empty.objectNames()); assertEquals(0,empty.enchantNames()); assertEquals(0,empty.classNames());
        }
    }
}
