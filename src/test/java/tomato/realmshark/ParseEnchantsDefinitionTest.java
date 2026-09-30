package tomato.realmshark;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.Test;
import util.StringXML;
import static org.junit.Assert.assertEquals;

/** Display names and descriptions for tooltips, read from enchantments.xml alongside the existing maps. */
public class ParseEnchantsDefinitionTest {
    private static final String ATTACK = "<Enchantment id=\"Attack_Bonus_1\" type=\"0x107\">\n  <DisplayId>Attack Bonus I</DisplayId>\n"
        + "  <Description>Increases Attack by 1.4</Description>\n  <Weight>35000</Weight>\n</Enchantment>";

    private static StringXML enchantment(String xml) throws Exception {
        for (StringXML node : StringXML.getParsedXML("<Enchantments>" + xml + "</Enchantments>"))
            if ("Enchantment".equals(node.name)) return node;
        throw new AssertionError("No <Enchantment> in " + xml);
    }

    @Test public void readsTheDisplayNameAndDescription() throws Exception {
        assertEquals(new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"), ParseEnchants.readDefinition(enchantment(ATTACK)));
    }

    @Test public void fallsBackToTheInternalIdAndAnEmptyDescription() throws Exception {
        assertEquals(new ParseEnchants.Definition("UNIQUE_THING", ""),
            ParseEnchants.readDefinition(enchantment("<Enchantment id=\"UNIQUE_THING\" type=\"0x9\"><DisplayId/></Enchantment>")));
    }

    @Test public void unknownTypesNameTheRawTypeInHex() {
        HashMap<Short, ParseEnchants.Definition> saved = ParseEnchants.ENCHANT_DEFINITIONS;
        try {
            ParseEnchants.ENCHANT_DEFINITIONS = new HashMap<>();
            assertEquals(new ParseEnchants.Definition("Unknown enchant (0x5ff)", ""), ParseEnchants.definition(0x5ff));
        } finally {
            ParseEnchants.ENCHANT_DEFINITIONS = saved;
        }
    }

    @Test public void reloadPublishesDefinitionsWithTheOtherMaps() throws Exception {
        String[] maps = {"ENCHANTS", "ENCHANT_EFFECTS", "ENCHANT_REGEN", "ENCHANT_LOOT_BONUS", "ENCHANT_DEFINITIONS"};
        Object[] saved = new Object[maps.length];
        for (int i = 0; i < maps.length; i++) saved[i] = field(maps[i]).get(null);
        Path xml = Files.createTempFile("enchantments", ".xml");
        try {
            Files.write(xml, ("<Enchantments>" + ATTACK + "</Enchantments>").getBytes(StandardCharsets.UTF_8));
            ParseEnchants.prepareReload(xml).run();
            assertEquals("Attack Bonus I", ParseEnchants.definition(0x107).displayName());
            assertEquals("Attack_Bonus_1", ParseEnchants.ENCHANTS.get((short) 0x107));
        } finally {
            for (int i = 0; i < maps.length; i++) field(maps[i]).set(null, saved[i]);
            Files.deleteIfExists(xml);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = ParseEnchants.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
