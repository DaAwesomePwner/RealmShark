package tomato.gui.kit;

import java.util.Locale;
import org.junit.*;
import static org.junit.Assert.*;

public class DisplayValueTest {
    private Locale previous;
    @Before public void fix() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void unknownIsNeverZero() {
        DisplayValue unknown = DisplayValue.unknown("Visit the Pet Yard with capture on to load pets");
        assertEquals("—", unknown.text());
        assertTrue(unknown.dimmed());
        assertEquals("Visit the Pet Yard with capture on to load pets", unknown.tooltip());
        DisplayValue zero = DisplayValue.zero("Counted from 3 saved runs");
        assertEquals("0", zero.display());
        assertFalse(zero.dimmed());
    }

    @Test public void statesCarryTheirMarkers() {
        String approximate = DisplayValue.estimate("1,240", "Weapon formula").text();
        assertTrue(approximate, approximate.equals("\u2248 1,240") || approximate.equals("~ 1,240"));
        assertEquals("5 (manual)", DisplayValue.manual("5", "Entered 2026-09-26").display());
        assertEquals("7 (partial)", DisplayValue.partial("7", "Backpack not captured").display());
        DisplayValue stale = DisplayValue.stale("1,240", "Captured 2 h ago");
        assertEquals("1,240 (stale)", stale.display());
        assertTrue(stale.dimmed());
        assertEquals("1,240", DisplayValue.known("1,240", "Live").display());
        assertNull(DisplayValue.known("1,240", "").tooltip());
    }

    @Test public void countMapsNullZeroAndValues() {
        assertEquals(DisplayValue.State.UNKNOWN, DisplayValue.count(null, "Saved runs", "Not recorded").state);
        assertEquals(DisplayValue.State.ZERO, DisplayValue.count(0L, "Saved runs", "Not recorded").state);
        assertEquals("12,345", DisplayValue.count(12_345L, "Saved runs", "Not recorded").display());
    }

    @Test public void equalityIsByValue() {
        assertEquals(DisplayValue.known("1", "a"), DisplayValue.known("1", "a"));
        assertNotEquals(DisplayValue.known("1", "a"), DisplayValue.estimate("1", "a"));
    }
}
