package tomato.gui.myinfo;

import java.awt.Component;
import java.awt.Container;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.StatTile;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;
import static tomato.gui.modern.FormattingTestSupport.field;

/** Build's two estimate KPIs, extracted for Home; the Build page must keep showing exactly these numbers. */
public class BuildEstimatesTest {
    private static final int WEAPON = 999_001;
    private static final TomatoData.PetAvailability UNKNOWN = TomatoData.PetAvailability.UNKNOWN, PRESENT = TomatoData.PetAvailability.PRESENT;

    private static void put(Entity entity, StatType type, int value) { StatData stat = new StatData(); stat.statValue = value; entity.stat.set(type, stat); }
    private static void text(Entity entity, StatType type, String value) { StatData stat = new StatData(); stat.stringStatValue = value; entity.stat.set(type, stat); }

    /** Attack and dexterity 75, exaltation ×1.0, wisdom 75, known-empty enchants, the fixture weapon equipped. */
    private static Entity build(Entity player) {
        put(player, StatType.ATTACK_STAT, 75); put(player, StatType.DEXTERITY_STAT, 75); put(player, StatType.EXALTATION_BONUS_DAMAGE, 1000);
        put(player, StatType.INVENTORY_0_STAT, WEAPON); put(player, StatType.WISDOM_STAT, 75); put(player, StatType.MAX_MP_STAT, 400);
        text(player, StatType.UNIQUE_DATA_STRING, "");
        return player;
    }

    @Test public void weaponDpsSumsEveryProjectileGroupAndMissingInputsStayUnavailable() throws Exception {
        try (AutoCloseable weapon = WeaponFixture.install(WEAPON, WeaponFixture.bullet(100, 200, 2, 1.5f), WeaponFixture.bullet(50, 50, 1, 1f))) {
            Entity player = build(new Entity(null, 1, 0));
            // 150 × 1.0 × 2.0 × 2 × 8.0 × 1.5 = 7200, plus 50 × 1.0 × 2.0 × 1 × 8.0 × 1 = 800.
            BuildEstimates.Estimates estimates = BuildEstimates.of(player, null, UNKNOWN, false);
            assertEquals(8000d, estimates.weaponDps(), 1e-9);
            assertNull("Unknown pet metadata leaves mana unavailable, not zero", estimates.mpPerSecond());
            put(player, StatType.INVENTORY_0_STAT, -1);
            assertNull(BuildEstimates.of(player, null, UNKNOWN, false).weaponDps());
            put(player, StatType.INVENTORY_0_STAT, WEAPON); player.stat.set(StatType.EXALTATION_BONUS_DAMAGE, null);
            assertNull(BuildEstimates.of(player, null, UNKNOWN, false).weaponDps());
            BuildEstimates.Estimates none = BuildEstimates.of(null, null, UNKNOWN, false);
            assertNull(none.weaponDps()); assertNull(none.mpPerSecond());
        }
    }

    @Test public void manaPerSecondAddsWisdomEnchantsAndMagicHeal() {
        Entity player = build(new Entity(null, 1, 0)), pet = new Entity(null, 2, 0);
        assertEquals("75 wisdom × 0.12, no enchant effects, no pet", 9d,
            BuildEstimates.of(player, null, TomatoData.PetAvailability.ABSENT, false).mpPerSecond(), 1e-9);
        put(pet, StatType.PET_FIRST_ABILITY_TYPE_STAT, BuildEstimates.MAGIC_HEAL); put(pet, StatType.PET_FIRST_ABILITY_POWER_STAT, 100);
        assertEquals("Plus level-100 Magic Heal: 45 mana every 1.00 s", 54d, BuildEstimates.of(player, pet, PRESENT, false).mpPerSecond(), 1e-9);
        player.stat.set(StatType.UNIQUE_DATA_STRING, null);
        assertNull("Enchant data not captured", BuildEstimates.of(player, pet, PRESENT, false).mpPerSecond());
    }

    @Test public void buildPageKpiCardsShowExactlyTheseEstimates() throws Exception {
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = build(new Entity(data, 1, 0)); data.player = player;
        text(player, StatType.ACCOUNT_ID_STAT, "A");
        MyInfoGUI[] view = new MyInfoGUI[1];
        try (AutoCloseable weapon = WeaponFixture.install(WEAPON, 100, 200, 2, 1.5f)) {
            SwingUtilities.invokeAndWait(() -> view[0] = new MyInfoGUI(data));
            MyInfoGuiTest.equipPet(data, BuildEstimates.MAGIC_HEAL); // Publishes the player with a known Magic Heal pet.
            SwingUtilities.invokeAndWait(() -> {
                StatTile[] summary = field(view[0], "summary", StatTile[].class);
                for (boolean outOfCombat : new boolean[]{false, true}) {
                    BuildEstimates.Estimates expected = BuildEstimates.of(player, data.pet, PRESENT, outOfCombat);
                    assertEquals(7200d, expected.weaponDps(), 1e-9); assertEquals(54d, expected.mpPerSecond(), 1e-9);
                    assertEquals(DisplayValue.estimate(DisplayFormat.formatNumber(expected.weaponDps(), 0, 2), "-").text(), summary[2].valueText());
                    assertEquals(DisplayValue.estimate(DisplayFormat.formatNumber(expected.mpPerSecond(), 0, 2), "-").text(), summary[3].valueText());
                    assertEquals(expected.weaponDps(), detail(view[0], "Weapon total"));
                    assertEquals(expected.mpPerSecond(), detail(view[0], "Estimated mana recovery"));
                    if (!outOfCombat) find(view[0], JCheckBox.class).doClick(); // "Estimate scenario: out of combat"
                }
            });
        }
    }

    @Test public void detachedInputsEstimateLikeTheEntitiesTheyCopiedAndIgnoreLaterPackets() throws Exception {
        try (AutoCloseable weapon = WeaponFixture.install(WEAPON, 100, 200, 2, 1.5f)) {
            Entity player = build(new Entity(null, 1, 0)), pet = new Entity(null, 2, 0);
            put(pet, StatType.PET_FIRST_ABILITY_TYPE_STAT, BuildEstimates.MAGIC_HEAL); put(pet, StatType.PET_FIRST_ABILITY_POWER_STAT, 100);
            BuildEstimates.Inputs inputs = BuildEstimates.Inputs.detach(player, pet, PRESENT);
            assertEquals("Build's in-combat scenario", BuildEstimates.of(player, pet, PRESENT, false), inputs.estimate());
            put(player, StatType.ATTACK_STAT, 25); put(pet, StatType.PET_FIRST_ABILITY_POWER_STAT, 1);
            BuildEstimates.Estimates detached = inputs.estimate();
            assertEquals(7200d, detached.weaponDps(), 1e-9); assertEquals(54d, detached.mpPerSecond(), 1e-9);
            assertNotEquals("Later packets change only the live entities", detached, BuildEstimates.of(player, pet, PRESENT, false));
            BuildEstimates.Estimates none = BuildEstimates.Inputs.detach(null, null, null).estimate();
            assertNull(none.weaponDps()); assertNull(none.mpPerSecond());
        }
    }

    private static Object detail(MyInfoGUI view, String name) {
        javax.swing.table.TableModel model = find(view, JTable.class).getModel();
        for (int i = 0; i < model.getRowCount(); i++) if (name.equals(model.getValueAt(i, 1))) return model.getValueAt(i, 2);
        throw new AssertionError("Missing detail: " + name);
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container) c, type); if (found != null) return found; }
        }
        return null;
    }
}
