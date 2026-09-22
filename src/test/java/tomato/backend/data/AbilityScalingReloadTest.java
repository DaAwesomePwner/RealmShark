package tomato.backend.data;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import static org.junit.Assert.*;

public class AbilityScalingReloadTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AbilityScalingManager manager = AbilityScalingManager.getInstance();
    private Field field;
    private Object previous;
    @Before public void save() throws Exception { field = AbilityScalingManager.class.getDeclaredField("rules"); field.setAccessible(true); previous = field.get(manager); }
    @After public void restore() throws Exception { field.set(manager, previous); }

    @Test public void replacementDropsRemovedScalingAndProjectileMappingsWhileFailureRetainsUsableRules() throws Exception {
        Entity player = new Entity(null, 1, 0); StatData wisdom = new StatData(); wisdom.statValue = 75; player.stat.set(StatType.WISDOM_STAT, wisdom);
        manager.prepareReload(xml("<Objects><Object type='0x100' id='Ability'><Activate type='0x200' scalingStat='WIS' statModDamage='2' statModScalingMin='50'>Shoot</Activate>"
            + "</Object><Object type='0x200' id='AbilityProj'/></Objects>")).run();
        assertEquals(50, manager.calculateStatBonus(0x100, player));
        assertEquals(50, manager.calculateStatBonus(0x200, player));
        Object usable = field.get(manager);
        try { manager.prepareReload(xml("<Objects><Object type='0x100'><Activate scalingStat='WIS' statModDamage='bad'/></Object></Objects>")); fail(); }
        catch (NumberFormatException expected) { }
        assertSame(usable, field.get(manager)); assertEquals(50, manager.calculateStatBonus(0x200, player));
        Runnable replacement = manager.prepareReload(xml("<Objects><Object type='0x100' id='No scaling'/></Objects>"));
        assertEquals(50, manager.calculateStatBonus(0x100, player));
        replacement.run();
        assertEquals(0, manager.getScalingAbilityCount());
        assertEquals(0, manager.calculateStatBonus(0x100, player)); assertEquals(0, manager.calculateStatBonus(0x200, player));
        assertFalse(manager.hasScaling(0x100)); assertFalse(manager.hasScaling(0x200));
    }
    private Path xml(String value) throws Exception { Path file = temp.newFile().toPath(); Files.write(file, value.getBytes(StandardCharsets.UTF_8)); return file; }
}
