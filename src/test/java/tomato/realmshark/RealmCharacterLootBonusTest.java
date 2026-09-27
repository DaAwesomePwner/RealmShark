package tomato.realmshark;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import tomato.realmshark.enums.CharacterClass;
import static org.junit.Assert.*;

/** The exalt loot boost over explicit saved counts (class id -> 8 counts in RealmCharacter order) and weapon groups. */
public class RealmCharacterLootBonusTest {
    private static int[] all(int count) { int[] counts = new int[8]; Arrays.fill(counts, count); return counts; }
    /** Seven counts at 75 and one at {@code lowest}. */
    private static int[] lowest(int lowest) { int[] counts = all(75); counts[3] = lowest; return counts; }
    private static Map<Integer, int[]> classes(int first, int count, int[] counts) {
        Map<Integer, int[]> map = new TreeMap<>();
        for (int i = 0; i < count; i++) map.put(first + i, counts.clone());
        return map;
    }

    @Test public void everyTierThresholdGivesItsBoost() {
        int[] group = {1, 2};
        int[][] cases = {{0, 0}, {4, 0}, {5, 5}, {14, 5}, {15, 10}, {29, 10}, {30, 15}, {49, 15}, {50, 20}, {74, 20}, {75, 25}, {300, 25}};
        for (int[] c : cases) {
            Map<Integer, int[]> exalts = new TreeMap<>();
            exalts.put(1, all(75)); exalts.put(2, lowest(c[0]));
            assertEquals("Lowest count " + c[0], c[1], RealmCharacter.exaltLootBonus(exalts, group));
        }
        Map<Integer, int[]> mixed = new TreeMap<>();
        mixed.put(1, lowest(20)); mixed.put(2, lowest(60));
        assertEquals("The lowest tier across the whole weapon group", 10, RealmCharacter.exaltLootBonus(mixed, group));
        mixed.put(3, all(0));
        assertEquals("A class outside the group does not count", 10, RealmCharacter.exaltLootBonus(mixed, group));
    }

    @Test public void aGroupMemberWithoutCountsGivesNoBoost() {
        Map<Integer, int[]> exalts = new TreeMap<>();
        exalts.put(1, all(75));
        assertEquals(0, RealmCharacter.exaltLootBonus(exalts, new int[]{1, 2}));
        assertEquals(25, RealmCharacter.exaltLootBonus(exalts, new int[]{1}));
    }

    @Test public void nineteenFullyExaltedClassesGiveThirtyFiveAndEighteenDoNot() {
        Map<Integer, int[]> nineteen = classes(100, 19, all(75));
        assertTrue(RealmCharacter.fullyExalted(nineteen));
        assertEquals(35, RealmCharacter.exaltLootBonus(nineteen, new int[]{100, 101}));
        assertEquals("Fully exalted applies to every class", 35, RealmCharacter.exaltLootBonus(nineteen, new int[]{999}));
        Map<Integer, int[]> eighteen = classes(100, 18, all(75));
        assertFalse(RealmCharacter.fullyExalted(eighteen));
        assertEquals(25, RealmCharacter.exaltLootBonus(eighteen, new int[]{100, 101}));
        Map<Integer, int[]> oneShort = classes(100, 19, all(75));
        oneShort.put(118, lowest(74));
        assertFalse("Every count must reach 75", RealmCharacter.fullyExalted(oneShort));
        assertEquals(20, RealmCharacter.exaltLootBonus(oneShort, new int[]{117, 118}));
        assertFalse(RealmCharacter.fullyExalted(new TreeMap<>()));
    }

    @Test public void aMissingWeaponGroupIsAnError() {
        try { RealmCharacter.exaltLootBonus(classes(100, 19, all(75)), null); fail("A null weapon group must throw"); }
        catch (NullPointerException expected) { }
    }

    @Test public void theLiveFormDelegatesToTheLiveCountsAndTheAssetWeaponGroup() throws Exception {
        TreeMap<Integer, int[]> previousExalts = RealmCharacter.exalts;
        Field weapons = CharacterClass.class.getDeclaredField("WEAPON_CLASSES"); weapons.setAccessible(true);
        Object previousWeapons = weapons.get(null);
        try {
            TreeMap<Integer, int[]> groups = new TreeMap<>();
            groups.put(9001, new int[]{9001, 9002}); groups.put(9002, new int[]{9001, 9002}); groups.put(9003, new int[]{9003});
            weapons.set(null, groups);
            TreeMap<Integer, int[]> live = new TreeMap<>();
            live.put(9001, lowest(31)); live.put(9002, lowest(52));
            RealmCharacter.exalts = live;
            assertEquals(15, RealmCharacter.exaltLootBonus(9001));
            assertEquals(RealmCharacter.exaltLootBonus(live, groups.get(9002)), RealmCharacter.exaltLootBonus(9002));
            assertEquals("A group member without live counts", 0, RealmCharacter.exaltLootBonus(9003));
            RealmCharacter.exalts = new TreeMap<>(classes(9001, 19, all(75)));
            assertEquals(35, RealmCharacter.exaltLootBonus(9003));
        } finally {
            RealmCharacter.exalts = previousExalts;
            weapons.set(null, previousWeapons);
        }
    }
}
